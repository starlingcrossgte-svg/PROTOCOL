package com.protocol.app.openport2

/**
 * Builds and parses SSM2 A8 (read address) request/response frames.
 *
 * A8 request layout:
 *   [0]   0x80  header
 *   [1]   0x10  destination (ECM)
 *   [2]   0xF0  source (tester)
 *   [3]   len   2 + (addressCount * 3)
 *   [4]   0xA8  command
 *   [5]   0x00  flags (padding byte observed in wireshark captures)
 *   [6..] address bytes (3 bytes each: high, mid, low)
 *   last  checksum = sum of all preceding bytes mod 256
 *
 * A8 response layout:
 *   [0]   0x80  header
 *   [1]   0xF0  destination (tester)
 *   [2]   0x10  source (ECM)
 *   [3]   len
 *   [4]   0xE8  response code (A8 | 0x40)
 *   [5..] one data byte per address, same order as request
 *   last  checksum
 *
 * Verified against RomRaider traffic in wireshark-sequencelab/romraider-traffic-raw.txt:
 *   Request:  80 10 F0 08 A8 00 00 00 1C 00 00 0C 58
 *   Response: 80 F0 10 03 E8 9B 81 87
 *   (two addresses: 0x00001C=battery, 0x00000C=coolant)
 */
data class Ssm2Address(val high: Byte, val mid: Byte, val low: Byte)

object Ssm2AddressQuery {

    private const val CMD_A8: Byte = 0xA8.toByte()
    private const val RSP_E8: Byte = 0xE8.toByte()

    fun buildA8Query(addresses: List<Ssm2Address>, flags: Byte = 0x00): ByteArray {
        require(addresses.isNotEmpty()) { "at least one address required" }
        val dataLen = 2 + addresses.size * 3
        val frameLen = dataLen + 5 // header(3) + len(1) + data + checksum(1)
        val frame = ByteArray(frameLen)
        frame[0] = 0x80.toByte()
        frame[1] = 0x10.toByte()
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
     * unsigned int per address. Returns null if the frame is too short or the
     * response code is not 0xE8.
     */
    fun parseA8Response(frame: Ssm2Frame, addressCount: Int): IntArray? {
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
