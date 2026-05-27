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
    val rawValues: IntArray,
    /** Wall-clock ms spent on the wire this cycle (queries + responses + parse, excludes the inter-cycle delay). */
    val wireMs: Long = 0L,
    /** True if the ECM query succeeded this cycle. False when the module didn't respond / returned a bad frame. */
    val ecmOk: Boolean = true,
    /** True if the TCM query succeeded this cycle. */
    val tcmOk: Boolean = true
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PollSample) return false
        if (timestampMs != other.timestampMs) return false
        if (values != other.values) return false
        if (!rawValues.contentEquals(other.rawValues)) return false
        if (wireMs != other.wireMs) return false
        if (ecmOk != other.ecmOk) return false
        if (tcmOk != other.tcmOk) return false
        return true
    }

    override fun hashCode(): Int {
        var result = timestampMs.hashCode()
        result = 31 * result + values.hashCode()
        result = 31 * result + rawValues.contentHashCode()
        result = 31 * result + wireMs.hashCode()
        result = 31 * result + ecmOk.hashCode()
        result = 31 * result + tcmOk.hashCode()
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
 * cycle, matching the approach observed in the capture.
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
    //
    // The config is volatile so updatePids() can swap it from a different
    // thread while pollOnce is running. Each pollOnce captures the config
    // at the top of the cycle and uses that snapshot throughout, so the
    // query and the decode always agree even if updatePids fires mid-cycle.
    private data class PidConfig(
        val ecmPids: List<Ssm2Pid>,
        val tcmPids: List<Ssm2Pid>,
        val orderedPids: List<Ssm2Pid>,
        val ecmAddresses: List<Ssm2Address>,
        val tcmAddresses: List<Ssm2Address>
    )

    private fun buildConfig(pids: List<Ssm2Pid>): PidConfig {
        val ecm = pids.filter { it.category == Ssm2PidCategory.ECU }
        val tcm = pids.filter { it.category == Ssm2PidCategory.TCM }
        return PidConfig(
            ecmPids = ecm,
            tcmPids = tcm,
            orderedPids = ecm + tcm,
            ecmAddresses = ecm.flatMap { it.addresses },
            tcmAddresses = tcm.flatMap { it.addresses }
        )
    }

    @Volatile
    private var config: PidConfig = buildConfig(pids)

    /**
     * Swap the polled PID set without restarting the flow. Safe to call
     * from any thread; the running [pollOnce] picks up the new config on
     * its next cycle (this cycle finishes with the old config it captured
     * at entry).
     */
    fun updatePids(newPids: List<Ssm2Pid>) {
        config = buildConfig(newPids)
    }

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
        // Snapshot the config so this cycle's query + decode use a
        // consistent PID set even if updatePids fires mid-cycle.
        val cfg = config
        val ecmAddresses = cfg.ecmAddresses
        val tcmAddresses = cfg.tcmAddresses
        val ecmPids = cfg.ecmPids
        val tcmPids = cfg.tcmPids

        if (ecmAddresses.isEmpty() && tcmAddresses.isEmpty()) {
            return PollResult.Transport("no addresses configured")
        }

        val wireStart = System.currentTimeMillis()

        // Per-module result tracking. A failure in one module no longer
        // aborts the whole cycle — the other module's data still flows.
        // The ViewModel merges the partial values map onto the previous
        // sample's so gauges for the failed module hold their last value.
        var ecmBytes: IntArray? = null
        var tcmBytes: IntArray? = null
        var ecmFailureReason: String? = null
        var tcmFailureReason: String? = null
        var ecmFailureBadFrame = false
        var tcmFailureBadFrame = false

        if (ecmAddresses.isNotEmpty()) {
            when (val r = queryModule(ecmAddresses, Ssm2AddressQuery.DEST_ECM)) {
                is ModuleQueryResult.Ok -> ecmBytes = r.bytes
                is ModuleQueryResult.BadFrame -> {
                    ecmFailureReason = "ECM: ${r.reason}"
                    ecmFailureBadFrame = true
                }
                is ModuleQueryResult.Transport -> ecmFailureReason = "ECM: ${r.reason}"
            }
        }
        if (tcmAddresses.isNotEmpty()) {
            when (val r = queryModule(tcmAddresses, Ssm2AddressQuery.DEST_TCM)) {
                is ModuleQueryResult.Ok -> tcmBytes = r.bytes
                is ModuleQueryResult.BadFrame -> {
                    tcmFailureReason = "TCM: ${r.reason}"
                    tcmFailureBadFrame = true
                }
                is ModuleQueryResult.Transport -> tcmFailureReason = "TCM: ${r.reason}"
            }
        }

        val wireMs = System.currentTimeMillis() - wireStart

        // Both required modules failed → escalate as a real miss so the
        // consecutive-misses counter trips and the loop bails after N
        // dead cycles (UsbDisconnect handles real disconnects elsewhere).
        val ecmRequired = ecmAddresses.isNotEmpty()
        val tcmRequired = tcmAddresses.isNotEmpty()
        val ecmFailed = ecmRequired && ecmBytes == null
        val tcmFailed = tcmRequired && tcmBytes == null
        if (ecmFailed && (tcmFailed || !tcmRequired) && (ecmFailureBadFrame)) {
            return PollResult.BadFrame(ecmFailureReason ?: "ECM failed")
        }
        if (ecmRequired && tcmRequired && ecmFailed && tcmFailed) {
            // Both modules dead this cycle.
            val combined = listOfNotNull(ecmFailureReason, tcmFailureReason).joinToString(" / ")
            return if (ecmFailureBadFrame || tcmFailureBadFrame)
                PollResult.BadFrame(combined)
            else
                PollResult.Transport(combined)
        }
        if (!ecmRequired && tcmFailed) {
            return if (tcmFailureBadFrame)
                PollResult.BadFrame(tcmFailureReason ?: "TCM failed")
            else
                PollResult.Transport(tcmFailureReason ?: "TCM failed")
        }
        if (!tcmRequired && ecmFailed) {
            return if (ecmFailureBadFrame)
                PollResult.BadFrame(ecmFailureReason ?: "ECM failed")
            else
                PollResult.Transport(ecmFailureReason ?: "ECM failed")
        }

        // Partial / full success. Decode only the slots whose module
        // responded. Missing slots simply don't appear in the values map;
        // the ViewModel merges on top of the previous sample so the
        // affected gauges hold their last reading rather than going --.
        val values = mutableMapOf<String, Double>()
        val rawValuesList = mutableListOf<Int>()
        var ecmOffset = 0
        for (pid in ecmPids) {
            if (ecmBytes != null) {
                val slice = ecmBytes.copyOfRange(ecmOffset, ecmOffset + pid.addresses.size)
                values[pid.id] = pid.decode(slice)
                rawValuesList.addAll(slice.toList())
            } else {
                repeat(pid.addresses.size) { rawValuesList.add(0) }
            }
            ecmOffset += pid.addresses.size
        }
        var tcmOffset = 0
        for (pid in tcmPids) {
            if (tcmBytes != null) {
                val slice = tcmBytes.copyOfRange(tcmOffset, tcmOffset + pid.addresses.size)
                values[pid.id] = pid.decode(slice)
                rawValuesList.addAll(slice.toList())
            } else {
                repeat(pid.addresses.size) { rawValuesList.add(0) }
            }
            tcmOffset += pid.addresses.size
        }

        return PollResult.Sample(
            PollSample(
                timestampMs = System.currentTimeMillis(),
                values = values,
                rawValues = rawValuesList.toIntArray(),
                wireMs = wireMs,
                ecmOk = !ecmFailed,
                tcmOk = !tcmFailed
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
        // The SSM2 reply's source byte mirrors our request's destination —
        // pass `destination` as expectedReplySource so the readUntil predicate
        // and frame extractor recognize the right module's reply (80 F0 10
        // for ECM, 80 F0 18 for TCM).
        val outcome = client.sendAsciiPlusBinary(
            asciiBodyWithoutReqId = "att3 ${queryBytes.size} 0 $ATT_TIMEOUT_MICROS",
            binaryTail = queryBytes,
            appendReqId = true,
            expectVehicleFrameOnChannel = K_LINE_CHANNEL,
            expectedReplySource = destination,
            readTimeoutMs = 1000L
        )
        if (!outcome.matched) {
            return ModuleQueryResult.Transport("att3 read timed out before ar3 frame")
        }
        val raw = TactrixHex.parseHexPayload(outcome.responseHex.replace(" ", ""))
        val vehicleFrame = client.extractVehicleFrame(raw, K_LINE_CHANNEL, destination)
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
