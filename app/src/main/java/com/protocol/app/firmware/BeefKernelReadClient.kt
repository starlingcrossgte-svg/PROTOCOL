package com.protocol.app.firmware

import java.io.ByteArrayOutputStream

/**
 * Reads a firmware image from an ALIVE FastECU-style kernel — the "BEEF" protocol.
 *
 * Unlike the bare-command kernel ([KernelReadClient]), every kernel message is wrapped
 * with a magic + length header, and the reply opcode is `cmd | 0x40` (not `cmd | 0x80`):
 *
 *   request :  BE EF | len(2B BE) | cmd | args           len = 1 + args.size
 *   reply   :  BE EF | len(2B BE) | (cmd | 0x40) | data   (5-byte header, then data)
 *
 * The transport adds/strips the CAN id and handles ISO-TP, so this client sends and
 * parses only the payload shown above. Commands used here:
 *   alive   BE EF 00 01 01                  -> BE EF .. 41 <id>
 *   read    BE EF 00 07 03 <addr4><size2>   -> BE EF .. 43 <page>
 * READ_AREA (0x03) is non-destructive — no enable/erase/commit is ever sent; the read is
 * a full image dump that doubles as the recovery backup before any future write.
 */
class BeefKernelReadClient(
    private val transport: FirmwareCanTransport,
    private val log: (String) -> Unit = {},
) {

    /** Confirm the kernel is alive; returns the id bytes, or null if no valid reply. */
    fun probeAlive(): ByteArray? {
        val id = sendBeef(CMD_ID, ByteArray(0), ALIVE_TIMEOUT_MS, minDataBytes = 1)
        if (id == null) { log("BEEF kernel-id (01): no reply"); return null }
        log("BEEF kernel alive — id: " + asciiPreview(id) + "  [" + toHex(id, 16) + "]")
        return id
    }

    /** Reads [length] bytes from [startAddr] in [PAGE_SIZE] pages. Null on first failure. */
    fun readImage(
        startAddr: Int = 0,
        length: Int = ROM_SIZE,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): ByteArray? {
        val out = ByteArrayOutputStream(length)
        var addr = startAddr
        var done = 0
        while (done < length) {
            val page = readPage(addr)
            if (page == null) {
                log("ERROR: BEEF READ_AREA failed @ 0x" + Integer.toHexString(addr) + " after $done B")
                return null
            }
            val take = minOf(page.size, length - done)
            out.write(page, 0, take)
            addr += PAGE_SIZE
            done += take
            if (done % (64 * 1024) == 0 || done >= length) log("Read $done / $length B")
            onProgress(done, length)
        }
        log("Image read complete: ${out.size()} bytes")
        return out.toByteArray()
    }

    /** READ_AREA for one [PAGE_SIZE] page at [addr]. */
    private fun readPage(addr: Int): ByteArray? =
        sendBeef(CMD_READ_AREA, be4(addr) + be2(PAGE_SIZE), READ_TIMEOUT_MS, minDataBytes = PAGE_SIZE)

    /**
     * Frame [cmd]+[args] as `BE EF | len | cmd | args`, transceive (the transport wraps it
     * in ISO-TP / CAN), and return the reply's data after the 5-byte `BE EF | len | cmd|0x40`
     * header — or null on a missing / malformed / short reply.
     */
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

    companion object {
        const val ROM_SIZE = 0x100000          // 1 MB SH7058 image
        const val PAGE_SIZE = 0x400            // 1024 B per READ_AREA page (reference read rhythm)
        private const val HEADER = 5           // BE EF | len(2) | cmd
        private val MAGIC_HI = 0xBE.toByte()
        private val MAGIC_LO = 0xEF.toByte()
        private const val CMD_ID = 0x01
        private const val CMD_READ_AREA = 0x03
        private const val READ_TIMEOUT_MS = 3000L
        private const val ALIVE_TIMEOUT_MS = 1500L
    }
}
