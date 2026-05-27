package com.protocol.app.openport2

import java.nio.charset.StandardCharsets

/*
 * One-shot SSM2 ECM identify probe over the Tactrix adapter.
 *
 * The byte choreography below was reconstructed from captures of a
 * working diagnostic session against a 2006 USDM Subaru Outback 3.0R 5EAT
 * (EZ30R H6, ECU 451A354006). The captured behavior is treated as the source
 * of truth; this implementation reproduces the same wire-side commands from
 * scratch.
 *
 * Sequence:
 *   1.  ati                                — device bring-up (no reqid)
 *   2.  ata 2                              — atomic activate
 *   3.  atp 5 2 3 \r\n 01 00               — open protocol 5 with 2-byte sub-param
 *   4.  ato3 512 4800 894790475 4          — channel 3 options: ISO9141_NO_CHECKSUM
 *                                            (bitfield 512), baud 4800
 *   5.  ats3 22 0 5                        — channel J2534 IOCTL parameters
 *   6.  ats3 22 0 6
 *   7.  ats3 7  1 7
 *   8.  ats3 10 0 8
 *   9.  ats3 12 0 9
 *  10.  ats3 3  0 10
 *  11.  atf3 1 0 4 11 \r\n 00 00 00 00 00 00 00 00   — receive filter
 *  12.  atv 9 -1 12                        — voltage probe / line state
 *      (settle ~200 ms)
 *  13.  att3 6 0 400000 13 \r\n 80 10 F0 01 BF 40    — SSM2 read ECU ID query
 *
 * Success criteria: the bytes received back from att3 contain `80 F0 10` (SSM2
 * reply header for dst=tool src=ECM) OR contain the calibration identifier
 * `45 1A 35 40 06`. Anything else is recorded honestly as no-response /
 * adapter error / timeout.
 */
class Ssm2EcmProbe(private val client: TactrixClient) {

    companion object {
        private const val SETTLE_MS = 200L
        private val SSM2_READ_ID_FRAME = byteArrayOf(
            0x80.toByte(),
            0x10.toByte(),
            0xF0.toByte(),
            0x01.toByte(),
            0xBF.toByte(),
            0x40.toByte()
        )
        private val ATP_BINARY_TAIL = byteArrayOf(0x01, 0x00)
        private val ATF_BINARY_TAIL = ByteArray(8) // eight 0x00 bytes
        private const val ATT_TIMEOUT_MICROS = 400_000L

        // Success markers, observed in captured ECU reply.
        private val MARKER_SSM2_REPLY_HEADER = byteArrayOf(0x80.toByte(), 0xF0.toByte(), 0x10.toByte())
        private val MARKER_CALIBRATION_ID = byteArrayOf(
            0x45.toByte(),
            0x1A.toByte(),
            0x35.toByte(),
            0x40.toByte(),
            0x06.toByte()
        )
    }

    data class ProbeResult(
        val log: List<TactrixCommandLog>,
        val outcome: ProbeOutcome,
        val ecuReplyBytes: ByteArray?,
        val ssm2DecodeBundle: Ssm2DecodeBundle?,
        val attStepDurationMs: Long?
    )

    enum class ProbeOutcome {
        SUCCESS_ECU_REPLIED,
        FAIL_INIT_STEP,
        FAIL_NO_ECU_REPLY,
        FAIL_TRANSPORT,
        FAIL_USB_DISCONNECTED
    }

