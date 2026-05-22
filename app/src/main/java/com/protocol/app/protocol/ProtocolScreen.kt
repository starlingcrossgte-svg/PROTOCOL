package com.protocol.app.protocol

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import coil.compose.AsyncImage
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.protocol.app.R
import com.protocol.app.openport2.EcuIdDecoder
import com.protocol.app.openport2.OpenPortCommand
import com.protocol.app.openport2.OpenPortCommandParser
import com.protocol.app.openport2.PollSample
import com.protocol.app.openport2.Ssm2DecodeBundle
import com.protocol.app.openport2.Ssm2EcmProbe
import com.protocol.app.openport2.Ssm2Frame
import com.protocol.app.openport2.Ssm2FrameParser
import com.protocol.app.openport2.Ssm2Pid
import com.protocol.app.openport2.Ssm2PidCategory
import com.protocol.app.openport2.Ssm2Pids
import com.protocol.app.openport2.TactrixCommandLog
import com.protocol.app.openport2.TactrixHex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Dark-mode palette. ScreenBg is the deepest layer; SurfaceBg sits one step
// up (header, cards); SurfaceAlt is for buttons + elevated chips. Text is
// pure white (InkPrimary) except for the few intentional muted bits
// (InkMuted) and accent colors. PassGreen/FailRed bumped saturation so they
// still read clearly against the dark backgrounds.
// Dark Y2K palette, lightened a touch from the previous pass so the
// background spikes have something to read against. Solid colors are
// the "low" end of each surface; the matching gradient brushes blend
// to a darker shade for depth (see headerBrush / surfaceBrush below).
private val ScreenBg    = Color(0xFF181B22)
private val SurfaceBg   = Color(0xFF22252C)
private val SurfaceAlt  = Color(0xFF2E323A)
private val BorderGray  = Color(0xFF3A3C42)
// Accent reverted from neon green to burnt orange — the "shaded" tone the
// user liked on the probe button. Less saturated than #FF6A00 so it reads
// with more depth and less retina burn. Used for non-text decoration
// only: hamburger lines, close-X strokes, header/footer stripes,
// edit-mode gauge border, drag-bar visuals.
private val Accent      = Color(0xFFB85419)
private val PassGreen   = Color(0xFF22C55E)
private val FailRed     = Color(0xFFEF4444)
private val NeutralGray = Color(0xFFB8B8C0)
private val SectionGray = Color(0xFFB8B8C0)
private val InkPrimary  = Color.White
private val InkMuted    = Color(0xFFB8B8C0)

// Subtle angular shape used on the home menu + Test SSM2 Probe button for
// the Y2K "terminal panel" feel. Sparing — most surfaces stay rounded.
private fun y2kCornerShape() = CutCornerShape(topEnd = 10.dp, bottomStart = 10.dp)

// Vertical gradient brushes — top of the surface a touch lighter than the
// bottom, gives buttons and panels visible depth without needing real
// shadows or textures.
private val headerBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
    colors = listOf(Color(0xFF2A2D34), Color(0xFF1A1C22))
)
private val surfaceBrush = androidx.compose.ui.graphics.Brush.verticalGradient(
    colors = listOf(Color(0xFF272A31), Color(0xFF1B1D24))
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ProtocolScreen(
    uiState: ProtocolUiState,
    onOpenSubPage: (SubPage) -> Unit,
    onCloseSubPage: () -> Unit,
    onToggleGaugeForPid: (String) -> Unit,
    onEnterEditMode: () -> Unit,
    onExitEditMode: () -> Unit,
    onRemoveGauge: (String) -> Unit,
    onResizeGauge: (String, Int, Int, Int, Int) -> Boolean,
    onRunProbe: () -> Unit,
    onStartReadingLive: () -> Unit,
    onStopReadingLive: () -> Unit,
    onStartLogging: () -> Unit,
    onStopLogging: () -> Unit,
    onClearLog: () -> Unit,
    onCopyLog: () -> Unit,
    onExportLog: () -> Unit,
    onClearSessionLog: () -> Unit,
    onCopySessionLog: () -> Unit,
    onExportSessionLog: () -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Hoisted here so the user's currently-visible page (Home vs Live Data)
    // is preserved when they pop into a sub-page and back.
    val pagerState = rememberPagerState(pageCount = { 2 })

    val subPageTitle: String? = when (uiState.activeSubPage) {
        SubPage.Parameters -> "Parameters"
        SubPage.Settings -> "Settings"
        SubPage.Flash -> "Flash ECU"
        SubPage.Diagnostics -> "Diagnostics / CEL"
        SubPage.Tuning -> "Minor Tuning"
        null -> null
    }

    // Sub-page BackHandler. LiveDataPage's edit-mode BackHandler is nested
    // deeper and stacks above this one when both could be relevant — but
    // openSubPage clears editMode anyway, so the two never both fire.
    BackHandler(enabled = uiState.activeSubPage != null) { onCloseSubPage() }

    Box(modifier = modifier.fillMaxSize().background(ScreenBg)) {
        // Layer 1 — user-chosen background image, if any. ContentScale.Crop
        // fills the screen, possibly cropping; the dim overlay keeps text
        // readable regardless of photo brightness.
        val bgUri = uiState.backgroundUri
        if (bgUri != null) {
            AsyncImage(
                model = bgUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
            )
        }

        // Layer 2 — Y2K spike decor. Sits over the photo (or the solid bg
        // if no photo). Low alpha so it whispers either way.
        Canvas(modifier = Modifier.fillMaxSize()) { drawY2kBackgroundDecor(this) }

        // Layer 3 — actual content.
        Column(modifier = Modifier.fillMaxSize()) {
            ProtocolHeader(
                subPageTitle = subPageTitle,
                onHamburger = { onOpenSubPage(SubPage.Parameters) },
                onClose = onCloseSubPage
            )

            // Body — main pager or sub-page content. Adapter pill is gone
            // from the chrome; discovery now triggers off the action
            // buttons (Test Probe / Read Live / Log Live).
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (uiState.activeSubPage) {
                    null -> HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        when (page) {
                            0 -> HomePage(
                                uiState = uiState,
                                onRunProbe = onRunProbe,
                                onClearLog = onClearLog,
                                onCopyLog = onCopyLog,
                                onExportLog = onExportLog,
                                onOpenSubPage = onOpenSubPage
                            )
                            else -> LiveDataPage(
                                uiState = uiState,
                                onStartReadingLive = onStartReadingLive,
                                onStopReadingLive = onStopReadingLive,
                                onStartLogging = onStartLogging,
                                onStopLogging = onStopLogging,
                                onClearSessionLog = onClearSessionLog,
                                onCopySessionLog = onCopySessionLog,
                                onExportSessionLog = onExportSessionLog,
                                onEnterEditMode = onEnterEditMode,
                                onExitEditMode = onExitEditMode,
                                onRemoveGauge = onRemoveGauge,
                                onResizeGauge = onResizeGauge
                            )
                        }
                    }
                    SubPage.Parameters -> ParametersBody(
                        uiState = uiState,
                        onTogglePid = onToggleGaugeForPid
                    )
                    SubPage.Settings -> SettingsBody(
                        uiState = uiState,
                        onPickBackground = onPickBackground,
                        onClearBackground = onClearBackground
                    )
                    SubPage.Flash, SubPage.Diagnostics, SubPage.Tuning ->
                        StubBody(page = uiState.activeSubPage!!)
                }
            }
        }
    }
}

