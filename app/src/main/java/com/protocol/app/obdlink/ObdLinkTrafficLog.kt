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
 * Process-wide ring buffer of recent OBDLink Bluetooth exchanges. Fed by
 * [ObdLinkBtTransport]'s log hook; the Developer page subscribes via [events]
 * so the user can watch the ELM handshake + SSM2 polling live — an in-app
 * view of the adapter's byte traffic.
 *
 * Its own log (zero shared code with the USB UsbTrafficLog / flash logs) so
 * the adapter paths stay isolated.
 */
object ObdLinkTrafficLog {
    private const val MAX_EVENTS = 1000

    private val _events = MutableStateFlow<List<ObdLinkTrafficEvent>>(emptyList())
    val events: StateFlow<List<ObdLinkTrafficEvent>> = _events.asStateFlow()

    @Volatile var recording: Boolean = true

    fun record(direction: String, text: String) {
        if (!recording) return
        if (text.isEmpty()) return
        val dir = if (direction == "OUT") ObdLinkTrafficEvent.Direction.OUT else ObdLinkTrafficEvent.Direction.IN
        val event = ObdLinkTrafficEvent(System.currentTimeMillis(), dir, text)
        val current = _events.value
        _events.value = if (current.size >= MAX_EVENTS) current.drop(1) + event else current + event
    }

    fun clear() {
        _events.value = emptyList()
    }
}
