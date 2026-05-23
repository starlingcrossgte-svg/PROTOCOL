package com.protocol.app.openport2

import com.protocol.app.EcuLogger
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Decoded values for one polling cycle.
 *
 * [values] is keyed by [Ssm2Pid.id]. [rawValues] is one unsigned-int byte
 * per address in the same order as the batched A8 request.
 */
data class PollSample(
    val timestampMs: Long,
    val values: Map<String, Double>,
    val rawValues: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PollSample) return false
        if (timestampMs != other.timestampMs) return false
        if (values != other.values) return false
        if (!rawValues.contentEquals(other.rawValues)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + values.hashCode()
        result = 31 * result + rawValues.contentHashCode()
        return result
    }
}

/**
 * Outcome of a single poll cycle. Lets the flow loop tell apart:
 *  - a clean sample (emit and reset miss counter)
 *  - a corrupt/incomplete SSM2 frame (recoverable — K-line glitch, log and retry)
 *  - a transport miss with the adapter (recoverable — att3 timed out / no ar3, log and retry)
 *
 * A real USB detach is NOT a [PollResult] — it propagates as
 * [UsbDisconnectedException] out of [pollOnce], the same as before.
 */
sealed class PollResult {
    data class Sample(val sample: PollSample) : PollResult()
    data class BadFrame(val reason: String) : PollResult()
    data class Transport(val reason: String) : PollResult()
}

/**
 * Polls a set of SSM2 parameters in a tight loop by sending repeated A8
 * (read address) queries over the already-initialized Tactrix channel.
 *
 * Usage:
 *   1. Call [Ssm2EcmProbe.initializeChannel] once to set up the adapter.
 *   2. Create a [Ssm2Poller] with the same [TactrixClient].
 *   3. Collect [startFlow] — each emission is one decoded poll sample.
 *   4. Cancel the collecting coroutine to stop.
 *
 * All addresses from all PIDs are batched into a single A8 request per
 * cycle, matching the approach observed in the RomRaider wireshark capture.
 */
