package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Page 0 — Home / main menu. Layout (top to bottom):
//   - One-line app subtitle + version
//   - Four menu buttons (Settings, Flash, Diagnostics, Tuning)
//   - Test SSM2 Probe button (runs the probe; auto-discover wraps it
//     in the Activity when no adapter is connected)
//   - OutcomeCard (shown after any probe run)
//   - Dev panel — Clear/Copy/Export Log + RunLogCard. Visible only when
//     settings.devMode is on (toggled from Settings → Developer Mode).
//   - Swipe hint at the bottom

@Composable
internal fun HomePage(
    uiState: ProtocolUiState,
    onRunProbe: () -> Unit,
    onClearLog: () -> Unit,
    onCopyLog: () -> Unit,
    onExportLog: () -> Unit,
    onOpenSubPage: (SubPage) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "OpenPort 2.0  ·  Subaru SSM2 K-line  ·  v1.0",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Main menu — four destinations. Each opens a sub-page.
        // Wide tappable rows so they're glove-friendly under the dash.
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            HomeMenuButton(label = "Settings") { onOpenSubPage(SubPage.Settings) }
            HomeMenuButton(label = "Flash ECU") { onOpenSubPage(SubPage.Flash) }
            HomeMenuButton(label = "Diagnostics / CEL") { onOpenSubPage(SubPage.Diagnostics) }
            HomeMenuButton(label = "Minor Tuning") { onOpenSubPage(SubPage.Tuning) }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Test SSM2 Probe. The Activity wraps onRunProbe so taps while
        // disconnected trigger discoverAndConnect — see Protocol.kt.
        Button(
            onClick = onRunProbe,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF6A00),
                contentColor = Color.Black,
                disabledContainerColor = Color(0xFFFF6A00).copy(alpha = 0.5f),
                disabledContentColor = Color.Black.copy(alpha = 0.7f)
            ),
            shape = y2kCornerShape(),
            enabled = !uiState.isReadingLive && !uiState.isLogging &&
                uiState.connectionStatus is ConnectionStatus.Connected,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (uiState.isRunningProbe) "Probing..." else "Test SSM2 Probe",
                fontWeight = FontWeight.Bold
            )
        }

        // Outcome stays visible after any probe so the user can see the
        // last result without flipping into the dev panel.
        OutcomeCard(uiState)

        // Dev panel — Clear/Copy/Export Log + RunLogCard. Gated on
        // settings.devMode. Toggle in Settings → Developer Mode.
        if (uiState.settings.devMode) {
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SmallActionButton("Clear Log", onClearLog, Modifier.weight(1f))
                SmallActionButton("Copy Log", onCopyLog, Modifier.weight(1f))
                SmallActionButton("Export Log", onExportLog, Modifier.weight(1f))
            }
            RunLogCard(uiState.log)
        }

        SwipeHintRow()
    }
}

@Composable
private fun HomeMenuButton(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceBg,
            contentColor = InkPrimary
        ),
        // Y2K terminal-panel slant — cut the top-right and bottom-left
        // corners. Visible enough to read as "this is a tool, not a
        // generic Material 3 button" without being a gimmick.
        shape = y2kCornerShape(),
        border = BorderStroke(1.dp, Accent),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            horizontal = 16.dp,
            vertical = 16.dp
        ),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                label,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.weight(1f)
            )
            Text(
                "›",
                color = Accent,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        }
    }
}

@Composable
private fun SmallActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceBg,
            contentColor = Color.White
        ),
        shape = y2kCornerShape(),
        border = BorderStroke(1.dp, Accent),
        modifier = modifier
    ) { Text(text, color = Color.White) }
}

// Small hint shown only on Home — Live Data and Parameters don't need
// it (by the time the user reaches them they already know the swipe
// gesture works).
@Composable
private fun SwipeHintRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "swipe for Live Data →",
            color = NeutralGray,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace
        )
    }
}
