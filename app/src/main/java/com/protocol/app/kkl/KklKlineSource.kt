package com.protocol.app.kkl

import com.protocol.app.obdlink.LiveSampleSource
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
 * [LiveSampleSource] for SSM2 over raw K-line through a dumb VAG-KKL cable
 * (FT232RL), driven by [KklKlineManager].
 *
 * Identical full-frame A8 batching and ECM (0x10) + TCM (0x18) split as the
 * OBDLink K-line source — but the wire I/O is our own software half-duplex
 * [KklKlineManager.transact] (write + echo-skip + read) instead of the STN's
 * STPX. The SSM2 frame build / parse / decode is shared verbatim with the rest
 * of the app ([Ssm2AddressQuery] / [Ssm2Pid]).
 *
 * ## Continuous mode (A8 flag 0x01)
 *
 * With [continuous] true and an ECM-only page, this arms the ECU's own stream
 * with a single A8 01 request, then reads the raw UART back-to-back, framing
 * each `80 F0 10 …` reply out of a rolling byte buffer and emitting a sample per
 * frame — the dumb-cable equivalent of the STN's STMA ride. Because the phone IS
 * the master here, there is no transaction layer to abort, so (like KKL polling)
 * none of the STN's FB-ERROR re-arm holes apply; we only re-arm if the ECU goes
 * quiet. K-line is half-duplex single-module, so streaming is ECM-only — a page
 * with TCM PIDs falls back to the polling loop below.
 */
