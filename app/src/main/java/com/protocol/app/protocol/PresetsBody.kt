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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Presets page (Live Data hamburger). Shows only the presets belonging to the
 *  parameter set currently loaded. */
@Composable
internal fun PresetsBody(
    uiState: ProtocolUiState,
    onCreatePreset: () -> Unit,
    onApplyPreset: (String) -> Unit,
    onDeletePreset: (String) -> Unit
) {
    val presets = uiState.userPresets
    val onPage = uiState.pidIdsOnLiveData

    Column(modifier = Modifier.fillMaxSize()) {
        // Pinned at the top so it cannot scroll away on a long list.
        Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(LocalButtonFill.current)
                    .border(1.dp, Accent, RoundedCornerShape(8.dp))
                    .clickable(onClick = onCreatePreset)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "CREATE PRESET",
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (uiState.settings.loggerDefName == null) {
                Text(
                    "No definition loaded. Presets are saved against the definition they " +
                        "were built from, so load one first.",
                    color = NeutralGray,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                Text(
                    "Saved for ${uiState.settings.loggerDefName}",
                    color = NeutralGray,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.labelSmall
                )
            }

            if (presets.isEmpty()) {
                Text(
                    "No presets yet. CREATE PRESET picks up to $MAX_PRESET_PIDS parameters " +
                        "and saves them under a name.",
                    color = NeutralGray,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                for (preset in presets) {
                    PresetRow(
                        preset = preset,
                        // Active = its params are all on the page, not "last tapped":
                        // the page can be edited by hand afterwards.
                        active = preset.pidIds.isNotEmpty() && onPage.containsAll(preset.pidIds),
                        onApply = { onApplyPreset(preset.id) },
                        onDelete = { onDeletePreset(preset.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun PresetRow(
    preset: UserPreset,
    active: Boolean,
    onApply: () -> Unit,
    onDelete: () -> Unit
) {
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(LocalButtonFill.current)
            .border(1.dp, if (active) Accent else BorderGray, shape),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onApply)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Text(
                preset.name,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "${preset.pidIds.size} parameters",
                color = NeutralGray,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelSmall
            )
        }
        Box(
            modifier = Modifier
                .clickable(onClick = onDelete)
                .padding(horizontal = 18.dp, vertical = 14.dp)
        ) {
            Text(
                "✕",
                color = FailRed,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
