package com.protocol.app.protocol

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.protocol.app.obdlink.ObdLinkTrafficLog
import com.protocol.app.openport2.UsbTrafficLog

// Flash ECU silo — the dedicated firmware read/write page (Home → "Flash ECU").
// Deliberately minimal: the two user-file pickers (kernel + ROM, which change
// every session) sit up top, then READ / TEST WRITE / COMMIT WRITE stacked, then
// the shared TRANSPORT log for progress + CSV export. Kernel grammar (BARE/BEEF)
// and PREP/VERBATIM live in Home → Settings (they change rarely). No CONNECT —
// OpenPort auto-connects on USB attach; the actions report "plug in & grant USB"
// if the link isn't up. Bench targets only.
@Composable
internal fun FlashBody(
    uiState: ProtocolUiState,
    onSelectKernel: () -> Unit,
    onClearKernel: () -> Unit,
    onSelectWriteRom: () -> Unit,
    onClearWriteRom: () -> Unit,
    onReadFirmware: () -> Unit,
    onWriteFirmware: (Boolean) -> Unit
) {
    val s = uiState.settings
    // Shared TRANSPORT log + CSV export (defined in DeveloperBody) — same readout
    // both pages render, so flash progress and proof capture live in one place.
    val merged = rememberMergedTransportLog()
    val exportLog = rememberTransportLogExport()

    val kernelName = s.kernelUri?.let {
        (android.net.Uri.parse(it).lastPathSegment ?: "kernel selected").substringAfterLast('/')
    }
    val romName = s.writeRomUri?.let {
        (android.net.Uri.parse(it).lastPathSegment ?: "ROM selected").substringAfterLast('/')
    }

    // COMMIT WRITE is the only destructive action on this page; gate it behind a
    // modal confirmation so a real flash can never fire on a single stray tap.
    var showCommitConfirm by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Log owns the top — fills all the space above the controls (scrollable),
        // so flash progress is the focus. compactHeight = null = fill mode.
        CombinedLogCard(
            lines = merged,
            onClear = { UsbTrafficLog.clear(); ObdLinkTrafficLog.clear() },
            onExportCsv = { exportLog(merged, "protocol-flash.csv") },
            modifier = Modifier.weight(1f).fillMaxWidth(),
            compactHeight = null
        )

        // Controls anchored at the bottom. The two user-file pickers (kernel + ROM;
        // picked via the system picker / "Open with PROTOCOL", never bundled — the
        // text IS the chosen file, red ✕ clears), then the three actions. READ is
        // safe (read-only dump); COMMIT WRITE (red) is the only destructive one;
        // TEST WRITE only VALIDATEs. Each action passes its own mode — no toggle.
        FilePickerRow(
            placeholder = "SELECT KERNEL FILE",
            selectedName = kernelName,
            onSelect = onSelectKernel,
            onClear = onClearKernel
        )
        FilePickerRow(
            placeholder = "SELECT ROM TO WRITE",
            selectedName = romName,
            onSelect = onSelectWriteRom,
            onClear = onClearWriteRom
        )
        DevActionButton("READ FIRMWARE", Modifier.fillMaxWidth(), border = Accent) { onReadFirmware() }
        DevActionButton("TEST WRITE", Modifier.fillMaxWidth(), border = Accent) { onWriteFirmware(true) }
        DevActionButton("COMMIT WRITE", Modifier.fillMaxWidth(), border = Color(0xFFE53935)) { showCommitConfirm = true }
    }

    if (showCommitConfirm) {
        CommitWriteConfirmDialog(
            onConfirm = {
                showCommitConfirm = false
                onWriteFirmware(false)
            },
            onCancel = { showCommitConfirm = false }
        )
    }
}

// A single user-file picker row: centered placeholder/filename, tap to pick, a red
// ✕ to clear (mirrored transparent on the left so the label stays centered).
@Composable
private fun FilePickerRow(
    placeholder: String,
    selectedName: String?,
    onSelect: () -> Unit,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceBg)
            .border(1.dp, Accent, RoundedCornerShape(8.dp)),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (selectedName != null) {
            Text(
                "✕",
                color = Color.Transparent,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .clickable { onSelect() }
                .padding(vertical = 12.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                selectedName ?: placeholder,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        if (selectedName != null) {
            Text(
                "✕",
                color = Color(0xFFE53935),
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .clickable { onClear() }
                    .padding(horizontal = 16.dp)
            )
        }
    }
}
