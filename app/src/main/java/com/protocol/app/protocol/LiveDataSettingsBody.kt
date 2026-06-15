package com.protocol.app.protocol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

// Live-Data-specific knobs, reached from the Live Data hamburger. Polling config
// (poll interval + Poll/Stream/Monitor mode) lives on the general Settings page
// (SettingsBody) so it's set once and shared by every feature — it is NOT
// duplicated here.
@Composable
internal fun LiveDataSettingsBody(
    uiState: ProtocolUiState,
    onSessionLogMaxChange: (Int) -> Unit,
    onResetLayout: () -> Unit,
    onShareSavedSession: () -> Unit
) {
    val s = uiState.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CategoryHeader("SESSION LOG")
        SliderRow(
            label = "Max rows",
            value = s.sessionLogMaxSize,
            suffix = "",
            range = AppSettings.SESSION_LOG_MIN..AppSettings.SESSION_LOG_MAX,
            stepDp = 500,
            onChange = onSessionLogMaxChange
        )
        SettingsHelp("Older rows drop off when the cap is reached. Applies live to the running session.")

        CategoryHeader("LAYOUT")
        SettingsButton(label = "Reset Gauge Layout", onClick = onResetLayout)
        SettingsHelp("Clears all gauges from Live Data. Add new ones from the Parameters menu.")

        CategoryHeader("CRASH RECOVERY")
        SettingsButton(label = "Share Saved Session", onClick = onShareSavedSession)
        SettingsHelp("PROTOCOL auto-saves your in-progress session log to internal cache every ~1 second while logging. If the app gets killed mid-session, tap this to recover the last saved CSV via the system share sheet (Files, Gmail, Drive, etc.).")
    }
}
