package com.protocol.app.protocol

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
    onPickCsvFolder: () -> Unit,
    onRawLogNameChange: (String) -> Unit,
    onSessionLogNameChange: (String) -> Unit
) {
    val s = uiState.settings
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // Keep the text field above the keyboard in edge-to-edge mode.
            .imePadding()
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 20.dp),
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
                SelectorButton("OBDLink EX", selected = s.adapter == Adapter.OBDLinkEx) {
                    onAdapterChange(if (s.adapter == Adapter.OBDLinkEx) null else Adapter.OBDLinkEx)
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
        SettingsButton(
            label = "Choose from Gallery",
            shape = RectangleShape,
            onClick = onPickBackground
        )
        if (uiState.backgroundUri != null) {
            SettingsButton(label = "Remove", shape = RectangleShape, onClick = onClearBackground)
        }

        // ── CSV Output ─────────────────────────────────────────────
        // Destination folder + base file names for the Lock-and-Tap
        // auto-save. Both logs save into this one folder, each under its
        // own name with a numeric suffix (rawbytes1.csv, rawbytes2.csv …).
        CategoryHeader("CSV OUTPUT")
        SettingsButton(label = "Choose CSV Folder", shape = RectangleShape, onClick = onPickCsvFolder)
        val folderLabel = s.csvFolderUri?.let { CsvDestination.prettyFolderLabel(Uri.parse(it)) }
        SettingsHelp(
            if (folderLabel != null) "Saving to: $folderLabel"
            else "No folder chosen — Lock-and-Tap auto-save is off until you pick one."
        )

        var rawNameField by remember(s.rawLogName) { mutableStateOf(s.rawLogName) }
        NameField(
            label = "RAW BYTE LOG NAME",
            value = rawNameField,
            onValueChange = { rawNameField = it; onRawLogNameChange(it) }
        )
        var sessionNameField by remember(s.sessionLogName) { mutableStateOf(s.sessionLogName) }
        NameField(
            label = "SESSION LOG NAME",
            value = sessionNameField,
            onValueChange = { sessionNameField = it; onSessionLogNameChange(it) }
        )

        // Developer Mode moved to its own page (Home → DEV MODE); the master
        // ON/OFF lives at the top of that page now.
    }
}

// Free-form name field (no forced uppercase) for the CSV base names. Dark/accent
// styling; ASCII keyboard with autocorrect off so
// file names aren't mangled by suggestions.
@Composable
private fun NameField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = {
            Text(
                label,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            autoCorrect = false,
            capitalization = KeyboardCapitalization.None
        ),
        textStyle = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            color = Color.White
        ),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedBorderColor = Accent,
            unfocusedBorderColor = Accent.copy(alpha = 0.6f),
            cursorColor = Accent,
            focusedLabelColor = Accent,
            unfocusedLabelColor = NeutralGray,
            focusedContainerColor = SurfaceBg,
            unfocusedContainerColor = SurfaceBg
        )
    )
}

// Reactive selector: dim outline + dim text when unselected, full-bright
// Accent (white) outline + text when selected. No fill — the container
// stays dark in both states so the brightness change reads cleanly.
@Composable
private fun SelectorButton(label: String, selected: Boolean, onClick: () -> Unit) {
    val lineColor = if (selected) Accent else AccentDim
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceBg,
            contentColor = lineColor
        ),
        shape = RectangleShape,
        border = BorderStroke(1.dp, lineColor),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = lineColor
        )
    }
}

@Composable
internal fun SettingsButton(label: String, shape: Shape = y2kCornerShape(), onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceBg,
            contentColor = Color.White
        ),
        shape = shape,
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
