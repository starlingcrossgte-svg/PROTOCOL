package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
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
    onSimulatorPortChange: (Int) -> Unit,
    onAutoInitChange: (Boolean) -> Unit,
    onSelectInitSequence: (String) -> Unit,
    onKlineContinuousTest: () -> Unit
) {
    val s = uiState.settings
    val trafficEvents by UsbTrafficLog.events.collectAsState()
    val btEvents by ObdLinkTrafficLog.events.collectAsState()
    val context = LocalContext.current
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
        // Combined raw log (USB + OBDLink), newest at the bottom. Title sits
        // above the log; Clear / Export are a tab on the log's inner top-right.
        CategoryHeader("RAW BYTES", startPadding = 8.dp)
        CombinedLogCard(
            lines = merged,
            onClear = { UsbTrafficLog.clear(); ObdLinkTrafficLog.clear() },
            onExportCsv = {
                pendingCsv = formatCombined(merged)
                saveCsvLauncher.launch("protocol-traffic.csv")
            }
        )

        // Manual command console — type any raw AT/ST/SSM2 command; it's sent to
        // the OBDLink and the reply shows in the RAW BYTES log above. Lets you
        // drive the adapter one command at a time (resets/wake like ATWS·ATZ·ATI,
        // or init/protocol probing) without a code change.
        CategoryHeader("ELM327 MANUAL COMMANDS", startPadding = 8.dp)
        ManualCommandRow(onSend = onSendManualCommand)

        // Adapter init — choose a saved command-library sequence and toggle
        // whether the OBDLink connects with it instead of the built-in default.
        CategoryHeader("ADAPTER INIT", startPadding = 8.dp)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = s.autoInitEnabled,
                onCheckedChange = onAutoInitChange,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = AccentDim,
                    uncheckedThumbColor = InkMuted,
                    uncheckedTrackColor = SurfaceAlt,
                    uncheckedBorderColor = BorderGray
                )
            )
            Text(
                if (s.autoInitEnabled) "Auto Init ON — uses selected sequence"
                else "Auto Init OFF — built-in default init",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
        SequenceSelector(selectedId = s.selectedInitSequenceId, onSelect = onSelectInitSequence)

        // K-line CONTINUOUS test — fires the on-page A8 01 burst (built from the
        // poller's own addresses, so no typos) and reports how many frames the
        // STN streamed back. >1 = continuous works (the OpenPort path). Result
        // lands in the RAW BYTES log above.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SurfaceBg)
                .border(1.dp, Accent, RoundedCornerShape(8.dp))
                .clickable { onKlineContinuousTest() }
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "K-LINE CONTINUOUS TEST",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Simulator — routes the live-data flow over a localhost TCP socket to
        // the host-side VIPER emulator (adb reverse tcp:<port> tcp:<port>).
        // Header sits right under the log; the toggle and the TCP PORT field
        // share one row so the whole section stays on a single screen.
        CategoryHeader("SIMULATOR", startPadding = 8.dp)
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
    var paletteOpen by remember { mutableStateOf(false) }
    // ONE bordered bar split by a solid white divider, like the Live Data action
    // bar but with plain rounded corners (no angular cutouts): [ SEND | type box ].
    // The input is borderless so the bar's Accent border is the only outline.
    // The tappable command palette sits directly beneath it: tap a command and
    // it drops straight into the box so you can fire them one-by-one rapidly.
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .clip(shape)
            .background(SurfaceBg, shape)
            .border(1.dp, Accent, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .clickable { if (cmd.isNotBlank()) onSend(cmd.trim()) }
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "SEND",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall
            )
        }
        // Solid white divider — the Live Data action bar's segment separator.
        Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White))
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .padding(horizontal = 14.dp, vertical = 14.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            BasicTextField(
                value = cmd,
                onValueChange = { cmd = it },
                singleLine = true,
                textStyle = TextStyle(
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                ),
                cursorBrush = SolidColor(Accent),
                // Raw command box: kill autocorrect / autocapitalize so terse
                // AT/ST commands with spaces/punctuation (e.g. "STPX d:...,r:1,x:7")
                // reach the adapter verbatim instead of mangled.
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Ascii,
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.None
                ),
                modifier = Modifier.fillMaxWidth(),
                decorationBox = { inner ->
                    if (cmd.isEmpty()) {
                        Text(
                            "COMMAND",
                            color = NeutralGray,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                    }
                    inner()
                }
            )
        }
    }
        CommandPalette(
            open = paletteOpen,
            onToggle = { paletteOpen = !paletteOpen },
            onPick = { cmd = it }
        )
    }
}

