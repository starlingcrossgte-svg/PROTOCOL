package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Hamburger opens a small dropdown with sub-page destinations plus the
// Split Screen toggle. Made internal so LiveDataPage can drop the same
// icon inline next to its mode buttons when the shared header is hidden.
@Composable
internal fun HamburgerMenu(
    onOpenParameters: () -> Unit,
    onOpenTcmParameters: () -> Unit,
    onOpenUnverified: () -> Unit,
    onOpenPresets: () -> Unit = {},
    onResetLayout: () -> Unit = {},
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clickable(enabled = enabled) { expanded = true }
            .size(width = 44.dp, height = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        // Dimmed while disabled (e.g. the Live Data page is locked).
        val barColor = if (enabled) Accent else AccentDim
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(2.dp)
                        .background(barColor)
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .background(SurfaceBg)
                .border(BorderStroke(1.dp, Accent))
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        "ECU Parameters",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                onClick = {
                    expanded = false
                    onOpenParameters()
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        "TCM Parameters",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                onClick = {
                    expanded = false
                    onOpenTcmParameters()
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        "Unverified",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                onClick = {
                    expanded = false
                    onOpenUnverified()
                }
            )
            DropdownMenuItem(
                text = {
                    Text(
                        "Presets",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                onClick = {
                    expanded = false
                    onOpenPresets()
                }
            )
            // Standalone action, not a destination.
            DropdownMenuItem(
                text = {
                    Text(
                        "Reset Layout",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                onClick = {
                    expanded = false
                    onResetLayout()
                }
            )
        }
    }
}

@Composable
internal fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clickable(onClick = onClick)
            .size(width = 44.dp, height = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val stroke = 2.5f.dp.toPx()
            drawLine(
                color = Accent,
                start = Offset(size.width * 0.18f, size.height * 0.18f),
                end = Offset(size.width * 0.82f, size.height * 0.82f),
                strokeWidth = stroke
            )
            drawLine(
                color = Accent,
                start = Offset(size.width * 0.82f, size.height * 0.18f),
                end = Offset(size.width * 0.18f, size.height * 0.82f),
                strokeWidth = stroke
            )
        }
    }
}
