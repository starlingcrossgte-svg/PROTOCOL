package com.protocol.app.protocol

import com.protocol.app.obdlink.ObdLinkTrafficEvent
import com.protocol.app.obdlink.ObdLinkTrafficLog
import com.protocol.app.openport2.TrafficEvent
import com.protocol.app.openport2.UsbTrafficLog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Builds a CSV snapshot of the dev-page RAW BYTES stream (USB + OBDLink
 * traffic merged, time-ordered). Used by the Lock-and-Tap auto-save so a
 * short rapid capture lands on disk for PID verification. The on-screen RAW
 * BYTES log keeps its own formatter (DeveloperBody) — this one is CSV-shaped
 * (Time,Dir,Payload) for spreadsheet/RomRaider-style inspection.
 */
object RawByteLog {

    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    private data class Line(val ts: Long, val out: Boolean, val payload: String)

    fun formatCsv(): String {
        val usb = UsbTrafficLog.events.value
        val bt = ObdLinkTrafficLog.events.value
        if (usb.isEmpty() && bt.isEmpty()) return ""
        val lines = ArrayList<Line>(usb.size + bt.size)
        for (e in usb) lines.add(Line(e.timestampMs, e.direction == TrafficEvent.Direction.OUT, e.hex))
        for (e in bt) lines.add(Line(e.timestampMs, e.direction == ObdLinkTrafficEvent.Direction.OUT, e.text))
        lines.sortBy { it.ts }
        val sb = StringBuilder("Time,Dir,Payload\n")
        for (l in lines) {
            sb.append(timeFmt.format(Date(l.ts)))
                .append(',')
                .append(if (l.out) "OUT" else "IN")
                .append(',')
                // Strip commas so the payload stays in one CSV cell.
                .append(l.payload.replace(',', ' '))
                .append('\n')
        }
        return sb.toString()
    }
}
