package com.protocol.app.protocol

import android.content.Context

// Ft232rl = VAG-KKL raw-K-line cable (FT232RL chip). Appended last so existing
// persisted ordinals (OpenPort=0, OBDLink=1, OBDLinkEx=2) are unchanged. Raw
// K-line transport is fully wired (KklKlineManager / KklKlineSource) — the phone
// is the SSM2 master; selectable from Settings, K-line only.
enum class Adapter { OpenPort, OBDLink, OBDLinkEx, Ft232rl }
// CAN splits into two incompatible cases: CanDiagnostic = ISO-TP request/response
// on 7E0/7E8 (bench / future CAN cars); CanBroadcast = the '06 Outback's listen-only
// powertrain CAN (no diagnostic channel — requests are unsafe there). `CAN` is the
// existing diagnostic value, kept as-is to avoid churn; CanBroadcast is the new one.
enum class BusProtocol { KLine, CAN, CanBroadcast }
enum class SsmVariant { SSM2, SSM3 }

// How Live Data gets data off the bus. Poll = request/response; Stream = one A8 01,
// the ECU streams; Monitor = listen-only decode of a broadcast bus.
enum class PollingMode { Poll, Stream, Monitor }

data class AppSettings(
    val pollIntervalMs: Int = DEFAULT_POLL_INTERVAL_MS,
    val sessionLogMaxSize: Int = DEFAULT_SESSION_LOG_MAX,
    val devMode: Boolean = false,
    val splitScreenMode: Boolean = true,
    val adapter: Adapter? = null,
    val protocol: BusProtocol? = null,
    val ssmVariant: SsmVariant? = null,
    val pollingMode: PollingMode = PollingMode.Poll,
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
    val sessionLogName: String = DEFAULT_SESSION_LOG_NAME,
    /** Height (dp) of the Live Data session-log card. Persisted so a resize
     *  survives an app restart, not just rotation. */
    val sessionLogHeightDp: Float = DEFAULT_SESSION_LOG_HEIGHT_DP,
    /** Index into PidPresets.PRESETS of the last preset loaded onto the Live
     *  Data gauges, or [NO_PRESET] if none. Dev-only. */
    val selectedPresetIndex: Int = NO_PRESET,
    /** Id of the chosen AdapterCommandLibrary sequence (null = library default).
     *  DEV-ONLY: consulted only when connecting from the Dev page CONNECT button.
     *  Live Data always connects with the verified standard init, never this. */
    val selectedInitSequenceId: String? = null,
    /** Live Data's continuous-streaming toggle. When on, the OBDLink K-line live
     *  source STREAMS (A8 01 + STN monitor, ~40 Hz) for ECM-only pages instead of
     *  re-asking each cycle. Independent of [selectedInitSequenceId] — the source
     *  applies the tight K-line timing it needs itself, so this works from the
     *  plain verified init. */
    val klineStreaming: Boolean = false
) {
    companion object {
        const val DEFAULT_POLL_INTERVAL_MS = 200
        const val DEFAULT_SESSION_LOG_MAX = 1000
        const val POLL_INTERVAL_MIN = 10
        const val POLL_INTERVAL_MAX = 500
        const val SESSION_LOG_MIN = 500
        const val SESSION_LOG_MAX = 5000
        const val DEFAULT_SIMULATOR_PORT = 9999
        const val SIMULATOR_PORT_MIN = 1024
        const val SIMULATOR_PORT_MAX = 65535
        const val DEFAULT_RAW_LOG_NAME = "rawbytes"
        const val DEFAULT_SESSION_LOG_NAME = "session"
        const val DEFAULT_SESSION_LOG_HEIGHT_DP = 300f
        const val SESSION_LOG_HEIGHT_MIN = 120f
        const val SESSION_LOG_HEIGHT_MAX = 800f
        const val NO_PRESET = -1
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
        adapter = prefs.getInt(KEY_ADAPTER, -1).takeIf { it >= 0 }?.let { Adapter.values().getOrNull(it) },
        protocol = prefs.getInt(KEY_PROTOCOL, -1).takeIf { it >= 0 }?.let { BusProtocol.values().getOrNull(it) },
        ssmVariant = prefs.getInt(KEY_SSM_VARIANT, -1).takeIf { it >= 0 }?.let { SsmVariant.values().getOrNull(it) },
        pollingMode = PollingMode.values().getOrNull(prefs.getInt(KEY_POLLING_MODE, 0)) ?: PollingMode.Poll,
        simulatorMode = prefs.getBoolean(KEY_SIMULATOR_MODE, false),
        simulatorPort = prefs.getInt(KEY_SIMULATOR_PORT, AppSettings.DEFAULT_SIMULATOR_PORT),
        csvFolderUri = prefs.getString(KEY_CSV_FOLDER, null),
        rawLogName = prefs.getString(KEY_RAW_LOG_NAME, AppSettings.DEFAULT_RAW_LOG_NAME)
            ?: AppSettings.DEFAULT_RAW_LOG_NAME,
        sessionLogName = prefs.getString(KEY_SESSION_LOG_NAME, AppSettings.DEFAULT_SESSION_LOG_NAME)
            ?: AppSettings.DEFAULT_SESSION_LOG_NAME,
        sessionLogHeightDp = prefs.getFloat(KEY_SESSION_LOG_HEIGHT, AppSettings.DEFAULT_SESSION_LOG_HEIGHT_DP),
        selectedPresetIndex = prefs.getInt(KEY_SELECTED_PRESET, AppSettings.NO_PRESET),
        selectedInitSequenceId = prefs.getString(KEY_INIT_SEQ, null),
        klineStreaming = prefs.getBoolean(KEY_KLINE_STREAMING, false)
    )

    fun save(s: AppSettings) {
        prefs.edit()
            .putInt(KEY_POLL_INTERVAL, s.pollIntervalMs)
            .putInt(KEY_LOG_MAX, s.sessionLogMaxSize)
            .putBoolean(KEY_DEV_MODE, s.devMode)
            .putBoolean(KEY_SPLIT_SCREEN, s.splitScreenMode)
            .putInt(KEY_ADAPTER, s.adapter?.ordinal ?: -1)
            .putInt(KEY_PROTOCOL, s.protocol?.ordinal ?: -1)
            .putInt(KEY_SSM_VARIANT, s.ssmVariant?.ordinal ?: -1)
            .putInt(KEY_POLLING_MODE, s.pollingMode.ordinal)
            .putBoolean(KEY_SIMULATOR_MODE, s.simulatorMode)
            .putInt(KEY_SIMULATOR_PORT, s.simulatorPort)
            .putString(KEY_CSV_FOLDER, s.csvFolderUri)
            .putString(KEY_RAW_LOG_NAME, s.rawLogName)
            .putString(KEY_SESSION_LOG_NAME, s.sessionLogName)
            .putFloat(KEY_SESSION_LOG_HEIGHT, s.sessionLogHeightDp)
            .putInt(KEY_SELECTED_PRESET, s.selectedPresetIndex)
            .putString(KEY_INIT_SEQ, s.selectedInitSequenceId)
            .putBoolean(KEY_KLINE_STREAMING, s.klineStreaming)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "protocol_settings"
        private const val KEY_POLL_INTERVAL = "poll_interval_ms"
        private const val KEY_LOG_MAX = "session_log_max"
        private const val KEY_DEV_MODE = "dev_mode"
        private const val KEY_SPLIT_SCREEN = "split_screen_mode"
        private const val KEY_ADAPTER = "adapter"
        private const val KEY_PROTOCOL = "protocol"
        private const val KEY_SSM_VARIANT = "ssm_variant"
        private const val KEY_POLLING_MODE = "polling_mode"
        private const val KEY_SIMULATOR_MODE = "simulator_mode"
        private const val KEY_SIMULATOR_PORT = "simulator_port"
        private const val KEY_CSV_FOLDER = "csv_folder_uri"
        private const val KEY_RAW_LOG_NAME = "csv_raw_log_name"
        private const val KEY_SESSION_LOG_NAME = "csv_session_log_name"
        private const val KEY_SESSION_LOG_HEIGHT = "session_log_height_dp"
        private const val KEY_SELECTED_PRESET = "selected_preset_index"
        private const val KEY_INIT_SEQ = "selected_init_sequence_id"
        private const val KEY_KLINE_STREAMING = "kline_streaming"
    }
}
