package com.protocol.app.openport2

/*
 * Transport-agnostic byte-stream contract that TactrixClient speaks against.
 *
 * Two implementations exist:
 *   - TactrixBulkIo: USB bulk endpoints on a connected OpenPort 2.0.
 *   - TactrixTcpIo:  TCP socket to a host process speaking the same wire
 *                    protocol (used for bench testing without the adapter).
 *
 * Both must produce identical byte sequences on the wire so that the
 * Tactrix init handshake, SSM2 framing, and ar-frame parsing in
 * TactrixClient are unchanged across transports.
 */
interface TactrixIo {

    /**
     * Writes [packet] verbatim. Returns the byte count on success.
     * Throws [UsbDisconnectedException] if the underlying transport has gone away.
     */
    fun write(packet: ByteArray): Int

    /**
     * Drains any bytes already sitting in the read buffer. Used at probe start
     * to discard late replies from a previous run.
     */
    fun drain(maxTotalMs: Long = 500L)

    /**
     * Reads repeatedly until [predicate] returns true on the accumulated buffer,
     * or until [totalTimeoutMs] elapses. Returns the bytes seen so far plus a
     * flag indicating whether the predicate matched (true) or the read timed
     * out (false).
     */
    fun readUntil(totalTimeoutMs: Long, predicate: (ByteArray) -> Boolean): ReadResult

    /** Releases any transport-level resources (sockets, etc.). USB session lifecycle is owned elsewhere. */
    fun close()
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
