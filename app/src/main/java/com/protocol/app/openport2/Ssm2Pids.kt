package com.protocol.app.openport2

/**
 * SSM2 parameter (PID) definitions for the 2006 USDM Subaru EZ30R H6.
 *
 * Address sources:
 *  - 0x00001C (battery voltage) and 0x00000C (coolant temp) are
 *    CONFIRMED from the earlier capture: 12.4 V battery and 89 °C
 *    coolant decoded correctly on a warmed engine at rest.
 *  - 0x00000E,0F (RPM) and 0x000013,14 (MAF) are CONFIRMED from the
 *    new multi-PID capture: decoded values 927 RPM and 6.39 g/s
 *    match an idling engine.
 *  - 0xFF2578-7B is IAM (4-byte float multiplier, ~0.41 on this ECU)
 *    and 0xFF8168-6B is Feedback Knock Correction (4-byte float, degrees,
 *    swings -3..+3° under load). The two were initially mapped the other
 *    way around; on-car logs proved them flipped (FB KC sat constant at
 *    0.41 while IAM tracked transient knock corrections), so this file
 *    now uses the corrected mapping.
 *  - 0xFF8184-87 is Fine Learning Knock Correction (4-byte float, ° —
 *    confirmed on-car: mostly 0.0, dropped to -3.15° on heavy load).
 *  - Standard SSM2 single-byte addresses (0x09, 0x0A, 0x11, 0x12, 0x46,
 *    0x113) follow the Subaru community mappings and produce sensible
 *    values on this ECU.
 */
enum class Ssm2PidCategory { ECU, TCM }

data class Ssm2Pid(
    val id: String,
    val displayName: String,
    val unit: String,
    val addresses: List<Ssm2Address>,
    val decode: (IntArray) -> Double,
    val longName: String = displayName,
    val category: Ssm2PidCategory = Ssm2PidCategory.ECU
)

object Ssm2Pids {

    val RPM = Ssm2Pid(
        id = "rpm",
        displayName = "RPM",
        unit = "rpm",
        addresses = listOf(
            Ssm2Address(0x00, 0x00, 0x0E),
            Ssm2Address(0x00, 0x00, 0x0F)
        ),
        decode = { raw ->
            if (raw.size < 2) 0.0
            else ((raw[0] shl 8) or raw[1]) * 0.25
        },
        longName = "Engine Speed"
    )

