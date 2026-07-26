package com.protocol.app.firmware

/**
 * UDS bootloader state machine for the Denso SH7058 (CAN/ISO15765), read path.
 *
 * Drives, in order: programming session -> security access (seed/key) -> kernel-
 * image upload to RAM -> jump -> kernel-alive confirm. The uploaded image is
 * already in upload-ready (encrypted, integrity-word-baked-in) form and is sent
 * VERBATIM — the bootloader decrypts it on receipt. After [connectAndStartKernel]
 * succeeds, a [KernelReadClient] on the same transport can read the image.
 *
 * All sequences are clean-room reimplementations of the source-verified flash
 * spec; every constant traces to that spec / the on-wire capture. READ-ONLY
 * phase: nothing here erases or writes flash.
 *
 * Byte sequences are written here in our own words; provenance is the verified
 * spec and the bench capture (no external tool/source names per the comment rule).
 */
class UdsBootloaderClient(
    private val transport: FirmwareCanTransport,
    private val log: (String) -> Unit = {},
) {

    /**
     * Full bring-up to a live kernel. [uploadImage] is the upload-ready kernel
     * image (already encrypted, integrity word baked in) — sent verbatim.
     * Returns true iff the kernel reports alive.
     */
    fun connectAndStartKernel(
        uploadImage: ByteArray,
        kernelStartAddr: Int = KERNEL_START_ADDR,
        protocol: KernelProtocol = KernelProtocol.BARE,
    ): Boolean {
        if (!transport.open()) {
            log("ERROR: CAN channel open failed")
            return false
        }
        log("CAN ISO15765 channel open (7E0/7E8 @ 500k)")
        if (!enterProgrammingSession()) return false
        if (!securityAccess()) return false
        return uploadAndStartKernel(uploadImage, kernelStartAddr, protocol)
    }

    /** 10 03 (tolerated — our bench ECU refuses it) then 10 43 (required). */
    fun enterProgrammingSession(): Boolean {
        transport.drain()
        val r03 = transport.transceive(byteArrayOf(0x10, 0x03), timeoutMs = 800, minReplyBytes = 1)
        log("10 03 -> " + (r03?.let { toHex(it) } ?: "(no reply, expected on this ECU)"))

        val r43 = transport.transceive(byteArrayOf(0x10, 0x43), timeoutMs = 1500, minReplyBytes = 2)
        if (r43 == null || r43.size < 2 || ub(r43[0]) != 0x50 || ub(r43[1]) != 0x43) {
            log("ERROR: 10 43 programming session refused: " + (r43?.let { toHex(it) } ?: "(no reply)"))
            return false
        }
        log("Programming session entered (50 43)")
        return true
    }

    /** 27 01 seed -> derive key -> 27 02 key. */
    fun securityAccess(): Boolean {
        val r1 = transport.transceive(byteArrayOf(0x27, 0x01), timeoutMs = 1500, minReplyBytes = 6)
        if (r1 == null || r1.size < 6 || ub(r1[0]) != 0x67 || ub(r1[1]) != 0x01) {
            log("ERROR: 27 01 seed request failed: " + (r1?.let { toHex(it) } ?: "(no reply)"))
            return false
        }
        val seed = r1.copyOfRange(2, 6)
        val key = SubaruSeedKey.stockKey(seed)
        log("Seed " + toHex(seed) + " -> key " + toHex(key))

        val r2 = transport.transceive(byteArrayOf(0x27, 0x02) + key, timeoutMs = 1500, minReplyBytes = 2)
        if (r2 == null || r2.size < 2 || ub(r2[0]) != 0x67 || ub(r2[1]) != 0x02) {
            log("ERROR: 27 02 key rejected: " + (r2?.let { toHex(it) } ?: "(no reply)"))
            return false
        }
        log("Security access granted (67 02)")
        return true
    }

    /** Uploads the upload-ready kernel image to RAM verbatim, jumps, confirms alive. */
    private fun uploadAndStartKernel(uploadImage: ByteArray, startAddr: Int, protocol: KernelProtocol): Boolean {
        // The image is uploaded VERBATIM — it is already in upload-ready form, so
        // it is not transformed here. Must be a non-empty whole number of 128-byte
        // blocks.
        if (uploadImage.isEmpty() || uploadImage.size % 128 != 0) {
            log("ERROR: kernel image ${uploadImage.size} B is not a non-empty multiple of 128")
            return false
        }
        val dataLen = uploadImage.size
        val maxblocks = dataLen / 128
        log("Kernel image $dataLen B, $maxblocks blocks (verbatim upload)")

        // --- pre-download session step (10 42) ---
        // Our bench ECU needs a second DiagnosticSessionControl AFTER security
        // access before it accepts RequestDownload: the capture shows
        // seed/key -> 10 42 -> 34. Without it the ECU answers 7F 34 22
        // (conditionsNotCorrect) — exactly what the first bench run hit. The 0x42
        // subfunction carries the suppress-positive-response bit, so a reply is
        // optional; we send it and move on rather than require one.
        transport.drain()
        val r1042 = transport.transceive(byteArrayOf(0x10, 0x42), timeoutMs = 600, minReplyBytes = 1)
        log("10 42 -> " + (r1042?.let { toHex(it) } ?: "(no reply — suppressed, expected)"))

        // --- 0x34 RequestDownload (addr low-24, len) ---
        transport.drain()
        val req34 = byteArrayOf(0x34, 0x04, 0x33) + be3(startAddr) + be3(dataLen)
        val r34 = transport.transceive(req34, timeoutMs = 1500, minReplyBytes = 2)
        if (r34 == null || r34.size < 2 || ub(r34[0]) != 0x74 || ub(r34[1]) != 0x20) {
            log("ERROR: 34 RequestDownload failed: " + (r34?.let { toHex(it) } ?: "(no reply)"))
            return false
        }
        log("RequestDownload accepted (74 20) @ 0x" + Integer.toHexString(startAddr) + " len 0x" + Integer.toHexString(dataLen))

        // --- 0xB6 TransferData, 128-byte blocks (no trailing empty block) ---
        // (matches the observed upload; B6 acks are drained, not validated.)
        for (blockno in 0 until maxblocks) {
            val blockAddr = startAddr + blockno * 128
            val data = uploadImage.copyOfRange(blockno * 128, blockno * 128 + 128)
            transport.transceive(byteArrayOf(0xB6.toByte()) + be3(blockAddr) + data, timeoutMs = 800, minReplyBytes = 1)
        }
        log("Kernel uploaded ($maxblocks blocks)")

        // --- 0x37 TransferExit -> 0x31 01 02 02 02 jump ---
        transport.drain()
        val r37 = transport.transceive(byteArrayOf(0x37), timeoutMs = 1500, minReplyBytes = 1)
        if (r37 == null || r37.isEmpty() || ub(r37[0]) != 0x77) {
            log("ERROR: 37 TransferExit failed: " + (r37?.let { toHex(it) } ?: "(no reply)"))
            return false
        }
        // Settle ~100 ms between TransferExit and the jump so the bootloader finishes
        // finalizing before control leaves it (matches the observed upload).
        Thread.sleep(100)
        // 31 01 02 02 02 triggers the jump to our kernel at 0xFFFF3000. We do NOT
        // hard-gate on a 71 here. Bench evidence: an 8 KB image structurally identical
        // to the known-good upload, accepted at TransferExit (37->77), still gets total
        // silence on 31 — no fast positive AND no fast negative — i.e. the bootloader
        // jumped without answering. The "71" the prior working kernel produced came
        // from that kernel itself, which ours does not emit. So log whatever 31 returns
        // and fall through to the real liveness test (the kernel-id probe below).
        // Read-only: a stray 01 to an ECU still in the bootloader is harmless.
        val r31 = transport.transceive(byteArrayOf(0x31, 0x01, 0x02, 0x02, 0x02), timeoutMs = 1500, minReplyBytes = 1)
        log("31 jump -> " + (r31?.let { toHex(it) } ?: "(no reply — proceeding to kernel probe)"))

        // Re-apply the RX-timeout ioctl once before first kernel contact (the jump
        // resets channel state) — observed on the wire.
        if (!transport.reapplyKernelTimeout()) {
            log("WARN: channel-timeout re-apply not acked (continuing)")
        }
        // The kernel boots + re-inits its link in ~50 ms and expects prompt first
        // contact; probe promptly and do NOT drain between attempts.
        Thread.sleep(50)

        // --- kernel-alive: the grammar depends on the kernel ---
        if (protocol == KernelProtocol.BEEF) {
            // BEEF kernels ignore a bare 0x01; the alive check is the wrapped
            // BE EF .. 01 -> BE EF .. 41, handled by the BEEF reader.
            val beef = BeefKernelReadClient(transport, log)
            for (attempt in 1..6) {
                if (beef.probeAlive() != null) return true
                log("BEEF kernel-id attempt $attempt/6: not alive yet")
                Thread.sleep(50)
            }
            log("ERROR: BEEF kernel not alive after 6 attempts")
            return false
        }

        // --- BARE: KERNEL_ID (0x01); reply (0x01|0x80) <id…> ---
        var rk: ByteArray? = null
        for (attempt in 1..6) {
            val r = transport.transceive(byteArrayOf(SUB_KERNEL_ID.toByte()), timeoutMs = 1000, minReplyBytes = 2, preDelayMs = 100)
            if (r != null && r.size >= 2 && ub(r[0]) == (SUB_KERNEL_ID or 0x80)) {
                rk = r
                break
            }
            log("kernel-id attempt $attempt/6: " + (r?.let { toHex(it) } ?: "(no reply)"))
            Thread.sleep(50)
        }
        if (rk == null) {
            log("ERROR: kernel not alive after 6 attempts")
            return false
        }
        val kid = rk.copyOfRange(1, rk.size)
        log("KERNEL ALIVE — id " + toHex(kid) + " (" + asciiPreview(kid) + ")")

        // Optional capability queries (diagnostics + extra liveness): max message
        // size (0x05 -> 0x85 <u32>) and max block size (0x06 -> 0x86 <u32>).
        val rmsg = transport.transceive(byteArrayOf(0x05), timeoutMs = 800, minReplyBytes = 5)
        if (rmsg != null && rmsg.size >= 5 && ub(rmsg[0]) == 0x85)
            log("maxmsg = 0x" + Integer.toHexString(be32(rmsg, 1)))
        val rblk = transport.transceive(byteArrayOf(0x06), timeoutMs = 800, minReplyBytes = 5)
        if (rblk != null && rblk.size >= 5 && ub(rblk[0]) == 0x86)
            log("maxblk = 0x" + Integer.toHexString(be32(rblk, 1)))
        return true
    }

    companion object {
        /** SH7058 on-chip RAM load address for the kernel (capture-confirmed). */
        val KERNEL_START_ADDR: Int = 0xFFFF3000.toInt()
        private const val SUB_KERNEL_ID = 0x01
    }
}
