package com.protocol.app.openport2

import com.protocol.app.obdlink.ObdLinkBtTransport
import com.protocol.app.obdlink.ObdLinkSsm2Can

/**
 * One-shot SSM2 "clear memory" — the DTC reset.
 *
 * Writes the clear-memory control byte to the ECM's diagnostic control address
 * via the SSM2 single-address write (B8). This is the first command in the app
 * that *writes* to the ECU; everything else (live data, DTC read) is read-only.
 *
 * Clear level 1 ([CM_VALUE_CLEAR]) clears stored + current trouble codes. As an
 * inherent side effect the ECU also resets adaptive learning (fuel trims, idle)
 * — that is how SSM2 clear-memory works, not a defect; the ECU relearns over the
 * next drive cycle.
 *
 * Per transport, mirroring [Ssm2DtcRead]: it borrows the link the user already
 * connected and adds no transport state. The write is verified by the ECU's own
 * echo (F8 <value>), so a silent or corrupt link reports failure rather than a
 * false "cleared".
 *
 * ECM only (destination 0x10). Provenance: the B8 write frame, the clear-memory
 * address ([CM_ADDRESS]) and the value are all verified against an observed
 * clear-memory exchange on the wire — over CAN the request payload is
 * `B8 00 00 60 40` and the ECU answers `F8 40` (the written byte echoed). The
 * K-line path sends the same command inside the 80/10/F0 frame wrapper.
 *
 * Covers both adapter families — OpenPort (att/ar) and OBDLink/STN (STPX / raw
 * ISO-TP) — over K-line and CAN, mirroring [Ssm2DtcRead]'s four transport paths.
 */
object Ssm2DtcClear {

    /** ECM diagnostic clear-memory control address. */
    const val CM_ADDRESS = 0x000060

    /** Clear-memory level 1: clears DTCs (and resets adaptions). */
    const val CM_VALUE_CLEAR: Byte = 0x40

    data class Result(val ok: Boolean, val message: String)

    private val cmAddr: Ssm2Address get() = DtcCatalog.toAddress(CM_ADDRESS)

    fun clearOpenPortKline(client: TactrixClient): Result {
        val frame = Ssm2WriteQuery.buildB8Write(cmAddr, CM_VALUE_CLEAR, Ssm2AddressQuery.DEST_ECM)
        val outcome = client.sendAsciiPlusBinary(
            asciiBodyWithoutReqId = "att$K_LINE_CHANNEL ${frame.size} 0 400000",
            binaryTail = frame,
            appendReqId = true,
            expectVehicleFrameOnChannel = K_LINE_CHANNEL,
            expectedReplySource = Ssm2AddressQuery.DEST_ECM,
            readTimeoutMs = 2000L
        )
        if (!outcome.matched) {
            return Result(false, "No response from the ECU — check the connection and that the key is on.")
        }
        val raw = TactrixHex.parseHexPayload(outcome.responseHex.replace(" ", ""))
        val vf = client.extractVehicleFrame(raw, K_LINE_CHANNEL, Ssm2AddressQuery.DEST_ECM)
        val parsed = vf?.let { Ssm2FrameParser.parseSsm2Frame(it) }
        return when {
            parsed == null -> Result(false, "No valid reply frame from the ECU — not cleared.")
            parsed.truncated || !parsed.checksumValid -> Result(false, "Corrupt reply from the ECU — not cleared.")
            Ssm2WriteQuery.isWriteAck(parsed, CM_VALUE_CLEAR) -> Result(true, "Trouble codes cleared.")
            else -> Result(false, "ECU rejected the clear command — not cleared.")
        }
    }

    fun clearOpenPortCan(source: OpenPortCanLiveSource): Result {
        if (!source.initChannel()) return Result(false, "CAN channel open failed.")
        val ok = source.writeAddressOnce(cmAddr, CM_VALUE_CLEAR)
        return if (ok) Result(true, "Trouble codes cleared.")
        else Result(false, "No / unexpected response from the ECU — not cleared.")
    }

    fun clearObdLinkKline(transport: ObdLinkBtTransport): Result {
        val frame = Ssm2WriteQuery.buildB8Write(cmAddr, CM_VALUE_CLEAR, Ssm2AddressQuery.DEST_ECM)
        val cmd = "STPX d:${ObdLinkSsm2Can.toElmHex(frame)},r:1,t:1000"
        val ascii = try { transport.sendAscii(cmd, timeoutMs = 2000L) } catch (e: Exception) { null }
            ?: return Result(false, "No response from the ECU — check the connection and that the key is on.")
        return if (klineWriteAck(ObdLinkSsm2Can.parseElmHex(ascii), CM_VALUE_CLEAR)) {
            Result(true, "Trouble codes cleared.")
        } else {
            Result(false, "No / unexpected response from the ECU — not cleared.")
        }
    }

    fun clearObdLinkCan(transport: ObdLinkBtTransport): Result {
        val reqHex = ObdLinkSsm2Can.toElmHex(ObdLinkSsm2Can.buildWritePayload(cmAddr, CM_VALUE_CLEAR))
        val ascii = try { transport.sendAscii(reqHex, timeoutMs = 1000L) } catch (e: Exception) { null }
            ?: return Result(false, "No response from the ECU — not cleared.")
        return if (ObdLinkSsm2Can.isWriteAck(ObdLinkSsm2Can.parseElmHex(ascii), CM_VALUE_CLEAR)) {
            Result(true, "Trouble codes cleared.")
        } else {
            Result(false, "No / unexpected response from the ECU — not cleared.")
        }
    }

    /**
     * Scan an OBDLink K-line reply buffer for a valid 80 F0 10 … F8 write-ack
     * frame echoing [value] (mirrors [Ssm2DtcRead]'s A8 reply scan). Checksum +
     * truncation are rejected inside [Ssm2WriteQuery.isWriteAck].
     */
    private fun klineWriteAck(bytes: ByteArray, value: Byte): Boolean {
        var i = 0
        while (i <= bytes.size - 5) {
            if ((bytes[i].toInt() and 0xFF) == 0x80 &&
                (bytes[i + 1].toInt() and 0xFF) == 0xF0 &&
                (bytes[i + 2].toInt() and 0xFF) == 0x10
            ) {
                val len = bytes[i + 3].toInt() and 0xFF
                val total = len + 5
                if (i + total <= bytes.size) {
                    val parsed = Ssm2FrameParser.parseSsm2Frame(bytes.copyOfRange(i, i + total))
                    if (parsed != null && Ssm2WriteQuery.isWriteAck(parsed, value)) return true
                }
            }
            i++
        }
        return false
    }
}
