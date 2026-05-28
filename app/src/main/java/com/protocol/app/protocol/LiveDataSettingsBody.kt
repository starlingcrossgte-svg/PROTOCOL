package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun LiveDataSettingsBody(
    uiState: ProtocolUiState,
    onAdapterChange: (Adapter) -> Unit,
    onProtocolChange: (BusProtocol) -> Unit,
    onPollIntervalChange: (Int) -> Unit,
    onSessionLogMaxChange: (Int) -> Unit,
    onResetLayout: () -> Unit,
    onShareSavedSession: () -> Unit,
    onResetAdapter: () -> Unit
) {
    val s = uiState.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CategoryHeader("ADAPTER")
        SelectorButton("OpenPort 2.0", selected = s.adapter == Adapter.OpenPort) {
            onAdapterChange(Adapter.OpenPort)
        }
        SelectorButton("OBDLink MX+", selected = s.adapter == Adapter.OBDLink) {
            onAdapterChange(Adapter.OBDLink)
        }

        CategoryHeader("PROTOCOL")
        SelectorButton("K-Line", selected = s.protocol == BusProtocol.KLine) {
            onProtocolChange(BusProtocol.KLine)
        }
        SelectorButton("CAN Bus", selected = s.protocol == BusProtocol.CAN) {
            onProtocolChange(BusProtocol.CAN)
        }

        CategoryHeader("POLLING")
        SliderRow(
            label = "Poll interval",
            value = s.pollIntervalMs,
            suffix = "ms",
            range = AppSettings.POLL_INTERVAL_MIN..AppSettings.POLL_INTERVAL_MAX,
            stepDp = 50,
            onChange = onPollIntervalChange
        )
        SettingsHelp("Lower = faster gauge updates, more K-line traffic. Changes apply on the next Read Live Data.")

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

        CategoryHeader("ADAPTER RESET")
        SettingsButton(label = "Reset OBDLink Adapter (factory)", onClick = onResetAdapter)
        SettingsHelp("Sends ATPP FF OFF / ATD / ATZ to the paired OBDLink over Bluetooth — clears its stored programmable parameters, restores factory defaults, then reboots it. Use this to return the adapter to a known factory state. Requires the Bluetooth toggle ON and the MX+ paired. The adapter drops its link after the reset — power-cycle it and re-pair.")
    }
}

@Composable
private fun SelectorButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (selected) Accent else SurfaceBg,
            contentColor = Color.White
        ),
        shape = y2kCornerShape(),
        border = BorderStroke(1.dp, Accent),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}
