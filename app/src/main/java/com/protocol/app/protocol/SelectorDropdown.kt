package com.protocol.app.protocol

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.sp

/**
 * One row of a [SelectorDropdown]. [enabled] = false renders it greyed and
 * non-clickable — used for options that aren't wired yet (so they can't be
 * picked, and never look "selected").
 */
data class DropdownItem(val label: String, val enabled: Boolean = true)

/**
 * Shared rounded single-select dropdown — the dev-console look (Accent border,
 * SurfaceBg header with a ▾ chevron, SurfaceAlt expanded list). Used by the
 * Settings page and the Dev page so every selector is the exact same control.
 *
 * The header shows the selected item's label, or [placeholder] (dim) when nothing
 * is selected ([selectedIndex] == null). Tapping an *enabled* item fires
 * [onSelect] with its index and collapses; disabled items are greyed and inert.
 */
@Composable
internal fun SelectorDropdown(
    placeholder: String,
    items: List<DropdownItem>,
    selectedIndex: Int?,
    onSelect: (Int) -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(8.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Accent, shape)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = !open }
                .background(LocalButtonFill.current)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val headerLabel = selectedIndex?.let { items.getOrNull(it)?.label } ?: placeholder
            Text(
                headerLabel,
                color = if (selectedIndex != null) Color.White else NeutralGray,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                if (open) "▴" else "▾",
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold
            )
        }
        if (open) {
            Column(modifier = Modifier.fillMaxWidth().background(SurfaceAlt)) {
                items.forEachIndexed { i, item ->
                    val color = when {
                        !item.enabled -> NeutralGray
                        i == selectedIndex -> Accent
                        else -> Color.White
                    }
                    Text(
                        item.label,
                        color = color,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (item.enabled) Modifier.clickable { onSelect(i); open = false }
                                else Modifier
                            )
                            .padding(horizontal = 14.dp, vertical = 11.dp)
                    )
                }
            }
        }
    }
}