// Decorative background — sharp angular black "spikes" scattered around
// the edges of the screen for Y2K terminal-panel depth without overdoing
// it. Drawn via a Canvas layer beneath the content Column. Alpha kept
// low so the shapes whisper rather than shout against whatever surface
// (default ScreenBg or a user-picked photo) sits under them.
private fun drawY2kBackgroundDecor(scope: androidx.compose.ui.graphics.drawscope.DrawScope) = with(scope) {
    val w = size.width
    val h = size.height
    val deep = Color(0xFF000000).copy(alpha = 0.55f)

    fun spike(points: List<Pair<Float, Float>>) {
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
            close()
        }
        drawPath(path = p, color = deep)
    }

    // Top-left horizontal shard pointing right.
    spike(listOf(
        0f to h * 0.04f,
        w * 0.32f to h * 0.07f,
        w * 0.18f to h * 0.09f,
        0f to h * 0.08f
    ))

    // Right edge thin shard pointing left.
    spike(listOf(
        w to h * 0.22f,
        w * 0.66f to h * 0.26f,
        w * 0.82f to h * 0.28f,
        w to h * 0.27f
    ))

    // Left mid-ish stubby triangle pointing right.
    spike(listOf(
        0f to h * 0.50f,
        w * 0.18f to h * 0.52f,
        0f to h * 0.54f
    ))

    // Bottom-right diagonal slash.
    spike(listOf(
        w to h * 0.78f,
        w * 0.62f to h * 0.86f,
        w * 0.78f to h * 0.88f,
        w to h * 0.83f
    ))

    // Bottom-left small chevron.
    spike(listOf(
        0f to h * 0.93f,
        w * 0.22f to h * 0.95f,
        w * 0.10f to h * 0.97f,
        0f to h * 0.96f
    ))
}

// ─── Shared header / footer ─────────────────────────────────────────────────

