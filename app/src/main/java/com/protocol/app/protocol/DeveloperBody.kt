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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.runtime.mutableStateListOf
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.protocol.app.obdlink.AdapterCommandLibrary
import com.protocol.app.obdlink.CommandFamily
import com.protocol.app.obdlink.CommandKind
import com.protocol.app.obdlink.CommandTier
import com.protocol.app.obdlink.ObdLinkTrafficEvent
import com.protocol.app.obdlink.ObdLinkTrafficLog
import com.protocol.app.openport2.TrafficEvent
import com.protocol.app.openport2.UsbTrafficLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Developer Mode content — a raw-control console, rendered inline on Home when
// Settings → Developer Mode is on. Sections:
//
//   - RAW BYTES: one combined, time-ordered traffic log (USB/OpenPort +
//     OBDLink byte streams). Only one transport runs at a time.
//   - MANUAL COMMANDS: type any command; it's auto-formatted and routed to the
//     SELECTED adapter — ELM/STN ASCII for OBDLink, the Tactrix line protocol
//     (with att framing for hex) for OpenPort. The palette filters to the
//     active adapter and tags each entry TEXT vs HEX.
//   - ADAPTER INIT: pick/toggle a command-library init sequence (ELM/STN only).
//   - K-LINE CONTINUOUS / CAN MONITOR: on-bus diagnostics.
//   - SIMULATOR: route live data to the host-side VIPER emulator over TCP.

@Composable
internal fun DeveloperBody(
    uiState: ProtocolUiState,
    onSendManualCommand: (String) -> Unit,
    onSimulatorModeChange: (Boolean) -> Unit,
    onSimulatorPortChange: (Int) -> Unit,
    onDevModeChange: (Boolean) -> Unit,
    onSelectInitSequence: (String?) -> Unit,
    onKlineContinuousTest: () -> Unit,
    onStartCanMonitor: () -> Unit,
    onStopCanMonitor: () -> Unit,
    onSelectAdapter: (Adapter?) -> Unit,
    onSelectProtocol: (BusProtocol?) -> Unit,
    onConnectAdapter: () -> Unit,
    onRunSequence: (List<String>, List<Long>, (Int, String) -> Unit) -> Unit
) {
    val s = uiState.settings
    // Which command set the console drives. The dev console talks to exactly one
    // adapter at a time, so the palette + manual router key off the selection.
    // Three real sets: OpenPort / KKL (FT232RL) / ELM (both OBDLinks).
    val family = when (s.adapter) {
        Adapter.OpenPort -> CommandFamily.OpenPort
        Adapter.Ft232rl -> CommandFamily.Kkl
        else -> CommandFamily.ELM
    }
    // The old in-page master toggle is gone. Opening the Dev Mode page enables
    // devMode (so dev-gated features like the Live Data PID presets keep
    // working) and the RAW BYTES log is always live here.
    LaunchedEffect(Unit) { onDevModeChange(true) }
    // Transport log (USB + OBDLink) + its CSV export — both shared with the Flash
    // silo via the helpers below, so the two pages render the same TRANSPORT card.
    val merged = rememberMergedTransportLog()
    val exportLog = rememberTransportLogExport()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // imePadding INSIDE the scroll: adds keyboard-height room at the
            // content bottom so a focused low field can scroll clear, without the
            // dead band the outside-scroll placement produced. Free scroll, no
            // forced bring-into-view fighting the gesture.
            .imePadding()
            // Top trimmed to 4dp (from 8) so the page rides ~4dp higher after the
            // CONFIGURATION header was removed — recovers the clipped bottom without
            // clipping the top. Bottom stays 8dp for breathing room.
            .padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Configuration (no header label): adapter, bus, emulator, init. Selecting
        // does NOT connect — the CONNECT button (below the log) does. Tap a selected
        // adapter/bus again to clear it.
        AdapterGrid(selected = s.adapter, onSelect = onSelectAdapter)
        ProtocolToggle(selected = s.protocol, onSelect = onSelectProtocol)
        EmulatorDropdown(
            on = s.simulatorMode,
            port = s.simulatorPort,
            onSetOn = onSimulatorModeChange,
            onSetPort = onSimulatorPortChange
        )
        InitDropdown(
            selectedId = s.selectedInitSequenceId,
            protocol = s.protocol,
            onSelect = onSelectInitSequence
        )

        // Combined transport log (USB + OBDLink), newest at the bottom. The
        // "TRANSPORT" label + Clear / Export live inside the card's top row
        // (no separate header above it).
        CombinedLogCard(
            lines = merged,
            onClear = { UsbTrafficLog.clear(); ObdLinkTrafficLog.clear() },
            onExportCsv = { exportLog(merged, "protocol-traffic.csv") }
        )

        // Action buttons below the log, above the manual command box — two rows
        // of two. CONNECT runs the handshake for the selected adapter (or primes
        // the emulator); STOP halts the active stream/monitor. CONTINUOUS TEST
        // fires an A8 01 burst and counts the streamed frames; CAN MONITOR starts
        // the listen-only broadcast sniff. Both report into the RAW BYTES log.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DevActionButton("CONNECT", Modifier.weight(1f), border = Accent) { onConnectAdapter() }
            DevActionButton("STOP", Modifier.weight(1f), border = BorderGray) { onStopCanMonitor() }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DevActionButton("CONTINUOUS TEST", Modifier.weight(1f), border = Accent) { onKlineContinuousTest() }
            DevActionButton("CAN MONITOR", Modifier.weight(1f), border = Accent) { onStartCanMonitor() }
        }
        // Manual command console — type any command; it's auto-formatted for the
        // SELECTED adapter (ELM/STN ASCII for OBDLink, Tactrix line protocol for
        // OpenPort, raw SSM2 frames for KKL) and the reply lands in the RAW BYTES
        // log above. The empty-box placeholder ("Manual Commands") is the label,
        // so there's no separate header. Palette + hex normalization follow the
        // active adapter family.
        ManualCommandRow(family = family, onSend = onSendManualCommand)

        // Sequence generator (last on the page, no header label): 10 command slots,
        // a ms-delay between commands, Clear, and Send. The label lives in the
        // "10-STEP SEQUENCE GENERATOR" dropdown title.
        SequenceGenerator(onRun = onRunSequence)
    }
}