    val COOLANT_TEMP = Ssm2Pid(
        id = "coolant",
        displayName = "Coolant",
        unit = "°F",
        // Address corrected 2026-05-23: previous value 0x00000C reads A/F
        // Learning #2 (P6), not coolant temp — it sat near 128 in
        // closed-loop and our F formula gave a constant ~190 °F. Coolant
        // Temperature is P2 at 0x000008, conversion (x-40) in °C →
        // (x-40)*9/5+32 °F.
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x08)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0].toDouble() - 40.0) * 9.0 / 5.0 + 32.0 },
        longName = "Coolant Temperature"
    )

    val BATTERY_VOLTAGE = Ssm2Pid(
        id = "battery",
        displayName = "Battery",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x1C)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 0.08 },
        longName = "Battery Voltage"
    )

    val AF_CORRECTION_1 = Ssm2Pid(
        id = "afc1",
        displayName = "A/F Corr",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x09)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] - 128) * 100.0 / 128.0 },
        longName = "A/F Correction #1"
    )

    val AF_LEARNING_1 = Ssm2Pid(
        id = "afl1",
        displayName = "A/F Learn",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x0A)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] - 128) * 100.0 / 128.0 },
        longName = "A/F Learning #1"
    )

    val AF_SENSOR_1 = Ssm2Pid(
        id = "afs1",
        displayName = "A/F Sensor",
        unit = "AFR",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x46)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 14.7 / 128.0 },
        longName = "A/F Sensor #1"
    )

    val IGNITION_TIMING = Ssm2Pid(
        id = "ign",
        displayName = "Ign Total",
        unit = "°",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x11)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] - 128) / 2.0 },
        longName = "Ignition Total Timing"
    )

    val MASS_AIRFLOW = Ssm2Pid(
        id = "maf",
        displayName = "MAF",
        unit = "g/s",
        addresses = listOf(
            Ssm2Address(0x00, 0x00, 0x13),
            Ssm2Address(0x00, 0x00, 0x14)
        ),
        decode = { raw ->
            if (raw.size < 2) 0.0
            else ((raw[0] shl 8) or raw[1]) * 0.01
        },
        longName = "Mass Airflow"
    )

    val OIL_TEMP = Ssm2Pid(
        id = "oil",
        displayName = "Oil Temp",
        unit = "°F",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x13)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0].toDouble() - 40.0) * 9.0 / 5.0 + 32.0 },
        longName = "Oil Temperature"
    )

    val INTAKE_AIR_TEMP = Ssm2Pid(
        id = "iat",
        displayName = "IAT",
        unit = "°F",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x12)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0].toDouble() - 40.0) * 9.0 / 5.0 + 32.0 },
        longName = "Intake Air Temperature"
    )

    val FEEDBACK_KNOCK_CORRECTION = Ssm2Pid(
        id = "fbkc",
        displayName = "FB KC",
        unit = "°",
        addresses = listOf(
            Ssm2Address(0xFF.toByte(), 0x81.toByte(), 0x68.toByte()),
            Ssm2Address(0xFF.toByte(), 0x81.toByte(), 0x69.toByte()),
            Ssm2Address(0xFF.toByte(), 0x81.toByte(), 0x6A.toByte()),
            Ssm2Address(0xFF.toByte(), 0x81.toByte(), 0x6B.toByte())
        ),
        decode = { raw -> decodeFloatBE(raw) },
        longName = "Feedback Knock Correction"
    )

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

    val FINE_LEARNING_KC = Ssm2Pid(
        id = "flkc",
        displayName = "FL KC",
        unit = "°",
        addresses = listOf(
            Ssm2Address(0xFF.toByte(), 0x81.toByte(), 0x84.toByte()),
            Ssm2Address(0xFF.toByte(), 0x81.toByte(), 0x85.toByte()),
            Ssm2Address(0xFF.toByte(), 0x81.toByte(), 0x86.toByte()),
            Ssm2Address(0xFF.toByte(), 0x81.toByte(), 0x87.toByte())
        ),
        decode = { raw -> decodeFloatBE(raw) },
        longName = "Fine Learning Knock Correction"
    )

    val IAM = Ssm2Pid(
        id = "iam",
        displayName = "IAM",
        unit = "x",
        // Ignition Advance Multiplier: standard single-byte parameter at
        // 0x0000F9, value = raw / 16 (16/16 = 1.0 = full advance on a healthy
        // engine). Previously pointed at the 0xFF2578 4-byte RAM float — that
        // was a wrong-ECU (2.5L turbo) address and read a bogus ~0.41 on this
        // EZ30R while the engine was perfectly healthy.
        addresses = listOf(Ssm2Address(0x00, 0x00, 0xF9.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 16.0 },
        longName = "IAM (Ignition Advance Multiplier)"
    )

    val VEHICLE_SPEED = Ssm2Pid(
        id = "speed",
        displayName = "Speed",
        unit = "mph",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x10)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 0.621371192 },
        longName = "Vehicle Speed"
    )

    val KNOCK_CORRECTION_ADVANCE = Ssm2Pid(
        id = "kca",
        displayName = "Knock Adv",
        unit = "°",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x22)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] - 128) / 2.0 },
        longName = "Knock Correction Advance"
    )

    // ───────────── ECM additions (P-codes) ─────────────
    // Where a parameter offers both metric and imperial units, the
    // imperial conversion is used (°F, psi, mph). Multi-byte addresses
    // are listed in MSB → LSB order.

    val ENGINE_LOAD_RELATIVE = Ssm2Pid(
        id = "load_rel",
        displayName = "Load Rel",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x07)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 100.0 / 255.0 },
        longName = "Engine Load (Relative)"
    )

    val AF_CORRECTION_2 = Ssm2Pid(
        id = "afc2",
        displayName = "A/F Corr2",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x0B)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] - 128) * 100.0 / 128.0 },
        longName = "A/F Correction #2"
    )

    val AF_LEARNING_2 = Ssm2Pid(
        id = "afl2",
        displayName = "A/F Learn2",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x0C)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] - 128) * 100.0 / 128.0 },
        longName = "A/F Learning #2"
    )

    val MAP = Ssm2Pid(
        id = "map",
        displayName = "MAP",
        unit = "psi",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x0D)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 37.0 / 255.0 },
        longName = "Manifold Absolute Pressure"
    )

    val THROTTLE_OPENING_ANGLE = Ssm2Pid(
        id = "throttle",
        displayName = "Throttle",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x15)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 100.0 / 255.0 },
        longName = "Throttle Opening Angle"
    )

    val FRONT_O2_1 = Ssm2Pid(
        id = "fo2_1",
        displayName = "F O2 #1",
        unit = "V",
        addresses = listOf(
            Ssm2Address(0x00, 0x00, 0x16),
            Ssm2Address(0x00, 0x00, 0x17)
        ),
        decode = { raw ->
            if (raw.size < 2) 0.0
            else ((raw[0] shl 8) or raw[1]) / 200.0
        },
        longName = "Front O2 Sensor #1"
    )

    val REAR_O2 = Ssm2Pid(
        id = "ro2",
        displayName = "Rear O2",
        unit = "V",
        addresses = listOf(
            Ssm2Address(0x00, 0x00, 0x18),
            Ssm2Address(0x00, 0x00, 0x19)
        ),
        decode = { raw ->
            if (raw.size < 2) 0.0
            else ((raw[0] shl 8) or raw[1]) / 200.0
        },
        longName = "Rear O2 Sensor"
    )

    val FRONT_O2_2 = Ssm2Pid(
        id = "fo2_2",
        displayName = "F O2 #2",
        unit = "V",
        addresses = listOf(
            Ssm2Address(0x00, 0x00, 0x1A),
            Ssm2Address(0x00, 0x00, 0x1B)
        ),
        decode = { raw ->
            if (raw.size < 2) 0.0
            else ((raw[0] shl 8) or raw[1]) / 200.0
        },
        longName = "Front O2 Sensor #2"
    )

    val MAF_VOLTAGE = Ssm2Pid(
        id = "maf_v",
        displayName = "MAF V",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x1D)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 50.0 },
        longName = "MAF Sensor Voltage"
    )

    val TPS_VOLTAGE = Ssm2Pid(
        id = "tps_v",
        displayName = "TPS V",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x1E)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 50.0 },
        longName = "Throttle Sensor Voltage"
    )

    val INJ1_PULSE_WIDTH = Ssm2Pid(
        id = "inj1_pw",
        displayName = "Inj1 PW",
        unit = "ms",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x20)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 256.0 / 1000.0 },
        longName = "Fuel Injector #1 Pulse Width"
    )

    val INJ2_PULSE_WIDTH = Ssm2Pid(
        id = "inj2_pw",
        displayName = "Inj2 PW",
        unit = "ms",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x21)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 256.0 / 1000.0 },
        longName = "Fuel Injector #2 Pulse Width"
    )

    val ATMOSPHERIC_PRESSURE = Ssm2Pid(
        id = "atm",
        displayName = "Atm",
        unit = "psi",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x23)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 37.0 / 255.0 },
        longName = "Atmospheric Pressure"
    )

    val MANIFOLD_RELATIVE_PRESSURE = Ssm2Pid(
        id = "mrp",
        displayName = "Manif Rel",
        unit = "psi",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x24)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] - 128) * 37.0 / 255.0 },
        longName = "Manifold Relative Pressure"
    )

    val LEARNED_IGNITION_TIMING = Ssm2Pid(
        id = "learn_ign",
        displayName = "Learn Ign",
        unit = "°",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x28)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] - 128) / 2.0 },
        longName = "Learned Ignition Timing"
    )

    val FUEL_TEMPERATURE = Ssm2Pid(
        id = "fuel_t",
        displayName = "Fuel T",
        unit = "°F",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x2A)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0].toDouble() - 40.0) * 9.0 / 5.0 + 32.0 },
        longName = "Fuel Temperature"
    )

    val RADIATOR_FAN_CONTROL = Ssm2Pid(
        id = "fan_ctrl",
        displayName = "Fan Ctrl",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x2F)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0].toDouble() },
        longName = "Radiator Fan Control"
    )

    val CPC_VALVE_DUTY = Ssm2Pid(
        id = "cpc_duty",
        displayName = "CPC Duty",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x32)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 100.0 / 255.0 },
        longName = "Canister Purge Control Valve Duty"
    )

    val TUMBLE_VALVE_R = Ssm2Pid(
        id = "tumble_r",
        displayName = "Tumble R",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x33)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 50.0 },
        longName = "Tumble Valve Position Sensor Right"
    )

    val TUMBLE_VALVE_L = Ssm2Pid(
        id = "tumble_l",
        displayName = "Tumble L",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x34)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 50.0 },
        longName = "Tumble Valve Position Sensor Left"
    )

    val ISC_VALVE_DUTY = Ssm2Pid(
        id = "iscv_duty",
        displayName = "ISCV Duty",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x35)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 2.0 },
        longName = "Idle Speed Control Valve Duty Ratio"
    )

    val AF_LEAN_CORRECTION = Ssm2Pid(
        id = "af_lean",
        displayName = "A/F Lean",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x36)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 100.0 / 255.0 },
        longName = "A/F Lean Correction"
    )

    val AF_HEATER_DUTY = Ssm2Pid(
        id = "af_heater",
        displayName = "A/F Htr",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x37)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 100.0 / 255.0 },
        longName = "A/F Heater Duty"
    )

    val ISC_VALVE_STEP = Ssm2Pid(
        id = "iscv_step",
        displayName = "ISCV Step",
        unit = "steps",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x38)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0].toDouble() },
        longName = "Idle Speed Control Valve Step"
    )

    val ALTERNATOR_DUTY = Ssm2Pid(
        id = "alt_duty",
        displayName = "Alt Duty",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x3A)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0].toDouble() },
        longName = "Alternator Duty"
    )

    val FUEL_PUMP_DUTY = Ssm2Pid(
        id = "fp_duty",
        displayName = "FP Duty",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x3B)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 100.0 / 255.0 },
        longName = "Fuel Pump Duty"
    )

    val AVCS_INTAKE_R = Ssm2Pid(
        id = "avcs_r",
        displayName = "AVCS R",
        unit = "°",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x3C)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] - 50).toDouble() },
        longName = "Intake VVT Advance Angle Right"
    )

    val AVCS_INTAKE_L = Ssm2Pid(
        id = "avcs_l",
        displayName = "AVCS L",
        unit = "°",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x3D)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] - 50).toDouble() },
        longName = "Intake VVT Advance Angle Left"
    )

    val OCV_DUTY_R = Ssm2Pid(
        id = "ocv_dr",
        displayName = "OCV D-R",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x3E)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 100.0 / 255.0 },
        longName = "Intake OCV Duty Right"
    )

    val OCV_DUTY_L = Ssm2Pid(
        id = "ocv_dl",
        displayName = "OCV D-L",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x3F)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 100.0 / 255.0 },
        longName = "Intake OCV Duty Left"
    )

    val OCV_CURRENT_R = Ssm2Pid(
        id = "ocv_cr",
        displayName = "OCV I-R",
        unit = "mA",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x40)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 32.0 },
        longName = "Intake OCV Current Right"
    )

    val OCV_CURRENT_L = Ssm2Pid(
        id = "ocv_cl",
        displayName = "OCV I-L",
        unit = "mA",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x41)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 32.0 },
        longName = "Intake OCV Current Left"
    )

    val AFS1_CURRENT = Ssm2Pid(
        id = "afs1_curr",
        displayName = "AFS1 I",
        unit = "mA",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x42)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] - 128) / 8.0 },
        longName = "A/F Sensor #1 Current"
    )

    val AFS2_CURRENT = Ssm2Pid(
        id = "afs2_curr",
        displayName = "AFS2 I",
        unit = "mA",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x43)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] - 128) / 8.0 },
        longName = "A/F Sensor #2 Current"
    )

    val AFS1_RESISTANCE = Ssm2Pid(
        id = "afs1_res",
        displayName = "AFS1 R",
        unit = "Ω",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x44)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0].toDouble() },
        longName = "A/F Sensor #1 Resistance"
    )

    val AFS2_RESISTANCE = Ssm2Pid(
        id = "afs2_res",
        displayName = "AFS2 R",
        unit = "Ω",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x45)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0].toDouble() },
        longName = "A/F Sensor #2 Resistance"
    )

    val AF_SENSOR_2 = Ssm2Pid(
        id = "afs2",
        displayName = "A/F Sens2",
        unit = "AFR",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x47)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 14.7 / 128.0 },
        longName = "A/F Sensor #2"
    )

    val AFS1_HEATER_CURRENT = Ssm2Pid(
        id = "afs1_htr",
        displayName = "AFS1 Htr",
        unit = "A",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x53)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 10.0 },
        longName = "A/F Sensor #1 Heater Current"
    )

    val AFS2_HEATER_CURRENT = Ssm2Pid(
        id = "afs2_htr",
        displayName = "AFS2 Htr",
        unit = "A",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x54)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 10.0 },
        longName = "A/F Sensor #2 Heater Current"
    )

    val CYL1_ROUGHNESS = Ssm2Pid(
        id = "cyl1_rough",
        displayName = "C1 Rough",
        unit = "ct",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0xCE.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0].toDouble() },
        longName = "Roughness Monitor Cylinder #1"
    )

    val CYL2_ROUGHNESS = Ssm2Pid(
        id = "cyl2_rough",
        displayName = "C2 Rough",
        unit = "ct",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0xCF.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0].toDouble() },
        longName = "Roughness Monitor Cylinder #2"
    )

    val CYL3_ROUGHNESS = Ssm2Pid(
        id = "cyl3_rough",
        displayName = "C3 Rough",
        unit = "ct",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0xD8.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0].toDouble() },
        longName = "Roughness Monitor Cylinder #3"
    )

    val CYL4_ROUGHNESS = Ssm2Pid(
        id = "cyl4_rough",
        displayName = "C4 Rough",
        unit = "ct",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0xD9.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0].toDouble() },
        longName = "Roughness Monitor Cylinder #4"
    )

    val CYL5_ROUGHNESS = Ssm2Pid(
        id = "cyl5_rough",
        displayName = "C5 Rough",
        unit = "ct",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0xEF.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0].toDouble() },
        longName = "Roughness Monitor Cylinder #5"
    )

    val CYL6_ROUGHNESS = Ssm2Pid(
        id = "cyl6_rough",
        displayName = "C6 Rough",
        unit = "ct",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0xF8.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0].toDouble() },
        longName = "Roughness Monitor Cylinder #6"
    )

    val THROTTLE_MOTOR_DUTY = Ssm2Pid(
        id = "tm_duty",
        displayName = "TM Duty",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0xFA.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] - 128) * 100.0 / 128.0 },
        longName = "Throttle Motor Duty"
    )

    val THROTTLE_MOTOR_VOLTAGE = Ssm2Pid(
        id = "tm_v",
        displayName = "TM V",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0xFB.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 0.08 },
        longName = "Throttle Motor Voltage"
    )

    val TPS_SUB = Ssm2Pid(
        id = "tps_sub",
        displayName = "TPS Sub",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x00)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 50.0 },
        longName = "Sub Throttle Sensor"
    )

    val TPS_MAIN = Ssm2Pid(
        id = "tps_main",
        displayName = "TPS Main",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x01)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 50.0 },
        longName = "Main Throttle Sensor"
    )

    val PEDAL_SUB = Ssm2Pid(
        id = "pedal_sub",
        displayName = "Pedal Sub",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x02)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 50.0 },
        longName = "Sub Accelerator Sensor"
    )

    val PEDAL_MAIN = Ssm2Pid(
        id = "pedal_main",
        displayName = "Pedal Main",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x03)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 50.0 },
        longName = "Main Accelerator Sensor"
    )

    val EGT_1 = Ssm2Pid(
        id = "egt",
        displayName = "EGT",
        unit = "°F",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x06)),
        // °F expr = 32 + 9*(x+40) — direct conversion, no /5.
        decode = { raw -> if (raw.isEmpty()) 0.0 else 32.0 + 9.0 * (raw[0] + 40) },
        longName = "Exhaust Gas Temperature"
    )

    val EGT_2 = Ssm2Pid(
        id = "egt2",
        displayName = "EGT 2",
        unit = "°F",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x07)),
        // °F expr = 32 + 9*(x*5+200)/5
        decode = { raw -> if (raw.isEmpty()) 0.0 else 32.0 + 9.0 * (raw[0] * 5.0 + 200.0) / 5.0 },
        longName = "Exhaust Gas Temperature 2"
    )

    val ODOMETER = Ssm2Pid(
        id = "odom",
        displayName = "Odometer",
        unit = "mi",
        addresses = listOf(
            Ssm2Address(0x00, 0x01, 0x0E),
            Ssm2Address(0x00, 0x01, 0x0F)
        ),
        decode = { raw ->
            if (raw.size < 2) 0.0
            else ((raw[0] shl 8) or raw[1]) * 1.242742384
        },
        longName = "Estimated Odometer"
    )

    val OSV_DUTY_R = Ssm2Pid(
        id = "osv_dr",
        displayName = "OSV D-R",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x14)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 255.0 * 100.0 },
        longName = "Oil Switching Solenoid Valve Duty (Right)"
    )

    val OSV_DUTY_L = Ssm2Pid(
        id = "osv_dl",
        displayName = "OSV D-L",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x15)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 255.0 * 100.0 },
        longName = "Oil Switching Solenoid Valve Duty (Left)"
    )

    val OSV_CURRENT_R = Ssm2Pid(
        id = "osv_cr",
        displayName = "OSV I-R",
        unit = "mA",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x16)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 32.0 },
        longName = "Oil Switching Solenoid Valve Current (Right)"
    )

    val OSV_CURRENT_L = Ssm2Pid(
        id = "osv_cl",
        displayName = "OSV I-L",
        unit = "mA",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x17)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 32.0 },
        longName = "Oil Switching Solenoid Valve Current (Left)"
    )

    val TPS_CLOSED_VOLTAGE = Ssm2Pid(
        id = "tps_closed",
        displayName = "TPS Cls V",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x6B)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 50.0 },
        longName = "Throttle Sensor Closed Voltage"
    )

    val OVERSPEED_VERY_HIGH = Ssm2Pid(
        id = "overspd_vh",
        displayName = "Ovr Spd VH",
        unit = "ct",
        addresses = listOf(Ssm2Address(0x00, 0x02, 0x98.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0].toDouble() },
        longName = "Overspeed Count (Very High RPM)"
    )

    val OVERSPEED_HIGH = Ssm2Pid(
        id = "overspd_h",
        displayName = "Ovr Spd H",
        unit = "ct",
        addresses = listOf(Ssm2Address(0x00, 0x02, 0x99.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0].toDouble() },
        longName = "Overspeed Count (High RPM)"
    )

    // ───── Calculated PIDs (mirror P201, P202, P203) ─────
    // These read raw bytes from other PIDs' addresses and compute on top.
    // Listed alongside the underlying address group so the poller fetches
    // them in the same A8 query — no separate round trip.

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

    // ────── TCM (5EAT) parameters ──────
    //
    // Addresses + conversions for the 5EAT transmission. Verified on-car
    // 2026-05-23 against a captured TCM reply at idle in Park: raw bytes
    // for gear=0, line=53, ATF=74, RPM=0x0D94 etc. all sense-check
    // against expected idle conditions.

    val TCM_GEAR_POSITION = Ssm2Pid(
        id = "tcm_gear",
        displayName = "Gear",
        unit = "gear",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x4A)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0] + 1).toDouble() },
        longName = "Gear Position",
        category = Ssm2PidCategory.TCM
    )

    val TCM_TURBINE_SPEED = Ssm2Pid(
        id = "tcm_turbine",
        displayName = "Turbine",
        unit = "rpm",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x4F)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 32.0 },
        longName = "Turbine Revolution Speed",
        category = Ssm2PidCategory.TCM
    )

    val TCM_ATF_TEMP = Ssm2Pid(
        id = "tcm_atf",
        displayName = "ATF Temp",
        unit = "°F",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x56)),
        // Raw is °C as (x-50); convert to °F to match the other
        // temperature gauges in the app.
        decode = { raw -> if (raw.isEmpty()) 0.0 else (raw[0].toDouble() - 50.0) * 9.0 / 5.0 + 32.0 },
        longName = "ATF Temperature",
        category = Ssm2PidCategory.TCM
    )

    val TCM_FRONT_WHEEL_SPEED = Ssm2Pid(
        id = "tcm_fws",
        displayName = "Frt Whl",
        unit = "mph",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x48)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 0.621371192 },
        longName = "Front Wheel Speed",
        category = Ssm2PidCategory.TCM
    )

    val TCM_REAR_WHEEL_SPEED = Ssm2Pid(
        id = "tcm_rws",
        displayName = "Rr Whl",
        unit = "mph",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x51)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 0.621371192 },
        longName = "Rear Wheel Speed",
        category = Ssm2PidCategory.TCM
    )

    val TCM_LU_PRESSURE = Ssm2Pid(
        id = "tcm_lu_press",
        displayName = "L/U Press",
        unit = "psi",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x4D)),
        // psi: x*1.450377 (raw byte directly to psi)
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 1.450377 },
        longName = "L/U Solenoid Valve Pressure",
        category = Ssm2PidCategory.TCM
    )

    val TCM_PL_PRESSURE = Ssm2Pid(
        id = "tcm_pl_press",
        displayName = "P/L Press",
        unit = "psi",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x4C)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 1.450377 },
        longName = "P/L Solenoid Valve Pressure",
        category = Ssm2PidCategory.TCM
    )

    val TCM_ENGINE_SPEED = Ssm2Pid(
        id = "tcm_rpm",
        displayName = "TCM RPM",
        unit = "rpm",
        addresses = listOf(
            Ssm2Address(0x00, 0x00, 0x0E),
            Ssm2Address(0x00, 0x00, 0x0F)
        ),
        decode = { raw ->
            if (raw.size < 2) 0.0
            else ((raw[0] shl 8) or raw[1]) * 0.25
        },
        longName = "Engine Speed (via TCM)",
        category = Ssm2PidCategory.TCM
    )

    val TCM_PEDAL_ANGLE = Ssm2Pid(
        id = "tcm_pedal",
        displayName = "Pedal",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x29)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 100.0 / 255.0 },
        longName = "Accelerator Pedal Angle",
        category = Ssm2PidCategory.TCM
    )

    // ───────────── TCM additions (5EAT P-codes) ─────────────
    // Pressures in psi, currents in A (small) or A (raw), voltages in V,
    // speeds in mph.

    val TCM_LINE_PRESSURE_DUTY = Ssm2Pid(
        id = "tcm_lp_duty",
        displayName = "LP Duty",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x4B)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 2.0 },
        longName = "Line Pressure Duty Ratio",
        category = Ssm2PidCategory.TCM
    )

    val TCM_LU_DUTY = Ssm2Pid(
        id = "tcm_lu_duty",
        displayName = "L/U Duty",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x4C)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 2.0 },
        longName = "Lock Up Duty Ratio",
        category = Ssm2PidCategory.TCM
    )

    val TCM_TRANSFER_DUTY = Ssm2Pid(
        id = "tcm_xfer_duty",
        displayName = "Xfer Duty",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x4D)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 2.0 },
        longName = "Transfer Duty Ratio (AWD)",
        category = Ssm2PidCategory.TCM
    )

    val TCM_BRAKE_CLUTCH_DUTY = Ssm2Pid(
        id = "tcm_bc_duty",
        displayName = "BrkCl Dty",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x50)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 2.0 },
        longName = "Brake Clutch Duty Ratio",
        category = Ssm2PidCategory.TCM
    )

    val TCM_LATERAL_G_VOLTAGE = Ssm2Pid(
        id = "tcm_latg_v",
        displayName = "LatG V",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x55)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 50.0 },
        longName = "Lateral G Sensor Voltage",
        category = Ssm2PidCategory.TCM
    )

    val TCM_LOW_CLUTCH_DUTY = Ssm2Pid(
        id = "tcm_lc_duty",
        displayName = "LowCl Dty",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x57)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 2.0 },
        longName = "Low Clutch Duty",
        category = Ssm2PidCategory.TCM
    )

    val TCM_HIGH_CLUTCH_DUTY = Ssm2Pid(
        id = "tcm_hc_duty",
        displayName = "HiCl Dty",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x58)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 2.0 },
        longName = "High Clutch Duty",
        category = Ssm2PidCategory.TCM
    )

    val TCM_LRB_DUTY = Ssm2Pid(
        id = "tcm_lrb_duty",
        displayName = "L/RB Dty",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x59)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 2.0 },
        longName = "Load and Reverse Brake Duty",
        category = Ssm2PidCategory.TCM
    )

    val TCM_CENTER_DIFF_SWITCH_V = Ssm2Pid(
        id = "tcm_cd_sw_v",
        displayName = "CD Sw V",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x5B)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 51.0 },
        longName = "Voltage Center Differential Switch",
        category = Ssm2PidCategory.TCM
    )

    val TCM_AT_TURBINE_1 = Ssm2Pid(
        id = "tcm_atts1",
        displayName = "AT Turb1",
        unit = "rpm",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x5C)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 32.0 },
        longName = "AT Turbine Speed 1",
        category = Ssm2PidCategory.TCM
    )

    val TCM_AT_TURBINE_2 = Ssm2Pid(
        id = "tcm_atts2",
        displayName = "AT Turb2",
        unit = "rpm",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x5D)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 32.0 },
        longName = "AT Turbine Speed 2",
        category = Ssm2PidCategory.TCM
    )

    val TCM_CENTER_DIFF_REAL_I = Ssm2Pid(
        id = "tcm_cd_real_i",
        displayName = "CD I Real",
        unit = "A",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x5E)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 32.0 },
        longName = "Center Differential Real Current",
        category = Ssm2PidCategory.TCM
    )

    val TCM_CENTER_DIFF_IND_I = Ssm2Pid(
        id = "tcm_cd_ind_i",
        displayName = "CD I Ind",
        unit = "A",
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x5F)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 32.0 },
        longName = "Center Differential Indicate Current",
        category = Ssm2PidCategory.TCM
    )

    val TCM_HLRC_CURRENT = Ssm2Pid(
        id = "tcm_hlrc_i",
        displayName = "H+LRC I",
        unit = "A",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x40)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 255.0 },
        longName = "H and LR/C Solenoid Valve Current",
        category = Ssm2PidCategory.TCM
    )

    val TCM_DC_CURRENT = Ssm2Pid(
        id = "tcm_dc_i",
        displayName = "D/C I",
        unit = "A",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x41)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 255.0 },
        longName = "D/C Solenoid Valve Current",
        category = Ssm2PidCategory.TCM
    )

    val TCM_FB_CURRENT = Ssm2Pid(
        id = "tcm_fb_i",
        displayName = "F/B I",
        unit = "A",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x42)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 255.0 },
        longName = "F/B Solenoid Valve Current",
        category = Ssm2PidCategory.TCM
    )

    val TCM_IC_CURRENT = Ssm2Pid(
        id = "tcm_ic_i",
        displayName = "I/C I",
        unit = "A",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x43)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 255.0 },
        longName = "I/C Solenoid Valve Current",
        category = Ssm2PidCategory.TCM
    )

    val TCM_PL_CURRENT = Ssm2Pid(
        id = "tcm_pl_i",
        displayName = "P/L I",
        unit = "A",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x44)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 255.0 },
        longName = "P/L Solenoid Valve Current",
        category = Ssm2PidCategory.TCM
    )

    val TCM_LU_CURRENT = Ssm2Pid(
        id = "tcm_lu_i",
        displayName = "L/U I",
        unit = "A",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x45)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 255.0 },
        longName = "L/U Solenoid Valve Current",
        category = Ssm2PidCategory.TCM
    )

    val TCM_AWD_CURRENT = Ssm2Pid(
        id = "tcm_awd_i",
        displayName = "AWD I",
        unit = "A",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x46)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 255.0 },
        longName = "AWD Solenoid Valve Current",
        category = Ssm2PidCategory.TCM
    )

    val TCM_YAW_RATE_VOLTAGE = Ssm2Pid(
        id = "tcm_yaw_v",
        displayName = "Yaw V",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x47)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 51.0 },
        longName = "Yaw Rate Sensor Voltage",
        category = Ssm2PidCategory.TCM
    )

    val TCM_HLRC_PRESSURE = Ssm2Pid(
        id = "tcm_hlrc_p",
        displayName = "H+LRC Pr",
        unit = "psi",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x48)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 1.450377 },
        longName = "H and LR/C Solenoid Valve Pressure",
        category = Ssm2PidCategory.TCM
    )

    val TCM_DC_PRESSURE = Ssm2Pid(
        id = "tcm_dc_p",
        displayName = "D/C Pr",
        unit = "psi",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x49)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 1.450377 },
        longName = "D/C Solenoid Valve Pressure",
        category = Ssm2PidCategory.TCM
    )

    val TCM_FB_PRESSURE = Ssm2Pid(
        id = "tcm_fb_p",
        displayName = "F/B Pr",
        unit = "psi",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x4A)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 1.450377 },
        longName = "F/B Solenoid Valve Pressure",
        category = Ssm2PidCategory.TCM
    )

    val TCM_IC_PRESSURE = Ssm2Pid(
        id = "tcm_ic_p",
        displayName = "I/C Pr",
        unit = "psi",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x4B)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 1.450377 },
        longName = "I/C Solenoid Valve Pressure",
        category = Ssm2PidCategory.TCM
    )

    val TCM_AWD_PRESSURE = Ssm2Pid(
        id = "tcm_awd_p",
        displayName = "AWD Pr",
        unit = "psi",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x4E)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 1.450377 },
        longName = "AWD Solenoid Valve Pressure",
        category = Ssm2PidCategory.TCM
    )

    val TCM_YAW_G_REF_VOLTAGE = Ssm2Pid(
        id = "tcm_yawg_ref",
        displayName = "YawG Ref",
        unit = "V",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x4F)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 51.0 },
        longName = "Yaw Rate and G Sensor Reference Voltage",
        category = Ssm2PidCategory.TCM
    )

    val TCM_WHEEL_FR = Ssm2Pid(
        id = "tcm_whl_fr",
        displayName = "Whl FR",
        unit = "mph",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x3C)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 0.621371192 },
        longName = "Wheel Speed Front Right",
        category = Ssm2PidCategory.TCM
    )

    val TCM_WHEEL_FL = Ssm2Pid(
        id = "tcm_whl_fl",
        displayName = "Whl FL",
        unit = "mph",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x3D)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 0.621371192 },
        longName = "Wheel Speed Front Left",
        category = Ssm2PidCategory.TCM
    )

    val TCM_WHEEL_RR = Ssm2Pid(
        id = "tcm_whl_rr",
        displayName = "Whl RR",
        unit = "mph",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x3E)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 0.621371192 },
        longName = "Wheel Speed Rear Right",
        category = Ssm2PidCategory.TCM
    )

    val TCM_WHEEL_RL = Ssm2Pid(
        id = "tcm_whl_rl",
        displayName = "Whl RL",
        unit = "mph",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x3F)),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 0.621371192 },
        longName = "Wheel Speed Rear Left",
        category = Ssm2PidCategory.TCM
    )

    val TCM_FWDB_CURRENT = Ssm2Pid(
        id = "tcm_fwdb_i",
        displayName = "FwdB I",
        unit = "A",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x85.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 255.0 },
        longName = "Fwd/B Solenoid Valve Current",
        category = Ssm2PidCategory.TCM
    )

    val TCM_FWDB_TARGET_PRESSURE = Ssm2Pid(
        id = "tcm_fwdb_p",
        displayName = "FwdB TgtPr",
        unit = "psi",
        addresses = listOf(Ssm2Address(0x00, 0x01, 0x86.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 1.450377 },
        longName = "Fwd/B Solenoid Valve Target Pressure",
        category = Ssm2PidCategory.TCM
    )

    val TCM_FR_WHEEL_RATIO = Ssm2Pid(
        id = "tcm_fr_ratio",
        displayName = "F/R Ratio",
        unit = "%",
        addresses = listOf(Ssm2Address(0x00, 0x02, 0x93.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] / 128.0 },
        longName = "Front-Rear Wheel Rotation Ratio",
        category = Ssm2PidCategory.TCM
    )

    val TCM_ABS_FRONT_MEAN = Ssm2Pid(
        id = "tcm_abs_fm",
        displayName = "ABS F Mn",
        unit = "mph",
        addresses = listOf(Ssm2Address(0x00, 0x02, 0x94.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 143.0 / 255.0 },
        longName = "ABS/VDC Front Wheel Mean Speed",
        category = Ssm2PidCategory.TCM
    )

    val TCM_ABS_REAR_MEAN = Ssm2Pid(
        id = "tcm_abs_rm",
        displayName = "ABS R Mn",
        unit = "mph",
        addresses = listOf(Ssm2Address(0x00, 0x02, 0x95.toByte())),
        decode = { raw -> if (raw.isEmpty()) 0.0 else raw[0] * 143.0 / 255.0 },
        longName = "ABS/VDC Rear Wheel Mean Speed",
        category = Ssm2PidCategory.TCM
    )

    val TCM_ATF_DETERIORATION = Ssm2Pid(
        id = "tcm_atf_deg",
        displayName = "ATF Deg",
        unit = "%",
        addresses = listOf(
            Ssm2Address(0x00, 0x02, 0x96.toByte()),
            Ssm2Address(0x00, 0x02, 0x97.toByte())
        ),
        decode = { raw ->
            if (raw.size < 2) 0.0
            else ((raw[0] shl 8) or raw[1]) * 40.0 / 13107.0
        },
        longName = "ATF Deterioration Degree",
        category = Ssm2PidCategory.TCM
    )

    val DEFAULT_DEMO_PIDS: List<Ssm2Pid> = listOf(
        RPM,
        COOLANT_TEMP,
        BATTERY_VOLTAGE,
        AF_CORRECTION_1,
        AF_LEARNING_1,
        AF_SENSOR_1,
        IGNITION_TIMING,
        MASS_AIRFLOW,
        OIL_TEMP,
        INTAKE_AIR_TEMP,
        FEEDBACK_KNOCK_CORRECTION,
        ENGINE_LOAD_CALC,
        FINE_LEARNING_KC,
        IAM,
        VEHICLE_SPEED,
        KNOCK_CORRECTION_ADVANCE,
        ENGINE_LOAD_RELATIVE,
        AF_CORRECTION_2,
        AF_LEARNING_2,
        MAP,
        THROTTLE_OPENING_ANGLE,
        FRONT_O2_1,
        REAR_O2,
        FRONT_O2_2,
        MAF_VOLTAGE,
        TPS_VOLTAGE,
        INJ1_PULSE_WIDTH,
        INJ2_PULSE_WIDTH,
        ATMOSPHERIC_PRESSURE,
        MANIFOLD_RELATIVE_PRESSURE,
        LEARNED_IGNITION_TIMING,
        FUEL_TEMPERATURE,
        RADIATOR_FAN_CONTROL,
        CPC_VALVE_DUTY,
        TUMBLE_VALVE_R,
        TUMBLE_VALVE_L,
        ISC_VALVE_DUTY,
        AF_LEAN_CORRECTION,
        AF_HEATER_DUTY,
        ISC_VALVE_STEP,
        ALTERNATOR_DUTY,
        FUEL_PUMP_DUTY,
        AVCS_INTAKE_R,
        AVCS_INTAKE_L,
        OCV_DUTY_R,
        OCV_DUTY_L,
        OCV_CURRENT_R,
        OCV_CURRENT_L,
        AFS1_CURRENT,
        AFS2_CURRENT,
        AFS1_RESISTANCE,
        AFS2_RESISTANCE,
        AF_SENSOR_2,
        AFS1_HEATER_CURRENT,
        AFS2_HEATER_CURRENT,
        CYL1_ROUGHNESS,
        CYL2_ROUGHNESS,
        CYL3_ROUGHNESS,
        CYL4_ROUGHNESS,
        CYL5_ROUGHNESS,
        CYL6_ROUGHNESS,
        THROTTLE_MOTOR_DUTY,
        THROTTLE_MOTOR_VOLTAGE,
        TPS_SUB,
        TPS_MAIN,
        PEDAL_SUB,
        PEDAL_MAIN,
        EGT_1,
        EGT_2,
        ODOMETER,
        OSV_DUTY_R,
        OSV_DUTY_L,
        OSV_CURRENT_R,
        OSV_CURRENT_L,
        TPS_CLOSED_VOLTAGE,
        OVERSPEED_VERY_HIGH,
        OVERSPEED_HIGH,
        INJECTOR_DUTY_CYCLE,
        MRP_CORRECTED,
        FUEL_CONSUMPTION,
        TCM_GEAR_POSITION,
        TCM_TURBINE_SPEED,
        TCM_ATF_TEMP,
        TCM_FRONT_WHEEL_SPEED,
        TCM_REAR_WHEEL_SPEED,
        TCM_LU_PRESSURE,
        TCM_PL_PRESSURE,
        TCM_ENGINE_SPEED,
        TCM_PEDAL_ANGLE,
        TCM_LINE_PRESSURE_DUTY,
        TCM_LU_DUTY,
        TCM_TRANSFER_DUTY,
        TCM_BRAKE_CLUTCH_DUTY,
        TCM_LATERAL_G_VOLTAGE,
        TCM_LOW_CLUTCH_DUTY,
        TCM_HIGH_CLUTCH_DUTY,
        TCM_LRB_DUTY,
        TCM_CENTER_DIFF_SWITCH_V,
        TCM_AT_TURBINE_1,
        TCM_AT_TURBINE_2,
        TCM_CENTER_DIFF_REAL_I,
        TCM_CENTER_DIFF_IND_I,
        TCM_HLRC_CURRENT,
        TCM_DC_CURRENT,
        TCM_FB_CURRENT,
        TCM_IC_CURRENT,
        TCM_PL_CURRENT,
        TCM_LU_CURRENT,
        TCM_AWD_CURRENT,
        TCM_YAW_RATE_VOLTAGE,
        TCM_HLRC_PRESSURE,
        TCM_DC_PRESSURE,
        TCM_FB_PRESSURE,
        TCM_IC_PRESSURE,
        TCM_AWD_PRESSURE,
        TCM_YAW_G_REF_VOLTAGE,
        TCM_WHEEL_FR,
        TCM_WHEEL_FL,
        TCM_WHEEL_RR,
        TCM_WHEEL_RL,
        TCM_FWDB_CURRENT,
        TCM_FWDB_TARGET_PRESSURE,
        TCM_FR_WHEEL_RATIO,
        TCM_ABS_FRONT_MEAN,
        TCM_ABS_REAR_MEAN,
        TCM_ATF_DETERIORATION
    )

    private fun decodeFloatBE(raw: IntArray): Double {
        if (raw.size < 4) return 0.0
        val bits = (raw[0] shl 24) or (raw[1] shl 16) or (raw[2] shl 8) or raw[3]
        return Float.fromBits(bits).toDouble()
    }
}
