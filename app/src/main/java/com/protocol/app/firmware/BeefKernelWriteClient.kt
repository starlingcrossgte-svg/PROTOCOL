package com.protocol.app.firmware

/**
 * Writes (reflashes) a firmware image through an ALIVE "BEEF" kernel — the same
 * kernel, same ISO-TP CAN channel ([FirmwareCanTransport]) the read path uses
 * ([BeefKernelReadClient]). The caller uploads + jumps the kernel first; this
 * client assumes it is already running and answering BEEF commands.
 *
 * Sequence mirrors the reference reflash exactly:
 *   1. changed-block compare (READ-ONLY): per erase-block CRC (0x02), ECU CRC vs
 *      image CRC. Only blocks that DIFFER are written, so flashing an identical
 *      ROM touches nothing — a safe no-op. This same compare is the verify step.
 *   2. init: GET_MAX_MSG (0x05), GET_MAX_BLK (0x06), then FLASH_ENABLE (0x20) for
 *      a real write, or FLASH_DISABLE (0x21) for a TEST write — in test mode the
 *      flash stays protected so erase/write are inert and only VALIDATE runs
 *      (genuinely non-destructive).
 *   3. per modified erase-block: PROG_VOLT (0x04) -> BLANK_PAGE (0x25, erase) ->
 *      WRITE_FLASH_BUFFER (0x22) in 0x200 chunks -> COMMIT (0x24) / VALIDATE
 *      (0x23) every 0x1000 with a CRC32 of that 4 KB.
 *
 * CRC32 here is the reference's custom reflected variant: polynomial 0x5AA5A55A,
 * init/xorout 0xFFFFFFFF — NOT standard zlib (0xEDB88320). The kernel computes the
 * same; with the wrong polynomial every block reads as changed and the per-commit
 * CRC the kernel re-checks would never match. Verified against the reference source.
 *
 * Read-only until the caller passes testMode=false. Bench targets only.
 */
