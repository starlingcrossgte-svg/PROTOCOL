package com.protocol.app.protocol

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.protocol.app.openport2.Ssm2Pid
import com.protocol.app.openport2.Ssm2PidCategory
import com.protocol.app.openport2.Ssm2Pids

// Parameters sub-page. Full-page list (not a drawer). Tapping a parameter
// toggles whether its gauge is on the Live Data page; a checkmark marks
// parameters that already have a gauge present. The parameter never
// disappears from the list — that is intentional, so re-tapping puts the
// gauge back. Hardware back is handled by the ProtocolScreen-level
// BackHandler.
//
// One body, two sub-pages: ECU Parameters and TCM Parameters. The page
// title in the shared header tells the user which list they're looking at.

@Composable
internal fun ParametersBody(
    uiState: ProtocolUiState,
    category: Ssm2PidCategory,
    onTogglePid: (String) -> Unit,
    onSelectDef: () -> Unit = {},
    onClearDef: () -> Unit = {},
    // When true this is the Unverified page: it shows every candidate PID
    // (verified == false) regardless of [category], kept apart from the
    // trusted lists. The category pages instead show only verified PIDs.
    showUnverified: Boolean = false,
    // Preset selection mode. Non-null draft turns this page into a picker:
    // taps collect into the draft instead of moving gauges, and a pinned bar
    // appears at the top.
    onToggleDraftPid: (String) -> Unit = {},
    onCancelDraft: () -> Unit = {},
    onDoneDraft: () -> Unit = {}
) {
    val draft = uiState.presetDraft
    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned: the count and the way out must stay on screen while scrolling.
        if (draft != null) {
            PresetDraftBar(
                selected = draft.pidIds.size,
                onCancel = onCancelDraft,
                onDone = onDoneDraft
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            val onLiveData = uiState.pidIdsOnLiveData
            val pids = (Ssm2Pids.DEFAULT_DEMO_PIDS + uiState.loadedPids).filter {
                if (showUnverified) !it.verified else it.category == category && it.verified
            }

            if (pids.isEmpty()) {
                EmptyCategoryRow(
                    when {
                        showUnverified -> "No unverified parameters."
                        category == Ssm2PidCategory.ECU -> "No ECU parameters available."
                        else -> "No TCM parameters available yet."
                    }
                )
            } else {
                for (pid in pids) {
                    // In draft mode the check reflects the DRAFT, not the page.
                    val checked = if (draft != null) pid.id in draft.pidIds else pid.id in onLiveData
                    ParameterRow(
                        pid = pid,
                        checked = checked,
                        dimmed = draft != null && draft.isFull && !checked,
                        onClick = {
                            if (draft != null) onToggleDraftPid(pid.id) else onTogglePid(pid.id)
                        }
                    )
                }
            }
        }
        // Hidden mid-draft: changing the parameter set would invalidate it.
        if (draft == null) {
            LoggerDefPickerRow(
                fileName = uiState.settings.loggerDefName,
                status = uiState.loggerDefStatus,
                loading = uiState.loggerDefLoading,
                onSelect = onSelectDef,
                onClear = onClearDef
            )
        }
    }
}

/** CANCEL, the running count, and DONE, locked to the top during selection. */
@Composable
private fun PresetDraftBar(
    selected: Int,
    onCancel: () -> Unit,
    onDone: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceBg)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        DraftBarButton("CANCEL", BorderGray, onCancel)
        Text(
            "$selected / $MAX_PRESET_PIDS",
            color = if (selected >= MAX_PRESET_PIDS) Accent else NeutralGray,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center
        )
        DraftBarButton("DONE", if (selected > 0) Accent else BorderGray, onDone)
    }
}

@Composable
private fun DraftBarButton(label: String, border: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(SurfaceAlt)
            .border(1.dp, border, RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            label,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

/** Name prompt shown after DONE. Hosted at the screen root. */
@Composable
internal fun PresetNameDialog(
    count: Int,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ScreenBg)
                .border(1.dp, Accent, RoundedCornerShape(10.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "NAME THIS PRESET",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                "$count parameters selected.",
                color = NeutralGray,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall
            )
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Text,
                    autoCorrect = false,
                    capitalization = KeyboardCapitalization.Words
                ),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                ),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = BorderGray,
                    cursorColor = Accent,
                    focusedContainerColor = SurfaceBg,
                    unfocusedContainerColor = SurfaceBg
                )
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    DraftBarButton("BACK", BorderGray, onDismiss)
                }
                Box(modifier = Modifier.weight(1f)) {
                    DraftBarButton("SAVE", Accent) { onSave(name) }
                }
            }
        }
    }
}

// File picker pinned at the bottom of the parameters pages. Tapping it opens the
// system document picker to choose an SSM2 logger definition; the app parses it
// and merges its parameters into the lists above. Once one is chosen its name and
// the outcome of the load are shown — including when the file yielded nothing, so
// a definition that parsed to zero parameters never looks the same as one that
// worked.
@Composable
private fun LoggerDefPickerRow(
    fileName: String?,
    status: String,
    loading: Boolean,
    onSelect: () -> Unit,
    onClear: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(LocalButtonFill.current)
                .border(1.dp, Accent, RoundedCornerShape(8.dp))
                .clickable(enabled = !loading, onClick = onSelect)
                .padding(vertical = 12.dp, horizontal = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                fileName ?: "LOAD PARAMETER DEFINITION",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1
            )
        }
        if (status.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    status,
                    color = NeutralGray,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp)
                )
                if (!loading && fileName != null) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .border(1.dp, BorderGray, RoundedCornerShape(6.dp))
                            .clickable(onClick = onClear)
                            .padding(vertical = 6.dp, horizontal = 12.dp)
                    ) {
                        Text(
                            "CLEAR",
                            color = Color.White,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }
    }
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
    onClick: () -> Unit,
    /** Draft is full and this row is not part of it, so it cannot be added. */
    dimmed: Boolean = false
) {
    Column(modifier = Modifier.fillMaxWidth().clickable(enabled = !dimmed, onClick = onClick)) {
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
                    color = if (dimmed) NeutralGray else InkPrimary,
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
