package com.protocol.app.protocol

import android.net.Uri
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
    // When true this is the Unverified page: it shows every candidate PID
    // (verified == false) regardless of [category], kept apart from the
    // trusted lists. The category pages instead show only verified PIDs.
    showUnverified: Boolean = false
) {
    Column(modifier = Modifier.fillMaxSize()) {
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
                    ParameterRow(
                        pid = pid,
                        checked = pid.id in onLiveData,
                        onClick = { onTogglePid(pid.id) }
                    )
                }
            }
        }
        LoggerDefPickerRow(
            fileName = uiState.settings.loggerDefUri
                ?.let { Uri.parse(it).lastPathSegment?.substringAfterLast('/') },
            loadedCount = uiState.loadedPids.size,
            onSelect = onSelectDef
        )
    }
}

// File picker pinned at the bottom of the parameters pages. Tapping it opens the
// system document picker to choose an SSM2 logger definition; the app parses it
// and merges its parameters into the lists above. Once one is chosen the filename
// and the number of parameters it added are shown.
@Composable
private fun LoggerDefPickerRow(
    fileName: String?,
    loadedCount: Int,
    onSelect: () -> Unit
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
                .background(SurfaceBg)
                .border(1.dp, Accent, RoundedCornerShape(8.dp))
                .clickable(onClick = onSelect)
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
        if (fileName != null) {
            Text(
                "$loadedCount parameters loaded",
                color = NeutralGray,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
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
