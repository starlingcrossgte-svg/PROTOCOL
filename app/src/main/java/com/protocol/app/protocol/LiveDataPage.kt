package com.protocol.app.protocol

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
    onOpenUnverified: () -> Unit,
    onOpenLiveDataSettings: () -> Unit,
    onApplyPreset: (Int) -> Unit,
    onAutoSaveLogs: () -> Unit
) {
    BackHandler(enabled = uiState.editMode) { onExitEditMode() }

    // `locked` is owned by ProtocolScreen (transient UI state) so the lock can
    // also freeze the horizontal pager swipe, and so back/home can clear it
    // without persisting. Here it gates scroll + edit-entry and drives the
    // button label.

    val scrollState = rememberScrollState()

    // Auto-save window: after tap 3 stops polling+logging, the save is "armed"
    // and a cancel popup shows for 5 s. Any tap (the popup or anywhere else)
    // cancels and returns to idle; if untouched, the save commits at timeout.
    // Transient UI state owned here.
    var autoSaveArmed by remember { mutableStateOf(false) }

    // Step 3 tap loop: while locked, a tap anywhere over the gauge area cycles
    // stream -> +log -> stop both (+ arm auto-save), then repeats. Routed
    // through rememberUpdatedState so the gesture (registered once) always sees
    // the current poll/log/armed state instead of a stale snapshot.
    val onCycleTap: () -> Unit = {
        when {
            autoSaveArmed -> autoSaveArmed = false           // any tap cancels pending save -> idle
            !uiState.isReadingLive -> onStartReadingLive()   // tap 1: idle -> stream
            !uiState.isLogging -> onStartLogging()           // tap 2: stream -> +log
            else -> {                                        // tap 3: stop both + arm save
                onStopReadingLive()
                autoSaveArmed = true
            }
        }
    }
    val currentCycleTap = rememberUpdatedState(onCycleTap)

    // Commit the auto-save when the 5 s window elapses untouched. Cancelling
    // (autoSaveArmed -> false) re-keys this effect, killing the delay so no
    // file is written.
    LaunchedEffect(autoSaveArmed) {
        if (autoSaveArmed) {
            kotlinx.coroutines.delay(5000)
            onAutoSaveLogs()
            autoSaveArmed = false
        }
    }

    // Lock/Tap flash: instead of a constant white outline, the gauges + log
    // outline flash white — 3x when entering lock mode, once per tap while
    // locked. One shared value drives both the gauge tiles and the log card.
    val lockFlash = remember { Animatable(0f) }
    val flashScope = rememberCoroutineScope()
    LaunchedEffect(locked) {
        if (locked) {
            repeat(3) {
                lockFlash.animateTo(1f, tween(90))
                lockFlash.animateTo(0f, tween(150))
            }
        } else {
            lockFlash.snapTo(0f)
            // Unlocking (Cancel button / back / ON_STOP) also drops any pending
            // auto-save so the popup can't linger after leaving lock mode.
            autoSaveArmed = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Live-Data only: trim the top gap so the gauges sit a bit higher.
            // The status-bar inset (applied at the root, above the status stripe)
            // is still the floor, so the gauges stay clear of both the status bar
            // and the camera cutout on every device — this only reclaims the
            // fixed gap that sat below that inset. Bottom stays 5dp for the
            // pinned button.
            .padding(start = 10.dp, end = 10.dp, top = 1.dp, bottom = 5.dp)
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
                // Dev-only quick PID presets — load a 10-PID group onto the
                // gauges in one tap (ECU 1-7 / TCM 1-5). Picking another preset
                // replaces the current gauges.
                if (uiState.settings.devMode) {
                    PresetSelector(onApplyPreset = onApplyPreset)
                    Spacer(Modifier.height(6.dp))
                }

                SnapGaugeGrid(
                    uiState = uiState,
                    // Locked → swallow the long-press so the grid can't enter
                    // edit mode. (Gating here keeps SnapGaugeGrid untouched.)
                    onEnterEditMode = { if (!locked) onEnterEditMode() },
                    onExitEditMode = onExitEditMode,
                    onRemoveGauge = onRemoveGauge,
                    onResizeGauge = onResizeGauge,
                    flash = lockFlash.value
                )

                Spacer(Modifier.height(9.dp))

                // Clear Log / Export CSV moved to the pinned bottom action bar;
                // only the section header stays above the log card here.
                CategoryHeader("Session Log")

                Spacer(Modifier.height(9.dp))

                SessionLogCard(
                    uiState = uiState,
                    locked = locked,
                    onEnterEditMode = onEnterEditMode,
                    flash = lockFlash.value
                )
            }
            // While locked, a transparent overlay captures every tap over the
            // gauge area and drives the loop; it also swallows drags, which
            // reinforces "no scrolling while locked". Each tap also fires a
            // single white flash on the gauges + log.
            if (locked) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = {
                                currentCycleTap.value()
                                flashScope.launch {
                                    lockFlash.snapTo(1f)
                                    lockFlash.animateTo(0f, tween(220))
                                }
                            })
                        }
                )
            }
            // Auto-save cancel popup — drawn on top of the lock overlay. Tapping
            // it cancels; tapping anywhere else lands on the lock overlay above,
            // which also cancels (onCycleTap sees autoSaveArmed). White text per
            // the app's font rule.
            if (autoSaveArmed) {
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clip(y2kCornerShape())
                        .background(SurfaceBg, y2kCornerShape())
                        .border(2.dp, Accent, y2kCornerShape())
                        .clickable { autoSaveArmed = false }
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Click here to cancel auto save",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(Modifier.height(5.dp))

        // Pinned to the bottom of the page — stays visible while the gauges
        // and Session Log scroll above it.
        ModeButtonsRow(
            locked = locked,
            onToggleLock = onToggleLock,
            onClearSessionLog = onClearSessionLog,
            onExportSessionLog = onExportSessionLog,
            onOpenParameters = onOpenParameters,
            onOpenTcmParameters = onOpenTcmParameters,
            onOpenUnverified = onOpenUnverified,
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
    onClearSessionLog: () -> Unit,
    onExportSessionLog: () -> Unit,
    onOpenParameters: () -> Unit,
    onOpenTcmParameters: () -> Unit,
    onOpenUnverified: () -> Unit,
    onOpenLiveDataSettings: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Always present so the bar keeps a fixed width whether or not the
        // page is locked; dimmed + non-clickable while locked so there's no
        // exit through the menu mid-session.
        HamburgerMenu(
            onOpenParameters = onOpenParameters,
            onOpenTcmParameters = onOpenTcmParameters,
            onOpenUnverified = onOpenUnverified,
            onOpenLiveDataSettings = onOpenLiveDataSettings,
            enabled = !locked
        )
        // Combined action bar filling the slot the lone "Lock and Tap" button
        // used to (weight 1f, same height): three segments — Lock and Tap |
        // Clear Log | Export CSV — split by 1 dp solid-white dividers instead
        // of gaps. Lock and Tap takes the remaining width (longest label);
        // Clear Log / Export CSV size to their content.
        //   Unlocked: "Lock and Tap" -> locks. Locked: "Cancel Lock and Tap" ->
        //   cancels the lock (caller also stops any live poll/log). The
        //   stream -> log -> stop cycle is driven by tapping the gauge area
        //   while locked (see the overlay above).
        val shape = y2kCornerShape()
        Row(
            modifier = Modifier
                .weight(1f)
                .height(IntrinsicSize.Min)
                .clip(shape)
                .background(SurfaceBg, shape)
                .border(1.dp, Accent, shape),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SegmentButton(
                label = if (locked) "Cancel Lock and Tap" else "Lock and Tap",
                onClick = onToggleLock,
                modifier = Modifier.weight(1f)
            )
            SegmentDivider()
            SegmentButton(label = "Clear Log", onClick = onClearSessionLog)
            SegmentDivider()
            SegmentButton(label = "Export CSV", onClick = onExportSessionLog)
        }
    }
}