@Composable
private fun ManualCommandRow(family: CommandFamily, onSend: (String) -> Unit) {
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
                            "Manual Commands",
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
        // Red ✕ clear — only while there's text. Taps wipe the box instantly.
        if (cmd.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .clickable { cmd = "" }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "✕",
                    color = Color(0xFFE53935),
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
        CommandPalette(
            family = family,
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
    family: CommandFamily,
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
            // Group the family's commands by section (SETUP · AT, CONTINUOUS, …)
            // and render one dim header per group, in first-seen order.
            val grouped = remember(family) {
                AdapterCommandLibrary.quickCommandsFor(family).groupBy { it.group }
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp)
                    .background(SurfaceBg)
            ) {
                grouped.forEach { (group, cmds) ->
                    if (group.isNotBlank()) {
                        item(key = "hdr-$group") { InitTierHeader(group) }
                    }
                    items(cmds, key = { it.command }) { qc ->
                        val isHex = qc.kind == CommandKind.HEX_FRAME
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
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            // Kind tag (HEX vs TXT) + the dim hint. HEX is accented
                            // so it's obvious which entries are raw frames vs control
                            // commands — the visible half of the hex/text rule.
                            Text(
                                (if (isHex) "HEX · " else "") + qc.label,
                                color = if (isHex) Accent else NeutralGray,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

// Adapter selector — a 2×2 grid of buttons (replaces the old dropdown). Top row
// OpenPort / OBDLink BT; bottom row OBDLink USB / FT232RL. Selected = bright
// Accent outline+text, unselected = dim; re-tapping the selected one clears it
// (toggle off), matching the K-LINE / CAN bus row below.
@Composable
private fun AdapterGrid(selected: Adapter?, onSelect: (Adapter?) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AdapterButton("OPEN PORT 2.0", Adapter.OpenPort, selected, Modifier.weight(1f), onSelect)
            AdapterButton("OBDLINK MX+", Adapter.OBDLink, selected, Modifier.weight(1f), onSelect)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AdapterButton("OBDLINK EX", Adapter.OBDLinkEx, selected, Modifier.weight(1f), onSelect)
            AdapterButton("FT232RL", Adapter.Ft232rl, selected, Modifier.weight(1f), onSelect)
        }
    }
}

@Composable
private fun AdapterButton(
    label: String,
    adapter: Adapter,
    selected: Adapter?,
    modifier: Modifier,
    onSelect: (Adapter?) -> Unit
) {
    val isSelected = selected == adapter
    val line = if (isSelected) Accent else AccentDim
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceBg)
            .border(1.dp, line, RoundedCornerShape(8.dp))
            .clickable { onSelect(if (isSelected) null else adapter) }
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = line,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// Shared rounded action button (CONNECT / STOP / test actions). Full white
// label, caller-supplied [border] color and [modifier] (weight for side-by-side).
@Composable
internal fun DevActionButton(
    label: String,
    modifier: Modifier,
    border: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceBg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// Init-sequence dropdown. Lists each command-library sequence with its commands
// under the title, grouped by tier. (The CONTINUOUS TEST + CAN MONITOR actions
// are buttons under the log now, not folded in here.)
@Composable
private fun InitDropdown(
    selectedId: String?,
    protocol: BusProtocol?,
    onSelect: (String?) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val lib = com.protocol.app.obdlink.AdapterCommandLibrary
    val selected = lib.byId(selectedId)
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
                selected?.name ?: "INITIATION PRESETS",
                color = if (selected != null) Color.White else NeutralGray,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Red ✕ clears the selected init sequence (like the command box ✕).
                if (selected != null) {
                    Text(
                        "✕",
                        color = Color(0xFFE53935),
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.clickable { onSelect(null) }
                    )
                }
                Text(
                    if (open) "▴" else "▾",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (open) {
            Column(modifier = Modifier.fillMaxWidth().background(SurfaceAlt)) {
                // Only the sequences for the selected protocol (all are ELM/STN;
                // OpenPort uses its own built-in init). Grouped by tier.
                val seqs = lib.ALL.filter {
                    protocol == null || it.kline == (protocol == BusProtocol.KLine)
                }
                listOf(
                    CommandTier.BASIC to "BASIC",
                    CommandTier.ADVANCED to "ADVANCED",
                    CommandTier.AGGRESSIVE to "AGGRESSIVE"
                ).forEach { (tier, label) ->
                    val group = seqs.filter { it.tier == tier }
                    if (group.isNotEmpty()) {
                        InitTierHeader(label)
                        group.forEach { seq ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(if (seq.id == selectedId) null else seq.id); open = false }
                                    .padding(horizontal = 14.dp, vertical = 9.dp)
                            ) {
                                Text(
                                    seq.name,
                                    color = if (seq.id == selectedId) Accent else Color.White,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.sp
                                )
                                // Commands listed under the title.
                                Text(
                                    seq.steps.joinToString("  ·  ") { it.command },
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
    }
}

// One thin segment of the RAW BYTES log's top-right action tab. White label,
// small padding so the tab stays slim. Mirrors the Live Data SegmentButton.
@Composable
internal fun LogTabButton(text: String, onClick: () -> Unit) {
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

internal data class LogLine(
    val ts: Long,
    val isOut: Boolean,
    val payload: String,        // hex (USB) or ASCII text (OBDLink)
    val byteCount: Int? = null, // USB only
    val ascii: String? = null   // USB only — printable rendering of the bytes
)

@Composable
internal fun CombinedLogCard(
    lines: List<LogLine>,
    onClear: () -> Unit,
    onExportCsv: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    // null → the log fills the card's remaining height (scrollable); a value →
    // fixed compact inline height (the Dev console default).
    compactHeight: Dp? = 200.dp
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14161A)),
        border = BorderStroke(1.dp, BorderGray),
        modifier = modifier
    ) {
        // "TRANSPORT" label on the left + the Clear / Export folder-tab on
        // the log's inner top-right corner (replaces the old header above the card).
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "TRANSPORT",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 12.dp)
            )
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
        if (compactHeight != null) {
            LogList(lines = lines, height = compactHeight, userScrollEnabled = false)
        } else {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                LogList(lines = lines, height = null, userScrollEnabled = true)
            }
        }
    }
}

// Scrollable, selectable list of log lines, snapped to the newest. Shared by
// the compact card (fixed [height]) and the fullscreen view ([height] = null →
// fills its parent). An instant snap (no animation) avoids fighting the pager's
// horizontal swipe when this page scrolls into view.
@Composable
internal fun LogList(lines: List<LogLine>, height: Dp?, userScrollEnabled: Boolean = true) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
    }
    val sizeMod = if (height != null) Modifier.fillMaxWidth().height(height) else Modifier.fillMaxSize()
    if (lines.isEmpty()) {
        Box(modifier = sizeMod.padding(12.dp), contentAlignment = Alignment.Center) {
            Text(
                "No traffic yet. Connect an adapter (or arm the Emulator) and Read Live Data.",
                color = NeutralGray,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
        return
    }
    SelectionContainer {
        LazyColumn(
            state = listState,
            userScrollEnabled = userScrollEnabled,
            modifier = sizeMod.padding(8.dp)
        ) {
            items(lines) { line -> CombinedLogRow(line) }
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

// Side-by-side K-LINE / CAN-BUS toggle. Selected = bright Accent outline+text,
// unselected = dim Accent. Rounded to match the rest.
@Composable
private fun ProtocolToggle(selected: BusProtocol?, onSelect: (BusProtocol?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Re-tapping the selected bus clears it (toggle off), like the adapter.
        ProtocolToggleButton("K-LINE", selected == BusProtocol.KLine, Modifier.weight(1f)) {
            onSelect(if (selected == BusProtocol.KLine) null else BusProtocol.KLine)
        }
        ProtocolToggleButton("CAN-BUS", selected == BusProtocol.CAN, Modifier.weight(1f)) {
            onSelect(if (selected == BusProtocol.CAN) null else BusProtocol.CAN)
        }
    }
}

@Composable
internal fun ProtocolToggleButton(
    label: String,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val line = if (selected) Accent else AccentDim
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceBg)
            .border(1.dp, line, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = line,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// Rounded emulator dropdown: ON / OFF (highlight on tap) / port type-box /
// ENTER (closes). ON arms the simulator; CONNECT primes it.
@Composable
private fun EmulatorDropdown(
    on: Boolean,
    port: Int,
    onSetOn: (Boolean) -> Unit,
    onSetPort: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    var portField by remember(port) { mutableStateOf(port.toString()) }
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
                if (on) "EMULATOR ON · :$port" else "EMULATOR OFF",
                color = if (on) Accent else NeutralGray,
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
            Column(modifier = Modifier.fillMaxWidth().background(SurfaceAlt)) {
                EmulatorLine("ON", highlighted = on) { onSetOn(true) }
                EmulatorLine("OFF", highlighted = !on) { onSetOn(false) }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "PORT",
                        color = NeutralGray,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                    BasicTextField(
                        value = portField,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }.take(5)
                            portField = digits
                            digits.toIntOrNull()?.let(onSetPort)
                        },
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        ),
                        cursorBrush = SolidColor(Accent),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(SurfaceBg)
                            .border(1.dp, BorderGray, RoundedCornerShape(6.dp))
                            .padding(horizontal = 10.dp, vertical = 8.dp)
                    )
                }
                EmulatorLine("ENTER", highlighted = false) { open = false }
            }
        }
    }
}

@Composable
private fun EmulatorLine(label: String, highlighted: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (highlighted) Accent else Color.White,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp)
    )
}

// Manual sequence generator: 10 command slots (each with its own response
// line), a ms-delay between commands, a wait-for-prompt toggle, Clear, and Send.
// Send fires every non-blank slot top→bottom on the configured adapter via
// [onRun], which reports each reply back into that slot's response line.
@Composable
private fun SequenceGenerator(
    onRun: (List<String>, List<Long>, (Int, String) -> Unit) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val commands = remember { mutableStateListOf("", "", "", "", "", "", "", "", "", "") }
    val responses = remember { mutableStateListOf("", "", "", "", "", "", "", "", "", "") }
    val delays = remember { mutableStateListOf("", "", "", "", "", "", "", "", "", "") }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
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
                    "10-STEP SEQUENCE GENERATOR",
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
                Column(
                    modifier = Modifier.fillMaxWidth().background(SurfaceAlt).padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    for (i in 0 until 10) {
                        SequenceRow(
                            index = i + 1,
                            command = commands[i],
                            response = responses[i],
                            delay = delays[i],
                            onCommandChange = { commands[i] = it },
                            onDelayChange = { delays[i] = it }
                        )
                    }
                }
            }
        }
        // CLEAR / SEND — outside the dropdown, like the buttons under the log.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            DevActionButton("CLEAR", Modifier.weight(1f), border = BorderGray) {
                for (i in 0 until 10) { commands[i] = ""; responses[i] = ""; delays[i] = "" }
            }
            DevActionButton("SEND", Modifier.weight(1f), border = Accent) {
                for (i in 0 until 10) responses[i] = ""
                onRun(commands.toList(), delays.map { it.toLongOrNull() ?: 0L }) { idx, r ->
                    if (idx in 0 until 10) responses[idx] = r
                }
            }
        }
    }
}

