package com.protocol.app.obdlink

import com.protocol.app.openport2.NoEcuResponseException
import com.protocol.app.openport2.PollSample
import com.protocol.app.openport2.Ssm2Address
import com.protocol.app.openport2.Ssm2Pid
import com.protocol.app.openport2.Ssm2PidCategory
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * [LiveSampleSource] backed by the OBDLink MX+ over Bluetooth (SSM2-over-CAN).
 *
 * Each cycle: build the raw A8 read payload for the on-page addresses, send it
 * as ELM hex over [ObdLinkBtTransport] (the adapter wraps it in ISO-TP to
 * 0x7E0 and reassembles the 0x7E8 reply), parse the E8 response, and decode
 * per-PID into a [PollSample] — identical in shape to the USB poller's output,
 * so the gauges and session log don't care which adapter produced it.
 *
 * Scope: ECM (0x7E0/0x7E8) only for now. TCM-over-CAN uses a different CAN ID
 * and is a later add (the ELM header would switch per module); TCM PIDs are
 * filtered out here so a mixed layout still reads its ECM gauges.
 */
class ObdLinkLiveSource(
    private val transport: ObdLinkBtTransport,
    pids: List<Ssm2Pid>
) : LiveSampleSource {

    @Volatile
    private var ecmPids: List<Ssm2Pid> = pids.filter { it.category == Ssm2PidCategory.ECU }

    private var consecutiveNoReply = 0
    // Addresses the ECU rejects (7F / NO DATA). Excluded from future batches so a
    // single unsupported address can't poison a whole STPX batch. Per-connection
    // (reset when the source is rebuilt on reconnect); newly-added PIDs aren't in
    // the set, so they still get evaluated without clearing it.
    private val deadAddrKeys = HashSet<Int>()

    /** The ELM channel is already set up by [ObdLinkBtManager.connect]. */
    override fun initChannel(): Boolean = true

    override fun updatePids(pids: List<Ssm2Pid>) {
        ecmPids = pids.filter { it.category == Ssm2PidCategory.ECU }
    }

    override fun close() {}

    override fun startFlow(intervalMs: Long): Flow<PollSample> = flow {
        consecutiveNoReply = 0
        while (true) {
            val cycleStart = System.currentTimeMillis()
            pollOnce()?.let { emit(it) }
            // intervalMs is the target cycle PERIOD, not additive idle: sleep
            // only the remainder after the STPX wire time. So a 100 ms interval
            // is a 100 ms cycle (not 100 + ~21 wire), and a low interval floors
            // the cycle at the wire time itself (~21 ms = ~45 Hz).
            val remaining = intervalMs - (System.currentTimeMillis() - cycleStart)
            if (remaining > 0) delay(remaining)
        }
    }

    private fun pollOnce(): PollSample? {
        val pids = ecmPids
        val addresses = pids.flatMap { it.addresses }
        if (addresses.isEmpty()) return null

        val wireStart = System.currentTimeMillis()
        // raw keeps a slot for EVERY address (dead/unread stay 0) so buildSample
        // stays aligned to the PID layout.
        val raw = IntArray(addresses.size)

        // Batch reads via STPX. Bare ELM hex caps at a 7-byte single frame, so a
        // multi-address A8 (>7 bytes) gets rejected with '?'. STPX is the STN's
        // native send: it ISO-TP-segments the request itself (multi-frame TX). We
        // chunk to MAX_BATCH addresses so each reply (E8 + <=6 bytes) stays a
        // single frame — works on any init. ~6x+ fewer round trips.
        //
        // Gotcha handled here: one unsupported address makes the ECU 7F the WHOLE
        // batch (NRC 0x12), zeroing the good addresses with it. So skip already-
        // dead addresses, and on a fresh rejection fall back to per-address reads
        // to salvage the supported ones + mark the dead. The page self-cleans
        // back to full batch speed after a cycle or two.
        val live = addresses.withIndex().filter { keyOf(it.value) !in deadAddrKeys }
        var anyOk = false
        for (group in live.chunked(MAX_BATCH)) {
            val batch = readBatch(group.map { it.value })
            if (batch != null) {
                group.forEachIndexed { i, iv -> raw[iv.index] = batch[i] }
                anyOk = true
            } else {
                for (iv in group) {
                    val one = readSingle(iv.value)
                    if (one != null) { raw[iv.index] = one; anyOk = true }
                    else deadAddrKeys.add(keyOf(iv.value))
                }
            }
        }

        if (anyOk) {
            consecutiveNoReply = 0
        } else {
            consecutiveNoReply++
            if (consecutiveNoReply >= MAX_CONSECUTIVE_NO_REPLY) {
                throw NoEcuResponseException(
                    "No ECU response after $MAX_CONSECUTIVE_NO_REPLY consecutive polls"
                )
            }
            return null
        }
        return buildSample(pids, raw, wireStart)
    }

    /** One STPX batch read: the STN multi-frame-sends the A8 payload and returns
     *  one byte per address, or null on '?' / 7F / NO DATA. */
    private fun readBatch(chunk: List<Ssm2Address>): IntArray? {
        if (chunk.isEmpty()) return IntArray(0)
        val reqHex = ObdLinkSsm2Can.toElmHex(ObdLinkSsm2Can.buildReadPayload(chunk))
        val ascii = try {
            transport.sendAscii("STPX d:$reqHex, t:500, r:1", timeoutMs = 700L)
        } catch (e: Exception) {
            return null
        }
        return ObdLinkSsm2Can.parseReadResponse(ObdLinkSsm2Can.parseElmHex(ascii), chunk.size)
    }

    /** Single-address read (bare hex single frame) to salvage a poisoned batch
     *  and identify the unsupported address. Null = no reply for this address. */
    private fun readSingle(addr: Ssm2Address): Int? {
        val reqHex = ObdLinkSsm2Can.toElmHex(ObdLinkSsm2Can.buildReadPayload(listOf(addr)))
        val ascii = try {
            transport.sendAscii(reqHex, timeoutMs = 400L)
        } catch (e: Exception) {
            return null
        }
        return ObdLinkSsm2Can.parseReadResponse(ObdLinkSsm2Can.parseElmHex(ascii), 1)?.getOrNull(0)
    }

    private fun keyOf(a: Ssm2Address): Int =
        ((a.high.toInt() and 0xFF) shl 16) or
            ((a.mid.toInt() and 0xFF) shl 8) or (a.low.toInt() and 0xFF)

    private fun buildSample(pids: List<Ssm2Pid>, raw: IntArray, wireStart: Long): PollSample {
        val values = HashMap<String, Double>()
        val rawValues = ArrayList<Int>(raw.size)
        var offset = 0
        for (pid in pids) {
            val slice = raw.copyOfRange(offset, offset + pid.addresses.size)
            values[pid.id] = pid.decode(slice)
            rawValues.addAll(slice.toList())
            offset += pid.addresses.size
        }
        return PollSample(
            timestampMs = System.currentTimeMillis(),
            values = values,
            rawValues = rawValues.toIntArray(),
            wireMs = System.currentTimeMillis() - wireStart,
            ecmOk = true,
            tcmOk = true
        )
    }

    private companion object {
        // Consecutive no-reply requests before declaring "no ECU". A present
        // ECU answers the first request, so a run this long = not on bus.
        private const val MAX_CONSECUTIVE_NO_REPLY = 8

        // Max addresses per STPX batch. Capped so the reply (E8 + N bytes) stays
        // a single ISO-TP frame (1 + N <= 7 -> N <= 6) — no receive-side flow
        // control needed, so batching works on any init (Fast or Standard).
        private const val MAX_BATCH = 6
    }
}
