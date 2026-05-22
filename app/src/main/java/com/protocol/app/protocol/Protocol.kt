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
import androidx.activity.result.contract.ActivityResultContracts
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
    private var pendingCsvText: String = ""

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

    private val exportCsvLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri == null) { Toast.makeText(this, "Export cancelled", Toast.LENGTH_SHORT).show(); return@registerForActivityResult }
        try {
            contentResolver.openOutputStream(uri)?.use { it.write(pendingCsvText.toByteArray(StandardCharsets.UTF_8)) }
            Toast.makeText(this, "Session log exported", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Export failed: ${e.message ?: e.javaClass.simpleName}", Toast.LENGTH_LONG).show()
        } finally { pendingCsvText = "" }
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
                    onDiscoverDevice = { discoverAndConnect() },
                    onOpenSubPage = { page -> viewModel.openSubPage(page) },
                    onCloseSubPage = { viewModel.closeSubPage() },
                    onToggleGaugeForPid = { pidId -> viewModel.toggleGaugeForPid(pidId) },
                    onEnterEditMode = { viewModel.enterEditMode() },
                    onExitEditMode = { viewModel.exitEditMode() },
                    onRemoveGauge = { pidId -> viewModel.removeGaugeForPid(pidId) },
                    onResizeGauge = { pidId, c, r, w, h ->
                        viewModel.resizeGauge(pidId, c, r, w, h)
                    },
                    onRunProbe = { viewModel.runProbe() },
                    onStartReadingLive = { viewModel.startReadingLive(recordToLog = false) },
                    onStopReadingLive = { viewModel.stopReadingLive() },
                    onStartLogging = { viewModel.startLogging() },
                    onStopLogging = { viewModel.stopLogging() },
                    onClearLog = { viewModel.clearLog() },
                    onCopyLog = { copyLogToClipboard() },
                    onExportLog = { launchExportLog() },
                    onClearSessionLog = { viewModel.clearSessionLog() },
                    onCopySessionLog = { copySessionLogToClipboard() },
                    onExportSessionLog = { launchExportSessionLog() }
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

    private fun launchExportSessionLog() {
        val csv = ProtocolLogFormatter.formatSessionLogCsv(viewModel.uiState.value)
        if (csv.isEmpty()) {
            Toast.makeText(this, "No session data to export", Toast.LENGTH_SHORT).show()
            return
        }
        pendingCsvText = csv
        exportCsvLauncher.launch(ProtocolLogFormatter.suggestedCsvFileName())
    }
}
