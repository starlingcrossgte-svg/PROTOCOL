package com.protocol.app.protocol

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Page 1 — Live Data. The active polling page: snap grid of gauges
// driven by uiState.gaugeLayout, mode buttons for Read/Log Live, and
// the session log table beneath. Hardware back exits edit mode if
// active; otherwise propagates normally.

@Composable
internal fun LiveDataPage(
    uiState: ProtocolUiState,
    onStartReadingLive: () -> Unit,
    onStopReadingLive: () -> Unit,
    onStartLogging: () -> Unit,
    onStopLogging: () -> Unit,
    onClearSessionLog: () -> Unit,
    onCopySessionLog: () -> Unit,
    onExportSessionLog: () -> Unit,
    onEnterEditMode: () -> Unit,
    onExitEditMode: () -> Unit,
    onRemoveGauge: (String) -> Unit,
    onResizeGauge: (String, Int, Int, Int, Int) -> Boolean
) {
    // In edit mode, hardware back exits edit instead of leaving the app, and
    // the page scroll is locked so vertical drags on the gauges feed the drag
    // bars rather than scrolling the page out from under them.
    BackHandler(enabled = uiState.editMode) { onExitEditMode() }

    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState, enabled = !uiState.editMode)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Mode buttons — slim row, just under the header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Read button. Always tappable (modulo probe in-flight).
            //   idle             → "Read Live Data"             → start reading only
            //   reading only     → "Stop Reading"               → stop reading
            //   reading+logging  → "Stop Reading + Logging"     → stop both
            ModeButton(
                label = when {
                    uiState.isLogging -> "Stop Reading + Logging"
                    uiState.isReadingLive -> "Stop Reading"
                    else -> "Read Live Data"
                },
                active = uiState.isReadingLive,
                enabled = !uiState.isRunningProbe,
                onClick = {
                    if (uiState.isReadingLive) onStopReadingLive() else onStartReadingLive()
                },
                modifier = Modifier.weight(1f)
            )
            // Log button. Always tappable (modulo probe in-flight).
            //   idle             → "Read + Log Live"            → start reading + logging
            //   reading only     → "Log Live Data"              → add logging on top
            //   logging (both)   → "Stop Logging"               → stop logging only,
            //                                                     keep reading running
            ModeButton(
                label = when {
                    uiState.isLogging -> "Stop Logging"
                    uiState.isReadingLive -> "Log Live Data"
                    else -> "Read + Log Live"
                },
                active = uiState.isLogging,
                enabled = !uiState.isRunningProbe,
                onClick = {
                    if (uiState.isLogging) onStopLogging() else onStartLogging()
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Gauges grid — absolute-positioned snap grid. Long-press a gauge to
        // enter edit mode (drag bars on the four sides resize by 1 cell each;
        // center X removes the gauge). Tapping any empty grid area exits edit.
        SnapGaugeGrid(
            uiState = uiState,
            onEnterEditMode = onEnterEditMode,
            onExitEditMode = onExitEditMode,
            onRemoveGauge = onRemoveGauge,
            onResizeGauge = onResizeGauge
        )

        // Status line — when a gauge was tapped recently, show its full
        // parameter name (set by the ViewModel for 5s, then cleared);
        // otherwise fall back to the connection / activity status.
        val statusLine = uiState.tappedParamLongName ?: uiState.statusMessage
        if (statusLine.isNotBlank()) {
            Text(
                text = statusLine,
                color = InkPrimary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Log action row — slim, just above the log
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Session Log (${uiState.sessionLog.size})",
                color = InkPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            SmallLogButton("Clear", onClearSessionLog)
            SmallLogButton("Copy", onCopySessionLog)
            SmallLogButton("Export CSV", onExportSessionLog)
        }

        SessionLogCard(uiState)
    }
}

@Composable
private fun ModeButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            // Active state fills with the Accent burnt-orange so a running
            // Read/Log press reads as the orange "Stop" press; idle stays
            // SurfaceBg with the orange Accent outline (matches the home
            // menu / probe button so the whole app reads as one family).
            containerColor = if (active) Accent else SurfaceBg,
            contentColor = Color.White,
            disabledContainerColor = SurfaceBg.copy(alpha = 0.5f),
            disabledContentColor = NeutralGray
        ),
        shape = y2kCornerShape(),
        border = BorderStroke(1.dp, Accent),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = modifier
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun SmallLogButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceBg,
            contentColor = Color.White
        ),
        shape = y2kCornerShape(),
        border = BorderStroke(1.dp, Accent),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SessionLogCard(uiState: ProtocolUiState) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14161A)),
        border = BorderStroke(1.dp, BorderGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(300.dp)
                .padding(10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Text(
                    text = ProtocolLogFormatter.formatSessionLogCleanText(
                        uiState.sessionLog,
                        uiState.pidIdsOnLiveData
                    ),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    softWrap = false
                )
            }
        }
    }
}
