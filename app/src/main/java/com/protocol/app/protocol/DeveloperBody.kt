package com.protocol.app.protocol

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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
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
    onSendManualCommand: (String) -> Unit,
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
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Combined raw log (USB + OBDLink), newest at the bottom.
        LogActionRow(
            title = "RAW BYTES",
            onClear = { UsbTrafficLog.clear(); ObdLinkTrafficLog.clear() },
            onCopy = {
                clipboard.setText(AnnotatedString(formatCombined(merged)))
                android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
            },
            onExportCsv = {
                pendingCsv = formatCombined(merged)
                saveCsvLauncher.launch("protocol-traffic.csv")
            },
            titleAsHeader = true,
            clearShape = y2kLeftCutShape(),
            exportShape = y2kRightCutShape()
        )
        CombinedLogCard(merged)

        // Manual command console — type any raw AT/ST/SSM2 command; it's sent to
        // the OBDLink and the reply shows in the RAW BYTES log above. Lets you
        // drive the adapter one command at a time (resets/wake like ATWS·ATZ·ATI,
        // or init/protocol probing) without a code change.
        CategoryHeader("ELM327 MANUAL COMMANDS")
        ManualCommandRow(onSend = onSendManualCommand)

        // Simulator — routes the live-data flow over a localhost TCP socket to
        // the host-side VIPER emulator (adb reverse tcp:<port> tcp:<port>).
        // Header sits right under the log; the toggle and the TCP PORT field
        // share one row so the whole section stays on a single screen.
        CategoryHeader("SIMULATOR")
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = s.simulatorMode,
                onCheckedChange = onSimulatorModeChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentDim,
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
                modifier = Modifier.weight(1f),
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
}

@Composable
private fun ManualCommandRow(onSend: (String) -> Unit) {
    var cmd by remember { mutableStateOf("") }
    // SEND lives INSIDE the command field on the left (leadingIcon). Typed text
    // is kept after sending so you can edit one variable and fire again — handy
    // for trying init/protocol combinations.
    OutlinedTextField(
        value = cmd,
        onValueChange = { cmd = it },
        label = {
            Text(
                "COMMAND",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold
            )
        },
        leadingIcon = {
            Box(
                modifier = Modifier
                    .padding(start = 6.dp)
                    .background(SurfaceBg, RoundedCornerShape(6.dp))
                    .border(1.dp, Accent, RoundedCornerShape(6.dp))
                    .clickable { if (cmd.isNotBlank()) onSend(cmd.trim()) }
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Text(
                    "SEND",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                )
            }
        },
        singleLine = true,
        // Raw command box: kill autocorrect / autocapitalize / suggestions so
        // terse AT/ST commands with spaces and punctuation (e.g.
        // "STPX d:...,r:1,x:7") reach the adapter verbatim instead of mangled.
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Ascii,
            autoCorrect = false,
            capitalization = KeyboardCapitalization.None
        ),
        modifier = Modifier.fillMaxWidth(),
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

private data class LogLine(
    val ts: Long,
    val isOut: Boolean,
    val payload: String,        // hex (USB) or ASCII text (OBDLink)
    val byteCount: Int? = null, // USB only
    val ascii: String? = null   // USB only — printable rendering of the bytes
)

@Composable
private fun CombinedLogCard(lines: List<LogLine>) {
    // Fill (almost) the whole screen so the RAW BYTES stream is the body of the
    // page and only the ELM327 command box sits below it at the bottom of the
    // view. Adaptive to screen height so it lands right on the S25 and S23.
    val logHeight = (LocalConfiguration.current.screenHeightDp - 340).coerceAtLeast(300).dp
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14161A)),
        border = BorderStroke(1.dp, BorderGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        val listState = rememberLazyListState()
        // Snap (no animation) to the newest line so the live tail is always
        // visible. An animated scroll races through the whole accumulated list
        // when this page is swiped into view — fighting the pager's horizontal
        // swipe and sometimes wedging it. An instant snap can't fight the swipe.
        LaunchedEffect(lines.size) {
            if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
        }
        if (lines.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(logHeight).padding(12.dp),
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
                modifier = Modifier.fillMaxWidth().height(logHeight).padding(8.dp)
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
