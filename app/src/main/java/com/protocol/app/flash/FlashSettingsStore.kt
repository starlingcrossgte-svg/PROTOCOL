package com.protocol.app.flash

import android.content.Context

/**
 * Persists flash-silo settings in the silo's OWN SharedPreferences file — zero
 * shared code with the logging path's settings. Kept tiny and additive.
 *
 * Phase 0 settings:
 *  - [batteryFloorPercent] — refuse to START a write below this (default 20%).
 *    Not applied to read-only identify; see [FlashSafety].
 *  - [requireAirplaneMode] — when true, a write is gated on airplane mode being
 *    on. Default OFF for development; may become mandatory later, and that
 *    decision is device-tier dependent (not everyone runs a flagship).
 */
class FlashSettingsStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var batteryFloorPercent: Int
        get() = prefs.getInt(KEY_BATTERY_FLOOR, DEFAULT_BATTERY_FLOOR)
        set(value) { prefs.edit().putInt(KEY_BATTERY_FLOOR, value.coerceIn(0, 100)).apply() }

    var requireAirplaneMode: Boolean
        get() = prefs.getBoolean(KEY_REQUIRE_AIRPLANE, DEFAULT_REQUIRE_AIRPLANE)
        set(value) { prefs.edit().putBoolean(KEY_REQUIRE_AIRPLANE, value).apply() }

    companion object {
        const val DEFAULT_BATTERY_FLOOR = 20
        const val DEFAULT_REQUIRE_AIRPLANE = false

        private const val PREFS_NAME = "flash_settings"
        private const val KEY_BATTERY_FLOOR = "battery_floor_percent"
        private const val KEY_REQUIRE_AIRPLANE = "require_airplane_mode"
    }
}
