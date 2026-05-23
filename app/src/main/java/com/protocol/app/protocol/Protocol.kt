package com.protocol.app.protocol

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import java.io.File
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModelProvider
import com.protocol.app.UsbPermissionHelper
import com.protocol.app.openport2.OpenPort2SessionResult
import com.protocol.app.openport2.OpenPort2UsbSessionManager
import java.nio.charset.StandardCharsets

class Protocol : ComponentActivity() {

    companion object {
        private const val TACTRIX_VENDOR_ID = 1027
        private const val TACTRIX_PRODUCT_ID = 52301
        private const val ACTION_USB_PERMISSION =
            "com.protocol.app.OPENPORT_USB_PERMISSION"
    }

    private lateinit var viewModel: ProtocolViewModel
    private lateinit var usbManager: UsbManager
    private lateinit var usbPermissionHelper: UsbPermissionHelper
    private lateinit var sessionManager: OpenPort2UsbSessionManager

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

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                ACTION_USB_PERMISSION -> handlePermissionResult(intent)
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

        refreshConnectionStatus()

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
                    onPollIntervalChange = { ms -> viewModel.setPollIntervalMs(ms) },
                    onSessionLogMaxChange = { rows -> viewModel.setSessionLogMaxSize(rows) },
                    onDevModeChange = { on -> viewModel.setDevMode(on) },
                    onSplitScreenChange = { on -> viewModel.setSplitScreenMode(on) },
                    onResetLayout = { viewModel.resetLayout() }
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        registerUsbReceiver()
        refreshConnectionStatus()
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
     * stash the action in the VM and start USB discovery; once the session
     * opens, ProtocolViewModel.setOpenSession replays the stashed action
     * so the user doesn't have to tap a second time.
     */
    private fun runActionOrDiscover(action: PendingAction) {
        if (viewModel.isConnected()) {
            when (action) {
                PendingAction.Probe -> viewModel.runProbe()
                PendingAction.ReadLive -> viewModel.startReadingLive(recordToLog = false)
                PendingAction.LogLive -> viewModel.startLogging()
            }
        } else {
            viewModel.setPendingAction(action)
            discoverAndConnect()
        }
    }

    private fun discoverAndConnect() {
        val device = sessionManager.findDevice()
        if (device == null) {
            viewModel.setConnectionStatus(
                ConnectionStatus.NoDevice,
                "Tactrix VID=$TACTRIX_VENDOR_ID PID=$TACTRIX_PRODUCT_ID not detected"
            )
            return
        }

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

    private fun handlePermissionResult(intent: Intent) {
        val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
        val device = usbPermissionHelper.getUsbDeviceFromIntent(intent)

        if (!granted) {
            viewModel.setConnectionStatus(
                ConnectionStatus.Error("USB permission denied"),
                "User denied USB permission for Tactrix device"
            )
            return
        }

        if (device == null ||
            device.vendorId != TACTRIX_VENDOR_ID ||
            device.productId != TACTRIX_PRODUCT_ID
        ) {
            viewModel.setConnectionStatus(
                ConnectionStatus.Error("Permission callback missing expected Tactrix device"),
                "Permission callback returned a different device"
            )
            return
        }

        openSessionForDevice(device)
    }

    private fun handleDetached(intent: Intent) {
        val device = usbPermissionHelper.getUsbDeviceFromIntent(intent) ?: return
        if (device.vendorId != TACTRIX_VENDOR_ID || device.productId != TACTRIX_PRODUCT_ID) {
            return
        }
        viewModel.clearOpenSession()
        viewModel.setConnectionStatus(
            ConnectionStatus.NoDevice,
            "Tactrix device detached"
        )
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter().apply {
            addAction(ACTION_USB_PERMISSION)
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

    private fun refreshConnectionStatus() {
        val device = sessionManager.findDevice()
        when {
            device == null -> viewModel.setConnectionStatus(
                ConnectionStatus.NoDevice,
                "No Tactrix device on USB bus"
            )
            !sessionManager.hasPermission(device) -> viewModel.setConnectionStatus(
                ConnectionStatus.PermissionRequired(deviceLabel(device)),
                "Tap Discover to request USB permission"
            )
            else -> viewModel.setConnectionStatus(
                ConnectionStatus.Ready(deviceLabel(device)),
                "Tap Discover to open USB session"
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

    // Export session log via the system share chooser. Writes the CSV to
    // app cache (exposed through FileProvider) and fires ACTION_SEND so the
    // user can pick Files, Gmail, Drive, Bluetooth, etc. — anything that
    // accepts a text/csv attachment. Previous behavior went straight to the
    // Files save-as picker, which gave no email / share options.
    private fun launchExportSessionLog() {
        val csv = ProtocolLogFormatter.formatSessionLogCsv(viewModel.uiState.value)
        if (csv.isEmpty()) {
            Toast.makeText(this, "No session data to export", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = ProtocolLogFormatter.suggestedCsvFileName()
        val uri = try {
            val exportsDir = File(cacheDir, "exports").apply { if (!exists()) mkdirs() }
            val tempFile = File(exportsDir, fileName)
            tempFile.writeText(csv, StandardCharsets.UTF_8)
            FileProvider.getUriForFile(this, "$packageName.fileprovider", tempFile)
        } catch (e: Exception) {
            Toast.makeText(this, "Export prep failed: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(send, "Export session log"))
    }
}