class Ssm2Poller(
    private val client: TactrixClient,
    pids: List<Ssm2Pid>
) {
    private companion object {
        private const val ATT_TIMEOUT_MICROS = 400_000L
        private const val DEFAULT_MAX_CONSECUTIVE_MISSES = 5
    }

    // PIDs split by module so we can issue per-module A8 queries. ECM and
    // TCM have separate SSM2 address spaces — a single batched query has to
    // target one or the other. Order: ECM PIDs first, then TCM PIDs. The
    // raw-byte stream and the decode loop both use this ordering so slices
    // align by position.
    private val ecmPids: List<Ssm2Pid> = pids.filter { it.category == Ssm2PidCategory.ECU }
    private val tcmPids: List<Ssm2Pid> = pids.filter { it.category == Ssm2PidCategory.TCM }
    private val orderedPids: List<Ssm2Pid> = ecmPids + tcmPids
    private val ecmAddresses: List<Ssm2Address> = ecmPids.flatMap { it.addresses }
    private val tcmAddresses: List<Ssm2Address> = tcmPids.flatMap { it.addresses }

    /**
     * Sends one (single-module) or two (mixed ECM+TCM) batched A8 queries
     * and classifies the response.
     *
     * Returns [PollResult.Sample] on a clean decode. Returns [PollResult.BadFrame]
     * when bytes arrived but the SSM2 frame is truncated, fails checksum, or
     * isn't a valid A8 response — these are recoverable on K-line. Returns
     * [PollResult.Transport] when the adapter ack/ar3 frame never showed up.
     *
     * @throws UsbDisconnectedException if the underlying bulk transfer fails.
     */
    fun pollOnce(): PollResult {
        if (ecmAddresses.isEmpty() && tcmAddresses.isEmpty()) {
            return PollResult.Transport("no addresses configured")
        }

        val ecmBytes = if (ecmAddresses.isNotEmpty()) {
            when (val r = queryModule(ecmAddresses, Ssm2AddressQuery.DEST_ECM)) {
                is ModuleQueryResult.Ok -> r.bytes
                is ModuleQueryResult.BadFrame -> return PollResult.BadFrame("ECM: ${r.reason}")
                is ModuleQueryResult.Transport -> return PollResult.Transport("ECM: ${r.reason}")
            }
        } else IntArray(0)

        val tcmBytes = if (tcmAddresses.isNotEmpty()) {
            when (val r = queryModule(tcmAddresses, Ssm2AddressQuery.DEST_TCM)) {
                is ModuleQueryResult.Ok -> r.bytes
                is ModuleQueryResult.BadFrame -> return PollResult.BadFrame("TCM: ${r.reason}")
                is ModuleQueryResult.Transport -> return PollResult.Transport("TCM: ${r.reason}")
            }
        } else IntArray(0)

        val rawValues = ecmBytes + tcmBytes
        val values = mutableMapOf<String, Double>()
        var offset = 0
        for (pid in orderedPids) {
            val slice = rawValues.copyOfRange(offset, offset + pid.addresses.size)
            values[pid.id] = pid.decode(slice)
            offset += pid.addresses.size
        }

        return PollResult.Sample(
            PollSample(
                timestampMs = System.currentTimeMillis(),
                values = values,
                rawValues = rawValues
            )
        )
    }

    private sealed class ModuleQueryResult {
        data class Ok(val bytes: IntArray) : ModuleQueryResult()
        data class BadFrame(val reason: String) : ModuleQueryResult()
        data class Transport(val reason: String) : ModuleQueryResult()
    }

    private fun queryModule(addresses: List<Ssm2Address>, destination: Byte): ModuleQueryResult {
        val queryBytes = Ssm2AddressQuery.buildA8Query(addresses, destination)
        val outcome = client.sendAsciiPlusBinary(
            asciiBodyWithoutReqId = "att3 ${queryBytes.size} 0 $ATT_TIMEOUT_MICROS",
            binaryTail = queryBytes,
            appendReqId = true,
            expectVehicleFrameOnChannel = K_LINE_CHANNEL,
            readTimeoutMs = 1000L
        )
        if (!outcome.matched) {
            return ModuleQueryResult.Transport("att3 read timed out before ar3 frame")
        }
        val raw = TactrixHex.parseHexPayload(outcome.responseHex.replace(" ", ""))
        val vehicleFrame = client.extractVehicleFrame(raw, K_LINE_CHANNEL)
            ?: return ModuleQueryResult.Transport("no ar3 wrapper found in adapter response")
        val parsed = Ssm2FrameParser.parseSsm2Frame(vehicleFrame)
            ?: return ModuleQueryResult.BadFrame("ssm2 reply too short to parse header (${vehicleFrame.size} bytes)")
        if (parsed.truncated) {
            return ModuleQueryResult.BadFrame(
                "ssm2 reply truncated (got ${parsed.rawBytes.size} of ${parsed.length + 5} bytes)"
            )
        }
        if (!parsed.checksumValid) {
            return ModuleQueryResult.BadFrame(
                "ssm2 checksum mismatch (rx=0x%02X)".format(parsed.checksum)
            )
        }
        val rawValues = Ssm2AddressQuery.parseA8Response(parsed, addresses.size)
            ?: return ModuleQueryResult.BadFrame("A8 response payload invalid (code or length mismatch)")
        return ModuleQueryResult.Ok(rawValues)
    }

    /**
     * Emits poll samples until the collecting coroutine is cancelled, the
     * USB device disconnects, or [maxConsecutiveMisses] back-to-back misses
     * (any mix of bad frames and transport misses) accumulate.
     *
     * A single dropped K-line frame no longer breaks the flow — one good
     * sample resets the miss counter. UsbDisconnectedException propagates
     * out of the flow unchanged so the ViewModel can show "USB disconnected".
     */
    fun startFlow(
        intervalMs: Long = 200L,
        maxConsecutiveMisses: Int = DEFAULT_MAX_CONSECUTIVE_MISSES
    ): Flow<PollSample> = flow {
        var consecutiveMisses = 0
        while (true) {
            when (val result = pollOnce()) {
                is PollResult.Sample -> {
                    consecutiveMisses = 0
                    emit(result.sample)
                }
                is PollResult.BadFrame -> {
                    consecutiveMisses++
                    EcuLogger.comm(
                        "poller: bad frame ($consecutiveMisses/$maxConsecutiveMisses) — ${result.reason}"
                    )
                    if (consecutiveMisses >= maxConsecutiveMisses) {
                        EcuLogger.error("poller: $maxConsecutiveMisses consecutive misses — stopping")
                        client.channelInitialized = false
                        break
                    }
                }
                is PollResult.Transport -> {
                    consecutiveMisses++
                    EcuLogger.comm(
                        "poller: transport miss ($consecutiveMisses/$maxConsecutiveMisses) — ${result.reason}"
                    )
                    if (consecutiveMisses >= maxConsecutiveMisses) {
                        EcuLogger.error("poller: $maxConsecutiveMisses consecutive misses — stopping")
                        client.channelInitialized = false
                        break
                    }
                }
            }
            if (intervalMs > 0) delay(intervalMs)
        }
    }
}