// One tappable segment of the combined bottom action bar. Transparent
// container (the parent Row draws the dark surface + white border); white
// label centered, one line. fillMaxHeight so every segment and the dividers
// span the bar's full height.
@Composable
private fun SegmentButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Accent,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1
        )
    }
}

// 1 dp solid-white divider spanning the bar's height — the separator the user
// asked for in place of the gap between segments.
@Composable
private fun SegmentDivider() {
    Box(
        Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(Color.White)
    )
}

// SmallLogButton moved to CommonWidgets.LogActionRow (shared across all logs).

// Dev-only preset picker. A compact dropdown listing the 12 fixed PID presets
// (ECU 1-7 / TCM 1-5). Selecting one calls onApplyPreset(index), which replaces
// the Live Data gauges with that preset's parameters. Lives at the top of the
// gauge area; hidden unless Developer Mode is on.
@Composable
private fun PresetSelector(onApplyPreset: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var selectedLabel by remember { mutableStateOf<String?>(null) }
    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(y2kCornerShape())
                .background(SurfaceBg, y2kCornerShape())
                .border(1.dp, Accent, y2kCornerShape())
                .clickable { expanded = !expanded }
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = selectedLabel?.let { "Preset: $it" } ?: "Load PID Preset",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "▾",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(SurfaceBg)
                .border(BorderStroke(1.dp, Accent))
        ) {
            PidPresets.PRESETS.forEachIndexed { index, preset ->
                Text(
                    text = "${preset.label}  (${preset.pidIds.size})",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            onApplyPreset(index)
                            selectedLabel = preset.label
                            expanded = false
                        }
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                )
            }
        }
    }
}

