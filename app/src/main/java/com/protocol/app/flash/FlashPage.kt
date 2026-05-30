package com.protocol.app.flash

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.protocol.app.flash.engine.FlashTrafficEvent
import com.protocol.app.flash.engine.FlashTrafficLog
import com.protocol.app.protocol.Accent
import com.protocol.app.protocol.BorderGray
import com.protocol.app.protocol.CategoryHeader
import com.protocol.app.protocol.FailRed
import com.protocol.app.protocol.InkPrimary
import com.protocol.app.protocol.NeutralGray
import com.protocol.app.protocol.PassGreen
import com.protocol.app.protocol.SurfaceBg
import com.protocol.app.protocol.y2kCornerShape
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun FlashPage(
    state: FlashUiState,
    onTestConnection: () -> Unit,
    onClose: () -> Unit,
    onExportCsv: (String, String) -> Unit,
    onToggleTrafficRecording: () -> Unit,
    onClearTrafficLog: () -> Unit,
    onToggleDeviceRecording: () -> Unit,
    onClearDeviceLog: () -> Unit,
    onToggleRunLogRecording: () -> Unit,
    onClearRunLog: () -> Unit
) {
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    if (!acknowledged) {
        FlashGate(onContinue = { acknowledged = true }, onClose = onClose)
        return
    }

    val traffic by FlashTrafficLog.events.collectAsState()
    val deviceText = remember(state.deviceLog.size) { deviceLogText(state.deviceLog) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Top row: status (left) | hamburger (right). No close-X — system back.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val statusColor = when (state.phase) {
                FlashUiState.Phase.Done -> PassGreen
                FlashUiState.Phase.Failed -> FailRed
                FlashUiState.Phase.Connecting, FlashUiState.Phase.Identifying -> Accent
                FlashUiState.Phase.Idle -> InkPrimary
            }
            Text(
                text = state.statusMessage,
                color = statusColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f).padding(horizontal = 6.dp)
            )
            FlashHamburgerMenu()
        }

        FlashButton(
            text = if (state.busy) "Testing..." else "Test Connection",
            onClick = onTestConnection,
            enabled = !state.busy
        )

        // Identity — selectable so values can be long-pressed and copied.
        state.identity?.let { id ->
            CategoryHeader("IDENTITY")
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    InfoRow("ECU", if (id.ecuResponding) "responding" else "no reply")
                    InfoRow("VIN", id.vin ?: "-")
                    InfoRow("CALID", id.calId ?: "-")
                    InfoRow("CVN", id.cvn ?: "-")
                    id.supportedPidsHex?.let { InfoRow("PIDs 01 00", it) }
                }
            }
        }

        // USB TRAFFIC
        CategoryHeader("USB TRAFFIC")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Events (${traffic.size})",
                color = InkPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            FlashSmallButton(if (state.trafficRecording) "Stop" else "Start", onToggleTrafficRecording)
            FlashSmallButton("Clear", onClearTrafficLog)
            FlashSmallButton("Export CSV") { onExportCsv("protocol-flash-traffic.csv", trafficCsv(traffic)) }
        }
        FlashTrafficCard(traffic)

        // DEVICE — compact current-value rows + accumulated log below
        CategoryHeader("DEVICE")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Samples (${state.deviceLog.size})",
                color = InkPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            FlashSmallButton(if (state.deviceRecording) "Stop" else "Start", onToggleDeviceRecording)
            FlashSmallButton("Clear", onClearDeviceLog)
            FlashSmallButton("Export CSV") { onExportCsv("protocol-flash-device.csv", deviceCsv(state.deviceLog)) }
        }
        state.health?.let { h ->
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    InfoRow("Battery", "${h.batteryPercent}%${if (h.charging) " CHG" else ""}")
                    InfoRow("Draw", "${h.currentMilliAmps} mA")
                    InfoRow("Thermal", h.thermalStatus)
                    InfoRow("Airplane", if (h.airplaneMode) "ON" else "OFF")
                    InfoRow("CPU", h.cpuPercent?.let { "%.0f%%".format(it) } ?: "-")
                    InfoRow("Memory", "${h.appUsedMb} MB app / ${h.systemAvailMb} MB free")
                }
            }
        }
        FlashTextCard(deviceText, "No device samples yet.")

        // RUN LOG
        CategoryHeader("RUN LOG")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Lines (${state.runLog.size})",
                color = InkPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            FlashSmallButton(if (state.runLogRecording) "Stop" else "Start", onToggleRunLogRecording)
            FlashSmallButton("Clear", onClearRunLog)
            FlashSmallButton("Export CSV") { onExportCsv("protocol-flash-runlog.txt", state.runLog.joinToString("\n")) }
        }
        FlashTextCard(state.runLog.joinToString("\n"), "No run log yet.")

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun FlashGate(onContinue: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CategoryHeader("FLASH TOOLS")
        Text(
            text = "Advanced / bench use. Phase 0 is read-only: it connects over CAN and reads " +
                "the ECU identity (VIN / CALID / CVN). It cannot write or erase anything.",
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium
        )
        FlashButton("Continue", onContinue)
    }
}

