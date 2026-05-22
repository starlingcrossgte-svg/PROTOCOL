package com.protocol.app.protocol

import com.protocol.app.openport2.PollSample
import com.protocol.app.openport2.Ssm2DecodeBundle
import com.protocol.app.openport2.Ssm2EcmProbe
import com.protocol.app.openport2.TactrixCommandLog

/**
 * Top-level destinations reachable above the Home / Live Data pager. When
 * activeSubPage is null the pager is visible; otherwise its sub-page is.
 */
sealed class SubPage {
    object Parameters : SubPage()
    object Settings : SubPage()
    object Flash : SubPage()
    object Diagnostics : SubPage()
    object Tuning : SubPage()
}

/**
 * Action the user kicked off from a button on Home / Live Data that needs
 * a connected adapter. If we're not connected yet, the Activity stashes
 * this in the VM and starts USB discovery; setOpenSession replays the
 * action once the session is up.
 */
enum class PendingAction { Probe, ReadLive, LogLive }

data class ProtocolUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.NoDevice,
    val statusMessage: String = "",
    val isRunningProbe: Boolean = false,
    val isReadingLive: Boolean = false,
    val isLogging: Boolean = false,
    val log: List<TactrixCommandLog> = emptyList(),
    val lastOutcome: Ssm2EcmProbe.ProbeOutcome? = null,
    val ssm2DecodeBundle: Ssm2DecodeBundle? = null,
    val ssm2ResponseHex: String = "",
    val attStepDurationMs: Long? = null,
    val liveValues: Map<String, Double> = emptyMap(),
    val lastSampleTimestampMs: Long = 0L,
    val sessionLog: List<PollSample> = emptyList(),
    val gaugeLayout: GaugeLayout = GaugeLayout(),
    val tappedParamLongName: String? = null,
    val activeSubPage: SubPage? = null,
    /** True while the user is moving/resizing/removing gauges. Transient — not persisted. */
    val editMode: Boolean = false,
    /** URI of the user-chosen background image. Null = default Y2K dark surface. */
    val backgroundUri: String? = null,
    /** Tunable preferences from the Settings sub-page. */
    val settings: AppSettings = AppSettings()
) {
    /** PIDs currently placed on the Live Data page. Drives log/CSV columns. */
    val pidIdsOnLiveData: Set<String> get() = gaugeLayout.pidIds
}

sealed class ConnectionStatus {
    object NoDevice : ConnectionStatus()
    data class PermissionRequired(val deviceLabel: String) : ConnectionStatus()
    data class Ready(val deviceLabel: String) : ConnectionStatus()
    data class Connected(val deviceLabel: String) : ConnectionStatus()
    data class Error(val reason: String) : ConnectionStatus()
}
