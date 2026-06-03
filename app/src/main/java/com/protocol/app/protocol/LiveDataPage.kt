package com.protocol.app.protocol

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
internal fun LiveDataPage(
    uiState: ProtocolUiState,
    locked: Boolean,
    onToggleLock: () -> Unit,
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
    onOpenLiveDataSettings: () -> Unit
) {
    BackHandler(enabled = uiState.editMode) { onExitEditMode() }

    // `locked` is owned by ProtocolScreen (transient UI state) so the lock can
    // also freeze the horizontal pager swipe, and so back/home can clear it
    // without persisting. Here it gates scroll + edit-entry and drives the
    // button label.

    val scrollState = rememberScrollState()

    // Step 3 tap loop: while locked, a tap anywhere over the gauge area cycles
    // stream -> +log -> stop both, then repeats. Routed through
    // rememberUpdatedState so the gesture (registered once) always sees the
    // current poll/log state instead of a stale snapshot.
    val onCycleTap: () -> Unit = {
        when {
            !uiState.isReadingLive -> onStartReadingLive()   // idle -> stream
            !uiState.isLogging -> onStartLogging()           // stream -> +log
            else -> onStopReadingLive()                      // stream+log -> stop both
        }
    }
    val currentCycleTap = rememberUpdatedState(onCycleTap)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 10.dp, vertical = 5.dp)
    ) {
        // Scrolling area: gauges + Session Log. Takes all height not used by
        // the pinned bottom bar below.
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState, enabled = !uiState.editMode && !locked),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                SnapGaugeGrid(
                    uiState = uiState,
                    // Locked → swallow the long-press so the grid can't enter
                    // edit mode. (Gating here keeps SnapGaugeGrid untouched.)
                    onEnterEditMode = { if (!locked) onEnterEditMode() },
                    onExitEditMode = onExitEditMode,
                    onRemoveGauge = onRemoveGauge,
                    onResizeGauge = onResizeGauge
                )

                Spacer(Modifier.height(6.dp))

                LogActionRow(
                    title = "Session Log",
                    onClear = onClearSessionLog,
                    onExportCsv = onExportSessionLog,
                    titleAsHeader = true
                )

                Spacer(Modifier.height(6.dp))

                SessionLogCard(uiState)
            }
            // While locked, a transparent overlay captures every tap over the
            // gauge area and drives the loop; it also swallows drags, which
            // reinforces "no scrolling while locked".
            if (locked) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { currentCycleTap.value() })
                        }
                )
            }
        }

        Spacer(Modifier.height(5.dp))

        // Pinned to the bottom of the page — stays visible while the gauges
        // and Session Log scroll above it.
        ModeButtonsRow(
            locked = locked,
            onToggleLock = onToggleLock,
            onOpenParameters = onOpenParameters,
            onOpenTcmParameters = onOpenTcmParameters,
            onOpenLiveDataSettings = onOpenLiveDataSettings
        )
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
    locked: Boolean,
    onToggleLock: () -> Unit,
    onOpenParameters: () -> Unit,
    onOpenTcmParameters: () -> Unit,
    onOpenLiveDataSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Always present so the button keeps a fixed width whether or not the
        // page is locked; dimmed + non-clickable while locked so there's no
        // exit through the menu mid-session.
        HamburgerMenu(
            onOpenParameters = onOpenParameters,
            onOpenTcmParameters = onOpenTcmParameters,
            onOpenLiveDataSettings = onOpenLiveDataSettings,
            enabled = !locked
        )
        // The single action button. Unlocked: "Lock and Tap" -> locks. Locked:
        // "Cancel Lock and Tap" -> cancels the lock (the caller also stops any
        // live poll/log). The stream -> log -> stop cycle itself is driven by
        // tapping the gauge area while locked (see the overlay above).
        ModeButton(
            label = AnnotatedString(if (locked) "Cancel Lock and Tap" else "Lock and Tap"),
            active = true,
            enabled = true,
            onClick = onToggleLock,
            shape = y2kCornerShape(),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ModeButton(
    label: AnnotatedString,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape = y2kCornerShape(),
    modifier: Modifier = Modifier
) {
    // Reactive button: dim outline + dim text when inactive, full-bright
    // Accent (white) when active (reading/logging). Disabled dims further.
    // Container stays dark in every state so brightness alone carries it.
    val baseColor = if (active) Accent else AccentDim
    val lineColor = if (enabled) baseColor else baseColor.copy(alpha = 0.4f)
    Box(
        modifier = modifier
            .clip(shape)
            .background(SurfaceBg, shape)
            .border(1.dp, lineColor, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = lineColor, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
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
        // White outline while logging (lock tap 2 lights the log window).
        border = BorderStroke(1.dp, if (uiState.isLogging) Accent else BorderGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        // SelectionContainer wraps the scroll containers (not the inner Text)
        // so the long-press-to-select gesture wins over the vertical/horizontal
        // scroll drag — same pattern the Run Log / USB Traffic cards use. With
        // it nested inside the scrolls, the scrolls swallowed the long-press and
        // tap-hold highlight never started.
        androidx.compose.foundation.text.selection.SelectionContainer {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .padding(10.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
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
