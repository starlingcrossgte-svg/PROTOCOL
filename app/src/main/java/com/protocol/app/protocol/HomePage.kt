package com.protocol.app.protocol

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Image
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.protocol.app.R

// Page 0 — Home / main menu. Layout (top to bottom):
//   - One-line app subtitle + version
//   - Menu buttons: Settings, Flash, Diagnostics, Tuning,
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
            // Keep the dev console / simulator-port fields above the keyboard
            // when it opens (edge-to-edge stops the system auto-panning).
            .imePadding()
            .padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Top bar: swipe hints flank the logo so the page-navigation cues sit
        // up top next to the brand instead of at the bottom of the scroll. Both
        // hints carry equal weight so the logo stays centered. The "™" rides the
        // top of the wordmark as the (pending) trademark mark.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // The left arrow is the SAME "→" glyph as the right one, mirrored
            // horizontally (scaleX = -1). Using one character for both makes a
            // size/baseline/weight mismatch impossible — the monospace face
            // lacks ←/→ and Android was substituting them from different
            // fallback fonts, which is why the left arrow rendered smaller and
            // lower.
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.Start),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "→",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.scale(scaleX = -1f, scaleY = 1f)
                )
                Text(
                    text = "DTC Scan",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(id = R.drawable.protocol_logo),
                    contentDescription = "PROTOCOL",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.height(40.dp)
                )
                Text(
                    text = "™",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 9.sp,
                    modifier = Modifier
                        .align(Alignment.Top)
                        .padding(start = 2.dp)
                )
            }
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Real Time",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "→",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Main menu — destinations open as sub-pages.
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Labels name the FUNCTION, not the feature area. The three tuning
            // stages are deliberately distinguished: a calibration FILE is edited
            // in the ROM Table Editor, written to the module by Read / Flash
            // Firmware, and volatile RAM values are changed by Live RAM Tuning.
            HomeMenuButton(label = "Configuration") { onOpenSubPage(SubPage.Settings) }
            HomeMenuButton(label = "Read / Write ROM") { onOpenSubPage(SubPage.Flash) }
            HomeMenuButton(label = "Live RAM Tuning") { onOpenSubPage(SubPage.Tuning) }
            HomeMenuButton(label = "ROM Table Editor") { onOpenSubPage(SubPage.TableEditor) }
            HomeMenuButton(label = "Manual Adapter Control") { onOpenSubPage(SubPage.Developer) }
            HomeMenuButton(label = "Adapter Command Library") { onOpenSubPage(SubPage.Library) }
            // Pulse until opened once. Persisted, so it does not come back.
            HomeMenuButton(
                label = "How To Use",
                pulse = !uiState.settings.visitedHowToUse
            ) { onOpenSubPage(SubPage.Navigation) }
            HomeMenuButton(
                label = "About & License",
                pulse = !uiState.settings.visitedNotices
            ) { onOpenSubPage(SubPage.Notices) }
        }
        // Developer Mode is now its own page (Dev Mode button above) — no longer
        // inlined here.
    }
}

@Composable
private fun HomeMenuButton(
    label: String,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(8.dp),
    /** Animates the border white to red until visited. Fill and label untouched. */
    pulse: Boolean = false,
    onClick: () -> Unit
) {
    // Box-based to avoid Material3 Button's offscreen clipping layer and
    // ripple-indication layer. Rounded corners (8.dp) via background(shape) +
    // border(shape) — same curved style as the Dev console, but the home
    // button's own size (16.dp padding / bodyLarge) is kept. Label centered.
    val borderColor = if (pulse) {
        val transition = rememberInfiniteTransition(label = "unvisited")
        val phase by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1400, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "phase"
        )
        lerp(Accent, PulseRed, phase)
    } else {
        Accent
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(LocalButtonFill.current, shape)
            .border(1.dp, borderColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center
        )
    }
}

private val PulseRed = Color(0xFFE53935)

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
