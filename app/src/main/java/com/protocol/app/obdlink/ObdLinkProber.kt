package com.protocol.app.obdlink

/**
 * General "try sequences until one works" prober for the OBDLink Bluetooth
 * path. Drives an already-connected [ObdLinkBtTransport] and logs every step
 * to [ObdLinkTrafficLog] (the Developer console). Reusable for init-variant
 * hunting, address discovery, DTC-read sequences, etc.
 *
 * Flow per candidate: drain the buffer -> send its setup commands -> send the
 * probe request -> classify the reply with the caller's predicate. Stop at the
 * FIRST hit and return it (channel left as-is for inspection); otherwise report
 * exhausted.
 *
 * Primary use right now: find the STN init that flips the MX+ into raw K-line
 * mode for SSM2 on the EZ30R/3.0R. The SSM2 frames themselves are known-good;
 * the prober cycles candidate inits against the real ECU until it answers
 * an SSM2 reply (header 80 F0 10) — the ECU is the pass/fail oracle.
 */

/** One thing to try: a label, ELM/STN setup commands to send first, then the probe request (hex). */
data class ObdLinkProbeCandidate(
    val label: String,
    val setupCommands: List<String>,
    val probeRequestHex: String
)

sealed class ObdLinkProbeOutcome {
    data class Hit(
        val candidate: ObdLinkProbeCandidate,
        val replyHex: String,
        val rawAscii: String
    ) : ObdLinkProbeOutcome()

    data class Exhausted(val tried: Int) : ObdLinkProbeOutcome()
}

/** Reply-success predicates for the prober. */
object ObdLinkProbeClassifier {
    private val FAIL_TOKENS = listOf(
        "?", "NO DATA", "STOPPED", "BUFFER FULL", "CAN ERROR", "BUS INIT: ERROR",
        "BUS ERROR", "ERROR", "UNABLE", "SEARCHING", "ACT ALERT", "FB ERROR"
    )

    private fun hasError(ascii: String): Boolean {
        val up = ascii.uppercase()
        return FAIL_TOKENS.any { up.contains(it) }
    }

    private fun containsHeader(bytes: ByteArray, b0: Int, b1: Int, b2: Int): Boolean {
        for (i in 0..bytes.size - 3) {
            if ((bytes[i].toInt() and 0xFF) == b0 &&
                (bytes[i + 1].toInt() and 0xFF) == b1 &&
                (bytes[i + 2].toInt() and 0xFF) == b2
            ) return true
        }
        return false
    }

    /** CAN read hit: no error and the stripped hex starts with 0xE8 (A8 reply). */
    fun canReadHit(ascii: String): Boolean {
        if (hasError(ascii)) return false
        val bytes = ObdLinkSsm2Can.parseElmHex(ascii)
        return bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) == 0xE8
    }

    /** K-line hit: no error and the reply carries the SSM2 reply header 80 F0 10. */
    fun klineReplyHit(ascii: String): Boolean {
        if (hasError(ascii)) return false
        return containsHeader(ObdLinkSsm2Can.parseElmHex(ascii), 0x80, 0xF0, 0x10)
    }
}

class ObdLinkAutoProber(private val transport: ObdLinkBtTransport) {

    /**
     * Run [candidates] in order, stopping at the first whose reply satisfies
     * [isHit]. Returns the winner (channel left as-is) or Exhausted.
     */
    fun run(
        candidates: List<ObdLinkProbeCandidate>,
        isHit: (String) -> Boolean,
        setupTimeoutMs: Long = 1500L,
        probeTimeoutMs: Long = 3000L
    ): ObdLinkProbeOutcome {
        for (c in candidates) {
            transport.drain()
            ObdLinkTrafficLog.record("OUT", "· [probe] try: ${c.label}")
            for (cmd in c.setupCommands) transport.sendAscii(cmd, setupTimeoutMs)
            val reply = transport.sendAscii(c.probeRequestHex, probeTimeoutMs)
            if (isHit(reply)) {
                ObdLinkTrafficLog.record("OUT", "· [probe] HIT: ${c.label}")
                val parsedHex = ObdLinkSsm2Can.toElmHex(ObdLinkSsm2Can.parseElmHex(reply))
                return ObdLinkProbeOutcome.Hit(c, parsedHex, reply)
            }
            ObdLinkTrafficLog.record("OUT", "· [probe] miss: ${c.label}")
        }
        return ObdLinkProbeOutcome.Exhausted(candidates.size)
    }
}

