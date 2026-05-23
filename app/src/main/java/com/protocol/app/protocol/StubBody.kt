package com.protocol.app.protocol

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

// Generic "coming soon" body. Title + close X live in the shared header;
// this composable only renders the body content. Used for the three
// stubbed sub-pages (Flash ECU, Diagnostics / CEL, Minor Tuning).
// Settings has its own real body (SettingsBody.kt).

@Composable
internal fun StubBody(page: SubPage) {
    val (blurb, eta) = when (page) {
        SubPage.Settings -> Pair(
            "Tunable app preferences — units (F/C), session log size, gauge layout reset, baud override for adapter testing.",
            "Lightweight — days of work once the list of options is locked."
        )
        SubPage.Flash -> Pair(
            "Full ECU reflash over OpenPort. Seed/key security access, flash-mode init, page-aligned erase + write, checksum, verify, ECU reset.",
            "Substantial — a multi-week project. The lower layers (USB + Tactrix line protocol + frame parser) are already in place; the flash sequence itself still needs to be written and tested very carefully."
        )
        SubPage.Diagnostics -> Pair(
            "Read stored DTCs from ECM (and TCM later) and decode them to P-codes with descriptions. SSM2 has a dedicated query for this; we'd sweep modules and group results.",
            "Moderate — couple of weeks, mostly because the DTC label table has to be hand-curated per family."
        )
        SubPage.Tuning -> Pair(
            "Live RAM-resident tunables: rev limiter, fuel cutoff, idle target, etc. Reads via SSM2 0xA8, writes via 0xB8. Addresses come from the per-ECU calibration definitions (EcuFlash/RomRaider XML).",
            "Few weeks once we settle on which parameters are in scope and pull the EZ30R definitions in."
        )
        SubPage.Parameters, SubPage.LiveDataSettings -> Pair("", "")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            text = "Coming soon",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.titleLarge
        )
        Text(
            text = blurb,
            color = InkPrimary,
            style = MaterialTheme.typography.bodyMedium
        )
        CategoryHeader("EFFORT")
        Text(
            text = eta,
            color = InkPrimary,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
        )
    }
}
