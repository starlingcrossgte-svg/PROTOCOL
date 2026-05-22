package com.protocol.app.protocol

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists the user's custom background image URI (chosen from Settings →
 * Choose Background). Returns null when no background is set, in which
 * case the app renders the default ScreenBg + Y2K spike decor.
 *
 * URI is stored as a String. The Activity is responsible for taking
 * persistable read permission via ContentResolver.takePersistableUriPermission
 * before saving, otherwise the URI becomes unreadable after process death.
 */
class BackgroundStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): String? = prefs.getString(KEY_URI, null)

    fun save(uri: String?) {
        val editor = prefs.edit()
        if (uri == null) editor.remove(KEY_URI) else editor.putString(KEY_URI, uri)
        editor.apply()
    }

    companion object {
        private const val PREFS_NAME = "protocol_background"
        private const val KEY_URI = "uri"
    }
}
