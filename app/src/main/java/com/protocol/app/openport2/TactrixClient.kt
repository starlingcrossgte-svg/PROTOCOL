package com.protocol.app.openport2

import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/*
 * Tactrix adapter channel number used for the K-line SSM2 bus on this project.
 *
 * The 12-step ati→atv setup binds the K-line bus to Tactrix channel 3 via
 * "ato3 512 4800 ..." (where the "3" is part of the command name, hardcoded
 * to match captured traffic). Every subsequent atf3/ats3/
 * att3 command targets that same channel, so all SSM2 reads and writes flow
 * through channel 3 in our setup.
 *
 * Hoisted here so the value lives in one place; callers reference it by name
 * for the ar<channel> frame extraction step instead of redefining "3" locally.
 */
internal const val K_LINE_CHANNEL: Int = 3

/*
 * High-level Tactrix adapter client.
 *
 * Composes the line-based ASCII command set observed in traces of the
 * adapter. Each command takes an auto-incrementing request ID. Responses are
 * read off bulk IN and recognized as one of:
 *   ari <text>                       — info line (returned after ati)
 *   aro <reqid>                      — atomic response OK
 *   arp <ch> <code> <reqid>          — protocol response (returned after atp)
 *   ar<ch> <2-byte LE length> <data> — received frame from the vehicle bus
 *
 * Note: req-id management lives here, not in the caller. Counter starts at 2
 * and increments per command, matching the pattern seen in our captures.
 */
class TactrixClient(private val io: TactrixIo) {

    private val nextRequestId = AtomicInteger(2)

    /*
     * Whether the 12-step ati→atv setup has been successfully run on this
     * client+adapter pair. Set by Ssm2EcmProbe.initializeChannel after a clean
     * pass. Cleared on att3 failure, USB disconnect, or repeated poller misses
     * so the next operation re-runs the full setup from scratch.
     *
     * Lives on TactrixClient (not the probe) because channel state is a
     * property of the client+adapter, not of the ephemeral probe object that
     * the ViewModel constructs per button press.
     */
    var channelInitialized: Boolean = false

    fun resetRequestIdCounter(startFrom: Int = 2) {
        nextRequestId.set(startFrom)
    }

    fun drainResponseBuffer(maxTotalMs: Long = 500L) {
        io.drain(maxTotalMs)
    }

    /*
     * Sends an ASCII line command (e.g. "ati", "ata 2", "ato3 512 4800 ...").
     * The request ID, when applicable, is appended to the body argument.
     * Returns the captured request/response pair plus structured outcome.
     */
    fun sendAsciiCommand(
        bodyWithoutReqId: String,
        appendReqId: Boolean,
        expectAck: Boolean,
        readTimeoutMs: Long = 1500L
    ): Outcome {
        val reqId = if (appendReqId) nextRequestId.getAndIncrement() else -1
        val fullLine = if (appendReqId) "$bodyWithoutReqId $reqId" else bodyWithoutReqId

        val requestBytes = ("$fullLine\r\n").toByteArray(StandardCharsets.US_ASCII)
        val sent = io.write(requestBytes)

        if (sent < 0) {
            return Outcome(
                requestAscii = fullLine,
                requestHex = TactrixHex.bytesToHex(requestBytes),
                responseAscii = "",
                responseHex = "",
                matched = false,
                reqIdUsed = reqId,
                notes = "bulkTransfer write failed"
            )
        }

        val readResult = if (expectAck && reqId >= 0) {
            io.readUntil(readTimeoutMs) { buf -> containsAnyResponseFor(buf, reqId) }
        } else {
            io.readUntil(readTimeoutMs) { buf -> containsAriLine(buf) || hasTrailingPromptOrCrLf(buf) }
        }

        val responseBytes = readResult.bytes
        return Outcome(
            requestAscii = fullLine,
            requestHex = TactrixHex.bytesToHex(requestBytes),
            responseAscii = TactrixHex.bytesToPrintableAscii(responseBytes),
            responseHex = TactrixHex.bytesToHex(responseBytes),
            matched = readResult.matched,
            reqIdUsed = reqId,
            notes = ""
        )
    }

