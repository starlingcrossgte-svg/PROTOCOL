package com.protocol.app.protocol

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.protocol.app.openport2.Ssm2Pid
import com.protocol.app.openport2.Ssm2PidCategory
import com.protocol.app.openport2.Ssm2Pids

// Parameters sub-page. Full-page list (not a drawer). Tapping a
// parameter toggles whether its gauge is on the Live Data page; a
// checkmark marks parameters that already have a gauge present. The
// parameter never disappears from the list — that is intentional, so
// re-tapping puts the gauge back. Hardware back is handled by the
// ProtocolScreen-level BackHandler.

@Composable
internal fun ParametersBody(
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