@Composable
private fun SequenceRow(
    index: Int,
    command: String,
    response: String,
    delay: String,
    onCommandChange: (String) -> Unit,
    onDelayChange: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        // Command input box.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceBg)
                .border(1.dp, BorderGray, RoundedCornerShape(6.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "%2d".format(index),
                color = NeutralGray,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(BorderGray))
            Box(modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 10.dp)) {
                BasicTextField(
                    value = command,
                    onValueChange = onCommandChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    ),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        autoCorrect = false,
                        capitalization = KeyboardCapitalization.None
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        if (command.isEmpty()) {
                            Text(
                                "paste or type command",
                                color = NeutralGray,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                        inner()
                    }
                )
            }
        }
        // Reply box (long) + delay box (narrow) side by side, same height. The
        // reply shows the adapter's human-readable reply; raw bytes -> main log.
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFF14161A))
                    .border(1.dp, BorderGray, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Text(
                    if (response.isEmpty()) "reply" else response,
                    color = if (response.isEmpty()) NeutralGray else PassGreen,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    maxLines = 2
                )
            }
            Row(
                modifier = Modifier
                    .width(94.dp)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(6.dp))
                    .background(SurfaceBg)
                    .border(1.dp, BorderGray, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = delay,
                    onValueChange = { raw -> onDelayChange(raw.filter { it.isDigit() }.take(6)) },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    ),
                    cursorBrush = SolidColor(Accent),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    decorationBox = { inner ->
                        if (delay.isEmpty()) {
                            Text("delay", color = NeutralGray, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                        inner()
                    },
                    modifier = Modifier.weight(1f)
                )
                Text("ms", color = NeutralGray, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
            }
        }
    }
}

