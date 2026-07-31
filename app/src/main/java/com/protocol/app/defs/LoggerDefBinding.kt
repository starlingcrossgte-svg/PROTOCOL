package com.protocol.app.defs

import com.protocol.app.openport2.Ssm2Address
import com.protocol.app.openport2.Ssm2Pid
import com.protocol.app.openport2.Ssm2PidCategory

/**
 * Binds a parsed [LoggerDefParam] to the app's runtime [Ssm2Pid], so a loaded
 * definition flows through the existing parameters page, poller and log path
 * unchanged.
 *
 * Returns null when the parameter can't be made loggable: no conversion whose
 * expression compiles (a derived parameter, whose expression refers to other
 * parameters, lands here), or an address that can't be expanded. Skipping such
 * a parameter is intentional — the rest of the file still loads.
 *
 * The raw bytes are assembled big-endian into `x` (high address first), a single
 * [LoggerAddress.bit] is extracted when present, then the compiled conversion
 * turns `x` into the real value — matching the byte order the built-in
 * parameters already use.
 */
fun LoggerDefParam.toSsm2Pid(): Ssm2Pid? {
    var chosen: LoggerConversion? = null
    var compiled: ((Double) -> Double)? = null
    for (c in conversions) {
        val fn = ExpressionEvaluator.compile(c.expr) ?: continue
        chosen = c
        compiled = fn
        break
    }
    val conversion = chosen ?: return null
    val convert = compiled ?: return null

    val expanded = addresses.flatMap { expandAddress(it) }
    if (expanded.isEmpty()) return null

    val bit = addresses.firstOrNull()?.bit
    val storage = conversion.storagetype   // int8|int16|uint8|uint16|int32|uint32|float|null

    return Ssm2Pid(
        id = id,
        displayName = name,
        unit = conversion.units,
        addresses = expanded,
        decode = { raw ->
            if (raw.isEmpty()) {
                0.0
            } else {
                // Assemble the bytes big-endian (high address first), then
                // interpret them per the definition's storage type — matching
                // the byte order and float/signed handling the built-in
                // parameters already use.
                var u = 0L
                for (b in raw) u = (u shl 8) or (b.toLong() and 0xFF)
                val x: Double = when (storage) {
                    "float" -> if (raw.size < 4) 0.0 else Float.fromBits(u.toInt()).toDouble()
                    "int8", "int16", "int32" -> signExtend(u, raw.size * 8).toDouble()
                    else ->
                        // uint8/uint16/uint32 or unspecified: unsigned, with an
                        // optional single-bit extraction for switch parameters.
                        if (bit != null) ((u ushr bit) and 1L).toDouble() else u.toDouble()
                }
                convert(x)
            }
        },
        longName = description.ifBlank { name },
        // The definition's target names the module: 1 = engine, 2 = transmission,
        // 3 = served by both. A parameter offered by both is polled on the engine
        // module, which is always present — the transmission may not be.
        category = if (target == 2) Ssm2PidCategory.TCM else Ssm2PidCategory.ECU,
        verified = true
    )
}

/** Sign-extend the low [bits] bits of [u] into a signed Long. */
private fun signExtend(u: Long, bits: Int): Long {
    if (bits <= 0 || bits >= 64) return u
    val mask = 1L shl (bits - 1)
    val low = u and ((1L shl bits) - 1)
    return (low xor mask) - mask
}

/** Expand one address slice into the individual single-byte SSM2 addresses the
 *  poller reads (a length-N slice becomes N consecutive addresses). */
private fun expandAddress(a: LoggerAddress): List<Ssm2Address> {
    if (a.length <= 0) return emptyList()
    return (0 until a.length).map { i ->
        val addr = a.address + i
        Ssm2Address(
            ((addr ushr 16) and 0xFF).toByte(),
            ((addr ushr 8) and 0xFF).toByte(),
            (addr and 0xFF).toByte()
        )
    }
}
