package com.protocol.app.flash.engine

import java.nio.charset.StandardCharsets

/**
 * Low-level USB bulk read/write for the flash silo.
 *
 * Dedicated copy of the logging path's `TactrixBulkIo` (zero shared code) with
 * the same OpenPort wire behavior: an ASCII line (optionally followed by a raw
 * binary tail) in one bulk OUT write; mixed ASCII lines + binary frames on bulk
 * IN that callers must accumulate. The logging path's UsbTrafficLog hook is
 * intentionally omitted — the flash silo will get its own diagnostics later.
 */
class FlashBulkIo(private val session: FlashUsbSession) {

    companion object {
        private const val WRITE_TIMEOUT_MS = 1000
        private const val READ_CHUNK_SIZE = 512
        private const val READ_POLL_MS = 50
    }

    /**
     * Writes [packet] to the adapter. Returns the byte count on success.
     * @throws FlashUsbException if the transfer returns a negative error code.
     */
    fun write(packet: ByteArray): Int {
        val result = session.connection.bulkTransfer(
            session.endpointOut, packet, packet.size, WRITE_TIMEOUT_MS
        )
        if (result < 0) throw FlashUsbException("write failed (bulkTransfer=$result)")
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

    /** Empties any bytes already sitting in the bulk IN buffer. */
    fun drain(maxTotalMs: Long = 500L) {
        val deadline = System.currentTimeMillis() + maxTotalMs
        val buf = ByteArray(READ_CHUNK_SIZE)
        while (System.currentTimeMillis() < deadline) {
            val r = session.connection.bulkTransfer(session.endpointIn, buf, buf.size, 40)
            if (r <= 0) return
        }
    }

    /**
     * Reads bulk IN repeatedly until [predicate] returns true on the
     * accumulated buffer, or until [totalTimeoutMs] elapses. Returns whatever
     * accumulated plus whether the predicate matched.
     */
    fun readUntil(totalTimeoutMs: Long, predicate: (ByteArray) -> Boolean): ReadResult {
        val accumulated = ArrayList<Byte>(512)
        val deadline = System.currentTimeMillis() + totalTimeoutMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) return ReadResult(accumulated.toByteArray(), matched = false)
            val pollMs = remaining.coerceAtMost(READ_POLL_MS.toLong()).toInt().coerceAtLeast(1)
            val buffer = ByteArray(READ_CHUNK_SIZE)
            val received = session.connection.bulkTransfer(session.endpointIn, buffer, buffer.size, pollMs)
            if (received > 0) {
                for (i in 0 until received) accumulated.add(buffer[i])
                val snapshot = accumulated.toByteArray()
                if (predicate(snapshot)) return ReadResult(snapshot, matched = true)
            }
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

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + matched.hashCode()
    }
}

object FlashHex {
    fun bytesToHex(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        val sb = StringBuilder(bytes.size * 3)
        for (b in bytes) {
            if (sb.isNotEmpty()) sb.append(' ')
            sb.append(String.format("%02X", b.toInt() and 0xFF))
        }
        return sb.toString()
    }
}
