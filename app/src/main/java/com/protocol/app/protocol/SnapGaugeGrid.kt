package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.protocol.app.openport2.Ssm2Pid
import com.protocol.app.openport2.Ssm2Pids

// Gauges are rendered at absolute (col,row) offsets so 1x1, 2x1, 1x2,
// and 2x2 spans all work. Long-press a gauge to enter edit mode; tap
// any empty area of the grid to exit. In edit mode each gauge gets a
// drag bar on each of its four edges (snap to ±1 cell when the drag
// accumulator crosses half a cell, rejected if it would overlap a
// neighbor or leave the grid) and a center X button to remove. The
// VM's resizeGauge already enforces canPlace/overlap rules, so this
// UI just hands it the desired (col,row,width,height) and watches the
// boolean it returns.

private val GAUGE_GRID_SPACING = 6.dp
private const val GAUGE_CELL_ASPECT = 0.7f          // height/width of a 1x1 cell
private val EDIT_BAR_THICKNESS = 28.dp              // touch target on each edge

private enum class DragAxis { Horizontal, Vertical }

@Composable
internal fun SnapGaugeGrid(
    uiState: ProtocolUiState,
    onEnterEditMode: () -> Unit,
    onExitEditMode: () -> Unit,
    onRemoveGauge: (String) -> Unit,
    onResizeGauge: (String, Int, Int, Int, Int) -> Boolean,
    flash: Float = 0f
) {
    val layout = uiState.gaugeLayout
    val editMode = uiState.editMode
    val live = uiState.liveValues
    val active = uiState.isReadingLive || uiState.isLogging || live.isNotEmpty()

    if (layout.entries.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "No gauges yet. Open the menu and choose parameters to add.",
                color = NeutralGray,
                style = MaterialTheme.typography.bodyMedium
            )
        }
        return
    }

    val pidById = remember(uiState.loadedPids) {
        (Ssm2Pids.DEFAULT_DEMO_PIDS + uiState.loadedPids).associateBy { it.id }
    }
    val maxRow = layout.maxRow().coerceAtLeast(0)
    val rowCount = maxRow + 1

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val containerWidth = maxWidth
        val cellWidth = (containerWidth - GAUGE_GRID_SPACING * (layout.columns - 1)) / layout.columns
        val cellHeight = cellWidth * GAUGE_CELL_ASPECT
        val gridHeight =
            cellHeight * rowCount + GAUGE_GRID_SPACING * (rowCount - 1).coerceAtLeast(0)

        // Pixel-space cell pitch (cell + spacing) used by the drag-bar snap
        // logic to decide when an accumulated drag delta crosses a cell.
        val density = LocalDensity.current
        val cellUnitWidthPx = with(density) { (cellWidth + GAUGE_GRID_SPACING).toPx() }
        val cellUnitHeightPx = with(density) { (cellHeight + GAUGE_GRID_SPACING).toPx() }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(gridHeight)
                .pointerInput(editMode) {
                    if (editMode) {
                        // Tap on empty area of the grid → exit edit. Taps on
                        // gauge bodies are swallowed inside GaugeTile so they
                        // don't bubble up to this handler.
                        detectTapGestures(onTap = { onExitEditMode() })
                    }
                }
        ) {
            for (entry in layout.entries) {
                // A laid-out parameter the current set doesn't provide — a layout
                // saved while a different definition was loaded, or none at all.
                // It gets a placeholder rather than being skipped: a skipped entry
                // still holds its cells and still counts toward the row height, so
                // it blocks placement invisibly with nothing on screen to remove.
                val pid = pidById[entry.pidId] ?: stalePidPlaceholder(entry.pidId)
                val xOffset = (cellWidth + GAUGE_GRID_SPACING) * entry.col
                val yOffset = (cellHeight + GAUGE_GRID_SPACING) * entry.row
                val tileWidth = cellWidth * entry.width +
                    GAUGE_GRID_SPACING * (entry.width - 1).coerceAtLeast(0)
                val tileHeight = cellHeight * entry.height +
                    GAUGE_GRID_SPACING * (entry.height - 1).coerceAtLeast(0)

                GaugeTile(
                    entry = entry,
                    pid = pid,
                    rawValue = live[entry.pidId],
                    minValue = uiState.liveValuesMin[entry.pidId],
                    maxValue = uiState.liveValuesMax[entry.pidId],
                    active = active,
                    editMode = editMode,
                    flash = flash,
                    cellUnitWidthPx = cellUnitWidthPx,
                    cellUnitHeightPx = cellUnitHeightPx,
                    onEnterEdit = onEnterEditMode,
                    onRemove = { onRemoveGauge(entry.pidId) },
                    onResize = { c, r, w, h -> onResizeGauge(entry.pidId, c, r, w, h) },
                    modifier = Modifier
                        .offset(x = xOffset, y = yOffset)
                        .size(width = tileWidth, height = tileHeight)
                )
            }
        }
    }
}

