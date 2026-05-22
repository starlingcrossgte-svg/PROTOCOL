package com.protocol.app.openport2

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
    private val pids: List<Ssm2Pid>
) {
    private companion object {
        private const val ATT_TIMEOUT_MICROS = 400_000L
        private const val CHANNEL = 3
    }

    private val allAddresses: List<Ssm2Address> = pids.flatMap { it.addresses }
    private val addressCount: Int = allAddresses.size

    /**
     * Sends one batched A8 query and decodes the response.
     * Returns null on transport failure or unparseable response.
     */
    fun pollOnce(): PollSample? {
        if (allAddresses.isEmpty()) return null
        val queryBytes = Ssm2AddressQuery.buildA8Query(allAddresses)
        val outcome = client.sendAsciiPlusBinary(
            asciiBodyWithoutReqId = "att3 ${queryBytes.size} 0 $ATT_TIMEOUT_MICROS",
            binaryTail = queryBytes,
            appendReqId = true,
            expectVehicleFrameOnChannel = CHANNEL,
            readTimeoutMs = 1000L
        )
        if (!outcome.matched) return null

        val raw = TactrixHex.parseHexPayload(outcome.responseHex.replace(" ", ""))
        val vehicleFrame = client.extractVehicleFrame(raw, CHANNEL) ?: return null
        val parsed = Ssm2FrameParser.parseSsm2Frame(vehicleFrame) ?: return null
        val rawValues = Ssm2AddressQuery.parseA8Response(parsed, addressCount) ?: return null

        val values = mutableMapOf<String, Double>()
        var offset = 0
        for (pid in pids) {
            val slice = rawValues.copyOfRange(offset, offset + pid.addresses.size)
            values[pid.id] = pid.decode(slice)
            offset += pid.addresses.size
        }

        return PollSample(
            timestampMs = System.currentTimeMillis(),
            values = values,
            rawValues = rawValues
        )
    }

    /**
     * Emits poll samples until the collecting coroutine is cancelled or a
     * transport error occurs (null from [pollOnce]).
     */
    fun startFlow(intervalMs: Long = 200L): Flow<PollSample> = flow {
        while (true) {
            val sample = pollOnce() ?: break
            emit(sample)
            if (intervalMs > 0) delay(intervalMs)
        }
    }
}
