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

// Table Editor subpage (opened from Home). Calibration table editing: view and
// change the tables a ROM definition describes, against a ROM image the user
// supplies.
//
// STATUS: destination only. Nothing here reads or writes a ROM, and the page
// says so in one line rather than presenting controls that do nothing. The
// working spec that used to live here (definition inheritance, image matching,
// table rendering, editing, integrity fields on save) belongs in the project
// docs, not in front of a user who opened the page expecting a tool.
//
// Isolation: this page owns no transport and no live state. Editing a
// calibration is a file operation and is NOT writing a ROM to a module; putting
// a modified image on a module stays behind the ROM page and its confirmations.

@Composable
internal fun TableEditorBody() {
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
            text = "Coming very soon, possibly in the next couple of weeks.",
            color = InkPrimary,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}
