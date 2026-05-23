package com.protocol.app.protocol

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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

// Page 1 — Live Data. Active polling page: snap grid of gauges, mode
// buttons + inline hamburger, bright-green status/polling-rate line, and
// the session log table beneath. The shared header is hidden on this
// page (see ProtocolScreen) so gauges get the maximum vertical space —
// important for split-screen and in-dash setups.
//
// Layout depends on settings.splitScreenMode:
//   OFF (default) — mode buttons + hamburger on top, then status,
//                   then gauges, then log section
//   ON            — status, then gauges, then mode buttons + hamburger,
//                   then log section. Gauge growth pushes both the
//                   button row and the log down together.

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
    onResizeGauge: (String, Int, Int, Int, Int) -> Boolean,
    onOpenParameters: () -> Unit,
    onOpenTcmParameters: () -> Unit,
    onOpenLiveDataSettings: () -> Unit,
    onToggleSplitScreen: () -> Unit
) {
    BackHandler(enabled = uiState.editMode) { onExitEditMode() }

    val splitScreen = uiState.settings.splitScreenMode
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState, enabled = !uiState.editMode)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        if (!splitScreen) {
            ModeButtonsRow(
                uiState = uiState,
                splitScreenMode = splitScreen,
                onStartReadingLive = onStartReadingLive,
                onStopReadingLive = onStopReadingLive,
                onStartLogging = onStartLogging,
                onStopLogging = onStopLogging,
                onOpenParameters = onOpenParameters,
                onOpenTcmParameters = onOpenTcmParameters,
                onOpenLiveDataSettings = onOpenLiveDataSettings,
                onToggleSplitScreen = onToggleSplitScreen
            )
        }

        StatusLine(uiState)

        SnapGaugeGrid(
            uiState = uiState,
            onEnterEditMode = onEnterEditMode,
            onExitEditMode = onExitEditMode,
            onRemoveGauge = onRemoveGauge,
            onResizeGauge = onResizeGauge
        )

        if (splitScreen) {
            ModeButtonsRow(
                uiState = uiState,
                splitScreenMode = splitScreen,
                onStartReadingLive = onStartReadingLive,
                onStopReadingLive = onStopReadingLive,
                onStartLogging = onStartLogging,
                onStopLogging = onStopLogging,
                onOpenParameters = onOpenParameters,
                onOpenTcmParameters = onOpenTcmParameters,
                onOpenLiveDataSettings = onOpenLiveDataSettings,
                onToggleSplitScreen = onToggleSplitScreen
            )
        }

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

// Status + live polling-rate line, bright green and above the gauges.
// Combines the textual status (or a tapped-param long name) with the
// measured ms between the last two poll samples. Falls back to the
// configured poll-interval setting when no samples have arrived yet.
@Composable
private fun StatusLine(uiState: ProtocolUiState) {
    val rateMs = if (uiState.lastPollIntervalMs > 0L)
        uiState.lastPollIntervalMs.toInt()
    else
        uiState.settings.pollIntervalMs
    val baseStatus = uiState.tappedParamLongName ?: uiState.statusMessage
    val text = if (baseStatus.isBlank())
        "${rateMs}ms"
    else
        "$baseStatus  ·  ${rateMs}ms"
    Text(
        text = text,
        color = BrightGreen,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold
    )
}

// Mode buttons + inline hamburger, used both at-top (default layout) and
// below-gauges (split-screen layout). Buttons share remaining width via
// weight(1f); the hamburger takes its native 44×32 dp footprint.
@Composable
private fun ModeButtonsRow(
    uiState: ProtocolUiState,
    splitScreenMode: Boolean,
    onStartReadingLive: () -> Unit,
    onStopReadingLive: () -> Unit,
    onStartLogging: () -> Unit,
    onStopLogging: () -> Unit,
    onOpenParameters: () -> Unit,
    onOpenTcmParameters: () -> Unit,
    onOpenLiveDataSettings: () -> Unit,
    onToggleSplitScreen: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
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
        HamburgerMenu(
            splitScreenMode = splitScreenMode,
            onOpenParameters = onOpenParameters,
            onOpenTcmParameters = onOpenTcmParameters,
            onOpenLiveDataSettings = onOpenLiveDataSettings,
            onToggleSplitScreen = onToggleSplitScreen
        )
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
            containerColor = if (active) Accent else SurfaceBg,
            contentColor = Color.White,
            disabledContainerColor = SurfaceBg.copy(alpha = 0.5f),
            disabledContentColor = NeutralGray
        ),
        shape = y2kCornerShape(),
        border = BorderStroke(1.dp, Accent),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
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
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
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
