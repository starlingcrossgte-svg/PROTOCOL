package com.protocol.app.protocol

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import com.protocol.app.UsbPermissionHelper
import com.protocol.app.obdlink.FtdiUsbSerial
import com.protocol.app.openport2.OpenPort2SessionResult
import com.protocol.app.openport2.OpenPort2UsbSessionManager
import java.nio.charset.StandardCharsets

class Protocol : ComponentActivity() {

    companion object {
        private const val TACTRIX_VENDOR_ID = 1027
        private const val TACTRIX_PRODUCT_ID = 52301
        private const val ACTION_USB_PERMISSION =
            "com.protocol.app.OPENPORT_USB_PERMISSION"

        // The app is the source of truth for its own scale — the OS "Screen
        // zoom" and "Font size" sliders are bypassed so the layout is identical
        // regardless of where a user leaves those sliders. The dialed-in look was
        // captured on the 1440px flagship panels: density 560 (screen-zoom lowest)
        // + font scale 0.9 (font second-from-lowest), giving ~411 dp of layout
        // width. Rather than hardcoding 560, we anchor that reference and scale the
        // density by the panel's pixel width so EVERY device lands on the same
        // ~411 dp width: a 1440px panel resolves to 560 (flagships unchanged), a
        // 1080px panel (A14 5G) resolves to 420. The flagship fleet is byte-for-
        // byte identical to the old hardcode; only non-1440 panels adapt.
        private const val REFERENCE_WIDTH_PX = 1440
        private const val REFERENCE_DENSITY_DPI = 560
        private const val LOCKED_FONT_SCALE = 0.9f
    }

