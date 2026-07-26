package com.protocol.app.firmware

/**
 * Stock SH7058 security-access (service 0x27) key derivation.
 *
 * Pure function: given the 4-byte seed returned in a `27 01` positive response, produces
 * the 4-byte key to send in `27 02`. The algorithm and its two tables are the stock
 * variant for this ECU family, restated independently and verified on a real seed/key
 * pair observed on the wire: seed `1F CB FB 1A` -> key `95 B2 B3 5D`.
 *
 * Integer widths are part of the algorithm: the per-round key is 16-bit and the running
 * state is 32-bit. Kotlin `Int` is 32-bit and wraps on overflow, which matches the
 * intended unsigned arithmetic; `ushr` (not `shr`) is required for the logical shifts.
 */
object SubaruSeedKey {

    // Per-round key-index table (16 entries, 16-bit each).
    private val KEY_INDEX = intArrayOf(
        0x78B1, 0x4625, 0x201C, 0x9EA5,
        0xAD6B, 0x35F4, 0xFD21, 0x5E71,
        0xB046, 0x7F4A, 0x4B75, 0x93F9,
        0x1895, 0x8961, 0x3ECC, 0x862B,
    )

    // 5-bit nibble substitution (32 entries, each a nibble).
    private val SUBST = intArrayOf(
        0x5, 0x6, 0x7, 0x1, 0x9, 0xC, 0xD, 0x8,
        0xA, 0xD, 0x2, 0xB, 0xF, 0x4, 0x0, 0x3,
        0xB, 0x4, 0x6, 0x0, 0xF, 0x2, 0xD, 0x9,
        0x5, 0xC, 0x1, 0xA, 0x3, 0xD, 0xE, 0x8,
    )

    /**
     * Derive the 4-byte security-access key from the 4-byte seed (both big-endian on the wire).
     * The descending round order maps seed -> key; the ascending order would invert it.
     */
    fun stockKey(seed: ByteArray): ByteArray {
        require(seed.size == 4) { "seed must be 4 bytes, was ${seed.size}" }

        var v = ((seed[0].toInt() and 0xFF) shl 24) or
            ((seed[1].toInt() and 0xFF) shl 16) or
            ((seed[2].toInt() and 0xFF) shl 8) or
            (seed[3].toInt() and 0xFF)

        for (ki in 15 downTo 0) {
            val low = v and 0xFFFF
            val high = (v ushr 16) and 0xFFFF
            var index = low xor KEY_INDEX[ki]
            index += index shl 16                       // mirror low16 into high16
            var ekey = 0
            for (n in 0 until 4) {
                ekey += SUBST[(index ushr (n * 4)) and 0x1F] shl (n * 4)
            }
            ekey = ekey and 0xFFFF
            ekey = ((ekey ushr 3) + (ekey shl 13)) and 0xFFFF   // 16-bit rotate right 3
            v = (ekey xor high) + (low shl 16)
        }
        v = (v ushr 16) + (v shl 16)                    // swap 16-bit halves

        return byteArrayOf(
            (v ushr 24).toByte(),
            (v ushr 16).toByte(),
            (v ushr 8).toByte(),
            v.toByte(),
        )
    }
}
