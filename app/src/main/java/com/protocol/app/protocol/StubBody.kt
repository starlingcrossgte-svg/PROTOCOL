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
            "ECU flashing is being built as a dedicated, standalone app — deliberately kept out of this live-logging app so a flash command can never cross into a logging path and brick an ECU. This button will become a download link to that app once it ships.",
            "In development in its own app. It folds back into PROTOCOL only when it's proven bulletproof."
        )
        SubPage.Tuning -> Pair(
            "This one will take a while to work on compatible ECUs.",
            ""
        )
        // Pages with a real body of their own never reach StubBody.
        SubPage.Parameters, SubPage.TcmParameters, SubPage.Unverified,
        SubPage.Presets, SubPage.Notices, SubPage.Library,
        SubPage.Developer, SubPage.TableEditor, SubPage.Navigation -> Pair("", "")
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
        // The effort note is optional. A page whose whole message is one line
        // should show one line, not one line followed by an empty heading.
        if (eta.isNotBlank()) {
            CategoryHeader("EFFORT")
            Text(
                text = eta,
                color = InkPrimary,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}
