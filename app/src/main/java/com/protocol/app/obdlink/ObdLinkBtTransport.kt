package com.protocol.app.obdlink

import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * ELM327/STN text-protocol exchange over an already-open Bluetooth SPP stream
 * pair (OBDLink MX+). One ASCII command per call, terminated with '\r'; reads
 * until the '>' prompt (0x3E) or timeout.
 *
 * Mirrors the proven OBDLink EX exchange loop from the archived sequencelab
 * transport, with the usb-serial port swapped for Bluetooth streams — so no
 * third-party dependency is needed (Android's BluetoothSocket gives us the
 * streams). EX and MX+ are both STN/ELM327 and speak the identical command
 * set, so this loop is unchanged in spirit.
 *
 * Pure I/O: knows nothing about SSM2 or CAN. [ObdLinkBtManager] layers the ELM
 * channel setup + SSM2-over-CAN payloads ([ObdLinkSsm2Can]) on top.
 */
class ObdLinkBtTransport(
    private val input: InputStream,
    private val output: OutputStream,
    private val log: (direction: String, text: String) -> Unit = { _, _ -> }
) {
    /**
     * Sends [command] (a trailing '\r' is added if absent) and returns the
     * adapter's ASCII response up to and including the '>' prompt, or whatever
     * arrived before [timeoutMs] elapsed. Propagates IOException from the
     * underlying stream (caller treats it as a dropped link).
     */
    fun sendAscii(command: String, timeoutMs: Long = 1000L): String {
        val line = if (command.endsWith("\r")) command else "$command\r"
        log("OUT", line.trimEnd('\r'))
        output.write(line.toByteArray(StandardCharsets.US_ASCII))
        output.flush()
        val resp = readUntilPrompt(timeoutMs)
        log("IN", resp.trim())
        return resp
    }

    /** Discards anything currently buffered on the input (stale prompts/banner). */
    fun drain(maxMs: Long = 200L) {
        val deadline = System.currentTimeMillis() + maxMs
        val buf = ByteArray(256)
        while (System.currentTimeMillis() < deadline) {
            val avail = input.available()
            if (avail <= 0) return
            input.read(buf, 0, minOf(avail, buf.size))
        }
    }

    private fun readUntilPrompt(timeoutMs: Long): String {
        val deadline = System.currentTimeMillis() + timeoutMs
        val sb = StringBuilder()
        val buf = ByteArray(256)
        while (System.currentTimeMillis() < deadline) {
            val avail = input.available()
            if (avail > 0) {
                val n = input.read(buf, 0, minOf(avail, buf.size))
                if (n > 0) {
                    for (i in 0 until n) sb.append((buf[i].toInt() and 0xFF).toChar())
                    if (sb.indexOf(">") >= 0) break
                }
            } else {
                try { Thread.sleep(5) } catch (_: InterruptedException) { break }
            }
        }
        return sb.toString()
    }
}
