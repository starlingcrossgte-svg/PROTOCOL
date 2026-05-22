package com.protocol.app.openport2

/**
 * SSM2 parameter (PID) definitions for the 2006 USDM Subaru EZ30R H6.
 *
 * Address sources:
 *  - 0x00001C (battery voltage) and 0x00000C (coolant temp) are
 *    CONFIRMED from the earlier wireshark RomRaider logger capture
 *    (wireshark-sequencelab/romraider-traffic-raw.txt). 12.4 V battery
 *    and 89 °C coolant decoded correctly on a warmed engine at rest.
 *  - 0x00000E,0F (RPM) and 0x000013,14 (MAF) are CONFIRMED from the new
 *    multi-PID capture (wireshark-sequencelab/log-to-add-more-guages-1.pcapng):
 *    decoded values 927 RPM and 6.39 g/s match an idling engine.
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
        addresses = listOf(Ssm2Address(0x00, 0x00, 0x0C)),
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
    // alongside MASS_AIRFLOW/RPM — matches how RomRaider's calculated load
    // works (sees the addresses twice in the A8 query).
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
                val rpm = ((raw[0] shl 8) or raw[1]) * 0.25
                val maf = ((raw[2] shl 8) or raw[3]) * 0.01
                if (rpm > 0.0) maf * 120.0 / rpm else 0.0
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
        addresses = listOf(
            Ssm2Address(0xFF.toByte(), 0x25, 0x78),
            Ssm2Address(0xFF.toByte(), 0x25, 0x79),
            Ssm2Address(0xFF.toByte(), 0x25, 0x7A),
            Ssm2Address(0xFF.toByte(), 0x25, 0x7B)
        ),
        decode = { raw -> decodeFloatBE(raw) },
        longName = "IAM (Ignition Advance Multiplier)"
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
        IAM
    )

    private fun decodeFloatBE(raw: IntArray): Double {
        if (raw.size < 4) return 0.0
        val bits = (raw[0] shl 24) or (raw[1] shl 16) or (raw[2] shl 8) or raw[3]
        return Float.fromBits(bits).toDouble()
    }
}