@Composable
private fun ProtocolHeader(
    subPageTitle: String?,
    onHamburger: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(brush = headerBrush)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        // Sub-page title on the left (none on the main pager — the logo
        // alone identifies the app and the visible page is implied by
        // content / swipe state).
        if (subPageTitle != null) {
            Text(
                text = subPageTitle,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }

        // Logo lives in the center of every header, always visible.
        Image(
            painter = painterResource(id = R.drawable.protocol_logo),
            contentDescription = "PROTOCOL",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .height(64.dp)
                .align(Alignment.Center)
        )

        // Hamburger on the main pager; close X on any sub-page.
        if (subPageTitle == null) {
            HamburgerButton(
                onClick = onHamburger,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        } else {
            CloseButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
    // Thin neon stripe under the header — replaces the old grey divider
    // and gives the Y2K terminal feel.
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Accent))
}


// ─── Parameters page ────────────────────────────────────────────────────────
//
// Full-page list (not a drawer). Tapping a parameter toggles whether its
// gauge is on the Live Data page; a checkmark marks parameters that already
// have a gauge present. The parameter never disappears from the list — that
// is intentional, so re-tapping puts the gauge back. Hardware back closes
// the page via BackHandler.

@Composable
private fun ParametersBody(
    uiState: ProtocolUiState,
    onTogglePid: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        val onLiveData = uiState.pidIdsOnLiveData
        val grouped = Ssm2Pids.DEFAULT_DEMO_PIDS.groupBy { it.category }

        CategoryHeader("ECU")
        val ecuPids = grouped[Ssm2PidCategory.ECU].orEmpty()
        if (ecuPids.isEmpty()) {
            EmptyCategoryRow("No ECU parameters available.")
        } else {
            for (pid in ecuPids) {
                ParameterRow(
                    pid = pid,
                    checked = pid.id in onLiveData,
                    onClick = { onTogglePid(pid.id) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        CategoryHeader("TCM")
        val tcmPids = grouped[Ssm2PidCategory.TCM].orEmpty()
        if (tcmPids.isEmpty()) {
            EmptyCategoryRow("No TCM parameters available yet.")
        } else {
            for (pid in tcmPids) {
                ParameterRow(
                    pid = pid,
                    checked = pid.id in onLiveData,
                    onClick = { onTogglePid(pid.id) }
                )
            }
        }
    }
}

@Composable
private fun CategoryHeader(label: String) {
    Text(
        "── $label ──",
        color = SectionGray,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

@Composable
private fun EmptyCategoryRow(text: String) {
    Text(
        text,
        color = NeutralGray,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
    )
}

@Composable
private fun ParameterRow(
    pid: Ssm2Pid,
    checked: Boolean,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                if (checked) Checkmark()
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    pid.displayName,
                    color = InkPrimary,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium
                )
                if (pid.longName != pid.displayName) {
                    Text(
                        pid.longName,
                        color = NeutralGray,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
            Text(
                pid.unit,
                color = NeutralGray,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(BorderGray.copy(alpha = 0.4f))
        )
    }
}

@Composable
private fun Checkmark() {
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = 2.5f.dp.toPx()
        drawLine(
            color = PassGreen,
            start = Offset(size.width * 0.18f, size.height * 0.55f),
            end = Offset(size.width * 0.42f, size.height * 0.80f),
            strokeWidth = stroke
        )
        drawLine(
            color = PassGreen,
            start = Offset(size.width * 0.42f, size.height * 0.80f),
            end = Offset(size.width * 0.85f, size.height * 0.25f),
            strokeWidth = stroke
        )
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .size(width = 44.dp, height = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val stroke = 2.5f.dp.toPx()
            drawLine(
                color = Accent,
                start = Offset(size.width * 0.18f, size.height * 0.18f),
                end = Offset(size.width * 0.82f, size.height * 0.82f),
                strokeWidth = stroke
            )
            drawLine(
                color = Accent,
                start = Offset(size.width * 0.82f, size.height * 0.18f),
                end = Offset(size.width * 0.18f, size.height * 0.82f),
                strokeWidth = stroke
            )
        }
    }
}

// Three-line hamburger icon, click area sized to a comfortable tap target
// without inflating the header height. Stage 1: no-op stub. Stage 2: wired
// to the Parameters drawer.
@Composable
private fun HamburgerButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .size(width = 44.dp, height = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(2.dp)
                        .background(Accent)
                )
            }
        }
    }
}

// ─── Page 0: Home ────────────────────────────────────────────────────────────
//
// Replaces the old Debug page. Layout (top to bottom):
//   - Text logo + app version
//   - Four menu buttons that push sub-pages (Settings, Flash, Diagnostics,
//     Tuning — each currently a Coming-soon stub)
//   - Test SSM2 Probe button. Hidden inside it: 10 quick taps (each
//     within 1.5 s of the previous) reveal the dev-mode "Run Log" card
//     and the Clear / Copy / Export log buttons. The probe still fires
//     normally each tap; the VM's internal re-entry guard makes back-
//     to-back taps a no-op while a probe is in flight.
//   - OutcomeCard (shown after any probe run)
//   - 10-tap-revealed dev section: Clear/Copy/Export Log + RunLogCard
//   - Swipe hint at the bottom

private const val RUN_LOG_UNLOCK_TAP_COUNT = 10
private const val RUN_LOG_UNLOCK_TAP_WINDOW_MS = 1500L

@Composable
private fun HomePage(
    uiState: ProtocolUiState,
    onRunProbe: () -> Unit,
    onClearLog: () -> Unit,
    onCopyLog: () -> Unit,
    onExportLog: () -> Unit,
    onOpenSubPage: (SubPage) -> Unit
) {
    // Easter-egg state. Lives in the composable's remember scope so it
    // survives recomposition but resets on process restart — that's fine,
    // we don't want the dev panel pinned forever just because someone
    // mashed the button once.
    var rapidTapCount by remember { mutableStateOf(0) }
    var lastTapMs by remember { mutableStateOf(0L) }
    var runLogRevealed by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // App identity sits in the header logo. Home gets a single
        // subtitle line so the user knows what build they're on without
        // taking up real estate with stacked text.
        Text(
            text = "OpenPort 2.0  ·  Subaru SSM2 K-line  ·  v1.0",
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )

        // Main menu — four destinations. Each opens a sub-page (stub for
        // now). Wide tappable rows so they're glove-friendly under the dash.
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

        // Test SSM2 Probe — also the unlock surface for the run log.
        Button(
            onClick = {
                val now = System.currentTimeMillis()
                rapidTapCount = if (now - lastTapMs < RUN_LOG_UNLOCK_TAP_WINDOW_MS) {
                    rapidTapCount + 1
                } else {
                    1
                }
                lastTapMs = now
                if (rapidTapCount >= RUN_LOG_UNLOCK_TAP_COUNT && !runLogRevealed) {
                    runLogRevealed = true
                }
                onRunProbe()
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFFF6A00),
                contentColor = Color.Black,
                disabledContainerColor = Color(0xFFFF6A00).copy(alpha = 0.5f),
                disabledContentColor = Color.Black.copy(alpha = 0.7f)
            ),
            shape = y2kCornerShape(),
            // Keep the button click-enabled while the probe runs so the
            // unlock counter still increments on rapid taps — the VM's
            // runProbe guard makes the actual probe call idempotent.
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
        // last result without digging into the dev panel.
        OutcomeCard(uiState)

        // Dev panel — hidden until the 10-tap unlock. Same controls as the
        // old Debug page so nothing is lost.
        if (runLogRevealed) {
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

// Settings sub-page. First setting wired up: a user-chosen background
// image. More settings (units, baud override, etc.) get added here in
// future phases. Title + close X live in the shared header.
@Composable
private fun SettingsBody(
    uiState: ProtocolUiState,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        CategoryHeader("BACKGROUND")
        Text(
            text = if (uiState.backgroundUri == null)
                "No custom background. App uses the default Y2K dark surface."
            else
                "Custom background active. Tap below to change or clear.",
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )

        Button(
            onClick = onPickBackground,
            colors = ButtonDefaults.buttonColors(
                containerColor = SurfaceBg,
                contentColor = InkPrimary
            ),
            shape = y2kCornerShape(),
            border = BorderStroke(1.dp, Accent),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 16.dp, vertical = 14.dp
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = if (uiState.backgroundUri == null) "Choose Background"
                else "Change Background",
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                color = Color.White
            )
        }

        if (uiState.backgroundUri != null) {
            Button(
                onClick = onClearBackground,
                colors = ButtonDefaults.buttonColors(
                    containerColor = SurfaceAlt,
                    contentColor = InkPrimary
                ),
                shape = y2kCornerShape(),
                border = BorderStroke(1.dp, BorderGray),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 16.dp, vertical = 14.dp
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "Clear Background",
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }

        Text(
            text = "Pick any image from your gallery. A 50% dark overlay is applied automatically so text stays readable against bright photos.",
            color = NeutralGray,
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

// Generic "coming soon" body. Title + close X live in the shared header;
// this composable only renders the body content.
@Composable
private fun StubBody(page: SubPage) {
    val (blurb, eta) = when (page) {
        SubPage.Settings -> Pair(
            "Tunable app preferences — units (F/C), session log size, gauge layout reset, baud override for adapter testing.",
            "Lightweight — days of work once the list of options is locked."
        )
        SubPage.Flash -> Pair(
            "Full ECU reflash over OpenPort. Seed/key security access, flash-mode init, page-aligned erase + write, checksum, verify, ECU reset.",
            "Substantial — a multi-week project. The lower layers (USB + Tactrix line protocol + frame parser) are already in place; the flash sequence itself still needs to be written and tested very carefully."
        )
        SubPage.Diagnostics -> Pair(
            "Read stored DTCs from ECM (and TCM later) and decode them to P-codes with descriptions. SSM2 has a dedicated query for this; we'd sweep modules and group results.",
            "Moderate — couple of weeks, mostly because the DTC label table has to be hand-curated per family."
        )
        SubPage.Tuning -> Pair(
            "Live RAM-resident tunables: rev limiter, fuel cutoff, idle target, etc. Reads via SSM2 0xA8, writes via 0xB8. Addresses come from the per-ECU calibration definitions (EcuFlash/RomRaider XML).",
            "Few weeks once we settle on which parameters are in scope and pull the EZ30R definitions in."
        )
        SubPage.Parameters -> Pair("", "")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Coming soon",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = blurb,
            color = InkPrimary,
            style = MaterialTheme.typography.bodyMedium
        )
        CategoryHeader("EFFORT")
        Text(
            text = eta,
            color = InkPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}

// Small hint shown only on the Debug page (the screen the app opens on).
// Live Data and Parameters don't need it — by the time the user reaches
// them they already know the swipe gesture works.
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

// ─── Page 1: Live Data ───────────────────────────────────────────────────────

@Composable
private fun LiveDataPage(
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
    onResizeGauge: (String, Int, Int, Int, Int) -> Boolean
) {
    // In edit mode, hardware back exits edit instead of leaving the app, and
    // the page scroll is locked so vertical drags on the gauges feed the drag
    // bars rather than scrolling the page out from under them.
    BackHandler(enabled = uiState.editMode) { onExitEditMode() }

    val connected = uiState.connectionStatus is ConnectionStatus.Connected
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState, enabled = !uiState.editMode)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Mode buttons — slim row, just under the header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ModeButton(
                label = if (uiState.isReadingLive) "Reading Live ●" else "Read Live Data",
                active = uiState.isReadingLive,
                enabled = connected && !uiState.isRunningProbe,
                onClick = {
                    if (uiState.isReadingLive) onStopReadingLive() else onStartReadingLive()
                },
                modifier = Modifier.weight(1f)
            )
            ModeButton(
                label = if (uiState.isLogging) "Logging ●" else "Log Live Data",
                active = uiState.isLogging,
                enabled = connected && !uiState.isRunningProbe,
                onClick = {
                    if (uiState.isLogging) onStopLogging() else onStartLogging()
                },
                modifier = Modifier.weight(1f)
            )
        }

        // Gauges grid — absolute-positioned snap grid. Long-press a gauge to
        // enter edit mode (drag bars on the four sides resize by 1 cell each;
        // center X removes the gauge). Tapping any empty grid area exits edit.
        SnapGaugeGrid(
            uiState = uiState,
            onEnterEditMode = onEnterEditMode,
            onExitEditMode = onExitEditMode,
            onRemoveGauge = onRemoveGauge,
            onResizeGauge = onResizeGauge
        )

        // Status line — when a gauge was tapped recently, show its full
        // parameter name (set by the ViewModel for 5s, then cleared);
        // otherwise fall back to the connection / activity status.
        val statusLine = uiState.tappedParamLongName ?: uiState.statusMessage
        if (statusLine.isNotBlank()) {
            Text(
                text = statusLine,
                color = InkPrimary,
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Log action row — slim, just above the log
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Session Log (${uiState.sessionLog.size})",
                color = InkPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
            SmallLogButton("Clear", onClearSessionLog)
            SmallLogButton("Copy", onCopySessionLog)
            SmallLogButton("Export CSV", onExportSessionLog)
        }

        SessionLogCard(uiState)
    }
}

@Composable
private fun ModeButton(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (active) PassGreen else SurfaceAlt,
            contentColor = if (active) Color.White else InkPrimary,
            disabledContainerColor = SurfaceAlt.copy(alpha = 0.5f),
            disabledContentColor = NeutralGray
        ),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, if (active) PassGreen else BorderGray),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        modifier = modifier
    ) {
        Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
}

// ─── Snap grid + edit mode ───────────────────────────────────────────────────
//
// Gauges are rendered at absolute (col,row) offsets so 1x1, 2x1, 1x2, and
// 2x2 spans all work. Long-press a gauge to enter edit mode; tap any empty
// area of the grid to exit. In edit mode each gauge gets a drag bar on each
// of its four edges (snap to ±1 cell when the drag accumulator crosses half
// a cell, rejected if it would overlap a neighbor or leave the grid) and a
// center X button to remove. The VM's resizeGauge already enforces
// canPlace/overlap rules, so this UI just hands it the desired (col,row,
// width,height) and watches the boolean it returns.

private val GAUGE_GRID_SPACING = 6.dp
private const val GAUGE_CELL_ASPECT = 0.7f          // height/width of a 1x1 cell
private val EDIT_BAR_THICKNESS = 28.dp              // touch target on each edge

private enum class DragAxis { Horizontal, Vertical }

@Composable
private fun SnapGaugeGrid(
    uiState: ProtocolUiState,
    onEnterEditMode: () -> Unit,
    onExitEditMode: () -> Unit,
    onRemoveGauge: (String) -> Unit,
    onResizeGauge: (String, Int, Int, Int, Int) -> Boolean
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

    val pidById = remember { Ssm2Pids.DEFAULT_DEMO_PIDS.associateBy { it.id } }
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
                val pid = pidById[entry.pidId] ?: continue
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
                    active = active,
                    editMode = editMode,
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

@Composable
private fun GaugeTile(
    entry: GaugeLayoutEntry,
    pid: Ssm2Pid,
    rawValue: Double?,
    active: Boolean,
    editMode: Boolean,
    cellUnitWidthPx: Float,
    cellUnitHeightPx: Float,
    onEnterEdit: () -> Unit,
    onRemove: () -> Unit,
    onResize: (col: Int, row: Int, width: Int, height: Int) -> Boolean,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val baseBg = if (active) SurfaceBg else SurfaceAlt
    val borderColor = if (editMode) Accent else BorderGray
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
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    pid.displayName,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = rawValue?.let {
                        ProtocolLogFormatter.formatPidValueText(pid.id, it)
                    } ?: "--",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = valueFontSize,
                    maxLines = 1,
                    softWrap = false
                )
                Text(
                    pid.unit,
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace
                )
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

        // Center X — removes the gauge entirely. Sits above the value text;
        // the user can still see the parameter name behind the red circle so
        // they know what they're about to delete.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .size(40.dp)
                .background(Color(0xCCEF4444), CircleShape)
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.size(18.dp)) {
                val stroke = 2.5f.dp.toPx()
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

@Composable
private fun SmallLogButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = SurfaceAlt, contentColor = InkPrimary),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, BorderGray),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SessionLogCard(uiState: ProtocolUiState) {
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
                Text(
                    text = ProtocolLogFormatter.formatSessionLogCleanText(
                        uiState.sessionLog,
                        uiState.pidIdsOnLiveData
                    ),
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    softWrap = false
                )
            }
        }
    }
}

