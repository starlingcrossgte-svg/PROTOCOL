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

    return Ssm2Pid(
        id = id,
        displayName = name,
        unit = conversion.units,
        addresses = expanded,
        decode = { raw ->
            if (raw.isEmpty()) {
                0.0
            } else {
                var x = 0
                for (b in raw) x = (x shl 8) or (b and 0xFF)
                val value = if (bit != null) (x ushr bit) and 1 else x
                convert(value.toDouble())
            }
        },
        longName = description.ifBlank { name },
        category = Ssm2PidCategory.ECU,
        verified = true
    )
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
