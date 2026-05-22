package com.protocol.app.openport2

/**
 * Everything the UI and exported log need to render the layered view of a
 * single SSM2 transmit step, pre-computed by [Ssm2EcmProbe] so neither the
 * ViewModel nor the screen has to re-parse bytes.
 *
 * For the BF read-ID probe:
 *  - [request]            the parsed `80 10 F0 01 BF 40` frame
 *  - [response]           the parsed reply frame, or null on failure
 *  - [ecuId]              5-byte calibration ID + display hex, or null on failure
 *  - [aroAcknowledged]    true if `aro <reqId>` ack matched the att3 reqId
 *  - [ar3FrameDetected]   true if a Tactrix `ar<channel>` vehicle wrapper was found
 *  - [extractedFrameHex]  space-separated hex of the extracted vehicle payload
 *                         (may differ from response.rawBytes when extraction
 *                         pulled in trailing bytes not part of the SSM2 frame)
 */
data class Ssm2DecodeBundle(
    val request: Ssm2Frame?,
    val response: Ssm2Frame?,
    val ecuId: EcuIdDecode?,
    val aroAcknowledged: Boolean,
    val ar3FrameDetected: Boolean,
    val extractedFrameHex: String
)
