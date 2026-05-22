package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Settings sub-page. Background image, polling, session log size,
// layout reset, developer mode. Title + close X live in the shared
// header. Each section uses CategoryHeader for visual consistency
// with the Parameters and Outcome bodies.

@Composable
internal fun SettingsBody(
    uiState: ProtocolUiState,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onPollIntervalChange: (Int) -> Unit,
    onSessionLogMaxChange: (Int) -> Unit,
    onDevModeChange: (Boolean) -> Unit,
    onResetLayout: () -> Unit
) {
    val s = uiState.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Background ─────────────────────────────────────────────
        CategoryHeader("BACKGROUND")
        Text(
            text = if (uiState.backgroundUri == null)
                "No custom background. App uses the default Y2K dark surface."
            else
                "Custom background active. Tap below to change or clear.",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
        SettingsButton(
            label = if (uiState.backgroundUri == null) "Choose Background" else "Change Background",
            onClick = onPickBackground
        )
        if (uiState.backgroundUri != null) {
            SettingsButton(label = "Clear Background", onClick = onClearBackground)
        }
        SettingsHelp("Pick any image from your gallery. A 50% dark overlay is applied automatically so text stays readable against bright photos.")

        // ── Polling ────────────────────────────────────────────────
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

        // ── Session log ────────────────────────────────────────────
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

        // ── Layout ─────────────────────────────────────────────────
        CategoryHeader("LAYOUT")
        SettingsButton(label = "Reset Gauge Layout", onClick = onResetLayout)
        SettingsHelp("Clears all gauges from Live Data. Add new ones from the Parameters menu.")

        // ── Developer ──────────────────────────────────────────────
        CategoryHeader("DEVELOPER")
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Developer Mode",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = s.devMode,
                onCheckedChange = onDevModeChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = Accent,
                    uncheckedThumbColor = InkMuted,
                    uncheckedTrackColor = SurfaceAlt,
                    uncheckedBorderColor = BorderGray
                )
            )
        }
        SettingsHelp("Reveals the SSM2 Run Log + Clear/Copy/Export Log buttons on the Home page. For debugging adapter / protocol issues.")
    }
}

@Composable
private fun SettingsButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceBg,
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

@Composable
private fun SettingsHelp(text: String) {
    Text(
        text = text,
        color = NeutralGray,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
    )
}

@Composable
private fun SliderRow(
    label: String,
    value: Int,
    suffix: String,
    range: IntRange,
    stepDp: Int,
    onChange: (Int) -> Unit
) {
    val steps = ((range.last - range.first) / stepDp).coerceAtLeast(0) - 1
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (suffix.isEmpty()) "$value" else "$value $suffix",
            color = Accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = if (steps > 0) steps else 0,
        colors = SliderDefaults.colors(
            thumbColor = Accent,
            activeTrackColor = Accent,
            inactiveTrackColor = SurfaceAlt,
            activeTickColor = SurfaceBg,
            inactiveTickColor = BorderGray
        )
    )
}
