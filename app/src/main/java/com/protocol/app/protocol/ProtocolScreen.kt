package com.protocol.app.protocol

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import coil.compose.AsyncImage
import com.protocol.app.flash.FlashPage
import com.protocol.app.flash.FlashUiState

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
 *   - ProtocolHeader.kt   ProtocolHeader, ConnectionStatusStripe
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
    onRunProbe: () -> Unit,
    onHuntKlineInit: () -> Unit,
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
    onPollIntervalChange: (Int) -> Unit,
    onSessionLogMaxChange: (Int) -> Unit,
    onDevModeChange: (Boolean) -> Unit,
    onSplitScreenChange: (Boolean) -> Unit,
    onObdLinkChange: (Boolean) -> Unit,
    onResetLayout: () -> Unit,
    onSaveVehicle: (year: String, make: String, model: String, subModel: String) -> Unit,
    onSelectVehicle: (id: String) -> Unit,
    onDeleteVehicle: (id: String) -> Unit,
    onShareSavedSession: () -> Unit,
    flashState: FlashUiState,
    onFlashTestConnection: () -> Unit,
    onFlashExportCsv: (String, String) -> Unit,
    onFlashToggleTrafficRecording: () -> Unit,
    onFlashClearTrafficLog: () -> Unit,
    onFlashToggleDeviceRecording: () -> Unit,
    onFlashClearDeviceLog: () -> Unit,
    onFlashToggleRunLogRecording: () -> Unit,
    onFlashClearRunLog: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Hoisted here so the user's currently-visible page (Home vs Live Data)
    // is preserved when they pop into a sub-page and back.
    val pagerState = rememberPagerState(pageCount = { 2 })

    val subPageTitle: String? = when (uiState.activeSubPage) {
        SubPage.Parameters -> "ECU Parameters"
        SubPage.TcmParameters -> "TCM Parameters"
        SubPage.LiveDataSettings -> "Live Data Settings"
        SubPage.Settings -> "Settings"
        SubPage.Garage -> "Garage"
        SubPage.Developer -> "Developer"
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
            // Connection status stripe — 3dp colored bar at the very top
            // of the screen. Always visible across every page so the user
            // can tell at a glance whether the adapter is alive. Replaces
            // the old AdapterPill without occupying meaningful real estate.
            ConnectionStatusStripe(uiState.connectionStatus)

            // Hide the shared header (logo + chrome) on Live Data. That
            // page renders its own compact top row with mode buttons and
            // an inline hamburger so the gauges get more vertical real
            // estate — important on split-screen and in-dash setups.
            val onLiveDataRoot = uiState.activeSubPage == null &&
                pagerState.currentPage == 1
            // Flash page hides the shared header + logo too (user request).
            val onFlashPage = uiState.activeSubPage == SubPage.Flash
            if (!onLiveDataRoot && !onFlashPage) {
                ProtocolHeader(
                    subPageTitle = subPageTitle,
                    showHamburger = false,
                    splitScreenMode = uiState.settings.splitScreenMode,
                    obdLinkEnabled = uiState.settings.obdLinkEnabled,
                    onOpenParameters = { onOpenSubPage(SubPage.Parameters) },
                    onOpenTcmParameters = { onOpenSubPage(SubPage.TcmParameters) },
                    onOpenLiveDataSettings = { onOpenSubPage(SubPage.LiveDataSettings) },
                    onToggleSplitScreen = { onSplitScreenChange(!uiState.settings.splitScreenMode) },
                    onToggleObdLink = { onObdLinkChange(!uiState.settings.obdLinkEnabled) },
                    onClose = onCloseSubPage
                )
            }

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
                                onResizeGauge = onResizeGauge,
                                onOpenParameters = { onOpenSubPage(SubPage.Parameters) },
                                onOpenTcmParameters = { onOpenSubPage(SubPage.TcmParameters) },
                                onOpenLiveDataSettings = { onOpenSubPage(SubPage.LiveDataSettings) },
                                onToggleSplitScreen = { onSplitScreenChange(!uiState.settings.splitScreenMode) },
                                onToggleObdLink = { onObdLinkChange(!uiState.settings.obdLinkEnabled) }
                            )
                        }
                    }
                    SubPage.Parameters -> ParametersBody(
                        uiState = uiState,
                        category = com.protocol.app.openport2.Ssm2PidCategory.ECU,
                        onTogglePid = onToggleGaugeForPid
                    )
                    SubPage.TcmParameters -> ParametersBody(
                        uiState = uiState,
                        category = com.protocol.app.openport2.Ssm2PidCategory.TCM,
                        onTogglePid = onToggleGaugeForPid
                    )
                    SubPage.LiveDataSettings -> LiveDataSettingsBody(
                        uiState = uiState,
                        onPollIntervalChange = onPollIntervalChange,
                        onSessionLogMaxChange = onSessionLogMaxChange,
                        onResetLayout = onResetLayout,
                        onShareSavedSession = onShareSavedSession
                    )
                    SubPage.Settings -> SettingsBody(
                        uiState = uiState,
                        onPickBackground = onPickBackground,
                        onClearBackground = onClearBackground,
                        onDevModeChange = onDevModeChange
                    )
                    SubPage.Garage -> GarageBody(
                        uiState = uiState,
                        onSaveVehicle = onSaveVehicle,
                        onSelectVehicle = onSelectVehicle,
                        onDeleteVehicle = onDeleteVehicle
                    )
                    SubPage.Developer -> DeveloperBody(
                        uiState = uiState,
                        onRunProbe = onRunProbe,
                        onClearProbeLog = onClearLog,
                        onCopyProbeLog = onCopyLog,
                        onExportProbeLog = onExportLog,
                        onHuntKlineInit = onHuntKlineInit
                    )
                    SubPage.Flash -> FlashPage(
                        state = flashState,
                        onTestConnection = onFlashTestConnection,
                        onClose = onCloseSubPage,
                        onExportCsv = onFlashExportCsv,
                        onToggleTrafficRecording = onFlashToggleTrafficRecording,
                        onClearTrafficLog = onFlashClearTrafficLog,
                        onToggleDeviceRecording = onFlashToggleDeviceRecording,
                        onClearDeviceLog = onFlashClearDeviceLog,
                        onToggleRunLogRecording = onFlashToggleRunLogRecording,
                        onClearRunLog = onFlashClearRunLog
                    )
                    SubPage.Diagnostics, SubPage.Tuning ->
                        StubBody(page = uiState.activeSubPage!!)
                }
            }
        }
    }
}
