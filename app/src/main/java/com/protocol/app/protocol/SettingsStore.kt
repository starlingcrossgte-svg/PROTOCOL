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
    val ssmVariant: SsmVariant? = null,
    val simulatorMode: Boolean = false,
    val simulatorPort: Int = DEFAULT_SIMULATOR_PORT,
    /** SAF tree URI (as String) of the folder CSV logs are saved into by the
     *  Lock-and-Tap auto-save. Null = not chosen yet (auto-save is skipped). */
    val csvFolderUri: String? = null,
    /** Base name for the dev RAW BYTES auto-save file; enumerated on write
     *  ("rawbytes1.csv", "rawbytes2.csv", …). */
    val rawLogName: String = DEFAULT_RAW_LOG_NAME,
    /** Base name for the Live Data session-log auto-save file; enumerated on
     *  write ("session1.csv", "session2.csv", …). */
    val sessionLogName: String = DEFAULT_SESSION_LOG_NAME
) {
    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 200
        const val DEFAULT_SESSION_LOG_MAX = 1000
        const val POLL_INTERVAL_MIN = 100
        const val POLL_INTERVAL_MAX = 500
        const val SESSION_LOG_MIN = 500
        const val SESSION_LOG_MAX = 5000
        const val DEFAULT_SIMULATOR_PORT = 9999
        const val SIMULATOR_PORT_MIN = 1024
        const val SIMULATOR_PORT_MAX = 65535
        const val DEFAULT_RAW_LOG_NAME = "rawbytes"
        const val DEFAULT_SESSION_LOG_NAME = "session"
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
        ssmVariant = prefs.getInt(KEY_SSM_VARIANT, -1).takeIf { it >= 0 }?.let { SsmVariant.values().getOrNull(it) },
        simulatorMode = prefs.getBoolean(KEY_SIMULATOR_MODE, false),
        simulatorPort = prefs.getInt(KEY_SIMULATOR_PORT, AppSettings.DEFAULT_SIMULATOR_PORT),
        csvFolderUri = prefs.getString(KEY_CSV_FOLDER, null),
        rawLogName = prefs.getString(KEY_RAW_LOG_NAME, AppSettings.DEFAULT_RAW_LOG_NAME)
            ?: AppSettings.DEFAULT_RAW_LOG_NAME,
        sessionLogName = prefs.getString(KEY_SESSION_LOG_NAME, AppSettings.DEFAULT_SESSION_LOG_NAME)
            ?: AppSettings.DEFAULT_SESSION_LOG_NAME
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
            .putBoolean(KEY_SIMULATOR_MODE, s.simulatorMode)
            .putInt(KEY_SIMULATOR_PORT, s.simulatorPort)
            .putString(KEY_CSV_FOLDER, s.csvFolderUri)
            .putString(KEY_RAW_LOG_NAME, s.rawLogName)
            .putString(KEY_SESSION_LOG_NAME, s.sessionLogName)
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
        private const val KEY_SIMULATOR_MODE = "simulator_mode"
        private const val KEY_SIMULATOR_PORT = "simulator_port"
        private const val KEY_CSV_FOLDER = "csv_folder_uri"
        private const val KEY_RAW_LOG_NAME = "csv_raw_log_name"
        private const val KEY_SESSION_LOG_NAME = "csv_session_log_name"
    }
}
