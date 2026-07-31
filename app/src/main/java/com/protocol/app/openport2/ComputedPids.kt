package com.protocol.app.openport2

/**
 * Computed / multi-input parameters — original, in-app derived views (the
 * calculated engine load plus the P201–P203 set). Unlike the direct parameters,
 * these read several addresses in one A8 query and combine them (division,
 * subtraction across separate byte groups), so they can't be expressed as a
 * single address + conversion expression and therefore stay in code rather than
 * moving to an uploaded definition file.
 *
 * [COMPUTED_PIDS] is merged into the live parameter set alongside the direct
 * parameters; the entries keep their original ids so saved layouts, presets and
 * logs are unaffected.
 */
object ComputedPids {

    // Engine Load (Calculated) = MAF (g/s) * 120 / RPM = grams of air per
    // engine cycle (2 revolutions). Reads RPM and MAF addresses again
    // alongside MASS_AIRFLOW/RPM — matches how the calculated load works
    // (sees the addresses twice in the A8 query).
    val ENGINE_LOAD_CALC = Ssm2Pid(
        id = "load",
        displayName = "Eng Load",
        unit = "g/rev",
        addresses = listOf(
            Ssm2Address(0x00, 0x00, 0x0E),
            Ssm2Address(0x00, 0x00, 0x0F),
            Ssm2Address(0x00, 0x00, 0x13),
            Ssm2Address(0x00, 0x00, 0x14)
        ),
        decode = { raw ->
            if (raw.size < 4) 0.0 else {
                // g/rev = MAF (g/s) * 60 (s/min) / RPM (rev/min).
                // Previous formula used *120, which actually computes g/cycle
                // (= g/2 revs on a 4-stroke). Reported value was 2× the
                // reference at idle (0.90 vs 0.41 g/rev). Verified 2026-05-23
                // by side-by-side comparison.
                val rpm = ((raw[0] shl 8) or raw[1]) * 0.25
                val maf = ((raw[2] shl 8) or raw[3]) * 0.01
                if (rpm > 0.0) maf * 60.0 / rpm else 0.0
            }
        },
        longName = "Engine Load (Calculated)"
    )

    // P201 Injector Duty Cycle = (RPM × Inj1_PW_ms) / 1200
    // Reads RPM hi/lo + Inj1 PW.
    val INJECTOR_DUTY_CYCLE = Ssm2Pid(
        id = "idc",
        displayName = "Inj Duty",
        unit = "%",
        addresses = listOf(
            Ssm2Address(0x00, 0x00, 0x0E),
            Ssm2Address(0x00, 0x00, 0x0F),
            Ssm2Address(0x00, 0x00, 0x20)
        ),
        decode = { raw ->
            if (raw.size < 3) 0.0 else {
                val rpm = ((raw[0] shl 8) or raw[1]) * 0.25
                val pwMs = raw[2] * 256.0 / 1000.0
                if (rpm > 0.0) (rpm * pwMs) / 1200.0 else 0.0
            }
        },
        longName = "Injector Duty Cycle (Calculated)"
    )

    // P202 Manifold Relative Pressure (Corrected) = MAP_psi - Atm_psi.
    // Reads MAP + Atm.
    val MRP_CORRECTED = Ssm2Pid(
        id = "mrp_corr",
        displayName = "Manif RelC",
        unit = "psi",
        addresses = listOf(
            Ssm2Address(0x00, 0x00, 0x0D),
            Ssm2Address(0x00, 0x00, 0x23)
        ),
        decode = { raw ->
            if (raw.size < 2) 0.0
            else (raw[0] - raw[1]) * 37.0 / 255.0
        },
        longName = "Manifold Relative Pressure (Corrected)"
    )

    // P203 Fuel Consumption (mpg US) = (VS_mph × AFR) / (1.25 × MAF).
    // Reads Vehicle Speed + MAF hi/lo + A/F Sensor #1.
    val FUEL_CONSUMPTION = Ssm2Pid(
        id = "mpg",
        displayName = "MPG Est",
        unit = "mpg",
        addresses = listOf(
            Ssm2Address(0x00, 0x00, 0x10),
            Ssm2Address(0x00, 0x00, 0x13),
            Ssm2Address(0x00, 0x00, 0x14),
            Ssm2Address(0x00, 0x00, 0x46)
        ),
        decode = { raw ->
            if (raw.size < 4) 0.0 else {
                val vsMph = raw[0] * 0.621371192
                val maf = ((raw[1] shl 8) or raw[2]) * 0.01
                val afr = raw[3] * 14.7 / 128.0
                if (maf > 0.001) vsMph * afr / (1.25 * maf) else 0.0
            }
        },
        longName = "Fuel Consumption (Estimated)"
    )

    /** All computed parameters, merged into the live set alongside the direct
     *  (definition-driven) parameters. */
    val COMPUTED_PIDS = listOf(
        ENGINE_LOAD_CALC,
        INJECTOR_DUTY_CYCLE,
        MRP_CORRECTED,
        FUEL_CONSUMPTION
    )
}
