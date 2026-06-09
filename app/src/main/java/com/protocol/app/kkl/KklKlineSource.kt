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
 * STPX. There is no continuous / monitor mode: a dumb cable has no on-board
 * monitor, so we transact each cycle. The SSM2 frame build / parse / decode is
 * shared verbatim with the rest of the app ([Ssm2AddressQuery] / [Ssm2Pid]).
 */
class KklKlineSource(
    private val manager: KklKlineManager,
    pids: List<Ssm2Pid>
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

    private companion object {
        private const val RESPONSE_TIMEOUT_MS = 400L
        private const val MAX_CONSECUTIVE_NO_REPLY = 8
    }
}
