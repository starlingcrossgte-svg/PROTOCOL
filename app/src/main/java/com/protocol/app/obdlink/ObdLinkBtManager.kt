package com.protocol.app.obdlink

import android.annotation.SuppressLint
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
 * GATING: this object is only ever constructed/called by the ViewModel when
 * the `obdLinkEnabled` setting is ON. While OFF, nothing here runs — no
 * adapter access, no socket, no connection. The caller also guarantees the
 * runtime BLUETOOTH_CONNECT permission is granted before [connect].
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
        val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            ?: return ConnectResult.Failure("No Bluetooth adapter on this device")
        if (!adapter.isEnabled) return ConnectResult.Failure("Bluetooth is turned off")

        val device = try {
            adapter.bondedDevices?.firstOrNull {
                val n = it.name ?: ""
                n.contains("OBDLink", ignoreCase = true) || n.contains("OBD", ignoreCase = true)
            }
        } catch (e: SecurityException) {
            return ConnectResult.Failure("Bluetooth permission not granted")
        } ?: return ConnectResult.Failure("No paired OBDLink — pair it in Android Bluetooth settings first")

        return try {
            adapter.cancelDiscovery()
            val sock = device.createRfcommSocketToServiceRecord(SPP_UUID)
            sock.connect() // blocking; caller runs this off the main thread
            socket = sock
            val t = ObdLinkBtTransport(
                input = sock.inputStream,
                output = sock.outputStream,
                log = { dir, text -> ObdLinkTrafficLog.record(dir, text) }
            )
            transport = t
            initElmForSsm2Can(t)
            ConnectResult.Connected(device.name ?: "OBDLink")
        } catch (e: SecurityException) {
            disconnect()
            ConnectResult.Failure("Bluetooth permission denied")
        } catch (e: Exception) {
            disconnect()
            ConnectResult.Failure(e.message ?: "Bluetooth connect failed")
        }
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
    }
}
