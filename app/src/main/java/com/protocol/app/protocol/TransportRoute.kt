package com.protocol.app.protocol

/**
 * The single source of truth for the adapter × bus transport combinations Live
 * Data can run. Replaces the parallel `when (adapter / protocol)` blocks that
 * used to enumerate the matrix independently in [ProtocolViewModel] (status
 * string and flow dispatch). Each route carries its display label and which
 * polling modes it supports, so the flow dispatch and the Settings selector
 * greying read the same capability data instead of drifting apart.
 *
 * Polling modes (see [PollingMode]):
 *  - **Poll** — request/response; supported by every route (the universal default).
 *  - **Stream** — one A8 01, the ECU firehoses replies; K-line ECM-only today.
 *  - **Monitor** — listen-only decode of a broadcast bus; CanBroadcast-only.
 *
 * Not every (adapter, bus) pair is a valid route — e.g. the FT232RL KKL cable is
 * K-line only, so it has no CAN route. [of] returns null for invalid pairs.
 */
enum class TransportRoute(
    val adapter: Adapter,
    val bus: BusProtocol,
    val label: String,
    val supportsPoll: Boolean = true,
    val supportsStream: Boolean = false,
    val supportsMonitor: Boolean = false,
) {
    OpenPortKline(Adapter.OpenPort, BusProtocol.KLine, "OpenPort K-line", supportsStream = true),
    OpenPortCan(Adapter.OpenPort, BusProtocol.CAN, "OpenPort CAN"),
    OpenPortCanBroadcast(Adapter.OpenPort, BusProtocol.CanBroadcast, "OpenPort CAN broadcast", supportsPoll = false, supportsMonitor = true),
    ObdLinkKline(Adapter.OBDLink, BusProtocol.KLine, "OBDLink K-line", supportsStream = true),
    ObdLinkCan(Adapter.OBDLink, BusProtocol.CAN, "OBDLink CAN"),
    ObdLinkCanBroadcast(Adapter.OBDLink, BusProtocol.CanBroadcast, "OBDLink CAN broadcast", supportsPoll = false, supportsMonitor = true),
    ObdLinkExKline(Adapter.OBDLinkEx, BusProtocol.KLine, "OBDLink EX K-line", supportsStream = true),
    ObdLinkExCan(Adapter.OBDLinkEx, BusProtocol.CAN, "OBDLink EX CAN"),
    ObdLinkExCanBroadcast(Adapter.OBDLinkEx, BusProtocol.CanBroadcast, "OBDLink EX CAN broadcast", supportsPoll = false, supportsMonitor = true),
    Ft232rlKline(Adapter.Ft232rl, BusProtocol.KLine, "FT232RL raw K-line", supportsStream = true);

    /** True if this route can run in [mode]. */
    fun supports(mode: PollingMode): Boolean = when (mode) {
        PollingMode.Poll -> supportsPoll
        PollingMode.Stream -> supportsStream
        PollingMode.Monitor -> supportsMonitor
    }

    /** The mode to fall back to when the selected one isn't supported here —
     *  Poll for request/response routes, Monitor for listen-only broadcast. */
    val defaultMode: PollingMode
        get() = when {
            supportsPoll -> PollingMode.Poll
            supportsMonitor -> PollingMode.Monitor
            supportsStream -> PollingMode.Stream
            else -> PollingMode.Poll
        }

    companion object {
        /**
         * The route for an (adapter, bus) pair, or null if that combination is
         * not a valid transport (e.g. FT232RL + CAN — the KKL cable is K-line
         * only; or any adapter + CanBroadcast until the Monitor source lands).
         */
        fun of(adapter: Adapter?, bus: BusProtocol?): TransportRoute? {
            if (adapter == null || bus == null) return null
            return entries.firstOrNull { it.adapter == adapter && it.bus == bus }
        }
    }
}