/**
 * Stand-in for a gauge whose parameter isn't in the current set, so the tile
 * stays visible and can be removed with its own X instead of holding cells
 * invisibly. Marked "stale" so it can't be mistaken for a gauge waiting on data.
 *
 * Never polled: the poll set is built from the real parameter list, which by
 * definition doesn't contain this id — so the empty address list is never read.
 */
private fun stalePidPlaceholder(pidId: String) = Ssm2Pid(
    id = pidId,
    displayName = pidId,
    unit = "stale",
    addresses = emptyList(),
    decode = { 0.0 },
    longName = pidId
)

@Composable
private fun GaugeTile(
    entry: GaugeLayoutEntry,
    pid: Ssm2Pid,
    rawValue: Double?,
    minValue: Double?,
    maxValue: Double?,
    active: Boolean,
    editMode: Boolean,
    flash: Float,
    cellUnitWidthPx: Float,
    cellUnitHeightPx: Float,
    onEnterEdit: () -> Unit,
    onRemove: () -> Unit,
    onResize: (col: Int, row: Int, width: Int, height: Int) -> Boolean,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val baseBg = if (active) SurfaceBg else SurfaceAlt
    // Edit mode → solid white outline (2 dp). Otherwise the outline flashes
    // white — 3x on entering lock mode and once per tap while locked — driven
    // by the lock-flash value; it rests at BorderGray when flash == 0.
    val borderColor = if (editMode) Accent else lerp(BorderGray, Accent, flash.coerceIn(0f, 1f))
    val borderWidth = if (editMode) 2.dp else 1.dp

    // Value font scales by cell footprint. The abbreviation (display name)
    // and the unit stay small per spec — only the number gets big so it
    // reads at a glance.
    val cells = entry.width * entry.height
    val valueFontSize = when {
        cells >= 4 -> 64.sp   // 2x2
        cells >= 2 -> 44.sp   // 2x1 or 1x2
        else -> 28.sp         // 1x1
    }

    // rememberUpdatedState gives the still-running drag coroutine a
    // pointer at the latest entry coords after a snap (entry is a value
    // captured by closure; without this the next snap in the same drag
    // would compute from stale col/row).
    val latestEntry by rememberUpdatedState(entry)
    val latestResize by rememberUpdatedState(onResize)

    Card(
        shape = RoundedCornerShape(6.dp),
        colors = CardDefaults.cardColors(containerColor = baseBg),
        border = BorderStroke(borderWidth, borderColor),
        modifier = modifier
            .pointerInput(editMode) {
                if (editMode) {
                    // Swallow taps on the gauge body so the grid's "tap
                    // outside" handler doesn't exit edit when the user is
                    // just trying to settle their finger between drags.
                    detectTapGestures(onTap = { /* consumed */ })
                } else {
                    detectTapGestures(
                        onLongPress = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onEnterEdit()
                        }
                    )
                }
            }
            // Drag the gauge body to move it. Active only in edit mode.
            // Touches on the four drag bars or the center X hit those
            // children first (they're drawn on top via EditModeOverlay),
            // so this handler only fires for drags inside the body area.
            .pointerInput(editMode, cellUnitWidthPx, cellUnitHeightPx) {
                if (!editMode) return@pointerInput
                var accX = 0f
                var accY = 0f
                val thresholdX = cellUnitWidthPx * 0.5f
                val thresholdY = cellUnitHeightPx * 0.5f
                detectDragGestures(
                    onDragStart = { accX = 0f; accY = 0f },
                    onDrag = { _, drag ->
                        accX += drag.x
                        accY += drag.y
                        // Track position locally across multiple snaps in
                        // one frame; latestEntry only updates after Compose
                        // settles, so we can't rely on it inside the loop.
                        val base = latestEntry
                        var col = base.col
                        var row = base.row
                        val w = base.width
                        val h = base.height
                        while (accX >= thresholdX) {
                            if (latestResize(col + 1, row, w, h)) {
                                col += 1; accX -= cellUnitWidthPx
                            } else { accX = thresholdX - 1f; break }
                        }
                        while (accX <= -thresholdX) {
                            if (latestResize(col - 1, row, w, h)) {
                                col -= 1; accX += cellUnitWidthPx
                            } else { accX = -thresholdX + 1f; break }
                        }
                        while (accY >= thresholdY) {
                            if (latestResize(col, row + 1, w, h)) {
                                row += 1; accY -= cellUnitHeightPx
                            } else { accY = thresholdY - 1f; break }
                        }
                        while (accY <= -thresholdY) {
                            if (latestResize(col, row - 1, w, h)) {
                                row -= 1; accY += cellUnitHeightPx
                            } else { accY = -thresholdY + 1f; break }
                        }
                    },
                    onDragEnd = { accX = 0f; accY = 0f },
                    onDragCancel = { accX = 0f; accY = 0f }
                )
            }
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // Three rows: title (with module suffix) — value+unit — min/max.
            // Title is small and top-aligned; value dominates the middle and
            // is right-padded by the unit; min/max row sits at the bottom.
            val moduleSuffix = when (pid.category) {
                com.protocol.app.openport2.Ssm2PidCategory.ECU -> "|E"
                com.protocol.app.openport2.Ssm2PidCategory.TCM -> "|T"
            }
            val formattedValue = rawValue?.let {
                ProtocolLogFormatter.formatPidValueText(pid.id, it)
            } ?: "--"
            val formattedMin = minValue?.let {
                ProtocolLogFormatter.formatPidValueText(pid.id, it)
            } ?: "--"
            val formattedMax = maxValue?.let {
                ProtocolLogFormatter.formatPidValueText(pid.id, it)
            } ?: "--"
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.SpaceBetween,
                horizontalAlignment = Alignment.Start
            ) {
                // Title row — display name + module suffix (E for ECU, T for TCM)
                Text(
                    "${pid.displayName} $moduleSuffix",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false
                )
                // Value + unit row — value left, unit right.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = formattedValue,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = valueFontSize,
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        pid.unit,
                        color = Color.White,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace
                    )
                }
                // Min/max row at the bottom of the tile.
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "min $formattedMin",
                        color = NeutralGray,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false
                    )
                    Text(
                        "max $formattedMax",
                        color = NeutralGray,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if (editMode) {
                EditModeOverlay(
                    entry = entry,
                    cellUnitWidthPx = cellUnitWidthPx,
                    cellUnitHeightPx = cellUnitHeightPx,
                    onRemove = onRemove,
                    onResize = onResize
                )
            }
        }
    }
}

