package com.protocol.app.defs

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.SequenceInputStream

/**
 * Streams an SSM2 logger definition file into [LoggerDefParam]s with a pull
 * parser, so a large file is read in one low-memory pass. Ships no data — the
 * [input] is always a file the user supplies at runtime.
 *
 * **Only the SSM section is read.** A definition file groups its parameters
 * under one element per wire protocol, and a single file routinely carries
 * several unrelated ones — other manufacturers, and diagnostic-CAN parameters
 * addressed a completely different way. Those sections number their parameters
 * from the same starting point, so their ids collide with the SSM ids: reading
 * the whole document yields parameters that share an id but name a foreign
 * address space, and the wrong one silently wins. Everything outside
 * [PROTOCOL_SSM] is therefore skipped.
 *
 * Reads standard parameters (a direct <address>), single-bit switches, and
 * extended per-ECU parameters (<ecu id="..."> address blocks). An extended
 * parameter means different addresses on different calibrations, so it is kept
 * only when [targetEcuId] names a block the file actually defines — never bound
 * to whichever block happens to come first. Derived parameters — an expression
 * over other parameters, with no address of their own — carry no address here
 * and are skipped; they are resolved in a later layer.
 */
object LoggerDefParser {

    /** The only protocol section this app reads. */
    const val PROTOCOL_SSM = "SSM"

    fun parse(input: InputStream, targetEcuId: String? = null): List<LoggerDefParam> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stripDoctype(input), null)

        val result = ArrayList<LoggerDefParam>()
        // Only content inside <protocol id="SSM"> is ours; everything else in the
        // file belongs to another bus or another manufacturer.
        var inSsm = false
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "protocol" ->
                        inSsm = parser.getAttributeValue(null, "id") == PROTOCOL_SSM
                    "parameter" -> if (inSsm) readParameter(parser)?.let(result::add)
                    "ecuparam" -> if (inSsm) readEcuParam(parser, targetEcuId)?.let(result::add)
                    "switch" -> if (inSsm) readSwitch(parser)?.let(result::add)
                }
                XmlPullParser.END_TAG -> if (parser.name == "protocol") inSsm = false
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
        val target = parser.getAttributeValue(null, "target")?.toIntOrNull() ?: 1

        // An extended parameter carries one address block per calibration, and the
        // same id means a different address on each. Only the block naming the
        // connected ECU is usable; with no ECU id known there is no correct block
        // to pick, so the parameter is dropped. Guessing here would read a real
        // address belonging to a different car and report the result as this
        // parameter's value.
        var chosenEcuId: String? = null
        var chosenAddresses: List<LoggerAddress>? = null
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
                    val exact = targetEcuId != null && ecuId != null &&
                        ecuId.split(',').any { it.trim() == targetEcuId }
                    if (exact && chosenAddresses == null) {
                        chosenEcuId = targetEcuId
                        chosenAddresses = addrs
                    }
                }
                "conversions" -> conversions = readConversions(parser)
                else -> skipElement(parser)
            }
        }
        val addrs = chosenAddresses ?: return null
        if (addrs.isEmpty() || conversions.isEmpty()) return null
        return LoggerDefParam(id, name, desc, addrs, conversions, null, null, target, chosenEcuId)
    }

    // --- switch (single bit of a single byte, no conversions element) ----------

    /**
     * A switch is an on/off bit rather than a measured value: it names one byte
     * and one bit directly and carries no conversion. It becomes an ordinary
     * parameter reading that byte, with the bit extracted to 0 or 1.
     */
    private fun readSwitch(parser: XmlPullParser): LoggerDefParam? {
        val id = parser.getAttributeValue(null, "id") ?: return null
        val name = parser.getAttributeValue(null, "name") ?: id
        val desc = parser.getAttributeValue(null, "desc") ?: ""
        val addr = parseHex(parser.getAttributeValue(null, "byte") ?: return null) ?: return null
        val bit = parser.getAttributeValue(null, "bit")?.toIntOrNull() ?: return null
        val capByte = parser.getAttributeValue(null, "ecubyteindex")?.toIntOrNull()
        val target = parser.getAttributeValue(null, "target")?.toIntOrNull() ?: 1
        return LoggerDefParam(
            id = id,
            name = name,
            description = desc,
            addresses = listOf(LoggerAddress(addr, 1, bit)),
            conversions = listOf(LoggerConversion(units = "", expr = "x")),
            capByteIndex = capByte,
            capBit = bit,
            target = target,
            ecuId = null
        )
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
                            storagetype = parser.getAttributeValue(null, "storagetype"),
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

    // --- document-type declaration removal -------------------------------------

    /** Bytes of the stream head examined for a document-type declaration. A
     *  declaration is only legal in the prolog, ahead of the root element, so a
     *  generous head is enough — the rest of the file streams through untouched. */
    private const val PROLOG_SCAN_BYTES = 256 * 1024

    /**
     * Returns [input] with any document-type declaration removed from its head.
     *
     * Some definition files carry an inline declaration with an internal subset —
     * element and attribute declarations between square brackets. The platform's
     * pull parser rejects that construct and the whole file fails to load even
     * though it is well-formed (an independent strict parser reads the same file
     * without complaint). Nothing this app reads comes from the declaration: every
     * value is taken from elements and attributes, so dropping it is lossless.
     *
     * Only the head is buffered; if no declaration is found there the stream is
     * handed back with the head restored, so files without one are unaffected.
     */
    private fun stripDoctype(input: InputStream): InputStream {
        val head = ByteArray(PROLOG_SCAN_BYTES)
        var n = 0
        while (n < head.size) {
            val r = input.read(head, n, head.size - n)
            if (r < 0) break
            n += r
        }
        val cleaned = removeDoctype(head, n)
        return SequenceInputStream(ByteArrayInputStream(cleaned), input)
    }

    /**
     * Copy of [buf]'s first [len] bytes with the document-type declaration cut
     * out, or those bytes unchanged when there is none.
     *
     * The end of the declaration is not simply the next '>': an internal subset
     * contains declarations that each end in '>' themselves. So while inside the
     * brackets those are ignored, and the declaration ends at the first '>' after
     * the subset closes. Quoted strings are tracked so a bracket or angle bracket
     * inside a quoted literal cannot end it early.
     */
    private fun removeDoctype(buf: ByteArray, len: Int): ByteArray {
        val marker = "<!DOCTYPE".toByteArray()
        val start = indexOf(buf, len, marker)
        if (start < 0) return buf.copyOf(len)

        var i = start + marker.size
        var quote = 0.toChar()
        var inSubset = false
        var end = -1
        while (i < len) {
            val c = buf[i].toInt().toChar()
            when {
                quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                c == '"' || c == '\'' -> quote = c
                c == '[' -> inSubset = true
                c == ']' -> inSubset = false
                c == '>' && !inSubset -> { end = i; }
            }
            if (end >= 0) break
            i++
        }
        // Declaration not terminated inside the scanned head: leave the stream
        // alone rather than truncate it, and let the parser report the problem.
        if (end < 0) return buf.copyOf(len)

        val out = ByteArray(len - (end - start + 1))
        System.arraycopy(buf, 0, out, 0, start)
        System.arraycopy(buf, end + 1, out, start, len - end - 1)
        return out
    }

    /** First index of [pattern] within the first [len] bytes of [buf], or -1. */
    private fun indexOf(buf: ByteArray, len: Int, pattern: ByteArray): Int {
        outer@ for (i in 0..(len - pattern.size)) {
            for (j in pattern.indices) {
                if (buf[i + j] != pattern[j]) continue@outer
            }
            return i
        }
        return -1
    }
}
