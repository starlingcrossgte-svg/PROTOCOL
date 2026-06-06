package com.protocol.app.protocol

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Page 0 of the pager — Diagnostics (swiped to from Home, left).
// Read-only SSM2 DTC read: one button + a response window. The raw request /
// reply bytes also land in the dev RAW BYTES log automatically (the transport
// logs them). Clear / Reset live in the THRESHOLD project, not here.

@Composable
internal fun DiagnosticsPage(
    uiState: ProtocolUiState,
    onReadDtc: () -> Unit,
    onCopyDtc: () -> Unit,
    onExportDtc: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "── DIAGNOSTICS ──",
            color = Accent,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "SSM2 trouble codes · read-only",
            color = NeutralGray,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.labelSmall
        )

        Spacer(Modifier.height(16.dp))
        SimpleButton(
            text = if (uiState.isReadingDtc) "READING…" else "READ CODES",
            onClick = { if (!uiState.isReadingDtc) onReadDtc() },
            shape = y2kCornerShape(),
            modifier = Modifier.fillMaxWidth()
        )

        if (uiState.dtcStatus.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = uiState.dtcStatus,
                color = InkPrimary,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }

        if (uiState.dtcCurrent.isNotEmpty() || uiState.dtcStored.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SimpleButton("Copy", onCopyDtc, y2kLeftCutShape(), modifier = Modifier.weight(1f))
                SimpleButton("Export CSV", onExportDtc, y2kRightCutShape(), modifier = Modifier.weight(1f))
            }
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "── RESPONSE ──",
            color = SectionGray,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))

        val scroll = rememberScrollState()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(SurfaceBg)
                .border(1.dp, BorderGray)
                .padding(10.dp)
                .verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val hasCodes = uiState.dtcCurrent.isNotEmpty() || uiState.dtcStored.isNotEmpty()
            if (!hasCodes) {
                Text(
                    text = if (uiState.isReadingDtc) "Reading…"
                    else "No codes to show. Tap READ CODES — raw bytes also appear in the dev RAW BYTES log.",
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