object ObdLinkProbeCandidates {

    /** SSM2-over-CAN init variants (bench ECU). Probe = single-address A8 read. */
    fun ssm2CanInitMatrix(): List<ObdLinkProbeCandidate> = listOf(
        ObdLinkProbeCandidate("A base / single addr", listOf("ATSP6", "ATSH7E0", "ATCRA7E8"), "A800000008"),
        ObdLinkProbeCandidate("B +ATAL / single addr", listOf("ATAL", "ATSP6", "ATSH7E0", "ATCRA7E8"), "A800000008"),
        ObdLinkProbeCandidate("C +ATCAF1 / single addr", listOf("ATCAF1", "ATSP6", "ATSH7E0", "ATCRA7E8"), "A800000008"),
        ObdLinkProbeCandidate("E no FC override / single addr", listOf("ATFCSM0", "ATSP6", "ATSH7E0", "ATCRA7E8"), "A800000008")
    )

    /**
     * SSM2-over-K-line init variants (EZ30R / 3.0R). Probe = the SSM2
     * read-ECU-ID frame `80 10 F0 01 BF 40` — a hit is the ECU answering with
     * an `80 F0 10 …` reply. Best-effort spread of ELM + STN K-line entries;
     * the ECU confirms which one actually opens the bus in raw mode.
     */
    fun klineInitMatrix(): List<ObdLinkProbeCandidate> {
        val id = "8010F001BF40"
        fun c(label: String, vararg setup: String) = ObdLinkProbeCandidate(label, setup.toList(), id)
        // FRPM-derived (OBDLink STN protocol presets, "ISO 9141 and ISO 14230-4" table):
        //   STP21 = ISO 9141 (no header, no autoinit) — raw mode: we send the full SSM2
        //           frame ourselves and the adapter skips the 5-baud/fast init handshake
        //           that SSM2 doesn't use (this is the wall the ELM ATSP presets hit).
        //   STP23 = ISO 14230-4 (no autoinit) — same idea on the KWP physical layer.
        //   STPBR4800 = K-line @ 4800 baud (SSM2 runs at 4800, not the 10400 auto-detect uses).
        //   STPCB0    = automatic check-byte OFF — the SSM2 frame already carries its own
        //               checksum (req ends 0x40, reply ends 0x85); let it pass through raw.
        //   STPO      = open the protocol without an init handshake.
        // STPC first closes any (possibly NVM-persisted) protocol so the candidate is tested
        // cleanly. Reply is matched as ASCII hex "80F010..." by klineReplyHit.
        return listOf(
            // K-line SSM2 init for the STN (OBDLink) — opens a raw ISO-9141 channel at the
            // 4800 baud SSM2 uses, with no auto-init handshake; the complete SSM2 frame is
            // then sent verbatim. All commands are from the OBDLink/STN command set:
            //   STP 21      ISO 9141, no header, no auto-init
            //   STIMCS 1    STN ISO message setting
            //   STPBR 4800  K-line baud = 4800
            //   ATAL        allow long (>7-byte) messages
            //   STIP4 0     transmit interbyte timing = 0 ms
            c("STN K-line (STP21 / 4800 / no autoinit)",
                "STP 21", "STIMCS 1", "STPBR 4800", "ATAL", "STIP4 0"),
            // Fallback: try whatever protocol is already loaded on the adapter, no setup.
            c("loaded protocol, no setup")
        )
    }
}
