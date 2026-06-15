package com.protocol.app.openport2

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One USB bulk-transfer event — a write going out from the host to the
 * adapter, or a read coming back. Both [hex] and [ascii] are pre-rendered
 * so the UI can pick whichever it wants without re-encoding per recompose.
 */
data class TrafficEvent(
    val timestampMs: Long,
    val direction: Direction,
    val hex: String,
    val ascii: String,
    val byteCount: Int
) {
    enum class Direction { OUT, IN }
}

/**
 * Process-wide ring buffer of recent USB bulk transfers between the host and
 * the Tactrix adapter. Lives outside the ViewModel so [TactrixBulkIo] can call
 * into it from anywhere on the I/O path without a back-reference.
 *
 * Capped at [MAX_EVENTS] (oldest drop off the front) — ~4 minutes of traffic at
 * the default poll rate. Backed by an [ArrayDeque] so [record] is O(1): append
 * plus a single front-drop, no whole-list copy. The hot wire path only bumps
 * [revision] (an O(1) counter); the O(n) snapshot is built by observers in
 * [snapshot] when they actually render, so always-on recording costs ~nothing
 * when nobody is watching the dev log. All access is synchronized — safe from
 * the IO reader threads and the UI thread at once.
 */
object UsbTrafficLog {
    private const val MAX_EVENTS = 5000

    private val buffer = ArrayDeque<TrafficEvent>(MAX_EVENTS)
    private val _revision = MutableStateFlow(0)
    /** Bumped on every record / clear. Observers re-read [snapshot] when it changes. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /** Current buffer contents, oldest-first. O(n) — call only when rendering. */
    @Synchronized fun snapshot(): List<TrafficEvent> = buffer.toList()

    fun recordWrite(bytes: ByteArray) = record(TrafficEvent.Direction.OUT, bytes)
    fun recordRead(bytes: ByteArray) = record(TrafficEvent.Direction.IN, bytes)

    @Synchronized fun clear() {
        buffer.clear()
        _revision.value++
    }

    @Synchronized private fun record(direction: TrafficEvent.Direction, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        if (buffer.size >= MAX_EVENTS) buffer.removeFirst()
        buffer.addLast(
            TrafficEvent(
                timestampMs = System.currentTimeMillis(),
                direction = direction,
                hex = TactrixHex.bytesToHex(bytes),
                ascii = TactrixHex.bytesToPrintableAscii(bytes),
                byteCount = bytes.size
            )
        )
        _revision.value++
    }
}
