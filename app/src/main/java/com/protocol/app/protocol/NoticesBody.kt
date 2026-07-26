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

// Notices subpage (opened from Home). One scrolling page: the app version, the
// license (GPLv3), and a detailed at your own risk disclaimer covering the
// project's unfinished, reference material status.
//
// Build facts are read from the installed package at runtime (PackageManager),
// so no BuildConfig / gradle wiring is needed and the numbers always reflect
// the APK that's actually running. The license and disclaimer text is static, so
// edit it directly in this file.

@Composable
internal fun NoticesBody() {
    val context = LocalContext.current
    val build = remember {
        runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            BuildFacts(
                version = info.versionName ?: "unknown",
                code = PackageInfoCompat.getLongVersionCode(info).toString(),
                pkg = context.packageName,
                updated = SimpleDateFormat("yyyy.MM.dd", Locale.US).format(Date(info.lastUpdateTime))
            )
        }.getOrElse { BuildFacts("unknown", "unknown", context.packageName, "unknown") }
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

        CategoryHeader("LICENSE")
        Body(
            "PROTOCOL is free software licensed under the GNU General Public License, " +
                "version 3 (GPLv3). You are free to use, study, modify, and redistribute it " +
                "under those terms. It comes with ABSOLUTELY NO WARRANTY.\n\n" +
                "Full license text: https://www.gnu.org/licenses/gpl-3.0.html\n" +
                "(also shipped as the LICENSE file with the source).\n\n" +
                "Bundled third party components are used under the Apache License 2.0 " +
                "(Jetpack Compose, AndroidX, Kotlin and kotlinx.coroutines, Coil)."
        )

        CategoryHeader("DISCLAIMER")
        Body(
            "PROJECT STATUS: UNFINISHED and EXPERIMENTAL. PROTOCOL is a work in progress and is " +
                "not a finished or validated product. It is best treated as open source REFERENCE " +
                "MATERIAL, to be studied, adapted, and built upon in accordance with the GPLv3, " +
                "rather than relied on as a turnkey tool.\n\n" +
                "PROTOCOL reads from and can reflash vehicle control modules (ECU and TCM). Reading " +
                "is safe. Writing or flashing firmware is irreversible and can permanently damage " +
                "(brick) a module, affect drivability, and impact emissions compliance. Flashing is " +
                "intended for bench use on a spare module only.\n\n" +
                "Clearing diagnostic trouble codes and sending manual commands from the Developer " +
                "page can also affect or damage a module. Because the software is unfinished, no " +
                "operation is fully guarded, and you are expected to learn the effects of each " +
                "operation yourself before using it.\n\n" +
                "You, and any tuner you choose to involve, are solely responsible for having the " +
                "knowledge and judgment to use software of this kind safely. Use it entirely at " +
                "your own risk. To the maximum extent permitted by law, the authors and " +
                "contributors accept no liability for any damage, loss, or legal consequence " +
                "arising from its use. No warranty, express or implied, is provided."
        )
    }
}

private data class BuildFacts(
    val version: String,
    val code: String,
    val pkg: String,
    val updated: String
)

// Fixed width label so the values line up in the monospace column.
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
