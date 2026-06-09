package com.protocol.app.openport2

import com.protocol.app.obdlink.AdapterCommandLibrary
import com.protocol.app.obdlink.CommandKind

/**
 * Routes a manually-typed dev-console command to the OpenPort 2.0 (Tactrix)
 * adapter, applying the OpenPort half of the "hex vs text / spacing" rule
 * (the ELM half lives in [AdapterCommandLibrary.normalizeElm]):
 *
 *  - TEXT (an `at*` control verb) is sent as an ASCII line; the request id is
 *    auto-appended for every verb EXCEPT `ati` (which takes none) — the console
 *    owns reqId management exactly like the live path. Whitespace inside the
 *    line is significant (token-separated args) so it's preserved, only the
 *    ends are trimmed.
 *  - HEX_FRAME (bare SSM2 bytes, typed with or without spaces) is parsed to RAW
 *    BINARY and wrapped in the `att<ch>` transmit header for the selected
 *    protocol:
 *      K-line (ch3): the typed frame is the full `80`-header SSM2 frame; it's
 *                    sent as the binary tail and the reply is matched on the
 *                    SSM2 reply header.
 *      CAN   (ch6): the typed frame is the SSM2 payload (`A8 00 <addr>`); the
 *                    console prepends the `7E0` request CAN-ID and reads the
 *                    raw reply.
 *
 * Every byte (ASCII header + binary tail outbound, and the reply inbound) is
 * mirrored to [UsbTrafficLog] by [TactrixBulkIo], so the dev RAW BYTES log
 * shows the real wire traffic with no extra logging here. Isolated: this only
 * drives an existing [TactrixClient]; it owns no transport and no live-logging
 * state, so it can't entangle the live-logging or flash paths.
 */
object OpenPortConsole {

    // ECM request CAN-ID for OpenPort CAN transmits (00 00 07 E0), prepended to
    // the SSM2 payload in the att6 binary tail — the same id the live CAN path
    // sends to. The console targets the ECM; a TCM frame would use a different id.
    private val CAN_ID_REQUEST = byteArrayOf(0x00, 0x00, 0x07, 0xE0.toByte())

    private const val CAN_CHANNEL = 6
    private const val CAN_TX_FLAGS = 64
    private const val CAN_TX_TIMEOUT_US = 2_000_000L
    private const val KLINE_TX_FLAGS = 0
    private const val KLINE_TX_TIMEOUT_US = 400_000L

    /**
     * Send [input] to the OpenPort via [client]. [kline] picks the K-line (ch3)
     * vs CAN (ch6) transmit framing for a hex frame. Returns a short status
     * string for the connection-status line — the actual request/reply bytes
     * land in the RAW BYTES log via the bulk-IO recorder.
     */
    fun send(client: TactrixClient, input: String, kline: Boolean): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return "empty command"
        client.drainResponseBuffer(200L)

        return when (AdapterCommandLibrary.classifyKind(trimmed)) {
            CommandKind.HEX_FRAME -> sendHexFrame(client, trimmed, kline)
            CommandKind.TEXT -> sendTextCommand(client, trimmed)
        }
    }

    private fun sendTextCommand(client: TactrixClient, line: String): String {
        val verb = line.split(Regex("\\s+")).first().lowercase()
        // `ati` is the only verb with no trailing request id and no `aro` ack;
        // every other verb gets a reqId appended and an ack awaited.
        val isAti = verb == "ati"
        val outcome = client.sendAsciiCommand(
            bodyWithoutReqId = line,
            appendReqId = !isAti,
            expectAck = !isAti
        )
        return if (outcome.matched) "OpenPort: $verb ok" else "OpenPort: $verb sent (no ack — see log)"
    }

    private fun sendHexFrame(client: TactrixClient, hex: String, kline: Boolean): String {
        val frame = AdapterCommandLibrary.hexToBytes(hex)
            ?: return "OpenPort: invalid hex frame (need a whole number of bytes)"
        return if (kline) {
            // K-line: the typed frame already carries `80 <dest> F0 ...`; the
            // reply source byte equals the destination we addressed.
            val dest = if (frame.size >= 2) frame[1] else Ssm2AddressQuery.DEST_ECM
            val outcome = client.sendAsciiPlusBinary(
                asciiBodyWithoutReqId = "att$K_LINE_CHANNEL ${frame.size} $KLINE_TX_FLAGS $KLINE_TX_TIMEOUT_US",
                binaryTail = frame,
                appendReqId = true,
                expectVehicleFrameOnChannel = K_LINE_CHANNEL,
                expectedReplySource = dest
            )
            if (outcome.matched) "OpenPort K-line: reply received — see log" else "OpenPort K-line: no reply"
        } else {
            // CAN: prepend the 7E0 request id; reply (raw E8 …) is read by ack
            // and shown as raw bytes in the log (no 80-header to match on).
            val tail = CAN_ID_REQUEST + frame
            val outcome = client.sendAsciiPlusBinary(
                asciiBodyWithoutReqId = "att$CAN_CHANNEL ${tail.size} $CAN_TX_FLAGS $CAN_TX_TIMEOUT_US",
                binaryTail = tail,
                appendReqId = true,
                expectVehicleFrameOnChannel = null
            )
            if (outcome.matched) "OpenPort CAN: sent — see log" else "OpenPort CAN: no reply (are/timeout)"
        }
    }
}
