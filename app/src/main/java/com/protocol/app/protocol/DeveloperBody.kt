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
    onExportProbeLog: () -> Unit
) {
    val trafficEvents by UsbTrafficLog.events.collectAsState()
    val btEvents by ObdLinkTrafficLog.events.collectAsState()
    val context = LocalContext.current
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

        // ── USB traffic section ───────────────────────────────────
        CategoryHeader("USB TRAFFIC")
        LogActionRow(
            title = "USB Traffic (${trafficEvents.size})",
            onClear = { UsbTrafficLog.clear() },
            onExportCsv = { shareCsv(context, "protocol-usb-traffic.csv", formatUsbLog(trafficEvents)) }
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
            onExportCsv = { shareCsv(context, "protocol-obdlink-bt.csv", formatBtLog(btEvents)) }
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

/** Write [content] to a CSV in cache and fire a share chooser. Used by the
 *  USB / BT logs' Export CSV button (the others export via Activity callbacks). */
private fun shareCsv(context: android.content.Context, fileName: String, content: String) {
    try {
        val dir = java.io.File(context.cacheDir, "exports").apply { if (!exists()) mkdirs() }
        val file = java.io.File(dir, fileName).apply { writeText(content) }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.fileprovider", file
        )
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(android.content.Intent.EXTRA_STREAM, uri)
            putExtra(android.content.Intent.EXTRA_TITLE, fileName)
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(android.content.Intent.createChooser(send, "Export $fileName"))
    } catch (e: Exception) {
        android.widget.Toast.makeText(
            context, "Export failed: ${e.message}", android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
