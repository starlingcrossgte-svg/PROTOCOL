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
    /** Saved parameter groups for the definition currently loaded. Reached from
     *  the Live Data hamburger; owns creating, applying and deleting them. */
    object Presets : SubPage()
    object Settings : SubPage()
    /** Flashing lives in a dedicated standalone app (kept out of the live-logging
     *  app for safety). This destination is a placeholder/stub — the Home "Flash
     *  ECU" button will become a download link to that app once it ships. */
    object Flash : SubPage()
    object Tuning : SubPage()
    /** Calibration table editing — view and change the tables a ROM definition
     *  describes. Reads a ROM image plus a table definition, both user-supplied;
     *  kept apart from the live-logging path and from the flash silo. */
    object TableEditor : SubPage()
    /** How to operate each part of the app, with worked examples. Content only —
     *  owns no state and touches no transport. */
    object Navigation : SubPage()
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

/**
 * In-progress preset being built on the Parameters page.
 *
 * Non-null puts that page into selection mode: a pinned bar appears, taps
 * collect into [pidIds] instead of moving gauges on and off Live Data, and the
 * user's real layout is left completely alone until they finish. Building a
 * preset must never disturb what is currently on the page, because the usual
 * reason to build one is that the current page is already set up the way you
 * want it for something else.
 */
data class PresetDraft(
    /** Selection order, which becomes the gauge order when applied. */
    val pidIds: List<String> = emptyList(),
    /** True once DONE was pressed and the name prompt is showing. */
    val awaitingName: Boolean = false
) {
    val isFull: Boolean get() = pidIds.size >= MAX_PRESET_PIDS
}

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
    val settings: AppSettings = AppSettings(),
    /** Runtime parameters from a user-loaded logger definition, merged with the
     *  built-in set everywhere the param universe is used. Empty = none loaded,
     *  so the app behaves exactly as before. */
    val loadedPids: List<com.protocol.app.openport2.Ssm2Pid> = emptyList(),
    /** True while a definition file is being read and parsed off the main thread. */
    val loggerDefLoading: Boolean = false,
    /** Outcome of the last definition load, shown under the picker. Empty before
     *  any attempt. A definition that yields no parameters, or a file that can no
     *  longer be read, reports here instead of leaving the lists silently blank. */
    val loggerDefStatus: String = "",
    /**
     * Incremented once per tap received from a paired watch. The Live Data page
     * observes the change and routes it into the SAME tap loop a finger tap
     * uses, so a remote tap is indistinguishable from a screen tap by the time
     * it reaches the logic — there is never a second path that could disagree
     * about whether logging is running.
     *
     * Same pattern as [noEcuEventId]: a counter, not a flag, so consecutive
     * taps each register.
     */
    val remoteTapEventId: Int = 0,
    /** Saved presets for the parameter set currently loaded. Re-read whenever
     *  the definition changes, so this always holds that definition's own. */
    val userPresets: List<UserPreset> = emptyList(),
    /** Non-null while the user is building a preset. See [PresetDraft]. */
    val presetDraft: PresetDraft? = null,
    /**
     * A preset chosen but NOT yet applied. Set by stepping the watch bezel;
     * cleared by the tap that confirms it.
     *
     * Staging exists because the gauges on the page decide the CSV columns. A
     * bezel that applied instantly would let a knock of the wrist change what a
     * recording contains halfway through, and the resulting file would have no
     * record of when it happened. Requiring a deliberate tap means a log's
     * columns only ever change because someone meant them to.
     */
    val stagedPresetId: String? = null,
    /**
     * One-line identity of the module currently answering: its SSM id and the
     * calibration id it reported, as read off the wire. Shown on every log, so
     * an exported capture can still be attributed to a car months later.
     *
     * Null until the ECU has been identified — never a placeholder or a guess.
     * A definition names addresses that are only correct for a specific
     * calibration, so an identity line that was assumed rather than read would
     * be worse than none at all.
     */
    val ecuIdentity: String? = null
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
