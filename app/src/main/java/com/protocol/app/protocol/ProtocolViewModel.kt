package com.protocol.app.protocol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.protocol.app.openport2.OpenPort2UsbSession
import com.protocol.app.openport2.OpenPort2UsbSessionManager
import com.protocol.app.openport2.Ssm2EcmProbe
import com.protocol.app.openport2.Ssm2Pids
import com.protocol.app.openport2.Ssm2Poller
import com.protocol.app.openport2.TactrixBulkIo
import com.protocol.app.openport2.TactrixClient
import com.protocol.app.openport2.TactrixCommandLog
import com.protocol.app.openport2.TactrixHex
import com.protocol.app.openport2.UsbDisconnectedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val DEFAULT_STARTER_PID_IDS = listOf("rpm", "coolant", "battery", "oil", "iat")

class ProtocolViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProtocolUiState())
    val uiState: StateFlow<ProtocolUiState> = _uiState.asStateFlow()

    private var sessionManager: OpenPort2UsbSessionManager? = null
    private var openSession: OpenPort2UsbSession? = null
    private var tactrixClient: TactrixClient? = null
    private var runJob: Job? = null
    private var layoutStore: GaugeLayoutStore? = null
    private var backgroundStore: BackgroundStore? = null
    private var settingsStore: SettingsStore? = null
    private var garageStore: GarageStore? = null

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

    fun setSplitScreenMode(on: Boolean) = updateSettings { it.copy(splitScreenMode = on) }

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
        _uiState.value = _uiState.value.copy(gaugeLayout = next)
        layoutStore?.save(next)
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
            lastSampleTimestampMs = 0L,
            lastPollIntervalMs = 0L,
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
        openSession ?: run {
            _uiState.value = state.copy(statusMessage = "No OpenPort session — discover and grant USB permission first")
            return
        }
        val client = tactrixClient ?: run {
            _uiState.value = state.copy(statusMessage = "No adapter client — reconnect the OpenPort")
            return
        }

        // Only poll PIDs the user has placed on the Live Data page —
        // saves K-line bandwidth and keeps the log columns aligned with
        // what's visible. Refuse to start with an empty layout instead
        // of running an empty A8 query.
        val pidsOnPage = Ssm2Pids.DEFAULT_DEMO_PIDS.filter { it.id in state.gaugeLayout.pidIds }
        if (pidsOnPage.isEmpty()) {
            _uiState.value = state.copy(
                statusMessage = "Add gauges from the Parameters menu before reading live data."
            )
            return
        }

        _uiState.value = state.copy(
            isReadingLive = true,
            isLogging = recordToLog,
            liveValues = emptyMap(),
            lastSampleTimestampMs = 0L,
            sessionLog = if (recordToLog) emptyList() else state.sessionLog,
            statusMessage = if (client.channelInitialized) "Reusing channel..." else "Initializing channel..."
        )

        val pollIntervalMs = state.settings.pollIntervalMs.toLong()

        runJob = viewModelScope.launch(Dispatchers.IO) {
            client.drainResponseBuffer()
            client.resetRequestIdCounter(startFrom = 2)

            val probe = Ssm2EcmProbe(client)
            val initLog = mutableListOf<TactrixCommandLog>()
            val ok = probe.initializeChannel(initLog)

            if (!ok) {
                _uiState.value = _uiState.value.copy(
                    isReadingLive = false,
                    isLogging = false,
                    statusMessage = "Channel init failed — check connection and retry"
                )
                return@launch
            }

            _uiState.value = _uiState.value.copy(
                statusMessage = if (_uiState.value.isLogging)
                    "Logging live data..."
                else
                    "Reading live data..."
            )

            val poller = Ssm2Poller(client, pidsOnPage)
            try {
                poller.startFlow(pollIntervalMs).collect { sample ->
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
                    // Live poll-rate readout — delta between this sample and
                    // the previous one. First sample emits 0 (no prior point
                    // of reference), subsequent samples carry the actual
                    // measured cadence.
                    val deltaMs = if (current.lastSampleTimestampMs > 0L)
                        sample.timestampMs - current.lastSampleTimestampMs
                    else 0L
                    _uiState.value = current.copy(
                        liveValues = sample.values,
                        lastSampleTimestampMs = sample.timestampMs,
                        lastPollIntervalMs = deltaMs,
                        sessionLog = nextSessionLog
                    )
                }
            } catch (_: UsbDisconnectedException) {
                _uiState.value = _uiState.value.copy(
                    isReadingLive = false,
                    isLogging = false,
                    liveValues = emptyMap(),
                    lastSampleTimestampMs = 0L,
                    lastPollIntervalMs = 0L,
                    statusMessage = "USB device disconnected"
                )
                return@launch
            } catch (_: Exception) {
                // Coroutine cancelled — fall through to finally
            } finally {
                // Drop the last-known values so gauges revert to "--" when
                // polling stops for any reason. Showing stale numbers after
                // a stop is misleading.
                _uiState.value = _uiState.value.copy(
                    isReadingLive = false,
                    isLogging = false,
                    liveValues = emptyMap(),
                    lastSampleTimestampMs = 0L,
                    lastPollIntervalMs = 0L
                )
            }
        }
    }

    fun startLogging() = startReadingLive(recordToLog = true)

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
        _uiState.value = _uiState.value.copy(
            isReadingLive = false,
            isLogging = false,
            liveValues = emptyMap(),
            lastSampleTimestampMs = 0L,
            lastPollIntervalMs = 0L,
            statusMessage = "Reading stopped"
        )
    }

    fun runProbe() {
        if (_uiState.value.isRunningProbe || _uiState.value.isReadingLive || _uiState.value.isLogging) return
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
