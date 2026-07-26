package com.protocol.app.protocol

import com.protocol.app.openport2.PollSample

/**
 * Top-level destinations reachable above the Home / Live Data pager. When
 * activeSubPage is null the pager is visible; otherwise its sub-page is.
 */
sealed class SubPage {
    object Parameters : SubPage()
    object TcmParameters : SubPage()
    object Unverified : SubPage()
    object LiveDataSettings : SubPage()
    object Settings : SubPage()
    /** Flashing lives in a dedicated standalone app (kept out of the live-logging
     *  app for safety). This destination is a placeholder/stub — the Home "Flash
     *  ECU" button will become a download link to that app once it ships. */
    object Flash : SubPage()
    object Tuning : SubPage()
    object Notices : SubPage()
    /** Verified init sequences + adapter command palettes, READ-ONLY. Reached
     *  from the Home "Library" button. Renders AdapterCommandLibrary; nothing
     *  depends on it (easy to remove). */
    object Library : SubPage()
    /** Developer / Raw Command Interface — its own page, reached by the Home
     *  DEV MODE button. Master ON/OFF lives at the top of this page. */
    object Developer : SubPage()
}

/**
 * Action the user kicked off from a button on Home / Live Data that needs
 * a connected adapter. If we're not connected yet, the Activity stashes
 * this in the VM and starts USB discovery; setOpenSession replays the
 * action once the session is up.
 */
enum class PendingAction { ReadLive, LogLive }

data class ProtocolUiState(
    val connectionStatus: ConnectionStatus = ConnectionStatus.NoDevice,
    /** True when an adapter is physically present (USB device on the bus, or
     *  OBDLink BT connected). Drives the status stripe: present = white,
     *  absent = invisible. Distinct from [connectionStatus], which tracks the
     *  session/permission state used for the status message text. */
    val adapterPresent: Boolean = false,
    /** Incremented each time a live source reports the ECU stopped answering
     *  (NoEcuResponseException). The UI observes the change to auto-exit lock
     *  mode and run the no-ECU red/white flash on the top/bottom bars. */
    val noEcuEventId: Int = 0,
    val statusMessage: String = "",
    val isReadingLive: Boolean = false,
    val isLogging: Boolean = false,
    val liveValues: Map<String, Double> = emptyMap(),
    /** Lowest value seen for each PID since the current Read Live Data session started. Cleared on stop / detach / layout removal. */
    val liveValuesMin: Map<String, Double> = emptyMap(),
    /** Highest value seen for each PID since the current Read Live Data session started. Cleared on stop / detach / layout removal. */
    val liveValuesMax: Map<String, Double> = emptyMap(),
    val lastSampleTimestampMs: Long = 0L,
    /** Wire-time of the most recent poll: ms the K-line was actively transmitting/receiving (queries + responses + parse). Excludes the inter-cycle [AppSettings.pollIntervalMs] delay so the displayed number reflects what the ECU/TCM is really taking to answer. 0 when no poll has completed. */
    val lastPollWireMs: Long = 0L,
    /** True if the most recent poll's ECM query succeeded. Stays true when there are no ECM PIDs on the page. */
    val ecmReplying: Boolean = true,
    /** True if the most recent poll's TCM query succeeded. Stays true when there are no TCM PIDs on the page. */
    val tcmReplying: Boolean = true,
    val sessionLog: List<PollSample> = emptyList(),
    /** True while a one-shot DTC read is in flight (Diagnostics page). */
    val isReadingDtc: Boolean = false,
    /** True while a DTC reset (SSM2 clear-memory write) is in flight. */
    val isResettingDtc: Boolean = false,
    /** Human-readable status line for the Diagnostics page DTC read. */
    val dtcStatus: String = "",
    /** Current (temporary) trouble codes from the last read, "P0xxx  DESCRIPTION". */
    val dtcCurrent: List<String> = emptyList(),
    /** Stored (memorized) trouble codes from the last read. */
    val dtcStored: List<String> = emptyList(),
    val gaugeLayout: GaugeLayout = GaugeLayout(),
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
