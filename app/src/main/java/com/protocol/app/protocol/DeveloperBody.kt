package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.protocol.app.obdlink.ObdLinkTrafficEvent
import com.protocol.app.obdlink.ObdLinkTrafficLog
import com.protocol.app.openport2.TrafficEvent
import com.protocol.app.openport2.UsbTrafficLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Developer Mode content. Pared down to two things:
//
//   - Simulator toggle + TCP port (routes live data to the host-side VIPER
//     emulator over a localhost socket).
//   - One combined, raw traffic log: the USB (Tactrix/OpenPort) and OBDLink
//     byte streams merged into a single time-ordered view. Only one transport
//     runs at a time, so the single log just shows whatever is live.
//
// Renders inline on the Home page when Settings → Developer Mode is on.

@Composable
internal fun DeveloperBody(
    uiState: ProtocolUiState,
    onRunProbe: () -> Unit,
    onClearProbeLog: () -> Unit,
    onCopyProbeLog: () -> Unit,
    onExportProbeLog: () -> Unit,
    onHuntKlineInit: () -> Unit,
    onSimulatorModeChange: (Boolean) -> Unit,
    onSimulatorPortChange: (Int) -> Unit
) {
    val s = uiState.settings
    val trafficEvents by UsbTrafficLog.events.collectAsState()
    val btEvents by ObdLinkTrafficLog.events.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // SAF "create document" save: Export writes the log to a folder the user
    // picks (Downloads/Files) via the system dialog. pendingCsv holds it until
    // the picker returns.
    var pendingCsv by remember { mutableStateOf("") }
    val saveCsvLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(pendingCsv.toByteArray()) }
                android.widget.Toast.makeText(context, "Saved", android.widget.Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                android.widget.Toast.makeText(context, "Save failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    // Merge USB + OBDLink traffic into one time-ordered stream.
    val merged = remember(trafficEvents, btEvents) {
        val lines = ArrayList<LogLine>(trafficEvents.size + btEvents.size)
        for (e in trafficEvents)
            lines.add(LogLine(e.timestampMs, e.direction == TrafficEvent.Direction.OUT, e.hex, e.byteCount, e.ascii))
        for (e in btEvents)
            lines.add(LogLine(e.timestampMs, e.direction == ObdLinkTrafficEvent.Direction.OUT, e.text, e.text.length))
        lines.sortBy { it.ts }
        lines
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Combined raw log (USB + OBDLink), newest at the bottom.
        LogActionRow(
            title = "BYTES",
            onClear = { UsbTrafficLog.clear(); ObdLinkTrafficLog.clear() },
            onCopy = {
                clipboard.setText(AnnotatedString(formatCombined(merged)))
                android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
            },
            onExportCsv = {
                pendingCsv = formatCombined(merged)
                saveCsvLauncher.launch("protocol-traffic.csv")
            },
            titleAsHeader = true
        )
        CombinedLogCard(merged)

        Spacer(modifier = Modifier.height(8.dp))

        // Simulator — routes the live-data flow over a localhost TCP socket to
        // the host-side VIPER emulator (adb reverse tcp:<port> tcp:<port>).
        CategoryHeader("SIMULATOR")
        Switch(
            checked = s.simulatorMode,
            onCheckedChange = onSimulatorModeChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Accent,
                uncheckedThumbColor = InkMuted,
                uncheckedTrackColor = SurfaceAlt,
                uncheckedBorderColor = BorderGray
            )
        )
        var portField by remember(s.simulatorPort) { mutableStateOf(s.simulatorPort.toString()) }
        OutlinedTextField(
            value = portField,
            onValueChange = { raw ->
                val digits = raw.filter { it.isDigit() }.take(5)
                portField = digits
                digits.toIntOrNull()?.let(onSimulatorPortChange)
            },
            label = {
                Text(
                    "TCP PORT",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
}

private data class LogLine(
    val ts: Long,
    val isOut: Boolean,
    val payload: String,        // hex (USB) or ASCII text (OBDLink)
    val byteCount: Int? = null, // USB only
    val ascii: String? = null   // USB only — printable rendering of the bytes
)

@Composable
private fun CombinedLogCard(lines: List<LogLine>) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14161A)),
        border = BorderStroke(1.dp, BorderGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        val listState = rememberLazyListState()
        // Auto-scroll to the newest line so the live tail is always visible.
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
        }
        if (lines.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(380.dp).padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No traffic yet. Turn on Simulator and Read Live Data.",
                    color = NeutralGray,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            return@Card
        }
        SelectionContainer {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxWidth().height(380.dp).padding(8.dp)
            ) {
                items(lines) { line -> CombinedLogRow(line) }
            }
        }
    }
}

@Composable
private fun CombinedLogRow(line: LogLine) {
    val arrow = if (line.isOut) "→" else "←"
    val arrowColor = if (line.isOut) Accent else PassGreen
    // OpenPort-style layout: compact header (time · arrow · size) on top, then
    // the payload on its own full-width line so the timestamp doesn't eat into
    // the hex. ascii (USB only) sits under the hex when it's meaningful.
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = trafficTimeFmt.format(Date(line.ts)),
                color = NeutralGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
            Text(
                text = "  $arrow  ",
                color = arrowColor,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp
            )
            if (line.byteCount != null) {
                Text(
                    text = "${line.byteCount}B",
                    color = NeutralGray,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp
                )
            }
        }
        Text(
            text = line.payload,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
        val ascii = line.ascii
        if (ascii != null && ascii.isNotBlank() && ascii.any { it.isLetterOrDigit() }) {
            Text(
                text = ascii,
                color = NeutralGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

private val trafficTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun formatCombined(lines: List<LogLine>): String =
    lines.joinToString("\n") { l ->
        val arrow = if (l.isOut) "->" else "<-"
        "${trafficTimeFmt.format(Date(l.ts))} $arrow ${l.payload}"
    }