private val csvTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

// ─── Shared composables ──────────────────────────────────────────────────────


@Composable
private fun SmallActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = SurfaceAlt, contentColor = InkPrimary),
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, BorderGray),
        modifier = modifier
    ) { Text(text) }
}

// ─── Outcome & Run Log (debug page) ──────────────────────────────────────────

@Composable
private fun OutcomeCard(uiState: ProtocolUiState) {
    val outcome = uiState.lastOutcome ?: return
    val (pillText, pillColor) = pillFor(outcome)
    val bundle = uiState.ssm2DecodeBundle
    val lastStep = uiState.log.lastOrNull()
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceBg),
        border = BorderStroke(1.dp, BorderGray)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.background(pillColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(pillText, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Text("Last Probe Outcome", color = InkPrimary, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
            }
            HumanSummarySection(outcome, bundle, uiState.attStepDurationMs)
            Ssm2DecodeSection(outcome, bundle)
            OpenPortFrameSection(outcome, bundle)
            LowLevelUsbSection(outcome, lastStep)
        }
    }
}

private fun pillFor(outcome: Ssm2EcmProbe.ProbeOutcome): Pair<String, Color> = when (outcome) {
    Ssm2EcmProbe.ProbeOutcome.SUCCESS_ECU_REPLIED -> "ECU REPLIED" to PassGreen
    Ssm2EcmProbe.ProbeOutcome.FAIL_INIT_STEP -> "INIT FAIL" to FailRed
    Ssm2EcmProbe.ProbeOutcome.FAIL_NO_ECU_REPLY -> "NO ECU REPLY" to FailRed
    Ssm2EcmProbe.ProbeOutcome.FAIL_TRANSPORT -> "TRANSPORT FAIL" to FailRed
    Ssm2EcmProbe.ProbeOutcome.FAIL_USB_DISCONNECTED -> "USB DISCONNECTED" to FailRed
}

