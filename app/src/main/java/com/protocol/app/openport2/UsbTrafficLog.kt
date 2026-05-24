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
 * Process-wide ring buffer of recent USB bulk transfers between the host
 * and the Tactrix adapter. Lives outside the ViewModel so [TactrixBulkIo]
 * can call into it from anywhere on the I/O path without needing a
 * back-reference.
 *
 * Capped at [MAX_EVENTS] — older events drop off the front when the cap
 * is reached. At ~5 polls/sec × 4 events/poll (two writes, two reads),
 * 500 events is roughly the last 25 seconds of traffic. Good enough for
 * "what just happened on the wire" debugging; the user can clear to
 * narrow focus.
 *
 * The UI subscribes via [events] (collectAsState in Compose). Mutation is
 * synchronous and lock-free — StateFlow's CAS handles the publish step.
 * Bursts of writes from multiple threads can re-order slightly under
 * heavy contention; for diagnostic display that's acceptable.
 */
object UsbTrafficLog {
    private const val MAX_EVENTS = 500

    private val _events = MutableStateFlow<List<TrafficEvent>>(emptyList())
    val events: StateFlow<List<TrafficEvent>> = _events.asStateFlow()

    fun recordWrite(bytes: ByteArray) = record(TrafficEvent.Direction.OUT, bytes)
    fun recordRead(bytes: ByteArray) = record(TrafficEvent.Direction.IN, bytes)

    fun clear() {
        _events.value = emptyList()
    }

    private fun record(direction: TrafficEvent.Direction, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val event = TrafficEvent(
            timestampMs = System.currentTimeMillis(),
            direction = direction,
            hex = TactrixHex.bytesToHex(bytes),
            ascii = TactrixHex.bytesToPrintableAscii(bytes),
            byteCount = bytes.size
        )
        val current = _events.value
        val next = if (current.size >= MAX_EVENTS)
            current.drop(1) + event
        else
            current + event
        _events.value = next
    }
}