    /**
     * Runs the 12-step adapter setup sequence (ati → atv) plus the 200 ms
     * settle gap. Returns true when every step passed; appends each step's
     * [TactrixCommandLog] entry to [log] regardless.
     *
     * After a successful return the channel is open and ready for att3
     * transmits. The caller must NOT call [client.resetRequestIdCounter] or
     * [client.drainResponseBuffer] before this — do that once before calling
     * this method.
     */
    fun initializeChannel(log: MutableList<TactrixCommandLog>): Boolean {
        if (client.channelInitialized) {
            log.add(
                TactrixCommandLog(
                    stepIndex = 0,
                    stepLabel = "channel reuse — ati→atv already initialized, skipping",
                    requestAscii = "",
                    requestHex = "",
                    responseAscii = "",
                    responseHex = "",
                    durationMs = 0L,
                    outcome = TactrixCommandLog.Outcome.PASS,
                    notes = ""
                )
            )
            return true
        }
        runStep(log, 1, "ati — device bring-up") {
            client.sendAsciiCommand("ati", appendReqId = false, expectAck = false, readTimeoutMs = 1500L)
        }
        if (log.last().outcome != TactrixCommandLog.Outcome.PASS) return false

        runStep(log, 2, "ata — atomic activate") {
            client.sendAsciiCommand("ata", appendReqId = true, expectAck = true, readTimeoutMs = 1500L)
        }
        if (log.last().outcome != TactrixCommandLog.Outcome.PASS) return false

        runStep(log, 3, "atp 5 2 — open protocol with 2-byte sub-param") {
            client.sendAsciiPlusBinary(
                asciiBodyWithoutReqId = "atp 5 2",
                binaryTail = ATP_BINARY_TAIL,
                appendReqId = true,
                expectVehicleFrameOnChannel = null,
                readTimeoutMs = 1500L
            )
        }
        if (log.last().outcome != TactrixCommandLog.Outcome.PASS) return false

        runStep(log, 4, "ato3 — channel options (bitfield 512, baud 4800)") {
            client.sendAsciiCommand("ato3 512 4800 894790475", appendReqId = true, expectAck = true)
        }
        if (log.last().outcome != TactrixCommandLog.Outcome.PASS) return false

        val ats3Body = listOf("ats3 22 0", "ats3 22 0", "ats3 7 1", "ats3 10 0", "ats3 12 0", "ats3 3 0")
        for ((idx, body) in ats3Body.withIndex()) {
            runStep(log, 5 + idx, "$body — IOCTL parameter write") {
                client.sendAsciiCommand(body, appendReqId = true, expectAck = true)
            }
            if (log.last().outcome != TactrixCommandLog.Outcome.PASS) return false
        }

        runStep(log, 11, "atf3 — receive filter") {
            client.sendAsciiPlusBinary(
                asciiBodyWithoutReqId = "atf3 1 0 4",
                binaryTail = ATF_BINARY_TAIL,
                appendReqId = true,
                expectVehicleFrameOnChannel = null,
                readTimeoutMs = 1500L
            )
        }
        if (log.last().outcome != TactrixCommandLog.Outcome.PASS) return false

        runStep(log, 12, "atv 9 -1 — voltage probe / line check") {
            client.sendAsciiCommand("atv 9 -1", appendReqId = true, expectAck = true)
        }
        if (log.last().outcome != TactrixCommandLog.Outcome.PASS) return false

        runSettle(log, 13)
        client.channelInitialized = true
        return true
    }

    fun run(): ProbeResult {
        val log = mutableListOf<TactrixCommandLog>()
        client.drainResponseBuffer()
        client.resetRequestIdCounter(startFrom = 2)

        try {
        if (!initializeChannel(log)) {
            return ProbeResult(log, ProbeOutcome.FAIL_INIT_STEP, null, null, null)
        }

        // Step 14: att3 6 0 400000 <reqid> + 80 10 F0 01 BF 40
        val sendStart = System.currentTimeMillis()
        val attOutcome = client.sendAsciiPlusBinary(
            asciiBodyWithoutReqId = "att3 ${SSM2_READ_ID_FRAME.size} 0 $ATT_TIMEOUT_MICROS",
            binaryTail = SSM2_READ_ID_FRAME,
            appendReqId = true,
            expectVehicleFrameOnChannel = K_LINE_CHANNEL,
            readTimeoutMs = 3000L
        )
        val sendDuration = System.currentTimeMillis() - sendStart

        val raw = TactrixHex.parseHexPayload(attOutcome.responseHex.replace(" ", ""))
        val vehicleFrame = client.extractVehicleFrame(raw, K_LINE_CHANNEL)

        val attLogOutcome: TactrixCommandLog.Outcome
        val notes: String
        val probeOutcome: ProbeOutcome
        when {
            vehicleFrame == null && !attOutcome.matched -> {
                attLogOutcome = TactrixCommandLog.Outcome.FAIL_TIMEOUT
                notes = "No ar3 frame within timeout"
                probeOutcome = ProbeOutcome.FAIL_NO_ECU_REPLY
            }
            vehicleFrame == null -> {
                attLogOutcome = TactrixCommandLog.Outcome.FAIL_NO_RESPONSE
                notes = "ar3 frame not found in response"
                probeOutcome = ProbeOutcome.FAIL_NO_ECU_REPLY
            }
            containsSubsequence(vehicleFrame, MARKER_SSM2_REPLY_HEADER) ||
                containsSubsequence(vehicleFrame, MARKER_CALIBRATION_ID) -> {
                attLogOutcome = TactrixCommandLog.Outcome.PASS
                notes = "SSM2 reply header or calibration ID present"
                probeOutcome = ProbeOutcome.SUCCESS_ECU_REPLIED
            }
            else -> {
                attLogOutcome = TactrixCommandLog.Outcome.FAIL_NO_RESPONSE
                notes = "ar3 frame present but no SSM2 reply / calibration ID match"
                probeOutcome = ProbeOutcome.FAIL_NO_ECU_REPLY
            }
        }

        log.add(
            TactrixCommandLog(
                stepIndex = 14,
                stepLabel = "att3 — transmit SSM2 read ECU ID (80 10 F0 01 BF 40)",
                requestAscii = attOutcome.requestAscii,
                requestHex = attOutcome.requestHex,
                responseAscii = attOutcome.responseAscii,
                responseHex = attOutcome.responseHex,
                durationMs = sendDuration,
                outcome = attLogOutcome,
                notes = notes
            )
        )

        if (probeOutcome != ProbeOutcome.SUCCESS_ECU_REPLIED) {
            client.channelInitialized = false
        }

        val parsedRequest = Ssm2FrameParser.parseSsm2Frame(SSM2_READ_ID_FRAME)
        val parsedResponse = vehicleFrame?.let { Ssm2FrameParser.parseSsm2Frame(it) }
        val decodedEcuId = parsedResponse?.let { EcuIdDecoder.decodeEcuIdResponse(it) }
        val aroMatched = attOutcome.reqIdUsed >= 0 &&
            attOutcome.responseAscii.contains("aro ${attOutcome.reqIdUsed}")
        val bundle = Ssm2DecodeBundle(
            request = parsedRequest,
            response = parsedResponse,
            ecuId = decodedEcuId,
            aroAcknowledged = aroMatched,
            ar3FrameDetected = vehicleFrame != null,
            extractedFrameHex = vehicleFrame?.let { TactrixHex.bytesToHex(it) } ?: ""
        )

        return ProbeResult(log, probeOutcome, vehicleFrame, bundle, sendDuration)
        } catch (e: UsbDisconnectedException) {
            client.channelInitialized = false
            return ProbeResult(log, ProbeOutcome.FAIL_USB_DISCONNECTED, null, null, null)
        }
    }