@Composable
private fun HumanSummarySection(
    outcome: Ssm2EcmProbe.ProbeOutcome,
    bundle: Ssm2DecodeBundle?,
    attStepDurationMs: Long?
) {
    SectionHeader("HUMAN SUMMARY")
    KvRow("Result", outcome.name)
    val ecuId = bundle?.ecuId
    val response = bundle?.response
    val placeholder = "—"
    val isTruncated = response?.truncated == true
    KvRow("ECU ID", ecuId?.ecuIdHex ?: placeholder)
    KvRow("Internal ID", if (isTruncated)
        "not in received bytes — response partially assembled"
    else
        EcuIdDecoder.INTERNAL_ID_NOT_PRESENT
    )
    KvRow("Calibration", ecuId?.calibrationBytes?.let(TactrixHex::bytesToHex) ?: placeholder)
    KvRow("SSM ID", ecuId?.ssmIdBytes?.let(TactrixHex::bytesToHex) ?: placeholder)
    KvRow("Source module", response?.let { "${Ssm2FrameParser.moduleLabel(it.source)} (0x%02X)".format(it.source) } ?: placeholder)
    KvRow("Header detected", response?.let { "%02X %02X %02X".format(it.format, it.destination, it.source) } ?: placeholder)
    KvRow("Response time", attStepDurationMs?.let { "$it ms" } ?: placeholder)
    if (isTruncated && response != null) {
        KvRow("Response status", "PARTIAL — ${response.payload.size} of ${response.length} bytes received")
        KvRow("ECU ID bytes", "found at expected offset")
        KvRow("Full assembly", "not complete — multi-frame fix pending")
    }
}

