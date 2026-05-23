package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

// Garage sub-page. Lets the user save a list of vehicles and mark one
// as active. The active vehicle is the foundation for future
// vehicle-specific PID profiles, diagnostic code labels, and flash
// recipes — currently it is only persisted, not consumed elsewhere.
// That deferral is intentional so the rest of the app keeps working
// for the user's single 06 Outback without a giant refactor today.

@Composable
internal fun GarageBody(
    uiState: ProtocolUiState,
    onSaveVehicle: (year: String, make: String, model: String, subModel: String) -> Unit,
    onSelectVehicle: (id: String) -> Unit,
    onDeleteVehicle: (id: String) -> Unit
) {
    val garage = uiState.garage
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CategoryHeader("ADD VEHICLE")

        var year by remember { mutableStateOf("") }
        var make by remember { mutableStateOf("") }
        var model by remember { mutableStateOf("") }
        var subModel by remember { mutableStateOf("") }

        VehicleField(
            label = "YEAR",
            value = year,
            onValueChange = { year = it.uppercase() },
            keyboardType = KeyboardType.Number
        )
        VehicleField(
            label = "MAKE",
            value = make,
            onValueChange = { make = it.uppercase() }
        )
        VehicleField(
            label = "MODEL",
            value = model,
            onValueChange = { model = it.uppercase() }
        )
        VehicleField(
            label = "SUB-MODEL",
            value = subModel,
            onValueChange = { subModel = it.uppercase() }
        )

        GarageActionButton(
            label = "SAVE",
            active = true,
            onClick = {
                onSaveVehicle(year, make, model, subModel)
                year = ""; make = ""; model = ""; subModel = ""
            }
        )

        CategoryHeader("SAVED VEHICLES")
        if (garage.vehicles.isEmpty()) {
            Text(
                "No vehicles saved yet. Fill in the form above and tap SAVE.",
                color = NeutralGray,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        } else {
            for (v in garage.vehicles) {
                VehicleCard(
                    vehicle = v,
                    selected = v.id == garage.selectedVehicleId,
                    onSelect = { onSelectVehicle(v.id) },
                    onDelete = { onDeleteVehicle(v.id) }
                )
            }
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
        // Suggest caps lock for letter fields. The onValueChange also
        // uppercases anything that slips through, so paste / autofill /
        // hardware keyboard inputs still get normalized.
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

// Selected vehicle = solid Accent fill (orange). Unselected = SurfaceBg
// fill with Accent border — matches the home-screen menu buttons so the
// garage list reads as the same "tappable card" family. SELECT + DELETE
// buttons live inside each card.
@Composable
private fun VehicleCard(
    vehicle: Vehicle,
    selected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit
) {
    val containerColor = if (selected) Accent else SurfaceBg
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = containerColor, shape = y2kCornerShape())
    ) {
        // Border drawn via a Button-style outline doesn't apply here
        // since we're using a Column. Use a manual border via Modifier.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = vehicle.displayName.ifBlank { "(unnamed vehicle)" },
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                GarageActionButton(
                    label = if (selected) "SELECTED" else "SELECT",
                    active = selected,
                    onClick = onSelect,
                    modifier = Modifier.weight(1f)
                )
                GarageActionButton(
                    label = "DELETE",
                    active = false,
                    danger = true,
                    onClick = onDelete,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun GarageActionButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    danger: Boolean = false
) {
    val container = when {
        danger -> SurfaceBg
        active -> Accent
        else -> SurfaceBg
    }
    val border = if (danger) FailRed else Accent
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = container,
            contentColor = Color.White
        ),
        shape = y2kCornerShape(),
        border = BorderStroke(1.dp, border),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = modifier.fillMaxWidth()
    ) {
        Text(
            label,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            color = Color.White
        )
    }
}
