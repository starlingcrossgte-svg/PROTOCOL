package com.protocol.app.firmware

import java.io.ByteArrayOutputStream

/**
 * Reads the firmware image from an ALIVE kernel and assembles the full image.
 *
 * Bare-command kernel grammar (after the CAN-ID, which the transport strips;
 * the reply opcode is the command | 0x80):
 *   verify   TX  02 <addr 4B BE> <size 4B BE>     RX  82 <crc 4B>
 *   read     TX  03 <addr 4B BE> <size 2B BE>     RX  83 <page data>
 *
 * The read follows the observed rhythm: per 4 KB, one verify (0x02) over the
 * block, then two READ_AREA (0x03) pages of [PAGE_SIZE] each. READ_AREA is
 * non-destructive — no enable/erase/commit is ever sent. This is the whole point
 * of the read-only phase: a full image dump that doubles as the recovery backup
 * before any future write is attempted.
 */
class KernelReadClient(
    private val transport: FirmwareCanTransport,
    private val log: (String) -> Unit = {},
) {

    /**
     * Reads [length] bytes from [startAddr]. Calls [onProgress] (bytesDone, total)
     * per page. Returns the assembled image, or null on the first failed step.
     */
    fun readImage(
        startAddr: Int = 0,
        length: Int = ROM_SIZE,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ByteArray? {
        val out = ByteArrayOutputStream(length)
        var addr = startAddr
        var done = 0
        while (done < length) {
            // Verify the upcoming 4 KB block (0x02) at each block boundary, then
            // read it as two pages (0x03) — the observed read rhythm.
            if ((addr - startAddr) % VERIFY_SIZE == 0) {
                if (verifyBlock(addr) == null) {
                    log("ERROR: verify (0x02) failed @ 0x" + Integer.toHexString(addr) + " after ${out.size()} B")
                    return null
                }
            }
            val page = readPage(addr)
            if (page == null) {
                log("ERROR: READ_AREA failed @ 0x" + Integer.toHexString(addr) + " after ${out.size()} B")
                return null
            }
            val take = minOf(page.size, length - done)
            out.write(page, 0, take)
            addr += PAGE_SIZE
            done += take
            if (done % (64 * 1024) == 0 || done >= length) {
                log("Read $done / $length B")
            }
            onProgress(done, length)
        }
        log("Image read complete: ${out.size()} bytes")
        return out.toByteArray()
    }

    /** Verify request over a [VERIFY_SIZE] block; returns the kernel's 4-byte CRC. */
    private fun verifyBlock(addr: Int): ByteArray? {
        val cmd = byteArrayOf(VERIFY) + be4(addr) + be4(VERIFY_SIZE)
        val resp = transport.transceive(cmd, timeoutMs = VERIFY_TIMEOUT_MS, minReplyBytes = 1 + 4)
            ?: return null
        if (resp.size < 5 || ub(resp[0]) != ((VERIFY.toInt() and 0xFF) or 0x80)) {
            log("Bad verify reply @ 0x" + Integer.toHexString(addr) + ": " + toHex(resp, 16))
            return null
        }
        return resp.copyOfRange(1, 5)
    }

    /** READ_AREA request for one [PAGE_SIZE] page; returns the page bytes. */
    private fun readPage(addr: Int): ByteArray? {
        val cmd = byteArrayOf(READ_AREA) + be4(addr) + be2(PAGE_SIZE)
        val resp = transport.transceive(cmd, timeoutMs = READ_TIMEOUT_MS, minReplyBytes = 1 + PAGE_SIZE)
            ?: return null
        if (resp.size < 1 + PAGE_SIZE || ub(resp[0]) != ((READ_AREA.toInt() and 0xFF) or 0x80)) {
            log("Bad READ_AREA reply @ 0x" + Integer.toHexString(addr) + ": " + toHex(resp, 16))
            return null
        }
        return resp.copyOfRange(1, 1 + PAGE_SIZE)
    }

    companion object {
        const val ROM_SIZE = 0x100000        // 1 MB SH7058 image
        const val PAGE_SIZE = 0x800          // 2048 B per READ_AREA page (fits maxmsg)
        private const val VERIFY_SIZE = 0x1000   // 4 KB verify block (two pages)
        private const val VERIFY: Byte = 0x02
        private const val READ_AREA: Byte = 0x03
        private const val READ_TIMEOUT_MS = 3000L
        private const val VERIFY_TIMEOUT_MS = 2000L
    }
}
