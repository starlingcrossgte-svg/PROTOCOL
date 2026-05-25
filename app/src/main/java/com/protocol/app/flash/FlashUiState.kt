package com.protocol.app.flash

import com.protocol.app.flash.engine.FlashIdentify

/**
 * Immutable UI snapshot the Flash page renders. Phase 0: connection/identify
 * state plus the live device-health strip and a short run log for bench
 * debugging.
 */
data class FlashUiState(
    val phase: Phase = Phase.Idle,
    val statusMessage: String = "Idle — tap Test Connection",
    val busy: Boolean = false,
    val identity: FlashIdentify.Identity? = null,
    val health: FlashSafety.DeviceHealth? = null,
    val runLog: List<String> = emptyList()
) {
    enum class Phase { Idle, Connecting, Identifying, Done, Failed }
}
