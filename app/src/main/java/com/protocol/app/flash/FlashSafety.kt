package com.protocol.app.flash

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.system.Os
import android.system.OsConstants
import java.io.File

/**
 * Reads live device condition for the flash silo and provides the pre-flight
 * safety gate.
 *
 * Two responsibilities, kept here because they read the same device state:
 *  1. [readHealth] — a cheap, pull-based snapshot (battery %, charging, current
 *     draw in mA, thermal status, airplane mode, own-process CPU%, memory).
 *     Doubles as the live diagnostics readout. All reads are light enough to
 *     poll at ~1 Hz even on the weaker S23 Ultra; the caller picks the cadence.
 *  2. [gateForWrite] — the yes/no check that guards WRITE operations later.
 *     Deliberately NOT applied to read-only identify, which stays freely
 *     clickable with status merely displayed.
 *
 * Pass an application context to avoid leaks across the flash session.
 */
class FlashSafety(
    private val context: Context,
    private val settings: FlashSettingsStore
) {

    private val batteryManager =
        context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val powerManager =
        context.getSystemService(Context.POWER_SERVICE) as PowerManager
    private val activityManager =
        context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private val clkTck: Long = try {
        Os.sysconf(OsConstants._SC_CLK_TCK)
    } catch (_: Exception) {
        100L
    }

    // Previous CPU sample; CPU% needs two reads to diff over wall-clock time.
    private data class CpuSample(val jiffies: Long, val wallMs: Long)
    private var lastCpuSample: CpuSample? = null

    data class DeviceHealth(
        val batteryPercent: Int,        // -1 if unknown
        val charging: Boolean,
        val currentMilliAmps: Int,      // signed; on most devices negative = discharging. 0 if unsupported.
        val thermalStatus: String,      // "NONE".."SHUTDOWN", or "n/a" below API 29
        val airplaneMode: Boolean,
        val cpuPercent: Double?,        // own-process CPU since the last read (can exceed 100 across cores); null on first read
        val appUsedMb: Long,            // app heap used (approx)
        val systemAvailMb: Long         // system available memory
    )

    sealed class GateResult {
        object Allowed : GateResult()
        data class Blocked(val reason: String) : GateResult()
    }

    /** Cheap snapshot of current device condition. Safe to call ~1 Hz. */
    fun readHealth(): DeviceHealth {
        val batteryIntent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL

        val currentUa = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        val currentMilliAmps = if (currentUa == Int.MIN_VALUE) 0 else currentUa / 1000

        val airplaneMode = Settings.Global.getInt(
            context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0
        ) != 0

        val cpuPercent = readCpuPercent()

        val rt = Runtime.getRuntime()
        val appUsedMb = (rt.totalMemory() - rt.freeMemory()) / BYTES_PER_MB
        val mi = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(mi)
        val systemAvailMb = mi.availMem / BYTES_PER_MB

        return DeviceHealth(
            batteryPercent = batteryPercent,
            charging = charging,
            currentMilliAmps = currentMilliAmps,
            thermalStatus = thermalStatusString(),
            airplaneMode = airplaneMode,
            cpuPercent = cpuPercent,
            appUsedMb = appUsedMb,
            systemAvailMb = systemAvailMb
        )
    }

    /**
     * The pre-flight gate for WRITE operations. Reserved for the write phases —
     * identify never calls this.
     */
    fun gateForWrite(health: DeviceHealth = readHealth()): GateResult {
        if (health.batteryPercent in 0 until settings.batteryFloorPercent) {
            return GateResult.Blocked(
                "Battery ${health.batteryPercent}% is below the ${settings.batteryFloorPercent}% floor"
            )
        }
        if (settings.requireAirplaneMode && !health.airplaneMode) {
            return GateResult.Blocked("Airplane mode is required — enable it before flashing")
        }
        if (isThermalCritical(health.thermalStatus)) {
            return GateResult.Blocked("Device thermal state is ${health.thermalStatus} — too hot to flash safely")
        }
        return GateResult.Allowed
    }

    // ---- internals ----

    private fun thermalStatusString(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "n/a"
        return when (powerManager.currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> "NONE"
            PowerManager.THERMAL_STATUS_LIGHT -> "LIGHT"
            PowerManager.THERMAL_STATUS_MODERATE -> "MODERATE"
            PowerManager.THERMAL_STATUS_SEVERE -> "SEVERE"
            PowerManager.THERMAL_STATUS_CRITICAL -> "CRITICAL"
            PowerManager.THERMAL_STATUS_EMERGENCY -> "EMERGENCY"
            PowerManager.THERMAL_STATUS_SHUTDOWN -> "SHUTDOWN"
            else -> "UNKNOWN"
        }
    }

    private fun isThermalCritical(status: String): Boolean =
        status == "SEVERE" || status == "CRITICAL" || status == "EMERGENCY" || status == "SHUTDOWN"

    /** Own-process CPU% since the previous call, from /proc/self/stat. Null on the first read. */
    private fun readCpuPercent(): Double? {
        val jiffies = readProcessJiffies() ?: return null
        val nowMs = System.currentTimeMillis()
        val prev = lastCpuSample
        lastCpuSample = CpuSample(jiffies, nowMs)
        if (prev == null || nowMs <= prev.wallMs) return null
        val deltaJiffies = (jiffies - prev.jiffies).coerceAtLeast(0)
        val cpuMs = deltaJiffies * 1000.0 / clkTck
        return cpuMs / (nowMs - prev.wallMs) * 100.0
    }

    /** Sum of utime+stime (clock ticks) for our own process. */
    private fun readProcessJiffies(): Long? = try {
        val stat = File("/proc/self/stat").readText()
        // The comm field is wrapped in parens and may contain spaces/parens, so
        // start parsing after the last ')'. The first token after it is 'state'
        // (field 3); utime is field 14 (index 11), stime field 15 (index 12).
        val after = stat.substring(stat.lastIndexOf(')') + 1).trim()
        val parts = after.split(Regex("\\s+"))
        parts[11].toLong() + parts[12].toLong()
    } catch (_: Exception) {
        null
    }

    private companion object {
        private const val BYTES_PER_MB = 1024L * 1024L
    }
}