    /*
     * Sends an ASCII header line followed by raw binary bytes in a single
     * bulk OUT write (e.g. "atp 5 2 <reqid>\r\n" + binary [01 00], or
     * "att3 <len> 0 <timeoutus> <reqid>\r\n" + binary [SSM2 frame]).
     *
     * For att-class commands, [expectVehicleFrame] should be true so the reader
     * waits for an ar<ch> binary frame in addition to the aro ack.
     */
    fun sendAsciiPlusBinary(
        asciiBodyWithoutReqId: String,
        binaryTail: ByteArray,
        appendReqId: Boolean,
        expectVehicleFrameOnChannel: Int? = null,
        // SSM2 source byte we expect in the reply ("who is replying"). For a
        // request with destination 0x10 (ECM), the reply has source 0x10 — so
        // pass 0x10 here. For a TCM request (destination 0x18), pass 0x18.
        // Defaults to ECM for backwards-compat with the probe path.
        expectedReplySource: Byte = 0x10.toByte(),
        readTimeoutMs: Long = 3000L
    ): Outcome {
        val reqId = if (appendReqId) nextRequestId.getAndIncrement() else -1
        val asciiLine = if (appendReqId) "$asciiBodyWithoutReqId $reqId" else asciiBodyWithoutReqId

        val asciiBytes = ("$asciiLine\r\n").toByteArray(StandardCharsets.US_ASCII)
        val packet = ByteArray(asciiBytes.size + binaryTail.size)
        System.arraycopy(asciiBytes, 0, packet, 0, asciiBytes.size)
        System.arraycopy(binaryTail, 0, packet, asciiBytes.size, binaryTail.size)

        val sent = io.write(packet)
        if (sent < 0) {
            return Outcome(
                requestAscii = asciiLine + "  + " + TactrixHex.bytesToHex(binaryTail),
                requestHex = TactrixHex.bytesToHex(packet),
                responseAscii = "",
                responseHex = "",
                matched = false,
                reqIdUsed = reqId,
                notes = "bulkTransfer write failed"
            )
        }

        val readResult = if (expectVehicleFrameOnChannel != null) {
            io.readUntil(readTimeoutMs) { buf -> containsArVehicleFrame(buf, expectVehicleFrameOnChannel, expectedReplySource) }
        } else if (reqId >= 0) {
            io.readUntil(readTimeoutMs) { buf -> containsAnyResponseFor(buf, reqId) }
        } else {
            io.readUntil(readTimeoutMs) { buf -> hasTrailingPromptOrCrLf(buf) }
        }

        val responseBytes = readResult.bytes
        return Outcome(
            requestAscii = asciiLine + "  + " + TactrixHex.bytesToHex(binaryTail),
            requestHex = TactrixHex.bytesToHex(packet),
            responseAscii = TactrixHex.bytesToPrintableAscii(responseBytes),
            responseHex = TactrixHex.bytesToHex(responseBytes),
            matched = readResult.matched,
            reqIdUsed = reqId,
            notes = ""
        )
    }

    /*
     * Extracts and reassembles the full SSM2 reply from a buffered Tactrix
     * response stream that may contain multiple ar<ch> wrapper frames.
     *
     * Tactrix wrapper format (confirmed from captures):
     *   "ar<ch>" (ASCII, 3 bytes) + 1-byte total-payload-length + 1-byte status
     *   + (length - 1) data bytes
     *
     * Status values:
     *   0x00 = K-line frame received from the vehicle bus  ← the real reply
     *   0x80 = transmit confirmation (echo of what we sent)
     *   0x40 = transmit timestamp / end-of-tx marker
     *
     * Strategy:
     *   1. Walk the buffer looking for ar<ch> wrappers.
     *   2. Collect the data payloads of all status=0x00 (RX) wrappers.
     *   3. After the first SSM2 header (80 F0 10) is seen, accumulate
     *      subsequent 0x00-status payloads until the SSM2 declared length
     *      byte is satisfied or the buffer is exhausted.
     *   4. Return the complete reassembled SSM2 frame.
     *
     * Falls back to the simpler header-search approach if wrapper parsing
     * fails (e.g. unexpected buffer layout), so the PoC path is not broken.
     */
    fun extractVehicleFrame(
        responseBytes: ByteArray,
        channel: Int,
        sourceByte: Byte = 0x10.toByte()
    ): ByteArray? {
        val wrapperMarker = "ar$channel".toByteArray(StandardCharsets.US_ASCII)
        val ssm2Header = byteArrayOf(0x80.toByte(), 0xF0.toByte(), sourceByte)
        val rxStatus: Byte = 0x00

        // Walk the buffer and gather all RX-status payloads in order.
        val rxPayloads = mutableListOf<ByteArray>()
        var pos = 0
        while (pos < responseBytes.size - wrapperMarker.size) {
            val markerAt = indexOfSubsequenceFrom(responseBytes, wrapperMarker, pos) ?: break
            val headerStart = markerAt + wrapperMarker.size
            if (headerStart + 1 >= responseBytes.size) break
            val wrapperLen = responseBytes[headerStart].toInt() and 0xFF
            val status = responseBytes[headerStart + 1]
            val dataStart = headerStart + 2
            val dataEnd = dataStart + (wrapperLen - 1).coerceAtLeast(0)
            if (dataEnd > responseBytes.size) break
            if (status == rxStatus && dataEnd > dataStart) {
                rxPayloads.add(responseBytes.copyOfRange(dataStart, dataEnd))
            }
            pos = dataEnd
        }

        // Concatenate all RX payloads and look for the SSM2 header.
        if (rxPayloads.isNotEmpty()) {
            val combined = rxPayloads.fold(ByteArray(0)) { acc, b -> acc + b }
            val headerPos = indexOfSubsequence(combined, ssm2Header) ?: 0
            val ssm2Start = combined.copyOfRange(headerPos, combined.size)
            // If we have at least the 4-byte SSM2 header, check declared length.
            if (ssm2Start.size >= 4) {
                val declaredLen = ssm2Start[3].toInt() and 0xFF
                val fullFrameLen = declaredLen + 5 // header(3)+len(1)+data+checksum(1)
                return if (ssm2Start.size >= fullFrameLen)
                    ssm2Start.copyOfRange(0, fullFrameLen)
                else
                    ssm2Start  // truncated; return what we have (Phase 3 improvement)
            }
            if (ssm2Start.isNotEmpty()) return ssm2Start
        }

        // Fallback: locate the SSM2 header directly and copy to end of buffer.
        val start = indexOfSubsequence(responseBytes, ssm2Header) ?: return null
        return responseBytes.copyOfRange(start, responseBytes.size)
    }

