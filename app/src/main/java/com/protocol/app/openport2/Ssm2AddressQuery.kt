package com.protocol.app.openport2

/**
 * Builds and parses SSM2 A8 (read address) request/response frames.
 *
 * A8 request layout:
 *   [0]   0x80   header
 *   [1]   dest   destination module — 0x10 = ECM, 0x18 = TCM
 *   [2]   0xF0   source (tester)
 *   [3]   len    2 + (addressCount * 3)
 *   [4]   0xA8   command
 *   [5]   0x00   flags (padding byte observed in captures)
 *   [6..] address bytes (3 bytes each: high, mid, low)
 *   last  checksum = sum of all preceding bytes mod 256
 *
 * A8 response layout:
 *   [0]   0x80   header
 *   [1]   0xF0   destination (tester)
 *   [2]   src    source module (mirror of request dest)
 *   [3]   len
 *   [4]   0xE8   response code (A8 | 0x40)
 *   [5..] one data byte per address, same order as request
 *   last  checksum
 *
 * Verified against traffic for both modules:
 *   ECM request : 80 10 F0 08 A8 00 00 00 1C 00 00 0C 58
 *   ECM reply   : 80 F0 10 03 E8 9B 81 87
 *   TCM request: 80 18 F0 20 A8 00 [10 addresses...] B9
 *   TCM reply   : 80 F0 18 0B E8 [10 data bytes...]   B3
 */
data class Ssm2Address(val high: Byte, val mid: Byte, val low: Byte)

object Ssm2AddressQuery {

    const val DEST_ECM: Byte = 0x10.toByte()
    const val DEST_TCM: Byte = 0x18.toByte()
    private const val CMD_A8: Byte = 0xA8.toByte()
    private const val RSP_E8: Byte = 0xE8.toByte()

    fun buildA8Query(
        addresses: List<Ssm2Address>,
        destination: Byte = DEST_ECM,
        flags: Byte = 0x00
    ): ByteArray {
        require(addresses.isNotEmpty()) { "at least one address required" }
        val dataLen = 2 + addresses.size * 3
        val frameLen = dataLen + 5 // header(3) + len(1) + data + checksum(1)
        val frame = ByteArray(frameLen)
        frame[0] = 0x80.toByte()
        frame[1] = destination
        frame[2] = 0xF0.toByte()
        frame[3] = dataLen.toByte()
        frame[4] = CMD_A8
        frame[5] = flags
        for ((i, addr) in addresses.withIndex()) {
            val base = 6 + i * 3
            frame[base]     = addr.high
            frame[base + 1] = addr.mid
            frame[base + 2] = addr.low
        }
        frame[frameLen - 1] = checksum(frame, 0, frameLen - 1)
        return frame
    }

    /**
     * Parses a raw SSM2 A8 response frame (starting at `80 F0 10`) into one
     * unsigned int per address. Returns null if the frame is truncated, the
     * checksum is invalid, the response code is not 0xE8, or the payload is
     * too short to cover [addressCount] data bytes.
     *
     * Checksum and truncation are rejected here (not just at the parser level)
     * so corrupt K-line frames cannot be decoded into gauge values.
     */
    fun parseA8Response(frame: Ssm2Frame, addressCount: Int): IntArray? {
        if (frame.truncated) return null
        if (!frame.checksumValid) return null
        val payload = frame.payload
        if (payload.isEmpty()) return null
        if (payload[0] != RSP_E8) return null
        if (payload.size < 1 + addressCount) return null
        return IntArray(addressCount) { i -> payload[1 + i].toInt() and 0xFF }
    }

    private fun checksum(bytes: ByteArray, from: Int, untilExclusive: Int): Byte {
        var sum = 0
        for (i in from until untilExclusive) sum += bytes[i].toInt() and 0xFF
        return (sum and 0xFF).toByte()
    }
}
