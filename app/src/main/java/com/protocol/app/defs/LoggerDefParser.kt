package com.protocol.app.defs

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

/**
 * Streams an SSM2 logger definition file into [LoggerDefParam]s with a pull
 * parser, so a large file is read in one low-memory pass. Ships no data — the
 * [input] is always a file the user supplies at runtime.
 *
 * Reads both standard parameters (a direct <address>) and extended, per-ECU
 * parameters (<ecu id="..."> address blocks). For extended parameters, when
 * [targetEcuId] is given only the matching ECU's block is kept; otherwise the
 * first block is used and tagged with its ECU id. Derived parameters — an
 * expression referring to other parameters, with no address of their own — have
 * no address here and are skipped; they are resolved in a later layer.
 */
object LoggerDefParser {

    fun parse(input: InputStream, targetEcuId: String? = null): List<LoggerDefParam> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(input, null)

        val result = ArrayList<LoggerDefParam>()
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "parameter" -> readParameter(parser)?.let(result::add)
                    "ecuparam" -> readEcuParam(parser, targetEcuId)?.let(result::add)
                }
            }
            event = parser.next()
        }
        return result
    }

    // --- standard parameter (single <address>) ---------------------------------

    private fun readParameter(parser: XmlPullParser): LoggerDefParam? {
        val id = parser.getAttributeValue(null, "id")
        if (id == null) { skipElement(parser); return null }
        val name = parser.getAttributeValue(null, "name") ?: id
        val desc = parser.getAttributeValue(null, "desc") ?: ""
        val capByte = parser.getAttributeValue(null, "ecubyteindex")?.toIntOrNull()
        val capBit = parser.getAttributeValue(null, "ecubit")?.toIntOrNull()
        val target = parser.getAttributeValue(null, "target")?.toIntOrNull() ?: 1

        val addresses = ArrayList<LoggerAddress>()
        var conversions = emptyList<LoggerConversion>()
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && parser.name == "parameter") break
            if (ev != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "address" -> readAddress(parser)?.let(addresses::add)
                "conversions" -> conversions = readConversions(parser)
                else -> skipElement(parser)
            }
        }
        if (addresses.isEmpty() || conversions.isEmpty()) return null
        return LoggerDefParam(id, name, desc, addresses, conversions, capByte, capBit, target, null)
    }

    // --- extended parameter (per-ECU <ecu id="..."> blocks) --------------------

    private fun readEcuParam(parser: XmlPullParser, targetEcuId: String?): LoggerDefParam? {
        val id = parser.getAttributeValue(null, "id")
        if (id == null) { skipElement(parser); return null }
        val name = parser.getAttributeValue(null, "name") ?: id
        val desc = parser.getAttributeValue(null, "desc") ?: ""

        var chosenEcuId: String? = null
        var chosenAddresses: List<LoggerAddress>? = null
        var lockedExact = false
        var conversions = emptyList<LoggerConversion>()

        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && parser.name == "ecuparam") break
            if (ev != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "ecu" -> {
                    val ecuId = parser.getAttributeValue(null, "id")
                    val addrs = readEcuAddresses(parser)
                    if (!lockedExact) {
                        val exact = targetEcuId != null && ecuId != null &&
                            ecuId.split(',').any { it.trim() == targetEcuId }
                        if (exact || chosenAddresses == null) {
                            chosenEcuId = ecuId
                            chosenAddresses = addrs
                            if (exact) lockedExact = true
                        }
                    }
                }
                "conversions" -> conversions = readConversions(parser)
                else -> skipElement(parser)
            }
        }
        val addrs = chosenAddresses ?: return null
        if (addrs.isEmpty() || conversions.isEmpty()) return null
        return LoggerDefParam(id, name, desc, addrs, conversions, null, null, 1, chosenEcuId)
    }

    private fun readEcuAddresses(parser: XmlPullParser): List<LoggerAddress> {
        val list = ArrayList<LoggerAddress>()
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && parser.name == "ecu") break
            if (ev != XmlPullParser.START_TAG) continue
            if (parser.name == "address") readAddress(parser)?.let(list::add) else skipElement(parser)
        }
        return list
    }

    // --- shared child readers --------------------------------------------------

    private fun readAddress(parser: XmlPullParser): LoggerAddress? {
        val length = parser.getAttributeValue(null, "length")?.toIntOrNull() ?: 1
        val bit = parser.getAttributeValue(null, "bit")?.toIntOrNull()
        val text = parser.nextText().trim() // consumes text; leaves parser on </address>
        val addr = parseHex(text) ?: return null
        return LoggerAddress(addr, length, bit)
    }

    private fun readConversions(parser: XmlPullParser): List<LoggerConversion> {
        val list = ArrayList<LoggerConversion>()
        while (true) {
            val ev = parser.next()
            if (ev == XmlPullParser.END_DOCUMENT) break
            if (ev == XmlPullParser.END_TAG && parser.name == "conversions") break
            if (ev != XmlPullParser.START_TAG) continue
            if (parser.name == "conversion") {
                val expr = parser.getAttributeValue(null, "expr")
                if (expr != null) {
                    list.add(
                        LoggerConversion(
                            units = parser.getAttributeValue(null, "units") ?: "",
                            expr = expr,
                            format = parser.getAttributeValue(null, "format"),
                            gaugeMin = parser.getAttributeValue(null, "gauge_min")?.toDoubleOrNull(),
                            gaugeMax = parser.getAttributeValue(null, "gauge_max")?.toDoubleOrNull(),
                            gaugeStep = parser.getAttributeValue(null, "gauge_step")?.toDoubleOrNull()
                        )
                    )
                }
            }
            skipElement(parser) // consume any child elements + the element's END_TAG
        }
        return list
    }

    // --- helpers ---------------------------------------------------------------

    /** Skip the element the parser is currently positioned on, through its
     *  matching END_TAG (handles nested and self-closing elements). */
    private fun skipElement(parser: XmlPullParser) {
        var depth = 1
        while (depth > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    private fun parseHex(s: String): Int? {
        val t = s.trim()
        val body = if (t.startsWith("0x") || t.startsWith("0X")) t.substring(2) else t
        return body.toLongOrNull(16)?.toInt()
    }
}
