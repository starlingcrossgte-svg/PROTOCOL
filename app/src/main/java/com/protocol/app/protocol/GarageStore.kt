package com.protocol.app.protocol

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the user's saved vehicles + the currently-selected vehicle.
 *
 * Storage: SharedPreferences with the vehicles list serialized as JSON.
 * JSON is used (vs the ad-hoc delimited format used by GaugeLayoutStore)
 * because:
 *  - Vehicles will gain optional fields over time (ECU calibration,
 *    supported modules, PID profile id, etc.) — JSON tolerates schema
 *    growth via `optString` reads without a migration step.
 *  - The list is small (hundreds of entries at most) so the encode/decode
 *    cost is negligible compared to polling.
 *  - org.json is shipped with Android — no new dependency.
 *
 * Bad/corrupt JSON yields an empty list rather than crashing; the user
 * sees an empty garage and can re-add. A future migration can attempt
 * recovery from a backup key if needed.
 */
class GarageStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): GarageState {
        val vehiclesJson = prefs.getString(KEY_VEHICLES, null).orEmpty()
        val selectedId = prefs.getString(KEY_SELECTED, null)
        val vehicles = deserialize(vehiclesJson)
        // If the saved selectedId no longer matches any vehicle (e.g.
        // user manually wiped prefs partially), clear it rather than
        // dangling a stale reference.
        val cleanedSelectedId = selectedId?.takeIf { id -> vehicles.any { it.id == id } }
        return GarageState(vehicles = vehicles, selectedVehicleId = cleanedSelectedId)
    }

    fun save(state: GarageState) {
        prefs.edit()
            .putString(KEY_VEHICLES, serialize(state.vehicles))
            .putString(KEY_SELECTED, state.selectedVehicleId)
            .apply()
    }

    private fun serialize(vehicles: List<Vehicle>): String {
        val arr = JSONArray()
        for (v in vehicles) {
            val obj = JSONObject()
            obj.put("id", v.id)
            obj.put("year", v.year)
            obj.put("make", v.make)
            obj.put("model", v.model)
            obj.put("subModel", v.subModel)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun deserialize(json: String): List<Vehicle> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                Vehicle(
                    id = id,
                    year = obj.optString("year"),
                    make = obj.optString("make"),
                    model = obj.optString("model"),
                    subModel = obj.optString("subModel")
                )
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    companion object {
        private const val PREFS_NAME = "protocol_garage"
        private const val KEY_VEHICLES = "vehicles_json"
        private const val KEY_SELECTED = "selected_vehicle_id"
    }
}
