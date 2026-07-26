package com.protocol.app.firmware

/**
 * Stock SH7058 kernel-image payload crypto (Denso "calculate_payload").
 *
 * The bench ECU's bootloader expects the RAM kernel image uploaded ENCRYPTED;
 * it decrypts on receipt before executing. This is the host-side encrypt that
 * produces exactly what the 0x34/0xB6 upload carries.
 *
 * Same inner transform as the verified security-access key derivation
 * ([SubaruSeedKey]) — identical 32-entry substitution table and per-round
 * arithmetic. The differences: it runs 4 ASCENDING rounds over each big-endian
 * 32-bit word of the buffer (keyed by a 4-entry table), versus 16 descending
 * rounds over a single word for the seed/key. Encrypt and decrypt are the same
 * routine with the key table reversed.
 *
 * Integer widths are load-bearing: the per-round key is 16-bit and the running
 * word 32-bit. Kotlin `Int` wraps on overflow to match the intended unsigned
 * arithmetic, and `ushr` (not `shr`) is required for the logical shifts. The
 * port is cross-checked: the shared transform reproduces the known seed/key
 * vector 1FCBFB1A -> 95B2B35D. Final proof is the kernel going alive on-bench.
 *
 * Only [encrypt] is needed for the firmware READ path (the ROM read returns
 * plaintext; the kernel image is the only thing uploaded). [decrypt] exists for
 * the encrypt/decrypt round-trip self-test and future readback paths.
 */
object PayloadCrypto {

    // Per-round key tables (16-bit each). Decrypt is the encrypt table reversed.
    private val ENCRYPT_KEYS = intArrayOf(0xC85B, 0x32C0, 0xE282, 0x92A0)
    private val DECRYPT_KEYS = intArrayOf(0x92A0, 0xE282, 0x32C0, 0xC85B)

    // 5-bit nibble substitution (32 entries) — identical table to the seed/key.
    private val SUBST = intArrayOf(
        0x5, 0x6, 0x7, 0x1, 0x9, 0xC, 0xD, 0x8,
        0xA, 0xD, 0x2, 0xB, 0xF, 0x4, 0x0, 0x3,
        0xB, 0x4, 0x6, 0x0, 0xF, 0x2, 0xD, 0x9,
        0x5, 0xC, 0x1, 0xA, 0x3, 0xD, 0xE, 0x8,
    )

    /** Encrypt [buf] for upload. Length is truncated to a multiple of 4 (matches source). */
    fun encrypt(buf: ByteArray): ByteArray = transform(buf, ENCRYPT_KEYS)

    /** Inverse of [encrypt]; not used by the ROM read path (provided for round-trip tests). */
    fun decrypt(buf: ByteArray): ByteArray = transform(buf, DECRYPT_KEYS)

    private fun transform(buf: ByteArray, keys: IntArray): ByteArray {
        val len = buf.size and 3.inv()                 // len &= ~3
        val out = ByteArray(len)
        var i = 0
        while (i < len) {
            var v = ((buf[i].toInt() and 0xFF) shl 24) or
                ((buf[i + 1].toInt() and 0xFF) shl 16) or
                ((buf[i + 2].toInt() and 0xFF) shl 8) or
                (buf[i + 3].toInt() and 0xFF)

            for (ki in 0 until 4) {
                val low = v and 0xFFFF
                val high = (v ushr 16) and 0xFFFF
                var index = low xor keys[ki]
                index += index shl 16                   // mirror low16 into high16
                var ekey = 0
                for (n in 0 until 4) {
                    ekey += SUBST[(index ushr (n * 4)) and 0x1F] shl (n * 4)
                }
                ekey = ekey and 0xFFFF
                ekey = ((ekey ushr 3) + (ekey shl 13)) and 0xFFFF   // 16-bit rotate right 3
                v = (ekey xor high) + (low shl 16)
            }
            v = (v ushr 16) + (v shl 16)                // swap 16-bit halves

            out[i] = (v ushr 24).toByte()
            out[i + 1] = (v ushr 16).toByte()
            out[i + 2] = (v ushr 8).toByte()
            out[i + 3] = v.toByte()
            i += 4
        }
        return out
    }
}
