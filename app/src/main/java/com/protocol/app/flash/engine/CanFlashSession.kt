package com.protocol.app.flash.engine

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * CAN (ISO15765) transport to the ECU over the Tactrix OpenPort 2.0, for the
 * flash silo. Reproduces the exact channel-open + transmit choreography decoded
 * from the EcuFlash bench ROM-read USBPcap capture (2008 SH7058S, CAN @ 500k).
 *
 * Zero shared code with the logging path — its own USB layer ([FlashBulkIo]),
 * its own command framing. This class only opens the channel and exchanges raw
 * payloads on the diagnostic CAN IDs; OBD/UDS request building lives above it.
 *
 * Channel-open sequence (verbatim from the capture, reqids start at 2):
 * ```
 *   ati                                            device bring-up (no reqid)
 *   ata 2                                          atomic activate
 *   atp 5 2 3            + [01 00]                 open protocol 5
 *   ato6 0 500000 894790475 <r>                    open ISO15765 ch6 @ 500 kbps
 *   ats6 22 0 <r>                                  IOCTL param
 *   atf6 3 64 4 <r>      + [FFFFFFFF 000007E8 000007E0]   flow-control filter
 *   atv 3 -2 <r>                                   line/voltage check
 *   att6 5 64 200000 <r> + [000007E0 01]           first tester contact (reply ignored)
 *   ats6 35 242 <r>                                ISO15765 IOCTL the capture set before reads began
 * ```
 * Transmit: `att6 <len> 64 <timeoutMicros> <r>` + `[000007E0 <payload>]`.
 * RX wrapper: `"ar6" + len(1) + flags(1) + ts(4) + canID(4) + data(len-9)`.
 * The ECU reply is flags 0x40 with canID 0x7E8 (the OpenPort reassembles
 * ISO-TP, so multi-byte OBD/UDS responses arrive whole). flags 0x10/0x80 and
 * canID 0x7E0 are our own transmit echo / flow markers and are ignored.
 *
 * This is the highest-fidelity starting point the capture allows; the exact
 * IOCTL set is expected to be confirmed/tuned against the bench ECU.
 */
class CanFlashSession(private val io: FlashBulkIo) {

    companion object {
        const val CHANNEL = 6
        const val BAUD = 500_000
        const val MAGIC = 894_790_475L

        // 4-byte CAN arbitration IDs, big-endian, exactly as they sit on the wire.
        val CAN_ID_REQUEST = byteArrayOf(0x00, 0x00, 0x07, 0xE0.toByte())   // 0x7E0 tester -> ECU
        val CAN_ID_RESPONSE = byteArrayOf(0x00, 0x00, 0x07, 0xE8.toByte())  // 0x7E8 ECU -> tester

        private const val TX_FLAGS = 64                 // ISO15765_FRAME_PAD (0x40)
        private const val DEFAULT_TX_TIMEOUT_MICROS = 500_000L
        private const val RX_FLAG_VEHICLE = 0x40        // ar6 flags byte for a frame received from the bus
        private const val AR6_HEADER_LEN = 9            // flags(1) + ts(4) + canID(4) counted in the len byte
    }

    private val nextReqId = AtomicInteger(2)

    sealed class SendResult {
        /** ECU replied; [data] is the reassembled OBD/UDS payload (service byte first). */
        data class Reply(val data: ByteArray) : SendResult()

        /** No vehicle frame arrived within the timeout / none recognized. */
        data class Miss(val reason: String) : SendResult()
    }

