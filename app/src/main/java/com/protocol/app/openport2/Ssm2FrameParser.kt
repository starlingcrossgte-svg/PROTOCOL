package com.protocol.app.openport2

/**
 * Decoded SSM2 frame.
 *
 * SSM2 frame layout on the wire:
 *   [0] format      (always 0x80)
 *   [1] destination (e.g. 0x10 = ECM, 0xF0 = tester)
 *   [2] source
 *   [3] length      (count of payload bytes between length and checksum)
 *   [4..4+length-1] payload (first payload byte is the command/response code)
 *   [4+length]      checksum (sum of bytes[0..4+length-1] mod 256)
 *
 * Total expected size = length + 5.
 *
 * If we only have part of the frame (e.g. the Tactrix `ar3` wrapper split the
 * reply across multiple bulk-IN reads and only the first wrapper has been
 * concatenated), [truncated] is set and [checksumValid] is false. The
 * [payload] field then carries whatever bytes are available after the header.
 */
data class Ssm2Frame(
    val rawBytes: ByteArray,
    val format: Int,
    val destination: Int,
    val source: Int,
    val length: Int,
    val payload: ByteArray,
    val checksum: Int,            // -1 if truncated
    val checksumValid: Boolean,
    val truncated: Boolean
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ssm2Frame) return false
        if (!rawBytes.contentEquals(other.rawBytes)) return false
        if (format != other.format) return false
        if (destination != other.destination) return false
        if (source != other.source) return false
        if (length != other.length) return false
        if (!payload.contentEquals(other.payload)) return false
        if (checksum != other.checksum) return false
        if (checksumValid != other.checksumValid) return false
        if (truncated != other.truncated) return false
        return true
    }

    override fun hashCode(): Int {
        var result = rawBytes.contentHashCode()
        result = 31 * result + format
        result = 31 * result + destination
        result = 31 * result + source
        result = 31 * result + length
        result = 31 * result + payload.contentHashCode()
        result = 31 * result + checksum
        result = 31 * result + checksumValid.hashCode()
        result = 31 * result + truncated.hashCode()
        return result
    }
}

object Ssm2FrameParser {

    /**
     * Parse bytes into an [Ssm2Frame]. Returns null when [bytes] is too short
     * to contain even the 4-byte header. Otherwise always returns a frame; the
     * frame's [Ssm2Frame.truncated] flag indicates whether all `length + 5`
     * bytes were available.
     */
    fun parseSsm2Frame(bytes: ByteArray): Ssm2Frame? {
        if (bytes.size < 4) return null
        val format = bytes[0].toInt() and 0xFF
        val destination = bytes[1].toInt() and 0xFF
        val source = bytes[2].toInt() and 0xFF
        val length = bytes[3].toInt() and 0xFF
        val expectedTotal = length + 5
        return if (bytes.size >= expectedTotal) {
            val payload = bytes.copyOfRange(4, 4 + length)
            val checksum = bytes[4 + length].toInt() and 0xFF
            val computed = computeChecksum(bytes, 0, 4 + length)
            Ssm2Frame(
                rawBytes = bytes.copyOfRange(0, expectedTotal),
                format = format,
                destination = destination,
                source = source,
                length = length,
                payload = payload,
                checksum = checksum,
                checksumValid = checksum == computed,
                truncated = false
            )
        } else {
            val payload = bytes.copyOfRange(4, bytes.size)
            Ssm2Frame(
                rawBytes = bytes.copyOf(),
                format = format,
                destination = destination,
                source = source,
                length = length,
                payload = payload,
                checksum = -1,
                checksumValid = false,
                truncated = true
            )
        }
    }

    /**
     * Sum of bytes[from..untilExclusive-1] mod 256.
     */
    private fun computeChecksum(bytes: ByteArray, from: Int, untilExclusive: Int): Int {
        var sum = 0
        for (i in from until untilExclusive) {
            sum += bytes[i].toInt() and 0xFF
        }
        return sum and 0xFF
    }

    fun moduleLabel(byte: Int): String = when (byte and 0xFF) {
        0x10 -> "ECM"
        0x18 -> "TCM"
        0xF0 -> "tester"
        else -> "unknown (0x%02X)".format(byte and 0xFF)
    }

    fun commandLabel(byte: Int): String = when (byte and 0xFF) {
        0xBF -> "read ECU ID (BF)"
        0xFF -> "read ECU ID response (FF)"
        0xA8 -> "read addresses (A8)"
        0xE8 -> "read addresses response (E8)"
        else -> "unknown (0x%02X)".format(byte and 0xFF)
    }
}
