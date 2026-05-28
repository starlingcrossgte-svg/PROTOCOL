package com.protocol.app.protocol

import android.content.Context

enum class Adapter { OpenPort, OBDLink }
enum class BusProtocol { KLine, CAN }
enum class SsmVariant { SSM2, SSM3 }

data class AppSettings(
    val pollIntervalMs: Int = DEFAULT_POLL_INTERVAL_MS,
    val sessionLogMaxSize: Int = DEFAULT_SESSION_LOG_MAX,
    val devMode: Boolean = false,
    val splitScreenMode: Boolean = true,
    val obdLinkEnabled: Boolean = false,
    val adapter: Adapter? = null,
    val protocol: BusProtocol? = null,
    val ssmVariant: SsmVariant? = null
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
        devMode = prefs.getBoolean(KEY_DEV_MODE, false),
        splitScreenMode = prefs.getBoolean(KEY_SPLIT_SCREEN, true),
        obdLinkEnabled = prefs.getBoolean(KEY_OBDLINK_ENABLED, false),
        adapter = prefs.getInt(KEY_ADAPTER, -1).takeIf { it >= 0 }?.let { Adapter.values().getOrNull(it) },
        protocol = prefs.getInt(KEY_PROTOCOL, -1).takeIf { it >= 0 }?.let { BusProtocol.values().getOrNull(it) },
        ssmVariant = prefs.getInt(KEY_SSM_VARIANT, -1).takeIf { it >= 0 }?.let { SsmVariant.values().getOrNull(it) }
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putInt(KEY_POLL_INTERVAL, s.pollIntervalMs)
            .putInt(KEY_LOG_MAX, s.sessionLogMaxSize)
            .putBoolean(KEY_DEV_MODE, s.devMode)
            .putBoolean(KEY_SPLIT_SCREEN, s.splitScreenMode)
            .putBoolean(KEY_OBDLINK_ENABLED, s.obdLinkEnabled)
            .putInt(KEY_ADAPTER, s.adapter?.ordinal ?: -1)
            .putInt(KEY_PROTOCOL, s.protocol?.ordinal ?: -1)
            .putInt(KEY_SSM_VARIANT, s.ssmVariant?.ordinal ?: -1)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "protocol_settings"
        private const val KEY_POLL_INTERVAL = "poll_interval_ms"
        private const val KEY_LOG_MAX = "session_log_max"
        private const val KEY_DEV_MODE = "dev_mode"
        private const val KEY_SPLIT_SCREEN = "split_screen_mode"
        private const val KEY_OBDLINK_ENABLED = "obdlink_enabled"
        private const val KEY_ADAPTER = "adapter"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_SSM_VARIANT = "ssm_variant"
    }
}
