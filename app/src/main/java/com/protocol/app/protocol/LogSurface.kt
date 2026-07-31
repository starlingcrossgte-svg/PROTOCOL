package com.protocol.app.protocol

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.protocol.app.obdlink.ObdLinkTrafficEvent
import com.protocol.app.obdlink.ObdLinkTrafficLog
import com.protocol.app.openport2.TrafficEvent
import com.protocol.app.openport2.UsbTrafficLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.withContext

/*
 * Shared surface for every log in the app.
 *
 * Inline = preview: no scroll, no selection. A scrollable inline log ate the
 * pager's drag and trapped the user on Live Data until they cleared it.
 * Expand owns scroll/select/copy.
 */

internal const val LOG_REFRESH_MS = 200L
internal const val LOG_PREVIEW_LINES = 150

// ── log line model ─────────────────────────────────────────────────────────

internal data class LogLine(
    val ts: Long,
    val isOut: Boolean,
    val payload: String,        // hex (USB) or ASCII text (OBDLink)
    val byteCount: Int? = null, // USB only
    val ascii: String? = null   // USB only — printable rendering of the bytes
)

// ── merged transport log ───────────────────────────────────────────────────

/**
 * USB + OBDLink rings merged newest-last, off the main thread, rate limited.
 * Must stay off the main thread: it used to re-key per USB transfer, which on
 * the firmware page is an ANR risk mid write. [tail] = null for full buffer.
 */
@Composable
internal fun rememberMergedTransportLog(tail: Int? = null): List<LogLine> =
    produceState(initialValue = emptyList<LogLine>(), tail) {
        combine(UsbTrafficLog.revision, ObdLinkTrafficLog.revision) { u, b -> u + b }
            .conflate()
            .collect {
                value = withContext(Dispatchers.Default) { mergeTransportLog(tail) }
                delay(LOG_REFRESH_MS)
            }
    }.value

/** Both rings are already time-sorted, so this is a linear two-pointer merge
 *  and [tail] can be applied per side before merging. */
private fun mergeTransportLog(tail: Int?): List<LogLine> {
    val usb = UsbTrafficLog.snapshot()
    val bt = ObdLinkTrafficLog.snapshot()
    val u = if (tail != null && usb.size > tail) usb.subList(usb.size - tail, usb.size) else usb
    val b = if (tail != null && bt.size > tail) bt.subList(bt.size - tail, bt.size) else bt

    val out = ArrayList<LogLine>(u.size + b.size)
    var i = 0
    var j = 0
    while (i < u.size && j < b.size) {
        if (u[i].timestampMs <= b[j].timestampMs) out.add(u[i++].toLogLine())
        else out.add(b[j++].toLogLine())
    }
    while (i < u.size) out.add(u[i++].toLogLine())
    while (j < b.size) out.add(b[j++].toLogLine())

    return if (tail != null && out.size > tail) out.subList(out.size - tail, out.size) else out
}

private fun TrafficEvent.toLogLine() =
    LogLine(timestampMs, direction == TrafficEvent.Direction.OUT, hex, byteCount, ascii)

private fun ObdLinkTrafficEvent.toLogLine() =
    LogLine(timestampMs, direction == ObdLinkTrafficEvent.Direction.OUT, text, text.length)

// ── the action tab ─────────────────────────────────────────────────────────

/** Tab on a log card's inner top-right corner. Null callback = segment omitted. */
@Composable
internal fun LogActionTab(
    onClear: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onExpand: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val tabShape = RoundedCornerShape(bottomStart = 10.dp)
    val segments = buildList {
        if (onClear != null) add("Clear" to onClear)
        if (onExport != null) add("Export" to onExport)
        if (onExpand != null) add("Expand" to onExpand)
    }
    if (segments.isEmpty()) return
    Row(
        modifier = modifier
            .height(IntrinsicSize.Min)
            .clip(tabShape)
            .background(SurfaceAlt, tabShape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        segments.forEachIndexed { index, (label, action) ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White))
            LogTabButton(label, action)
        }
    }
}

/** One segment of a log's action tab. */
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

// ── full screen ────────────────────────────────────────────────────────────
// NOT A DIALOG. A Dialog is a second, floating window with an inset background
// and renders as a padded card, not edge to edge. This overlays the app's own
// window instead, which is already edge to edge.

