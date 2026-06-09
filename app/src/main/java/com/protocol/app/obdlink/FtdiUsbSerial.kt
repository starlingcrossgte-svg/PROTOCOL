package com.protocol.app.obdlink

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimal, dependency-free FTDI USB-serial transport for the OBDLink EX, whose
 * STN2120 sits behind a standard FT231X (VID 0x0403 / PID 0x6015) in VCP mode.
 *
 * Unlike the Tactrix OpenPort (also an FTDI VID, but custom firmware over raw
 * bulk), the EX speaks true FTDI VCP, which means two things the OpenPort path
 * doesn't do:
 *   1. the host sets the line baud via vendor control transfers, and
 *   2. EVERY bulk-IN packet is prefixed with 2 modem/line status bytes that
 *      must be stripped before the ELM/STN ASCII underneath is read.
 *
 * [input] / [output] hand plain byte streams to [ObdLinkBtTransport], so the
 * exact same STN init + SSM2 polling that runs over Bluetooth / TCP runs over
 * USB unchanged.
 *
 * Control-transfer constants + the baud divisor encoding are taken verbatim
 * from the canonical FTDI VCP algorithm (the widely-used Android FTDI driver):
 * requestType 0x40, 8N1 = 0x0008, single-port wIndex = 1, 3 MHz baud generator.
 */