// Scrollable, tappable command palette beneath the manual command box. A tap
// drops the command straight into the box (it does NOT auto-send and does NOT
// close, so you can pick → SEND → pick again rapidly). Sourced from
// AdapterCommandLibrary.QUICK_COMMANDS — add entries there to grow the list.
@Composable
private fun CommandPalette(
    open: Boolean,
    onToggle: () -> Unit,
    onPick: (String) -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, BorderGray, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .background(SurfaceAlt)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                "QUICK COMMANDS",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                if (open) "▴" else "▾",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
        if (open) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .background(SurfaceBg)
            ) {
                items(com.protocol.app.obdlink.AdapterCommandLibrary.QUICK_COMMANDS) { qc ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(qc.command) }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            qc.command,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            qc.label,
                            color = NeutralGray,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        }
    }
}

// Expandable single-select dropdown for the init-sequence library. Shows the
// selected sequence's name; tapping expands the list (name + description), and
// picking one persists it and collapses. Small list (4), so a plain Column.
@Composable
private fun SequenceSelector(selectedId: String?, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val all = com.protocol.app.obdlink.AdapterCommandLibrary.ALL
    val selected = com.protocol.app.obdlink.AdapterCommandLibrary.byId(selectedId)
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Accent, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .background(SurfaceBg)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                selected?.name ?: "Select init sequence",
                color = if (selected != null) Color.White else NeutralGray,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                if (open) "▴" else "▾",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
        if (open) {
            Column(
                modifier = Modifier.fillMaxWidth().background(SurfaceAlt)
            ) {
                all.forEach { seq ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(seq.id); open = false }
                            .padding(horizontal = 14.dp, vertical = 9.dp)
                    ) {
                        Text(
                            seq.name,
                            color = if (seq.id == selectedId) Accent else Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                        Text(
                            seq.description,
                            color = NeutralGray,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }
    }
}

// One thin segment of the RAW BYTES log's top-right action tab. White label,
// small padding so the tab stays slim. Mirrors the Live Data SegmentButton.
@Composable
private fun LogTabButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall
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
private fun CombinedLogCard(
    lines: List<LogLine>,
    onClear: () -> Unit,
    onExportCsv: () -> Unit
) {
    // Sized so the RAW BYTES stream is the page body with the ELM327 MANUAL
    // COMMANDS title (but not the command box) just visible below it without
    // scrolling. The extra subtraction vs the old layout is the height the RAW
    // BYTES title now occupies above the card. Adaptive to screen height.
    val logHeight = (LocalConfiguration.current.screenHeightDp - 374).coerceAtLeast(300).dp
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14161A)),
        border = BorderStroke(1.dp, BorderGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        // Clear / Export as one folder-tab attached to the log's inner top-right
        // corner: only the bottom-left corner is rounded, the top + right edges
        // sit flush against the log. Filled (no border) so it reads clean.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            val tabShape = RoundedCornerShape(bottomStart = 10.dp)
            Row(
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    .clip(tabShape)
                    .background(SurfaceAlt, tabShape),
                verticalAlignment = Alignment.CenterVertically
            ) {
                LogTabButton("Clear", onClear)
                Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White))
                LogTabButton("Export", onExportCsv)
            }
        }
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