@Composable
private fun Ssm2DecodeSection(outcome: Ssm2EcmProbe.ProbeOutcome, bundle: Ssm2DecodeBundle?) {
    SectionHeader("SSM2 DECODE")
    if (bundle == null) { BodyMono("att3 never reached — no SSM2 frame transmitted."); return }
    val req = bundle.request
    if (req != null) {
        BodyMono("Request : ${TactrixHex.bytesToHex(req.rawBytes)}")
        BodyMono("  format = 0x%02X".format(req.format))
        BodyMono("  destination = 0x%02X (${Ssm2FrameParser.moduleLabel(req.destination)})".format(req.destination))
        BodyMono("  source = 0x%02X (${Ssm2FrameParser.moduleLabel(req.source)})".format(req.source))
        BodyMono("  length = 0x%02X (${req.length} byte${if (req.length == 1) "" else "s"})".format(req.length))
        if (req.payload.isNotEmpty()) {
            val cmd = req.payload[0].toInt() and 0xFF
            BodyMono("  command = 0x%02X (${Ssm2FrameParser.commandLabel(cmd)})".format(cmd))
        }
        BodyMono(checksumLine(req))
    } else { BodyMono("Request : (not parsed)") }
    val rsp = bundle.response
    if (rsp != null) {
        BodyMono("Response: ${TactrixHex.bytesToHex(rsp.rawBytes)}")
        BodyMono("  format = 0x%02X".format(rsp.format))
        BodyMono("  destination = 0x%02X (${Ssm2FrameParser.moduleLabel(rsp.destination)})".format(rsp.destination))
        BodyMono("  source = 0x%02X (${Ssm2FrameParser.moduleLabel(rsp.source)})".format(rsp.source))
        BodyMono(lengthLine(rsp))
        if (rsp.payload.isNotEmpty()) {
            val code = rsp.payload[0].toInt() and 0xFF
            BodyMono("  response = 0x%02X (${Ssm2FrameParser.commandLabel(code)})".format(code))
        }
        BodyMono(checksumLine(rsp))
    } else { BodyMono("Response: not parsed — no vehicle frame received.") }
}

private fun lengthLine(frame: Ssm2Frame): String {
    val declared = frame.length; val received = frame.payload.size
    return if (frame.truncated || declared != received)
        "  length = 0x%02X ($declared declared, $received received — truncated)".format(declared)
    else
        "  length = 0x%02X ($declared byte${if (declared == 1) "" else "s"})".format(declared)
}

private fun checksumLine(frame: Ssm2Frame): String = when {
    frame.truncated || frame.checksum < 0 -> "  checksum = (not received — truncated)"
    frame.checksumValid -> "  checksum = 0x%02X (valid)".format(frame.checksum)
    else -> "  checksum = 0x%02X (invalid)".format(frame.checksum)
}

@Composable
private fun OpenPortFrameSection(outcome: Ssm2EcmProbe.ProbeOutcome, bundle: Ssm2DecodeBundle?) {
    SectionHeader("OPENPORT FRAME")
    if (bundle == null) { BodyMono("att3 never reached."); return }
    BodyMono("att3 ack (aro)        : ${if (bundle.aroAcknowledged) "acknowledged" else "not acknowledged"}")
    BodyMono("Vehicle frame (ar3)   : ${if (bundle.ar3FrameDetected) "detected (channel 3)" else "not detected"}")
    if (bundle.extractedFrameHex.isNotBlank())
        BodyMono("Extracted payload     : ${bundle.extractedFrameHex}")
    else
        BodyMono("Extracted payload     : (none)")
}

@Composable
private fun LowLevelUsbSection(outcome: Ssm2EcmProbe.ProbeOutcome, lastStep: TactrixCommandLog?) {
    SectionHeader("LOW LEVEL USB / OPENPORT")
    if (lastStep == null) { BodyMono("No step recorded."); return }
    BodyMono("Step       : ${lastStep.stepIndex} — ${lastStep.stepLabel}")
    BodyMono("Request    : ${lastStep.requestAscii.ifBlank { "(empty)" }}")
    BodyMono("USB hex out: ${lastStep.requestHex.ifBlank { "(empty)" }}")
    val cmd = OpenPortCommandParser.parseOpenPortCommand(lastStep.requestAscii)
    val payloadLen = cmd?.payloadLen
    if (payloadLen != null && payloadLen > 0) {
        val hex = lastStep.requestHex.trim().split(" ").filter { it.isNotBlank() }
        if (hex.size >= payloadLen) BodyMono("Payload    : ${hex.takeLast(payloadLen).joinToString(" ")}")
    }
    BodyMono("USB hex in : ${lastStep.responseHex.ifBlank { "(empty)" }}")
    BodyMono("Duration   : ${lastStep.durationMs} ms")
}

@Composable
private fun SectionHeader(text: String) {
    Text("── $text ──", color = SectionGray, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun KvRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label.padEnd(16), color = InkPrimary, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(": $value", color = InkPrimary, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BodyMono(text: String) {
    Text(text, color = InkPrimary, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun RunLogCard(log: List<TactrixCommandLog>) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14161A)),
        border = BorderStroke(1.dp, BorderGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Run Log", color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            if (log.isEmpty()) {
                Text("No probe runs yet.", color = InkMuted, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(modifier = Modifier.fillMaxWidth().height(520.dp).verticalScroll(rememberScrollState())) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Text(formatLog(log), color = Color.White, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, softWrap = false)
                    }
                }
            }
        }
    }
}

// ─── Log formatters ──────────────────────────────────────────────────────────

private fun formatLog(log: List<TactrixCommandLog>): String {
    val sb = StringBuilder()
    for (entry in log) appendStepBlock(sb, entry)
    return sb.toString()
}

private fun appendStepBlock(sb: StringBuilder, entry: TactrixCommandLog) {
    sb.append("[").append(entry.stepIndex).append("] ").append(entry.stepLabel).append("\n")
    sb.append("  REQ ASCII : ").append(entry.requestAscii).append("\n")
    sb.append("  REQ HEX   : ").append(entry.requestHex).append("\n")
    sb.append("  RSP ASCII : ").append(entry.responseAscii).append("\n")
    sb.append("  RSP HEX   : ").append(entry.responseHex).append("\n")
    val parsed = OpenPortCommandParser.parseOpenPortCommand(entry.requestAscii)
    sb.append("  PARSED CMD: ").append(parsedCmdLine(parsed)).append("\n")
    sb.append("  TIME      : ").append(entry.durationMs).append(" ms\n")
    sb.append("  OUTCOME   : ").append(entry.outcome.name).append("\n")
    if (entry.notes.isNotBlank()) sb.append("  NOTES     : ").append(entry.notes).append("\n")
    sb.append("\n")
}

