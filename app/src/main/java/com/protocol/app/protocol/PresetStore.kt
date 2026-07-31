package com.protocol.app.protocol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists user presets, one drawer per parameter-set fingerprint, so each
 * definition sees only its own and switching back restores them.
 *
 * JSON via the platform parser (no dependency). Names are free text, so they
 * are not packed into a delimited string the way the gauge layout is.
 */
class PresetStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Malformed records are skipped, not thrown, so one bad entry cannot cost
     *  the user the rest of their presets. */
    fun load(fingerprint: String): List<UserPreset> {
        val raw = prefs.getString(keyFor(fingerprint), null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = ArrayList<UserPreset>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString(FIELD_ID).takeIf { it.isNotEmpty() } ?: continue
                val name = obj.optString(FIELD_NAME).takeIf { it.isNotEmpty() } ?: continue
                val idsJson = obj.optJSONArray(FIELD_PIDS) ?: continue
                val ids = ArrayList<String>(idsJson.length())
                for (j in 0 until idsJson.length()) {
                    idsJson.optString(j).takeIf { it.isNotEmpty() }?.let(ids::add)
                }
                if (ids.isEmpty()) continue
                out.add(UserPreset(id = id, name = name, pidIds = ids))
            }
            out
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(fingerprint: String, presets: List<UserPreset>) {
        if (presets.isEmpty()) {
            prefs.edit().remove(keyFor(fingerprint)).apply()
            return
        }
        val array = JSONArray()
        for (p in presets) {
            val ids = JSONArray()
            for (id in p.pidIds) ids.put(id)
            array.put(
                JSONObject()
                    .put(FIELD_ID, p.id)
                    .put(FIELD_NAME, p.name)
                    .put(FIELD_PIDS, ids)
            )
        }
        prefs.edit().putString(keyFor(fingerprint), array.toString()).apply()
    }

    private fun keyFor(fingerprint: String) = "$KEY_PREFIX$fingerprint"

    companion object {
        private const val PREFS_NAME = "protocol_presets"
        private const val KEY_PREFIX = "preset_set_"
        private const val FIELD_ID = "id"
        private const val FIELD_NAME = "name"
        private const val FIELD_PIDS = "pids"
    }
}
