package com.protocol.app.obdlink

import com.protocol.app.openport2.PollSample
import com.protocol.app.openport2.Ssm2AddressQuery
import com.protocol.app.openport2.Ssm2FrameParser
import com.protocol.app.openport2.Ssm2Pid
import com.protocol.app.openport2.Ssm2PidCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [LiveSampleSource] for SSM2 over **K-line** through the OBDLink MX+ — the
 * EZ30R / Outback 3.0R path. Over K-line the FULL SSM2 frame is sent on the
 * wire, header + checksum and all:
 *   send  80 10 F0 <len> A8 00 <3-byte addrs...> <checksum>
 *   recv  80 F0 10 <len> E8 <one byte per addr...> <checksum>
 *
 * Reuses the existing K-line SSM2 builder/parser ([Ssm2AddressQuery],
 * [Ssm2FrameParser]) — they already produce/consume exactly that wire format
 * (it's what the OpenPort K-line path uses). The only new bit is shipping it
 * as ELM hex over the Bluetooth transport.
 *
 * K-line has no 8-byte CAN single-frame limit, so unlike the CAN source we
 * batch ALL addresses into ONE A8 frame per cycle — far fewer round trips.
 *
 * Assumes the adapter is already in raw K-line mode (the init the auto-prober
 * discovers / we replay). ECM only for now.
 */
class ObdLinkKlineSource(
    private val transport: ObdLinkBtTransport,
    pids: List<Ssm2Pid>
) : LiveSampleSource {

    @Volatile
    private var ecmPids: List<Ssm2Pid> = pids.filter { it.category == Ssm2PidCategory.ECU }

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
        val frame = Ssm2AddressQuery.buildA8Query(addresses, Ssm2AddressQuery.DEST_ECM)
        val ascii = try {
            transport.sendAscii(ObdLinkSsm2Can.toElmHex(frame), timeoutMs = 1500L)
        } catch (e: Exception) {
            return null
        }
        val replyBytes = ObdLinkSsm2Can.parseElmHex(ascii)
        val ssm2Frame = extractSsm2Reply(replyBytes) ?: return null
        val parsed = Ssm2FrameParser.parseSsm2Frame(ssm2Frame) ?: return null
        val raw = Ssm2AddressQuery.parseA8Response(parsed, addresses.size) ?: return null

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
            wireMs = System.currentTimeMillis() - wireStart,
            ecmOk = true,
            tcmOk = true
        )
    }

    /** Locate the SSM2 reply frame (header 80 F0 10) inside the adapter's reply. */
    private fun extractSsm2Reply(bytes: ByteArray): ByteArray? {
        for (i in 0..bytes.size - 3) {
            if ((bytes[i].toInt() and 0xFF) == 0x80 &&
                (bytes[i + 1].toInt() and 0xFF) == 0xF0 &&
                (bytes[i + 2].toInt() and 0xFF) == 0x10
            ) {
                return bytes.copyOfRange(i, bytes.size)
            }
        }
        return null
    }
}
