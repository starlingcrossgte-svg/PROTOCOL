package com.protocol.app.firmware

/**
 * Turns a RAW kernel binary into the upload image the SH7058 CAN bootloader expects.
 *
 * The bootloader decrypts each uploaded image before the kernel runs and checks an
 * integrity word, so a raw kernel must be:
 *   1. padded with 0x00 up to a whole number of 128-byte blocks,
 *   2. stamped with a trailing 4-byte integrity word so the sum of every big-endian
 *      32-bit word in the image equals 0x5AA5A55A,
 *   3. encrypted with [PayloadCrypto].
 * That is exactly the reference upload sequence (pad -> integrity word -> encrypt), and
 * [PayloadCrypto.encrypt] is byte-for-byte the reference payload cipher.
 *
 * The recipe belongs to the ECU bootloader, not the kernel, so the same prep works for
 * ANY kernel targeting this ECU: the user supplies the raw kernel, this produces the
 * bytes the 0x34/0xB6 upload carries. (A kernel that is already in upload form — i.e.
 * pre-encrypted — must NOT be run through this; that would double-encrypt it.)
 */
object KernelImagePrep {

    private const val BLOCK = 128
    private const val INTEGRITY_TARGET = 0x5AA5A55A   // sum of all BE32 words must equal this

    /** Raw kernel bytes -> block-aligned, integrity-stamped, encrypted upload image. */
    fun prepare(raw: ByteArray): ByteArray {
        // 1. pad to a whole number of 128-byte blocks (copyOf zero-fills the tail).
        val blocks = maxOf(1, (raw.size + BLOCK - 1) / BLOCK)
        val total = blocks * BLOCK
        val img = raw.copyOf(total)

        // 2. integrity word occupies the final 4 bytes: chosen so the whole image (all
        //    BE32 words, including this one) sums to INTEGRITY_TARGET. Int wraps mod 2^32,
        //    matching the unsigned arithmetic of the reference.
        var sum = 0
        var i = 0
        while (i < total - 4) { sum += be32(img, i); i += 4 }
        val word = INTEGRITY_TARGET - sum
        img[total - 4] = (word ushr 24).toByte()
        img[total - 3] = (word ushr 16).toByte()
        img[total - 2] = (word ushr 8).toByte()
        img[total - 1] = word.toByte()

        // 3. encrypt the whole (plaintext + integrity word) for upload.
        return PayloadCrypto.encrypt(img)
    }
}
