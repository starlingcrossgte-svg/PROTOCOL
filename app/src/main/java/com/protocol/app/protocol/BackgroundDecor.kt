package com.protocol.app.protocol

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope

// Decorative background — sharp angular black "spikes" scattered around
// the edges of the screen for Y2K terminal-panel depth without overdoing
// it. Drawn via a Canvas layer beneath the content Column in
// ProtocolScreen. Alpha kept low so the shapes whisper rather than shout
// against whatever surface (default ScreenBg or a user-picked photo)
// sits under them.
internal fun drawY2kBackgroundDecor(scope: DrawScope) = with(scope) {
    val w = size.width
    val h = size.height
    val deep = Color(0xFF000000).copy(alpha = 0.55f)

    fun spike(points: List<Pair<Float, Float>>) {
        val p = Path().apply {
            moveTo(points[0].first, points[0].second)
            for (i in 1 until points.size) lineTo(points[i].first, points[i].second)
            close()
        }
        drawPath(path = p, color = deep)
    }

    // Top-left horizontal shard pointing right.
    spike(listOf(
        0f to h * 0.04f,
        w * 0.32f to h * 0.07f,
        w * 0.18f to h * 0.09f,
        0f to h * 0.08f
    ))

    // Right edge thin shard pointing left.
    spike(listOf(
        w to h * 0.22f,
        w * 0.66f to h * 0.26f,
        w * 0.82f to h * 0.28f,
        w to h * 0.27f
    ))

    // Left mid-ish stubby triangle pointing right.
    spike(listOf(
        0f to h * 0.50f,
        w * 0.18f to h * 0.52f,
        0f to h * 0.54f
    ))

    // Bottom-right diagonal slash.
    spike(listOf(
        w to h * 0.78f,
        w * 0.62f to h * 0.86f,
        w * 0.78f to h * 0.88f,
        w to h * 0.83f
    ))

    // Bottom-left small chevron.
    spike(listOf(
        0f to h * 0.93f,
        w * 0.22f to h * 0.95f,
        w * 0.10f to h * 0.97f,
        0f to h * 0.96f
    ))
}