private fun parsedCmdLine(cmd: OpenPortCommand?): String {
    if (cmd == null) return "(no command on the wire)"
    val ch = cmd.channel?.let { "$it" } ?: "-"
    val reqId = cmd.reqId?.let { "$it" } ?: "-"
    val timeout = cmd.timeoutMicros?.let { "${it}µs" } ?: "-"
    val payloadLen = cmd.payloadLen?.let { "$it" } ?: "-"
    return "verb=${cmd.verb} channel=$ch payloadLen=$payloadLen timeout=$timeout reqId=$reqId"
}

object ProtocolLogFormatter {

    fun formatForExport(uiState: ProtocolUiState): String {
        val sb = StringBuilder()
        sb.append("========================================\n")
        sb.append("PROTOCOL — OpenPort SSM2 ECM Probe Log\n")
        sb.append("Exported: ").append(timestampNow()).append("\n")
        sb.append("========================================\n\n")
        sb.append("==== HUMAN SUMMARY ====\n")
        val outcome = uiState.lastOutcome
        val bundle = uiState.ssm2DecodeBundle
        val ecuId = bundle?.ecuId
        val response = bundle?.response
        val isTruncated = response?.truncated == true
        sb.append(kv("Result", outcome?.name ?: "(no run)"))
        sb.append(kv("ECU ID", ecuId?.ecuIdHex ?: "—"))
        sb.append(kv("Internal ID", if (isTruncated)
            "not in received bytes — response partially assembled"
        else
            EcuIdDecoder.INTERNAL_ID_NOT_PRESENT))
        sb.append(kv("Calibration", ecuId?.calibrationBytes?.let(TactrixHex::bytesToHex) ?: "—"))
        sb.append(kv("SSM ID", ecuId?.ssmIdBytes?.let(TactrixHex::bytesToHex) ?: "—"))
        sb.append(kv("Source module", response?.let { "${Ssm2FrameParser.moduleLabel(it.source)} (0x%02X)".format(it.source) } ?: "—"))
        sb.append(kv("Header detected", response?.let { "%02X %02X %02X".format(it.format, it.destination, it.source) } ?: "—"))
        sb.append(kv("Response time", uiState.attStepDurationMs?.let { "$it ms" } ?: "—"))
        if (isTruncated && response != null) {
            sb.append(kv("Response status", "PARTIAL — ${response.payload.size} of ${response.length} bytes received"))
            sb.append(kv("ECU ID bytes", "found at expected offset"))
            sb.append(kv("Full assembly", "not complete — multi-frame fix pending"))
        }
        sb.append("\n")
        sb.append("==== SSM2 DECODE ====\n")
        if (bundle == null) {
            sb.append("att3 never reached — no SSM2 frame transmitted.\n")
        } else {
            val req = bundle.request
            if (req != null) {
                sb.append("Request : ").append(TactrixHex.bytesToHex(req.rawBytes)).append("\n")
                sb.append("  format = 0x%02X\n".format(req.format))
                sb.append("  destination = 0x%02X (${Ssm2FrameParser.moduleLabel(req.destination)})\n".format(req.destination))
                sb.append("  source = 0x%02X (${Ssm2FrameParser.moduleLabel(req.source)})\n".format(req.source))
                sb.append("  length = 0x%02X (${req.length} byte${if (req.length == 1) "" else "s"})\n".format(req.length))
                if (req.payload.isNotEmpty()) {
                    val cmd = req.payload[0].toInt() and 0xFF
                    sb.append("  command = 0x%02X (${Ssm2FrameParser.commandLabel(cmd)})\n".format(cmd))
                }
                sb.append(checksumLine(req)).append("\n")
            } else { sb.append("Request : (not parsed)\n") }
            val rsp = bundle.response
            if (rsp != null) {
                sb.append("Response: ").append(TactrixHex.bytesToHex(rsp.rawBytes)).append("\n")
                sb.append("  format = 0x%02X\n".format(rsp.format))
                sb.append("  destination = 0x%02X (${Ssm2FrameParser.moduleLabel(rsp.destination)})\n".format(rsp.destination))
                sb.append("  source = 0x%02X (${Ssm2FrameParser.moduleLabel(rsp.source)})\n".format(rsp.source))
                sb.append(lengthLine(rsp)).append("\n")
                if (rsp.payload.isNotEmpty()) {
                    val code = rsp.payload[0].toInt() and 0xFF
                    sb.append("  response = 0x%02X (${Ssm2FrameParser.commandLabel(code)})\n".format(code))
                }
                sb.append(checksumLine(rsp)).append("\n")
            } else { sb.append("Response: not parsed — no vehicle frame received.\n") }
        }
        sb.append("\n")
        sb.append("==== OPENPORT FRAME ====\n")
        if (bundle == null) {
            sb.append("att3 never reached.\n")
        } else {
            sb.append(kv("att3 ack (aro)", if (bundle.aroAcknowledged) "acknowledged" else "not acknowledged"))
            sb.append(kv("Vehicle frame (ar3)", if (bundle.ar3FrameDetected) "detected (channel 3)" else "not detected"))
            sb.append(kv("Extracted payload", bundle.extractedFrameHex.ifBlank { "(none)" }))
        }
        sb.append("\n")
        sb.append("==== LOW LEVEL USB / OPENPORT (final step) ====\n")
        val lastStep = uiState.log.lastOrNull()
        if (lastStep == null) {
            sb.append("(no run)\n")
        } else {
            sb.append(kv("Step", "${lastStep.stepIndex} — ${lastStep.stepLabel}"))
            sb.append(kv("Request", lastStep.requestAscii.ifBlank { "(empty)" }))
            sb.append(kv("USB hex out", lastStep.requestHex.ifBlank { "(empty)" }))
            val cmd = OpenPortCommandParser.parseOpenPortCommand(lastStep.requestAscii)
            val payloadLen = cmd?.payloadLen
            if (payloadLen != null && payloadLen > 0) {
                val hex = lastStep.requestHex.trim().split(" ").filter { it.isNotBlank() }
                if (hex.size >= payloadLen) sb.append(kv("Payload", hex.takeLast(payloadLen).joinToString(" ")))
            }
            sb.append(kv("USB hex in", lastStep.responseHex.ifBlank { "(empty)" }))
            sb.append(kv("Duration", "${lastStep.durationMs} ms"))
        }
        sb.append("\n")
        sb.append("==== FULL RUN LOG (all steps) ====\n")
        if (uiState.log.isEmpty()) sb.append("(no run)\n")
        else for (entry in uiState.log) appendStepBlock(sb, entry)
        return sb.toString()
    }

