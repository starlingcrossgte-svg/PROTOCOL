package com.protocol.app.obdlink

import com.protocol.app.openport2.NoEcuResponseException
import com.protocol.app.openport2.PollSample
import com.protocol.app.openport2.Ssm2Pid
import com.protocol.app.openport2.Ssm2PidCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [LiveSampleSource] backed by the OBDLink MX+ over Bluetooth (SSM2-over-CAN).
 *
 * Each cycle: build the raw A8 read payload for the on-page addresses, send it
 * as ELM hex over [ObdLinkBtTransport] (the adapter wraps it in ISO-TP to
 * 0x7E0 and reassembles the 0x7E8 reply), parse the E8 response, and decode
 * per-PID into a [PollSample] — identical in shape to the USB poller's output,
 * so the gauges and session log don't care which adapter produced it.
 *
 * Scope: ECM (0x7E0/0x7E8) only for now. TCM-over-CAN uses a different CAN ID
 * and is a later add (the ELM header would switch per module); TCM PIDs are
 * filtered out here so a mixed layout still reads its ECM gauges.
 */
class ObdLinkLiveSource(
    private val transport: ObdLinkBtTransport,
    pids: List<Ssm2Pid>
) : LiveSampleSource {

    @Volatile
    private var ecmPids: List<Ssm2Pid> = pids.filter { it.category == Ssm2PidCategory.ECU }

    private var consecutiveNoReply = 0

    /** The ELM channel is already set up by [ObdLinkBtManager.connect]. */
    override fun initChannel(): Boolean = true

    override fun updatePids(pids: List<Ssm2Pid>) {
        ecmPids = pids.filter { it.category == Ssm2PidCategory.ECU }
    }

    override fun close() {}

    override fun startFlow(intervalMs: Long): Flow<PollSample> = flow {
        consecutiveNoReply = 0
        while (true) {
            pollOnce()?.let { emit(it) }
            if (intervalMs > 0) delay(intervalMs)
        }
    }

    private fun pollOnce(): PollSample? {
        val pids = ecmPids
        val addresses = pids.flatMap { it.addresses }
        if (addresses.isEmpty()) return null

        val wireStart = System.currentTimeMillis()

        // One address per request so each is a single CAN frame
        // (A8 00 + 3-byte addr = 5 bytes <= 7). Batching every address into one
        // A8 forces a multi-frame ISO-TP *send*, which ELM/STN rejects with '?'.
        // Single-frame reads are reliable; we trade speed for correctness here.
        val raw = IntArray(addresses.size)
        var anyOk = false
        for ((i, addr) in addresses.withIndex()) {
            val reqHex = ObdLinkSsm2Can.toElmHex(ObdLinkSsm2Can.buildReadPayload(listOf(addr)))
            val ascii = try {
                transport.sendAscii(reqHex, timeoutMs = 600L)
            } catch (e: Exception) {
                return if (anyOk) buildSample(pids, raw, wireStart) else null
            }
            val one = ObdLinkSsm2Can.parseReadResponse(ObdLinkSsm2Can.parseElmHex(ascii), 1)
            if (one != null) {
                raw[i] = one[0]
                anyOk = true
                consecutiveNoReply = 0
            } else {
                // NO DATA / '?' — no ECU reply for this address. After a
                // sustained run, conclude the ECU isn't on the bus.
                consecutiveNoReply++
                if (consecutiveNoReply >= MAX_CONSECUTIVE_NO_REPLY) {
                    throw NoEcuResponseException(
                        "No ECU response after $MAX_CONSECUTIVE_NO_REPLY consecutive requests"
                    )
                }
            }
        }
        if (!anyOk) return null
        return buildSample(pids, raw, wireStart)
    }

    private fun buildSample(pids: List<Ssm2Pid>, raw: IntArray, wireStart: Long): PollSample {
        val values = HashMap<String, Double>()
        val rawValues = ArrayList<Int>(raw.size)
        var offset = 0
        for (pid in pids) {
            val slice = raw.copyOfRange(offset, offset + pid.addresses.size)
            values[pid.id] = pid.decode(slice)
            rawValues.addAll(slice.toList())
            offset += pid.addresses.size
        }
        return PollSample(
            timestampMs = System.currentTimeMillis(),
            values = values,
            rawValues = rawValues.toIntArray(),
            wireMs = System.currentTimeMillis() - wireStart,
            ecmOk = true,
            tcmOk = true
        )
    }

    private companion object {
        // Consecutive no-reply requests before declaring "no ECU". A present
        // ECU answers the first request, so a run this long = not on bus.
        private const val MAX_CONSECUTIVE_NO_REPLY = 8
    }
}