    /**
     * Runs the full channel-open. Returns null on success, or a human-readable
     * reason for the first step that failed.
     */
    fun openChannel(): String? {
        io.drain()

        // 1. ati — bring-up; no reqid, response is the version line.
        io.writeAsciiLine("ati")
        readAck(1500L)

        // 2. ata 2
        if (!command("ata", ackTimeoutMs = 1500L)) return "ata (activate) failed"
        // 3. atp 5 2 3 + [01 00]
        if (!commandBinary("atp 5 2", byteArrayOf(0x01, 0x00))) return "atp 5 (open protocol) failed"
        // 4. ato6 0 500000 894790475 <r>
        if (!command("ato$CHANNEL 0 $BAUD $MAGIC")) return "ato6 (open CAN channel) failed"
        // 5. ats6 22 0 <r>
        if (!command("ats$CHANNEL 22 0")) return "ats6 22 0 (ioctl) failed"
        // 6. atf6 3 64 4 <r> + [mask=FFFFFFFF | pattern=7E8 | flowControl=7E0]
        val filterTail = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
            CAN_ID_RESPONSE + CAN_ID_REQUEST
        if (!commandBinary("atf$CHANNEL 3 $TX_FLAGS 4", filterTail)) return "atf6 (flow-control filter) failed"
        // 7. atv 3 -2 <r>
        if (!command("atv 3 -2")) return "atv (line check) failed"

        // Priming, replicated from the capture: a first tester contact (the ECU
        // does not reply to this), then the ISO15765 IOCTL the capture issued
        // before real reads started returning data.
        send(byteArrayOf(0x01), timeoutMicros = 200_000L, readTimeoutMs = 500L)
        if (!command("ats$CHANNEL 35 242")) return "ats6 35 242 (iso15765 ioctl) failed"

        return null
    }

    /**
     * Sends an OBD/UDS [payload] to the request CAN ID and waits for the ECU
     * reply on the response CAN ID.
     */
    fun send(
        payload: ByteArray,
        timeoutMicros: Long = DEFAULT_TX_TIMEOUT_MICROS,
        readTimeoutMs: Long = 2000L
    ): SendResult {
        val tail = CAN_ID_REQUEST + payload
        val reqId = nextReqId.getAndIncrement()
        io.writeAsciiPlusBinary("att$CHANNEL ${tail.size} $TX_FLAGS $timeoutMicros $reqId", tail)
        val rr = io.readUntil(readTimeoutMs) { buf -> extractVehicleData(buf) != null }
        val data = extractVehicleData(rr.bytes)
            ?: return SendResult.Miss(if (rr.matched) "no vehicle frame parsed" else "att6 read timed out")
        return SendResult.Reply(data)
    }

    // ---- internals ----

    private fun command(body: String, ackTimeoutMs: Long = 1500L): Boolean {
        val reqId = nextReqId.getAndIncrement()
        io.writeAsciiLine("$body $reqId")
        return readAck(ackTimeoutMs, reqId)
    }

    private fun commandBinary(body: String, tail: ByteArray, ackTimeoutMs: Long = 1500L): Boolean {
        val reqId = nextReqId.getAndIncrement()
        io.writeAsciiPlusBinary("$body $reqId", tail)
        return readAck(ackTimeoutMs, reqId)
    }

    /**
     * Reads until an "ar*" response line ending in " <reqId>\r\n" arrives (or
     * any "ar" line when [reqId] < 0, used for the reqid-less `ati`), or timeout.
     */
    private fun readAck(timeoutMs: Long, reqId: Int = -1): Boolean =
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

    /**
     * Scans the buffer for "ar6" wrappers and returns the data bytes of the
     * first frame received from the response CAN ID (0x7E8, flags 0x40), or
     * null if no such complete frame is present yet.
     */
    private fun extractVehicleData(buf: ByteArray): ByteArray? {
        val marker = "ar$CHANNEL".toByteArray(StandardCharsets.US_ASCII)
        var pos = 0
        while (pos <= buf.size - marker.size) {
            val at = indexOf(buf, marker, pos) ?: break
            val lenIdx = at + marker.size
            if (lenIdx >= buf.size) break
            val len = buf[lenIdx].toInt() and 0xFF
            val flagsIdx = lenIdx + 1
            val frameEnd = flagsIdx + len   // flags + ts + canID + data == len bytes
            if (len < AR6_HEADER_LEN || frameEnd > buf.size) {
                pos = lenIdx
                continue
            }
            val flags = buf[flagsIdx].toInt() and 0xFF
            val canIdStart = flagsIdx + 1 + 4   // skip flags(1) + ts(4)
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
}
