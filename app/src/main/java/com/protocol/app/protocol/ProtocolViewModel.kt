package com.protocol.app.protocol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.protocol.app.obdlink.LiveSampleSource
import com.protocol.app.obdlink.ObdLinkBtManager
import com.protocol.app.obdlink.ObdLinkKlineSource
import com.protocol.app.obdlink.ObdLinkLiveSource
import com.protocol.app.obdlink.ObdLinkTcpManager
import com.protocol.app.openport2.OpenPort2UsbSession
import com.protocol.app.openport2.OpenPort2UsbSessionManager
import com.protocol.app.openport2.OpenPortCanLiveSource
import com.protocol.app.openport2.PollSample
import com.protocol.app.openport2.Ssm2EcmProbe
import com.protocol.app.openport2.Ssm2Pid
import com.protocol.app.openport2.Ssm2Pids
import com.protocol.app.openport2.Ssm2Poller
import com.protocol.app.openport2.TactrixBulkIo
import com.protocol.app.openport2.TactrixClient
import com.protocol.app.openport2.TactrixHex
import com.protocol.app.openport2.TactrixTcpIo
import com.protocol.app.openport2.UsbDisconnectedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val DEFAULT_STARTER_PID_IDS = listOf("rpm", "coolant", "battery", "oil", "iat")

/**
 * Fold a new batch of values into a running min-or-max map. [shouldReplace]
 * returns true when the incoming value should overwrite the previous one
 * (min: v < prev; max: v > prev). Pids without a prior entry get seeded
 * with the first observed value.
 */
private inline fun updateExtreme(
    current: Map<String, Double>,
    incoming: Map<String, Double>,
    shouldReplace: (newVal: Double, prev: Double) -> Boolean
): Map<String, Double> {
    if (incoming.isEmpty()) return current
    var acc = current
    for ((k, v) in incoming) {
        val prev = acc[k]
        if (prev == null || shouldReplace(v, prev)) {
            acc = acc + (k to v)
        }
    }
    return acc
}

class ProtocolViewModel : ViewModel() {

    private companion object {
        // Rewrite the autosaved session-log file every N poll samples.
        // At default 200 ms poll interval that's ~one disk write per
        // second — frequent enough to survive a crash without losing
        // more than ~1 second of data, infrequent enough to be gentle
        // on cache storage on long logs.
        private const val AUTOSAVE_EVERY_N_SAMPLES = 5
    }

    private val _uiState = MutableStateFlow(ProtocolUiState())
    val uiState: StateFlow<ProtocolUiState> = _uiState.asStateFlow()

    private var sessionManager: OpenPort2UsbSessionManager? = null
    private var openSession: OpenPort2UsbSession? = null
    private var tactrixClient: TactrixClient? = null
    private var runJob: Job? = null
    // Reference to the currently-active poller, kept here so updateLayout
    // can hand it a fresh PID set without restarting the flow (so gauges
    // don't flicker when the user toggles a parameter while reading live).
    private var runningPoller: Ssm2Poller? = null
    // Non-USB-K-line live source (OBDLink CAN, OBDLink K-line, OpenPort CAN).
    // Selected per run based on adapter × protocol. Held as the interface so
    // updateLayout can hand any of the three concrete impls a fresh PID set.
    private var runningLiveSource: LiveSampleSource? = null
    private var obdLinkManager: ObdLinkBtManager? = null
    // TCP transport for simulator mode (OpenPort paths). Held across the
    // coroutine so stop / disconnect can close the socket and drop the
    // reference.
    private var simulatorIo: TactrixTcpIo? = null
    // TCP-backed OBDLink manager for simulator mode (OBDLink paths). Held
    // separately so its lifecycle is independent of the real-BT manager.
    private var simulatorObdLink: ObdLinkTcpManager? = null
    private var layoutStore: GaugeLayoutStore? = null
    private var backgroundStore: BackgroundStore? = null
    private var settingsStore: SettingsStore? = null
    private var garageStore: GarageStore? = null
    private var sessionLogStore: SessionLogStore? = null
    // Saved-sample counter — used to throttle disk autosaves to every
    // AUTOSAVE_EVERY_N_SAMPLES poll cycles so we don't hammer cache
    // storage at 5 Hz on long-running logs.
    private var samplesSinceLastAutosave: Int = 0

    // Action the user requested while the adapter wasn't yet connected.
    // setOpenSession replays this once a session is open so the user
    // doesn't have to tap the button a second time after granting USB
    // permission.
    private var pendingAction: PendingAction? = null

    fun attachSessionManager(manager: OpenPort2UsbSessionManager) {
        sessionManager = manager
    }

    /**
     * Wire the persistent gauge-layout store. First-launch behavior is a
     * starter layout (RPM, Coolant, Battery, Oil Temp, IAT) so the user
     * lands on a useful Live Data screen instead of a blank grid. Existing
     * installs that already have a saved layout keep it untouched.
     */
    fun attachLayoutStore(store: GaugeLayoutStore) {
        layoutStore = store
        val existing = store.load()
        val layout = existing ?: DEFAULT_STARTER_PID_IDS.fold(GaugeLayout()) { acc, id ->
            acc.withAdded(id)
        }
        _uiState.value = _uiState.value.copy(gaugeLayout = layout)
        if (existing == null) store.save(layout)
    }

    /**
     * Wire the persistent background-image store. Loaded URI (if any) is
     * dropped into UiState so the photo renders on first composition.
     */
    fun attachBackgroundStore(store: BackgroundStore) {
        backgroundStore = store
        _uiState.value = _uiState.value.copy(backgroundUri = store.load())
    }