    // Force our derived density + fixed font scale onto every context this
    // Activity builds resources from. Runs on first create and again on any
    // config-change recreation (e.g. the user moves an OS slider), so the lock
    // holds.
    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration)
        // Short edge = portrait width, robust to whatever orientation this
        // context is built in. Pixel count is physical (density-independent), so
        // anchoring 560 dpi @ 1440px yields the same dp width on any panel.
        val metrics = newBase.resources.displayMetrics
        val widthPx = minOf(metrics.widthPixels, metrics.heightPixels)
        config.densityDpi =
            Math.round(widthPx.toFloat() / REFERENCE_WIDTH_PX * REFERENCE_DENSITY_DPI)
        config.fontScale = LOCKED_FONT_SCALE
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    private lateinit var viewModel: ProtocolViewModel
    private lateinit var usbManager: UsbManager
    private lateinit var usbPermissionHelper: UsbPermissionHelper
    private lateinit var sessionManager: OpenPort2UsbSessionManager
    private lateinit var sessionLogStore: SessionLogStore

    private var pendingExportText: String = ""

    private val exportLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri: Uri? ->
        if (uri == null) { Toast.makeText(this, "Export cancelled", Toast.LENGTH_SHORT).show(); return@registerForActivityResult }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(pendingExportText.toByteArray(StandardCharsets.UTF_8)) }
            Toast.makeText(this, "Log exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        } finally { pendingExportText = "" }
    }

    // Direct-to-Files CSV save (Storage Access Framework). Same write path as
    // exportLogLauncher but a text/csv document so the picker defaults the
    // session log to a .csv the user can drop anywhere in their Files app.
    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri == null) { Toast.makeText(this, "Export cancelled", Toast.LENGTH_SHORT).show(); return@registerForActivityResult }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(pendingExportText.toByteArray(StandardCharsets.UTF_8)) }
            Toast.makeText(this, "CSV exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        } finally { pendingExportText = "" }
    }

    // Settings → Choose CSV Folder. SAF tree picker; the chosen folder is
    // persisted (with a read/write grant that survives reboot) and used by the
    // Lock-and-Tap auto-save to drop enumerated CSVs without a per-file dialog.
    private val pickCsvFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Provider doesn't support persistable grants — the URI still works
            // for this process; the user re-picks after a kill.
        }
        viewModel.setCsvFolderUri(uri.toString())
        Toast.makeText(this, "CSV folder set", Toast.LENGTH_SHORT).show()
    }

    // Settings → Choose Background. Android Photo Picker handles the
    // gallery selection — no runtime READ_EXTERNAL_STORAGE permission
    // needed. We take persistable read permission on the returned URI so
    // it survives process death and reboots.
    private val pickBackgroundLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        try {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        } catch (_: SecurityException) {
            // Some content providers don't support persistable grants
            // (older Storage Access Framework providers). The URI is still
            // valid for the current process — user just needs to re-pick
            // after a kill.
        }
        viewModel.setBackgroundUri(uri.toString())
    }

    private fun launchBackgroundPicker() {
        pickBackgroundLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    // OBDLink Bluetooth: when the user picks OBDLink in Settings, request
    // BLUETOOTH_CONNECT (API 31+) then connect with the protocol they picked.
    // Denied -> drop the OBDLink selection so the UI reflects reality.
    private val btPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        if (granted) {
            viewModel.connectObdLink(applicationContext, pendingConnectFromDev)
        } else {
            viewModel.clearObdLinkAdapter()
            Toast.makeText(this, "Bluetooth permission denied", Toast.LENGTH_LONG).show()
        }
    }

    /**
     * Tap on the ADAPTER selector. Saves the selection, then:
     *   - OpenPort selected → run USB discovery + permission flow
     *   - OBDLink selected → request BT permission + connect with current protocol
     *   - null (user deselected) → tear down OBDLink link if any; USB session is
     *     left attached until USB-DETACH fires (so re-selecting OpenPort can reuse it)
     */
    private fun onAdapterChanged(adapter: Adapter?) {
        viewModel.setAdapter(adapter)
        when (adapter) {
            Adapter.OpenPort -> discoverAndConnect()
            Adapter.OBDLink -> ensureBtPermissionThenConnect()
            Adapter.OBDLinkEx -> connectObdLinkEx()
            Adapter.Ft232rl -> connectFt232rl()
            null -> {
                viewModel.disconnectObdLink()
                viewModel.disconnectObdLinkEx()
                viewModel.disconnectFt232rl()
            }
        }
    }

    /**
     * Tap on the FT232RL (VAG-KKL raw K-line) adapter, or Connect with it
     * selected. Mirrors the OBDLink EX USB flow — discover the FTDI device,
     * request USB permission if needed — but on grant hands the raw connection
     * to the VM's KKL connect, which opens the FTDI serial at 4800 and probes
     * the ECU over the bare K-line.
     */
    private fun connectFt232rl() {
        val device = findFt232rlDevice()
        if (device == null) {
            viewModel.setAdapterPresent(false)
            viewModel.setConnectionStatus(
                ConnectionStatus.NoDevice,
                "FT232RL (KKL, FTDI VID=${FtdiUsbSerial.FTDI_VENDOR_ID} " +
                    "PID=${FtdiUsbSerial.FT232RL_PRODUCT_ID}) not detected — plug the cable in"
            )
            return
        }
        viewModel.setAdapterPresent(true)
        if (!usbManager.hasPermission(device)) {
            viewModel.setConnectionStatus(
                ConnectionStatus.PermissionRequired(deviceLabel(device)),
                "Requesting USB permission for FT232RL..."
            )
            usbPermissionHelper.requestUsbPermission(device, ACTION_USB_PERMISSION)
            return
        }
        openFt232rlForDevice(device)
    }

    private fun openFt232rlForDevice(device: UsbDevice) {
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            viewModel.setConnectionStatus(
                ConnectionStatus.Error("openDevice returned null"),
                "FT232RL: USB openDevice failed"
            )
            return
        }
        viewModel.connectFt232rl(connection, device)
    }

    /** Dev-page Connect button: connect whatever adapter is currently selected.
     *  The dev dropdown only selects; this performs the actual handshake. */
    private fun connectSelectedAdapter() {
        val s = viewModel.uiState.value.settings
        // Emulator armed → CONNECT primes it; the VIPER TCP session opens on
        // Read Live (the simulator path doesn't touch real hardware).
        if (s.simulatorMode) {
            viewModel.setConnectionStatus(
                ConnectionStatus.Connected("Emulator"),
                "Emulator armed on port ${s.simulatorPort} — Read Live to start the VIPER session"
            )
            return
        }
        when (s.adapter) {
            Adapter.OpenPort -> discoverAndConnect()
            Adapter.OBDLink -> ensureBtPermissionThenConnect(fromDev = true)
            Adapter.OBDLinkEx -> connectObdLinkEx(fromDev = true)
            Adapter.Ft232rl -> connectFt232rl()
            null -> viewModel.setConnectionStatus(
                ConnectionStatus.NoDevice,
                "Pick an adapter in the dropdown first"
            )
        }
    }

    /**
     * Tap on the PROTOCOL selector. For OBDLink the STN init differs by
     * protocol, so we reconnect immediately so the new mode is live before the
     * user taps Read Live. For OpenPort the channel is re-initialized lazily
     * inside startReadingLive (channelInitialized is reset by setProtocol).
     */
    private fun onProtocolChanged(protocol: BusProtocol?) {
        viewModel.setProtocol(protocol)
        val s = viewModel.uiState.value.settings
        if (s.adapter == Adapter.OBDLink && protocol != null) {
            viewModel.disconnectObdLink()
            ensureBtPermissionThenConnect()
        }
        if (s.adapter == Adapter.OBDLinkEx && protocol != null) {
            viewModel.disconnectObdLinkEx()
            connectObdLinkEx()
        }
    }

    // True when the in-flight connect was initiated from the Dev page CONNECT
    // button, so it should use the dev-selected init. Set right before an async
    // permission request and read in the permission callback. Every other connect
    // path (Settings adapter tap, protocol change, Read Live) leaves it false =
    // verified Live Data init.
    private var pendingConnectFromDev = false

    private fun ensureBtPermissionThenConnect(fromDev: Boolean = false) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            pendingConnectFromDev = fromDev
            btPermissionLauncher.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            viewModel.connectObdLink(applicationContext, fromDev)
        }
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                ACTION_USB_PERMISSION -> handlePermissionResult(intent)
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> handleAttached(intent)
                UsbManager.ACTION_USB_DEVICE_DETACHED -> handleDetached(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // installSplashScreen() must be called BEFORE super.onCreate so
        // the system swaps the splash theme out before content draws.
        // Backported to pre-Android-12 via androidx.core:core-splashscreen.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Edge-to-edge. Draw our background behind the system bars and the
        // camera cutout. The chrome (status stripe, body, pinned buttons) is
        // already wrapped in statusBarsPadding()/navigationBarsPadding() in
        // ProtocolScreen, so it insets itself below the status bar and above
        // whatever bottom nav affordance is active — a thin gap on gesture
        // nav, a thick one on 3-button nav — while the background bleeds the
        // full physical display.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Drop the translucent scrim the system otherwise paints behind the
            // nav bar, so the background shows through cleanly under both
            // gesture and 3-button nav.
            window.isNavigationBarContrastEnforced = false
        }
        // Dark UI → light system-bar icons (clock/battery stay legible on top
        // of our background).
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
        // Render into the display cutout (punch-hole / notch). ALWAYS on R+
        // covers any orientation; SHORT_EDGES is the P/Q fallback.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    else
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        usbPermissionHelper = UsbPermissionHelper(this, usbManager)
        sessionManager = OpenPort2UsbSessionManager(
            context = this,
            vendorId = TACTRIX_VENDOR_ID,
            productId = TACTRIX_PRODUCT_ID
        )

        viewModel = ViewModelProvider(this)[ProtocolViewModel::class.java]
        viewModel.attachSessionManager(sessionManager)
        viewModel.attachLayoutStore(GaugeLayoutStore(applicationContext))
        viewModel.attachBackgroundStore(BackgroundStore(applicationContext))
        viewModel.attachSettingsStore(SettingsStore(applicationContext))
        sessionLogStore = SessionLogStore(applicationContext)
        viewModel.attachSessionLogStore(sessionLogStore)

        syncAdapterPresenceAndAutoConnect()

        setContent {
            val uiState by viewModel.uiState.collectAsState()

            val colors = darkColorScheme(
                primary = Color(0xFFFF6A00),
                secondary = Color(0xFF2F6FE4),
                tertiary = Color(0xFFFF6A00),
                background = Color(0xFF0F1115),
                surface = Color(0xFF1A1C22),
                onPrimary = Color.Black,
                onSecondary = Color.White,
                onTertiary = Color.Black,
                onBackground = Color.White,
                onSurface = Color.White
            )

            MaterialTheme(colorScheme = colors) {
                ProtocolScreen(
                    uiState = uiState,
                    onOpenSubPage = { page -> viewModel.openSubPage(page) },
                    onCloseSubPage = { viewModel.closeSubPage() },
                    onToggleGaugeForPid = { pidId -> viewModel.toggleGaugeForPid(pidId) },
                    onEnterEditMode = { viewModel.enterEditMode() },
                    onExitEditMode = { viewModel.exitEditMode() },
                    onRemoveGauge = { pidId -> viewModel.removeGaugeForPid(pidId) },
                    onResizeGauge = { pidId, c, r, w, h ->
                        viewModel.resizeGauge(pidId, c, r, w, h)
                    },
                    onRunProbe = { runActionOrDiscover(PendingAction.Probe) },
                    onHuntKlineInit = { viewModel.huntKlineInit(applicationContext) },
                    onSendManualCommand = { cmd -> viewModel.sendManualCommand(cmd, applicationContext) },
                    onReadDtc = { viewModel.readDtcs() },
                    onCopyDtc = { copyDtcToClipboard() },
                    onExportDtc = { launchExportDtc() },
                    onStartReadingLive = { runActionOrDiscover(PendingAction.ReadLive) },
                    onStopReadingLive = { viewModel.stopReadingLive() },
                    onStartLogging = { runActionOrDiscover(PendingAction.LogLive) },
                    onStopLogging = { viewModel.stopLogging() },
                    onClearLog = { viewModel.clearLog() },
                    onCopyLog = { copyLogToClipboard() },
                    onExportLog = { launchExportLog() },
                    onClearSessionLog = { viewModel.clearSessionLog() },
                    onCopySessionLog = { copySessionLogToClipboard() },
                    onExportSessionLog = { launchExportSessionLog() },
                    onPickBackground = { launchBackgroundPicker() },
                    onClearBackground = { viewModel.setBackgroundUri(null) },
                    onAdapterChange = { adapter -> onAdapterChanged(adapter) },
                    onProtocolChange = { protocol -> onProtocolChanged(protocol) },
                    onSsmVariantChange = { variant -> viewModel.setSsmVariant(variant) },
                    onPollIntervalChange = { ms -> viewModel.setPollIntervalMs(ms) },
                    onSessionLogMaxChange = { rows -> viewModel.setSessionLogMaxSize(rows) },
                    onKlineStreamingChange = { on -> viewModel.setKlineStreaming(on) },
                    onDevModeChange = { on -> viewModel.setDevMode(on) },
                    onSimulatorModeChange = { on -> viewModel.setSimulatorMode(on) },
                    onSimulatorPortChange = { port -> viewModel.setSimulatorPort(port) },
                    onSelectInitSequence = { id -> viewModel.setSelectedInitSequence(id) },
                    onKlineContinuousTest = { viewModel.runKlineContinuousTest(applicationContext) },
                    onStartCanMonitor = { viewModel.startCanMonitor(applicationContext) },
                    onStopCanMonitor = { viewModel.stopCanMonitor() },
                    onSelectAdapter = { a -> viewModel.setAdapter(a) },
                    onSelectProtocol = { p -> viewModel.setProtocol(p) },
                    onConnectAdapter = { connectSelectedAdapter() },
                    onRunSequence = { cmds, delays, cb -> viewModel.runManualSequence(cmds, delays, cb) },
                    onApplyPreset = { i -> viewModel.applyPreset(i) },
                    onResizeSessionLog = { dp -> viewModel.setSessionLogHeightDp(dp) },
                    onAutoSaveLogs = { autoSaveBothLogs() },
                    onPickCsvFolder = { launchPickCsvFolder() },
                    onRawLogNameChange = { name -> viewModel.setRawLogName(name) },
                    onSessionLogNameChange = { name -> viewModel.setSessionLogName(name) },
                    onResetLayout = { viewModel.resetLayout() },
                    onResetAdapter = { viewModel.resetObdLinkAdapter(applicationContext) },
                    onShareSavedSession = { launchShareSavedSession() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerUsbReceiver()
        syncAdapterPresenceAndAutoConnect()
    }

    override fun onStop() {
        unregisterReceiverSafely()
        super.onStop()
    }

    override fun onDestroy() {
        viewModel.clearOpenSession()
        super.onDestroy()
    }

    /**
     * Connected path: kick off the action immediately. Disconnected path:
     * route to the right adapter's connect flow (USB for OpenPort, BT for
     * OBDLink) — connecting only brings the link up; it never auto-runs the
     * action. Once connected (status stripes update), the user taps again to
     * start, so a plug-in can't auto-start a stale action.
     */
    private fun runActionOrDiscover(action: PendingAction) {
        val s = viewModel.uiState.value.settings
        if (s.simulatorMode || viewModel.isConnected()) {
            when (action) {
                PendingAction.Probe -> viewModel.runProbe()
                PendingAction.ReadLive -> viewModel.startReadingLive(recordToLog = false)
                PendingAction.LogLive -> viewModel.startLogging()
            }
        } else {
            when (s.adapter) {
                Adapter.OpenPort -> discoverAndConnect()
                Adapter.OBDLink -> ensureBtPermissionThenConnect()
                Adapter.OBDLinkEx -> connectObdLinkEx()
                Adapter.Ft232rl -> connectFt232rl()
                null -> viewModel.setConnectionStatus(
                    ConnectionStatus.NoDevice,
                    "Pick ADAPTER and PROTOCOL in Settings before reading live data"
                )
            }
        }
    }

    private fun discoverAndConnect() {
        val device = sessionManager.findDevice()
        if (device == null) {
            viewModel.setAdapterPresent(false)
            viewModel.setConnectionStatus(
                ConnectionStatus.NoDevice,
                "Tactrix VID=$TACTRIX_VENDOR_ID PID=$TACTRIX_PRODUCT_ID not detected"
            )
            return
        }
        viewModel.setAdapterPresent(true)

        if (!sessionManager.hasPermission(device)) {
            viewModel.setConnectionStatus(
                ConnectionStatus.PermissionRequired(deviceLabel(device)),
                "Requesting USB permission..."
            )
            usbPermissionHelper.requestUsbPermission(device, ACTION_USB_PERMISSION)
            return
        }

        openSessionForDevice(device)
    }

    private fun openSessionForDevice(device: UsbDevice) {
        val result = sessionManager.openSession(device)
        when (result) {
            is OpenPort2SessionResult.Connected -> {
                viewModel.setOpenSession(result.session, deviceLabel(device))
            }
            is OpenPort2SessionResult.Failed -> {
                viewModel.setConnectionStatus(
                    ConnectionStatus.Error(result.reason),
                    "openSession failed: ${result.reason}"
                )
            }
        }
    }

    // The OBDLink EX is an FTDI USB-serial device (shares the FTDI vendor id
    // with the Tactrix, distinct product id). It does NOT use the Tactrix bulk
    // session — only the raw UsbDeviceConnection, which the VM hands to
    // ObdLinkUsbManager / FtdiUsbSerial.
    private fun findObdLinkExDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == FtdiUsbSerial.FTDI_VENDOR_ID &&
                it.productId == FtdiUsbSerial.OBDLINK_EX_PRODUCT_ID
        }

    // The VAG-KKL cable is a plain FT232RL behind FTDI's generic FT232R product
    // id — distinct from the EX's FT231X (0x6015) and the Tactrix (0xCC4D), so
    // it dispatches cleanly on VID+PID like the others.
    private fun findFt232rlDevice(): UsbDevice? =
        usbManager.deviceList.values.firstOrNull {
            it.vendorId == FtdiUsbSerial.FTDI_VENDOR_ID &&
                it.productId == FtdiUsbSerial.FT232RL_PRODUCT_ID
        }

    /**
     * Tap on the OBDLink EX adapter (or Read Live with EX selected). Mirrors the
     * OpenPort USB flow — discover the FTDI device, request USB permission if
     * needed — but on grant opens a plain connection and hands it to the VM's
     * OBDLink-EX connect instead of opening a Tactrix bulk session.
     */
    private fun connectObdLinkEx(fromDev: Boolean = false) {
        val device = findObdLinkExDevice()
        if (device == null) {
            viewModel.setAdapterPresent(false)
            viewModel.setConnectionStatus(
                ConnectionStatus.NoDevice,
                "OBDLink EX (FTDI VID=${FtdiUsbSerial.FTDI_VENDOR_ID} " +
                    "PID=${FtdiUsbSerial.OBDLINK_EX_PRODUCT_ID}) not detected"
            )
            return
        }
        viewModel.setAdapterPresent(true)
        if (!usbManager.hasPermission(device)) {
            pendingConnectFromDev = fromDev
            viewModel.setConnectionStatus(
                ConnectionStatus.PermissionRequired(deviceLabel(device)),
                "Requesting USB permission for OBDLink EX..."
            )
            usbPermissionHelper.requestUsbPermission(device, ACTION_USB_PERMISSION)
            return
        }
        openObdLinkExForDevice(device, fromDev)
    }

    private fun openObdLinkExForDevice(device: UsbDevice, fromDev: Boolean = false) {
        val connection = usbManager.openDevice(device)
        if (connection == null) {
            viewModel.setConnectionStatus(
                ConnectionStatus.Error("openDevice returned null"),
                "OBDLink EX: USB openDevice failed"
            )
            return
        }
        viewModel.connectObdLinkEx(connection, device, fromDev)
    }

    private fun handlePermissionResult(intent: Intent) {
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val device = usbPermissionHelper.getUsbDeviceFromIntent(intent)

        if (device == null) {
            viewModel.setConnectionStatus(
                ConnectionStatus.Error("Permission callback missing device"),
                "Permission callback returned no device"
            )
            return
        }

        // Tactrix, the OBDLink EX, and the KKL cable all share the FTDI vendor
        // id, so dispatch on the full VID+PID. All come back through this one
        // ACTION_USB_PERMISSION.
        val isEx = device.vendorId == FtdiUsbSerial.FTDI_VENDOR_ID &&
            device.productId == FtdiUsbSerial.OBDLINK_EX_PRODUCT_ID
        val isFt232rl = device.vendorId == FtdiUsbSerial.FTDI_VENDOR_ID &&
            device.productId == FtdiUsbSerial.FT232RL_PRODUCT_ID
        val isTactrix = device.vendorId == TACTRIX_VENDOR_ID &&
            device.productId == TACTRIX_PRODUCT_ID

        if (!granted) {
            viewModel.setConnectionStatus(
                ConnectionStatus.Error("USB permission denied"),
                when {
                    isEx -> "User denied USB permission for OBDLink EX"
                    isFt232rl -> "User denied USB permission for FT232RL"
                    else -> "User denied USB permission for Tactrix device"
                }
            )
            return
        }

        when {
            isEx -> openObdLinkExForDevice(device, pendingConnectFromDev)
            isFt232rl -> openFt232rlForDevice(device)
            isTactrix -> openSessionForDevice(device)
            else -> viewModel.setConnectionStatus(
                ConnectionStatus.Error("Permission callback returned an unexpected device"),
                "Permission callback returned a different device"
            )
        }
    }

    private fun handleDetached(intent: Intent) {
        val device = usbPermissionHelper.getUsbDeviceFromIntent(intent) ?: return
        if (device.vendorId == FtdiUsbSerial.FTDI_VENDOR_ID &&
            device.productId == FtdiUsbSerial.OBDLINK_EX_PRODUCT_ID
        ) {
            viewModel.disconnectObdLinkEx()
            viewModel.setAdapterPresent(false)
            viewModel.setConnectionStatus(
                ConnectionStatus.NoDevice,
                "OBDLink EX detached"
            )
            return
        }
        if (device.vendorId == FtdiUsbSerial.FTDI_VENDOR_ID &&
            device.productId == FtdiUsbSerial.FT232RL_PRODUCT_ID
        ) {
            viewModel.disconnectFt232rl()
            viewModel.setAdapterPresent(false)
            viewModel.setConnectionStatus(
                ConnectionStatus.NoDevice,
                "FT232RL detached"
            )
            return
        }
        if (device.vendorId != TACTRIX_VENDOR_ID || device.productId != TACTRIX_PRODUCT_ID) {
            return
        }
        viewModel.clearOpenSession()
        viewModel.setAdapterPresent(false)
        viewModel.setConnectionStatus(
            ConnectionStatus.NoDevice,
            "Tactrix device detached"
        )
    }

    /**
     * USB attach while the app is open (or right after the manifest filter
     * launches it on plug-in). Auto-select OpenPort, mark the adapter present,
     * and connect — no manual selection needed. The first attach prompts for
     * USB permission; once "always" is granted, subsequent attaches connect
     * silently.
     */
    private fun handleAttached(intent: Intent) {
        val device = usbPermissionHelper.getUsbDeviceFromIntent(intent) ?: return
        if (device.vendorId != TACTRIX_VENDOR_ID || device.productId != TACTRIX_PRODUCT_ID) {
            return
        }
        viewModel.setAdapterPresent(true)
        onAdapterChanged(Adapter.OpenPort)
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
    }

    private fun unregisterReceiverSafely() {
        try {
            unregisterReceiver(usbReceiver)
        } catch (_: IllegalArgumentException) {
        }
    }

    /**
     * Reconcile USB presence with the UI and auto-connect. Called on create /
     * start, which also covers the manifest-launch-on-attach case. A present
     * USB device → mark present + auto-select OpenPort + connect (if not
     * already connected). No USB device → mark absent (stripe invisible),
     * unless OBDLink is the active adapter, whose presence the VM owns.
     */
    private fun syncAdapterPresenceAndAutoConnect() {
        val device = sessionManager.findDevice()
        if (device != null) {
            viewModel.setAdapterPresent(true)
            if (!viewModel.isConnected()) onAdapterChanged(Adapter.OpenPort)
        } else if (viewModel.uiState.value.settings.adapter != Adapter.OBDLink &&
            viewModel.uiState.value.settings.adapter != Adapter.OBDLinkEx
        ) {
            viewModel.setAdapterPresent(false)
            viewModel.setConnectionStatus(
                ConnectionStatus.NoDevice,
                "No Tactrix device on USB bus"
            )
        }
    }

    private fun deviceLabel(device: UsbDevice): String {
        return "VID=${device.vendorId} PID=${device.productId}"
    }

    private fun copyLogToClipboard() {
        val text = ProtocolLogFormatter.formatForExport(viewModel.uiState.value)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            ClipData.newPlainText("PROTOCOL Log", text)
        )
        Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    private fun launchExportLog() {
        pendingExportText = ProtocolLogFormatter.formatForExport(viewModel.uiState.value)
        exportLogLauncher.launch(ProtocolLogFormatter.suggestedExportFileName())
    }

    private fun copySessionLogToClipboard() {
        val state = viewModel.uiState.value
        if (state.sessionLog.isEmpty()) {
            Toast.makeText(this, "No session data to copy", Toast.LENGTH_SHORT).show()
            return
        }
        val text = ProtocolLogFormatter.formatSessionLogCleanText(
            state.sessionLog,
            state.pidIdsOnLiveData,
            oldestFirst = true
        )
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PROTOCOL Session Log", text))
        Toast.makeText(this, "Session log copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // Share the most recent autosaved session log via the system share
    // chooser. Used by the "Share Saved Session" button in Live Data
    // Settings — gives the user a way to recover their session log after
    // a crash / process kill without needing a file-browser workaround.
    private fun launchShareSavedSession() {
        if (!sessionLogStore.exists()) {
            Toast.makeText(this, "No saved session log on disk", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = try {
            FileProvider.getUriForFile(this, "$packageName.fileprovider", sessionLogStore.file)
        } catch (e: Exception) {
            Toast.makeText(this, "Share prep failed: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, sessionLogStore.file.name)
            putExtra(Intent.EXTRA_TITLE, sessionLogStore.file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Share saved session"))
    }

    // Export the live-data session log straight to the user's Files via the
    // Storage Access Framework save-as picker (ACTION_CREATE_DOCUMENT). The
    // user picks the destination (Downloads, Documents, Drive, …) and the CSV
    // is written directly to it — no share sheet, no FileProvider temp file.
    private fun launchExportSessionLog() {
        val csv = ProtocolLogFormatter.formatSessionLogCsv(viewModel.uiState.value)
        if (csv.isEmpty()) {
            Toast.makeText(this, "No session data to export", Toast.LENGTH_SHORT).show()
            return
        }
        pendingExportText = csv
        exportCsvLauncher.launch(ProtocolLogFormatter.suggestedCsvFileName())
    }

    // Diagnostics page: copy the decoded DTC list (current + stored) to the
    // clipboard as plain text.
    private fun copyDtcToClipboard() {
        val s = viewModel.uiState.value
        if (s.dtcCurrent.isEmpty() && s.dtcStored.isEmpty()) {
            Toast.makeText(this, "No codes to copy — run READ CODES first", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        if (s.dtcStatus.isNotEmpty()) sb.append(s.dtcStatus).append("\n\n")
        sb.append("CURRENT (").append(s.dtcCurrent.size).append(")\n")
        s.dtcCurrent.forEach { sb.append("  ").append(it).append('\n') }
        sb.append("\nSTORED (").append(s.dtcStored.size).append(")\n")
        s.dtcStored.forEach { sb.append("  ").append(it).append('\n') }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("PROTOCOL DTCs", sb.toString()))
        Toast.makeText(this, "Codes copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    // Diagnostics page: export the decoded DTC list to a CSV via the SAF
    // save-as picker (Type,Code,Description). Description is quoted so a stray
    // comma can't shift columns.
    private fun launchExportDtc() {
        val s = viewModel.uiState.value
        if (s.dtcCurrent.isEmpty() && s.dtcStored.isEmpty()) {
            Toast.makeText(this, "No codes to export — run READ CODES first", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder("Type,Code,Description\n")
        fun row(type: String, line: String) {
            val i = line.indexOf("  ")
            val code = if (i >= 0) line.substring(0, i) else line
            val desc = if (i >= 0) line.substring(i).trim() else ""
            sb.append(type).append(',').append(code).append(",\"")
                .append(desc.replace("\"", "\"\"")).append("\"\n")
        }
        s.dtcCurrent.forEach { row("CURRENT", it) }
        s.dtcStored.forEach { row("STORED", it) }
        pendingExportText = sb.toString()
        exportCsvLauncher.launch("dtc.csv")
    }

    private fun launchPickCsvFolder() {
        pickCsvFolderLauncher.launch(null)
    }

    // Lock-and-Tap auto-save: write BOTH logs — the Live Data session log and
    // the dev RAW BYTES stream — into the chosen folder, each under its own
    // base name with a numeric suffix (session1.csv / rawbytes1.csv, then 2, 3…).
    // No-op with a toast if no folder is set yet; skips a log that has no data.
    private fun autoSaveBothLogs() {
        val s = viewModel.uiState.value.settings
        val folder = s.csvFolderUri?.let { Uri.parse(it) }
        if (folder == null) {
            Toast.makeText(this, "Set a CSV folder in Settings to auto-save", Toast.LENGTH_LONG).show()
            return
        }
        val saved = ArrayList<String>()
        val sessionCsv = ProtocolLogFormatter.formatSessionLogCsv(viewModel.uiState.value)
        if (sessionCsv.isNotEmpty()) {
            CsvDestination.writeEnumerated(this, folder, s.sessionLogName, sessionCsv)?.let { saved.add(it) }
        }
        val rawCsv = RawByteLog.formatCsv()
        if (rawCsv.isNotEmpty()) {
            CsvDestination.writeEnumerated(this, folder, s.rawLogName, rawCsv)?.let { saved.add(it) }
        }
        val msg = if (saved.isEmpty()) "Auto-save: nothing to write" else "Auto-saved: ${saved.joinToString(", ")}"
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