class KklKlineSource(
    private val manager: KklKlineManager,
    pids: List<Ssm2Pid>,
    /** When true and the page is ECM-only, STREAM via one A8 01 instead of
     *  transacting each cycle (POLLING MODE → Stream). */
    private val continuous: Boolean = false
) : LiveSampleSource {

    @Volatile private var ecmPids: List<Ssm2Pid> = pids.filter { it.category == Ssm2PidCategory.ECU }
    @Volatile private var tcmPids: List<Ssm2Pid> = pids.filter { it.category == Ssm2PidCategory.TCM }

    override fun initChannel(): Boolean = manager.isConnected()

    override fun updatePids(pids: List<Ssm2Pid>) {
        ecmPids = pids.filter { it.category == Ssm2PidCategory.ECU }
        tcmPids = pids.filter { it.category == Ssm2PidCategory.TCM }
    }

    override fun close() {}

    override fun startFlow(intervalMs: Long): Flow<PollSample> = flow {
        // Continuous streaming (A8 01), ECM-only: arm the ECU's own stream once,
        // then read the raw UART and emit a sample per framed reply. No per-cycle
        // request transmit, so we shed ~70% of the K-line wire time the same way
        // the OBDLink STMA path does — but without an STN, the phone reads bytes
        // directly. Re-arm only if the ECU goes quiet.
        if (continuous && tcmPids.isEmpty() && ecmPids.isNotEmpty()) {
            val streamPids = ecmPids
            val addresses = streamPids.flatMap { it.addresses }
            val addrCount = addresses.size
            val a8 = Ssm2AddressQuery.buildA8Query(addresses, Ssm2AddressQuery.DEST_ECM, flags = 0x01)

            var pending = ByteArray(0)
            val tmp = ByteArray(512)
            var idleMs = 0L
            var stalls = 0

            if (!manager.armStream(a8)) throw NoEcuResponseException("KKL stream: arm write failed")
            while (true) {
                val n = manager.readAvailable(tmp)
                if (n < 0) throw NoEcuResponseException("KKL stream: link closed")
                if (n == 0) {
                    idleMs += STREAM_POLL_MS
                    delay(STREAM_POLL_MS)
                    if (idleMs >= STREAM_STALL_MS) {
                        stalls++
                        if (stalls >= MAX_CONSECUTIVE_NO_REPLY) {
                            throw NoEcuResponseException("KKL stream: stalled, no replies")
                        }
                        manager.armStream(a8)          // ECU went quiet → restart it
                        pending = ByteArray(0)
                        idleMs = 0
                    }
                    continue
                }
                idleMs = 0
                pending = if (pending.isEmpty()) tmp.copyOf(n) else pending + tmp.copyOf(n)
                val (frames, consumed) = extractStreamFrames(pending, addrCount)
                if (consumed > 0) pending = pending.copyOfRange(consumed, pending.size)
                if (frames.isNotEmpty()) {
                    stalls = 0
                    for (fr in frames) emit(buildEcmSample(streamPids, fr))
                }
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
                consecutiveNoReply++
                if (consecutiveNoReply >= MAX_CONSECUTIVE_NO_REPLY) {
                    throw NoEcuResponseException(
                        "No ECU response after $MAX_CONSECUTIVE_NO_REPLY KKL cycles"
                    )
                }
            }
            // intervalMs is a MINIMUM period: the K-line round trip already paces
            // us, so only sleep the remainder.
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
        if ((!ecmRequired || ecmFailed) && (!tcmRequired || tcmFailed)) return null

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

    /** Send one A8 read to [destination] (0x10 ECM / 0x18 TCM) over the raw
     *  K-line and return one unsigned byte per address, or null on no/invalid
     *  reply. The reply's source byte mirrors our destination. */
    private fun queryModule(addresses: List<Ssm2Address>, destination: Byte): IntArray? {
        if (addresses.isEmpty()) return null
        val frame = Ssm2AddressQuery.buildA8Query(addresses, destination)
        val reply = manager.transact(
            frame, replySource = destination.toInt() and 0xFF, timeoutMs = RESPONSE_TIMEOUT_MS
        ) ?: return null
        val parsed = Ssm2FrameParser.parseSsm2Frame(reply) ?: return null
        return Ssm2AddressQuery.parseA8Response(parsed, addresses.size)
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

    /** Build an ECM-only [PollSample] from one streamed frame's address bytes. */
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

    /**
     * Pull every COMPLETE ECM reply frame (`80 F0 10 LL E8 …<cs>`) out of the
     * front of [buf] (the rolling raw-byte stream), parse each to one byte per
     * address, and report how many bytes were consumed so the caller can drop
     * them and keep any partial trailing frame for the next read. Walks past the
     * single request echo (`80 10 F0 …`) since its header isn't `80 F0 10`. A
     * frame whose length byte is wrong resyncs on the next header instead of
     * letting a corrupt length swallow good data.
     */
    private fun extractStreamFrames(buf: ByteArray, addressCount: Int): Pair<List<IntArray>, Int> {
        val out = ArrayList<IntArray>()
        var pos = 0
        while (true) {
            var h = -1
            var i = pos
            while (i <= buf.size - 3) {
                if ((buf[i].toInt() and 0xFF) == 0x80 &&
                    (buf[i + 1].toInt() and 0xFF) == 0xF0 &&
                    (buf[i + 2].toInt() and 0xFF) == 0x10
                ) { h = i; break }
                i++
            }
            // No header in the remainder: consume up to it, keeping at most a
            // 2-byte tail that could be the start of a split header.
            if (h < 0) return Pair(out, maxOf(pos, buf.size - 2))
            if (h + 4 > buf.size) return Pair(out, h)        // need 80 F0 10 LL
            val len = buf[h + 3].toInt() and 0xFF
            if (len != addressCount + 1) { pos = h + 1; continue }  // bad length → resync
            val total = len + 5
            if (h + total > buf.size) return Pair(out, h)    // frame not fully arrived yet
            val frame = buf.copyOfRange(h, h + total)
            val parsed = Ssm2FrameParser.parseSsm2Frame(frame)
            if (parsed != null && !parsed.truncated && parsed.checksumValid) {
                Ssm2AddressQuery.parseA8Response(parsed, addressCount)?.let { out.add(it) }
            }
            pos = h + total
        }
    }

    private companion object {
        private const val RESPONSE_TIMEOUT_MS = 400L
        private const val MAX_CONSECUTIVE_NO_REPLY = 8
        // Continuous stream: how often to poll the UART when idle, and how long
        // with no bytes before we conclude the ECU stopped streaming and re-arm.
        private const val STREAM_POLL_MS = 5L
        private const val STREAM_STALL_MS = 250L
    }
}
