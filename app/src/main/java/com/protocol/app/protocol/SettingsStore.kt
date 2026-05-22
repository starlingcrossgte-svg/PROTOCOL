package com.protocol.app.protocol

import android.content.Context

/**
 * Tunable app preferences exposed via the Settings sub-page. Default
 * values mirror the constants that were previously hardcoded so
 * existing behavior is unchanged on first launch.
 */
data class AppSettings(
    val pollIntervalMs: Int = DEFAULT_POLL_INTERVAL_MS,
    val sessionLogMaxSize: Int = DEFAULT_SESSION_LOG_MAX,
    val devMode: Boolean = false
) {
    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 200
        const val DEFAULT_SESSION_LOG_MAX = 1000
        const val POLL_INTERVAL_MIN = 100
        const val POLL_INTERVAL_MAX = 500
        const val SESSION_LOG_MIN = 500
        const val SESSION_LOG_MAX = 5000
    }
}

class SettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        pollIntervalMs = prefs.getInt(KEY_POLL_INTERVAL, AppSettings.DEFAULT_POLL_INTERVAL_MS),
        sessionLogMaxSize = prefs.getInt(KEY_LOG_MAX, AppSettings.DEFAULT_SESSION_LOG_MAX),
        devMode = prefs.getBoolean(KEY_DEV_MODE, false)
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putInt(KEY_POLL_INTERVAL, s.pollIntervalMs)
            .putInt(KEY_LOG_MAX, s.sessionLogMaxSize)
            .putBoolean(KEY_DEV_MODE, s.devMode)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "protocol_settings"
        private const val KEY_POLL_INTERVAL = "poll_interval_ms"
        private const val KEY_LOG_MAX = "session_log_max"
        private const val KEY_DEV_MODE = "dev_mode"
    }
}
