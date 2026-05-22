package com.protocol.app.protocol

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.protocol.app.openport2.OpenPort2SessionResult
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ProtocolViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ProtocolUiState())
    val uiState: StateFlow<ProtocolUiState> = _uiState.asStateFlow()

    private var sessionManager: OpenPort2UsbSessionManager? = null
    private var openSession: OpenPort2UsbSession? = null
    private var runJob: Job? = null
    private var tappedParamClearJob: Job? = null

    fun attachSessionManager(manager: OpenPort2UsbSessionManager) {
        sessionManager = manager
    }

    fun setConnectionStatus(status: ConnectionStatus, message: String = "") {
        _uiState.value = _uiState.value.copy(
            connectionStatus = status,
            statusMessage = message
        )
    }

    fun setOpenSession(session: OpenPort2UsbSession, deviceLabel: String) {
        openSession = session
        _uiState.value = _uiState.value.copy(
            connectionStatus = ConnectionStatus.Connected(deviceLabel),
            statusMessage = "Connected to $deviceLabel"
        )
    }

    fun clearOpenSession() {
        runJob?.cancel()
        runJob = null
        val wasRunning = _uiState.value.isRunningProbe
        val wasReading = _uiState.value.isReadingLive
        val wasLogging = _uiState.value.isLogging
        val session = openSession
        openSession = null
        if (session != null) {
            sessionManager?.closeSession(session)
        }
        if (wasRunning || wasReading || wasLogging) {
            _uiState.value = _uiState.value.copy(
                isRunningProbe = false,
                isReadingLive = false,
                isLogging = false,
                lastOutcome = if (wasRunning) Ssm2EcmProbe.ProbeOutcome.FAIL_USB_DISCONNECTED else _uiState.value.lastOutcome,
                statusMessage = "USB device detached — run aborted"
            )
        }
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
        val session = openSession ?: run {
            _uiState.value = state.copy(statusMessage = "No OpenPort session — discover and grant USB permission first")
            return
        }

        _uiState.value = state.copy(
            isReadingLive = true,
            isLogging = recordToLog,
            liveValues = emptyMap(),
            lastSampleTimestampMs = 0L,
            sessionLog = if (recordToLog) emptyList() else state.sessionLog,
            statusMessage = "Initializing channel..."
        )

        runJob = viewModelScope.launch(Dispatchers.IO) {
            val io = TactrixBulkIo(session)
            val client = TactrixClient(io)
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

            val poller = Ssm2Poller(client, Ssm2Pids.DEFAULT_DEMO_PIDS)
            try {
                poller.startFlow(200L).collect { sample ->
                    val current = _uiState.value
                    val nextSessionLog = if (current.isLogging) {
                        val trimmed = if (current.sessionLog.size >= 1000)
                            current.sessionLog.drop(1)
                        else
                            current.sessionLog
                        trimmed + sample
                    } else {
                        current.sessionLog
                    }
                    _uiState.value = current.copy(
                        liveValues = sample.values,
                        lastSampleTimestampMs = sample.timestampMs,
                        sessionLog = nextSessionLog
                    )
                }
            } catch (_: UsbDisconnectedException) {
                _uiState.value = _uiState.value.copy(
                    isReadingLive = false,
                    isLogging = false,
                    statusMessage = "USB device disconnected"
                )
                return@launch
            } catch (_: Exception) {
                // Coroutine cancelled — fall through to finally
            } finally {
                _uiState.value = _uiState.value.copy(
                    isReadingLive = false,
                    isLogging = false
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
            statusMessage = "Reading stopped"
        )
    }

    fun runProbe() {
        if (_uiState.value.isRunningProbe || _uiState.value.isReadingLive || _uiState.value.isLogging) return
        val session = openSession ?: run {
            _uiState.value = _uiState.value.copy(
                statusMessage = "No OpenPort session — discover and grant USB permission first"
            )
            return
        }

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
            val io = TactrixBulkIo(session)
            val client = TactrixClient(io)
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

    /**
     * Toggle whether [pidId] contributes to the session log + copy/CSV
     * output. The poller always reads all configured PIDs; this flag only
     * filters which columns appear in the recorded/exported log. Gauges keep
     * updating regardless.
     */
    fun togglePidSelected(pidId: String) {
        val pid = Ssm2Pids.DEFAULT_DEMO_PIDS.find { it.id == pidId }
        val current = _uiState.value.selectedPidIds
        val next = if (pidId in current) current - pidId else current + pidId
        _uiState.value = _uiState.value.copy(
            selectedPidIds = next,
            tappedParamLongName = pid?.longName
        )
        tappedParamClearJob?.cancel()
        tappedParamClearJob = viewModelScope.launch {
            delay(5000)
            _uiState.value = _uiState.value.copy(tappedParamLongName = null)
        }
    }

    override fun onCleared() {
        runJob?.cancel()
        clearOpenSession()
        super.onCleared()
    }
}
