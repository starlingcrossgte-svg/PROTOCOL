package com.protocol.app.obdlink

import com.protocol.app.openport2.Ssm2Address

/**
 * SSM2-over-CAN frame codec for the OBDLink (Bluetooth) live-data path.
 *
 * Over CAN (ISO-TP) the SSM2 command travels WITHOUT the K-line
 * `80 <dest> F0 <len> ... <checksum>` wrapper — the raw command bytes are the
 * ISO-TP payload, and the adapter/ECU handle framing + integrity. The module
 * (ECM vs TCM) is selected by the CAN arbitration ID, not a dest byte.
 *
 * Decoded from the SSM2-over-CAN capture:
 *   request  → 0x7E0 :  A8 00 <hi mid lo> [<hi mid lo> ...]
 *   reply    ← 0x7E8 :  E8 <one data byte per address, same order>
 *
 * Zero coupling to the OpenPort/K-line code: this builds/parses the CAN
 * payload only. [ObdLinkBtTransport] handles ELM hex <-> bytes; the OBDLink
 * adapter handles ISO-TP reassembly so [parseReadResponse] sees the whole
 * reply payload at once.
 */
object ObdLinkSsm2Can {

    private const val CMD_A8 = 0xA8
    private const val RSP_E8 = 0xE8
    private const val CMD_B8 = 0xB8
    private const val RSP_F8 = 0xF8
    private const val READ_FLAG_SINGLE = 0x00 // single response (0x01 would be continuous)

    /** Raw A8 read payload for [addresses]: `A8 00 <hi mid lo>...` (no wrapper, no checksum). */
    fun buildReadPayload(addresses: List<Ssm2Address>): ByteArray {
        require(addresses.isNotEmpty()) { "at least one address required" }
        val out = ByteArray(2 + addresses.size * 3)
        out[0] = CMD_A8.toByte()
        out[1] = READ_FLAG_SINGLE.toByte()
        for ((i, a) in addresses.withIndex()) {
            val base = 2 + i * 3
            out[base] = a.high
            out[base + 1] = a.mid
            out[base + 2] = a.low
        }
        return out
    }

    /**
     * Raw B8 write payload for [address] = [value]: `B8 <hi mid lo> <value>`
     * (no K-line wrapper, no flag byte, no checksum — the adapter/ECU handle
     * ISO-TP framing + integrity over CAN). The write command, unlike the A8
     * read, carries NO 0x00 flag byte after the command.
     */
    fun buildWritePayload(address: Ssm2Address, value: Byte): ByteArray {
        val out = ByteArray(5)
        out[0] = CMD_B8.toByte()
        out[1] = address.high
        out[2] = address.mid
        out[3] = address.low
        out[4] = value
        return out
    }

    /**
     * True only if a reassembled SSM2-over-CAN reply [payload] is a B8 positive
     * response (0xF8) echoing [expectedValue] — i.e. the ECU accepted the write.
     */
    fun isWriteAck(payload: ByteArray, expectedValue: Byte): Boolean {
        if (payload.size < 2) return false
        if ((payload[0].toInt() and 0xFF) != RSP_F8) return false
        return payload[1] == expectedValue
    }

    /**
     * Parse a reassembled SSM2-over-CAN reply [payload] (ISO-TP already
     * de-framed by the adapter) into one unsigned int per address. Returns
     * null if the reply doesn't start with 0xE8 or is too short to cover
     * [addressCount] data bytes.
     */
    fun parseReadResponse(payload: ByteArray, addressCount: Int): IntArray? {
        if (payload.isEmpty()) return null
        if ((payload[0].toInt() and 0xFF) != RSP_E8) return null
        if (payload.size < 1 + addressCount) return null
        return IntArray(addressCount) { i -> payload[1 + i].toInt() and 0xFF }
    }

    /** Hex with no separators, suitable for an ELM data line (e.g. "A800000008"). */
    fun toElmHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }

    /**
     * Parse an ELM ASCII hex reply into raw bytes. Keeps only hex digit pairs,
     * dropping spaces, CR/LF, the '>' prompt, and any line that isn't pure hex
     * (e.g. "SEARCHING...", "NO DATA", echoed headers when ATH is off). With
     * ATH1 the caller is responsible for stripping the leading CAN-ID/PCI
     * bytes before handing the SSM2 payload to [parseReadResponse].
     */
    fun parseElmHex(ascii: String): ByteArray {
        val sb = StringBuilder()
        for (token in ascii.split('\r', '\n', ' ')) {
            val t = token.trim().removeSuffix(">")
            if (t.isNotEmpty() && t.all { it in '0'..'9' || it in 'A'..'F' || it in 'a'..'f' } && t.length % 2 == 0) {
                sb.append(t)
            }
        }
        val hex = sb.toString()
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) + Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}
