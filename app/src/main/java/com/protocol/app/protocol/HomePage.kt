package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.protocol.app.R

// Page 0 — Home / main menu. Layout (top to bottom):
//   - One-line app subtitle + version
//   - Menu buttons: Garage, Settings, Flash, Diagnostics, Tuning,
//     and Developer (only when devMode is on)
//   - Swipe hint at the bottom
//
// The probe button, outcome card, and run log moved to the Developer
// sub-page when this commit landed. Reading Live Data still triggers
// auto-discover on the Live Data page, so non-dev users still have a
// straightforward path to connect.

@Composable
internal fun HomePage(
    uiState: ProtocolUiState,
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
        // Logo lives in the scrolling Home content now (no fixed header), so it
        // slides up with the page. Same art/size as before.
        Image(
            painter = painterResource(id = R.drawable.protocol_logo),
            contentDescription = "PROTOCOL",
            contentScale = ContentScale.Fit,
            modifier = Modifier.height(40.dp)
        )

        Text(
            text = "OpenPort 2.0  ·  Subaru SSM2 K-line  ·  v1.0",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Main menu — destinations open as sub-pages.
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            HomeMenuButton(label = "Garage") { onOpenSubPage(SubPage.Garage) }
            HomeMenuButton(label = "Settings") { onOpenSubPage(SubPage.Settings) }
            HomeMenuButton(label = "Flash ECU") { onOpenSubPage(SubPage.Flash) }
            HomeMenuButton(label = "Minor Tuning") { onOpenSubPage(SubPage.Tuning) }
            // Developer Mode — gated on the settings toggle so it stays
            // out of the way for non-debug use.
            if (uiState.settings.devMode) {
                HomeMenuButton(label = "Developer") { onOpenSubPage(SubPage.Developer) }
            }
        }

        SwipeHintRow()
    }
}

@Composable
private fun HomeMenuButton(label: String, onClick: () -> Unit) {
    // Box-based to avoid Material3 Button's offscreen clipping layer and
    // ripple-indication layer. Y2K corner cut still rendered correctly by
    // background(shape) + border(shape); no clip() needed because the
    // inner Row stays inside the padded rectangle.
    val shape = y2kCornerShape()
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceBg, shape)
            .border(1.dp, Accent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp)
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
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "← Diagnostics",
            color = NeutralGray,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = "Live Data →",
            color = NeutralGray,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace
        )
    }
}
