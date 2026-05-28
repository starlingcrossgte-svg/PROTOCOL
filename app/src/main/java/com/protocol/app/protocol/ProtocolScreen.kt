package com.protocol.app.protocol

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
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
    onAdapterChange: (Adapter) -> Unit,
    onProtocolChange: (BusProtocol) -> Unit,
    onSsmVariantChange: (SsmVariant) -> Unit,
    onPollIntervalChange: (Int) -> Unit,
    onSessionLogMaxChange: (Int) -> Unit,
    onDevModeChange: (Boolean) -> Unit,
    onResetLayout: () -> Unit,
    onResetAdapter: () -> Unit,
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
    val pagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })

    // Sub-page BackHandler. LiveDataPage's edit-mode BackHandler is nested
    // deeper and stacks above this one when both could be relevant — but
    // openSubPage clears editMode anyway, so the two never both fire.
    BackHandler(enabled = uiState.activeSubPage != null) { onCloseSubPage() }

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

        Column(modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
        ) {
            // Connection status stripe — 3dp colored bar at the very top
            // of the screen. Always visible across every page so the user
            // can tell at a glance whether the adapter is alive. Replaces
            // the old AdapterPill without occupying meaningful real estate.
            ConnectionStatusStripe(uiState.connectionStatus)

            // Header + logo removed app-wide. The logo now lives in the Home
            // page content and scrolls with it; Live Data and Flash render
            // their own top rows. Sub-pages keep only a close (X) button so
            // there's still a way back — the Flash page draws its own X, so
            // it's excluded here.
            if (uiState.activeSubPage != null && uiState.activeSubPage != SubPage.Flash) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    CloseButton(
                        onClick = onCloseSubPage,
                        modifier = Modifier.align(Alignment.CenterEnd)
                    )
                }
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
                            0 -> DiagnosticsPage()
                            1 -> HomePage(
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
                                onOpenLiveDataSettings = { onOpenSubPage(SubPage.LiveDataSettings) }
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
                        onShareSavedSession = onShareSavedSession,
                        onResetAdapter = onResetAdapter
                    )
                    SubPage.Settings -> SettingsBody(
                        uiState = uiState,
                        onAdapterChange = onAdapterChange,
                        onProtocolChange = onProtocolChange,
                        onSsmVariantChange = onSsmVariantChange,
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
                    SubPage.Tuning ->
                        StubBody(page = uiState.activeSubPage!!)
                }
            }
        }
    }
}
