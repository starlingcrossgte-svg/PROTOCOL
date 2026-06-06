package com.protocol.app.obdlink

/**
 * One step of an adapter init sequence: a command to send, plus an optional
 * host UART baud to switch the FTDI to *after* the adapter acknowledges it.
 *
 * The baud hook exists for STPBR-style speed bumps: the adapter changes its
 * own UART rate the moment it acks `STPBR2000000`, so the host must follow
 * immediately or every later reply is garbage. [switchBaudAfter] tells the
 * sequence runner to call [FtdiUsbSerial.setBaud] right after this step's OK.
 */
data class InitStep(
    val command: String,
    val switchBaudAfter: Int? = null
)

/**
 * A named, selectable adapter init sequence.
 *
 * This is the "command library" — a safe, isolated place for hand-tuned
 * command sets, decoupled from the logging and flash paths. Adding an entry
 * to [AdapterCommandLibrary.ALL] makes it selectable in the UI; nothing else
 * needs to change. The runner (in the OBDLink managers) walks [steps], sends
 * each command, and honors any per-step baud switch.
 */
data class CommandSequence(
    val id: String,
    val name: String,
    val description: String,
    /** True = raw K-line init, false = ISO-TP CAN init. Picks which list the
     *  selector shows for the active protocol. */
    val kline: Boolean,
    val steps: List<InitStep>
) {
    companion object {
        /** Build a sequence from plain command strings (no baud hooks). */
        fun of(
            id: String, name: String, description: String, kline: Boolean,
            vararg commands: String
        ) = CommandSequence(id, name, description, kline, commands.map { InitStep(it) })
    }
}

/**
 * A single tappable command for the Dev-panel quick-pick palette. Tapping one
 * drops [command] straight into the manual command box so the user can fire
 * commands one-by-one without typing. [label] is a dim hint shown beside it.
 */
data class QuickCommand(val command: String, val label: String)

object AdapterCommandLibrary {

