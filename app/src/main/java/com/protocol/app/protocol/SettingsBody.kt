package com.protocol.app.protocol

import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.DropdownMenu
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
import androidx.compose.ui.geometry.Offset
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
    onDevModeChange: (Boolean) -> Unit,
    onSaveVehicle: (year: String, make: String, model: String, subModel: String) -> Unit,
    onSelectVehicle: (id: String) -> Unit,
    onDeleteVehicle: (id: String) -> Unit,
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

        // ── Garage ─────────────────────────────────────────────────
        // Inline form to add a vehicle, plus a dropdown that picks the
        // active one. Active vehicle drives future PID profiles,
        // diagnostic decode, and per-car flash recipes — persisted but
        // not yet read by any feature.
        CategoryHeader("GARAGE")

        var yearField by remember { mutableStateOf("") }
        var makeField by remember { mutableStateOf("") }
        var modelField by remember { mutableStateOf("") }
        var subModelField by remember { mutableStateOf("") }

        VehicleField(
            label = "YEAR",
            value = yearField,
            onValueChange = { yearField = it.uppercase() },
            keyboardType = KeyboardType.Number
        )
        VehicleField(
            label = "MAKE",
            value = makeField,
            onValueChange = { makeField = it.uppercase() }
        )
        VehicleField(
            label = "MODEL",
            value = modelField,
            onValueChange = { modelField = it.uppercase() }
        )
        VehicleField(
            label = "SUB-MODEL",
            value = subModelField,
            onValueChange = { subModelField = it.uppercase() }
        )

        SettingsButton(label = "SAVE", shape = RectangleShape) {
            onSaveVehicle(yearField, makeField, modelField, subModelField)
            yearField = ""; makeField = ""; modelField = ""; subModelField = ""
        }

        VehicleDropdown(
            garage = uiState.garage,
            onSelectVehicle = onSelectVehicle,
            onDeleteVehicle = onDeleteVehicle
        )

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
                    checkedTrackColor = AccentDim,
                    uncheckedThumbColor = InkMuted,
                    uncheckedTrackColor = SurfaceAlt,
                    uncheckedBorderColor = BorderGray
                )
            )
        }
    }
}

@Composable
private fun VehicleField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text
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
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = keyboardType
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

// Free-form name field (no forced uppercase) for the CSV base names. Same
// dark/accent styling as VehicleField; ASCII keyboard with autocorrect off so
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

// Collapsed selector shows the active vehicle (or "Select vehicle"
// placeholder). Tap to expand a DropdownMenu listing every saved
// vehicle with a delete X. Tapping a row selects (and closes); tapping
// X deletes that vehicle in place without closing the menu. Selecting
// the currently-active vehicle deselects it (ViewModel.selectVehicle
// toggles when id matches the existing selection).
@Composable
private fun VehicleDropdown(
    garage: GarageState,
    onSelectVehicle: (id: String) -> Unit,
    onDeleteVehicle: (id: String) -> Unit
) {
    if (garage.vehicles.isEmpty()) {
        Text(
            "No vehicles saved yet.",
            color = NeutralGray,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxWidth()) {
        val shape = RectangleShape
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(SurfaceBg, shape)
                .border(1.dp, Accent, shape)
                .clickable { expanded = !expanded }
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val selected = garage.selectedVehicle
            Text(
                text = selected?.displayName ?: "Select vehicle",
                color = if (selected == null) NeutralGray else Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "▾",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(SurfaceBg)
                .border(BorderStroke(1.dp, Accent))
        ) {
            for (v in garage.vehicles) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = v.displayName,
                        color = if (v.id == garage.selectedVehicleId) Accent else AccentDim,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .clickable {
                                onSelectVehicle(v.id)
                                expanded = false
                            }
                            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
                    )
                    SmallDeleteX(onClick = { onDeleteVehicle(v.id) })
                }
            }
        }
    }
}

@Composable
private fun SmallDeleteX(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 40.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(14.dp)) {
            val stroke = 2.dp.toPx()
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.15f, size.height * 0.15f),
                end = Offset(size.width * 0.85f, size.height * 0.85f),
                strokeWidth = stroke
            )
            drawLine(
                color = Color.White,
                start = Offset(size.width * 0.85f, size.height * 0.15f),
                end = Offset(size.width * 0.15f, size.height * 0.85f),
                strokeWidth = stroke
            )
        }
    }
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
