package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Decorative section header used across Parameters, Settings, Stub, and
 * OutcomeCard bodies. Renders as `── LABEL ──` in SectionGray monospace
 * bold. Single source so the visual stays consistent if we tweak it.
 */
@Composable
internal fun CategoryHeader(label: String) {
    Text(
        "── $label ──",
        color = SectionGray,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
    )
}

@Composable
internal fun LogActionRow(
    title: String,
    onClear: () -> Unit,
    onExportCsv: () -> Unit,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null,
    titleAsHeader: Boolean = false
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (titleAsHeader) {
            Text(
                "── $title ──",
                color = SectionGray,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 10.dp)
            )
        } else {
            Text(
                text = title,
                color = InkPrimary,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }
        SimpleButton(
            "Clear Log",
            onClear,
            RectangleShape,
            contentPadding = PaddingValues(horizontal = 10.75.dp, vertical = 6.dp)
        )
        if (onCopy != null) SimpleButton("Copy", onCopy, RectangleShape)
        SimpleButton("Export CSV", onExportCsv, RectangleShape)
    }
}

@Composable
internal fun SimpleButton(
    text: String,
    onClick: () -> Unit,
    shape: androidx.compose.ui.graphics.Shape,
    containerColor: Color = SurfaceBg,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
) {
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 58.dp, minHeight = 40.dp)
            .background(containerColor, shape)
            .border(1.dp, Accent, shape)
            .clickable(onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = Color.White,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold
        )
    }
}