    /** Flat, scrollable palette for the manual command box. Ordered by use:
     *  identity/reset, link config, CAN setup, timing/speed, reads. */
    val QUICK_COMMANDS: List<QuickCommand> = listOf(
        QuickCommand("ATI", "adapter / ELM version"),
        QuickCommand("STI", "STN firmware version"),
        QuickCommand("@1", "device description"),
        QuickCommand("BF", "ECU ID + capability bitmap"),
        QuickCommand("ATZ", "full reset"),
        QuickCommand("ATWS", "warm start"),
        QuickCommand("ATD", "restore defaults"),
        QuickCommand("ATE0", "echo off"),
        QuickCommand("ATL0", "linefeeds off"),
        QuickCommand("ATS0", "spaces off"),
        QuickCommand("ATH0", "headers off"),
        QuickCommand("ATH1", "headers on"),
        QuickCommand("ATAL", "allow long messages"),
        QuickCommand("ATSP6", "ISO 15765 CAN 11/500"),
        QuickCommand("ATSH7E0", "tx header = ECM"),
        QuickCommand("ATSH7E1", "tx header = TCM"),
        QuickCommand("ATCRA7E8", "rx filter = ECM"),
        QuickCommand("ATCRA7E9", "rx filter = TCM"),
        QuickCommand("ATFCSH7E0", "flow-control header"),
        QuickCommand("ATFCSD300000", "flow-control data"),
        QuickCommand("ATFCSM1", "flow-control mode 1"),
        QuickCommand("ATFCSM0", "flow-control mode 0"),
        QuickCommand("ATAT0", "adaptive timing off"),
        QuickCommand("ATAT2", "adaptive timing aggressive"),
        QuickCommand("ATST10", "timeout 64 ms"),
        QuickCommand("ATST08", "timeout 32 ms"),
        QuickCommand("STP301", "P3 inter-msg gap 4 ms"),
        QuickCommand("STPBR2000000", "UART 2 Mbaud"),
        QuickCommand("STPBR115200", "UART 115200"),
        QuickCommand("STPRS", "protocol status"),
        // ── K-line timing / aggression (ISO 9141, SSM2 @ 4800) ──
        QuickCommand("STP 21", "K-line: ISO9141, no header/autoinit"),
        QuickCommand("STIMCS 1", "K-line: self-checksum (auto off)"),
        QuickCommand("STPBR 4800", "K-line: SSM2 baud 4800"),
        QuickCommand("STIP4 0", "P4: TX interbyte 0 ms"),
        QuickCommand("STIP1X 5", "P1: RX interbyte max 5 ms (aggressive)"),
        QuickCommand("STIP1X 10", "P1: RX interbyte max 10 ms"),
        QuickCommand("STIP1X 20", "P1: RX interbyte max 20 ms (safe)"),
        QuickCommand("STIAT 0", "adaptive P1-max OFF (manual)"),
        QuickCommand("STIAT 1", "adaptive P1-max ON (default)"),
        QuickCommand("STIP3 20", "P3 inter-msg gap — smoothness"),
        QuickCommand("STIP3 0A", "P3 inter-msg gap — push limits (hex)"),
        QuickCommand("STIP3 10", "P3 inter-msg gap — push limits (decimal)"),
        QuickCommand("STPTO 200", "OBD request timeout 200 ms"),
        // ── Polling-technique tests (continuous / monitor) ──
        QuickCommand("STM", "start bus monitor"),
        QuickCommand("STMA", "monitor ALL frames"),
        QuickCommand("STPX d:8010F008A80100000E00000F4E,r:25,t:3000", "RPM continuous burst x25"),
        QuickCommand(
            "STPX d:8010F02CA80100000E00000F00000800001C000046000113000012FF2578FF2579FF257AFF257B00002200003C00003D13,r:25,t:3000",
            "full-page continuous burst x25"
        ),
        // ── EZ30R K-line frames (full SSM2 80-header + checksum) ──
        QuickCommand("8010F008A80000000E00000F4D", "EZ30R RPM single (A8 00)"),
        QuickCommand("8010F008A80100000E00000F4E", "EZ30R RPM continuous (A8 01)"),
        QuickCommand(
            "8010F02CA80000000E00000F00000800001C000046000113000012FF2578FF2579FF257AFF257B00002200003C00003D12",
            "EZ30R full-page single (A8 00)"
        ),
        QuickCommand(
            "8010F02CA80100000E00000F00000800001C000046000113000012FF2578FF2579FF257AFF257B00002200003C00003D13",
            "EZ30R full-page continuous (A8 01)"
        ),
        QuickCommand("A80000000E", "read RPM hi (0x0E)"),
        QuickCommand("A80000000F", "read RPM lo (0x0F)"),
        QuickCommand("A800000008", "read coolant (0x08)"),
        QuickCommand("A80000001C", "read battery (0x1C)"),
        QuickCommand("A800000012", "read IAT (0x12)"),
        QuickCommand("A800000113", "read oil temp (0x113)"),
        QuickCommand("A00000083F", "block read 0x08..0x47 (64B)"),
        QuickCommand("1003", "UDS extended session")
    )

    // ── CAN (ISO-TP, ECM @ 7E0/7E8) ──────────────────────────────────────

    /** Full ISO-TP init with flow control. The proven default — supports
     *  multi-frame replies, so A0 block reads work. ~2.6 Hz single reads. */
    val CAN_STANDARD = CommandSequence.of(
        "can_standard",
        "CAN · Standard (block-read ready)",
        "Full ISO-TP init with flow control. Multi-frame replies work, so A0 block reads are possible. Safe default.",
        kline = false,
        "ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATAL",
        "ATSP6", "ATSH7E0", "ATCRA7E8",
        "ATFCSH7E0", "ATFCSD300000", "ATFCSM1"
    )

    /** Lean single-read init: drops ATAL + flow control (not needed for 1-byte
     *  reads), manual timing, 64 ms timeout. Faster per command, but NO block
     *  reads (multi-frame replies would be rejected). */
    val CAN_FAST = CommandSequence.of(
        "can_fast",
        "CAN · Fast single-read",
        "Lean init, manual timing (ATAT0), 64 ms timeout (ATST10). Single-byte reads only — block reads will fail.",
        kline = false,
        "ATZ", "ATE0", "ATL0", "ATS0",
        "ATSP6", "ATSH7E0", "ATCRA7E8",
        "ATAT0", "ATST10"
    )