    /*
     * Read-only: wait for the next vehicle frame already streaming in from the
     * bus, WITHOUT transmitting anything. Used by continuous SSM2 mode (A8 flag
     * 0x01), where the ECU streams replies back-to-back after a single request
     * — every reply after the first is harvested here with no new transmit.
     * Returns the reassembled SSM2 frame, or null if none arrived in time.
     */
    fun readNextVehicleFrame(
        channel: Int,
        sourceByte: Byte,
        timeoutMs: Long
    ): ByteArray? {
        val readResult = io.readUntil(timeoutMs) { buf -> containsArVehicleFrame(buf, channel, sourceByte) }
        if (!readResult.matched) return null
        return extractVehicleFrame(readResult.bytes, channel, sourceByte)
    }

    private fun indexOfSubsequenceFrom(haystack: ByteArray, needle: ByteArray, startAt: Int): Int? {
        if (needle.isEmpty() || haystack.size < needle.size || startAt >= haystack.size) return null
        outer@ for (i in startAt..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }

    data class Outcome(
        val requestAscii: String,
        val requestHex: String,
        val responseAscii: String,
        val responseHex: String,
        val matched: Boolean,
        val reqIdUsed: Int,
        val notes: String
    )

    /*
     * Recognizes any Tactrix response line that ends with " <reqid>\r\n".
     * Covers all observed response prefixes (aro, arp, arf<ch>, arv, plus any
     * future ar<letter> response) without requiring per-prefix handling.
     *
     * Tolerates garbage bytes before the "ar" prefix on the same line (the
     * adapter sometimes emits a short ASCII session token before the response
     * proper, e.g. "TA5UkK4saro 4\r\n" — the matcher still locks onto "aro 4").
     */
    private fun containsAnyResponseFor(buf: ByteArray, reqId: Int): Boolean {
        val asString = String(buf, StandardCharsets.US_ASCII)
        val tail = " $reqId\r\n"
        var searchFrom = 0
        while (true) {
            val arIdx = asString.indexOf("ar", searchFrom)
            if (arIdx < 0) return false
            if (arIdx + 2 >= asString.length) return false
            val typeChar = asString[arIdx + 2]
            if (typeChar in 'a'..'z') {
                val lineEnd = asString.indexOf("\r\n", arIdx)
                if (lineEnd > arIdx) {
                    val line = asString.substring(arIdx, lineEnd + 2)
                    if (line.endsWith(tail)) return true
                }
            }
            searchFrom = arIdx + 2
        }
    }

    private fun containsAriLine(buf: ByteArray): Boolean {
        val asString = String(buf, StandardCharsets.US_ASCII)
        val idx = asString.indexOf("ari ")
        if (idx < 0) return false
        val lineEnd = asString.indexOf("\r\n", idx)
        return lineEnd > idx
    }

    private fun hasTrailingPromptOrCrLf(buf: ByteArray): Boolean {
        if (buf.size < 2) return false
        return buf[buf.size - 1] == 0x0A.toByte() && buf[buf.size - 2] == 0x0D.toByte()
    }

    /*
     * Read-loop predicate for the att<ch> transmit case.
     *
     * Returns true when the accumulated buffer contains what appears to be a
     * complete SSM2 reply. "Complete" means: we've seen the SSM2 header
     * (80 F0 10) and the total accumulated RX-status bytes from all ar<ch>
     * wrappers are >= the full declared frame length (4 + length_byte + 1).
     *
     * If we can't parse the wrapper or don't see a length byte yet, we fall
     * back to the header-present check so short replies still terminate
     * promptly.
     */
    private fun containsArVehicleFrame(buf: ByteArray, channel: Int, sourceByte: Byte): Boolean {
        val ssm2Header = byteArrayOf(0x80.toByte(), 0xF0.toByte(), sourceByte)
        val headerIdx = indexOfSubsequence(buf, ssm2Header) ?: return false

        // Try to determine declared length from the bytes we have.
        // ssm2Header is at headerIdx; length byte is at headerIdx + 3.
        if (buf.size < headerIdx + 4) return false  // need at least 4 header bytes
        val declaredLen = buf[headerIdx + 3].toInt() and 0xFF
        val fullFrameLen = declaredLen + 5

        // Gather total bytes received so far from SSM2 header onward.
        val bytesFromHeader = buf.size - headerIdx
        return bytesFromHeader >= fullFrameLen
    }

    private fun indexOfSubsequence(haystack: ByteArray, needle: ByteArray): Int? {
        if (needle.isEmpty() || haystack.size < needle.size) return null
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return i
        }
        return null
    }
}
