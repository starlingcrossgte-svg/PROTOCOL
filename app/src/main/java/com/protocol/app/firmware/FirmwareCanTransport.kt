package com.protocol.app.firmware

import com.protocol.app.openport2.TactrixIo
import com.protocol.app.openport2.UsbDisconnectedException
import java.nio.charset.StandardCharsets

/**
 * CAN / ISO-TP transport for the firmware silo, layered on the shared OpenPort
 * byte transport ([TactrixIo]).
 *
 * Opens diagnostic channel 6 (ISO15765, 500 kbps, 7E0 request / 7E8 response),
 * then sends a full UDS-or-kernel payload as a single `att6` — the adapter
 * performs ISO-TP segmentation on transmit and reassembly on receive, so the
 * host never builds first/consecutive frames. Replies are read back as one or
 * more `ar6` wrappers and concatenated.
 *
 * Deliberately separate from the live-logging CAN source (which is single-frame
 * and reads a 1-byte payload by design). This is the large-frame reader the
 * firmware image read needs. It lives entirely in the firmware package and never
 * touches the logging path; it only borrows the byte transport.
 *
 * Returned payloads have the 4-byte CAN-ID prefix and per-wrapper framing
 * STRIPPED: a bootloader reply is the raw UDS bytes (SID at [0]); a kernel reply
 * is `<cmd|0x80> <data…>` (the reply opcode is at [0]).
 */
class FirmwareCanTransport(private val io: TactrixIo) {

    private var reqId = 2
    private var opened = false

