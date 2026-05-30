package com.protocol.app.obdlink

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import com.protocol.app.EcuLogger
import java.util.UUID

/**
 * Owns the OBDLink MX+ Bluetooth connection lifecycle: find the *paired*
 * OBDLink, open an RFCOMM (SPP) socket, run the ELM/STN channel setup for
 * SSM2-over-CAN, and hand back a connected [ObdLinkBtTransport].
 *
 * Paired-only by design — the user pairs the MX+ once in Android's Bluetooth
 * settings, so we never scan (no location/scan permission needed, just
 * BLUETOOTH_CONNECT on API 31+).
 *
 * GATING: this object is only constructed/called by the ViewModel when the
 * user has picked OBDLink as the active adapter in Settings. The caller also
 * guarantees the runtime BLUETOOTH_CONNECT permission is granted before
 * [connect] / [connectKline].
 */
class ObdLinkBtManager(context: Context) {

    private val appContext = context.applicationContext
    private var socket: BluetoothSocket? = null
    var transport: ObdLinkBtTransport? = null
        private set

    sealed class ConnectResult {
        data class Connected(val deviceLabel: String) : ConnectResult()
        data class Failure(val reason: String) : ConnectResult()
    }

    /**
     * Find the paired OBDLink, open the socket, and run ELM setup. Returns the
     * device label on success or a human-readable reason on failure. Safe to
     * call again after a [disconnect].
     */
    @SuppressLint("MissingPermission") // caller guarantees BLUETOOTH_CONNECT is granted
    fun connect(): ConnectResult {
        // Every phase is logged to the BT log so a failed/hung connect is
        // visible on the Developer page instead of a silent "nothing happened".
        fun info(m: String) = ObdLinkTrafficLog.record("OUT", "· $m")

        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return ConnectResult.Failure("No Bluetooth adapter on this device")
        if (!adapter.isEnabled) {
            info("Bluetooth is OFF")
            return ConnectResult.Failure("Bluetooth is turned off")
        }

        val bonded = try {
            adapter.bondedDevices?.toList() ?: emptyList()
        } catch (e: SecurityException) {
            return ConnectResult.Failure("Bluetooth permission not granted")
        }
        info("paired: " + if (bonded.isEmpty()) "(none)" else bonded.joinToString { it.name ?: "?" })
        val device = bonded.firstOrNull { (it.name ?: "").contains("OBD", ignoreCase = true) }
            ?: return ConnectResult.Failure("No paired OBDLink — pair the MX+ in Android Bluetooth settings first")
        info("target: ${device.name}")

        // cancelDiscovery() needs BLUETOOTH_SCAN on API 31+, which we don't
        // request (paired-only, no scanning). It's only a connect-speed
        // optimization, so swallow any SecurityException instead of letting
        // it abort the whole connect (the original bug: it surfaced as a
        // bogus "permission denied").
        try { adapter.cancelDiscovery() } catch (_: Exception) {}

        val sock = openSocket(device, ::info)
            ?: run {
                disconnect()
                return ConnectResult.Failure("Couldn't open a Bluetooth socket to ${device.name} — see BT log")
            }
        socket = sock
        info("socket OPEN — starting ELM setup")
        return try {
            val t = ObdLinkBtTransport(
                input = sock.inputStream,
                output = sock.outputStream,
                log = { dir, text -> ObdLinkTrafficLog.record(dir, text) }
            )
            transport = t
            initElmForSsm2Can(t)
            ConnectResult.Connected(device.name ?: "OBDLink")
        } catch (e: Exception) {
            disconnect()
            ConnectResult.Failure(e.message ?: "ELM setup failed after connect")
        }
    }

    /**
     * Open an RFCOMM socket, trying the three approaches that between them
     * cover nearly every Android/adapter combo: secure SPP, insecure SPP,
     * then the reflection channel-1 fallback (the classic workaround for
     * adapters whose SDP-record connect fails). Each attempt is logged so a
     * failure shows up in the BT log instead of vanishing.
     */
    @SuppressLint("MissingPermission")
    private fun openSocket(device: BluetoothDevice, info: (String) -> Unit): BluetoothSocket? {
        try {
            info("try secure RFCOMM…")
            val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            info("secure RFCOMM connected")
            return s
        } catch (e: Exception) {
            info("secure failed: ${e.message ?: e.javaClass.simpleName}")
        }
        try {
            info("try insecure RFCOMM…")
            val s = device.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            s.connect()
            info("insecure RFCOMM connected")
            return s
        } catch (e: Exception) {
            info("insecure failed: ${e.message ?: e.javaClass.simpleName}")
        }
        try {
            info("try reflection createRfcommSocket(1)…")
            val m = device.javaClass.getMethod("createRfcommSocket", Int::class.javaPrimitiveType)
            val s = m.invoke(device, 1) as BluetoothSocket
            s.connect()
            info("reflection RFCOMM connected")
            return s
        } catch (e: Exception) {
            info("reflection failed: ${e.message ?: e.javaClass.simpleName}")
        }
        return null
    }

