package com.protocol.app.flash

import com.protocol.app.flash.engine.FlashIdentify

/**
 * Immutable UI snapshot the Flash page renders. Phase 0: connection/identify
 * state plus the live device-health strip and a short run log for bench
 * debugging.
 */
data class FlashUiState(
    val phase: Phase = Phase.Idle,
    val statusMessage: String = "Idle - tap Test Connection",
    val busy: Boolean = false,
    val identity: FlashIdentify.Identity? = null,
    val health: FlashSafety.DeviceHealth? = null,
    val deviceLog: List<DeviceSample> = emptyList(),
    val runLog: List<String> = emptyList()
) {
    enum class Phase { Idle, Connecting, Identifying, Done, Failed }
}

/** One timestamped device-health reading, accumulated so we can watch how the
 *  phone behaves under load and export the history as CSV. */
data class DeviceSample(
    val timestampMs: Long,
    val health: FlashSafety.DeviceHealth
)