    /** Opens the ISO15765 channel. Mirrors the bench-proven OpenPort CAN init. */
    fun open(): Boolean {
        if (opened) return true
        try {
            io.drain()

            write("ati\r\n")
            io.readUntil(1500L) { buf -> asciiHasAck(buf, -1) }

            if (!command("ata")) return false
            if (!command("ato$CHANNEL 0 $BAUD 0")) return false
            if (!command("ats$CHANNEL 3 0")) return false
            if (!command("ats$CHANNEL 30 0")) return false
            if (!command("ats$CHANNEL 31 0")) return false
            if (!command("ats$CHANNEL 34 65535")) return false
            if (!command("ats$CHANNEL 35 65535")) return false
            if (!command("ats$CHANNEL 37 0")) return false

            val filter = byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()) +
                CAN_RESPONSE + CAN_REQUEST
            if (!commandBinary("atf$CHANNEL 3 $TX_FLAGS 4", filter)) return false

            opened = true
            return true
        } catch (e: UsbDisconnectedException) {
            // Stale/dead USB handle (cold-start wedge) — fail cleanly so the caller
            // reports "channel open failed" instead of the op crashing.
            opened = false
            return false
        }
    }

    fun isOpen(): Boolean = opened

    /** Flushes any bytes queued in the adapter's IN buffer (call at phase boundaries). */
    fun drain(maxMs: Long = 300L) = io.drain(maxMs)

    /**
     * Re-applies the channel RX-timeout config once, immediately after the jump to
     * the RAM kernel. Observed on the wire: the host re-sends this single ioctl
     * before first contact with the kernel (the jump resets channel state). Uses
     * the same proven command path as init.
     */
    fun reapplyKernelTimeout(): Boolean = command("ats$CHANNEL 35 65535")

    fun close() {
        opened = false
    }

    /**
     * Sends [payload] (UDS/kernel bytes, WITHOUT the CAN-ID prefix) and returns
     * the reassembled reply payload, CAN-ID and per-wrapper framing stripped.
     *
     * Keeps reading `ar6` wrappers until the concatenated reply reaches
     * [minReplyBytes] (used for large `READ_AREA` pages), the adapter signals a
     * transmit error, or [timeoutMs] elapses. Returns null on timeout/no-reply
     * or USB disconnect.
     *
     * [preDelayMs] inserts a settle delay before reading (e.g. kernel boot after
     * the jump) — a plain blocking sleep, safe on the IO thread this runs on.
     */
    fun transceive(
        payload: ByteArray,
        timeoutMs: Long,
        minReplyBytes: Int = 1,
        preDelayMs: Long = 0L,
        txTimeoutUs: Long = TX_TIMEOUT_US,
    ): ByteArray? {
        val tail = CAN_REQUEST + payload
        val id = nextReqId()
        val line = "att$CHANNEL ${tail.size} $TX_FLAGS $txTimeoutUs $id\r\n"
        val packet = line.toByteArray(StandardCharsets.US_ASCII) + tail
        return try {
            io.write(packet)
            if (preDelayMs > 0) Thread.sleep(preDelayMs)
            val rr = io.readUntil(timeoutMs) { buf ->
                val data = reassemble(buf)
                (data != null && data.size >= minReplyBytes) || hasTxError(buf, id)
            }
            reassemble(rr.bytes)
        } catch (e: UsbDisconnectedException) {
            opened = false
            null
        }
    }

    /**
     * Concatenates the data of every response-CAN-id (7E8) `ar6` wrapper in [buf],
     * in order, regardless of frame flag. Each wrapper is
     * `ar6 <len1> <flags1> <ts4> <canId4> <data(len-9)>`. A short reply is one
     * wrapper; a large reassembled reply (e.g. a 2 KB READ_AREA page) arrives as
     * several — confirmed on-bench: an empty first wrapper (flag 0x80), the data
     * split across consecutive wrappers (flag 0x00), and a final wrapper (flag
     * 0x40). Joining all 7E8 wrappers that carry data yields the full reply.
     * Request-echo wrappers carry the request CAN-id (7E0) and are excluded.
     * Returns null if none present.
     */
    private fun reassemble(buf: ByteArray): ByteArray? {
        val marker = "ar$CHANNEL".toByteArray(StandardCharsets.US_ASCII)
        var out: ByteArray? = null
        var pos = 0
        while (pos <= buf.size - marker.size) {
            val at = indexOf(buf, marker, pos) ?: break
            val lenIdx = at + marker.size
            if (lenIdx >= buf.size) break
            val len = buf[lenIdx].toInt() and 0xFF
            val flagsIdx = lenIdx + 1
            val frameEnd = flagsIdx + len
            if (len < AR6_HEADER_LEN || frameEnd > buf.size) {
                // Incomplete wrapper at the tail — stop; caller reads more.
                break
            }
            val canIdStart = flagsIdx + 1 + TS_LEN
            val dataStart = canIdStart + CAN_ID_LEN
            if (regionEquals(buf, canIdStart, CAN_RESPONSE) && dataStart < frameEnd) {
                val chunk = buf.copyOfRange(dataStart, frameEnd)
                out = if (out == null) chunk else out + chunk
            }
            pos = frameEnd
        }
        return out
    }

    // --- channel-open helpers (mirror the proven CAN init) ---

    private fun write(ascii: String) {
        io.write(ascii.toByteArray(StandardCharsets.US_ASCII))
    }

    private fun command(body: String, timeoutMs: Long = 1500L): Boolean {
        val id = nextReqId()
        write("$body $id\r\n")
        return io.readUntil(timeoutMs) { buf -> asciiHasAck(buf, id) }.matched
    }

    private fun commandBinary(body: String, tail: ByteArray, timeoutMs: Long = 1500L): Boolean {
        val id = nextReqId()
        val ascii = "$body $id\r\n".toByteArray(StandardCharsets.US_ASCII)
        io.write(ascii + tail)
        return io.readUntil(timeoutMs) { buf -> asciiHasAck(buf, id) }.matched
    }

    private fun nextReqId(): Int {
        val v = reqId
        reqId++
        return v
    }

    /** True if the buffer holds an `ar… <id>` ack line (any `ar*` prefix). id<0 = any `ar`. */
    private fun asciiHasAck(buf: ByteArray, id: Int): Boolean {
        val s = String(buf, StandardCharsets.US_ASCII)
        if (id < 0) return s.contains("ar")
        val needle = " $id\r\n"
        var from = 0
        while (true) {
            val i = s.indexOf("ar", from)
            if (i < 0) return false
            val end = s.indexOf("\r\n", i)
            if (end > i && s.substring(i, end + 2).endsWith(needle)) return true
            from = i + 2
        }
    }

    /** True if the buffer holds an `are … <id>` transmit-error line (adapter "no CAN response"). */
    private fun hasTxError(buf: ByteArray, id: Int): Boolean {
        val s = String(buf, StandardCharsets.US_ASCII)
        val needle = " $id\r\n"
        var from = 0
        while (true) {
            val i = s.indexOf("are", from)
            if (i < 0) return false
            val end = s.indexOf("\r\n", i)
            if (end > i && s.substring(i, end + 2).endsWith(needle)) return true
            from = i + 2
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
        if (needle.isEmpty() || from > haystack.size - needle.size) return null
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }

    private fun regionEquals(buf: ByteArray, start: Int, expect: ByteArray): Boolean {
        if (start + expect.size > buf.size) return false
        for (j in expect.indices) if (buf[start + j] != expect[j]) return false
        return true
    }

    companion object {
        const val CHANNEL = 6
        const val BAUD = 500_000

        val CAN_REQUEST = byteArrayOf(0x00, 0x00, 0x07, 0xE0.toByte())
        val CAN_RESPONSE = byteArrayOf(0x00, 0x00, 0x07, 0xE8.toByte())

        private const val TX_FLAGS = 64
        // Adapter-side transmit-completion timeout (µs); generous to cover an
        // ISO-TP multi-frame send + flow-control handshake.
        private const val TX_TIMEOUT_US = 2_000_000L

        private const val TS_LEN = 4
        private const val CAN_ID_LEN = 4
        // ar6 wrapper header counted by its length byte: flags(1)+ts(4)+canId(4).
        private const val AR6_HEADER_LEN = 9
    }
}
