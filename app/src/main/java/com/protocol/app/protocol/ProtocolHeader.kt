package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.protocol.app.R

/**
 * Thin colored bar pinned to the top of the screen above the header.
 * Replaces the old AdapterPill — communicates connection state at a
 * glance without occupying meaningful real estate.
 */
@Composable
internal fun ConnectionStatusStripe(status: ConnectionStatus) {
    val color = when (status) {
        is ConnectionStatus.Connected -> PassGreen
        is ConnectionStatus.Ready,
        is ConnectionStatus.PermissionRequired -> Accent
        is ConnectionStatus.Error,
        ConnectionStatus.NoDevice -> FailRed
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(color)
    )
}

/**
 * Shared app header. Layout (left to right):
 *   - Optional sub-page title (null on the main pager)
 *   - Centered logo (always)
 *   - Right-side action: close X on sub-pages, hamburger on Live Data,
 *     nothing on Home
 *
 * The Accent stripe below the header doubles as the divider + Y2K
 * accent line.
 */
@Composable
internal fun ProtocolHeader(
    subPageTitle: String?,
    showHamburger: Boolean,
    onOpenParameters: () -> Unit,
    onOpenTcmParameters: () -> Unit,
    onOpenLiveDataSettings: () -> Unit,
    onClose: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(brush = headerBrush)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        if (subPageTitle != null) {
            Text(
                text = subPageTitle,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.CenterStart)
            )
        }

        Image(
            painter = painterResource(id = R.drawable.protocol_logo),
            contentDescription = "PROTOCOL",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .height(40.dp)
                .align(Alignment.Center)
        )

        when {
            subPageTitle != null -> CloseButton(
                onClick = onClose,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
            showHamburger -> HamburgerMenu(
                onOpenParameters = onOpenParameters,
                onOpenTcmParameters = onOpenTcmParameters,
                onOpenLiveDataSettings = onOpenLiveDataSettings,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
    }
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Accent))
}

// Hamburger now opens a tiny dropdown with two destinations rather than
// jumping straight to the full Parameters page. The dropdown is anchored
// to the icon — selecting an item triggers the matching sub-page nav and
// dismisses the menu.
@Composable
private fun HamburgerMenu(
    onOpenParameters: () -> Unit,
    onOpenTcmParameters: () -> Unit,
    onOpenLiveDataSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .clickable { expanded = true }
            .size(width = 44.dp, height = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(3) {
                Box(
                    modifier = Modifier
                        .width(22.dp)
                        .height(2.dp)
                        .background(Accent)
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
                        "Live Data Settings",
                        color = Color.White,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                onClick = {
                    expanded = false
                    onOpenLiveDataSettings()
                }
            )
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
        }
    }
}

@Composable
private fun CloseButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
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
