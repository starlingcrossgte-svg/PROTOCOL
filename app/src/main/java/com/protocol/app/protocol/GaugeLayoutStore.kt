package com.protocol.app.protocol

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the user's Live Data gauge layout across app restarts.
 *
 * Storage: a single SharedPreferences string key. Entries are joined with
 * `;`, fields with `:`, in the order pidId:col:row:width:height. A malformed
 * record yields a null return rather than crashing — the caller seeds a
 * default in that case.
 *
 * Why SharedPreferences instead of DataStore: keeps PROTOCOL on its current
 * dependency set (no new gradle coordinates), and the layout is tiny (≤ ~50
 * entries × 20 chars). The synchronous `apply()` cost is negligible compared
 * to the polling loop.
 */
class GaugeLayoutStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): GaugeLayout? {
        val raw = prefs.getString(KEY_LAYOUT, null) ?: return null
        return deserialize(raw)
    }

    fun save(layout: GaugeLayout) {
        prefs.edit().putString(KEY_LAYOUT, serialize(layout)).apply()
    }

    private fun serialize(layout: GaugeLayout): String =
        layout.entries.joinToString(";") { e ->
            "${e.pidId}:${e.col}:${e.row}:${e.width}:${e.height}"
        }

    private fun deserialize(raw: String): GaugeLayout? {
        if (raw.isBlank()) return GaugeLayout()
        val entries = raw.split(";").mapNotNull { part ->
            val pieces = part.split(":")
            if (pieces.size != 5) return@mapNotNull null
            val pid = pieces[0]
            if (pid.isBlank()) return@mapNotNull null
            val col = pieces[1].toIntOrNull() ?: return@mapNotNull null
            val row = pieces[2].toIntOrNull() ?: return@mapNotNull null
            val w = pieces[3].toIntOrNull() ?: 1
            val h = pieces[4].toIntOrNull() ?: 1
            GaugeLayoutEntry(pidId = pid, col = col, row = row, width = w, height = h)
        }
        return GaugeLayout(entries = entries)
    }

    companion object {
        private const val PREFS_NAME = "protocol_layout"
        private const val KEY_LAYOUT = "gauge_layout"
    }
}
