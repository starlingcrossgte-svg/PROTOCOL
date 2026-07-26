package com.protocol.app.openport2

/**
 * Builds and validates SSM2 B8 (write single address) request/response frames.
 *
 * This is the first SSM2 *write* in the app — every other path (live data, DTC
 * read) is read-only A8. The write command differs from the A8 read in two ways
 * confirmed against the reference SSM2 implementation:
 *   - the command byte is 0xB8 (read is 0xA8), and
 *   - there is NO padding/flag byte after the command (A8 carries a 0x00 flag).
 *
 * B8 request layout (single address):
 *   [0]   0x80   header
 *   [1]   dest   destination module — 0x10 = ECM, 0x18 = TCM
 *   [2]   0xF0   source (tester)
 *   [3]   0x05   len = 1 (cmd) + 3 (address) + 1 (data)
 *   [4]   0xB8   command
 *   [5..7]       address bytes (high, mid, low)
 *   [8]   data   the byte to write
 *   [9]   cksum  sum of all preceding bytes mod 256
 *
 * B8 response layout:
 *   [0]   0x80
 *   [1]   0xF0   destination (tester)
 *   [2]   src    source module (mirror of request dest)
 *   [3]   0x02   len
 *   [4]   0xF8   response code (B8 | 0x40)
 *   [5]   data   the byte just written, echoed back
 *   [6]   cksum
 */
object Ssm2WriteQuery {

    private const val CMD_B8: Byte = 0xB8.toByte()
    private const val RSP_F8: Byte = 0xF8.toByte()

    fun buildB8Write(
        address: Ssm2Address,
        value: Byte,
        destination: Byte = Ssm2AddressQuery.DEST_ECM
    ): ByteArray {
        val dataLen = 5 // cmd(1) + address(3) + data(1)
        val frameLen = dataLen + 5 // header(3) + len(1) + data + checksum(1)
        val frame = ByteArray(frameLen)
        frame[0] = 0x80.toByte()
        frame[1] = destination
        frame[2] = 0xF0.toByte()
        frame[3] = dataLen.toByte()
        frame[4] = CMD_B8
        frame[5] = address.high
        frame[6] = address.mid
        frame[7] = address.low
        frame[8] = value
        frame[frameLen - 1] = checksum(frame, 0, frameLen - 1)
        return frame
    }

    /**
     * True only if [frame] is a valid B8 positive response (0xF8) that echoes
     * [expectedValue]. Truncation and a bad checksum are rejected here so a
     * corrupt link can never be read as a successful write.
     */
    fun isWriteAck(frame: Ssm2Frame, expectedValue: Byte): Boolean {
        if (frame.truncated) return false
        if (!frame.checksumValid) return false
        val payload = frame.payload
        if (payload.size < 2) return false
        if (payload[0] != RSP_F8) return false
        return payload[1] == expectedValue
    }

    private fun checksum(bytes: ByteArray, from: Int, untilExclusive: Int): Byte {
        var sum = 0
        for (i in from until untilExclusive) sum += bytes[i].toInt() and 0xFF
        return (sum and 0xFF).toByte()
    }
}
