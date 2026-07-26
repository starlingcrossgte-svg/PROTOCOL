package com.protocol.app.firmware

/** Small byte helpers shared across the firmware silo. Module-internal. */

/** Unsigned value of a byte (0..255). */
internal fun ub(b: Byte): Int = b.toInt() and 0xFF

/** Space-separated uppercase hex, optionally capped to [max] bytes with a count suffix. */
internal fun toHex(bytes: ByteArray, max: Int = Int.MAX_VALUE): String {
    val n = minOf(bytes.size, max)
    val sb = StringBuilder(n * 3)
    for (i in 0 until n) {
        if (sb.isNotEmpty()) sb.append(' ')
        sb.append(String.format("%02X", bytes[i].toInt() and 0xFF))
    }
    if (bytes.size > max) sb.append(" ...(${bytes.size} B)")
    return sb.toString()
}

/** Printable-ASCII rendering ('.' for non-printables) — for kernel-id logging. */
internal fun asciiPreview(bytes: ByteArray): String {
    val sb = StringBuilder(bytes.size)
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        sb.append(if (v in 0x20..0x7E) v.toChar() else '.')
    }
    return sb.toString()
}

/** Low 24 bits of [v] as 3 big-endian bytes (UDS/kernel address field). */
internal fun be3(v: Int): ByteArray = byteArrayOf(
    ((v ushr 16) and 0xFF).toByte(),
    ((v ushr 8) and 0xFF).toByte(),
    (v and 0xFF).toByte(),
)

/** [v] as 4 big-endian bytes (kernel 32-bit address / size field). */
internal fun be4(v: Int): ByteArray = byteArrayOf(
    ((v ushr 24) and 0xFF).toByte(),
    ((v ushr 16) and 0xFF).toByte(),
    ((v ushr 8) and 0xFF).toByte(),
    (v and 0xFF).toByte(),
)

/** Low 16 bits of [v] as 2 big-endian bytes (kernel read-size field). */
internal fun be2(v: Int): ByteArray = byteArrayOf(
    ((v ushr 8) and 0xFF).toByte(),
    (v and 0xFF).toByte(),
)

/** Reads 4 big-endian bytes at [i] as a 32-bit word (Int bit-pattern). */
internal fun be32(b: ByteArray, i: Int): Int =
    ((b[i].toInt() and 0xFF) shl 24) or
        ((b[i + 1].toInt() and 0xFF) shl 16) or
        ((b[i + 2].toInt() and 0xFF) shl 8) or
        (b[i + 3].toInt() and 0xFF)