@Composable
private fun EditModeOverlay(
    entry: GaugeLayoutEntry,
    cellUnitWidthPx: Float,
    cellUnitHeightPx: Float,
    onRemove: () -> Unit,
    onResize: (col: Int, row: Int, width: Int, height: Int) -> Boolean
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Top: vertical-axis drag — pulling up extends the gauge upward.
        DragBar(
            axis = DragAxis.Vertical,
            cellSize = cellUnitHeightPx,
            onSnap = { delta ->
                onResize(entry.col, entry.row + delta, entry.width, entry.height - delta)
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(EDIT_BAR_THICKNESS)
        )
        // Bottom
        DragBar(
            axis = DragAxis.Vertical,
            cellSize = cellUnitHeightPx,
            onSnap = { delta ->
                onResize(entry.col, entry.row, entry.width, entry.height + delta)
            },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(EDIT_BAR_THICKNESS)
        )
        // Left: horizontal-axis drag — pulling left extends the gauge leftward.
        DragBar(
            axis = DragAxis.Horizontal,
            cellSize = cellUnitWidthPx,
            onSnap = { delta ->
                onResize(entry.col + delta, entry.row, entry.width - delta, entry.height)
            },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(EDIT_BAR_THICKNESS)
        )
        // Right
        DragBar(
            axis = DragAxis.Horizontal,
            cellSize = cellUnitWidthPx,
            onSnap = { delta ->
                onResize(entry.col, entry.row, entry.width + delta, entry.height)
            },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .width(EDIT_BAR_THICKNESS)
        )

        // Center X — removes the gauge entirely. Kept small (28dp) so it
        // doesn't eat the body's drag-to-move surface area; the user
        // dragging the gauge around shouldn't accidentally hit delete.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(28.dp)
                .background(Color(0xCCEF4444), CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(12.dp)) {
                val stroke = 2.dp.toPx()
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.2f, size.height * 0.2f),
                    end = Offset(size.width * 0.8f, size.height * 0.8f),
                    strokeWidth = stroke
                )
                drawLine(
                    color = Color.White,
                    start = Offset(size.width * 0.8f, size.height * 0.2f),
                    end = Offset(size.width * 0.2f, size.height * 0.8f),
                    strokeWidth = stroke
                )
            }
        }
    }
}