    /**
     * Connect for SSM2-over-K-line. Opens the RFCOMM socket, then puts the STN
     * into raw K-line mode at 4800 baud with no autoinit so the complete SSM2
     * frame (header + checksum) can be sent verbatim. Verified working on the
     * EZ30R / 3.0R.
     *
     *   STP 21      ISO 9141, no header, no auto-init
     *   STIMCS 1    STN ISO message setting
     *   STPBR 4800  K-line baud = 4800
     *   ATAL        allow long (>7-byte) messages
     *   STIP4 0     transmit interbyte timing = 0 ms
     *   ATAT 2      aggressive adaptive timing (trims post-reply wait)
     */
    @SuppressLint("MissingPermission")
    fun connectKline(): ConnectResult {
        fun info(m: String) = ObdLinkTrafficLog.record("OUT", "· $m")
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return ConnectResult.Failure("No Bluetooth adapter on this device")
        if (!adapter.isEnabled) return ConnectResult.Failure("Bluetooth is turned off")
        val device = try {
            adapter.bondedDevices?.firstOrNull { (it.name ?: "").contains("OBD", ignoreCase = true) }
        } catch (e: SecurityException) {
            return ConnectResult.Failure("Bluetooth permission not granted")
        } ?: return ConnectResult.Failure("No paired OBDLink — pair the MX+ in Android Bluetooth settings first")
        try { adapter.cancelDiscovery() } catch (_: Exception) {}
        val sock = openSocket(device, ::info)
            ?: run { disconnect(); return ConnectResult.Failure("Couldn't open a Bluetooth socket to ${device.name} — see BT log") }
        socket = sock
        val t = ObdLinkBtTransport(
            input = sock.inputStream,
            output = sock.outputStream,
            log = { dir, text -> ObdLinkTrafficLog.record(dir, text) }
        )
        transport = t
        return try {
            t.drain()
            for (cmd in listOf("ATE0", "ATL0", "ATS0")) t.sendAscii(cmd, 800L)
            for (cmd in KLINE_INIT_COMMANDS) {
                val reply = t.sendAscii(cmd, timeoutMs = 1500L)
                if (reply.contains("?")) {
                    info("K-line init '$cmd' rejected (?)")
                    disconnect()
                    return ConnectResult.Failure("STN rejected '$cmd' during K-line init")
                }
            }
            info("K-line raw mode open — ready to send SSM2 frames")
            ConnectResult.Connected(device.name ?: "OBDLink")
        } catch (e: Exception) {
            disconnect()
            ConnectResult.Failure(e.message ?: "K-line init failed after socket open")
        }
    }

    /**
     * Connect with only a basic reset (ATZ/ATE0/ATL0/ATS0/ATAL) and NO protocol
     * selected, leaving the adapter ready for the auto-prober to try K-line
     * init variants on top. Used by the K-line init hunt (EZ30R/3.0R).
     */
    @SuppressLint("MissingPermission")
    fun connectBasic(): ConnectResult {
        fun info(m: String) = ObdLinkTrafficLog.record("OUT", "· $m")
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return ConnectResult.Failure("No Bluetooth adapter on this device")
        if (!adapter.isEnabled) return ConnectResult.Failure("Bluetooth is turned off")
        val device = try {
            adapter.bondedDevices?.firstOrNull { (it.name ?: "").contains("OBD", ignoreCase = true) }
        } catch (e: SecurityException) {
            return ConnectResult.Failure("Bluetooth permission not granted")
        } ?: return ConnectResult.Failure("No paired OBDLink — pair the MX+ in Android Bluetooth settings first")
        try { adapter.cancelDiscovery() } catch (_: Exception) {}
        val sock = openSocket(device, ::info)
            ?: run { disconnect(); return ConnectResult.Failure("Couldn't open a Bluetooth socket to ${device.name} — see BT log") }
        socket = sock
        val t = ObdLinkBtTransport(
            input = sock.inputStream,
            output = sock.outputStream,
            log = { dir, text -> ObdLinkTrafficLog.record(dir, text) }
        )
        transport = t
        t.drain()
        // No ATZ — a full reset isn't needed before opening a K-line channel; the STN keeps
        // its state. Just echo/linefeeds/spaces off so replies parse cleanly; the candidate
        // supplies the full K-line init (protocol/baud/timing).
        for (cmd in listOf("ATE0", "ATL0", "ATS0")) {
            t.sendAscii(cmd, 800L)
        }
        info("preamble done — ready for K-line init probing")
        return ConnectResult.Connected(device.name ?: "OBDLink")
    }