    /** 2 Mbaud UART: STPBR bumps the STN<->FTDI link to 2,000,000 for max
     *  throughput. The host FTDI switches to 2 M right after STPBR acks
     *  (switchBaudAfter), or replies would be garbage. Advanced. */
    val CAN_2MBAUD = CommandSequence(
        "can_2mbaud",
        "CAN · 2 Mbaud UART (advanced)",
        "Bumps the USB UART to 2,000,000 for max throughput; the host follows automatically. FRAGILE — the live baud switch can desync the link. Single-read tuned, experimental.",
        kline = false,
        steps = listOf(
            InitStep("ATZ"),
            InitStep("STPBR2000000", switchBaudAfter = 2_000_000),
            InitStep("ATE0"), InitStep("ATL0"), InitStep("ATS0"),
            InitStep("ATSP6"), InitStep("ATSH7E0"), InitStep("ATCRA7E8"),
            InitStep("ATAT0"), InitStep("ATST10")
        )
    )

    // ── K-line (raw STN SSM2 @ 4800) ─────────────────────────────────────

    /** STN raw K-line SSM2 init (the proven EZ30R path). */
    val KLINE_STANDARD = CommandSequence.of(
        "kline_standard",
        "K-line · Standard (SSM2 4800)",
        "STN raw K-line SSM2 init: STP21 / 4800 / STIP4 0. Proven on the EZ30R.",
        kline = true,
        "ATE0", "ATL0", "ATS0", "STP 21", "STIMCS 1", "STPBR 4800", "ATAL", "STIP4 0"
    )

    /** Aggressive K-line timing: P1-max held manually at 5 ms (STIAT 0 + STIP1X 5)
     *  so the STN calls the reply done sooner, + ATAT 2. This is the fast-but-may-
     *  be-too-tight variant — if replies start dropping, back STIP1X up toward
     *  10–20 ms. The lever to find the EZ30R's edge. */
    val KLINE_AGGRESSIVE = CommandSequence.of(
        "kline_aggressive",
        "K-line · Aggressive timing",
        "STP21/4800 + STIP4 0, STIAT 0, STIP1X 5 (5 ms RX interbyte), STIP3 0A (tight P3 gap), ATAT 2. Faster reply-done + inter-message; loosen STIP1X/STIP3 if replies drop.",
        kline = true,
        "ATE0", "ATL0", "ATS0", "STP 21", "STIMCS 1", "STPBR 4800", "ATAL", "STIP4 0", "STIAT 0", "STIP1X 5", "STIP3 0A", "ATAT 2"
    )

    /** Continuous streaming K-line. The on-car-proven combo that lets the STN
     *  keep pace with an A8 01 stream: STIP1X 2 (flush each frame the instant it
     *  lands), STIAT 0, ATAT 2. Selecting this with Auto Init ON flips the poller
     *  into streaming mode — one request, the ECU firehoses replies (~40 Hz vs
     *  ~9 Hz). ECM-only. STIP1X 0 chops frames, 6 falls behind; 2 is the edge. */
    val KLINE_CONTINUOUS = CommandSequence.of(
        "kline_continuous",
        "K-line · Continuous (streaming ~40 Hz)",
        "A8 01 streaming: STIP1X 2 / STIAT 0 / ATAT 2. The poller STREAMS off one request instead of re-asking. ECM-only; ~40 Hz on-car.",
        kline = true,
        "ATE0", "ATL0", "ATS0", "STP 21", "STIMCS 1", "STPBR 4800", "ATAL", "STIP4 0", "STIAT 0", "STIP1X 2", "ATAT 2"
    )

    /** Every sequence, in selector order. Add new tuned inits here. */
    val ALL: List<CommandSequence> = listOf(
        CAN_STANDARD, CAN_FAST, CAN_2MBAUD, KLINE_STANDARD, KLINE_AGGRESSIVE, KLINE_CONTINUOUS
    )

    fun byId(id: String?): CommandSequence? = ALL.firstOrNull { it.id == id }

    /** Selectable sequences for one protocol (CAN vs K-line). */
    fun forProtocol(kline: Boolean): List<CommandSequence> = ALL.filter { it.kline == kline }

    /** Default when the user hasn't picked one. */
    fun defaultFor(kline: Boolean): CommandSequence =
        if (kline) KLINE_STANDARD else CAN_STANDARD
}
