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
    onToggleObdLink: () -> Unit
) {
    BackHandler(enabled = uiState.editMode) { onExitEditMode() }

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState, enabled = !uiState.editMode)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        StatusLine(uiState)

        SnapGaugeGrid(
            uiState = uiState,
            onEnterEditMode = onEnterEditMode,
            onExitEditMode = onExitEditMode,
            onRemoveGauge = onRemoveGauge,
            onResizeGauge = onResizeGauge
        )

        ModeButtonsRow(
            uiState = uiState,
            onStartReadingLive = onStartReadingLive,
            onStopReadingLive = onStopReadingLive,
            onStartLogging = onStartLogging,
            onStopLogging = onStopLogging,
            onOpenParameters = onOpenParameters,
            onOpenTcmParameters = onOpenTcmParameters,
            onOpenLiveDataSettings = onOpenLiveDataSettings,
            onToggleObdLink = onToggleObdLink
        )

        LogActionRow(
            title = "Session Log (${uiState.sessionLog.size})",
            onClear = onClearSessionLog,
            onExportCsv = onExportSessionLog
        )

        SessionLogCard(uiState)
    }
}

// Status + live polling-rate line, above the gauges. Color tracks the
// connection stripe at the top of the screen (green/accent/red) so the
// status text and the stripe always agree at a glance.
//
// The ms readout is the measured wall-clock delta between the last two
// poll samples — what the adapter is actually doing on the K-line right
// now. We do NOT fall back to the configured setting; if there's no
// measured rate (not polling, or only one sample so far) the ms is
// simply omitted.
@Composable
private fun StatusLine(uiState: ProtocolUiState) {
    val rateMs = uiState.lastPollWireMs.toInt()
    val baseStatus = uiState.statusMessage
    val text = when {
        rateMs > 0 && baseStatus.isNotBlank() -> "$baseStatus  ·  ${rateMs}ms"
        rateMs > 0 -> "${rateMs}ms"
        else -> baseStatus
    }
    if (text.isBlank()) return
    val statusColor = when (uiState.connectionStatus) {
        is ConnectionStatus.Connected -> PassGreen
        is ConnectionStatus.Ready,
        is ConnectionStatus.PermissionRequired -> Accent
        is ConnectionStatus.Error,
        ConnectionStatus.NoDevice -> FailRed
    }
    Text(
        text = text,
        color = statusColor,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun ModeButtonsRow(
    uiState: ProtocolUiState,
    onStartReadingLive: () -> Unit,
    onStopReadingLive: () -> Unit,
    onStartLogging: () -> Unit,
    onStopLogging: () -> Unit,
    onOpenParameters: () -> Unit,
    onOpenTcmParameters: () -> Unit,
    onOpenLiveDataSettings: () -> Unit,
    onToggleObdLink: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        HamburgerMenu(
            obdLinkEnabled = uiState.settings.obdLinkEnabled,
            onOpenParameters = onOpenParameters,
            onOpenTcmParameters = onOpenTcmParameters,
            onOpenLiveDataSettings = onOpenLiveDataSettings,
            onToggleObdLink = onToggleObdLink
        )
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
            shape = y2kLeftButtonShape(),
            modifier = Modifier.weight(1f)
        )
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
            shape = y2kRightButtonShape(),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ModeButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape = y2kCornerShape(),
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
        shape = shape,
        border = BorderStroke(1.dp, Accent),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
        modifier = modifier
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

// SmallLogButton moved to CommonWidgets.LogActionRow (shared across all logs).

@Composable
private fun SessionLogCard(uiState: ProtocolUiState) {
    // Reformat the entire session log only when the row count or the PID
    // set actually changes. Without this, every 200ms poll sample
    // triggered a full ~1000-row × ~10-PID reformat — wasteful even on
    // fast phones. The new sample appended each cycle is enough of an
    // identity change to bypass the cache (sessionLog.size differs).
    val sessionLog = uiState.sessionLog
    val pidIds = uiState.pidIdsOnLiveData
    val formattedText = androidx.compose.runtime.remember(
        sessionLog.size,
        pidIds
    ) {
        ProtocolLogFormatter.formatSessionLogCleanText(sessionLog, pidIds)
    }
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
                androidx.compose.foundation.text.selection.SelectionContainer {
                    Text(
                        text = formattedText,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                        softWrap = false
                    )
                }
            }
        }
    }
}
