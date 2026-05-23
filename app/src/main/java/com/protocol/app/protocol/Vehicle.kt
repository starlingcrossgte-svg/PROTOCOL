package com.protocol.app.protocol

/**
 * A vehicle saved in the user's garage. The four user-entered fields are
 * intentionally plain strings (defaulted to uppercase by the UI) so the
 * persistence layer doesn't need to know about year ranges, make/model
 * registries, or sub-model coding schemes — that growth happens upstream
 * if/when PROTOCOL adds vehicle-specific PID profiles, ECU calibration
 * lookups, or flash recipes.
 *
 * The [id] is generated once on creation and used as the stable handle
 * for selection and deletion. Display naming is derived; never stored.
 *
 * Forward-compatibility note: new optional fields (ECU calibration,
 * supported modules, PID profile id, etc.) can be added here without a
 * migration step — [GarageStore] persists via JSON with `optString`
 * reads, so unknown keys round-trip safely and missing keys default
 * cleanly.
 */
data class Vehicle(
    val id: String,
    val year: String,
    val make: String,
    val model: String,
    val subModel: String
) {
    /** Joined display name, blank-component-tolerant: "2006 SUBARU OUTBACK 3.0R". */
    val displayName: String
        get() = listOf(year, make, model, subModel)
            .filter { it.isNotBlank() }
            .joinToString(" ")
}

/**
 * Top-level garage state held in UiState. [selectedVehicleId] is the
 * stable handle to the currently-active vehicle, or null when nothing
 * is selected. Only one vehicle can be selected at a time.
 */
data class GarageState(
    val vehicles: List<Vehicle> = emptyList(),
    val selectedVehicleId: String? = null
) {
    val selectedVehicle: Vehicle?
        get() = vehicles.find { it.id == selectedVehicleId }
}