    private inline fun runStep(
        log: MutableList<TactrixCommandLog>,
        stepIndex: Int,
        label: String,
        action: () -> TactrixClient.Outcome
    ) {
        val start = System.currentTimeMillis()
        val outcome: TactrixClient.Outcome = try {
            action()
        } catch (e: UsbDisconnectedException) {
            log.add(
                TactrixCommandLog(
                    stepIndex = stepIndex,
                    stepLabel = label,
                    requestAscii = "",
                    requestHex = "",
                    responseAscii = "",
                    responseHex = "",
                    durationMs = System.currentTimeMillis() - start,
                    outcome = TactrixCommandLog.Outcome.FAIL_INTERNAL,
                    notes = "USB disconnected: ${e.message}"
                )
            )
            throw e  // rethrow so run() / initializeChannel() can short-circuit
        } catch (e: Exception) {
            log.add(
                TactrixCommandLog(
                    stepIndex = stepIndex,
                    stepLabel = label,
                    requestAscii = "",
                    requestHex = "",
                    responseAscii = "",
                    responseHex = "",
                    durationMs = System.currentTimeMillis() - start,
                    outcome = TactrixCommandLog.Outcome.FAIL_INTERNAL,
                    notes = e.message ?: e.javaClass.simpleName
                )
            )
            return
        }
        val duration = System.currentTimeMillis() - start
        val mappedOutcome = when {
            outcome.matched -> TactrixCommandLog.Outcome.PASS
            outcome.responseHex.isEmpty() -> TactrixCommandLog.Outcome.FAIL_NO_RESPONSE
            else -> TactrixCommandLog.Outcome.FAIL_ADAPTER_ERROR
        }
        log.add(
            TactrixCommandLog(
                stepIndex = stepIndex,
                stepLabel = label,
                requestAscii = outcome.requestAscii,
                requestHex = outcome.requestHex,
                responseAscii = outcome.responseAscii,
                responseHex = outcome.responseHex,
                durationMs = duration,
                outcome = mappedOutcome,
                notes = outcome.notes
            )
        )
    }

    private fun runSettle(log: MutableList<TactrixCommandLog>, stepIndex: Int) {
        val start = System.currentTimeMillis()
        try {
            Thread.sleep(SETTLE_MS)
        } catch (_: InterruptedException) {
        }
        log.add(
            TactrixCommandLog(
                stepIndex = stepIndex,
                stepLabel = "settle (~$SETTLE_MS ms)",
                requestAscii = "",
                requestHex = "",
                responseAscii = "",
                responseHex = "",
                durationMs = System.currentTimeMillis() - start,
                outcome = TactrixCommandLog.Outcome.PASS,
                notes = ""
            )
        )
    }

    private fun containsSubsequence(haystack: ByteArray, needle: ByteArray): Boolean {
        if (needle.isEmpty() || haystack.size < needle.size) return false
        outer@ for (i in 0..haystack.size - needle.size) {
            for (j in needle.indices) {
                if (haystack[i + j] != needle[j]) continue@outer
            }
            return true
        }
        return false
    }
}