    fun formatSessionLogCsv(uiState: ProtocolUiState): String {
        val log = uiState.sessionLog
        if (log.isEmpty()) return ""
        val pids = Ssm2Pids.DEFAULT_DEMO_PIDS.filter { it.id in uiState.pidIdsOnLiveData }
        if (pids.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("Timestamp")
        for (pid in pids) sb.append(",${pid.displayName} (${pid.unit})")
        sb.append("\n")
        for (sample in log) {
            sb.append(csvTimeFmt.format(Date(sample.timestampMs)))
            for (pid in pids) {
                val v = sample.values[pid.id]
                sb.append(",").append(v?.let { formatPidValueCsv(pid.id, it) } ?: "")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Aligned text table for the on-screen session log and for clipboard
     * copy. Designed to survive paste into any monospaced or proportional
     * context: fixed-width columns, single-space delimiters, ASCII-only
     * (degree sign stripped from unit labels).
     *
     * Only PIDs in [pidIdsOnPage] (i.e., gauges currently placed on the Live
     * Data page) become columns. If the page is empty, an explanatory
     * placeholder is returned.
     */
    fun formatSessionLogCleanText(
        log: List<PollSample>,
        pidIdsOnPage: Set<String>,
        oldestFirst: Boolean = false
    ): String {
        val pids = Ssm2Pids.DEFAULT_DEMO_PIDS.filter { it.id in pidIdsOnPage }
        if (pids.isEmpty()) {
            return "(no gauges — open the menu and add parameters to log them)"
        }
        val headers = pids.map(::pidHeaderText)
        val maxValueWidths = pids.map { maxValueWidth(it.id) }
        val colWidths = headers.mapIndexed { i, h -> maxOf(h.length, maxValueWidths[i]) }
        val sb = StringBuilder()
        sb.append("Time".padEnd(12))
        for (i in pids.indices) {
            sb.append("  ").append(headers[i].padEnd(colWidths[i]))
        }
        sb.append("\n")
        if (log.isEmpty()) {
            sb.append("(no samples yet — press Log Live Data and select parameters)")
            return sb.toString()
        }
        val ordered = if (oldestFirst) log else log.asReversed()
        for (sample in ordered) {
            sb.append(logTimeFmt.format(Date(sample.timestampMs)))
            for (i in pids.indices) {
                val v = sample.values[pids[i].id]
                val s = v?.let { formatPidValueText(pids[i].id, it) } ?: "--"
                sb.append("  ").append(s.padStart(colWidths[i]))
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    fun formatPidValueText(pidId: String, value: Double): String = when (pidId) {
        "rpm"     -> "%.0f".format(value)
        "coolant" -> "%.0f".format(value)
        "battery" -> "%.1f".format(value)
        "afc1"    -> "%.1f".format(value)
        "afl1"    -> "%.1f".format(value)
        "afs1"    -> "%.2f".format(value)
        "ign"     -> "%.1f".format(value)
        "maf"     -> "%.2f".format(value)
        "oil"     -> "%.0f".format(value)
        "iat"     -> "%.0f".format(value)
        "fbkc"    -> "%.2f".format(value)
        "load"    -> "%.2f".format(value)
        "flkc"    -> "%.2f".format(value)
        "iam"     -> "%.3f".format(value)
        else      -> "%.2f".format(value)
    }

    private fun pidHeaderText(pid: com.protocol.app.openport2.Ssm2Pid): String {
        val ascii = pid.unit.replace("°", "")
        return if (ascii.isBlank() || ascii.equals(pid.displayName, ignoreCase = true)) pid.displayName
        else "${pid.displayName}($ascii)"
    }

    private fun maxValueWidth(pidId: String): Int = when (pidId) {
        "rpm"     -> 5   // up to "8000"
        "coolant" -> 3   // up to "240"
        "battery" -> 4   // "14.5"
        "afc1"    -> 6   // "-99.9" / " 99.9"
        "afl1"    -> 6
        "afs1"    -> 5   // "14.70"
        "ign"     -> 5   // "-30.0" / " 60.0"
        "maf"     -> 6   // up to "300.00"
        "oil"     -> 3   // up to "260"
        "iat"     -> 3
        "fbkc"    -> 6   // signed degrees, e.g., "-9.99" / " 9.99"
        "load"    -> 5   // "9.99"
        "flkc"    -> 6
        "iam"     -> 5   // "1.000"
        else      -> 6
    }

    private val logTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun suggestedExportFileName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            .let { "protocol_probe_$it.txt" }

    fun suggestedCsvFileName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            .let { "protocol_session_$it.csv" }

    private fun timestampNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun kv(label: String, value: String): String = "${label.padEnd(16)}: $value\n"

    private fun formatPidValueCsv(pidId: String, value: Double): String = when (pidId) {
        "rpm"     -> "%.1f".format(value)
        "coolant" -> "%.1f".format(value)
        "battery" -> "%.2f".format(value)
        "afc1"    -> "%.2f".format(value)
        "afl1"    -> "%.2f".format(value)
        "afs1"    -> "%.3f".format(value)
        "ign"     -> "%.2f".format(value)
        "maf"     -> "%.3f".format(value)
        "oil"     -> "%.1f".format(value)
        "iat"     -> "%.1f".format(value)
        "fbkc"    -> "%.4f".format(value)
        "load"    -> "%.4f".format(value)
        "flkc"    -> "%.4f".format(value)
        "iam"     -> "%.4f".format(value)
        else      -> "%.3f".format(value)
    }
}
