package com.protocol.app.protocol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.protocol.app.obdlink.AdapterCommandLibrary
import com.protocol.app.obdlink.CommandSequence
import com.protocol.app.obdlink.LiveSampleSource
import com.protocol.app.obdlink.ObdLinkBtManager
import com.protocol.app.obdlink.ObdLinkKlineSource
import com.protocol.app.obdlink.ObdLinkLiveSource
import com.protocol.app.obdlink.ObdLinkTcpManager
import com.protocol.app.obdlink.ObdLinkUsbManager
import com.protocol.app.kkl.KklKlineManager
import com.protocol.app.kkl.KklKlineSource
import com.protocol.app.openport2.OpenPort2UsbSession
import com.protocol.app.openport2.OpenPort2UsbSessionManager
import com.protocol.app.openport2.OpenPortCanBroadcastSource
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
    // USB-backed OBDLink EX. Sibling of obdLinkManager (BT) / simulatorObdLink
    // (TCP) — same STN/SSM2 stack, reached over an FTDI USB serial. Built from a
    // UsbDeviceConnection the Activity opens after USB permission is granted.
    private var obdLinkUsbManager: ObdLinkUsbManager? = null
    // True while an EX connect coroutine is in flight. Repeated Read-Live taps
    // during the (slow, timeout-bound) connect would otherwise stack concurrent
    // opens on the same FTDI device — the storm seen in the first bench log.
    @Volatile private var obdLinkExConnecting = false
    // Raw K-line over a dumb VAG-KKL cable (FT232RL). No STN / OpenPort firmware
    // in between — the phone is the SSM2 master. Built from a UsbDeviceConnection
    // the Activity opens after USB permission. K-line only (a dumb K-line cable
    // has no CAN path).
    private var kklManager: KklKlineManager? = null
    @Volatile private var kklConnecting = false
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
    private var sessionLogStore: SessionLogStore? = null
    // Saved-sample counter — used to throttle disk autosaves to every
    // AUTOSAVE_EVERY_N_SAMPLES poll cycles so we don't hammer cache
    // storage at 5 Hz on long-running logs.
    private var samplesSinceLastAutosave: Int = 0

    // ── Dev Mode → Raw Command Interface ────────────────────────────────────
    // The dev/diagnostic console ops (init hunt, adapter reset, manual command +
    // sequence, K-line continuous test, CAN monitor) live in their own
    // [DevConsoleController]. This ViewModel still OWNS the adapter managers and
    // the poll job; the controller borrows them through this private bridge, so
    // it never reaches into the rest of the ViewModel. When connection ownership
    // later moves to its own controller, that owner becomes the host.
    private val devHost = object : DevConsoleHost {
        override val scope get() = viewModelScope
        override val state get() = _uiState.value
        override suspend fun takeOverLink() {
            val active = runJob
            runJob = null
            active?.cancel()
            active?.join()
        }
        override fun setConnectionStatus(status: ConnectionStatus, message: String) =
            this@ProtocolViewModel.setConnectionStatus(status, message)
        override fun setStatusMessage(message: String) {
            _uiState.value = _uiState.value.copy(statusMessage = message)
        }
        override fun tactrixClient() = this@ProtocolViewModel.tactrixClient
        override fun kklManager() = this@ProtocolViewModel.kklManager
        override fun obdLinkBtManager() = obdLinkManager
        override fun obdLinkExManager() = obdLinkUsbManager
        override fun ensureObdLinkBtManager(appContext: android.content.Context): ObdLinkBtManager =
            obdLinkManager ?: ObdLinkBtManager(appContext).also { obdLinkManager = it }
        override fun clearObdLinkBtManager() { obdLinkManager = null }
        override fun clearObdLinkExManager() { obdLinkUsbManager = null }
    }

    /** Dev Mode → Raw Command Interface operations (extracted from this VM). */
    internal val devConsole = DevConsoleController(devHost)

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

    /** Pick the active AdapterCommandLibrary init sequence (by id). DEV-ONLY:
     *  consulted only by the Dev page CONNECT button. Live Data ignores this and
     *  always connects with the verified standard init. */
    fun setSelectedInitSequence(id: String?) = updateSettings { it.copy(selectedInitSequenceId = id) }

    /** True when the Settings POLLING MODE selector is set to Stream, so the
     *  OBDLink K-line source STREAMS (A8 01 + monitor) for ECM-only pages instead
     *  of re-asking each cycle. The source applies the tight K-line timing it needs
     *  itself, so this works from the plain verified init. Auto-falls-back to
     *  polling on CAN, the KKL cable, or when a TCM gauge is on the page. */
    private fun streamingRequested(): Boolean =
        _uiState.value.settings.pollingMode == PollingMode.Stream

    /**
     * Init sequence for a connect. Live Data ([fromDev]=false) uses the verified
     * standard init ONLY — never the dev selection — so a live connection can't
     * inherit an experimental init. The Dev page ([fromDev]=true) uses the
     * dev-selected sequence (or null = the manager's built-in default when the
     * picked one doesn't match the protocol).
     */
    private fun protocolLabelOf(protocol: BusProtocol): String = when (protocol) {
        BusProtocol.KLine -> "K-line"
        BusProtocol.CAN -> "CAN"
        BusProtocol.CanBroadcast -> "CAN broadcast"
    }

    private fun connectInitSeq(isKline: Boolean, fromDev: Boolean): CommandSequence? =
        if (fromDev)
            AdapterCommandLibrary.byId(_uiState.value.settings.selectedInitSequenceId)
                ?.takeIf { it.kline == isKline }
        else
            AdapterCommandLibrary.defaultFor(isKline)

    /** If the current (adapter, bus) route can't run the selected polling mode,
     *  fall back to Poll. Keeps the persisted mode honest with the route table
     *  (and the Settings selector greying) when the adapter/protocol changes. */
    private fun AppSettings.withSupportedPollingMode(): AppSettings {
        val route = TransportRoute.of(adapter, protocol) ?: return this
        return if (route.supports(pollingMode)) this else copy(pollingMode = route.defaultMode)
    }

    fun setAdapter(adapter: Adapter?) {
        // Switching adapter / protocol invalidates any cached K-line channel
        // state so the next Read Live re-runs the full ATI..ATV init. If a
        // poll is currently running, stop it first — letting it continue
        // against the old adapter while the UI shows the new selection would
        // be silently wrong.
        val state = _uiState.value
        if (state.isReadingLive || state.isLogging) stopReadingLive()
        tactrixClient?.channelInitialized = false
        updateSettings { it.copy(adapter = adapter).withSupportedPollingMode() }
    }

    fun setProtocol(protocol: BusProtocol?) {
        val state = _uiState.value
        if (state.isReadingLive || state.isLogging) stopReadingLive()
        tactrixClient?.channelInitialized = false
        updateSettings { it.copy(protocol = protocol).withSupportedPollingMode() }
    }

    fun setSsmVariant(variant: SsmVariant?) = updateSettings { it.copy(ssmVariant = variant) }

    fun setPollingMode(mode: PollingMode) = updateSettings { it.copy(pollingMode = mode).withSupportedPollingMode() }

    fun setSimulatorMode(on: Boolean) = updateSettings { it.copy(simulatorMode = on) }

    fun setSimulatorPort(port: Int) = updateSettings {
        it.copy(simulatorPort = port.coerceIn(AppSettings.SIMULATOR_PORT_MIN, AppSettings.SIMULATOR_PORT_MAX))
    }

    /** Persist the SAF folder URI the Lock-and-Tap auto-save writes CSVs into. */
    fun setCsvFolderUri(uri: String?) = updateSettings { it.copy(csvFolderUri = uri) }

    /** Base name for the auto-saved RAW BYTES CSV (enumerated on write). */
    fun setRawLogName(name: String) = updateSettings { it.copy(rawLogName = name) }

    /** Base name for the auto-saved session-log CSV (enumerated on write). */
    fun setSessionLogName(name: String) = updateSettings { it.copy(sessionLogName = name) }

    /** Persist the Live Data session-log card height (dp) after a resize drag. */
    fun setSessionLogHeightDp(dp: Float) = updateSettings {
        it.copy(
            sessionLogHeightDp = dp.coerceIn(
                AppSettings.SESSION_LOG_HEIGHT_MIN,
                AppSettings.SESSION_LOG_HEIGHT_MAX
            )
        )
    }


    /**
     * Routed from the BT-permission denial path. Drops the OBDLink selection
     * so the Settings UI reflects reality and nothing else tries to connect.
     */
    fun clearObdLinkAdapter() {
        if (_uiState.value.settings.adapter == Adapter.OBDLink) setAdapter(null)
        disconnectObdLink()
    }

    /**
     * Connect the *paired* OBDLink over Bluetooth, picking the right channel
     * mode based on the user's selected protocol. The Activity must have
     * granted BLUETOOTH_CONNECT first. No-op unless OBDLink is the selected
     * adapter (defense in depth on stale callbacks).
     */
    fun connectObdLink(appContext: android.content.Context, fromDev: Boolean = false) {
        val s = _uiState.value.settings
        if (s.adapter != Adapter.OBDLink) return
        val protocol = s.protocol ?: run {
            setConnectionStatus(
                ConnectionStatus.Error("PROTOCOL not picked"),
                "Pick K-Line or CAN in Settings before connecting OBDLink"
            )
            return
        }
        val protocolLabel = protocolLabelOf(protocol)
        setConnectionStatus(
            ConnectionStatus.PermissionRequired("OBDLink"),
            "Connecting OBDLink ($protocolLabel)..."
        )
        viewModelScope.launch(Dispatchers.IO) {
            val mgr = obdLinkManager
                ?: ObdLinkBtManager(appContext).also { obdLinkManager = it }
            // Live Data uses the verified standard init; the Dev CONNECT button
            // (fromDev) uses the dev-selected sequence. See [connectInitSeq].
            val isKline = protocol == BusProtocol.KLine
            val seq = connectInitSeq(isKline, fromDev)
            val r = when (protocol) {
                // CanBroadcast shares the CAN channel init — the broadcast source
                // flips it into raw STMA monitor at flow start.
                BusProtocol.CAN, BusProtocol.CanBroadcast -> mgr.connect(seq)
                BusProtocol.KLine -> mgr.connectKline(seq)
            }
            when (r) {
                is ObdLinkBtManager.ConnectResult.Connected -> {
                    setConnectionStatus(
                        ConnectionStatus.Connected(r.deviceLabel),
                        "Connected to ${r.deviceLabel} ($protocolLabel)"
                    )
                    setAdapterPresent(true)
                }
                is ObdLinkBtManager.ConnectResult.Failure -> {
                    obdLinkManager?.disconnect()
                    obdLinkManager = null
                    setConnectionStatus(ConnectionStatus.Error(r.reason), r.reason)
                    setAdapterPresent(false)
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
                adapterPresent = false,
                statusMessage = "OBDLink disconnected"
            )
        }
    }

    /**
     * Connect the OBDLink EX over USB. The Activity opens the [connection] for
     * the FTDI device after USB permission is granted, then hands it here. We
     * build an [ObdLinkUsbManager] (which takes ownership of the connection) and
     * run the same STN init the BT/TCP managers use, picking K-line vs CAN from
     * the user's selected protocol. No-op unless OBDLink EX is the live adapter
     * (defense against stale permission callbacks).
     */
    fun connectObdLinkEx(
        connection: android.hardware.usb.UsbDeviceConnection,
        device: android.hardware.usb.UsbDevice,
        fromDev: Boolean = false
    ) {
        val s = _uiState.value.settings
        if (s.adapter != Adapter.OBDLinkEx) {
            try { connection.close() } catch (_: Exception) {}
            return
        }
        if (obdLinkExConnecting) {
            // A connect is already running — drop this duplicate so repeated taps
            // can't stack concurrent opens (and steal the interface) on the EX.
            try { connection.close() } catch (_: Exception) {}
            return
        }
        val protocol = s.protocol ?: run {
            try { connection.close() } catch (_: Exception) {}
            setConnectionStatus(
                ConnectionStatus.Error("PROTOCOL not picked"),
                "Pick K-Line or CAN in Settings before connecting OBDLink EX"
            )
            return
        }
        val protocolLabel = protocolLabelOf(protocol)
        obdLinkExConnecting = true
        setConnectionStatus(
            ConnectionStatus.PermissionRequired("OBDLink EX"),
            "Connecting OBDLink EX ($protocolLabel)..."
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                obdLinkUsbManager?.disconnect()
                val mgr = ObdLinkUsbManager(connection, device)
                obdLinkUsbManager = mgr
                // Live Data uses the verified standard init; the Dev CONNECT
                // button (fromDev) uses the dev-selected sequence. See [connectInitSeq].
                val isKline = protocol == BusProtocol.KLine
                val seq = connectInitSeq(isKline, fromDev)
                val r = when (protocol) {
                    // CanBroadcast shares the CAN channel init — the broadcast
                    // source flips it into raw STMA monitor at flow start.
                    BusProtocol.CAN, BusProtocol.CanBroadcast -> mgr.connect(seq)
                    BusProtocol.KLine -> mgr.connectKline(seq)
                }
                when (r) {
                    is ObdLinkUsbManager.ConnectResult.Connected -> {
                        setConnectionStatus(
                            ConnectionStatus.Connected(r.deviceLabel),
                            "Connected to ${r.deviceLabel} ($protocolLabel)"
                        )
                        setAdapterPresent(true)
                    }
                    is ObdLinkUsbManager.ConnectResult.Failure -> {
                        obdLinkUsbManager?.disconnect()
                        obdLinkUsbManager = null
                        setConnectionStatus(ConnectionStatus.Error(r.reason), r.reason)
                        setAdapterPresent(false)
                    }
                }
            } finally {
                obdLinkExConnecting = false
            }
        }
    }

    /** Tear down the OBDLink EX USB link. Safe whether or not it's connected;
     *  leaves a live OpenPort USB session (if any) untouched. */
    fun disconnectObdLinkEx() {
        val wasEx = runningLiveSource != null && _uiState.value.settings.adapter == Adapter.OBDLinkEx
        if (wasEx) {
            runJob?.cancel()
            runJob = null
            runningLiveSource = null
        }
        obdLinkUsbManager?.disconnect()
        obdLinkUsbManager = null
        if (openSession == null) {
            _uiState.value = _uiState.value.copy(
                isReadingLive = if (wasEx) false else _uiState.value.isReadingLive,
                isLogging = if (wasEx) false else _uiState.value.isLogging,
                liveValues = if (wasEx) emptyMap() else _uiState.value.liveValues,
                connectionStatus = ConnectionStatus.NoDevice,
                adapterPresent = false,
                statusMessage = "OBDLink EX disconnected"
            )
        }
    }

    /**
     * Connect the FT232RL (VAG-KKL raw K-line) over USB. The Activity opens the
     * [connection] for the FTDI device after USB permission, then hands it here.
     * We build a [KklKlineManager] (which takes ownership of the connection),
     * open the serial at 4800, and probe the ECU over the bare K-line. K-line
     * only — CAN over a dumb K-line cable is impossible, so a CAN selection is
     * rejected with a clear message. No-op unless FT232RL is the live adapter.
     */
    fun connectFt232rl(
        connection: android.hardware.usb.UsbDeviceConnection,
        device: android.hardware.usb.UsbDevice
    ) {
        val s = _uiState.value.settings
        if (s.adapter != Adapter.Ft232rl) {
            try { connection.close() } catch (_: Exception) {}
            return
        }
        if (kklConnecting) {
            try { connection.close() } catch (_: Exception) {}
            return
        }
        if (s.protocol == BusProtocol.CAN) {
            try { connection.close() } catch (_: Exception) {}
            setConnectionStatus(
                ConnectionStatus.Error("KKL is K-line only"),
                "FT232RL is a raw K-line cable — pick K-Line (CAN needs an OBDLink / OpenPort)"
            )
            return
        }
        kklConnecting = true
        setConnectionStatus(
            ConnectionStatus.PermissionRequired("FT232RL"),
            "Connecting FT232RL (K-line)…"
        )
        viewModelScope.launch(Dispatchers.IO) {
            try {
                kklManager?.disconnect()
                val mgr = KklKlineManager(connection, device)
                kklManager = mgr
                when (val r = mgr.connect()) {
                    is KklKlineManager.ConnectResult.Connected -> {
                        setConnectionStatus(
                            ConnectionStatus.Connected(r.deviceLabel),
                            "Connected to ${r.deviceLabel}"
                        )
                        setAdapterPresent(true)
                    }
                    is KklKlineManager.ConnectResult.Failure -> {
                        kklManager?.disconnect()
                        kklManager = null
                        setConnectionStatus(ConnectionStatus.Error(r.reason), r.reason)
                        setAdapterPresent(false)
                    }
                }
            } finally {
                kklConnecting = false
            }
        }
    }

    /** Tear down the FT232RL KKL link. Safe whether or not it's connected. */
    fun disconnectFt232rl() {
        val wasKkl = runningLiveSource != null && _uiState.value.settings.adapter == Adapter.Ft232rl
        if (wasKkl) {
            runJob?.cancel()
            runJob = null
            runningLiveSource = null
        }
        kklManager?.disconnect()
        kklManager = null
        if (openSession == null) {
            _uiState.value = _uiState.value.copy(
                isReadingLive = if (wasKkl) false else _uiState.value.isReadingLive,
                isLogging = if (wasKkl) false else _uiState.value.isLogging,
                liveValues = if (wasKkl) emptyMap() else _uiState.value.liveValues,
                connectionStatus = ConnectionStatus.NoDevice,
                adapterPresent = false,
                statusMessage = "FT232RL disconnected"
            )
        }
    }

    fun attachSessionLogStore(store: SessionLogStore) {
        sessionLogStore = store
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

    /**
     * Replace the whole Live Data layout with [PidPresets] entry [index] —
     * clears the current gauges and lays out that preset's PIDs in order.
     * Goes through [updateLayout] so a running poll picks up the new PID set
     * mid-flight (gauges swap without tearing down the connection).
     */
    fun applyPreset(index: Int) {
        val preset = PidPresets.PRESETS.getOrNull(index) ?: return
        updateLayout { preset.pidIds.fold(GaugeLayout()) { acc, id -> acc.withAdded(id) } }
        // Remember which preset is active so the hamburger picker can mark it
        // and the choice survives a restart.
        updateSettings { it.copy(selectedPresetIndex = index) }
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

    /** Physical adapter presence — drives the status stripe (present = white,
     *  absent = invisible). USB presence is set by the Activity on attach /
     *  detach / launch; OBDLink presence is set here on connect / disconnect. */
    fun setAdapterPresent(present: Boolean) {
        _uiState.value = _uiState.value.copy(adapterPresent = present)
    }

    fun setOpenSession(session: OpenPort2UsbSession, deviceLabel: String) {
        openSession = session
        tactrixClient = TactrixClient(TactrixBulkIo(session))
        // Connecting only updates the status (drives the presence stripes) —
        // it never auto-starts reading/logging/probing. The user always taps to
        // begin, so plugging the adapter in can't leave the UI in a half-started
        // "highlighted but idle" state.
        _uiState.value = _uiState.value.copy(
            connectionStatus = ConnectionStatus.Connected(deviceLabel),
            statusMessage = "Connected to $deviceLabel"
        )
    }

    fun isConnected(): Boolean =
        _uiState.value.connectionStatus is ConnectionStatus.Connected

    fun clearOpenSession() {
        runJob?.cancel()
        runJob = null
        val wasReading = _uiState.value.isReadingLive
        val wasLogging = _uiState.value.isLogging
        val session = openSession
        openSession = null
        tactrixClient = null
        if (session != null) {
            sessionManager?.closeSession(session)
        }
        // Drop liveValues so gauges revert to "--" instead of showing the last
        // value they had before the adapter vanished.
        _uiState.value = _uiState.value.copy(
            isReadingLive = false,
            isLogging = false,
            liveValues = emptyMap(),
            liveValuesMin = emptyMap(),
            liveValuesMax = emptyMap(),
            lastSampleTimestampMs = 0L,
            lastPollWireMs = 0L,
            statusMessage = if (wasReading || wasLogging)
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
        // Resolve the single transport route for this (adapter, bus). A null
        // route means a valid pair of selectors that isn't a real transport
        // (e.g. FT232RL + CAN — the KKL cable is K-line only).
        val route = TransportRoute.of(adapter, protocol)
        if (route == null) {
            _uiState.value = state.copy(
                statusMessage = "${adapter.name} + ${protocol.name} isn't a supported combination"
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
                Adapter.OBDLinkEx -> if (obdLinkUsbManager?.isConnected() != true) {
                    _uiState.value = state.copy(
                        statusMessage = "OBDLink EX not connected — Settings → tap OBDLink EX to grant USB permission"
                    )
                    return
                }
                Adapter.Ft232rl -> if (kklManager?.isConnected() != true) {
                    _uiState.value = state.copy(
                        statusMessage = "FT232RL not connected — Settings → tap FT232RL (KKL) to grant USB permission"
                    )
                    return
                }
            }
        }

        val simHostPort = "127.0.0.1:${state.settings.simulatorPort}"
        val initialStatus = if (useSimulator) {
            "Simulator: ${route.label} @ $simHostPort"
        } else when (route) {
            TransportRoute.OpenPortKline ->
                if (tactrixClient?.channelInitialized == true) "Reusing K-line channel..." else "Initializing OpenPort K-line..."
            TransportRoute.OpenPortCan -> "Initializing OpenPort CAN @ 500 kbps..."
            TransportRoute.OpenPortCanBroadcast -> "Monitoring OpenPort CAN broadcast @ 500k..."
            TransportRoute.ObdLinkKline -> "Polling OBDLink K-line @ 4800..."
            TransportRoute.ObdLinkCan -> "Polling OBDLink CAN @ 500k..."
            TransportRoute.ObdLinkCanBroadcast -> "Monitoring OBDLink CAN broadcast @ 500k..."
            TransportRoute.ObdLinkExKline -> "Polling OBDLink EX K-line @ 4800..."
            TransportRoute.ObdLinkExCan -> "Polling OBDLink EX CAN @ 500k..."
            TransportRoute.ObdLinkExCanBroadcast -> "Monitoring OBDLink EX CAN broadcast @ 500k..."
            TransportRoute.Ft232rlKline -> "Polling FT232RL raw K-line @ 4800..."
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
                if (useSimulator) when (route) {
                    // VIPER emulates OpenPort and OBDLink only — the EX/KKL routes
                    // have no simulator path (null → "Channel init failed" below).
                    TransportRoute.OpenPortKline -> startSimulatorOpenPortKlineFlow(simPort, pidsOnPage, pollIntervalMs)
                    TransportRoute.OpenPortCan -> startSimulatorOpenPortCanFlow(simPort, pidsOnPage, pollIntervalMs)
                    TransportRoute.ObdLinkKline -> startSimulatorObdLinkKlineFlow(simPort, pidsOnPage, pollIntervalMs)
                    TransportRoute.ObdLinkCan -> startSimulatorObdLinkCanFlow(simPort, pidsOnPage, pollIntervalMs)
                    else -> null
                } else when (route) {
                    TransportRoute.OpenPortKline -> startOpenPortKlineFlow(pidsOnPage, pollIntervalMs)
                    TransportRoute.OpenPortCan -> startOpenPortCanFlow(pidsOnPage, pollIntervalMs)
                    TransportRoute.OpenPortCanBroadcast -> startOpenPortCanBroadcastFlow()
                    TransportRoute.ObdLinkKline -> startObdLinkKlineFlow(pidsOnPage, pollIntervalMs)
                    TransportRoute.ObdLinkCan -> startObdLinkCanFlow(pidsOnPage, pollIntervalMs)
                    TransportRoute.ObdLinkCanBroadcast -> startObdLinkCanBroadcastFlow(obdLinkManager?.transport)
                    TransportRoute.ObdLinkExKline -> startObdLinkExKlineFlow(pidsOnPage, pollIntervalMs)
                    TransportRoute.ObdLinkExCan -> startObdLinkExCanFlow(pidsOnPage, pollIntervalMs)
                    TransportRoute.ObdLinkExCanBroadcast -> startObdLinkCanBroadcastFlow(obdLinkUsbManager?.transport)
                    TransportRoute.Ft232rlKline -> startFt232rlKlineFlow(pidsOnPage, pollIntervalMs)
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
            } catch (_: com.protocol.app.openport2.NoEcuResponseException) {
                // Adapter connected + channel open, but the ECU never answered.
                // Stop instead of hammering the bus forever; the finally block
                // clears the gauges and the reading/logging flags.
                _uiState.value = _uiState.value.copy(
                    statusMessage = "No ECU response — adapter connected, ECU not answering",
                    noEcuEventId = _uiState.value.noEcuEventId + 1
                )
                return@launch
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
        // Continuous SSM2 mode (A8 flag 0x01): one request, the ECU streams
        // replies, dropping the per-cycle request transmit (~70% of K-line wire
        // time). Driven by POLLING MODE → Stream (the OpenPortKline route's
        // supportsStream), ECM-only pages only (K-line can't stream two modules);
        // auto-falls-back to single-response if the ECU ignores it. Verify in the
        // BYTES log: look for `A8 01` and multiple E8 replies per request.
        val ecmOnly = pidsOnPage.isNotEmpty() &&
            pidsOnPage.none { it.category == com.protocol.app.openport2.Ssm2PidCategory.TCM }
        return if (streamingRequested() && ecmOnly) {
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
        val src = ObdLinkKlineSource(transport, pidsOnPage, streamingRequested())
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

    /** OBDLink EX (USB) + K-line: identical to the BT K-line path, transport from the USB manager. */
    private fun startObdLinkExKlineFlow(
        pidsOnPage: List<Ssm2Pid>,
        pollIntervalMs: Long
    ): kotlinx.coroutines.flow.Flow<PollSample>? {
        val transport = obdLinkUsbManager?.transport ?: return null
        val src = ObdLinkKlineSource(transport, pidsOnPage, streamingRequested())
        runningLiveSource = src
        return src.startFlow(pollIntervalMs)
    }

    /** FT232RL (USB) + K-line: raw SSM2 over the dumb KKL cable. Same A8 batching
     *  and ECM/TCM split as the OBDLink K-line path, but the wire I/O is our own
     *  software half-duplex transact (no STN). */
    private fun startFt232rlKlineFlow(
        pidsOnPage: List<Ssm2Pid>,
        pollIntervalMs: Long
    ): kotlinx.coroutines.flow.Flow<PollSample>? {
        val mgr = kklManager ?: return null
        val src = KklKlineSource(mgr, pidsOnPage, continuous = streamingRequested())
        runningLiveSource = src
        return src.startFlow(pollIntervalMs)
    }

    /** OBDLink EX (USB) + CAN: identical to the BT CAN path, transport from the USB manager. */
    private fun startObdLinkExCanFlow(
        pidsOnPage: List<Ssm2Pid>,
        pollIntervalMs: Long
    ): kotlinx.coroutines.flow.Flow<PollSample>? {
        val transport = obdLinkUsbManager?.transport ?: return null
        val src = ObdLinkLiveSource(transport, pidsOnPage)
        runningLiveSource = src
        return src.startFlow(pollIntervalMs)
    }

    /** OpenPort 2.0 + CAN broadcast (Monitor): listen-only accept-all CAN; never
     *  transmits. Frames stream into the RAW BYTES log; gauges stay empty (no
     *  SSM2 mapping for broadcast). */
    private fun startOpenPortCanBroadcastFlow(): kotlinx.coroutines.flow.Flow<PollSample>? {
        val session = openSession ?: return null
        val src = OpenPortCanBroadcastSource(TactrixBulkIo(session))
        if (!src.initChannel()) return null
        runningLiveSource = src
        return src.startFlow(0L)
    }

    /** OBDLink (MX+ BT or EX USB) + CAN broadcast (Monitor): STN STMA monitor-all,
     *  listen-only. Same source for both — they share [ObdLinkBtTransport]. */
    private fun startObdLinkCanBroadcastFlow(
        transport: com.protocol.app.obdlink.ObdLinkBtTransport?
    ): kotlinx.coroutines.flow.Flow<PollSample>? {
        val t = transport ?: return null
        val src = com.protocol.app.obdlink.ObdLinkCanBroadcastSource(t)
        runningLiveSource = src
        return src.startFlow(0L)
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

    /**
     * Read-only DTC read for the Diagnostics page. Reuses the same A8
     * read-address transport the live-data path uses (no transport changes),
     * pointed at the ECU's diagnostic status bytes, then decodes the set bits
     * against [com.protocol.app.openport2.DtcCatalog].
     *
     * Stops any running live poll first and waits for it to finish — the DTC
     * read and a poll loop share one socket and must not interleave (same rule
     * the manual console follows). ECM only; clear/reset are intentionally NOT
     * here (write commands live in the THRESHOLD project).
     */
    fun readDtcs() {
        val state = _uiState.value
        if (state.isReadingDtc) return
        val adapter = state.settings.adapter
        val protocol = state.settings.protocol
        if (adapter == null || protocol == null) {
            _uiState.value = state.copy(dtcStatus = "Pick ADAPTER and PROTOCOL in Settings first")
            return
        }
        _uiState.value = state.copy(
            isReadingDtc = true,
            dtcStatus = "Reading codes…",
            dtcCurrent = emptyList(),
            dtcStored = emptyList()
        )
        viewModelScope.launch(Dispatchers.IO) {
            // Take over the shared link: stop any running poll/log loop and wait
            // for it to fully finish so the DTC read can't collide on the socket.
            val active = runJob
            runJob = null
            active?.cancel()
            active?.join()
            try {
                val result = when {
                    adapter == Adapter.OBDLink && protocol == BusProtocol.KLine -> {
                        val t = obdLinkManager?.transport
                        if (t == null) { dtcFail("OBDLink not connected — pair it in Settings first"); return@launch }
                        com.protocol.app.openport2.Ssm2DtcRead.readObdLinkKline(t)
                    }
                    adapter == Adapter.OBDLink && protocol == BusProtocol.CAN -> {
                        val t = obdLinkManager?.transport
                        if (t == null) { dtcFail("OBDLink not connected — pair it in Settings first"); return@launch }
                        com.protocol.app.openport2.Ssm2DtcRead.readObdLinkCan(t)
                    }
                    adapter == Adapter.OBDLinkEx && protocol == BusProtocol.KLine -> {
                        val t = obdLinkUsbManager?.transport
                        if (t == null) { dtcFail("OBDLink EX not connected — tap it in Settings first"); return@launch }
                        com.protocol.app.openport2.Ssm2DtcRead.readObdLinkKline(t)
                    }
                    adapter == Adapter.OBDLinkEx && protocol == BusProtocol.CAN -> {
                        val t = obdLinkUsbManager?.transport
                        if (t == null) { dtcFail("OBDLink EX not connected — tap it in Settings first"); return@launch }
                        com.protocol.app.openport2.Ssm2DtcRead.readObdLinkCan(t)
                    }
                    adapter == Adapter.OpenPort && protocol == BusProtocol.KLine -> {
                        val client = tactrixClient
                        if (client == null) { dtcFail("OpenPort not connected — tap OpenPort 2.0 in Settings"); return@launch }
                        if (!client.channelInitialized) {
                            client.drainResponseBuffer()
                            client.resetRequestIdCounter(startFrom = 2)
                            if (!Ssm2EcmProbe(client).initializeChannel(mutableListOf())) {
                                dtcFail("OpenPort K-line init failed — check the OBD connection"); return@launch
                            }
                        }
                        com.protocol.app.openport2.Ssm2DtcRead.readOpenPortKline(client)
                    }
                    adapter == Adapter.OpenPort && protocol == BusProtocol.CAN -> {
                        val session = openSession
                        if (session == null) { dtcFail("OpenPort not connected — tap OpenPort 2.0 in Settings"); return@launch }
                        val src = OpenPortCanLiveSource(TactrixBulkIo(session), emptyList())
                        com.protocol.app.openport2.Ssm2DtcRead.readOpenPortCan(src)
                    }
                    else -> {
                        dtcFail("Unsupported adapter/protocol combination for DTC read."); return@launch
                    }
                }
                applyDtcResult(result)
            } catch (e: Exception) {
                dtcFail("DTC read failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private fun dtcFail(message: String) {
        _uiState.value = _uiState.value.copy(isReadingDtc = false, dtcStatus = message)
    }

    private fun applyDtcResult(r: com.protocol.app.openport2.Ssm2DtcRead.Result) {
        val status = if (!r.reachedEcu) {
            "No response from the ECU — check the connection and that the key is on."
        } else {
            val cur = if (r.current.isEmpty()) "No current codes" else "${r.current.size} current"
            val sto = if (r.stored.isEmpty()) "no stored codes" else "${r.stored.size} stored"
            val missed = (r.currentChunks - r.currentOkChunks) + (r.storedChunks - r.storedOkChunks)
            val partial = if (missed > 0) "  (partial — $missed block(s) didn't answer)" else ""
            "$cur · $sto$partial"
        }
        _uiState.value = _uiState.value.copy(
            isReadingDtc = false,
            dtcStatus = status,
            dtcCurrent = r.current.map { "${it.code}  ${it.description}" },
            dtcStored = r.stored.map { "${it.code}  ${it.description}" }
        )
    }

    fun clearSessionLog() {
        _uiState.value = _uiState.value.copy(sessionLog = emptyList())
    }

    override fun onCleared() {
        runJob?.cancel()
        clearOpenSession()
        // Release the FTDI interface claim so the EX isn't left wedged for the
        // next process (a leaked USB claim is harder to recover than a socket).
        obdLinkUsbManager?.disconnect()
        obdLinkUsbManager = null
        kklManager?.disconnect()
        kklManager = null
        // Close the Bluetooth SPP session too — a leaked socket leaves the MX+
        // deaf to the next connect until it's power-cycled.
        obdLinkManager?.disconnect()
        obdLinkManager = null
        super.onCleared()
    }
}
