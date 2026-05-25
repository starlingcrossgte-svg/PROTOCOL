package com.protocol.app.obdlink

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

    /** The ELM channel is already set up by [ObdLinkBtManager.connect]. */
    override fun initChannel(): Boolean = true

    override fun updatePids(pids: List<Ssm2Pid>) {
        ecmPids = pids.filter { it.category == Ssm2PidCategory.ECU }
    }

    override fun close() {}

    override fun startFlow(intervalMs: Long): Flow<PollSample> = flow {
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
        val requestHex = ObdLinkSsm2Can.toElmHex(ObdLinkSsm2Can.buildReadPayload(addresses))
        val ascii = try {
            transport.sendAscii(requestHex, timeoutMs = 1000L)
        } catch (e: Exception) {
            return null // dropped link / I/O — treated as a miss; manager handles reconnect
        }
        val replyBytes = ObdLinkSsm2Can.parseElmHex(ascii)
        val raw = ObdLinkSsm2Can.parseReadResponse(replyBytes, addresses.size) ?: return null
        val wireMs = System.currentTimeMillis() - wireStart

        val values = HashMap<String, Double>()
        val rawValues = ArrayList<Int>(addresses.size)
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
            wireMs = wireMs,
            ecmOk = true,
            tcmOk = true
        )
    }
}
