package com.protocol.app.defs

/**
 * Tool-neutral model of one SSM2 logger definition. Ships nothing: every value
 * here comes from a definition file the user supplies at runtime. The parser
 * ([LoggerDefParser]) fills these in; binding to live polling and to the UI
 * happens in later layers.
 */

/**
 * One address slice of a parameter. [length] bytes are read starting at
 * [address]; when [bit] is set the value is a single bit (a switch/flag) rather
 * than a byte range.
 */
data class LoggerAddress(
    val address: Int,
    val length: Int = 1,
    val bit: Int? = null
)

/**
 * One way to turn a parameter's raw value into a real-world reading. A parameter
 * may offer several (for example °F and °C) and the UI chooses one. [expr] is
 * kept verbatim for [ExpressionEvaluator] to compile; the gauge hints, when
 * present, give a sensible default range without hand-tuning.
 */
data class LoggerConversion(
    val units: String,
    val expr: String,
    val format: String? = null,
    val gaugeMin: Double? = null,
    val gaugeMax: Double? = null,
    val gaugeStep: Double? = null
)

/**
 * One logged parameter. Standard parameters carry a single [addresses] entry;
 * extended (per-ECU) parameters resolve to the address block matching the
 * connected ECU, tagged with [ecuId]. [capByteIndex]/[capBit] index the ECU's
 * capability bitmap and let a later layer hide parameters the ECU cannot serve.
 */
data class LoggerDefParam(
    val id: String,
    val name: String,
    val description: String = "",
    val addresses: List<LoggerAddress>,
    val conversions: List<LoggerConversion>,
    val capByteIndex: Int? = null,
    val capBit: Int? = null,
    val target: Int = 1,
    val ecuId: String? = null
)
