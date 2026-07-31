package com.protocol.app.openport2

/**
 * SSM2 parameter model + the built-in parameter set.
 *
 * The definition-derived parameter DATA (per-ECU addresses + conversions for the
 * ECM/TCM) was **removed from source** and is no longer shipped: those
 * definitions were derived from external community logger definitions and don't
 * belong baked into the public app. Parameters are now supplied at runtime from a
 * user-loaded definition file (see `com.protocol.app.defs`) and merged into the
 * live set.
 *
 * The only built-ins that remain are the original computed / multi-input views in
 * [ComputedPids] — the app's own work, which can't be expressed as a single
 * address + conversion and so stay in code.
 *
 * (The previous verified EZ30R table is recoverable from git history if needed to
 * author a definition file.)
 */
enum class Ssm2PidCategory { ECU, TCM }

data class Ssm2Pid(
    val id: String,
    val displayName: String,
    val unit: String,
    val addresses: List<Ssm2Address>,
    val decode: (IntArray) -> Double,
    val longName: String = displayName,
    val category: Ssm2PidCategory = Ssm2PidCategory.ECU,
    /**
     * False = candidate parameter pending on-car validation. Unverified PIDs are
     * shown only on the Unverified parameters page, excluded from the quick
     * presets, and never mixed with the trusted list; they still poll on their
     * [category]'s module. Promote by flipping this to true.
     */
    val verified: Boolean = true
)

object Ssm2Pids {
    /**
     * The built-in parameter set. Definition-derived parameters are no longer
     * shipped — load a definition file to add parameters. Only the original
     * computed parameters remain (see [ComputedPids]); with no definition loaded
     * the app has just those plus whatever the user uploads.
     */
    val DEFAULT_DEMO_PIDS: List<Ssm2Pid> = ComputedPids.COMPUTED_PIDS
}
