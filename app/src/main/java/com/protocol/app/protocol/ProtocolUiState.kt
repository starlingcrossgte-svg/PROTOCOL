package com.protocol.app.protocol

import com.protocol.app.openport2.PollSample
import com.protocol.app.openport2.Ssm2DecodeBundle
import com.protocol.app.openport2.Ssm2EcmProbe
import com.protocol.app.openport2.Ssm2Pids
import com.protocol.app.openport2.TactrixCommandLog

/*
 * Default selection = every PID the poller knows about. Derived from
 * [Ssm2Pids.DEFAULT_DEMO_PIDS] so adding or removing a PID can't desync
 * this list from the actual catalog. UX for choosing a different default
 * is the user's call (parameter-selection menu still to be designed).
 */
private val DEFAULT_SELECTED_PID_IDS: Set<String> =
    Ssm2Pids.DEFAULT_DEMO_PIDS.map { it.id }.toSet()

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
    val selectedPidIds: Set<String> = DEFAULT_SELECTED_PID_IDS,
    val tappedParamLongName: String? = null
)

sealed class ConnectionStatus {
    object NoDevice : ConnectionStatus()
    data class PermissionRequired(val deviceLabel: String) : ConnectionStatus()
    data class Ready(val deviceLabel: String) : ConnectionStatus()
    data class Connected(val deviceLabel: String) : ConnectionStatus()
    data class Error(val reason: String) : ConnectionStatus()
}
