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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.protocol.app.obdlink.ObdLinkTrafficEvent
import com.protocol.app.obdlink.ObdLinkTrafficLog
import com.protocol.app.openport2.TrafficEvent
import com.protocol.app.openport2.UsbTrafficLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Developer Mode sub-page. Home for all low-level diagnostic surfaces:
//
//   - Test SSM2 Probe button + outcome card (moved here from Home)
//   - Per-step run log (formerly the dev panel on Home, gated by devMode)
//   - Live USB bulk-transfer traffic log (every byte going to/from the
//     adapter, as it happens) — sourced from UsbTrafficLog (singleton)
//
// Only accessible when Settings → Developer Mode is on; the Home menu's
// "Developer" entry shows up under the same gate.

@Composable
internal fun DeveloperBody(
    uiState: ProtocolUiState,
    onRunProbe: () -> Unit,
    onClearProbeLog: () -> Unit,
    onCopyProbeLog: () -> Unit,
    onExportProbeLog: () -> Unit,
    onHuntKlineInit: () -> Unit
) {
    val trafficEvents by UsbTrafficLog.events.collectAsState()
    val btEvents by ObdLinkTrafficLog.events.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // SAF "create document" save: the Export buttons write the CSV to a folder
    // the user picks (Downloads/Files) via the system dialog, instead of a
    // share-only sheet. pendingCsv holds the content until the picker returns.
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ── Probe section ─────────────────────────────────────────
        CategoryHeader("SSM2 PROBE")
        Button(
            onClick = onRunProbe,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF6A00),
                contentColor = Color.Black,
                disabledContainerColor = Color(0xFFFF6A00).copy(alpha = 0.5f),
                disabledContentColor = Color.Black.copy(alpha = 0.7f)
            ),
            shape = y2kCornerShape(),
            enabled = !uiState.isReadingLive && !uiState.isLogging && !uiState.isRunningProbe,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (uiState.isRunningProbe) "Probing..." else "Test SSM2 Probe",
                fontWeight = FontWeight.Bold
            )
        }

        // Outcome card surfaces here (was on Home before this commit).
        OutcomeCard(uiState)

        // Per-step run log — uniform Clear / Export CSV row + the log card.
        LogActionRow(
            title = "Probe Log",
            onClear = onClearProbeLog,
            onExportCsv = onExportProbeLog
        )
        SelectionContainer { RunLogCard(uiState.log) }

        Spacer(modifier = Modifier.height(8.dp))

        // ── K-line init hunt (3.0R / EZ30R) ───────────────────────
        // Basic-connects the MX+ then cycles candidate STN/ELM K-line inits
        // until the ECU answers SSM2. Watch the OBDLINK BT TRAFFIC log below.
        CategoryHeader("K-LINE INIT HUNT (3.0R)")
        Button(
            onClick = onHuntKlineInit,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2F6FE4),
                contentColor = Color.White
            ),
            shape = y2kCornerShape(),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Hunt K-line Init", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ── USB traffic section ───────────────────────────────────
        CategoryHeader("USB TRAFFIC")
        LogActionRow(
            title = "USB Traffic (${trafficEvents.size})",
            onClear = { UsbTrafficLog.clear() },
            onCopy = {
                clipboard.setText(AnnotatedString(formatUsbLog(trafficEvents)))
                android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
            },
            onExportCsv = {
                pendingCsv = formatUsbLog(trafficEvents)
                saveCsvLauncher.launch("protocol-usb-traffic.csv")
            }
        )
        SelectionContainer { TrafficLogCard(trafficEvents) }

        Spacer(modifier = Modifier.height(8.dp))

        // ── OBDLink Bluetooth traffic ──────────────────────────────
        // ELM/STN ASCII exchanges over the MX+ (handshake + SSM2-over-CAN
        // polling). The in-app replacement for Wireshark, which USBPcap
        // keeps blocking on the OBDLink. Sourced from ObdLinkTrafficLog.
        CategoryHeader("OBDLINK BT TRAFFIC")
        LogActionRow(
            title = "OBDLink BT (${btEvents.size})",
            onClear = { ObdLinkTrafficLog.clear() },
            onCopy = {
                clipboard.setText(AnnotatedString(formatBtLog(btEvents)))
                android.widget.Toast.makeText(context, "Copied to clipboard", android.widget.Toast.LENGTH_SHORT).show()
            },
            onExportCsv = {
                pendingCsv = formatBtLog(btEvents)
                saveCsvLauncher.launch("protocol-obdlink-bt.csv")
            }
        )
        ObdLinkTrafficLogCard(btEvents)
    }
}