class BeefKernelWriteClient(
    private val transport: FirmwareCanTransport,
    private val log: (String) -> Unit = {},
) {
    /** ok = the sequence completed; modifiedBlocks = how many differed; verified =
     *  (real write) post-write CRC re-compare matched, or (test) sequence passed. */
    data class Outcome(val ok: Boolean, val modifiedBlocks: Int, val verified: Boolean)

    /** Reflash [image] (must be exactly [ROM_SIZE]). testMode=true is non-destructive. */
    fun writeImage(
        image: ByteArray,
        testMode: Boolean,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): Outcome {
        if (image.size != ROM_SIZE) {
            log("ERROR: ROM image ${image.size} B is not the expected $ROM_SIZE B")
            return Outcome(false, 0, false)
        }

        log("--- Comparing ECU flash to image (per-block CRC32) ---")
        val modified = compareBlocks(image) ?: run {
            log("ERROR: block compare failed (no kernel reply)")
            return Outcome(false, 0, false)
        }
        if (modified.isEmpty()) {
            log("All ${BLOCKS.size} blocks match the image — nothing to write (no-op).")
            return Outcome(true, 0, true)
        }
        log("Blocks differing from image: ${modified.joinToString(",")} (${modified.size})")

        if (!initFlashWrite(testMode)) return Outcome(false, modified.size, false)
        log(
            if (testMode) "TEST mode: flash left protected, VALIDATE only (non-destructive)"
            else "COMMIT mode: performing a REAL flash write"
        )

        val totalBytes = modified.sumOf { BLOCKS[it].second }
        var doneBytes = 0
        for (blk in modified) {
            val (start, len) = BLOCKS[blk]
            log("Block $blk @ 0x${hex(start)} len 0x${hex(len)} ...")
            val ok = writeBlock(image, start, len, testMode) { chunk ->
                doneBytes += chunk; onProgress(doneBytes, totalBytes)
            }
            if (!ok) {
                log("ERROR: block $blk ${if (testMode) "validate" else "write"} failed — do NOT power-cycle; the kernel is likely still running, retry is possible.")
                return Outcome(false, modified.size, false)
            }
            log("Block $blk ${if (testMode) "validated" else "written"}.")
        }

        if (testMode) {
            log("*** TEST write PASS — sequence completed clean; safe to perform a real COMMIT write. ***")
            return Outcome(true, modified.size, true)
        }

        log("--- Verifying flash vs image after write ---")
        val after = compareBlocks(image)
        val verified = after != null && after.isEmpty()
        log(
            if (verified) "WRITE VERIFIED — flash now matches the image."
            else "*** WRITE VERIFY FAILED *** ${after?.size ?: -1} block(s) still differ — kernel still running, you can retry."
        )
        return Outcome(true, modified.size, verified)
    }

    /** Returns the indices of blocks whose ECU CRC differs from the image, or null on no reply. */
    private fun compareBlocks(image: ByteArray): List<Int>? {
        val modified = ArrayList<Int>()
        for (i in BLOCKS.indices) {
            val (start, len) = BLOCKS[i]
            val ecuCrc = blockCrc(start, len) ?: return null
            val imgCrc = crc32(image, start, len)
            val same = ecuCrc == imgCrc
            log("FB${i.toString().padStart(2, '0')} 0x${hex(start)} len 0x${hex(len)}  ecu=0x${hex(ecuCrc)} img=0x${hex(imgCrc)}  ${if (same) "OK" else "DIFF"}")
            if (!same) modified.add(i)
        }
        return modified
    }

    /** SUB_KERNEL_CRC (0x02): ECU computes CRC32 over [addr, addr+len). Read-only. */
    private fun blockCrc(addr: Int, len: Int): Int? {
        val d = sendBeef(CMD_CRC, be4(addr) + be4(len), CRC_TIMEOUT, minDataBytes = 4) ?: return null
        return (ub(d[0]) shl 24) or (ub(d[1]) shl 16) or (ub(d[2]) shl 8) or ub(d[3])
    }

    private fun initFlashWrite(testMode: Boolean): Boolean {
        if (sendBeef(CMD_GET_MAX_MSG, ByteArray(0), CMD_TIMEOUT, minDataBytes = 4) == null) {
            log("ERROR: GET_MAX_MSG_SIZE failed"); return false
        }
        if (sendBeef(CMD_GET_MAX_BLK, ByteArray(0), CMD_TIMEOUT, minDataBytes = 4) == null) {
            log("ERROR: GET_MAX_BLK_SIZE failed"); return false
        }
        val cmd = if (testMode) CMD_FLASH_DISABLE else CMD_FLASH_ENABLE
        if (sendBeef(cmd, ByteArray(0), CMD_TIMEOUT, minDataBytes = 0) == null) {
            log("ERROR: FLASH ${if (testMode) "DISABLE(test)" else "ENABLE"} failed"); return false
        }
        return true
    }

    /** Erase one block then stream it in 0x200 chunks, committing per 0x1000 with a CRC32. */
    private fun writeBlock(
        image: ByteArray, start: Int, len: Int, testMode: Boolean, onChunk: (Int) -> Unit,
    ): Boolean {
        if (progVolt() == null) { log("ERROR: PROG_VOLT failed"); return false }

        log("Erasing page @ 0x${hex(start)} ...")
        if (sendBeef(CMD_BLANK_PAGE, be4(start), ERASE_TIMEOUT, minDataBytes = 0) == null) {
            log("ERROR: BLANK_PAGE failed @ 0x${hex(start)}"); return false
        }

        var addr = start
        var remain = len
        var commitBase = start
        while (remain > 0) {
            val args = be4(addr) + image.copyOfRange(addr, addr + WRITE_CHUNK)
            if (sendBeef(CMD_WRITE_BUFFER, args, WRITE_TIMEOUT, minDataBytes = 0) == null) {
                log("ERROR: WRITE_FLASH_BUFFER failed @ 0x${hex(addr)}"); return false
            }
            addr += WRITE_CHUNK
            remain -= WRITE_CHUNK
            onChunk(WRITE_CHUNK)

            if (addr - commitBase == COMMIT_GRAN) {
                val crc = crc32(image, commitBase, COMMIT_GRAN)
                val cmd = if (testMode) CMD_VALIDATE_BUFFER else CMD_COMMIT_BUFFER
                val cargs = be4(commitBase) + be2(COMMIT_GRAN) + be4(crc)
                if (sendBeef(cmd, cargs, COMMIT_TIMEOUT, minDataBytes = 0) == null) {
                    log("ERROR: ${if (testMode) "VALIDATE" else "COMMIT"} failed @ 0x${hex(commitBase)} crc 0x${hex(crc)}"); return false
                }
                commitBase += COMMIT_GRAN
            }
        }
        return true
    }

    /** PROG_VOLT (0x04): returns programming voltage, or null on no reply. */
    private fun progVolt(): Double? {
        val d = sendBeef(CMD_PROG_VOLT, ByteArray(0), CMD_TIMEOUT, minDataBytes = 2) ?: return null
        val v = (((ub(d[0]) shl 8) or ub(d[1])) / 50.0)
        log("Programming voltage: ${"%.1f".format(v)} V")
        return v
    }

    /** Frame `BE EF | len | cmd | args`, transceive, return the data after the 5-byte header. */
    private fun sendBeef(cmd: Int, args: ByteArray, timeoutMs: Long, minDataBytes: Int): ByteArray? {
        val len = 1 + args.size
        val req = ByteArray(HEADER + args.size)
        req[0] = MAGIC_HI
        req[1] = MAGIC_LO
        req[2] = (len ushr 8).toByte()
        req[3] = (len and 0xFF).toByte()
        req[4] = (cmd and 0xFF).toByte()
        System.arraycopy(args, 0, req, HEADER, args.size)

        val resp = transport.transceive(req, timeoutMs = timeoutMs, minReplyBytes = HEADER + minDataBytes)
            ?: return null
        if (resp.size < HEADER + minDataBytes ||
            ub(resp[0]) != 0xBE || ub(resp[1]) != 0xEF ||
            ub(resp[4]) != ((cmd and 0xFF) or 0x40)
        ) {
            log("Bad BEEF reply (cmd 0x" + Integer.toHexString(cmd) + "): " + toHex(resp, 16))
            return null
        }
        return resp.copyOfRange(HEADER, resp.size)
    }

    /** Reference custom reflected CRC-32 (poly 0x5AA5A55A, init/xorout 0xFFFFFFFF). */
    private fun crc32(buf: ByteArray, start: Int, len: Int): Int {
        var crc = -1 // 0xFFFFFFFF
        var i = start
        val end = start + len
        while (i < end) {
            crc = CRC_TAB[(crc xor ub(buf[i])) and 0xFF] xor (crc ushr 8)
            i++
        }
        return crc.inv()
    }

    private fun hex(v: Int): String = Integer.toHexString(v).uppercase()

    companion object {
        const val ROM_SIZE = 0x100000 // 1 MB SH7058 user flash

        // SH7058 erase-block map: 8x4 KB + 96 KB + 7x128 KB = 1 MB (start, len).
        private val BLOCKS = arrayOf(
            0x00000000 to 0x1000, 0x00001000 to 0x1000, 0x00002000 to 0x1000, 0x00003000 to 0x1000,
            0x00004000 to 0x1000, 0x00005000 to 0x1000, 0x00006000 to 0x1000, 0x00007000 to 0x1000,
            0x00008000 to 0x18000, 0x00020000 to 0x20000, 0x00040000 to 0x20000, 0x00060000 to 0x20000,
            0x00080000 to 0x20000, 0x000A0000 to 0x20000, 0x000C0000 to 0x20000, 0x000E0000 to 0x20000,
        )

        private const val WRITE_CHUNK = 0x200   // bytes per WRITE_FLASH_BUFFER
        private const val COMMIT_GRAN = 0x1000  // commit/validate granularity (4 KB)
        private const val CRC32_POLY = 0x5AA5A55A

        private const val HEADER = 5
        private val MAGIC_HI = 0xBE.toByte()
        private val MAGIC_LO = 0xEF.toByte()

        private const val CMD_CRC = 0x02
        private const val CMD_PROG_VOLT = 0x04
        private const val CMD_GET_MAX_MSG = 0x05
        private const val CMD_GET_MAX_BLK = 0x06
        private const val CMD_FLASH_ENABLE = 0x20
        private const val CMD_FLASH_DISABLE = 0x21
        private const val CMD_WRITE_BUFFER = 0x22
        private const val CMD_VALIDATE_BUFFER = 0x23
        private const val CMD_COMMIT_BUFFER = 0x24
        private const val CMD_BLANK_PAGE = 0x25

        private const val CMD_TIMEOUT = 2500L
        private const val ERASE_TIMEOUT = 8000L
        private const val WRITE_TIMEOUT = 3000L
        private const val COMMIT_TIMEOUT = 8000L
        private const val CRC_TIMEOUT = 12000L

        private val CRC_TAB = IntArray(256).also { tab ->
            for (i in 0 until 256) {
                var crc = 0
                var c = i
                repeat(8) {
                    crc = if (((crc xor c) and 1) != 0) (crc ushr 1) xor CRC32_POLY else crc ushr 1
                    c = c ushr 1
                }
                tab[i] = crc
            }
        }
    }
}
