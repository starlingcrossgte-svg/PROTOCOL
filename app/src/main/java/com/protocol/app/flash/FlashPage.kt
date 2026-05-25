package com.protocol.app.flash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Local, self-contained style for the flash silo (kept out of the logging
// package's palette on purpose). Final look is the user's to design.
private val FlashAccent = Color(0xFFB85419)
private val FlashSurface = Color(0xFF22252C)
private val FlashSuccess = Color(0xFF22FF66)
private val FlashFail = Color(0xFFEF4444)
private fun flashCorner() = CutCornerShape(topEnd = 10.dp, bottomStart = 10.dp)

/**
 * Flash ECU page (Phase 0). Rendered as a sub-page body inside ProtocolScreen,
 * which supplies the background and the "Flash ECU" header + close.
 *
 * First view is a confirmation gate; after acknowledging, the read-only
 * connection test UI appears (Test Connection + identify results + live device
 * health + a run log). Nothing here can write to or erase the ECU.
 */
@Composable
internal fun FlashPage(
    state: FlashUiState,
    onTestConnection: () -> Unit,
    onRefreshHealth: () -> Unit
) {
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    if (!acknowledged) {
        FlashGate(onContinue = { acknowledged = true })
    } else {
        FlashTestContent(state, onTestConnection, onRefreshHealth)
    }
}

@Composable
private fun FlashGate(onContinue: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 18.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "FLASH TOOLS — ADVANCED",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleMedium
        )
        Text(
            text = "This area is for advanced / bench use. Phase 0 is read-only — it " +
                "connects over CAN and reads the ECU's identity (VIN / calibration). " +
                "It cannot write or erase anything.",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
        AccentButton(text = "Continue", onClick = onContinue)
    }
}

@Composable
private fun FlashTestContent(
    state: FlashUiState,
    onTestConnection: () -> Unit,
    onRefreshHealth: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Status line, colored by phase.
        val statusColor = when (state.phase) {
            FlashUiState.Phase.Done -> FlashSuccess
            FlashUiState.Phase.Failed -> FlashFail
            FlashUiState.Phase.Connecting, FlashUiState.Phase.Identifying -> FlashAccent
            FlashUiState.Phase.Idle -> Color.White
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (state.busy) {
                CircularProgressIndicator(
                    color = FlashAccent,
                    strokeWidth = 2.dp,
                    modifier = Modifier.height(16.dp).padding(end = 8.dp)
                )
            }
            Text(
                text = state.statusMessage,
                color = statusColor,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        AccentButton(
            text = if (state.busy) "Testing…" else "Test Connection",
            onClick = onTestConnection,
            enabled = !state.busy
        )

        // Identify results.
        state.identity?.let { id ->
            SectionCard(title = "IDENTITY") {
                InfoRow("ECU", if (id.ecuResponding) "responding" else "no reply")
                InfoRow("VIN", id.vin ?: "—")
                InfoRow("CALID", id.calId ?: "—")
                InfoRow("CVN", id.cvn ?: "—")
                id.supportedPidsHex?.let { InfoRow("PIDs 01·00", it) }
            }
        }

        // Live device health.
        state.health?.let { h ->
            SectionCard(title = "DEVICE") {
                InfoRow("Battery", "${h.batteryPercent}%" + if (h.charging) "  (charging)" else "")
                InfoRow("Draw", "${h.currentMilliAmps} mA")
                InfoRow("Thermal", h.thermalStatus)
                InfoRow("Airplane", if (h.airplaneMode) "ON" else "off")
                InfoRow("CPU", h.cpuPercent?.let { "%.0f%%".format(it) } ?: "—")
                InfoRow("Memory", "${h.appUsedMb} MB app · ${h.systemAvailMb} MB free")
            }
        }

        // Run log.
        if (state.runLog.isNotEmpty()) {
            SectionCard(title = "LOG") {
                Text(
                    text = state.runLog.joinToString("\n"),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .border(BorderStroke(1.dp, FlashAccent), flashCorner())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = title,
            color = FlashAccent,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelMedium
        )
        content()
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            color = Color(0xFFB8B8C0),
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.width(110.dp)
        )
        Text(
            text = value,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun AccentButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = FlashSurface,
            contentColor = Color.White,
            disabledContainerColor = FlashSurface,
            disabledContentColor = Color(0xFF6A6A72)
        ),
        shape = flashCorner(),
        border = BorderStroke(1.dp, FlashAccent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
    }
}