// Small dim-caps tier header inside the init dropdown (BASIC / ADVANCED / …).
@Composable
private fun InitTierHeader(label: String) {
    Text(
        label,
        color = AccentDim,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        modifier = Modifier.padding(start = 14.dp, top = 10.dp, bottom = 2.dp)
    )
}

private val trafficTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun formatCombined(lines: List<LogLine>): String =
    lines.joinToString("\n") { l ->
        val arrow = if (l.isOut) "->" else "<-"
        "${trafficTimeFmt.format(Date(l.ts))} $arrow ${l.payload}"
    }

// ── Shared TRANSPORT-log infrastructure ─────────────────────────────────────
// Both the Dev console and the Flash silo render the same combined log + Export.
// These helpers keep that single source of truth in one place so neither page
// duplicates the snapshot/merge or the SAF save plumbing.

/** Process-wide USB + OBDLink traffic merged into one time-ordered list.
 *  Recomposes only when either ring buffer's revision bumps; the O(n) snapshot
 *  copy stays off the wire-recording hot path. */
@Composable
internal fun rememberMergedTransportLog(): List<LogLine> {
    val usbRevision by UsbTrafficLog.revision.collectAsState()
    val btRevision by ObdLinkTrafficLog.revision.collectAsState()
    return remember(usbRevision, btRevision) {
        val trafficEvents = UsbTrafficLog.snapshot()
        val btEvents = ObdLinkTrafficLog.snapshot()
        val lines = ArrayList<LogLine>(trafficEvents.size + btEvents.size)
        for (e in trafficEvents)
            lines.add(LogLine(e.timestampMs, e.direction == TrafficEvent.Direction.OUT, e.hex, e.byteCount, e.ascii))
        for (e in btEvents)
            lines.add(LogLine(e.timestampMs, e.direction == ObdLinkTrafficEvent.Direction.OUT, e.text, e.text.length))
        lines.sortBy { it.ts }
        lines
    }
}

/** SAF "create document" CSV export for a transport log. Returns a lambda: call
 *  it with the lines to save + a suggested filename, and the system save dialog
 *  picks the destination (Downloads/Files). */
@Composable
internal fun rememberTransportLogExport(): (List<LogLine>, String) -> Unit {
    val context = LocalContext.current
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
    return { lines, name ->
        pendingCsv = formatCombined(lines)
        saveCsvLauncher.launch(name)
    }
}
