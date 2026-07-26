package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

// Diagnostics (DTC) page — Page 0 of the pager (swipe left from Home).
// Decoded SSM2 trouble codes in a dark, rounded log-style card that matches the
// Dev / Flash transport log (Clear / Export tab inside), filling the top, with
// READ + RESET locked at the bottom. Raw request/reply bytes still land in the
// Dev transport log. RESET performs a real ECU clear-codes (SSM2 clear-memory)
// write via [onResetDtc], gated behind a two-tap confirm because it writes to
// the ECU and also resets adaptive learning. The "Clear" tab only wipes the
// on-screen results.
@Composable
internal fun DiagnosticsPage(
    uiState: ProtocolUiState,
    onReadDtc: () -> Unit,
    onClearDtc: () -> Unit,
    onResetDtc: () -> Unit,
    onExportDtc: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Decoded-codes card — dark + rounded like the transport log, fills the top.
        Card(
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF14161A)),
            border = BorderStroke(1.dp, BorderGray),
            modifier = Modifier.weight(1f).fillMaxWidth()
        ) {
            // Clear / Export / Expand tab, top-right (mirrors the transport log).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val tabShape = RoundedCornerShape(bottomStart = 10.dp)
                Row(
                    modifier = Modifier
                        .height(IntrinsicSize.Min)
                        .clip(tabShape)
                        .background(SurfaceAlt, tabShape),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LogTabButton("Clear", onClearDtc)
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Color.White))
                    LogTabButton("Export", onExportDtc)
                }
            }
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                DtcContent(uiState)
            }
        }

        // READ + RESET locked at the bottom (full-width, matches the Flash silo).
        DevActionButton(
            if (uiState.isReadingDtc) "READING…" else "READ CODES",
            Modifier.fillMaxWidth(),
            border = Accent
        ) { if (!uiState.isReadingDtc) onReadDtc() }

        // RESET writes to the ECU (SSM2 clear-memory). Two-tap confirm: the first
        // tap arms it (red, "TAP AGAIN TO CLEAR CODES"), the second fires. Auto-
        // disarms after a few seconds so a stray later tap can't clear codes.
        var resetArmed by remember { mutableStateOf(false) }
        LaunchedEffect(resetArmed) {
            if (resetArmed) { delay(RESET_ARM_TIMEOUT_MS); resetArmed = false }
        }
        val busy = uiState.isResettingDtc || uiState.isReadingDtc
        DevActionButton(
            when {
                uiState.isResettingDtc -> "CLEARING…"
                resetArmed -> "TAP AGAIN TO CLEAR CODES"
                else -> "RESET"
            },
            Modifier.fillMaxWidth(),
            border = if (resetArmed) FailRed else Accent
        ) {
            if (!busy) {
                if (resetArmed) { resetArmed = false; onResetDtc() } else resetArmed = true
            }
        }
    }
}

private const val RESET_ARM_TIMEOUT_MS = 4000L

// The scrollable decoded-code body — shown in the card and the root fullscreen.
@Composable
internal fun DtcContent(uiState: ProtocolUiState) {
    val scroll = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(10.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (uiState.dtcStatus.isNotEmpty()) {
            Text(
                text = uiState.dtcStatus,
                color = InkPrimary,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(6.dp))
        }
        val hasCodes = uiState.dtcCurrent.isNotEmpty() || uiState.dtcStored.isNotEmpty()
        if (!hasCodes && uiState.dtcStatus.isEmpty()) {
            Text(
                text = if (uiState.isReadingDtc) "Reading…" else "No codes to show. Tap READ CODES.",
                color = NeutralGray,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
        }
        if (uiState.dtcCurrent.isNotEmpty()) {
            DtcSectionLabel("CURRENT (${uiState.dtcCurrent.size})")
            uiState.dtcCurrent.forEach { DtcLine(it) }
        }
        if (uiState.dtcStored.isNotEmpty()) {
            if (uiState.dtcCurrent.isNotEmpty()) Spacer(Modifier.height(8.dp))
            DtcSectionLabel("STORED (${uiState.dtcStored.size})")
            uiState.dtcStored.forEach { DtcLine(it) }
        }
    }
}

@Composable
private fun DtcSectionLabel(label: String) {
    Text(
        text = label,
        color = SectionGray,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.labelMedium
    )
}

@Composable
private fun DtcLine(text: String) {
    Text(
        text = text,
        color = InkPrimary,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall
    )
}
