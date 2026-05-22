package com.protocol.app.openport2

import java.nio.charset.StandardCharsets

/*
 * Low-level USB bulk read/write helpers for the Tactrix adapter.
 *
 * Observed protocol traits from USBPcap captures:
 *  - Adapter enumerates as USB CDC-ACM virtual serial; line-based ASCII
 *    requests on bulk OUT, mixed ASCII line replies + binary frames on bulk IN.
 *  - A single request that carries trailing binary (e.g. att3 transmit) is one
 *    bulk OUT write: the ASCII header line terminated by CR LF, then the
 *    raw binary bytes immediately concatenated, all in one packet.
 *  - The adapter does NOT echo our bytes back when the host-side echo flag
 *    is off (which is the default state after enumeration).
 *  - Adapter replies can arrive in multiple bulk IN reads with no guarantee
 *    about how lines split across packets; callers must accumulate.
 */
class TactrixBulkIo(private val session: OpenPort2UsbSession) {

    companion object {
        private const val WRITE_TIMEOUT_MS = 1000
        private const val READ_CHUNK_SIZE = 512
        private const val READ_POLL_MS = 50
    }

    /**
     * Writes [packet] to the adapter. Returns the byte count on success.
     * @throws UsbDisconnectedException if the transfer returns a negative error code.
     */
    fun write(packet: ByteArray): Int {
        val result = session.connection.bulkTransfer(
            session.endpointOut,
            packet,
            packet.size,
            WRITE_TIMEOUT_MS
        )
        if (result < 0) throw UsbDisconnectedException("write failed (bulkTransfer=$result)")
        return result
    }

    fun writeAsciiLine(line: String): Int {
        val terminated = if (line.endsWith("\r\n")) line else "$line\r\n"
        return write(terminated.toByteArray(StandardCharsets.US_ASCII))
    }

    fun writeAsciiPlusBinary(asciiHeaderLine: String, binaryTail: ByteArray): Int {
        val terminated = if (asciiHeaderLine.endsWith("\r\n")) asciiHeaderLine else "$asciiHeaderLine\r\n"
        val headerBytes = terminated.toByteArray(StandardCharsets.US_ASCII)
        val packet = ByteArray(headerBytes.size + binaryTail.size)
        System.arraycopy(headerBytes, 0, packet, 0, headerBytes.size)
        System.arraycopy(binaryTail, 0, packet, headerBytes.size, binaryTail.size)
        return write(packet)
    }

    /*
     * Empties any bytes already sitting in the bulk IN buffer.
     *
     * Useful at probe start to discard delayed responses from a previous run
     * (e.g. an atf3 reply that arrived after the previous probe gave up on it).
     * Reads chunks with a short per-call timeout until a chunk comes back empty
     * or the overall budget is spent.
     */
    fun drain(maxTotalMs: Long = 500L) {
        val deadline = System.currentTimeMillis() + maxTotalMs
        val buf = ByteArray(READ_CHUNK_SIZE)
        while (System.currentTimeMillis() < deadline) {
            val r = session.connection.bulkTransfer(
                session.endpointIn,
                buf,
                buf.size,
                40
            )
            if (r <= 0) return
        }
    }

    /*
     * Reads bulk IN repeatedly until [predicate] returns true on the
     * accumulated buffer, or until [totalTimeoutMs] elapses.
     *
     * Returns whatever has been accumulated, plus a flag indicating whether the
     * predicate matched (true) or the read timed out (false).
     */
    fun readUntil(totalTimeoutMs: Long, predicate: (ByteArray) -> Boolean): ReadResult {
        val accumulated = ArrayList<Byte>(512)
        val deadline = System.currentTimeMillis() + totalTimeoutMs

        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) {
                return ReadResult(accumulated.toByteArray(), matched = false)
            }

            val pollMs = remaining.coerceAtMost(READ_POLL_MS.toLong()).toInt().coerceAtLeast(1)
            val buffer = ByteArray(READ_CHUNK_SIZE)
            val received = session.connection.bulkTransfer(
                session.endpointIn,
                buffer,
                buffer.size,
                pollMs
            )

            if (received > 0) {
                for (i in 0 until received) {
                    accumulated.add(buffer[i])
                }
                val snapshot = accumulated.toByteArray()
                if (predicate(snapshot)) {
                    return ReadResult(snapshot, matched = true)
                }
            }
            // Non-positive (0 or -1) means no bytes arrived within this poll
            // window. On Android, bulkTransfer cannot distinguish a per-chunk
            // timeout from a hard disconnect — both return -1. Real disconnects
            // are detected by (a) the OS ACTION_USB_DEVICE_DETACHED broadcast
            // wired in Protocol, and (b) write() failures, where
            // a -1 inside the 1 s write timeout is reliably a disconnect.
        }
    }

    data class ReadResult(val bytes: ByteArray, val matched: Boolean) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ReadResult
            if (!bytes.contentEquals(other.bytes)) return false
            if (matched != other.matched) return false
            return true
        }
        override fun hashCode(): Int {
            return 31 * bytes.contentHashCode() + matched.hashCode()
        }
    }
}

object TactrixHex {
    fun bytesToHex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }

    fun bytesToPrintableAscii(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(
                when {
                    v == 0x0D -> "\\r"
                    v == 0x0A -> "\\n"
                    v in 0x20..0x7E -> v.toChar().toString()
                    else -> "[%02X]".format(v)
                }
            )
        }
        return sb.toString()
    }

    fun parseHexPayload(hex: String): ByteArray {
        val sanitized = hex.replace(" ", "").replace("\r", "").replace("\n", "")
        require(sanitized.length % 2 == 0) { "Hex payload must have an even number of characters" }
        require(sanitized.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' }) {
            "Hex payload contains non-hex characters"
        }
        val out = ByteArray(sanitized.length / 2)
        for (i in out.indices) {
            val high = Character.digit(sanitized[i * 2], 16)
            val low = Character.digit(sanitized[i * 2 + 1], 16)
            out[i] = ((high shl 4) or low).toByte()
        }
        return out
    }
}
