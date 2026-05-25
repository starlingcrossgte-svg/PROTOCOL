package com.protocol.app.obdlink

/**
 * DORMANT groundwork (added 2026-05-25) — NOT wired into any UI or ViewModel
 * yet, by design. Nothing references this file; it compiles inert.
 *
 * A general "try sequences until one works" prober for the OBDLink Bluetooth
 * path. It drives an already-connected [ObdLinkBtTransport] and logs every
 * step to [ObdLinkTrafficLog] (the Developer console), so when it IS wired up
 * later the dev page already narrates the whole run.
 *
 * Built deliberately general — the same engine can later brute-force unknown
 * parameter addresses, try alternate DTC-read sequences, hunt init variants,
 * etc. For now it just exists, ready to be triggered from a dev-mode action.
 *
 * Flow (per the agreed design): for each candidate -> drain the adapter
 * buffer -> send its setup commands -> send its probe request -> classify the
 * reply. Stop at the FIRST hit and return it (channel left as-is so the result
 * can be inspected); otherwise report exhausted.
 */

/** One thing to try: a label, ELM/STN setup commands to send first, and the probe request (hex). */
data class ObdLinkProbeCandidate(
    val label: String,
    val setupCommands: List<String>,
    val probeRequestHex: String
)

/** Result of an auto-probe run. */
sealed class ObdLinkProbeOutcome {
    /** First candidate that produced a valid reply, plus the raw + parsed hex for inspection. */
    data class Hit(
        val candidate: ObdLinkProbeCandidate,
        val replyHex: String,
        val rawAscii: String
    ) : ObdLinkProbeOutcome()

    /** Nothing produced a valid reply. */
    data class Exhausted(val tried: Int) : ObdLinkProbeOutcome()
}

/** Judges whether an adapter reply counts as a successful response. */
object ObdLinkProbeClassifier {
    private val FAIL_TOKENS = listOf(
        "?", "NO DATA", "STOPPED", "BUFFER FULL", "CAN ERROR", "ERROR", "UNABLE", "SEARCHING"
    )

    /** Hit = no error token AND the stripped hex starts with [expectedCode] (e.g. 0xE8 for an A8 read). */
    fun isHit(ascii: String, expectedCode: Int): Boolean {
        val up = ascii.uppercase()
        if (FAIL_TOKENS.any { up.contains(it) }) return false
        val bytes = ObdLinkSsm2Can.parseElmHex(ascii)
        return bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) == (expectedCode and 0xFF)
    }
}

class ObdLinkAutoProber(private val transport: ObdLinkBtTransport) {

    /**
     * Run [candidates] in order, stopping at the first hit. [expectedCode] is
     * the response byte that marks success (0xE8 = SSM2 A8 read reply).
     */
    fun run(candidates: List<ObdLinkProbeCandidate>, expectedCode: Int = 0xE8): ObdLinkProbeOutcome {
        for (c in candidates) {
            transport.drain()
            ObdLinkTrafficLog.record("OUT", "· [probe] try: ${c.label}")
            for (cmd in c.setupCommands) transport.sendAscii(cmd, timeoutMs = 800L)
            val reply = transport.sendAscii(c.probeRequestHex, timeoutMs = 1000L)
            if (ObdLinkProbeClassifier.isHit(reply, expectedCode)) {
                ObdLinkTrafficLog.record("OUT", "· [probe] HIT: ${c.label}")
                val parsedHex = ObdLinkSsm2Can.toElmHex(ObdLinkSsm2Can.parseElmHex(reply))
                return ObdLinkProbeOutcome.Hit(c, parsedHex, reply)
            }
            ObdLinkTrafficLog.record("OUT", "· [probe] miss: ${c.label}")
        }
        return ObdLinkProbeOutcome.Exhausted(candidates.size)
    }
}

/**
 * Default candidate matrix for SSM2-over-CAN bring-up — one variable changed
 * per row, ordered by likelihood. The probe requests use a known-safe single
 * address (0x000008) and a 2-address batch to probe the multi-frame boundary;
 * when wired up these would use the page's real addresses.
 */
object ObdLinkProbeCandidates {
    private const val SINGLE_ADDR = "A800000008"          // A8 00 + 0x000008 (5 bytes, single frame)
    private const val TWO_ADDR = "A80000000800001C"       // 8 bytes -> forces multi-frame

    fun ssm2CanInitMatrix(): List<ObdLinkProbeCandidate> = listOf(
        ObdLinkProbeCandidate("A base / single addr", listOf("ATSP6", "ATSH7E0", "ATCRA7E8"), SINGLE_ADDR),
        ObdLinkProbeCandidate("B +ATAL / single addr", listOf("ATAL", "ATSP6", "ATSH7E0", "ATCRA7E8"), SINGLE_ADDR),
        ObdLinkProbeCandidate("C +ATCAF1 / single addr", listOf("ATCAF1", "ATSP6", "ATSH7E0", "ATCRA7E8"), SINGLE_ADDR),
        ObdLinkProbeCandidate(
            "D +ATAL +flowctl / 2 addr",
            listOf("ATAL", "ATSP6", "ATSH7E0", "ATCRA7E8", "ATFCSH7E0", "ATFCSD300000", "ATFCSM1"),
            TWO_ADDR
        ),
        ObdLinkProbeCandidate("E no FC override / single addr", listOf("ATFCSM0", "ATSP6", "ATSH7E0", "ATCRA7E8"), SINGLE_ADDR)
    )
}