// Canvas X close button — matches CloseButton in ProtocolHeader.
@Composable
private fun FlashCloseButton(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clickable(onClick = onClick)
            .size(width = 44.dp, height = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val stroke = 2.5f.dp.toPx()
            drawLine(
                color = Accent,
                start = Offset(size.width * 0.18f, size.height * 0.18f),
                end = Offset(size.width * 0.82f, size.height * 0.82f),
                strokeWidth = stroke
            )
            drawLine(
                color = Accent,
                start = Offset(size.width * 0.82f, size.height * 0.18f),
                end = Offset(size.width * 0.18f, size.height * 0.82f),
                strokeWidth = stroke
            )
        }
    }
}

// Hamburger menu — same bar style as ProtocolHeader, flash-specific items (all unwired).
@Composable
private fun FlashHamburgerMenu() {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .clickable { expanded = true }
            .size(width = 44.dp, height = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(2.dp)
                        .background(Accent)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(SurfaceBg)
                .border(BorderStroke(1.dp, Accent))
        ) {
            listOf("Airplane Mode", "Wake Lock", "Developer Mode").forEach { label ->
                DropdownMenuItem(
                    text = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = label,
                                color = Color.White,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "OFF",
                                color = NeutralGray,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    onClick = { expanded = false }
                )
            }
        }
    }
}

@Composable
private fun FlashTrafficCard(events: List<FlashTrafficEvent>) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14161A)),
        border = BorderStroke(1.dp, BorderGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        val listState = rememberLazyListState()
        LaunchedEffect(events.size) {
            if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
        }
        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(380.dp).padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No traffic yet. Tap Test Connection.",
                    color = NeutralGray,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            return@Card
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().height(380.dp).padding(8.dp)
        ) {
            items(events) { e -> FlashTrafficEventRow(e) }
        }
    }
}

@Composable
private fun FlashTrafficEventRow(event: FlashTrafficEvent) {
    val arrow = if (event.direction == FlashTrafficEvent.Direction.OUT) "→" else "←"
    val arrowColor = if (event.direction == FlashTrafficEvent.Direction.OUT) Accent else PassGreen
    val ts = flashTimeFmt.format(Date(event.timestampMs))
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ts, color = NeutralGray, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            Text("  $arrow  ", color = arrowColor, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            Text("${event.byteCount}B", color = NeutralGray, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        }
        Text(event.hex, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp))
        if (event.ascii.isNotBlank() && event.ascii.any { it.isLetterOrDigit() }) {
            Text(event.ascii, color = NeutralGray, fontFamily = FontFamily.Monospace, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun FlashTextCard(text: String, emptyMsg: String, height: Dp = 380.dp) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14161A)),
        border = BorderStroke(1.dp, BorderGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .padding(10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    text = text.ifBlank { emptyMsg },
                    color = if (text.isBlank()) NeutralGray else Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    softWrap = false
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = NeutralGray, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Text(value, color = Color.White, fontFamily = FontFamily.Monospace, fontSize = 13.sp, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FlashButton(text: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceBg,
            contentColor = Color.White,
            disabledContainerColor = SurfaceBg.copy(alpha = 0.5f),
            disabledContentColor = NeutralGray
        ),
        shape = y2kCornerShape(),
        border = BorderStroke(1.dp, Accent),
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(text, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FlashSmallButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = SurfaceBg, contentColor = Color.White),
        shape = y2kCornerShape(),
        border = BorderStroke(1.dp, Accent),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

// ---- CSV / display formatting ----

private val flashTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun trafficCsv(events: List<FlashTrafficEvent>): String {
    val sb = StringBuilder()
    sb.append("time,dir,bytes,hex\n")
    for (e in events) {
        val dir = if (e.direction == FlashTrafficEvent.Direction.OUT) "OUT" else "IN"
        sb.append(flashTimeFmt.format(Date(e.timestampMs))).append(",")
        sb.append(dir).append(",")
        sb.append(e.byteCount).append(",")
        sb.append("\"").append(e.hex).append("\"").append("\n")
    }
    return sb.toString()
}

private fun deviceCsv(samples: List<DeviceSample>): String {
    val sb = StringBuilder()
    sb.append("time,battery_pct,charging,draw_mA,thermal,cpu_pct,app_mb,free_mb\n")
    for (s in samples) {
        val h = s.health
        sb.append(flashTimeFmt.format(Date(s.timestampMs))).append(",")
        sb.append(h.batteryPercent).append(",")
        sb.append(if (h.charging) "1" else "0").append(",")
        sb.append(h.currentMilliAmps).append(",")
        sb.append(h.thermalStatus).append(",")
        sb.append(h.cpuPercent?.let { "%.1f".format(it) } ?: "").append(",")
        sb.append(h.appUsedMb).append(",")
        sb.append(h.systemAvailMb).append("\n")
    }
    return sb.toString()
}

private fun deviceLogText(samples: List<DeviceSample>): String {
    val sb = StringBuilder()
    for (s in samples) {
        val h = s.health
        sb.append(flashTimeFmt.format(Date(s.timestampMs)))
        sb.append("  bat ").append(h.batteryPercent).append("%")
        if (h.charging) sb.append(" chg")
        sb.append("  ").append(h.currentMilliAmps).append("mA")
        sb.append("  ").append(h.thermalStatus)
        sb.append("  cpu ").append(h.cpuPercent?.let { "%.0f".format(it) } ?: "-").append("%")
        sb.append("  mem ").append(h.appUsedMb).append("/").append(h.systemAvailMb)
        sb.append("\n")
    }
    return sb.toString()
}
