package com.protocol.app.obdlink

import com.protocol.app.openport2.PollSample
import com.protocol.app.openport2.Ssm2Pid
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Listen-only CAN **broadcast** source for the OBDLink STN adapters (MX+ over
 * Bluetooth, EX over USB) — the '06 Outback 3.0R's powertrain CAN, which has NO
 * `7E0`/`7E8` diagnostic channel (ROM-confirmed) and so can only be *monitored*,
 * never requested. This is the `CanBroadcast` / Monitor mode.
 *
 * The channel is already on CAN (the manager ran the SSM2-over-CAN init at
 * connect). Here we flip it into a raw monitor: headers on (so each line carries
 * its CAN arbitration ID), then ride the STN's all-frame monitor [STMA]. Every
 * distinct monitor line is recorded into the RAW BYTES log — that is the "data
 * angle": which IDs are present on the bus and which bytes move. Frames are
 * deduped per CAN ID so a steady signal doesn't flood the log; a changing signal
 * (RPM, throttle…) logs each change, which is what makes correlation possible.
 *
 * Gauge decode is intentionally NOT attempted: broadcast frames are proprietary
 * packed signals, not SSM2 address reads, so there is no Ssm2Pid mapping yet
 * (that needs a broadcast CAN-ID → signal map built from a real capture — this
 * source is how that capture gets made). Live Data therefore shows the link as
 * alive (heartbeat samples) with empty gauges; the bytes live in the log.
 *
 * Safety: monitor-only. It never transmits onto the bus (no request frame is
 * ever sent), which is the whole point of keeping CanBroadcast a separate
 * protocol selection from request/response CAN.
 */
class ObdLinkCanBroadcastSource(
    private val transport: ObdLinkBtTransport
) : LiveSampleSource {

    override fun initChannel(): Boolean {
        // Raw monitor config, sent while the '>' prompt is still active (before
        // the monitor starts). Best-effort: a rejected command is logged by the
        // transport and we continue — STMA streams regardless of CAF/spaces.
        for (cmd in MONITOR_SETUP) {
            try { transport.sendAscii(cmd, timeoutMs = 600L) } catch (_: Exception) {}
        }
        return true
    }

    override fun updatePids(pids: List<Ssm2Pid>) { /* no PID decode in monitor mode */ }

    override fun close() {
        try { transport.stopMonitor() } catch (_: Exception) {}
    }

    override fun startFlow(intervalMs: Long): Flow<PollSample> = flow {
        if (!initChannel()) return@flow
        transport.stopMonitor()              // no-op if not monitoring
        transport.beginMonitor(MONITOR_CMD)  // STMA — no '>' until 0x0D

        val lastById = HashMap<String, String>()
        val pending = StringBuilder()
        var sawAny = false
        var sinceEmit = 0L
        try {
            while (true) {
                delay(MONITOR_POLL_MS)
                sinceEmit += MONITOR_POLL_MS
                val raw = transport.takeBuffered()
                if (raw.isNotEmpty()) {
                    // The STN restarts the prompt on a glitch — re-arm the monitor.
                    val died = raw.contains("STOPPED") || raw.contains("ERROR") || raw.contains("?")
                    pending.append(raw)
                    var nl = indexOfLineEnd(pending)
                    while (nl >= 0) {
                        val line = pending.substring(0, nl).trim()
                        pending.delete(0, nl + 1)
                        if (isFrameLine(line)) {
                            sawAny = true
                            // Dedup per CAN ID (first whitespace token, else the
                            // leading chars) so steady frames don't flood but each
                            // changing signal still logs every change.
                            val id = line.substringBefore(' ').take(8)
                            if (lastById[id] != line) {
                                lastById[id] = line
                                ObdLinkTrafficLog.record("IN", "CAN $line")
                            }
                        }
                        nl = indexOfLineEnd(pending)
                    }
                    if (died) {
                        transport.stopMonitor()
                        pending.setLength(0)
                        transport.beginMonitor(MONITOR_CMD)
                    }
                }
                // Heartbeat: emit an empty sample on a slow cadence so Live Data
                // shows the monitor is alive without churning the gauge merge.
                if (sinceEmit >= HEARTBEAT_MS) {
                    sinceEmit = 0
                    emit(
                        PollSample(
                            timestampMs = System.currentTimeMillis(),
                            values = emptyMap(),
                            rawValues = IntArray(0),
                            wireMs = 0,
                            ecmOk = sawAny,
                            tcmOk = false
                        )
                    )
                }
            }
        } finally {
            transport.stopMonitor()
        }
    }

    /** Index of the next CR or LF in [sb], or -1 if no full line yet. */
    private fun indexOfLineEnd(sb: StringBuilder): Int {
        for (i in sb.indices) if (sb[i] == '\r' || sb[i] == '\n') return i
        return -1
    }

    /** A frame line is non-empty and starts with a hex digit (the CAN ID). Status
     *  echoes ('>', 'STOPPED', 'SEARCHING…') are skipped. */
    private fun isFrameLine(line: String): Boolean {
        if (line.isEmpty()) return false
        val c = line[0]
        return (c in '0'..'9' || c in 'A'..'F' || c in 'a'..'f')
    }

    private companion object {
        // Headers on (CAN ID per line), spaces on (readable), CAN auto-format off
        // (raw frames, no ISO-TP reassembly so every frame shows).
        private val MONITOR_SETUP = listOf("ATH1", "ATS1", "ATCAF0")
        private const val MONITOR_CMD = "STMA"      // STN monitor-all
        private const val MONITOR_POLL_MS = 20L
        private const val HEARTBEAT_MS = 250L
    }
}
