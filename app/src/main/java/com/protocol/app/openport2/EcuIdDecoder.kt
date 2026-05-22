package com.protocol.app.openport2

/**
 * Result of decoding an SSM2 read-ECU-ID (BF) response.
 *
 * Layout of a BF response payload, confirmed against the SSM2 spec by the
 * full 62-byte multi-frame capture on 2026-05-21
 * (`80 F0 10 39 FF A2 10 12 45 1A 35 40 06 ...`):
 *
 *   payload[0]    = 0xFF (response code for BF)
 *   payload[1..3] = 3-byte SSM ID / ROM ID (ECU type identifier — A2 10 12
 *                   for the EZ30R family on the PoC car)
 *   payload[4..8] = 5-byte calibration ID (specific ROM revision)
 *   payload[9..]  = 48-byte capability bitmap (which SSM2 addresses are
 *                   supported by this ECU; see Phase 4 work for parsing)
 *
 * No static lookup table is consulted. [internalIdNote] is a constant string
 * that explicitly states the internal/display name was not in the response.
 * Any human-readable label like "D0XJ001P" comes from a tuning suite's own
 * definitions file, NOT from the bytes the ECU sends.
 */
data class EcuIdDecode(
    val ssmIdBytes: ByteArray,
    val ssmIdHex: String,
    val calibrationBytes: ByteArray,
    val ecuIdHex: String,
    val internalIdNote: String
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EcuIdDecode) return false
        if (!ssmIdBytes.contentEquals(other.ssmIdBytes)) return false
        if (ssmIdHex != other.ssmIdHex) return false
        if (!calibrationBytes.contentEquals(other.calibrationBytes)) return false
        if (ecuIdHex != other.ecuIdHex) return false
        if (internalIdNote != other.internalIdNote) return false
        return true
    }

    override fun hashCode(): Int {
        var result = ssmIdBytes.contentHashCode()
        result = 31 * result + ssmIdHex.hashCode()
        result = 31 * result + calibrationBytes.contentHashCode()
        result = 31 * result + ecuIdHex.hashCode()
        result = 31 * result + internalIdNote.hashCode()
        return result
    }
}

object EcuIdDecoder {

    const val INTERNAL_ID_NOT_PRESENT = "not present in this response"

    /**
     * Decode a parsed BF response frame into the 5-byte calibration ID + its
     * concatenated-hex display form. Returns null when the frame is not a BF
     * response or its payload is too short to contain a calibration ID at the
     * verified offset.
     */
    fun decodeEcuIdResponse(responseFrame: Ssm2Frame): EcuIdDecode? {
        val payload = responseFrame.payload
        if (payload.isEmpty()) return null
        val responseCode = payload[0].toInt() and 0xFF
        if (responseCode != 0xFF) return null
        if (payload.size < 9) return null // need response code + 3 SSM-ID bytes + 5 cal-ID bytes
        val ssm = payload.copyOfRange(1, 4)
        val ssmHex = buildString(ssm.size * 2) {
            for (b in ssm) append("%02X".format(b.toInt() and 0xFF))
        }
        val cal = payload.copyOfRange(4, 9)
        val hex = buildString(cal.size * 2) {
            for (b in cal) append("%02X".format(b.toInt() and 0xFF))
        }
        return EcuIdDecode(
            ssmIdBytes = ssm,
            ssmIdHex = ssmHex,
            calibrationBytes = cal,
            ecuIdHex = hex,
            internalIdNote = INTERNAL_ID_NOT_PRESENT
        )
    }
}