    /**
     * Factory-reset the paired OBDLink: open a socket, send the full STN/ELM
     * restore sequence, then drop the link.
     *
     *   ATPP FF OFF — disable ALL programmable parameters at once. This is what
     *                 clears settings persisted in the adapter's NVM. A plain
     *                 ATZ does NOT touch stored PPs (FRPM §7), which is exactly
     *                 why a persisted raw-K-line config can survive a reset.
     *   ATD         — restore all runtime settings to defaults.
     *   ATZ         — full device reset, applying the cleared PPs. The adapter
     *                 reboots, so the socket is dead afterward.
     *
     * Purpose: return the adapter to a known factory state, clearing any persisted
     * protocol / programmable-parameter config from a previous connection.
     */
    @SuppressLint("MissingPermission")
    fun resetAdapter(): ConnectResult {
        fun info(m: String) = ObdLinkTrafficLog.record("OUT", "· $m")
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return ConnectResult.Failure("No Bluetooth adapter on this device")
        if (!adapter.isEnabled) return ConnectResult.Failure("Bluetooth is turned off")
        val device = try {
            adapter.bondedDevices?.firstOrNull { (it.name ?: "").contains("OBD", ignoreCase = true) }
        } catch (e: SecurityException) {
            return ConnectResult.Failure("Bluetooth permission not granted — turn the Bluetooth toggle on first")
        } ?: return ConnectResult.Failure("No paired OBDLink — pair the MX+ in Android Bluetooth settings first")
        try { adapter.cancelDiscovery() } catch (_: Exception) {}
        val sock = openSocket(device, ::info)
            ?: run { disconnect(); return ConnectResult.Failure("Couldn't open a Bluetooth socket to ${device.name} — see BT log") }
        socket = sock
        val t = ObdLinkBtTransport(
            input = sock.inputStream,
            output = sock.outputStream,
            log = { dir, text -> ObdLinkTrafficLog.record(dir, text) }
        )
        transport = t
        t.drain()
        info("FACTORY RESET: ATPP FF OFF / ATD / ATZ")
        t.sendAscii("ATPP FF OFF", timeoutMs = 1200L)
        t.sendAscii("ATD", timeoutMs = 1200L)
        t.sendAscii("ATZ", timeoutMs = 2500L)
        info("FACTORY RESET sent — adapter is rebooting; power-cycle + re-pair to be safe")
        disconnect() // ATZ reboots the adapter; the socket is gone now
        return ConnectResult.Connected(device.name ?: "OBDLink")
    }

    /**
     * Best-effort ELM327/STN channel setup for SSM2-over-CAN: ISO15765 @ 500k,
     * tester header 7E0, receive filter 7E8 (ECM). Headers + spaces off so the
     * adapter returns clean reassembled SSM2 payload bytes (E8 ...).
     *
     * These commands are the documented ELM/STN set; exact values may want
     * bench tuning — watch the Developer BT byte log to confirm each step's
     * reply and adjust. A '?' reply means the adapter rejected a command.
     */
    private fun initElmForSsm2Can(t: ObdLinkBtTransport) {
        t.drain()
        val setup = listOf(
            "ATZ",          // reset
            "ATE0",         // echo off
            "ATL0",         // linefeeds off
            "ATS0",         // spaces off
            "ATH0",         // headers off (ATCRA filters to 7E8, so the reply is ECM-only)
            "ATAL",         // allow long (>7 byte) messages — needed for any ISO-TP multi-frame
            "ATSP6",        // ISO 15765-4 CAN, 11-bit, 500 kbps
            "ATSH7E0",      // tester -> ECU header
            "ATCRA7E8",     // accept only ECU -> tester
            "ATFCSH7E0",    // flow-control header
            "ATFCSD300000", // flow-control data: CTS, block size 0, st-min 0
            "ATFCSM1"       // use the custom flow control above
        )
        for (cmd in setup) {
            val reply = t.sendAscii(cmd, timeoutMs = if (cmd == "ATZ") 1500L else 800L)
            if (reply.contains("?")) {
                EcuLogger.comm("OBDLink ELM '$cmd' rejected ('?') — see Developer BT log")
            }
        }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        transport = null
    }

    fun isConnected(): Boolean = socket != null && transport != null

    companion object {
        // Standard Serial Port Profile UUID.
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private val KLINE_INIT_COMMANDS = listOf(
            "STP 21",
            "STIMCS 1",
            "STPBR 4800",
            "ATAL",
            "STIP4 0",
            "ATAT 2"      // aggressive adaptive timing — trims post-reply wait on the slow BT link
        )
    }
}
