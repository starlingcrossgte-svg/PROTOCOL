package com.protocol.app.protocol

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage

/**
 * Top-level composable for the PROTOCOL app. Lays out the three-layer
 * background (optional user photo → black overlay → Y2K spike Canvas)
 * plus the content Column with the shared header, the active page body,
 * and the connection status stripe at the very top.
 *
 * All concrete page bodies (Home / Live Data / Parameters / Settings /
 * Stub) live in sibling files. This file just routes between them.
 *
 * Companion files:
 *   - Palette.kt          color constants, gradient brushes, y2kCornerShape
 *   - BackgroundDecor.kt  drawY2kBackgroundDecor
 *   - ProtocolHeader.kt   HamburgerMenu, CloseButton
 *   - HomePage.kt         HomePage (Page 0 of the pager)
 *   - LiveDataPage.kt     LiveDataPage (Page 1 of the pager)
 *   - SnapGaugeGrid.kt    SnapGaugeGrid + GaugeTile + DragBar + EditModeOverlay
 *   - ParametersBody.kt   ParametersBody (sub-page)
 *   - SettingsBody.kt     SettingsBody (sub-page)
 *   - StubBody.kt         StubBody (sub-page for Flash / Diagnostics / Tuning)
 *   - OutcomeCard.kt      OutcomeCard + RunLogCard + helpers
 *   - ProtocolLogFormatter.kt  CSV / clipboard / export formatting
 *   - CommonWidgets.kt    CategoryHeader (shared across bodies)
 */
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
    onSendManualCommand: (String) -> Unit,
    onReadDtc: () -> Unit,
    onClearDtc: () -> Unit,
    onResetDtc: () -> Unit,
    onExportDtc: () -> Unit,
    onStartReadingLive: () -> Unit,
    onStopReadingLive: () -> Unit,
    onStartLogging: () -> Unit,
    onClearSessionLog: () -> Unit,
    onExportSessionLog: () -> Unit,
    onPickBackground: () -> Unit,
    onClearBackground: () -> Unit,
    onAdapterChange: (Adapter?) -> Unit,
    onProtocolChange: (BusProtocol?) -> Unit,
    onPollingModeChange: (PollingMode) -> Unit,
    onPollIntervalChange: (Int) -> Unit,
    onSessionLogMaxChange: (Int) -> Unit,
    onButtonFillChange: (Int) -> Unit,
    onDevModeChange: (Boolean) -> Unit,
    onSimulatorModeChange: (Boolean) -> Unit,
    onSimulatorPortChange: (Int) -> Unit,
    onSelectInitSequence: (String?) -> Unit,
    onKlineContinuousTest: () -> Unit,
    onStartCanMonitor: () -> Unit,
    onStopCanMonitor: () -> Unit,
    onReadFirmware: () -> Unit,
    onSelectKernel: () -> Unit,
    onClearKernel: () -> Unit,
    onSelectDef: () -> Unit,
    onClearDef: () -> Unit,
    onSelectKernelProtocol: (com.protocol.app.firmware.KernelProtocol) -> Unit,
    onSetKernelNeedsPrep: (Boolean) -> Unit,
    onWriteFirmware: (Boolean) -> Unit,
    onSelectWriteRom: () -> Unit,
    onClearWriteRom: () -> Unit,
    onSelectAdapter: (Adapter?) -> Unit,
    onSelectProtocol: (BusProtocol?) -> Unit,
    onConnectAdapter: () -> Unit,
    onRunSequence: (List<String>, List<Long>, (Int, String) -> Unit) -> Unit,
    // ── user presets ──
    onCreatePreset: () -> Unit,
    onApplyUserPreset: (String) -> Unit,
    onDeleteUserPreset: (String) -> Unit,
    onToggleDraftPid: (String) -> Unit,
    onCancelDraft: () -> Unit,
    onDoneDraft: () -> Unit,
    onSavePreset: (String) -> Unit,
    onDismissPresetName: () -> Unit,
    onResizeSessionLog: (Float) -> Unit,
    onAutoSaveLogs: () -> Unit,
    onPickCsvFolder: () -> Unit,
    onPickKernelFolder: () -> Unit,
    onPickRomFolder: () -> Unit,
    onRawLogNameChange: (String) -> Unit,
    onSessionLogNameChange: (String) -> Unit,
    onResetLayout: () -> Unit,
    onDisconnectObdLink: () -> Unit,
    onResetAdapter: () -> Unit,
    onShareSavedSession: () -> Unit,
    /** Report the current tap-loop state to a paired watch. No-op without one. */
    onWatchState: (String) -> Unit = {},
    /** Applies a preset staged from the watch bezel. True = the tap was used. */
    onCommitStagedPreset: () -> Boolean = { false },
    /** Returns true exactly once per watch-tap id. */
    onConsumeRemoteTap: (Int) -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    // Hoisted here so the user's currently-visible page (Home vs Live Data)
    // is preserved when they pop into a sub-page and back.
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })

    // The expanded-log overlay is the LAST child of the root Box below — the
    // only un-inset, full-panel surface, which is what makes it edge to edge.
    val fullscreenLogHost = remember { FullscreenLogHost() }

    // Sub-page BackHandler. LiveDataPage's edit-mode BackHandler is nested
    // deeper and stacks above this one when both could be relevant — but
    // openSubPage clears editMode anyway, so the two never both fire.
    BackHandler(enabled = uiState.activeSubPage != null) { onCloseSubPage() }

    // Live Data page lock (driven by the "Lock and Tap" button). Transient UI
    // state on purpose: held here so it can freeze the pager (horizontal
    // swipe), but it must NOT persist. Cancelling the lock — via the button,
    // the Back button, or ON_STOP (home / backgrounded) — also stops live
    // polling and logging if they're running, and the app always returns
    // unlocked so the user re-applies it manually.
    var locked by remember { mutableStateOf(false) }
    val cancelLock: () -> Unit = {
        if (uiState.isReadingLive) onStopReadingLive()   // stopReadingLive clears the logging flag too
        locked = false
    }
    BackHandler(enabled = locked) { cancelLock() }
    // rememberUpdatedState so the long-lived lifecycle observer always calls
    // the latest cancelLock (which reads the current poll/log state).
    val currentCancelLock by rememberUpdatedState(cancelLock)
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && locked) currentCancelLock()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // No-ECU flash: when a live source reports the ECU stopped answering, auto-
    // exit lock and flash ALL bars — top, bottom, and the side edges —
    // white<->red ~20 cycles over ~6 s. When it ends, top/bottom settle via
    // presence (white if still connected, invisible if dropped) and the sides
    // go away (lock is no longer engaged). Read in the draw phase so nothing
    // recomposes per frame. (Borders are otherwise solid white — no pulse.)
    var noEcuFlashing by remember { mutableStateOf(false) }
    val noEcuFlash = remember { Animatable(0f) }
    LaunchedEffect(uiState.noEcuEventId) {
        if (uiState.noEcuEventId > 0) {
            locked = false
            noEcuFlashing = true
            // 75 ms half-cycles: ~2x the old frequency and, across the same
            // 20 cycles, ~half the old total duration (≈3 s instead of ≈6 s).
            repeat(20) {
                noEcuFlash.animateTo(1f, tween(75))
                noEcuFlash.animateTo(0f, tween(75))
            }
            noEcuFlashing = false
        }
    }

    // Overdraw note: ScreenBg is skipped when a background photo is set
    // (the photo covers it completely). The 50% dim overlay is folded
    // into the AsyncImage's own draw pass via drawWithContent, so the
    // photo + dim render in a single layer instead of two.
    val bgUri = uiState.backgroundUri
    val rootModifier = if (bgUri != null) {
        modifier.fillMaxSize()
    } else {
        modifier.fillMaxSize().background(ScreenBg)
    }
    Box(modifier = rootModifier) {
        if (bgUri != null) {
            AsyncImage(
                model = bgUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        drawRect(Color.Black.copy(alpha = 0.5f))
                    }
            )
        }

        // Y2K spike decor. Drawn only on the default ScreenBg — a user
        // photo replaces the look entirely (the spikes would just be
        // noise on top of their image and add 1x overdraw).
        if (bgUri == null) {
            Canvas(modifier = Modifier.fillMaxSize()) { drawY2kBackgroundDecor(this) }
        }

        // Provided here rather than threaded through the callback list. Log
        // surfaces stay opaque simply by not reading LocalButtonFill.
        CompositionLocalProvider(
            LocalFullscreenLogHost provides fullscreenLogHost,
            LocalButtonFill provides SurfaceBg.copy(
                alpha = (uiState.settings.buttonFillPercent / 100f).coerceIn(0f, 1f)
            )
        ) {
        Column(modifier = Modifier
            .fillMaxSize()
            // Top inset = the status bar height, so content (and the gauges)
            // always seats just below the clock/battery. The status bar stays
            // visible on every page — including Live Data — so it never slides
            // away. On these punch-hole panels the status bar spans past the
            // camera cutout, so this also keeps content clear of the cutout.
            .windowInsetsPadding(
                WindowInsets.statusBars.only(WindowInsetsSides.Top)
            )
            .navigationBarsPadding()
        ) {
            // No header / close-X anywhere. The system back button closes
            // sub-pages (BackHandler above), so the body fills straight from
            // the status stripe down — nothing clips at an old header line.

            // Body — main pager or sub-page content. Adapter pill is gone
            // from the chrome; discovery now triggers off the action
            // buttons (Test Probe / Read Live / Log Live).
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (uiState.activeSubPage) {
                    null -> HorizontalPager(
                        state = pagerState,
                        userScrollEnabled = !locked,
                        modifier = Modifier.fillMaxSize()
                    ) { page ->
                        when (page) {
                            0 -> DiagnosticsPage(
                                uiState = uiState,
                                onReadDtc = onReadDtc,
                                onClearDtc = onClearDtc,
                                onResetDtc = onResetDtc,
                                onExportDtc = onExportDtc
                            )
                            1 -> HomePage(
                                uiState = uiState,
                                onOpenSubPage = onOpenSubPage
                            )
                            else -> LiveDataPage(
                                uiState = uiState,
                                locked = locked,
                                onToggleLock = { if (locked) cancelLock() else { locked = true } },
                                onStartReadingLive = onStartReadingLive,
                                onStopReadingLive = onStopReadingLive,
                                onStartLogging = onStartLogging,
                                onClearSessionLog = onClearSessionLog,
                                onExportSessionLog = onExportSessionLog,
                                onEnterEditMode = onEnterEditMode,
                                onExitEditMode = onExitEditMode,
                                onRemoveGauge = onRemoveGauge,
                                onResizeGauge = onResizeGauge,
                                onOpenParameters = { onOpenSubPage(SubPage.Parameters) },
                                onOpenTcmParameters = { onOpenSubPage(SubPage.TcmParameters) },
                                onOpenUnverified = { onOpenSubPage(SubPage.Unverified) },
                                onOpenPresets = { onOpenSubPage(SubPage.Presets) },
                                onResetLayout = onResetLayout,
                                onResizeSessionLog = onResizeSessionLog,
                                onAutoSaveLogs = onAutoSaveLogs,
                                onWatchState = onWatchState,
                                onCommitStagedPreset = onCommitStagedPreset,
                                onConsumeRemoteTap = onConsumeRemoteTap,
                                // The pager keeps neighbouring pages composed, so
                                // Live Data is alive even while Home is showing.
                                // A watch tap must only act when it is genuinely
                                // the visible page. (A sub-page being open is
                                // already excluded — this branch only runs when
                                // activeSubPage is null.)
                                isVisiblePage = pagerState.currentPage == page
                            )
                        }
                    }
                    SubPage.Parameters -> ParametersBody(
                        uiState = uiState,
                        category = com.protocol.app.openport2.Ssm2PidCategory.ECU,
                        onTogglePid = onToggleGaugeForPid,
                        onSelectDef = onSelectDef,
                        onClearDef = onClearDef,
                        onToggleDraftPid = onToggleDraftPid,
                        onCancelDraft = onCancelDraft,
                        onDoneDraft = onDoneDraft
                    )
                    // Draft selection works here too, so presets can hold TCM params.
                    SubPage.TcmParameters -> ParametersBody(
                        uiState = uiState,
                        category = com.protocol.app.openport2.Ssm2PidCategory.TCM,
                        onTogglePid = onToggleGaugeForPid,
                        onSelectDef = onSelectDef,
                        onClearDef = onClearDef,
                        onToggleDraftPid = onToggleDraftPid,
                        onCancelDraft = onCancelDraft,
                        onDoneDraft = onDoneDraft
                    )
                    SubPage.Unverified -> ParametersBody(
                        uiState = uiState,
                        category = com.protocol.app.openport2.Ssm2PidCategory.ECU,
                        onTogglePid = onToggleGaugeForPid,
                        onSelectDef = onSelectDef,
                        onClearDef = onClearDef,
                        showUnverified = true,
                        onToggleDraftPid = onToggleDraftPid,
                        onCancelDraft = onCancelDraft,
                        onDoneDraft = onDoneDraft
                    )
                    SubPage.Presets -> PresetsBody(
                        uiState = uiState,
                        onCreatePreset = onCreatePreset,
                        onApplyPreset = onApplyUserPreset,
                        onDeletePreset = onDeleteUserPreset
                    )
                    SubPage.Settings -> SettingsBody(
                        uiState = uiState,
                        onAdapterChange = onAdapterChange,
                        onProtocolChange = onProtocolChange,
                        onPollingModeChange = onPollingModeChange,
                        onPollIntervalChange = onPollIntervalChange,
                        onSessionLogMaxChange = onSessionLogMaxChange,
                        onButtonFillChange = onButtonFillChange,
                        onShareSavedSession = onShareSavedSession,
                        onDisconnectObdLink = onDisconnectObdLink,
                        onResetAdapter = onResetAdapter,
                        onPickBackground = onPickBackground,
                        onClearBackground = onClearBackground,
                        onPickCsvFolder = onPickCsvFolder,
                        onPickKernelFolder = onPickKernelFolder,
                        onPickRomFolder = onPickRomFolder,
                        onSelectKernelProtocol = onSelectKernelProtocol,
                        onSetKernelNeedsPrep = onSetKernelNeedsPrep,
                        onRawLogNameChange = onRawLogNameChange,
                        onSessionLogNameChange = onSessionLogNameChange
                    )
                    SubPage.Flash -> FlashBody(
                        uiState = uiState,
                        onSelectKernel = onSelectKernel,
                        onClearKernel = onClearKernel,
                        onSelectWriteRom = onSelectWriteRom,
                        onClearWriteRom = onClearWriteRom,
                        onReadFirmware = onReadFirmware,
                        onWriteFirmware = onWriteFirmware
                    )
                    SubPage.Tuning ->
                        StubBody(page = uiState.activeSubPage)
                    SubPage.TableEditor -> TableEditorBody()
                    SubPage.Navigation -> NavigationBody()
                    SubPage.Notices -> NoticesBody()
                    SubPage.Library -> LibraryBody()
                    SubPage.Developer -> DeveloperBody(
                        uiState = uiState,
                        onSendManualCommand = onSendManualCommand,
                        onSimulatorModeChange = onSimulatorModeChange,
                        onSimulatorPortChange = onSimulatorPortChange,
                        onDevModeChange = onDevModeChange,
                        onSelectInitSequence = onSelectInitSequence,
                        onKlineContinuousTest = onKlineContinuousTest,
                        onStartCanMonitor = onStartCanMonitor,
                        onStopCanMonitor = onStopCanMonitor,
                        onSelectAdapter = onSelectAdapter,
                        onSelectProtocol = onSelectProtocol,
                        onConnectAdapter = onConnectAdapter,
                        onRunSequence = onRunSequence,
                    )
                }
            }
        }
        }

        // Side edges: the connection-status + lock indicator (3 dp, replaces
        // the old top/bottom stripes). Solid white whenever an adapter is
        // present OR the page is locked; during the no-ECU flash they flash
        // white<->red, then settle by presence. Anchored to the ROOT box (not
        // the inset-padded body) so they run truly edge-to-edge: full physical
        // height, up behind the transparent status bar and down behind the nav
        // bar, with the screen's rounded corners clipping the ends. The status
        // bar is never hidden — we only draw behind it (no immersive toggle).
        // 3 dp at the extreme edges, clear of the gauges' 10 dp page padding.
        // Read in the draw phase so nothing recomposes per frame.
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .width(3.dp)
                .fillMaxHeight()
                .drawBehind {
                    when {
                        noEcuFlashing -> drawRect(lerp(Color.White, Color.Red, noEcuFlash.value.coerceIn(0f, 1f)))
                        locked || uiState.adapterPresent -> drawRect(Color.White)
                    }
                }
        )
        Box(
            Modifier
                .align(Alignment.CenterEnd)
                .width(3.dp)
                .fillMaxHeight()
                .drawBehind {
                    when {
                        noEcuFlashing -> drawRect(lerp(Color.White, Color.Red, noEcuFlash.value.coerceIn(0f, 1f)))
                        locked || uiState.adapterPresent -> drawRect(Color.White)
                    }
                }
        )

        // LAST child, so an expanded log covers the pages, photo and stripes.
        FullscreenLogOverlay(
            host = fullscreenLogHost,
            uiState = uiState,
            onClearSessionLog = onClearSessionLog,
            onExportSessionLog = onExportSessionLog,
            onClearDtc = onClearDtc,
            onExportDtc = onExportDtc
        )

        // At the root so the parameter list cannot scroll it away.
        val draft = uiState.presetDraft
        if (draft != null && draft.awaitingName) {
            PresetNameDialog(
                count = draft.pidIds.size,
                onDismiss = onDismissPresetName,
                onSave = onSavePreset
            )
        }
    }
}