@Composable
private fun DragBar(
    axis: DragAxis,
    cellSize: Float,
    onSnap: (delta: Int) -> Boolean,
    modifier: Modifier = Modifier
) {
    // Latest snap callback captured via rememberUpdatedState — needed because
    // a successful snap recomposes EditModeOverlay with new entry values, but
    // the running detectDragGestures coroutine here keeps its original
    // closures. Without this we'd compute the next snap from stale col/row/
    // width/height.
    val currentOnSnap by rememberUpdatedState(onSnap)
    var accumulator by remember { mutableStateOf(0f) }
    val threshold = cellSize * 0.5f

    Box(
        modifier = modifier.pointerInput(axis, cellSize) {
            detectDragGestures(
                onDragStart = { accumulator = 0f },
                onDrag = { _, drag ->
                    val d = if (axis == DragAxis.Vertical) drag.y else drag.x
                    accumulator += d
                    while (accumulator >= threshold) {
                        if (currentOnSnap(+1)) {
                            accumulator -= cellSize
                        } else {
                            // Snap rejected (overlap / out of bounds / would
                            // hit min or max span). Pin just under threshold
                            // so we don't retry every frame.
                            accumulator = threshold - 1f
                            break
                        }
                    }
                    while (accumulator <= -threshold) {
                        if (currentOnSnap(-1)) {
                            accumulator += cellSize
                        } else {
                            accumulator = -threshold + 1f
                            break
                        }
                    }
                },
                onDragEnd = { accumulator = 0f },
                onDragCancel = { accumulator = 0f }
            )
        },
        contentAlignment = Alignment.Center
    ) {
        if (axis == DragAxis.Vertical) {
            Box(
                modifier = Modifier
                    .width(48.dp)
                    .height(4.dp)
                    .background(Accent.copy(alpha = 0.85f), RoundedCornerShape(2.dp))
            )
        } else {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(48.dp)
                    .background(Accent.copy(alpha = 0.85f), RoundedCornerShape(2.dp))
            )
        }
    }
}
