package com.protocol.app.kkl

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.os.SystemClock
import com.protocol.app.obdlink.FtdiUsbSerial
import com.protocol.app.obdlink.ObdLinkTrafficLog
import com.protocol.app.openport2.Ssm2FrameParser
import java.io.ByteArrayOutputStream

/**
 * Raw K-line SSM2 transport for a dumb VAG-KKL cable: an FTDI FT232RL
 * (VID 0x0403 / PID 0x6001) wired straight to a K-line line driver, with NO
 * on-board protocol intelligence.
 *
 * Unlike the OBDLink (STN firmware) or Tactrix (OpenPort firmware), every job
 * the adapter used to do for us now happens here, in software, on the phone:
 * half-duplex echo cancellation, byte timing, framing. SSM2 makes that the easy
 * version of K-line — there is no 5-baud / fast-init handshake; we open the UART
 * at 4800 8N1 and send the frame.
 *
 * ## Half-duplex echo
 *
 * K-line is a single wire, so every byte we transmit is echoed straight back on
 * RX before the ECU answers. We do NOT count echo bytes (one dropped byte would
 * desync the count); instead [transact] scans the whole RX stream for the REPLY
 * header `80 F0 <src>`, which differs from the request header `80 <dest> F0`, so
 * the echo is simply walked past by the frame scanner — the same robust approach
 * the OBDLink K-line source uses on its hex stream.
 *
 * ## Logging-only
 *
 * This drives SSM2 A8 reads (and a BF identity probe), which cannot alter the
 * ECU. No flash / write path goes through this class.
 *
 * Takes ownership of [connection] (opened by the Activity after USB permission)
 * and closes it in [disconnect].
 */
