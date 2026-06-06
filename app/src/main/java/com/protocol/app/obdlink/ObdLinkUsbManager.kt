package com.protocol.app.obdlink

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection

/**
 * USB-backed sibling of [ObdLinkBtManager] / [ObdLinkTcpManager] for the
 * OBDLink EX. Opens the FTDI serial via [FtdiUsbSerial], wraps it in the same
 * [ObdLinkBtTransport], and runs the identical ELM/STN init sequences — so the
 * EX is the STN2120 family same as the MX+, just reached over USB instead of
 * Bluetooth. Downstream [ObdLinkKlineSource] (ECM+TCM) / [ObdLinkLiveSource]
 * (CAN) see the exact same transport surface and don't care about the link.
 *
 * Takes ownership of [connection] (opened by the Activity) and closes it in
 * [disconnect].
 */
class ObdLinkUsbManager(
    private val connection: UsbDeviceConnection,
    private val device: UsbDevice
) {
    private var ftdi: FtdiUsbSerial? = null
    var transport: ObdLinkBtTransport? = null
        private set

    sealed class ConnectResult {
        data class Connected(val deviceLabel: String) : ConnectResult()
        data class Failure(val reason: String) : ConnectResult()
    }

    /** Connect for SSM2-over-K-line: open FTDI serial, run raw-K-line STN init
     *  (or [sequence] from the command library if supplied). */
    fun connectKline(sequence: CommandSequence? = null): ConnectResult =
        bringUp(kline = true, sequence = sequence)

    /** Connect for SSM2-over-CAN: open FTDI serial, run ELM CAN setup (or
     *  [sequence] from the command library if supplied). */
    fun connect(sequence: CommandSequence? = null): ConnectResult =
        bringUp(kline = false, sequence = sequence)

    private fun bringUp(kline: Boolean, sequence: CommandSequence?): ConnectResult {
        fun info(m: String) = ObdLinkTrafficLog.record("OUT", "· $m")
        info("opening FTDI USB serial @ ${FtdiUsbSerial.DEFAULT_BAUD} baud…")
        val serial = FtdiUsbSerial.open(connection, device)
            ?: return ConnectResult.Failure("FTDI open failed (claim/endpoints) — see BT log")
        ftdi = serial
        val t = ObdLinkBtTransport(
            input = serial.input,
            output = serial.output,
            log = { dir, text -> ObdLinkTrafficLog.record(dir, text) }
        )
        transport = t
        return try {
            t.drain()
            // Baud sweep + liveness gate. The wired-OBDLink default is 115200,
            // but it's firmware-dependent (older units shipped 38400) and the
            // first bench unit answered at none of the guesses, so probe the
            // common STN rates and lock onto whichever replies to ATI (printed
            // regardless of echo state). Total silence at every rate = USB link
            // up but the STN mute — fail loudly instead of faking "channel open".
            val liveBaud = BAUD_CANDIDATES.firstOrNull { b ->
                serial.setBaud(b)
                t.drain()
                val reply = t.sendAscii("ATI", timeoutMs = 700L)
                info("probe @ $b baud -> '${reply.replace("\r", " ").trim()}'")
                reply.trim().isNotEmpty()
            }
            if (liveBaud == null) {
                info("adapter silent at every baud tried — USB up, no UART response")
                disconnect()
                return ConnectResult.Failure(
                    "OBDLink EX not answering at any baud — verify it responds on the PC (COM port) first"
                )
            }
            info("adapter alive @ $liveBaud baud")
            if (sequence != null) {
                // User-selected library init. Logs rejected steps but keeps
                // going (experimental — one bad command shouldn't abort), and
                // honors any per-step host baud switch (STPBR speed bumps).
                runSequence(t, serial, sequence)
                info("init sequence '${sequence.name}' done — ready")
            } else if (kline) {
                for (cmd in listOf("ATE0", "ATL0", "ATS0")) t.sendAscii(cmd, 800L)
                for (cmd in KLINE_INIT_COMMANDS) {
                    val reply = t.sendAscii(cmd, timeoutMs = 1500L)
                    if (reply.contains("?")) {
                        info("K-line init '$cmd' rejected (?)")
                        disconnect()
                        return ConnectResult.Failure("adapter rejected '$cmd' during K-line init")
                    }
                }
                info("K-line raw mode open over USB — ready to send SSM2 frames")
            } else {
                for (cmd in CAN_INIT_COMMANDS) {
                    val reply = t.sendAscii(cmd, timeoutMs = if (cmd == "ATZ") 1500L else 800L)
                    if (reply.contains("?")) {
                        info("CAN init '$cmd' rejected (?)")
                        disconnect()
                        return ConnectResult.Failure("adapter rejected '$cmd' during CAN init")
                    }
                }
                info("CAN channel open over USB — ready for SSM2 polling")
            }
            ConnectResult.Connected("OBDLink EX (USB)")
        } catch (e: Exception) {
            disconnect()
            ConnectResult.Failure(e.message ?: "init failed after USB open")
        }
    }

    /**
     * Walk a library [CommandSequence]: send each step, log a '?' rejection but
     * keep going, and apply any per-step host UART baud switch *after* the
     * adapter acks (for STPBR-style speed bumps where the host must follow).
     */
    private fun runSequence(t: ObdLinkBtTransport, serial: FtdiUsbSerial, seq: CommandSequence) {
        fun info(m: String) = ObdLinkTrafficLog.record("OUT", "· $m")
        for (step in seq.steps) {
            val reply = t.sendAscii(
                step.command,
                timeoutMs = if (step.command.equals("ATZ", ignoreCase = true)) 1500L else 1200L
            )
            if (reply.contains("?")) info("seq '${step.command}' rejected (?) — continuing")
            step.switchBaudAfter?.let {
                info("switching host UART -> $it baud after ${step.command}")
                serial.setBaud(it)
            }
        }
    }

    fun disconnect() {
        try { transport?.close() } catch (_: Exception) {}
        transport = null
        try { ftdi?.close() } catch (_: Exception) {}
        ftdi = null
    }

    fun isConnected(): Boolean = transport != null

    /**
     * Factory-reset the STN over the already-open USB link
     * (ATPP FF OFF / ATD / ATZ). Restores the adapter's programmable params —
     * including UART baud — to its defaults. Requires a live transport; the EX
     * can't reopen the USB connection on its own (the Activity owns that).
     */
    fun resetAdapter(): ConnectResult {
        val t = transport
            ?: return ConnectResult.Failure("OBDLink EX not connected — connect it first, then reset")
        fun info(m: String) = ObdLinkTrafficLog.record("OUT", "· $m")
        return try {
            t.drain()
            info("FACTORY RESET: ATPP FF OFF / ATD / ATZ")
            t.sendAscii("ATPP FF OFF", timeoutMs = 1200L)
            t.sendAscii("ATD", timeoutMs = 1200L)
            t.sendAscii("ATZ", timeoutMs = 2500L)
            info("FACTORY RESET sent — STN rebooting to its default baud")
            disconnect()
            ConnectResult.Connected("OBDLink EX (USB)")
        } catch (e: Exception) {
            disconnect()
            ConnectResult.Failure(e.message ?: "reset failed")
        }
    }

    companion object {
        // USB UART rates to probe at connect, most-likely first. 115200 is this
        // EX's confirmed rate (STI answered at 115200 over USB after the reflash)
        // and the documented wired-OBDLink default; 38400 is the older-firmware
        // default; the rest are fallbacks.
        private val BAUD_CANDIDATES = listOf(115200, 38400, 9600, 230400, 500000, 57600)

        // Same STN init sequences the BT / TCP managers run (kept local, matching
        // the existing per-manager copies).
        private val KLINE_INIT_COMMANDS = listOf(
            "STP 21",
            "STIMCS 1",
            "STPBR 4800",
            "ATAL",
            "STIP4 0",
            "ATAT 2"   // aggressive adaptive timing — match the MX+ K-line init
        )
        private val CAN_INIT_COMMANDS = listOf(
            "ATZ",
            "ATE0",
            "ATL0",
            "ATS0",
            "ATH0",
            "ATAL",
            "ATSP6",
            "ATSH7E0",
            "ATCRA7E8",
            "ATFCSH7E0",
            "ATFCSD300000",
            "ATFCSM1"
        )
    }
}
