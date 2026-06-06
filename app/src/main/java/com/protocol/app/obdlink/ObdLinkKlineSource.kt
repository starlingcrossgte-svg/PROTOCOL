package com.protocol.app.obdlink

import com.protocol.app.openport2.NoEcuResponseException
import com.protocol.app.openport2.PollSample
import com.protocol.app.openport2.Ssm2Address
import com.protocol.app.openport2.Ssm2AddressQuery
import com.protocol.app.openport2.Ssm2FrameParser
import com.protocol.app.openport2.Ssm2Pid
import com.protocol.app.openport2.Ssm2PidCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [LiveSampleSource] for SSM2 over **K-line** through the OBDLink MX+ — the
 * EZ30R / Outback 3.0R path. The full SSM2 frame (header + checksum) goes on
 * the wire verbatim:
 *   send  80 <dest> F0 <len> A8 00 <3-byte addrs...> <checksum>
 *   recv  80 F0 <dest> <len> E8 <one byte per addr...> <checksum>
 *
 * ## Multi-module (ECM + TCM)
 *
 * K-line is half-duplex and single-module: one batched A8 request targets one
 * module's address space at a time. When the Live Data page mixes ECM and TCM
 * parameters this source issues TWO STPX exchanges per cycle — ECM (dest 0x10,
 * reply 80 F0 10) then TCM (dest 0x18, reply 80 F0 18) — exactly like the
 * OpenPort poller. A module with no PIDs on the page is skipped. If one module
 * answers and the other doesn't, the responding module's gauges still update
 * and the missing ones hold their last value (the ViewModel merges partial
 * samples).
 *
 * ## Transmit via STPX (single response)
 *
 * Each query is sent with the STN's `STPX` send-and-receive command:
 *   STPX d:<frame hex>,r:1,t:300
 * `r:1` (expected response count) makes the adapter return **the instant the
 * ECU's one reply arrives** instead of padding out the response timeout. We
 * still wait for the `>` prompt every cycle (sendAscii does), so there is no
 * STOPPED risk; we just make `>` come sooner. Auto-checksum is already off via
 * `STIMCS 1` in the init (STIMCS == STPCB), so STPX transmits our
 * self-checksummed frame verbatim and doesn't verify the reply's checksum (we
 * do, in [extractLatestA8]).
 *
 * ## Continuous mode (A8 flag 0x01) via monitor
 *
 * The A8 01 flag tells the ECU to RESPOND CONTINUOUSLY — once it receives one
 * A8 01 it streams E8 frames on the K-line by itself, indefinitely. We send A8
 * 01 once (STPX r:1, to arm it and grab the first frame), then hand the wire to
 * the STN's all-frame monitor (STMA) and ride the stream, emitting a sample per
 * frame as it arrives. A repeated STPX r:N can't do this smoothly: it's a
 * transaction that wants to "complete", so on an endless stream the STN aborts
 * each burst (FB ERROR) and we re-arm, leaving a ~200 ms hole every ~10 frames.
 * The monitor has no transaction — it just forwards the wire — so the stream is
 * gap-free. Needs the tight STIP1X timing from the "K-line · Continuous" init.
 */