class KklKlineManager(
    private val connection: UsbDeviceConnection,
    private val device: UsbDevice
) {
    private var ftdi: FtdiUsbSerial? = null
    @Volatile private var open = false

    sealed class ConnectResult {
        data class Connected(val deviceLabel: String) : ConnectResult()
        data class Failure(val reason: String) : ConnectResult()
    }

    fun isConnected(): Boolean = open && ftdi != null

    /**
     * Open the FTDI serial at 4800 8N1, then prove the ECU is on the K-line with
     * a BF (read-ECU-ID) probe. This mirrors the OBDLink manager's ATI liveness
     * gate, except the "are you there?" here is a real SSM2 transaction over the
     * raw wire — the first proof the phone can talk K-line through a dumb cable.
     */
    fun connect(): ConnectResult {
        fun info(m: String) = ObdLinkTrafficLog.record("OUT", "· kkl: $m")
        info("opening FT232RL serial @ ${FtdiUsbSerial.KLINE_BAUD} baud (4800 8N1)…")
        val serial = FtdiUsbSerial.open(connection, device, FtdiUsbSerial.KLINE_BAUD)
            ?: return ConnectResult.Failure("FT232RL open failed (interface claim / endpoints)")
        ftdi = serial
        open = true

        // BF read-ECU-ID: a fixed frame needing no address knowledge. Both the
        // EZ30R (3.0R) and the bench 2.5i answer it. 80 10 F0 01 BF 40, where
        // 0x40 is the checksum (0x80+0x10+0xF0+0x01+0xBF & 0xFF). Reply is the FF
        // response under header 80 F0 10.
        val bf = byteArrayOf(
            0x80.toByte(), 0x10.toByte(), 0xF0.toByte(), 0x01.toByte(), 0xBF.toByte(), 0x40.toByte()
        )
        for (attempt in 1..BF_PROBE_TRIES) {
            val reply = transact(bf, replySource = 0x10, timeoutMs = BF_TIMEOUT_MS)
            if (reply != null) {
                val id = ecuIdHex(reply)
                info("ECU answered BF on try $attempt — K-line is alive")
                return ConnectResult.Connected(
                    if (id != null) "FT232RL · KKL (ECU $id)" else "FT232RL · KKL"
                )
            }
            info("BF probe $attempt/$BF_PROBE_TRIES — no reply yet")
        }
        disconnect()
        return ConnectResult.Failure(
            "No SSM2 reply over KKL — USB link is up but the ECU is silent " +
                "(check K-line wiring on OBD pin 7 / ignition)"
        )
    }

    /**
     * One half-duplex SSM2 transaction: drain stale RX, write [frame], then read
     * until a complete, checksum-valid reply frame (header `80 F0 <replySource>`)
     * appears or [timeoutMs] elapses. The leading echo of our own request (header
     * `80 <dest> F0`) is ignored by the header scan. Returns the reply frame
     * bytes (header..checksum), or null on timeout / write failure.
     */
    fun transact(frame: ByteArray, replySource: Int, timeoutMs: Long): ByteArray? {
        val serial = ftdi ?: return null
        drain()
        ObdLinkTrafficLog.record("OUT", toHex(frame))
        try {
            serial.output.write(frame)
        } catch (e: Exception) {
            ObdLinkTrafficLog.record("OUT", "· kkl: write failed: ${e.message}")
            return null
        }
        val buf = ByteArrayOutputStream()
        val tmp = ByteArray(256)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            val n = try { serial.input.read(tmp, 0, tmp.size) } catch (e: Exception) { -1 }
            if (n < 0) break        // stream closed
            if (n == 0) continue    // status-only / nothing yet — keep waiting
            buf.write(tmp, 0, n)
            val reply = scanForReply(buf.toByteArray(), replySource)
            if (reply != null) {
                ObdLinkTrafficLog.record("IN", toHex(reply))
                return reply
            }
        }
        return null
    }

    /**
     * Arm a continuous read: drain stale RX, then write [frame] (an A8 01 query)
     * WITHOUT waiting for a reply. The ECU then streams E8 frames on the K-line
     * by itself; the caller pulls them with [readAvailable]. The single request
     * echoes back once (header `80 <dest> F0`) ahead of the replies — the reply
     * frame scanner walks past it, exactly as [transact] does. Returns false on
     * write failure. The continuous analogue of the STN's STMA monitor, but here
     * the phone simply reads the raw UART (no on-board monitor to drive).
     */
    fun armStream(frame: ByteArray): Boolean {
        val serial = ftdi ?: return false
        drain()
        ObdLinkTrafficLog.record("OUT", toHex(frame) + " (stream arm)")
        return try {
            serial.output.write(frame); true
        } catch (e: Exception) {
            ObdLinkTrafficLog.record("OUT", "· kkl: stream write failed: ${e.message}"); false
        }
    }

    /** One raw read of whatever the FTDI RX holds into [tmp]; returns the byte
     *  count (0 = nothing yet, -1 = closed / error). For the continuous reader;
     *  framing/decoding is the caller's job. */
    fun readAvailable(tmp: ByteArray): Int {
        val serial = ftdi ?: return -1
        return try { serial.input.read(tmp, 0, tmp.size) } catch (e: Exception) { -1 }
    }

    /** Discard whatever is sitting in the FTDI RX buffer (stale echo / a partial
     *  frame from a prior transaction) so the next read starts clean. */
    fun drain() {
        val serial = ftdi ?: return
        val tmp = ByteArray(256)
        val deadline = SystemClock.elapsedRealtime() + DRAIN_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val n = try { serial.input.read(tmp, 0, tmp.size) } catch (e: Exception) { break }
            if (n <= 0) break
        }
    }

    fun disconnect() {
        open = false
        try { ftdi?.close() } catch (_: Exception) {}
        ftdi = null
    }

    /** Scan [bytes] for the first complete, checksum-valid SSM2 reply frame
     *  (`80 F0 <replySource> LL … cs`); null if none has fully arrived. Walks
     *  past our request echo (`80 <dest> F0 …`) automatically. */
    private fun scanForReply(bytes: ByteArray, replySource: Int): ByteArray? {
        var i = 0
        while (i <= bytes.size - 5) {
            if ((bytes[i].toInt() and 0xFF) == 0x80 &&
                (bytes[i + 1].toInt() and 0xFF) == 0xF0 &&
                (bytes[i + 2].toInt() and 0xFF) == replySource
            ) {
                val len = bytes[i + 3].toInt() and 0xFF
                val total = len + 5
                if (i + total <= bytes.size) {
                    val frame = bytes.copyOfRange(i, i + total)
                    val parsed = Ssm2FrameParser.parseSsm2Frame(frame)
                    if (parsed != null && !parsed.truncated && parsed.checksumValid) return frame
                }
            }
            i++
        }
        return null
    }

    /** Hex of the BF (FF) reply's data tail, for the connect label. Null if the
     *  reply isn't an FF identity response. */
    private fun ecuIdHex(reply: ByteArray): String? {
        val parsed = Ssm2FrameParser.parseSsm2Frame(reply) ?: return null
        val p = parsed.payload
        if (p.isEmpty() || (p[0].toInt() and 0xFF) != 0xFF) return null
        val tail = p.copyOfRange(1, p.size)
        return if (tail.isEmpty()) null else tail.joinToString("") { "%02X".format(it.toInt() and 0xFF) }
    }

    private fun toHex(bytes: ByteArray): String =
        bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private companion object {
        private const val BF_PROBE_TRIES = 3
        private const val BF_TIMEOUT_MS = 800L
        private const val DRAIN_MS = 30L
    }
}
