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

// General app Settings sub-page. Background image + developer mode +
// future global app preferences. Live-Data-specific knobs (poll
// interval, session log size, layout reset) live on the new
// LiveDataSettingsBody, accessed via the hamburger on Live Data.

@Composable
internal fun SettingsBody(
    uiState: ProtocolUiState,
    onAdapterChange: (Adapter?) -> Unit,
    onProtocolChange: (BusProtocol?) -> Unit,
    onSsmVariantChange: (SsmVariant?) -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onDevModeChange: (Boolean) -> Unit
) {
    val s = uiState.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Adapter + Protocol (side-by-side) ───────────────────────
        // Connection-config knobs shared by Live Data, Diagnostics, and
        // future Flash. Live here so they're set once in Settings and
        // every feature reads the same values.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CategoryHeader("ADAPTER")
                SelectorButton("OpenPort 2.0", selected = s.adapter == Adapter.OpenPort) {
                    onAdapterChange(if (s.adapter == Adapter.OpenPort) null else Adapter.OpenPort)
                }
                SelectorButton("OBDLink MX+", selected = s.adapter == Adapter.OBDLink) {
                    onAdapterChange(if (s.adapter == Adapter.OBDLink) null else Adapter.OBDLink)
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CategoryHeader("PROTOCOL")
                SelectorButton("K-Line", selected = s.protocol == BusProtocol.KLine) {
                    onProtocolChange(if (s.protocol == BusProtocol.KLine) null else BusProtocol.KLine)
                }
                SelectorButton("CAN Bus", selected = s.protocol == BusProtocol.CAN) {
                    onProtocolChange(if (s.protocol == BusProtocol.CAN) null else BusProtocol.CAN)
                }
            }
        }

        // ── SSM variant ────────────────────────────────────────────
        // Drives Diagnostics request format. Live Data doesn't read this.
        // SSM3 is a placeholder until protocol support lands.
        CategoryHeader("SSM")
        SelectorButton("SSM2", selected = s.ssmVariant == SsmVariant.SSM2) {
            onSsmVariantChange(if (s.ssmVariant == SsmVariant.SSM2) null else SsmVariant.SSM2)
        }
        SelectorButton("SSM3", selected = s.ssmVariant == SsmVariant.SSM3) {
            onSsmVariantChange(if (s.ssmVariant == SsmVariant.SSM3) null else SsmVariant.SSM3)
        }

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

@Composable
internal fun SettingsButton(label: String, onClick: () -> Unit) {
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
internal fun SettingsHelp(text: String) {
    Text(
        text = text,
        color = NeutralGray,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
    )
}

@Composable
internal fun SliderRow(
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