class ObdLinkKlineSource(
    private val transport: ObdLinkBtTransport,
    pids: List<Ssm2Pid>,
    /** When true (the "K-line · Continuous" init is active), ECM-only pages
     *  STREAM via one A8 01 request instead of re-asking each cycle. */
    private val continuous: Boolean = false
) : LiveSampleSource {

    @Volatile
    private var ecmPids: List<Ssm2Pid> = pids.filter { it.category == Ssm2PidCategory.ECU }

    @Volatile
    private var tcmPids: List<Ssm2Pid> = pids.filter { it.category == Ssm2PidCategory.TCM }

    override fun initChannel(): Boolean = true

    override fun updatePids(pids: List<Ssm2Pid>) {
        ecmPids = pids.filter { it.category == Ssm2PidCategory.ECU }
        tcmPids = pids.filter { it.category == Ssm2PidCategory.TCM }
    }

    override fun close() {}

    override fun startFlow(intervalMs: Long): Flow<PollSample> = flow {
        // Continuous streaming (A8 01), ECM-only: arm the ECU's OWN stream once,
        // then ride it via the STN's all-frame monitor (STMA) and emit a sample
        // per streamed reply (~35-40 Hz on-car). No per-cycle re-asking, and —
        // unlike a repeated STPX r:N — no transaction to abort (FB ERROR) and
        // re-arm, so no ~200 ms holes. We only re-arm if the stream truly stalls
        // or the monitor dies. Needs the tight STIP1X timing from the
        // "K-line · Continuous" init.
        if (continuous && tcmPids.isEmpty() && ecmPids.isNotEmpty()) {
            val pids = ecmPids
            val addresses = pids.flatMap { it.addresses }
            val a8 = Ssm2AddressQuery.buildA8Query(addresses, Ssm2AddressQuery.DEST_ECM, flags = 0x01)
            // STPX r:1: send A8 01 once and return on the FIRST streamed frame —
            // that both confirms the ECU is now streaming and hands us frame #1.
            val arm = "STPX d:${ObdLinkSsm2Can.toElmHex(a8)},r:1,t:$CONTINUOUS_ARM_MS"
            val pending = StringBuilder()

            // Arm the ECU stream (grabs frame #1, leaves it buffered), then start
            // the monitor. beginMonitor flips the transport into no-'>'-expected
            // state; stopMonitor (inside, via the next sendAscii) sends the 0x0D.
            fun armAndMonitor() {
                pending.setLength(0)
                transport.stopMonitor()                              // no-op if not monitoring
                transport.sendAscii(arm, timeoutMs = CONTINUOUS_ARM_MS + 500L)
                transport.beginMonitor(MONITOR_CMD)                  // STMA — no '>' until 0x0D
            }
            armAndMonitor()

            var idleMs = 0L
            var stalls = 0
            try {
                while (true) {
                    kotlinx.coroutines.delay(STREAM_POLL_MS)
                    val raw = transport.takeBuffered()
                    if (raw.isEmpty()) {
                        idleMs += STREAM_POLL_MS
                        if (idleMs >= STREAM_STALL_MS) {
                            stalls++
                            if (stalls >= MAX_CONSECUTIVE_NO_REPLY) {
                                throw NoEcuResponseException("continuous: stream stalled")
                            }
                            armAndMonitor()                          // ECU went quiet → restart it
                            idleMs = 0
                        }
                        continue
                    }
                    idleMs = 0
                    // The monitor died (stray byte / bus glitch) if the STN printed
                    // STOPPED / an error / a '?'. Emit whatever good frames came
                    // first, then re-arm.
                    val died = raw.contains("STOPPED") || raw.contains("ERROR") ||
                        raw.contains("?")
                    // Keep only hex; integrity (header + length + checksum) is
                    // validated per frame in extractFramesFromHex.
                    for (c in raw) if (c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f') pending.append(c)
                    val frames = extractFramesFromHex(pending, addresses.size)
                    if (frames.isNotEmpty()) {
                        stalls = 0
                        for (fr in frames) emit(buildEcmSample(pids, fr))
                    }
                    if (died) armAndMonitor()
                }
            } finally {
                // Leave the channel clean for the next command / disconnect.
                transport.stopMonitor()
            }
        }

        var consecutiveNoReply = 0
        while (true) {
            val cycleStart = System.currentTimeMillis()
            val sample = pollOnce()
            if (sample != null) {
                consecutiveNoReply = 0
                emit(sample)
            } else if (ecmPids.isNotEmpty() || tcmPids.isNotEmpty()) {
                // PIDs selected but no module answered this cycle (NO DATA /
                // BUS ERROR / timeout). A sustained run = ECU not answering.
                consecutiveNoReply++
                if (consecutiveNoReply >= MAX_CONSECUTIVE_NO_REPLY) {
                    throw NoEcuResponseException(
                        "No ECU response after $MAX_CONSECUTIVE_NO_REPLY consecutive cycles"
                    )
                }
            }
            // intervalMs is a MINIMUM period (idle floor), NOT additive idle:
            // the K-line round trip already paces us, so only sleep the
            // remainder. Wire time ~0 on the simulator, so the full interval is
            // still honored there.
            val remaining = intervalMs - (System.currentTimeMillis() - cycleStart)
            if (remaining > 0) delay(remaining)
        }
    }

    private fun pollOnce(): PollSample? {
        val ecm = ecmPids
        val tcm = tcmPids
        if (ecm.isEmpty() && tcm.isEmpty()) return null

        val wireStart = System.currentTimeMillis()

        val ecmBytes = if (ecm.isNotEmpty())
            queryModule(ecm.flatMap { it.addresses }, Ssm2AddressQuery.DEST_ECM) else null
        val tcmBytes = if (tcm.isNotEmpty())
            queryModule(tcm.flatMap { it.addresses }, Ssm2AddressQuery.DEST_TCM) else null

        val ecmRequired = ecm.isNotEmpty()
        val tcmRequired = tcm.isNotEmpty()
        val ecmFailed = ecmRequired && ecmBytes == null
        val tcmFailed = tcmRequired && tcmBytes == null
        // Every required module failed this cycle → whole-cycle miss so the
        // no-reply counter can trip. (At least one module is required here.)
        if ((!ecmRequired || ecmFailed) && (!tcmRequired || tcmFailed)) return null

        // Decode each responding module in page order (ECM then TCM). A missing
        // module's PIDs are simply omitted from `values` so the ViewModel merge
        // holds their last reading instead of zeroing the gauges.
        val values = HashMap<String, Double>()
        val rawValues = ArrayList<Int>()
        decodeInto(ecm, ecmBytes, values, rawValues)
        decodeInto(tcm, tcmBytes, values, rawValues)

        return PollSample(
            timestampMs = System.currentTimeMillis(),
            values = values,
            rawValues = rawValues.toIntArray(),
            wireMs = System.currentTimeMillis() - wireStart,
            ecmOk = !ecmFailed,
            tcmOk = !tcmFailed
        )
    }

    /** Decode one module's reply bytes into [values] / [rawValues], in PID order. */
    private fun decodeInto(
        pids: List<Ssm2Pid>,
        bytes: IntArray?,
        values: MutableMap<String, Double>,
        rawValues: MutableList<Int>
    ) {
        if (bytes == null) return
        var offset = 0
        for (pid in pids) {
            val end = offset + pid.addresses.size
            if (end > bytes.size) break
            val slice = bytes.copyOfRange(offset, end)
            values[pid.id] = pid.decode(slice)
            rawValues.addAll(slice.toList())
            offset = end
        }
    }

    /**
     * Send one STPX A8 read to [destination] (0x10 ECM / 0x18 TCM) and return
     * one unsigned byte per address, or null on no/invalid reply.
     */
    private fun queryModule(addresses: List<Ssm2Address>, destination: Byte): IntArray? {
        if (addresses.isEmpty()) return null
        val frame = Ssm2AddressQuery.buildA8Query(addresses, destination)
        // NOTE: do NOT add x:<len> here. It makes the STN do a strict ISO-9141
        // length check that rejects the valid SSM2 reply with "<DATA ERROR" (the
        // bytes are fine — verified on-car — but the STN flags them). r:1 already
        // returns the instant the one reply lands, which is the win we wanted.
        val command = "STPX d:${ObdLinkSsm2Can.toElmHex(frame)},r:1,t:$RESPONSE_TIMEOUT_MS"
        val ascii = try {
            transport.sendAscii(command, timeoutMs = 1500L)
        } catch (e: Exception) {
            return null
        }
        // The reply's source byte mirrors our destination (80 F0 10 for ECM,
        // 80 F0 18 for TCM) — match on it so we don't decode the wrong module.
        return extractLatestA8(
            ObdLinkSsm2Can.parseElmHex(ascii), addresses.size, destination.toInt() and 0xFF
        )
    }

    /**
     * Scan [bytes] for SSM2 reply frames (header 80 F0 [replySource]) and
     * return the A8 data of the LAST complete, checksum-valid one — robust to
     * any leading status bytes or a stale frame ahead of the fresh reply. Null
     * if none.
     */
    private fun extractLatestA8(bytes: ByteArray, addressCount: Int, replySource: Int): IntArray? {
        var result: IntArray? = null
        var i = 0
        while (i <= bytes.size - 5) {
            if ((bytes[i].toInt() and 0xFF) == 0x80 &&
                (bytes[i + 1].toInt() and 0xFF) == 0xF0 &&
                (bytes[i + 2].toInt() and 0xFF) == replySource
            ) {
                val len = bytes[i + 3].toInt() and 0xFF
                val total = len + 5
                if (i + total <= bytes.size) {
                    val parsed = Ssm2FrameParser.parseSsm2Frame(bytes.copyOfRange(i, i + total))
                    if (parsed != null && !parsed.truncated && parsed.checksumValid) {
                        val raw = Ssm2AddressQuery.parseA8Response(parsed, addressCount)
                        if (raw != null) {
                            result = raw
                            i += total
                            continue
                        }
                    }
                }
            }
            i++
        }
        return result
    }

    /**
     * Pull every COMPLETE 80 F0 10 frame off the front of [pending] (a running
     * hex-char buffer for the continuous stream), parse each to one byte per
     * address, and consume it. Leaves any partial trailing frame for the next
     * chunk, so frames split across reads reassemble correctly.
     */
    private fun extractFramesFromHex(pending: StringBuilder, addressCount: Int): List<IntArray> {
        val out = ArrayList<IntArray>()
        while (true) {
            val start = pending.indexOf("80F010")
            if (start < 0) {
                // no header; keep at most a partial-header tail for next time
                if (pending.length > 6) pending.delete(0, pending.length - 6)
                break
            }
            if (start > 0) pending.delete(0, start)
            if (pending.length < 8) break // need 80 F0 10 LL to read the length
            val len = Integer.parseInt(pending.substring(6, 8), 16)
            // Length sanity: a valid E8 reply is LL = E8 byte + one byte per
            // address. A corrupt LL can't be trusted to frame the next packet, so
            // drop this header byte and re-sync on the following 80F010 instead of
            // letting a bad length swallow a good frame.
            if (len != addressCount + 1) {
                pending.delete(0, 2)
                continue
            }
            val totalChars = (len + 5) * 2
            if (pending.length < totalChars) break // frame not fully arrived yet
            val frameHex = pending.substring(0, totalChars)
            pending.delete(0, totalChars)
            val bytes = ByteArray(len + 5) {
                Integer.parseInt(frameHex.substring(it * 2, it * 2 + 2), 16).toByte()
            }
            val parsed = Ssm2FrameParser.parseSsm2Frame(bytes)
            if (parsed != null && !parsed.truncated && parsed.checksumValid) {
                Ssm2AddressQuery.parseA8Response(parsed, addressCount)?.let { out.add(it) }
            }
        }
        return out
    }

    /** Build an ECM-only [PollSample] from one streamed frame's raw bytes. */
    private fun buildEcmSample(pids: List<Ssm2Pid>, raw: IntArray): PollSample {
        val values = HashMap<String, Double>()
        val rawValues = ArrayList<Int>()
        decodeInto(pids, raw, values, rawValues)
        return PollSample(
            timestampMs = System.currentTimeMillis(),
            values = values,
            rawValues = rawValues.toIntArray(),
            wireMs = 0,
            ecmOk = true,
            tcmOk = true
        )
    }

    private companion object {
        // STPX response-wait cap (ms). r:1 returns as soon as the ECU's single
        // reply lands, so this only bounds the no-reply case.
        private const val RESPONSE_TIMEOUT_MS = 300
        // Consecutive no-reply cycles before declaring "no ECU". A present ECU
        // answers the first cycle, so a run this long = not on bus.
        private const val MAX_CONSECUTIVE_NO_REPLY = 8
        // Continuous mode: the STN monitor command that rides the ECU's stream,
        // and the t: cap on the one A8 01 arming request (r:1 returns on frame #1,
        // so this only bounds the no-reply case at arm time).
        private const val MONITOR_CMD = "STMA"
        private const val CONTINUOUS_ARM_MS = 1000
        // How often to drain the stream buffer, and how long with no bytes before
        // we conclude the stream stalled and re-arm A8 01.
        private const val STREAM_POLL_MS = 8L
        private const val STREAM_STALL_MS = 250L
    }
}
