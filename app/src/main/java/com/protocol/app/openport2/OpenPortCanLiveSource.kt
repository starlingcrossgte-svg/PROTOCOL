package com.protocol.app.openport2

import com.protocol.app.obdlink.LiveSampleSource
import com.protocol.app.obdlink.ObdLinkSsm2Can
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * SSM2-over-CAN live polling source for the OpenPort 2.0 adapter (USB or TCP).
 *
 * Mirrors the OBDLink CAN path's "one address per request" pattern because
 * batching every address into one A8 payload would force a multi-frame
 * ISO-TP transmit, which doesn't fit the OpenPort's single-frame CAN
 * transmit path on the diagnostic IDs.
 *
 * Wire protocol (observed on the wire):
 *
 * Channel open:
 *   ati                                                no reqid
 *   ata 2
 *   ato6 0 500000 0                                    open CAN ch6 at 500 kbps
 *   ats6 3 0    / ats6 30 0 / ats6 31 0
 *   ats6 34 65535 / ats6 35 65535 / ats6 37 0          IOCTLs
 *   atf6 3 64 4   + [FFFFFFFF | 000007E8 | 000007E0]   flow filter, rx 7E8, fc 7E0
 *
 * Per-poll cycle (per address):
 *   att6 9 64 2000000 <reqid> + [000007E0 | A8 00 <hi mid lo>]
 *   ar6 <len> 40 <ts4> [000007E8 | E8 <data byte>]
 */
class OpenPortCanLiveSource(
    private val io: TactrixIo,
    pids: List<Ssm2Pid>
) : LiveSampleSource {

    @Volatile
    private var ecmPids: List<Ssm2Pid> = pids.filter { it.category == Ssm2PidCategory.ECU }

    private val nextReqId = AtomicInteger(2)
    private var channelOpened = false

    override fun initChannel(): Boolean {
        if (channelOpened) return true
        io.drain()

        write("ati\r\n")
        readAck(1500L, -1)

        if (!command("ata", 1500L)) return false
        if (!command("ato$CHANNEL 0 $BAUD 0")) return false
        if (!command("ats$CHANNEL 3 0")) return false
        if (!command("ats$CHANNEL 30 0")) return false
        if (!command("ats$CHANNEL 31 0")) return false
        if (!command("ats$CHANNEL 34 65535")) return false
        if (!command("ats$CHANNEL 35 65535")) return false
        if (!command("ats$CHANNEL 37 0")) return false

        val filterTail = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            CAN_ID_RESPONSE + CAN_ID_REQUEST
        if (!commandBinary("atf$CHANNEL 3 $TX_FLAGS 4", filterTail)) return false

        channelOpened = true
        return true
    }

    override fun updatePids(pids: List<Ssm2Pid>) {
        ecmPids = pids.filter { it.category == Ssm2PidCategory.ECU }
    }

    override fun close() {
        channelOpened = false
    }

    override fun startFlow(intervalMs: Long): Flow<PollSample> = flow {
        if (!initChannel()) return@flow
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
        val raw = IntArray(addresses.size)
        var anyOk = false

        for ((i, addr) in addresses.withIndex()) {
            val ssm2Payload = ObdLinkSsm2Can.buildReadPayload(listOf(addr))
            val tail = CAN_ID_REQUEST + ssm2Payload
            val reqId = nextReqId.getAndIncrement()
            val asciiLine = "att$CHANNEL ${tail.size} $TX_FLAGS $DEFAULT_TX_TIMEOUT_MICROS $reqId\r\n"
            val packet = asciiLine.toByteArray(StandardCharsets.US_ASCII) + tail

            try {
                io.write(packet)
                val rr = io.readUntil(2000L) { buf -> extractVehicleData(buf) != null }
                val data = extractVehicleData(rr.bytes)
                if (data != null && data.size >= 2 && (data[0].toInt() and 0xFF) == 0xE8) {
                    raw[i] = data[1].toInt() and 0xFF
                    anyOk = true
                }
            } catch (e: UsbDisconnectedException) {
                channelOpened = false
                return if (anyOk) buildSample(pids, raw, wireStart) else null
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

    private fun write(ascii: String) {
        io.write(ascii.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun command(body: String, timeoutMs: Long = 1500L): Boolean {
        val reqId = nextReqId.getAndIncrement()
        write("$body $reqId\r\n")
        return readAck(timeoutMs, reqId)
    }

    private fun commandBinary(body: String, tail: ByteArray, timeoutMs: Long = 1500L): Boolean {
        val reqId = nextReqId.getAndIncrement()
        val ascii = "$body $reqId\r\n".toByteArray(StandardCharsets.US_ASCII)
        val packet = ByteArray(ascii.size + tail.size)
        System.arraycopy(ascii, 0, packet, 0, ascii.size)
        System.arraycopy(tail, 0, packet, ascii.size, tail.size)
        io.write(packet)
        return readAck(timeoutMs, reqId)
    }

    private fun readAck(timeoutMs: Long, reqId: Int): Boolean =
        io.readUntil(timeoutMs) { buf -> bufHasAck(buf, reqId) }.matched

    private fun bufHasAck(buf: ByteArray, reqId: Int): Boolean {
        val s = String(buf, StandardCharsets.US_ASCII)
        if (reqId < 0) return s.contains("ar")
        val needle = " $reqId\r\n"
        var from = 0
        while (true) {
            val i = s.indexOf("ar", from)
            if (i < 0) return false
            val end = s.indexOf("\r\n", i)
            if (end > i && s.substring(i, end + 2).endsWith(needle)) return true
            from = i + 2
        }
    }

    private fun extractVehicleData(buf: ByteArray): ByteArray? {
        val marker = "ar$CHANNEL".toByteArray(StandardCharsets.US_ASCII)
        var pos = 0
        while (pos <= buf.size - marker.size) {
            val at = indexOf(buf, marker, pos) ?: break
            val lenIdx = at + marker.size
            if (lenIdx >= buf.size) break
            val len = buf[lenIdx].toInt() and 0xFF
            val flagsIdx = lenIdx + 1
            val frameEnd = flagsIdx + len
            if (len < AR6_HEADER_LEN || frameEnd > buf.size) {
                pos = lenIdx
                continue
            }
            val flags = buf[flagsIdx].toInt() and 0xFF
            val canIdStart = flagsIdx + 1 + 4
            val canId = buf.copyOfRange(canIdStart, canIdStart + 4)
            val dataStart = canIdStart + 4
            if (flags == RX_FLAG_VEHICLE && canId.contentEquals(CAN_ID_RESPONSE) && dataStart < frameEnd) {
                return buf.copyOfRange(dataStart, frameEnd)
            }
            pos = frameEnd
        }
        return null
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
        if (needle.isEmpty() || from > haystack.size - needle.size) return null
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }

    companion object {
        const val CHANNEL = 6
        const val BAUD = 500_000

        val CAN_ID_REQUEST = byteArrayOf(0x00, 0x00, 0x07, 0xE0.toByte())
        val CAN_ID_RESPONSE = byteArrayOf(0x00, 0x00, 0x07, 0xE8.toByte())

        private const val TX_FLAGS = 64
        private const val DEFAULT_TX_TIMEOUT_MICROS = 2_000_000L
        private const val RX_FLAG_VEHICLE = 0x40
        private const val AR6_HEADER_LEN = 9
    }
}