// Session-log card height (dp): default, and the clamp the bottom-edge drag
// handle is allowed to roam within.
private const val DEFAULT_LOG_HEIGHT_DP = 300f
private const val MIN_LOG_HEIGHT_DP = 120f
private const val MAX_LOG_HEIGHT_DP = 800f

@Composable
private fun SessionLogCard(
    uiState: ProtocolUiState,
    locked: Boolean,
    onEnterEditMode: () -> Unit,
    flash: Float
) {
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

    // User-resizable height, but only in edit mode (same long-press gesture
    // the gauges use) so the log isn't freely draggable during normal use.
    // Kept as a saveable Float (dp) so it survives rotation / activity recreation.
    val density = LocalDensity.current.density
    var logHeightDp by rememberSaveable { mutableStateOf(DEFAULT_LOG_HEIGHT_DP) }

    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14161A)),
        // Outline flashes white together with the gauges (3x on lock entry,
        // once per tap while locked); rests at BorderGray otherwise.
        border = BorderStroke(1.dp, lerp(BorderGray, Accent, flash.coerceIn(0f, 1f))),
        modifier = Modifier.fillMaxWidth()
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(logHeightDp.dp)
                // Long-press the log to enter edit mode, same as the gauges.
                // Disabled while locked (no edit entry mid lock-and-tap). This
                // replaces long-press text-selection on the log.
                .pointerInput(locked) {
                    if (!locked) {
                        detectTapGestures(onLongPress = { onEnterEditMode() })
                    }
                }
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
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
            // Resize grip — shown only in edit mode. Sits inside the card on its
            // bottom edge; drag it vertically to grow/shrink the log.
            // detectVerticalDragGestures consumes the drag, and the page scroll
            // is already frozen in edit mode, so nothing fights it.
            if (uiState.editMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(22.dp)
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, dragAmount ->
                                logHeightDp = (logHeightDp + dragAmount / density)
                                    .coerceIn(MIN_LOG_HEIGHT_DP, MAX_LOG_HEIGHT_DP)
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .width(40.dp)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Accent)
                    )
                }
            }
        }
    }
}
