package com.protocol.app.openport2

import com.protocol.app.obdlink.LiveSampleSource
import com.protocol.app.obdlink.ObdLinkTrafficLog
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Listen-only CAN **broadcast** source for the OpenPort 2.0 — the `CanBroadcast`
 * / Monitor counterpart to [OpenPortCanLiveSource]. Opens CAN channel 6 at
 * 500 kbps with an **accept-all PASS filter** (mask 0 / pattern 0) and then only
 * READS the `ar6` indications the adapter delivers for every received frame — it
 * never sends an `att6` request, so nothing is injected onto the bus.
 *
 * Every distinct received frame (`CAN <id>: <data>`) is recorded into the RAW
 * BYTES log, deduped per CAN ID, so a changing signal logs each change — the
 * "data angle" for the '06 Outback's listen-only powertrain CAN. No Ssm2Pid
 * decode (broadcast frames aren't address reads); gauges stay empty and Live
 * Data shows the link alive via heartbeat samples.
 *
 * NOTE: the OpenPort `atf` filter grammar is reverse-engineered from captures.
 * The PASS-filter form here ([FILTER_CMD] + zero mask/pattern) is best-effort —
 * if no frames appear, the adapter's reply in the BYTES log shows whether the
 * filter command was accepted, and the exact form can be corrected there.
 */
class OpenPortCanBroadcastSource(
    private val io: TactrixIo
) : LiveSampleSource {

    private var nextReqId = 2
    private var channelOpened = false

    override fun initChannel(): Boolean {
        if (channelOpened) return true
        io.drain()
        write("ati\r\n")
        io.readUntil(1500L) { buf -> String(buf, StandardCharsets.US_ASCII).contains("ar") }

        if (!command("ata", 1500L)) return false
        if (!command("ato$CHANNEL 0 $BAUD 0")) return false
        if (!command("ats$CHANNEL 3 0")) return false
        if (!command("ats$CHANNEL 30 0")) return false
        if (!command("ats$CHANNEL 31 0")) return false
        if (!command("ats$CHANNEL 34 65535")) return false
        if (!command("ats$CHANNEL 35 65535")) return false
        if (!command("ats$CHANNEL 37 0")) return false

        // Accept-all PASS filter: mask 0 + pattern 0 = every CAN ID passes.
        val passTail = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0)
        if (!commandBinary("atf$CHANNEL $FILTER_CMD", passTail)) {
            ObdLinkTrafficLog.record("OUT", "· op2: accept-all filter rejected — monitor may see nothing")
        }
        channelOpened = true
        return true
    }

    override fun updatePids(pids: List<Ssm2Pid>) { /* no PID decode in monitor mode */ }

    override fun close() { channelOpened = false }

    override fun startFlow(intervalMs: Long): Flow<PollSample> = flow {
        if (!initChannel()) return@flow
        val lastById = HashMap<String, String>()
        var sawAny = false
        while (true) {
            val rr = try {
                // Passive read window: never-true predicate, so it returns
                // whatever frames arrived within the window.
                io.readUntil(MONITOR_WINDOW_MS) { false }
            } catch (e: UsbDisconnectedException) {
                channelOpened = false
                throw e
            }
            for ((id, data) in parseFrames(rr.bytes)) {
                sawAny = true
                if (lastById[id] != data) {
                    lastById[id] = data
                    ObdLinkTrafficLog.record("IN", "CAN $id: $data")
                }
            }
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

    /** Walk every `ar6` indication in [buf] → (canIdHex, dataHex). Mirrors the
     *  framing in [OpenPortCanLiveSource] but keeps ALL IDs, not just 7E8. */
    private fun parseFrames(buf: ByteArray): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val marker = "ar$CHANNEL".toByteArray(StandardCharsets.US_ASCII)
        var pos = 0
        while (pos <= buf.size - marker.size) {
            val at = indexOf(buf, marker, pos) ?: break
            val lenIdx = at + marker.size
            if (lenIdx >= buf.size) break
            val len = buf[lenIdx].toInt() and 0xFF
            val flagsIdx = lenIdx + 1
            val frameEnd = flagsIdx + len
            if (len < AR6_HEADER_LEN || frameEnd > buf.size) { pos = lenIdx; continue }
            val canIdStart = flagsIdx + 1 + 4
            val dataStart = canIdStart + 4
            if (dataStart <= frameEnd && canIdStart + 4 <= buf.size) {
                val canId = buf.copyOfRange(canIdStart, canIdStart + 4)
                val data = buf.copyOfRange(dataStart, frameEnd)
                out.add(hex(canId) to hex(data))
            }
            pos = frameEnd
        }
        return out
    }

    private fun hex(b: ByteArray): String = b.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
        if (needle.isEmpty() || from > haystack.size - needle.size) return null
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return null
    }

    private fun write(ascii: String) = io.write(ascii.toByteArray(StandardCharsets.US_ASCII)).let {}

    private fun command(body: String, timeoutMs: Long = 1500L): Boolean {
        val reqId = nextReqId++
        write("$body $reqId\r\n")
        return io.readUntil(timeoutMs) { buf -> String(buf, StandardCharsets.US_ASCII).contains("ar") }.matched
    }

    private fun commandBinary(body: String, tail: ByteArray, timeoutMs: Long = 1500L): Boolean {
        val reqId = nextReqId++
        val ascii = "$body $reqId\r\n".toByteArray(StandardCharsets.US_ASCII)
        io.write(ascii + tail)
        return io.readUntil(timeoutMs) { buf -> String(buf, StandardCharsets.US_ASCII).contains("ar") }.matched
    }

    private companion object {
        const val CHANNEL = 6
        const val BAUD = 500_000
        // PASS filter (J2534 type 1) with a 2-word (mask, pattern) tail.
        private const val FILTER_CMD = "1 64 4"
        private const val AR6_HEADER_LEN = 9
        // Passive read window per emit cycle (ms).
        private const val MONITOR_WINDOW_MS = 200L
    }
}
