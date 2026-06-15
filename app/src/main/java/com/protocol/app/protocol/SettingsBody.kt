package com.protocol.app.protocol

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

// General app Settings sub-page. Connection config (adapter / protocol / polling
// mode) at the top in the rounded dev-console dropdown style, then background +
// CSV output. Live-Data-specific knobs (poll interval, session log size, layout
// reset) live on LiveDataSettingsBody, reached from the Live Data hamburger.

private val SETTINGS_SHAPE = RoundedCornerShape(8.dp)

@Composable
internal fun SettingsBody(
    uiState: ProtocolUiState,
    onAdapterChange: (Adapter?) -> Unit,
    onProtocolChange: (BusProtocol?) -> Unit,
    onPollingModeChange: (PollingMode) -> Unit,
    onPollIntervalChange: (Int) -> Unit,
    onDisconnectObdLink: () -> Unit,
    onResetAdapter: () -> Unit,
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
        // ── Connection config ──────────────────────────────────────
        // Shared by Live Data, Diagnostics, and future Flash — set once
        // here and every feature reads the same values.
        CategoryHeader("ADAPTER")
        SelectorDropdown(
            placeholder = "SELECT ADAPTER",
            items = listOf(
                DropdownItem("OpenPort 2.0"),
                DropdownItem("OBDLink MX+"),
                DropdownItem("OBDLink EX"),
                DropdownItem("FT232RL (KKL)")
            ),
            selectedIndex = s.adapter?.ordinal,
            onSelect = { i ->
                val picked = Adapter.values()[i]
                onAdapterChange(if (s.adapter == picked) null else picked)
            }
        )

        // Connection actions for the picked OBDLink, shown only while it's live.
        // OpenPort / KKL are USB — unplugging the cable IS the disconnect — so
        // they get no button. Disconnect is BT-only (the MX+ has no cable to
        // pull); factory reset applies to both STN OBDLinks.
        val obdLinkConnected = uiState.connectionStatus is ConnectionStatus.Connected &&
            (s.adapter == Adapter.OBDLink || s.adapter == Adapter.OBDLinkEx)
        if (obdLinkConnected && s.adapter == Adapter.OBDLink) {
            SettingsButton(label = "Disconnect OBDLink", shape = SETTINGS_SHAPE, onClick = onDisconnectObdLink)
            SettingsHelp("Drops the Bluetooth link to the OBDLink MX+ and stops polling. Your adapter and protocol selection are kept — reconnect from Read Live Data. Use this to free the adapter or recover a stuck Bluetooth connection.")
        }
        if (obdLinkConnected) {
            SettingsButton(label = "Factory-Reset OBDLink", shape = SETTINGS_SHAPE, onClick = onResetAdapter)
            SettingsHelp("Sends ATPP FF OFF / ATD / ATZ — clears the adapter's stored programmable parameters, restores factory defaults, then reboots it. The adapter drops its link after the reset; power-cycle and reconnect. Deep troubleshooting only.")
        }

        CategoryHeader("PROTOCOL")
        // CAN · Broadcast is the '06 Outback's listen-only powertrain CAN — its
        // decode path isn't wired yet, so it's disabled. Keeping it a separate
        // selection (not "CAN") is a SAFETY gate: regular request mode on a
        // broadcast bus could inject commands.
        SelectorDropdown(
            placeholder = "SELECT PROTOCOL",
            items = listOf(
                DropdownItem("K-Line"),
                DropdownItem("CAN · Diagnostic"),
                DropdownItem("CAN · Broadcast", enabled = false)
            ),
            selectedIndex = s.protocol?.ordinal,
            onSelect = { i ->
                val picked = BusProtocol.values()[i]
                onProtocolChange(if (s.protocol == picked) null else picked)
            }
        )

        CategoryHeader("POLLING MODE")
        // Monitor (listen-only decode) isn't wired yet → disabled.
        SelectorDropdown(
            placeholder = "SELECT MODE",
            items = listOf(
                DropdownItem("Poll"),
                DropdownItem("Stream"),
                DropdownItem("Monitor", enabled = false)
            ),
            selectedIndex = s.pollingMode.ordinal,
            onSelect = { i -> onPollingModeChange(PollingMode.values()[i]) }
        )

        // Poll interval is the minimum cycle period for POLL mode only. Stream
        // rides the ECU's self-paced A8 01 stream and Monitor listens passively,
        // so neither honors it — grey it out there to make that explicit.
        CategoryHeader("POLL INTERVAL")
        val pollEnabled = s.pollingMode == PollingMode.Poll
        SliderRow(
            label = "Poll interval",
            value = s.pollIntervalMs,
            suffix = "ms",
            range = AppSettings.POLL_INTERVAL_MIN..AppSettings.POLL_INTERVAL_MAX,
            stepDp = 50,
            enabled = pollEnabled,
            onChange = onPollIntervalChange
        )
        SettingsHelp(
            if (pollEnabled)
                "Minimum poll period — lower = faster gauge updates, more bus traffic. Mainly bounds CAN and the simulator; on K-line the wire is usually the limit. Applies on the next Read Live Data."
            else
                "Only applies in Poll mode. Stream and Monitor run at the ECU's own rate."
        )

        // ── Background ─────────────────────────────────────────────
        CategoryHeader("BACKGROUND")
        SettingsButton(label = "Choose from Gallery", shape = SETTINGS_SHAPE, onClick = onPickBackground)
        if (uiState.backgroundUri != null) {
            SettingsButton(label = "Remove", shape = SETTINGS_SHAPE, onClick = onClearBackground)
        }

        // ── CSV Output ─────────────────────────────────────────────
        // Destination folder + base file names for the Lock-and-Tap auto-save.
        // Both logs save into this one folder, each under its own name with a
        // numeric suffix (rawbytes1.csv, rawbytes2.csv …).
        CategoryHeader("CSV OUTPUT")
        SettingsButton(label = "Choose CSV Folder", shape = SETTINGS_SHAPE, onClick = onPickCsvFolder)
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
    }
}

// Free-form name field (no forced uppercase) for the CSV base names. Dark/accent
// styling; ASCII keyboard with autocorrect off so file names aren't mangled.
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
    enabled: Boolean = true,
    onChange: (Int) -> Unit
) {
    val steps = ((range.last - range.first) / stepDp).coerceAtLeast(0) - 1
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            color = if (enabled) Color.White else NeutralGray,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = if (suffix.isEmpty()) "$value" else "$value $suffix",
            color = if (enabled) Accent else NeutralGray,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { onChange(it.toInt()) },
        enabled = enabled,
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = if (steps > 0) steps else 0,
        colors = SliderDefaults.colors(
            thumbColor = Accent,
            activeTrackColor = Accent,
            inactiveTrackColor = SurfaceAlt,
            activeTickColor = SurfaceBg,
            inactiveTickColor = BorderGray,
            disabledThumbColor = NeutralGray,
            disabledActiveTrackColor = SurfaceAlt,
            disabledInactiveTrackColor = SurfaceAlt
        )
    )
}
