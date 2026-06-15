package com.protocol.app.protocol

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.protocol.app.obdlink.AdapterCommandLibrary
import com.protocol.app.obdlink.CommandKind
import com.protocol.app.obdlink.CommandSequence
import com.protocol.app.obdlink.QuickCommand

// Library — a READ-ONLY reference page reached from the Home "Library" button.
// It renders the app's verified knowledge straight out of AdapterCommandLibrary:
// the verified init sequences (grouped K-line / CAN) and the adapter command
// palettes (ELM/STN + OpenPort). It owns NO state and nothing depends on it —
// it's just a window onto the command library so the verified inits and commands
// are visible in-app instead of only in source. Easy to remove later: delete
// this file + the SubPage.Library case + the Home button.
//
// Later (planned): a per-adapter command catalog, and example polling commands
// appended to each sequence. The data store grows; this view just reads more of
// the same library object.

@Composable
internal fun LibraryBody() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        PageTitle("LIBRARY")

        // ── Verified init sequences, grouped by bus. ──
        SectionHeader("VERIFIED INITIATIONS")
        GroupHeader("K-LINE")
        AdapterCommandLibrary.ALL.filter { it.kline }.forEach { InitRow(it) }
        GroupHeader("CAN")
        AdapterCommandLibrary.ALL.filter { !it.kline }.forEach { InitRow(it) }

        // ── Adapter command palettes, grouped by adapter family. ──
        SectionHeader("ADAPTER COMMANDS")
        GroupHeader("ELM / STN  (OBDLink)")
        AdapterCommandLibrary.QUICK_COMMANDS.forEach { CommandRow(it) }
        GroupHeader("OPENPORT 2.0  (Tactrix)")
        AdapterCommandLibrary.OPENPORT_QUICK_COMMANDS.forEach { CommandRow(it) }
    }
}

@Composable
private fun PageTitle(text: String) {
    Text(
        text,
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(bottom = 4.dp)
    )
}

// Major section divider (INITIATIONS / ADAPTER COMMANDS).
@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = Accent,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 13.sp,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

// Sub-group within a section (K-LINE / CAN / adapter family).
@Composable
private fun GroupHeader(text: String) {
    Text(
        text,
        color = AccentDim,
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Bold,
        fontSize = 10.sp,
        modifier = Modifier.padding(start = 2.dp, top = 10.dp, bottom = 2.dp)
    )
}

// One init sequence rendered Parameters-style: a simple title (the sequence
// name) with all of its individual commands listed beneath, then a thin rule.
@Composable
private fun InitRow(seq: CommandSequence) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 10.dp)) {
            Text(
                seq.name,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyMedium
            )
            // The individual commands, in order — the heart of the entry.
            Text(
                seq.steps.joinToString("   ") { it.command },
                color = Accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
            Text(
                seq.description,
                color = NeutralGray,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        ThinRule()
    }
}

// One adapter command: the command text + its dim label, HEX-tagged (like the
// dev palette) so raw frames are obvious at a glance.
@Composable
private fun CommandRow(qc: QuickCommand) {
    val isHex = qc.kind == CommandKind.HEX_FRAME
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                qc.command,
                color = Color.White,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            Text(
                (if (isHex) "HEX · " else "") + qc.label,
                color = if (isHex) Accent else NeutralGray,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
        ThinRule()
    }
}

@Composable
private fun ThinRule() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(BorderGray.copy(alpha = 0.4f))
    )
}