/** Three sources, not four: the firmware page renders the same transport rings. */
internal enum class LogSource(val title: String, val label: String) {
    // Labels renamed to MODULE; internal ids stay sessionLog because the
    // preference keys are persisted.
    Session("MODULE", "MODULE"),
    Transport("TRANSPORT", "TRANSPORT"),
    Codes("TROUBLE CODES", "CODES")
}

/** Which log the root overlay is showing. Null = nothing expanded. */
@Stable
internal class FullscreenLogHost {
    var source by mutableStateOf<LogSource?>(null)
}

/** No default on purpose: throws loudly rather than showing nothing. */
internal val LocalFullscreenLogHost = staticCompositionLocalOf<FullscreenLogHost> {
    error("No FullscreenLogHost provided — wrap the content in ProtocolScreen's host")
}

/**
 * Renders a SOURCE, not a slot passed down from the opening page. A composable
 * lambda handed across the tree gets skipped when its params compare equal,
 * which froze this view on its placeholder. Reading state here avoids that.
 */
@Composable
internal fun FullscreenLogOverlay(
    host: FullscreenLogHost,
    uiState: ProtocolUiState,
    onClearSessionLog: () -> Unit,
    onExportSessionLog: () -> Unit,
    onClearDtc: () -> Unit,
    onExportDtc: () -> Unit
) {
    val source = host.source ?: return
    val close: () -> Unit = { host.source = null }
    BackHandler(enabled = true) { close() }

    val exportTransport = rememberTransportLogExport()
    val transportLines = if (source == LogSource.Transport) rememberMergedTransportLog() else emptyList()

    val onClear: () -> Unit = when (source) {
        LogSource.Session -> onClearSessionLog
        LogSource.Transport -> ({ UsbTrafficLog.clear(); ObdLinkTrafficLog.clear() })
        LogSource.Codes -> onClearDtc
    }
    val onExport: () -> Unit = when (source) {
        LogSource.Session -> onExportSessionLog
        LogSource.Transport -> ({ exportTransport(transportLines, "protocol-traffic.csv") })
        LogSource.Codes -> onExportDtc
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Without this, taps fall through to the page underneath.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {}
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 6.dp)
                    .height(IntrinsicSize.Min),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        source.title,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    // Null until the ECU is identified.
                    val identity = uiState.ecuIdentity
                    if (identity != null) {
                        Text(
                            identity,
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
                Row(
                    modifier = Modifier.height(IntrinsicSize.Min),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LogTabButton("Clear", onClear)
                    LogTabButton("Export", onExport)
                    LogTabButton("Close", close)
                }
            }

            LogSourceSelector(selected = source, onSelect = { host.source = it })

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                when (source) {
                    LogSource.Session -> SessionLogFullscreenContent(uiState)
                    LogSource.Transport -> LogList(transportLines, height = null, interactive = true)
                    LogSource.Codes -> SelectionContainer { DtcContent(uiState, interactive = true) }
                }
            }
        }
    }
}

/** Three segments, dim until chosen — the app's existing selection idiom. */
@Composable
private fun LogSourceSelector(selected: LogSource, onSelect: (LogSource) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 2.dp)
            .height(IntrinsicSize.Min)
    ) {
        LogSource.values().forEachIndexed { index, source ->
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(BorderGray))
            val chosen = source == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onSelect(source) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    source.label,
                    color = if (chosen) Accent else AccentDim,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

/** Full session text, formatted off the main thread on a bounded schedule. */
@Composable
private fun SessionLogFullscreenContent(uiState: ProtocolUiState) {
    val latest = rememberUpdatedState(uiState)
    val text by produceState("Formatting...", latest) {
        while (true) {
            val s = latest.value
            value = withContext(Dispatchers.Default) {
                ProtocolLogFormatter.formatSessionLogCleanText(
                    s.sessionLog,
                    s.pidIdsOnLiveData,
                    availablePids = com.protocol.app.openport2.Ssm2Pids.DEFAULT_DEMO_PIDS + s.loadedPids
                )
            }
            delay(LOG_REFRESH_MS)
        }
    }
    SelectionContainer {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(10.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                softWrap = false
            )
        }
    }
}
