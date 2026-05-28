package com.protocol.app.obdlink

import java.net.InetSocketAddress
import java.net.Socket

/**
 * TCP-backed sibling of [ObdLinkBtManager]. Opens a plain TCP socket (intended
 * to point at the bench-side emulator over `adb reverse`) and reuses the same
 * [ObdLinkBtTransport] + ELM/STN init sequences the Bluetooth manager runs.
 *
 * Result: every byte that would have crossed the BT link goes over the socket
 * instead, while [ObdLinkKlineSource] / [ObdLinkLiveSource] downstream see the
 * exact same transport surface and don't care which physical link is below.
 * Same [ObdLinkTrafficLog] singleton is wired in so the OBDLINK BT TRAFFIC
 * card on Home shows the simulator exchange unchanged.
 */
class ObdLinkTcpManager {

    private var socket: Socket? = null
    var transport: ObdLinkBtTransport? = null
        private set

    sealed class ConnectResult {
        data class Connected(val deviceLabel: String) : ConnectResult()
        data class Failure(val reason: String) : ConnectResult()
    }

    /** Connect for SSM2-over-CAN: open socket, run ELM CAN setup. */
    fun connect(host: String, port: Int): ConnectResult {
        fun info(m: String) = ObdLinkTrafficLog.record("OUT", "· $m")
        val sock = openSocket(host, port, ::info) ?: return ConnectResult.Failure(
            "Couldn't open TCP socket to $host:$port — see BT log"
        )
        socket = sock
        val t = ObdLinkBtTransport(
            input = sock.getInputStream(),
            output = sock.getOutputStream(),
            log = { dir, text -> ObdLinkTrafficLog.record(dir, text) }
        )
        transport = t
        return try {
            t.drain()
            for (cmd in CAN_INIT_COMMANDS) {
                val reply = t.sendAscii(cmd, timeoutMs = if (cmd == "ATZ") 1500L else 800L)
                if (reply.contains("?")) {
                    info("CAN init '$cmd' rejected (?)")
                    disconnect()
                    return ConnectResult.Failure("emulator rejected '$cmd' during CAN init")
                }
            }
            info("CAN channel open over TCP — ready for SSM2 polling")
            ConnectResult.Connected("Emulator @ $host:$port")
        } catch (e: Exception) {
            disconnect()
            ConnectResult.Failure(e.message ?: "CAN init failed after socket open")
        }
    }

    /** Connect for SSM2-over-K-line: open socket, run raw-K-line STN init. */
    fun connectKline(host: String, port: Int): ConnectResult {
        fun info(m: String) = ObdLinkTrafficLog.record("OUT", "· $m")
        val sock = openSocket(host, port, ::info) ?: return ConnectResult.Failure(
            "Couldn't open TCP socket to $host:$port — see BT log"
        )
        socket = sock
        val t = ObdLinkBtTransport(
            input = sock.getInputStream(),
            output = sock.getOutputStream(),
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
                    return ConnectResult.Failure("emulator rejected '$cmd' during K-line init")
                }
            }
            info("K-line raw mode open over TCP — ready to send SSM2 frames")
            ConnectResult.Connected("Emulator @ $host:$port")
        } catch (e: Exception) {
            disconnect()
            ConnectResult.Failure(e.message ?: "K-line init failed after socket open")
        }
    }

    private fun openSocket(host: String, port: Int, info: (String) -> Unit): Socket? {
        return try {
            info("connecting TCP $host:$port…")
            val s = Socket()
            s.connect(InetSocketAddress(host, port), 3000)
            s.tcpNoDelay = true
            info("TCP connected")
            s
        } catch (e: Exception) {
            info("TCP failed: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    fun disconnect() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        transport = null
    }

    fun isConnected(): Boolean = socket != null && transport != null

    companion object {
        private val KLINE_INIT_COMMANDS = listOf(
            "STP 21",
            "STIMCS 1",
            "STPBR 4800",
            "ATAL",
            "STIP4 0"
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
