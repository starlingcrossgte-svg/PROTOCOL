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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.pm.PackageInfoCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Notices sub-page (opened from Home). One scrolling page that gathers the
// app's build facts, supporter/sponsor credits, open-source license notices,
// and a use-at-your-own-risk disclaimer.
//
// Build facts are read from the installed package at runtime (PackageManager),
// so no BuildConfig / gradle wiring is needed and the numbers always reflect
// the APK that's actually running. Credit + sponsor text is static — edit it
// directly in this file.

@Composable
internal fun NoticesBody() {
    val context = LocalContext.current
    val build = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            BuildFacts(
                version = info.versionName ?: "—",
                code = PackageInfoCompat.getLongVersionCode(info).toString(),
                pkg = context.packageName,
                updated = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(info.lastUpdateTime))
            )
        }.getOrElse { BuildFacts("—", "—", context.packageName, "—") }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CategoryHeader("BUILD")
        InfoLine("App", "PROTOCOL")
        InfoLine("Version", "${build.version} (${build.code})")
        InfoLine("Package", build.pkg)
        InfoLine("Updated", build.updated)

        CategoryHeader("CREDITS")
        Body("Special thanks to the supporters who made PROTOCOL possible. (Edit this list in NoticesBody.kt.)")

        CategoryHeader("SPONSORS")
        Body("No sponsors yet. (Edit this section in NoticesBody.kt.)")

        CategoryHeader("LICENSES")
        Body(
            "PROTOCOL is built with open-source software, used under the Apache License 2.0:\n" +
                "- Jetpack Compose, AndroidX Core / AppCompat / Activity / Lifecycle\n" +
                "- Kotlin and kotlinx.coroutines\n" +
                "- Coil (io.coil-kt)\n\n" +
                "Full license text: https://www.apache.org/licenses/LICENSE-2.0"
        )

        CategoryHeader("DISCLAIMER")
        Body(
            "PROTOCOL reads from — and can reflash — vehicle control modules (ECU/TCM). " +
                "Improper use can damage your modules or vehicle and may affect emissions compliance. " +
                "Use entirely at your own risk. No warranty, express or implied, is provided."
        )
    }
}

private data class BuildFacts(
    val version: String,
    val code: String,
    val pkg: String,
    val updated: String
)

// Fixed-width label so the values line up in the monospace column.
@Composable
private fun InfoLine(label: String, value: String) {
    Text(
        text = label.padEnd(9) + value,
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall
    )
}

@Composable
private fun Body(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall
    )
}
