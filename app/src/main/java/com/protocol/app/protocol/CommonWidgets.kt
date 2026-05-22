package com.protocol.app.protocol

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