    fun setBackgroundUri(uri: String?) {
        if (_uiState.value.backgroundUri == uri) return
        _uiState.value = _uiState.value.copy(backgroundUri = uri)
        backgroundStore?.save(uri)
    }

    /**
     * Wire the persistent settings store. Loads existing prefs and pushes
     * them into UiState so the rest of the app sees the user's last values
     * on first composition.
     */
    fun attachSettingsStore(store: SettingsStore) {
        settingsStore = store
        _uiState.value = _uiState.value.copy(settings = store.load())
    }

    private fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val next = transform(_uiState.value.settings)
        if (next == _uiState.value.settings) return
        _uiState.value = _uiState.value.copy(settings = next)
        settingsStore?.save(next)
    }

    fun setPollIntervalMs(ms: Int) = updateSettings {
        it.copy(pollIntervalMs = ms.coerceIn(AppSettings.POLL_INTERVAL_MIN, AppSettings.POLL_INTERVAL_MAX))
    }

    fun setSessionLogMaxSize(rows: Int) = updateSettings {
        it.copy(sessionLogMaxSize = rows.coerceIn(AppSettings.SESSION_LOG_MIN, AppSettings.SESSION_LOG_MAX))
    }

    fun setDevMode(on: Boolean) = updateSettings { it.copy(devMode = on) }

    fun setAdapter(adapter: Adapter?) {
        // Switching adapter / protocol invalidates any cached K-line channel
        // state so the next Read Live re-runs the full ATI..ATV init. If a
        // poll is currently running, stop it first — letting it continue
        // against the old adapter while the UI shows the new selection would
        // be silently wrong.
        val state = _uiState.value
        if (state.isReadingLive || state.isLogging) stopReadingLive()
        tactrixClient?.channelInitialized = false
        updateSettings { it.copy(adapter = adapter) }
    }

    fun setProtocol(protocol: BusProtocol?) {
        val state = _uiState.value
        if (state.isReadingLive || state.isLogging) stopReadingLive()
        tactrixClient?.channelInitialized = false
        updateSettings { it.copy(protocol = protocol) }
    }

    fun setSsmVariant(variant: SsmVariant?) = updateSettings { it.copy(ssmVariant = variant) }

    fun setSimulatorMode(on: Boolean) = updateSettings { it.copy(simulatorMode = on) }

    fun setSimulatorPort(port: Int) = updateSettings {
        it.copy(simulatorPort = port.coerceIn(AppSettings.SIMULATOR_PORT_MIN, AppSettings.SIMULATOR_PORT_MAX))
    }


    /**
     * Routed from the BT-permission denial path. Drops the OBDLink selection
     * so the Settings UI reflects reality and nothing else tries to connect.
     * The legacy obdLinkEnabled flag is no longer read by anything live — it
     * stays persisted only to avoid prefs migration churn for existing users.
     */
    fun clearObdLinkAdapter() {
        if (_uiState.value.settings.adapter == Adapter.OBDLink) setAdapter(null)
        disconnectObdLink()
    }

    /**
     * Dev-only: hunt the STN init that opens raw K-line SSM2 on the MX+ (for the
     * EZ30R / 3.0R). Basic-connects, then cycles [ObdLinkProbeCandidates.klineInitMatrix]
     * against the ECU until it answers an SSM2 reply (80 F0 10). Every step
     * streams to the Developer BT log; the winning init lands in the status.
     */
    fun huntKlineInit(appContext: android.content.Context) {
        setConnectionStatus(ConnectionStatus.PermissionRequired("OBDLink"), "Hunting K-line init…")
        viewModelScope.launch(Dispatchers.IO) {
            val mgr = obdLinkManager
                ?: com.protocol.app.obdlink.ObdLinkBtManager(appContext).also { obdLinkManager = it }
            when (val r = mgr.connectBasic()) {
                is com.protocol.app.obdlink.ObdLinkBtManager.ConnectResult.Failure ->
                    setConnectionStatus(ConnectionStatus.Error(r.reason), r.reason)
                is com.protocol.app.obdlink.ObdLinkBtManager.ConnectResult.Connected -> {
                    val transport = mgr.transport
                    if (transport == null) {
                        setConnectionStatus(ConnectionStatus.Error("no transport"), "K-line hunt: no transport")
                        return@launch
                    }
                    val prober = com.protocol.app.obdlink.ObdLinkAutoProber(transport)
                    val outcome = prober.run(
                        com.protocol.app.obdlink.ObdLinkProbeCandidates.klineInitMatrix(),
                        com.protocol.app.obdlink.ObdLinkProbeClassifier::klineReplyHit
                    )
                    when (outcome) {
                        is com.protocol.app.obdlink.ObdLinkProbeOutcome.Hit ->
                            setConnectionStatus(
                                ConnectionStatus.Connected("OBDLink"),
                                "K-line init FOUND: ${outcome.candidate.label} — see BT log"
                            )
                        is com.protocol.app.obdlink.ObdLinkProbeOutcome.Exhausted ->
                            setConnectionStatus(
                                ConnectionStatus.Error("no K-line init worked"),
                                "K-line hunt: none of ${outcome.tried} candidates worked — see BT log"
                            )
                    }
                }
            }
        }
    }

    /**
     * Utility: factory-reset the paired OBDLink adapter over Bluetooth. Sends
     * ATPP FF OFF (clear all programmable parameters / NVM-persisted config),
     * ATD (restore default settings), ATZ (full reset). Returns the adapter to a
     * known factory state, clearing any persisted protocol/PP config. The adapter
     * reboots after ATZ, so the manager is discarded; the next connect builds a fresh one.
     */
    fun resetObdLinkAdapter(appContext: android.content.Context) {
        setConnectionStatus(ConnectionStatus.PermissionRequired("OBDLink"), "Resetting OBDLink adapter…")
        viewModelScope.launch(Dispatchers.IO) {
            val mgr = obdLinkManager
                ?: com.protocol.app.obdlink.ObdLinkBtManager(appContext).also { obdLinkManager = it }
            val r = mgr.resetAdapter()
            obdLinkManager = null // adapter reboots after ATZ — force a fresh manager next time
            when (r) {
                is com.protocol.app.obdlink.ObdLinkBtManager.ConnectResult.Connected ->
                    setConnectionStatus(
                        ConnectionStatus.NoDevice,
                        "OBDLink factory reset sent (ATPP FF OFF / ATD / ATZ) — power-cycle the adapter, then re-pair. See Developer BT log."
                    )
                is com.protocol.app.obdlink.ObdLinkBtManager.ConnectResult.Failure ->
                    setConnectionStatus(ConnectionStatus.Error(r.reason), r.reason)
            }
        }
    }

    /**
     * Connect the *paired* OBDLink over Bluetooth, picking the right channel
     * mode based on the user's selected protocol. The Activity must have
     * granted BLUETOOTH_CONNECT first. No-op unless OBDLink is the selected
     * adapter (defense in depth on stale callbacks).
     */
    fun connectObdLink(appContext: android.content.Context) {
        val s = _uiState.value.settings
        if (s.adapter != Adapter.OBDLink) return
        val protocol = s.protocol ?: run {
            setConnectionStatus(
                ConnectionStatus.Error("PROTOCOL not picked"),
                "Pick K-Line or CAN in Settings before connecting OBDLink"
            )
            return
        }
        val protocolLabel = if (protocol == BusProtocol.KLine) "K-line" else "CAN"
        setConnectionStatus(
            ConnectionStatus.PermissionRequired("OBDLink"),
            "Connecting OBDLink ($protocolLabel)..."
        )
        viewModelScope.launch(Dispatchers.IO) {
            val mgr = obdLinkManager
                ?: ObdLinkBtManager(appContext).also { obdLinkManager = it }
            val r = when (protocol) {
                BusProtocol.CAN -> mgr.connect()
                BusProtocol.KLine -> mgr.connectKline()
            }
            when (r) {
                is ObdLinkBtManager.ConnectResult.Connected ->
                    setConnectionStatus(
                        ConnectionStatus.Connected(r.deviceLabel),
                        "Connected to ${r.deviceLabel} ($protocolLabel)"
                    )
                is ObdLinkBtManager.ConnectResult.Failure -> {
                    obdLinkManager?.disconnect()
                    obdLinkManager = null
                    setConnectionStatus(ConnectionStatus.Error(r.reason), r.reason)
                }
            }
        }
    }

    /** Tear down the OBDLink link. Safe whether or not it's connected; leaves a
     *  live USB session (if any) untouched. */
    fun disconnectObdLink() {
        val wasObd = runningLiveSource != null && _uiState.value.settings.adapter == Adapter.OBDLink
        if (wasObd) {
            runJob?.cancel()
            runJob = null
            runningLiveSource = null
        }
        obdLinkManager?.disconnect()
        obdLinkManager = null
        if (openSession == null) {
            _uiState.value = _uiState.value.copy(
                isReadingLive = if (wasObd) false else _uiState.value.isReadingLive,
                isLogging = if (wasObd) false else _uiState.value.isLogging,
                liveValues = if (wasObd) emptyMap() else _uiState.value.liveValues,
                connectionStatus = ConnectionStatus.NoDevice,
                statusMessage = "OBDLink disconnected"
            )
        }
    }

    fun attachSessionLogStore(store: SessionLogStore) {
        sessionLogStore = store
    }

    /**
     * Wire the persistent garage store. Loads existing vehicles + selection
     * into UiState on first composition.
     */
    fun attachGarageStore(store: GarageStore) {
        garageStore = store
        _uiState.value = _uiState.value.copy(garage = store.load())
    }

    private fun updateGarage(transform: (GarageState) -> GarageState) {
        val next = transform(_uiState.value.garage)
        if (next == _uiState.value.garage) return
        _uiState.value = _uiState.value.copy(garage = next)
        garageStore?.save(next)
    }

    /**
     * Create + persist a new vehicle. All four fields are stored as
     * passed in (the UI uppercases them at the input layer). At least
     * one of year/make/model must be non-blank or the call is a no-op
     * — blank-everywhere vehicles aren't useful and clutter the list.
     */
    fun addVehicle(year: String, make: String, model: String, subModel: String) {
        val y = year.trim(); val mk = make.trim(); val md = model.trim(); val sm = subModel.trim()
        if (y.isBlank() && mk.isBlank() && md.isBlank()) return
        val vehicle = Vehicle(
            id = java.util.UUID.randomUUID().toString(),
            year = y, make = mk, model = md, subModel = sm
        )
        updateGarage { it.copy(vehicles = it.vehicles + vehicle) }
    }

    /**
     * Toggle the active vehicle. Passing the currently-selected id
     * deselects (only one vehicle can be active at a time). Passing
     * null also deselects. Unknown ids are no-ops.
     */
    fun selectVehicle(id: String?) {
        updateGarage { current ->
            val next = when {
                id == null -> null
                id == current.selectedVehicleId -> null
                current.vehicles.any { it.id == id } -> id
                else -> current.selectedVehicleId
            }
            current.copy(selectedVehicleId = next)
        }
    }

    /**
     * Remove a vehicle by id. If it was the selected one, selection
     * is cleared. Unknown ids are no-ops.
     */
    fun deleteVehicle(id: String) {
        updateGarage { current ->
            val remaining = current.vehicles.filterNot { it.id == id }
            val newSelected = if (current.selectedVehicleId == id) null else current.selectedVehicleId
            current.copy(vehicles = remaining, selectedVehicleId = newSelected)
        }
    }

    /**
     * Clear all gauges from the Live Data page. Persists immediately.
     * Surfaced from Settings → Reset Gauge Layout.
     */
    fun resetLayout() {
        val empty = GaugeLayout()
        _uiState.value = _uiState.value.copy(gaugeLayout = empty)
        layoutStore?.save(empty)
    }

    private fun updateLayout(transform: (GaugeLayout) -> GaugeLayout) {
        val current = _uiState.value.gaugeLayout
        val next = transform(current)
        if (next === current) return
        // Drop liveValues / min / max for PIDs no longer on the page —
        // keeping stale entries would just bloat the maps over time.
        val keptValues = _uiState.value.liveValues.filterKeys { it in next.pidIds }
        val keptMin = _uiState.value.liveValuesMin.filterKeys { it in next.pidIds }
        val keptMax = _uiState.value.liveValuesMax.filterKeys { it in next.pidIds }
        _uiState.value = _uiState.value.copy(
            gaugeLayout = next,
            liveValues = keptValues,
            liveValuesMin = keptMin,
            liveValuesMax = keptMax
        )
        layoutStore?.save(next)
        // If a poll flow is running, hand it the new PID set without
        // tearing down — the next cycle picks it up automatically.
        val livePids = Ssm2Pids.DEFAULT_DEMO_PIDS.filter { it.id in next.pidIds }
        runningPoller?.updatePids(livePids)
        runningLiveSource?.updatePids(livePids)
    }

    /** Add a gauge for [pidId] to the Live Data page if not already present. */
    fun addGaugeForPid(pidId: String) {
        updateLayout { it.withAdded(pidId) }
    }

    /** Remove the gauge for [pidId] from the Live Data page if present. */
    fun removeGaugeForPid(pidId: String) {
        updateLayout { it.withRemoved(pidId) }
    }

    /** Toggle: add the gauge if absent, remove it if present. */
    fun toggleGaugeForPid(pidId: String) {
        updateLayout { if (it.contains(pidId)) it.withRemoved(pidId) else it.withAdded(pidId) }
    }

    fun openSubPage(page: SubPage) {
        if (_uiState.value.activeSubPage == page) return
        // Switching screens implicitly exits edit mode — drag handles would
        // be confusing if they hung around after the user moved off Live Data.
        _uiState.value = _uiState.value.copy(activeSubPage = page, editMode = false)
    }

    fun closeSubPage() {
        if (_uiState.value.activeSubPage == null) return
        _uiState.value = _uiState.value.copy(activeSubPage = null)
    }

    fun enterEditMode() {
        if (_uiState.value.editMode) return
        _uiState.value = _uiState.value.copy(editMode = true)
    }

    fun exitEditMode() {
        if (!_uiState.value.editMode) return
        _uiState.value = _uiState.value.copy(editMode = false)
    }

    /**
     * Move/resize an existing gauge. Returns true if the change was accepted
     * (no overlap, fits in grid). Phase C wires this to the edit-mode drag
     * handles; nothing calls it yet in Phase A.
     */
    fun resizeGauge(pidId: String, col: Int, row: Int, width: Int, height: Int): Boolean {
        val current = _uiState.value.gaugeLayout
        val next = current.withResized(pidId, col, row, width, height) ?: return false
        _uiState.value = _uiState.value.copy(gaugeLayout = next)
        layoutStore?.save(next)
        return true
    }

    fun setConnectionStatus(status: ConnectionStatus, message: String = "") {
        _uiState.value = _uiState.value.copy(
            connectionStatus = status,
            statusMessage = message
        )
    }

    fun setOpenSession(session: OpenPort2UsbSession, deviceLabel: String) {
        openSession = session
        tactrixClient = TactrixClient(TactrixBulkIo(session))
        _uiState.value = _uiState.value.copy(
            connectionStatus = ConnectionStatus.Connected(deviceLabel),
            statusMessage = "Connected to $deviceLabel"
        )
        // Replay whatever action the user kicked off while we were
        // discovering / waiting for USB permission. Cleared first so a
        // failed action doesn't loop.
        val pending = pendingAction
        pendingAction = null
        when (pending) {
            PendingAction.Probe -> runProbe()
            PendingAction.ReadLive -> startReadingLive(recordToLog = false)
            PendingAction.LogLive -> startLogging()
            null -> Unit
        }
    }

    /**
     * Action the user requested but couldn't run yet because the adapter
     * wasn't connected. [setOpenSession] consumes this once the session is
     * up. Stays unset on success — caller is responsible for triggering
     * discovery alongside this call.
     */
    fun setPendingAction(action: PendingAction) {
        pendingAction = action
    }

    fun isConnected(): Boolean =
        _uiState.value.connectionStatus is ConnectionStatus.Connected

    fun clearOpenSession() {
        runJob?.cancel()
        runJob = null
        val wasRunning = _uiState.value.isRunningProbe
        val wasReading = _uiState.value.isReadingLive
        val wasLogging = _uiState.value.isLogging
        val session = openSession
        openSession = null
        tactrixClient = null
        if (session != null) {
            sessionManager?.closeSession(session)
        }
        // Wipe the probe outcome card whenever the adapter goes away — a stale
        // "ECU REPLIED" sitting on screen after detach is misleading. If a run
        // was mid-flight, mark it as USB-disconnected; otherwise just clear.
        // Also drop liveValues so gauges revert to "--" instead of showing
        // the last value they had before the adapter vanished.
        _uiState.value = _uiState.value.copy(
            isRunningProbe = false,
            isReadingLive = false,
            isLogging = false,
            liveValues = emptyMap(),
            liveValuesMin = emptyMap(),
            liveValuesMax = emptyMap(),
            lastSampleTimestampMs = 0L,
            lastPollWireMs = 0L,
            log = if (wasRunning) _uiState.value.log else emptyList(),
            lastOutcome = if (wasRunning) Ssm2EcmProbe.ProbeOutcome.FAIL_USB_DISCONNECTED else null,
            ssm2DecodeBundle = if (wasRunning) _uiState.value.ssm2DecodeBundle else null,
            ssm2ResponseHex = if (wasRunning) _uiState.value.ssm2ResponseHex else "",
            attStepDurationMs = if (wasRunning) _uiState.value.attStepDurationMs else null,
            statusMessage = if (wasRunning || wasReading || wasLogging)
                "USB device detached — run aborted"
            else
                "USB device detached"
        )
    }

    /**
     * Start polling the ECU and updating live gauge values without recording
     * samples to the session log. If [recordToLog] is true, samples are also
     * appended to the session log (this is the "Log Live Data" mode).
     *
     * Calling this while a poll job is already running with the same or higher
     * recording level is a no-op; calling it while a poll job is running with
     * recordToLog=false and this call has recordToLog=true just flips the
     * recording flag and lets the existing job continue.
     */
    fun startReadingLive(recordToLog: Boolean = false) {
        val state = _uiState.value
        if (state.isRunningProbe) return
        if (state.isReadingLive) {
            // Poll job already running — just flip the recording flag if needed.
            if (recordToLog && !state.isLogging) {
                _uiState.value = state.copy(
                    isLogging = true,
                    sessionLog = emptyList(),
                    statusMessage = "Logging live data..."
                )
            }
            return
        }

        val useSimulator = state.settings.simulatorMode
        val adapter = state.settings.adapter
        val protocol = state.settings.protocol

        val pidsOnPage = Ssm2Pids.DEFAULT_DEMO_PIDS.filter { it.id in state.gaugeLayout.pidIds }
        if (pidsOnPage.isEmpty()) {
            _uiState.value = state.copy(
                statusMessage = "Add gauges from the Parameters menu before reading live data."
            )
            return
        }

        // Pre-flight: ADAPTER + PROTOCOL must be picked (true even in simulator
        // mode — the selectors decide which of the four paths runs against
        // VIPER, so an empty selector would just route nowhere).
        if (adapter == null || protocol == null) {
            _uiState.value = state.copy(
                statusMessage = "Pick ADAPTER and PROTOCOL in Settings first"
            )
            return
        }
        // Live-hardware paths additionally require the adapter to be connected.
        // Simulator paths skip this — they open their own TCP socket on demand.
        if (!useSimulator) {
            when (adapter) {
                Adapter.OpenPort -> if (openSession == null || tactrixClient == null) {
                    _uiState.value = state.copy(
                        statusMessage = "OpenPort not connected — Settings → tap OpenPort 2.0 to grant USB permission"
                    )
                    return
                }
                Adapter.OBDLink -> if (obdLinkManager?.isConnected() != true) {
                    _uiState.value = state.copy(
                        statusMessage = "OBDLink not connected — Settings → tap OBDLink MX+ to pair and grant Bluetooth"
                    )
                    return
                }
            }
        }

        val simHostPort = "127.0.0.1:${state.settings.simulatorPort}"
        val initialStatus = when {
            useSimulator && adapter == Adapter.OpenPort && protocol == BusProtocol.KLine ->
                "Simulator: OpenPort K-line @ $simHostPort"
            useSimulator && adapter == Adapter.OpenPort && protocol == BusProtocol.CAN ->
                "Simulator: OpenPort CAN @ $simHostPort"
            useSimulator && adapter == Adapter.OBDLink && protocol == BusProtocol.KLine ->
                "Simulator: OBDLink K-line @ $simHostPort"
            useSimulator && adapter == Adapter.OBDLink && protocol == BusProtocol.CAN ->
                "Simulator: OBDLink CAN @ $simHostPort"
            adapter == Adapter.OpenPort && protocol == BusProtocol.KLine ->
                if (tactrixClient?.channelInitialized == true) "Reusing K-line channel..." else "Initializing OpenPort K-line..."
            adapter == Adapter.OpenPort && protocol == BusProtocol.CAN -> "Initializing OpenPort CAN @ 500 kbps..."
            adapter == Adapter.OBDLink && protocol == BusProtocol.KLine -> "Polling OBDLink K-line @ 4800..."
            adapter == Adapter.OBDLink && protocol == BusProtocol.CAN -> "Polling OBDLink CAN @ 500k..."
            else -> "Connecting..."
        }

        _uiState.value = state.copy(
            isReadingLive = true,
            isLogging = recordToLog,
            liveValues = emptyMap(),
            lastSampleTimestampMs = 0L,
            sessionLog = if (recordToLog) emptyList() else state.sessionLog,
            statusMessage = initialStatus
        )

        val pollIntervalMs = state.settings.pollIntervalMs.toLong()

        runJob = viewModelScope.launch(Dispatchers.IO) {
            val simPort = state.settings.simulatorPort
            val sampleFlow: kotlinx.coroutines.flow.Flow<PollSample>? = try {
                when {
                    useSimulator && adapter == Adapter.OpenPort && protocol == BusProtocol.KLine ->
                        startSimulatorOpenPortKlineFlow(simPort, pidsOnPage, pollIntervalMs)
                    useSimulator && adapter == Adapter.OpenPort && protocol == BusProtocol.CAN ->
                        startSimulatorOpenPortCanFlow(simPort, pidsOnPage, pollIntervalMs)
                    useSimulator && adapter == Adapter.OBDLink && protocol == BusProtocol.KLine ->
                        startSimulatorObdLinkKlineFlow(simPort, pidsOnPage, pollIntervalMs)
                    useSimulator && adapter == Adapter.OBDLink && protocol == BusProtocol.CAN ->
                        startSimulatorObdLinkCanFlow(simPort, pidsOnPage, pollIntervalMs)
                    adapter == Adapter.OpenPort && protocol == BusProtocol.KLine -> startOpenPortKlineFlow(pidsOnPage, pollIntervalMs)
                    adapter == Adapter.OpenPort && protocol == BusProtocol.CAN -> startOpenPortCanFlow(pidsOnPage, pollIntervalMs)
                    adapter == Adapter.OBDLink && protocol == BusProtocol.KLine -> startObdLinkKlineFlow(pidsOnPage, pollIntervalMs)
                    adapter == Adapter.OBDLink && protocol == BusProtocol.CAN -> startObdLinkCanFlow(pidsOnPage, pollIntervalMs)
                    else -> null
                }
            } catch (e: UsbDisconnectedException) {
                _uiState.value = _uiState.value.copy(
                    isReadingLive = false,
                    isLogging = false,
                    statusMessage = "Adapter disconnected during init"
                )
                return@launch
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isReadingLive = false,
                    isLogging = false,
                    statusMessage = "Init failed: ${e.message ?: e.javaClass.simpleName}"
                )
                return@launch
            }

            if (sampleFlow == null) {
                _uiState.value = _uiState.value.copy(
                    isReadingLive = false,
                    isLogging = false,
                    statusMessage = "Channel init failed — check connection and retry"
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                statusMessage = if (_uiState.value.isLogging) "Logging live data..." else "Reading live data..."
            )
            try {
                sampleFlow.collect { sample ->
                    val current = _uiState.value
                    val maxRows = current.settings.sessionLogMaxSize
                    val nextSessionLog = if (current.isLogging) {
                        val trimmed = if (current.sessionLog.size >= maxRows)
                            current.sessionLog.drop(1)
                        else
                            current.sessionLog
                        trimmed + sample
                    } else {
                        current.sessionLog
                    }
                    // Partial-cycle merge: sample.values only contains entries
                    // for modules that responded this cycle. Merge onto the
                    // current liveValues so a missing module's gauges hold
                    // their last reading instead of going --.
                    val mergedValues = current.liveValues + sample.values
                    // Track per-PID min/max across the current Read Live Data
                    // session. The bottom row of each gauge tile shows these,
                    // cleared on stop / detach / layout removal.
                    val newMin = updateExtreme(current.liveValuesMin, sample.values) { v, prev -> v < prev }
                    val newMax = updateExtreme(current.liveValuesMax, sample.values) { v, prev -> v > prev }
                    _uiState.value = current.copy(
                        liveValues = mergedValues,
                        liveValuesMin = newMin,
                        liveValuesMax = newMax,
                        lastSampleTimestampMs = sample.timestampMs,
                        lastPollWireMs = sample.wireMs,
                        ecmReplying = sample.ecmOk,
                        tcmReplying = sample.tcmOk,
                        sessionLog = nextSessionLog
                    )
                    // Autosave the session log to disk while actively
                    // logging so a process kill doesn't lose the data.
                    // Throttled — see AUTOSAVE_EVERY_N_SAMPLES.
                    if (current.isLogging) {
                        samplesSinceLastAutosave += 1
                        if (samplesSinceLastAutosave >= AUTOSAVE_EVERY_N_SAMPLES) {
                            samplesSinceLastAutosave = 0
                            val csv = ProtocolLogFormatter.formatSessionLogCsv(_uiState.value)
                            sessionLogStore?.save(csv)
                        }
                    }
                }
            } catch (_: UsbDisconnectedException) {
                _uiState.value = _uiState.value.copy(
                    isReadingLive = false,
                    isLogging = false,
                    liveValues = emptyMap(),
                    liveValuesMin = emptyMap(),
                    liveValuesMax = emptyMap(),
                    lastSampleTimestampMs = 0L,
                    lastPollWireMs = 0L,
                    statusMessage = "USB device disconnected"
                )
                return@launch
            } catch (_: Exception) {
                // Coroutine cancelled — fall through to finally
            } finally {
                runningPoller = null
                runningLiveSource = null
                simulatorIo?.close()
                simulatorIo = null
                simulatorObdLink?.disconnect()
                simulatorObdLink = null
                // Drop the last-known values so gauges revert to "--" when
                // polling stops for any reason. Showing stale numbers after
                // a stop is misleading. Min/max also reset so the next
                // session starts with a clean range.
                _uiState.value = _uiState.value.copy(
                    isReadingLive = false,
                    isLogging = false,
                    liveValues = emptyMap(),
                    liveValuesMin = emptyMap(),
                    liveValuesMax = emptyMap(),
                    lastSampleTimestampMs = 0L,
                    lastPollWireMs = 0L
                )
            }
        }
    }

    /**
     * Simulator — OpenPort 2.0 + K-line. TCP socket to 127.0.0.1:<port> instead
     * of USB; init and poll loop are byte-identical to the live K-line path
     * because [TactrixTcpIo] implements the same [com.protocol.app.openport2.TactrixIo]
     * surface as [com.protocol.app.openport2.TactrixBulkIo].
     */
    private fun startSimulatorOpenPortKlineFlow(
        port: Int,
        pidsOnPage: List<Ssm2Pid>,
        pollIntervalMs: Long
    ): kotlinx.coroutines.flow.Flow<PollSample>? {
        val tcpIo = TactrixTcpIo("127.0.0.1", port)
        simulatorIo = tcpIo
        val client = TactrixClient(tcpIo)
        client.drainResponseBuffer()
        client.resetRequestIdCounter(startFrom = 2)
        val probe = Ssm2EcmProbe(client)
        if (!probe.initializeChannel(mutableListOf())) return null
        val poller = Ssm2Poller(client, pidsOnPage)
        runningPoller = poller
        return poller.startFlow(pollIntervalMs)
    }

    /**
     * Simulator — OpenPort 2.0 + CAN. Same TCP socket, same Tactrix wire format
     * the live CAN path uses (the `ato6 0 500000 0` channel open, the `att6`
     * single-frame request loop). [OpenPortCanLiveSource] already accepts
     * [com.protocol.app.openport2.TactrixIo], so swapping in [TactrixTcpIo]
     * routes the whole CAN path through the emulator unchanged.
     */
    private fun startSimulatorOpenPortCanFlow(
        port: Int,
        pidsOnPage: List<Ssm2Pid>,
        pollIntervalMs: Long
    ): kotlinx.coroutines.flow.Flow<PollSample>? {
        val tcpIo = TactrixTcpIo("127.0.0.1", port)
        simulatorIo = tcpIo
        val src = OpenPortCanLiveSource(tcpIo, pidsOnPage)
        if (!src.initChannel()) return null
        runningLiveSource = src
        return src.startFlow(pollIntervalMs)
    }

    /**
     * Simulator — OBDLink + K-line. [ObdLinkTcpManager] opens a TCP socket to
     * VIPER, wraps the streams in the same [com.protocol.app.obdlink.ObdLinkBtTransport]
     * the BT manager uses, and runs the same STN K-line init (STP21 / 4800 /
     * STIP4 0). Downstream [ObdLinkKlineSource] is identical to the live path.
     */
    private fun startSimulatorObdLinkKlineFlow(
        port: Int,
        pidsOnPage: List<Ssm2Pid>,
        pollIntervalMs: Long
    ): kotlinx.coroutines.flow.Flow<PollSample>? {
        val mgr = ObdLinkTcpManager()
        simulatorObdLink = mgr
        val r = mgr.connectKline("127.0.0.1", port)
        if (r is ObdLinkTcpManager.ConnectResult.Failure) {
            _uiState.value = _uiState.value.copy(statusMessage = "Simulator OBDLink K-line: ${r.reason}")
            return null
        }
        val transport = mgr.transport ?: return null
        val src = ObdLinkKlineSource(transport, pidsOnPage)
        runningLiveSource = src
        return src.startFlow(pollIntervalMs)
    }

    /**
     * Simulator — OBDLink + CAN. Same as the K-line simulator path but runs
     * the ELM CAN setup (ATSP6 / ATSH7E0 / ATCRA7E8 / flow control) so VIPER's
     * ELM-CAN handler picks up the right mode from the first writes.
     */
    private fun startSimulatorObdLinkCanFlow(
        port: Int,
        pidsOnPage: List<Ssm2Pid>,
        pollIntervalMs: Long
    ): kotlinx.coroutines.flow.Flow<PollSample>? {
        val mgr = ObdLinkTcpManager()
        simulatorObdLink = mgr
        val r = mgr.connect("127.0.0.1", port)
        if (r is ObdLinkTcpManager.ConnectResult.Failure) {
            _uiState.value = _uiState.value.copy(statusMessage = "Simulator OBDLink CAN: ${r.reason}")
            return null
        }
        val transport = mgr.transport ?: return null
        val src = ObdLinkLiveSource(transport, pidsOnPage)
        runningLiveSource = src
        return src.startFlow(pollIntervalMs)
    }

    /** OpenPort 2.0 + K-line: the original path, full ATI..ATV init then SSM2 polling. */
    private fun startOpenPortKlineFlow(
        pidsOnPage: List<Ssm2Pid>,
        pollIntervalMs: Long
    ): kotlinx.coroutines.flow.Flow<PollSample>? {
        val client = tactrixClient ?: return null
        client.drainResponseBuffer()
        client.resetRequestIdCounter(startFrom = 2)
        val probe = Ssm2EcmProbe(client)
        if (!probe.initializeChannel(mutableListOf())) return null
        val poller = Ssm2Poller(client, pidsOnPage)
        runningPoller = poller
        // Experimental continuous SSM2 mode (A8 flag 0x01): one request, the ECU
        // streams replies, dropping the per-cycle request transmit (~70% of
        // K-line wire time). Gated on Developer Mode + ECM-only pages (K-line
        // can't stream two modules); auto-falls-back to single-response if the
        // ECU ignores it. Verify in the BYTES log: look for `A8 01` and multiple
        // E8 replies per request.
        val ecmOnly = pidsOnPage.isNotEmpty() &&
            pidsOnPage.none { it.category == com.protocol.app.openport2.Ssm2PidCategory.TCM }
        return if (_uiState.value.settings.devMode && ecmOnly) {
            poller.startContinuousEcmFlow()
        } else {
            poller.startFlow(pollIntervalMs)
        }
    }

    /** OpenPort 2.0 + CAN: open ISO15765 channel @ 500k on 7E0/7E8 then per-address A8 polling. */
    private fun startOpenPortCanFlow(
        pidsOnPage: List<Ssm2Pid>,
        pollIntervalMs: Long
    ): kotlinx.coroutines.flow.Flow<PollSample>? {
        val session = openSession ?: return null
        val io = TactrixBulkIo(session)
        val src = OpenPortCanLiveSource(io, pidsOnPage)
        if (!src.initChannel()) return null
        runningLiveSource = src
        return src.startFlow(pollIntervalMs)
    }

    /** OBDLink + K-line: raw STN K-line mode + full SSM2 frame batched in one A8. */
    private fun startObdLinkKlineFlow(
        pidsOnPage: List<Ssm2Pid>,
        pollIntervalMs: Long
    ): kotlinx.coroutines.flow.Flow<PollSample>? {
        val transport = obdLinkManager?.transport ?: return null
        val src = ObdLinkKlineSource(transport, pidsOnPage)
        runningLiveSource = src
        return src.startFlow(pollIntervalMs)
    }

    /** OBDLink + CAN: ELM CAN channel set up by ObdLinkBtManager.connect(); poll one address at a time. */
    private fun startObdLinkCanFlow(
        pidsOnPage: List<Ssm2Pid>,
        pollIntervalMs: Long
    ): kotlinx.coroutines.flow.Flow<PollSample>? {
        val transport = obdLinkManager?.transport ?: return null
        val src = ObdLinkLiveSource(transport, pidsOnPage)
        runningLiveSource = src
        return src.startFlow(pollIntervalMs)
    }

    fun startLogging() {
        // Fresh logging run — reset the autosave throttle counter so the
        // first sample's save triggers promptly after the first batch.
        samplesSinceLastAutosave = 0
        startReadingLive(recordToLog = true)
    }

    /**
     * Stop appending to the session log. Polling continues; gauges keep
     * updating. To stop polling entirely, use [stopReadingLive].
     */
    fun stopLogging() {
        if (!_uiState.value.isLogging) return
        _uiState.value = _uiState.value.copy(
            isLogging = false,
            statusMessage = "Log paused — gauges still reading"
        )
    }

    /**
     * Stop polling. Cancels the poll job and clears both reading and logging
     * flags. Gauges freeze at their last value.
     */
    fun stopReadingLive() {
        runJob?.cancel()
        runJob = null
        simulatorIo?.close()
        simulatorIo = null
        simulatorObdLink?.disconnect()
        simulatorObdLink = null
        _uiState.value = _uiState.value.copy(
            isReadingLive = false,
            isLogging = false,
            liveValues = emptyMap(),
            liveValuesMin = emptyMap(),
            liveValuesMax = emptyMap(),
            lastSampleTimestampMs = 0L,
            lastPollWireMs = 0L,
            statusMessage = "Reading stopped"
        )
    }

    fun runProbe() {
        if (_uiState.value.isRunningProbe || _uiState.value.isReadingLive || _uiState.value.isLogging) return
        val s = _uiState.value.settings
        // Probe still drives the K-line ATI..ATV init + BF40 ECU ID query. The
        // CAN and OBDLink paths verify themselves through Read Live Data; a
        // dedicated probe for each path is a follow-up.
        if (!s.simulatorMode && (s.adapter != Adapter.OpenPort || s.protocol != BusProtocol.KLine)) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Test SSM2 Probe runs on OpenPort 2.0 + K-Line. For the other paths, use Read Live Data."
            )
            return
        }
        openSession ?: run {
            _uiState.value = _uiState.value.copy(
                statusMessage = "No OpenPort session — discover and grant USB permission first"
            )
            return
        }
        val client = tactrixClient ?: run {
            _uiState.value = _uiState.value.copy(
                statusMessage = "No adapter client — reconnect the OpenPort"
            )
            return
        }

        // Test SSM2 Probe is a connection-verification action — always re-run
        // the full ATI→ATV init so the result reflects the wire right now, not
        // a cached "channel still open" assumption from a previous press.
        client.channelInitialized = false

        _uiState.value = _uiState.value.copy(
            isRunningProbe = true,
            log = emptyList(),
            lastOutcome = null,
            ssm2DecodeBundle = null,
            ssm2ResponseHex = "",
            attStepDurationMs = null,
            statusMessage = "Running SSM2 ECM probe..."
        )

        runJob = viewModelScope.launch(Dispatchers.IO) {
            val probe = Ssm2EcmProbe(client)
            val result = probe.run()

            val responseHex = result.ssm2DecodeBundle?.response?.rawBytes
                ?.let { TactrixHex.bytesToHex(it) }
                ?: result.ecuReplyBytes?.let { TactrixHex.bytesToHex(it) }
                ?: ""
            val statusLine = when (result.outcome) {
                Ssm2EcmProbe.ProbeOutcome.SUCCESS_ECU_REPLIED ->
                    "Probe complete — ECU replied (${result.ecuReplyBytes?.size ?: 0} bytes)"
                Ssm2EcmProbe.ProbeOutcome.FAIL_INIT_STEP ->
                    "Probe failed at an init step — see log"
                Ssm2EcmProbe.ProbeOutcome.FAIL_NO_ECU_REPLY ->
                    "Probe sent the SSM2 query but no ECU reply was recognized"
                Ssm2EcmProbe.ProbeOutcome.FAIL_TRANSPORT ->
                    "Probe failed at the USB transport layer"
                Ssm2EcmProbe.ProbeOutcome.FAIL_USB_DISCONNECTED ->
                    "USB device disconnected during probe"
            }

            _uiState.value = _uiState.value.copy(
                isRunningProbe = false,
                log = result.log,
                lastOutcome = result.outcome,
                ssm2DecodeBundle = result.ssm2DecodeBundle,
                ssm2ResponseHex = responseHex,
                attStepDurationMs = result.attStepDurationMs,
                statusMessage = statusLine
            )
        }
    }

    fun clearLog() {
        _uiState.value = _uiState.value.copy(
            log = emptyList(),
            lastOutcome = null,
            ssm2DecodeBundle = null,
            ssm2ResponseHex = "",
            attStepDurationMs = null,
            statusMessage = "Log cleared"
        )
    }

    fun clearSessionLog() {
        _uiState.value = _uiState.value.copy(sessionLog = emptyList())
    }

    override fun onCleared() {
        runJob?.cancel()
        clearOpenSession()
        super.onCleared()
    }
}