class FtdiUsbSerial private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val epIn: UsbEndpoint,
    private val epOut: UsbEndpoint
) {
    private val readPacket = ByteArray(epIn.maxPacketSize.coerceAtLeast(64))
    // Decoded (status-stripped) bytes read but not yet handed to the consumer.
    private var pending = ByteArray(0)
    private var pendingPos = 0
    @Volatile private var closed = false
    // ── Temporary USB-layer diagnostics (gated on DIAG) ──────────────────
    private var statusOnlyReads = 0L
    private var dataReads = 0L
    private var errReads = 0L
    private var lastHeartbeatMs = 0L

    val input: InputStream = object : InputStream() {
        override fun read(): Int {
            val one = ByteArray(1)
            val n = read(one, 0, 1)
            return if (n <= 0) -1 else one[0].toInt() and 0xFF
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            // Serve any leftover decoded bytes first.
            if (pendingPos < pending.size) {
                val n = minOf(len, pending.size - pendingPos)
                System.arraycopy(pending, pendingPos, b, off, n)
                pendingPos += n
                return n
            }
            if (closed) return -1
            // One bulk packet = [2 status bytes][data...]. When idle the FTDI
            // emits the status pair every latency-timer period, so a status-only
            // (got == 2) or timed-out (got < 0) read just yields 0 — the reader
            // loop treats that as "nothing yet" and tries again.
            val got = connection.bulkTransfer(epIn, readPacket, readPacket.size, READ_TIMEOUT_MS)
            recordRead(got)
            if (got < 0) return if (closed) -1 else 0
            if (got <= READ_HEADER_LENGTH) return 0
            val dataLen = got - READ_HEADER_LENGTH
            val n = minOf(len, dataLen)
            System.arraycopy(readPacket, READ_HEADER_LENGTH, b, off, n)
            if (n < dataLen) {
                pending = readPacket.copyOfRange(READ_HEADER_LENGTH + n, READ_HEADER_LENGTH + dataLen)
                pendingPos = 0
            }
            return n
        }
    }

    val output: OutputStream = object : OutputStream() {
        override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

        override fun write(b: ByteArray, off: Int, len: Int) {
            if (len == 0 || closed) return
            val chunk = if (off == 0 && len == b.size) b else b.copyOfRange(off, off + len)
            // ELM/ST commands are tiny (< one packet); a single bulk write covers it.
            val n = connection.bulkTransfer(epOut, chunk, len, WRITE_TIMEOUT_MS)
            if (n != len) diag("write $len bytes -> bulkTransfer returned $n")
        }
    }

    fun close() {
        closed = true
        try { connection.releaseInterface(usbInterface) } catch (_: Exception) {}
        try { connection.close() } catch (_: Exception) {}
    }

    private fun ctrlOut(request: Int, value: Int, index: Int): Int =
        connection.controlTransfer(REQTYPE_OUT, request, value, index, null, 0, CTRL_TIMEOUT_MS)

    /** Change just the line baud — used by the connect-time baud sweep. */
    fun setBaud(baud: Int) {
        val (value, index) = encodeBaud(baud)
        val r = ctrlOut(REQ_SET_BAUD, value, index)
        diag("setBaud $baud -> $r")
    }

    private fun configure(baud: Int) {
        // Force standard UART mode first: if a prior app left the FT231X in
        // bitbang/CBUS mode it would accept all our config below yet never move
        // a single UART byte — exactly the "config OK, zero data" symptom.
        val bitmode = ctrlOut(REQ_SET_BITMODE, BITMODE_RESET, INDEX_PORT)
        val reset = ctrlOut(REQ_RESET, 0, INDEX_PORT)             // reset the SIO
        // Assert DTR then RTS. Some USB-serial bridges gate the downstream UART
        // on these lines; setting them is harmless if the EX ignores them.
        val dtr = ctrlOut(REQ_SET_MODEM_CTRL, MODEM_DTR_HIGH, INDEX_PORT)
        val rts = ctrlOut(REQ_SET_MODEM_CTRL, MODEM_RTS_HIGH, INDEX_PORT)
        val flow = ctrlOut(REQ_SET_FLOW_CTRL, 0, INDEX_PORT)      // no flow control
        val (value, index) = encodeBaud(baud)
        val bd = ctrlOut(REQ_SET_BAUD, value, index)             // line baud
        val data = ctrlOut(REQ_SET_DATA, DATA_8N1, INDEX_PORT)   // 8 data bits, no parity, 1 stop
        val lat = ctrlOut(REQ_SET_LATENCY, LATENCY_MS, INDEX_PORT) // small latency timer
        diag(
            "configure baud=$baud -> bitmode=$bitmode reset=$reset dtr=$dtr rts=$rts flow=$flow " +
                "baud=$bd data=$data lat=$lat (negative = control transfer failed)"
        )
    }

    private fun diag(m: String) {
        if (DIAG) ObdLinkTrafficLog.record("OUT", "· ftdi: $m")
    }

    /**
     * Counts every bulk-IN result and emits a periodic rx summary to the BYTES
     * log. The decisive signal: `status>0 data=0` means the USB read path is
     * alive (FTDI status packets flowing) but the adapter is sending no data —
     * i.e. the STN is silent, not the transport. `err` climbing means the
     * bulkTransfer itself is failing.
     */
    private fun recordRead(got: Int) {
        if (!DIAG) return
        when {
            got < 0 -> errReads++
            got <= READ_HEADER_LENGTH -> statusOnlyReads++
            else -> dataReads++
        }
        val now = System.currentTimeMillis()
        if (now - lastHeartbeatMs >= HEARTBEAT_MS) {
            lastHeartbeatMs = now
            ObdLinkTrafficLog.record(
                "OUT",
                "· ftdi rx: status=$statusOnlyReads data=$dataReads err=$errReads"
            )
        }
    }

    companion object {
        const val FTDI_VENDOR_ID = 0x0403          // 1027
        const val OBDLINK_EX_PRODUCT_ID = 0x6015   // 24597 (FT231X)
        // VAG-KKL raw K-line cable: a plain FT232RL behind FTDI's generic
        // FT232R product id. Same FTDI VCP layer as the EX — only the product
        // id and the open baud differ (the KKL UART IS the K-line, so it runs at
        // the SSM2 K-line rate directly; the EX UART runs at 115200 to the STN,
        // which does 4800 on its own K-line side).
        const val FT232RL_PRODUCT_ID = 0x6001      // 24577 (FT232R)
        // OBDLink USB default UART baud. If the EX never answers, this is the
        // first thing to try changing (the FRPM lists alternates).
        const val DEFAULT_BAUD = 115200
        // SSM2 K-line bit rate. For the dumb KKL cable the FTDI UART speaks this
        // directly (no smart adapter translating). SSM2 needs no 5-baud / fast
        // init — open 4800 8N1 and send the frame.
        const val KLINE_BAUD = 4800

        private const val REQTYPE_OUT = 0x40       // USB_TYPE_VENDOR | USB_DIR_OUT
        private const val REQ_RESET = 0
        private const val REQ_SET_MODEM_CTRL = 1
        private const val REQ_SET_FLOW_CTRL = 2
        private const val REQ_SET_BAUD = 3
        private const val REQ_SET_DATA = 4
        private const val REQ_SET_LATENCY = 9
        private const val REQ_SET_BITMODE = 0x0B
        private const val BITMODE_RESET = 0x0000   // mode 0 = standard UART/serial
        // Single-port FT231X: control-transfer wIndex is the port number, 0.
        // (The dual-port FT2232 uses 1/2 — that's where the "+1" myth comes from.)
        private const val INDEX_PORT = 0
        // SET_MODEM_CTRL: high byte = enable mask, low byte = level. DTR is bit 0,
        // RTS is bit 1, so 0x0101 drives DTR high and 0x0202 drives RTS high.
        private const val MODEM_DTR_HIGH = 0x0101
        private const val MODEM_RTS_HIGH = 0x0202
        private const val DATA_8N1 = 0x0008        // 8 data bits, no parity, 1 stop bit
        // FTDI latency timer (ms): how long the chip holds RX data before
        // flushing it to the host. 16 = chip default — a finished OBD reply can
        // sit up to 16 ms in the FTDI buffer before the phone ever sees it. 2 =
        // aggressive flush, cutting that tail off every EX reply (FT_SetLatencyTimer).
        private const val LATENCY_MS = 2
        private const val READ_HEADER_LENGTH = 2   // FTDI prepends 2 status bytes per packet
        private const val CTRL_TIMEOUT_MS = 1000
        private const val READ_TIMEOUT_MS = 200
        private const val WRITE_TIMEOUT_MS = 1000
        private const val HEARTBEAT_MS = 2000L     // rx-summary cadence while DIAG is on

        /** USB-layer tracing into the BYTES log (FTDI control-transfer codes +
         *  rx status/data/err heartbeat). Off for normal use; flip true to debug
         *  a silent EX. Confirmed working on the bench 2026-06-06 (data=433,
         *  err=0), so the noise is no longer needed. */
        var DIAG = false

        /**
         * Open + configure the FTDI device. Claims the first interface that has
         * both a bulk IN and bulk OUT endpoint (FT231X has exactly one). Returns
         * null if the interface can't be claimed or the endpoints are missing.
         * On success the returned object owns [connection] and closes it in
         * [close]; on null the caller still owns the connection.
         */
        fun open(connection: UsbDeviceConnection, device: UsbDevice, baud: Int = DEFAULT_BAUD): FtdiUsbSerial? {
            var iface: UsbInterface? = null
            var epIn: UsbEndpoint? = null
            var epOut: UsbEndpoint? = null
            outer@ for (i in 0 until device.interfaceCount) {
                val candidate = device.getInterface(i)
                var inEp: UsbEndpoint? = null
                var outEp: UsbEndpoint? = null
                for (e in 0 until candidate.endpointCount) {
                    val ep = candidate.getEndpoint(e)
                    if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                    if (ep.direction == UsbConstants.USB_DIR_IN) inEp = ep else outEp = ep
                }
                if (inEp != null && outEp != null) {
                    iface = candidate; epIn = inEp; epOut = outEp; break@outer
                }
            }
            if (iface == null || epIn == null || epOut == null) return null
            if (!connection.claimInterface(iface, true)) return null
            return FtdiUsbSerial(connection, iface, epIn, epOut).apply { configure(baud) }
        }

        /**
         * FT232R / FT-X baud divisor encoding (3 MHz base, 1/8 fractional steps).
         * Returns (wValue, wIndex) for the SET_BAUD_RATE control transfer.
         */
        private fun encodeBaud(baud: Int): Pair<Int, Int> {
            if (baud >= 2500000) return 0 to 0       // 3 Mbaud
            if (baud >= 1750000) return 1 to 0       // 2 Mbaud
            var d = (24000000 shl 1) / baud
            d = (d + 1) shr 1                        // round to nearest
            val subdivisor = d and 0x07
            val divisor = d shr 3
            var value = divisor
            var index = 0
            when (subdivisor) {
                0 -> {}
                4 -> value = value or 0x4000
                2 -> value = value or 0x8000
                1 -> value = value or 0xc000
                3 -> index = index or 1
                5 -> { value = value or 0x4000; index = index or 1 }
                6 -> { value = value or 0x8000; index = index or 1 }
                7 -> { value = value or 0xc000; index = index or 1 }
            }
            return value to index
        }
    }
}
