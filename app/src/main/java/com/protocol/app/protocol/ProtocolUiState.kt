package com.protocol.app.protocol

import com.protocol.app.openport2.PollSample
import com.protocol.app.openport2.Ssm2DecodeBundle
import com.protocol.app.openport2.Ssm2EcmProbe
import com.protocol.app.openport2.TactrixCommandLog

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
    val tappedParamLongName: String? = null
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
