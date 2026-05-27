package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

/**
 * Uniform header/action row for every log on the logging side (Session Log,
 * Probe Log, USB Traffic, OBDLink BT). Title on the left (weight 1f), then
 * Clear and Export CSV side by side. Single source so spacing, font, button
 * height, and the right-edge inset are identical on every log — modeled on the
 * Session Log. (The flash silo intentionally keeps its own controls.)
 *
 * The 2.dp end padding pulls the Export CSV button a hair inside the log
 * card's right edge so it no longer slightly overhangs it.
 */
@Composable
internal fun LogActionRow(
    title: String,
    onClear: () -> Unit,
    onExportCsv: () -> Unit,
    modifier: Modifier = Modifier,
    onCopy: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(end = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            color = InkPrimary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        LogActionButton("Clear", onClear)
        // Optional one-tap copy — the whole log to the clipboard, so there's no
        // need to manually highlight text (lazy-list selection is unreliable).
        if (onCopy != null) LogActionButton("Copy", onCopy)
        LogActionButton("Export CSV", onExportCsv)
    }
}

@Composable
private fun LogActionButton(text: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(
            containerColor = SurfaceBg,
            contentColor = Color.White
        ),
        shape = y2kCornerShape(),
        border = BorderStroke(1.dp, Accent),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
    }
}