@Composable
private fun ObdLinkTrafficLogCard(events: List<ObdLinkTrafficEvent>) {
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
                modifier = Modifier.fillMaxWidth().height(300.dp).padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No OBDLink traffic yet. Turn on Bluetooth (OBDLink) and read live data.",
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
                modifier = Modifier.fillMaxWidth().height(300.dp).padding(8.dp)
            ) {
                items(events) { event -> ObdLinkTrafficEventRow(event) }
            }
        }
    }
}

@Composable
private fun ObdLinkTrafficEventRow(event: ObdLinkTrafficEvent) {
    val isOut = event.direction == ObdLinkTrafficEvent.Direction.OUT
    val arrow = if (isOut) "→" else "←"
    val arrowColor = if (isOut) Accent else PassGreen
    val ts = trafficTimeFmt.format(Date(event.timestampMs))
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(text = ts, color = NeutralGray, fontFamily = FontFamily.Monospace, fontSize = 10.sp)
        Text(
            text = "  $arrow  ",
            color = arrowColor,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp
        )
        Text(
            text = event.text,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TrafficLogCard(events: List<TrafficEvent>) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14161A)),
        border = BorderStroke(1.dp, BorderGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        val listState = rememberLazyListState()
        // Auto-scroll to the newest event whenever a new one lands so the
        // user always sees the live tail without manual scrolling.
        LaunchedEffect(events.size) {
            if (events.isNotEmpty()) {
                listState.animateScrollToItem(events.size - 1)
            }
        }
        if (events.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(380.dp).padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "No USB traffic recorded yet. Start a probe or read live data to populate.",
                    color = NeutralGray,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            }
            return@Card
        }
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .padding(8.dp)
        ) {
            items(events) { event -> TrafficEventRow(event) }
        }
    }
}

@Composable
private fun TrafficEventRow(event: TrafficEvent) {
    val arrow = if (event.direction == TrafficEvent.Direction.OUT) "→" else "←"
    val arrowColor = if (event.direction == TrafficEvent.Direction.OUT) Accent else PassGreen
    val ts = trafficTimeFmt.format(Date(event.timestampMs))
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ts,
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
            Text(
                text = "${event.byteCount}B",
                color = NeutralGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
        Text(
            text = event.hex,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 8.dp)
        )
        if (event.ascii.isNotBlank() && event.ascii.any { it.isLetterOrDigit() }) {
            Text(
                text = event.ascii,
                color = NeutralGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

private val trafficTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

private fun formatBtLog(events: List<ObdLinkTrafficEvent>): String =
    events.joinToString("\n") { e ->
        val arrow = if (e.direction == ObdLinkTrafficEvent.Direction.OUT) "->" else "<-"
        "${trafficTimeFmt.format(Date(e.timestampMs))} $arrow ${e.text}"
    }

private fun formatUsbLog(events: List<TrafficEvent>): String =
    events.joinToString("\n") { e ->
        val dir = if (e.direction == TrafficEvent.Direction.OUT) "OUT" else "IN"
        "${trafficTimeFmt.format(Date(e.timestampMs))},$dir,${e.byteCount},${e.hex},${e.ascii}"
    }

