package com.protocol.app.flash

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.protocol.app.flash.engine.CanFlashSession
import com.protocol.app.flash.engine.FlashBulkIo
import com.protocol.app.flash.engine.FlashIdentify
import com.protocol.app.flash.engine.FlashUsbException
import com.protocol.app.flash.engine.FlashUsbResult
import com.protocol.app.flash.engine.FlashUsbSessionManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Phase 0 orchestrator for the flash silo (Option B: no service / wake lock yet).
 *
 * Owns a short-lived flow: find + open the OpenPort via the silo's own USB
 * manager, open the CAN channel, run the read-only [FlashIdentify] probes, then
 * close the session immediately. Separately polls [FlashSafety] once a second
 * for the live device-health strip.
 *
 * Read-only by construction — there is no write/erase path reachable from here.
 * USB ownership stays safe simply by opening the device only for the brief
 * duration of a test and closing right after; the formal single-owner arbiter
 * arrives with the FlashService in Phase 1.
 */
class FlashViewModel(app: Application) : AndroidViewModel(app) {

    private val appContext = app.applicationContext
    private val usbManager = FlashUsbSessionManager(appContext)
    private val settings = FlashSettingsStore(appContext)
    private val safety = FlashSafety(appContext, settings)

    private val _uiState = MutableStateFlow(FlashUiState())
    val uiState: StateFlow<FlashUiState> = _uiState.asStateFlow()

    private var testJob: Job? = null
    private var healthJob: Job? = null

    init {
        healthJob = viewModelScope.launch {
            while (isActive) {
                val h = withContext(Dispatchers.IO) { safety.readHealth() }
                _uiState.update { it.copy(health = h) }
                delay(HEALTH_POLL_MS)
            }
        }
    }

    /** Full safe identify sweep: connect → ping / VIN / CALID / CVN → close. */
    fun testConnection() {
        if (_uiState.value.busy) return
        testJob = viewModelScope.launch(Dispatchers.IO) {
            setBusy(true)
            log("── Test connection ──")
            setState { it.copy(phase = FlashUiState.Phase.Connecting, statusMessage = "Locating OpenPort…") }
            try {
                withCanSession { can ->
                    setState { it.copy(phase = FlashUiState.Phase.Identifying, statusMessage = "Identifying ECU…") }
                    log("CAN channel open (ISO15765 @ 500k). Probing…")
                    val identity = FlashIdentify(can).identify()
                    setState {
                        it.copy(
                            phase = if (identity.ecuResponding) FlashUiState.Phase.Done else FlashUiState.Phase.Failed,
                            identity = identity,
                            statusMessage = if (identity.ecuResponding) "ECU responded" else "No ECU reply"
                        )
                    }
                    logIdentity(identity)
                }
            } finally {
                setBusy(false)
            }
        }
    }

    /** Re-read device health immediately (e.g. a manual refresh tap). */
    fun refreshHealth() {
        viewModelScope.launch {
            val h = withContext(Dispatchers.IO) { safety.readHealth() }
            _uiState.update { it.copy(health = h) }
        }
    }

    override fun onCleared() {
        healthJob?.cancel()
        testJob?.cancel()
        super.onCleared()
    }

    // ---- internals ----

    /**
     * Opens the OpenPort + CAN channel, runs [block], and always closes the
     * session. Any failure is reported into UI state and the session still
     * closes via finally.
     */
    private fun withCanSession(block: (CanFlashSession) -> Unit) {
        val device = usbManager.findDevice()
        if (device == null) {
            fail("OpenPort not detected — plug it in and try again")
            return
        }
        if (!usbManager.hasPermission(device)) {
            fail("No USB permission — plug in the OpenPort and approve PROTOCOL on the main screen")
            return
        }
        when (val r = usbManager.openSession(device)) {
            is FlashUsbResult.Failed -> fail("USB open failed: ${r.reason}")
            is FlashUsbResult.Connected -> {
                try {
                    val can = CanFlashSession(FlashBulkIo(r.session))
                    val openErr = can.openChannel()
                    if (openErr != null) {
                        fail("Channel open failed: $openErr")
                        return
                    }
                    block(can)
                } catch (e: FlashUsbException) {
                    fail("USB disconnected: ${e.message}")
                } catch (e: Exception) {
                    fail("Error: ${e.message ?: e.javaClass.simpleName}")
                } finally {
                    usbManager.closeSession(r.session)
                    log("Session closed.")
                }
            }
        }
    }

    private fun fail(msg: String) {
        setState { it.copy(phase = FlashUiState.Phase.Failed, statusMessage = msg) }
        log("FAIL: $msg")
    }

    private fun setBusy(busy: Boolean) = _uiState.update { it.copy(busy = busy) }

    private fun setState(reducer: (FlashUiState) -> FlashUiState) = _uiState.update(reducer)

    private fun log(line: String) =
        _uiState.update { it.copy(runLog = (it.runLog + line).takeLast(MAX_LOG)) }

    private fun logIdentity(id: FlashIdentify.Identity) {
        log("ECU responding: ${if (id.ecuResponding) "YES" else "no"}")
        log("VIN:   ${id.vin ?: "—"}")
        log("CALID: ${id.calId ?: "—"}")
        log("CVN:   ${id.cvn ?: "—"}")
        id.supportedPidsHex?.let { log("Supported PIDs (01 00): $it") }
    }

    private companion object {
        private const val HEALTH_POLL_MS = 1000L
        private const val MAX_LOG = 300
    }
}
