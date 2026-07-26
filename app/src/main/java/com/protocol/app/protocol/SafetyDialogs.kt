package com.protocol.app.protocol

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// Safety / liability surfaces. Two gates that stand between the user and the two
// riskiest facts about this app: (1) it is unfinished, experimental software that
// talks to a live control module at all; (2) a COMMIT flash is irreversible and can
// brick a module. Pure UI. No protocol or flash logic lives here.

private val WarnRed = Color(0xFFE53935)

private const val STARTUP_DISCLAIMER =
    "PROTOCOL is unfinished, experimental software for reverse engineering and " +
        "diagnostics. It is published as open source REFERENCE MATERIAL under the GNU " +
        "GPL v3, to be studied, adapted, and built upon. It is NOT a finished or " +
        "validated product.\n\n" +
        "It communicates directly with vehicle control modules (ECU and TCM). Reading " +
        "data is safe and does not change the module. Other operations can cause harm.\n\n" +
        "WRITING or FLASHING firmware is irreversible. It can permanently damage (brick) " +
        "a module, affect drivability, and impact emissions compliance. It is intended " +
        "for bench use on a spare module only.\n\n" +
        "Erasing or clearing diagnostic trouble codes clears stored fault history and can " +
        "reset learned adaptations.\n\n" +
        "Sending manual commands from the Developer page puts raw bytes on the bus. These " +
        "can also damage a module or vehicle.\n\n" +
        "Because this software is unfinished, no operation is fully guarded. You are " +
        "expected to learn the effects of each operation yourself before you use it. You, " +
        "and any tuner you choose to involve, are solely responsible for having the " +
        "knowledge and judgment to use software of this kind safely.\n\n" +
        "You use this software entirely at your own risk. To the maximum extent permitted " +
        "by law, the authors and contributors accept no liability and provide NO WARRANTY " +
        "of any kind."

private const val COMMIT_WARNING =
    "You are about to COMMIT a real write or ROM upload to a connected control module.\n\n" +
        "This is IRREVERSIBLE. It erases and rewrites the module's flash memory. If it is " +
        "interrupted, the image is wrong, or something else goes wrong during the write, the " +
        "module can be permanently bricked and the vehicle left inoperable. It may also " +
        "affect emissions compliance.\n\n" +
        "Only proceed if ALL of the following are true:\n" +
        "1. The target is a spare bench module, not a module you depend on.\n" +
        "2. You already have a full, known good backup ROM of this module saved.\n" +
        "3. You have the equipment and the knowledge to reflash that backup if this write " +
        "fails. That is what recovery means here: if you cannot flash it back, you cannot " +
        "undo a bad write.\n" +
        "4. Power and the adapter connection are stable and will not drop during the write.\n\n" +
        "Are you sure you want to continue?"

/**
 * Full screen launch gate. Shown over the app on every cold start until the user
 * taps I UNDERSTAND. The scrim swallows taps so nothing underneath is reachable.
 */
@Composable
internal fun StartupDisclaimerGate(onAcknowledge: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF00F1115))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {},
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(10.dp))
                .background(ScreenBg)
                .border(1.dp, WarnRed, RoundedCornerShape(10.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "⚠  READ BEFORE USE",
                color = WarnRed,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                STARTUP_DISCLAIMER,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
            )
            GateButton(
                label = "I UNDERSTAND",
                border = Accent,
                modifier = Modifier.fillMaxWidth(),
                onClick = onAcknowledge
            )
        }
    }
}

/**
 * Modal confirmation shown when the user taps COMMIT WRITE in the flash silo. The
 * OK path fires [onConfirm] (the real destructive write); CANCEL / back backs out.
 * Tapping outside is disabled so the destructive action is never dismissed by accident.
 */
@Composable
internal fun CommitWriteConfirmDialog(onConfirm: () -> Unit, onCancel: () -> Unit) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(dismissOnClickOutside = false)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(ScreenBg)
                .border(1.dp, WarnRed, RoundedCornerShape(10.dp))
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "⚠  CONFIRM COMMIT WRITE",
                color = WarnRed,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                COMMIT_WARNING,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                GateButton("CANCEL", Accent, Modifier.weight(1f), onCancel)
                GateButton("COMMIT", WarnRed, Modifier.weight(1f), onConfirm)
            }
        }
    }
}

@Composable
private fun GateButton(
    label: String,
    border: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceBg)
            .border(1.dp, border, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = Color.White,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
