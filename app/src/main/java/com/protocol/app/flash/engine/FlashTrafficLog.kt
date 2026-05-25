package com.protocol.app.flash.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One USB bulk-transfer event for the flash silo - a write going out to the
 * adapter or a read coming back. hex/ascii are pre-rendered for cheap display.
 *
 * Mirrors the logging path's TrafficEvent but is the flash silo's OWN (zero
 * shared code), so flash byte traffic never mixes with the logging traffic log.
 */
data class FlashTrafficEvent(
    val timestampMs: Long,
    val direction: Direction,
    val hex: String,
    val ascii: String,
    val byteCount: Int
) {
    enum class Direction { OUT, IN }
}

/**
 * Process-wide ring buffer of recent flash USB bulk transfers. Fed by
 * [FlashBulkIo] on the I/O path; the Flash page subscribes via [events]
 * (collectAsState) to show every byte as it goes out and comes back, live.
 */
object FlashTrafficLog {
    private const val MAX_EVENTS = 2000

    private val _events = MutableStateFlow<List<FlashTrafficEvent>>(emptyList())
    val events: StateFlow<List<FlashTrafficEvent>> = _events.asStateFlow()

    @Volatile var recording: Boolean = true

    fun recordWrite(bytes: ByteArray) = record(FlashTrafficEvent.Direction.OUT, bytes)
    fun recordRead(bytes: ByteArray) = record(FlashTrafficEvent.Direction.IN, bytes)

    fun clear() {
        _events.value = emptyList()
    }

    private fun record(direction: FlashTrafficEvent.Direction, bytes: ByteArray) {
        if (!recording) return
        if (bytes.isEmpty()) return
        val event = FlashTrafficEvent(
            timestampMs = System.currentTimeMillis(),
            direction = direction,
            hex = FlashHex.bytesToHex(bytes),
            ascii = FlashHex.bytesToPrintableAscii(bytes),
            byteCount = bytes.size
        )
        val current = _events.value
        _events.value = if (current.size >= MAX_EVENTS) current.drop(1) + event else current + event
    }
}
