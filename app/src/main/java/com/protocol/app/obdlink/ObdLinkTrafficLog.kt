package com.protocol.app.obdlink

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One line of OBDLink Bluetooth traffic — an ASCII command going out or a
 * reply coming back. Pre-trimmed for cheap display.
 */
data class ObdLinkTrafficEvent(
    val timestampMs: Long,
    val direction: Direction,
    val text: String
) {
    enum class Direction { OUT, IN }
}

/**
 * Process-wide ring buffer of recent OBDLink exchanges. Fed by
 * [ObdLinkBtTransport]'s log hook; the Developer page reads it so the user can
 * watch the ELM handshake + SSM2 polling — an in-app view of the adapter's byte
 * traffic. Its own log (zero shared code with the USB
 * [com.protocol.app.openport2.UsbTrafficLog]) so the adapter paths stay isolated.
 *
 * Same O(1) ring as the USB log: [record] appends to an [ArrayDeque] and bumps
 * [revision]; the O(n) [snapshot] is built by observers when they render, so
 * always-on recording is cheap when nobody is watching. Synchronized for the
 * reader-thread / UI-thread mix.
 */
object ObdLinkTrafficLog {
    private const val MAX_EVENTS = 5000

    private val buffer = ArrayDeque<ObdLinkTrafficEvent>(MAX_EVENTS)
    private val _revision = MutableStateFlow(0)
    /** Bumped on every record / clear. Observers re-read [snapshot] when it changes. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Current buffer contents, oldest-first. O(n) — call only when rendering. */
    @Synchronized fun snapshot(): List<ObdLinkTrafficEvent> = buffer.toList()

    @Synchronized fun record(direction: String, text: String) {
        if (text.isEmpty()) return
        val dir = if (direction == "OUT") ObdLinkTrafficEvent.Direction.OUT else ObdLinkTrafficEvent.Direction.IN
        if (buffer.size >= MAX_EVENTS) buffer.removeFirst()
        buffer.addLast(ObdLinkTrafficEvent(System.currentTimeMillis(), dir, text))
        _revision.value++
    }

    @Synchronized fun clear() {
        buffer.clear()
        _revision.value++
    }
}
