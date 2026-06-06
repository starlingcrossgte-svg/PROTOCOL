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
 * Continuous mode (A8 flag 0x01) was tried on-car and does NOT work over the
 * STN: the flag hangs the adapter's one-shot tx/rx state machine (no prompt),
 * which then storms STOPPED / BUS ERROR. Continuous stays an OpenPort-only
 * capability.
 */
class ObdLinkKlineSource(
    private val transport: ObdLinkBtTransport,
    pids: List<Ssm2Pid>
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

    private companion object {
        // STPX response-wait cap (ms). r:1 returns as soon as the ECU's single
        // reply lands, so this only bounds the no-reply case.
        private const val RESPONSE_TIMEOUT_MS = 300
        // Consecutive no-reply cycles before declaring "no ECU". A present ECU
        // answers the first cycle, so a run this long = not on bus.
        private const val MAX_CONSECUTIVE_NO_REPLY = 8
    }
}
