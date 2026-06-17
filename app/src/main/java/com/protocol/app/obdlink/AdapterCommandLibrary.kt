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
/** Difficulty/safety tier used to GROUP init sequences in the dev console. */
enum class CommandTier { BASIC, ADVANCED, AGGRESSIVE }

data class CommandSequence(
    val id: String,
    val name: String,
    val description: String,
    /** True = raw K-line init, false = ISO-TP CAN init. Picks which list the
     *  selector shows for the active protocol. */
    val kline: Boolean,
    val steps: List<InitStep>,
    /** Grouping tier in the init dropdown (BASIC default). */
    val tier: CommandTier = CommandTier.BASIC
) {
    companion object {
        /** Build a sequence from plain command strings (no baud hooks). [tier]
         *  is a named arg after the vararg (defaults BASIC). */
        fun of(
            id: String, name: String, description: String, kline: Boolean,
            vararg commands: String,
            tier: CommandTier = CommandTier.BASIC
        ) = CommandSequence(id, name, description, kline, commands.map { InitStep(it) }, tier)
    }
}

/** Which adapter family a command targets. The dev console drives only one
 *  family at a time (whatever adapter is selected), so the palette filters by
 *  this and the manual-command router formats by it. Three real sets:
 *   - ELM      = OBDLink BT + OBDLink EX (ELM/STN ASCII).
 *   - OpenPort = Tactrix line protocol.
 *   - Kkl      = FT232RL dumb cable — no command language, raw SSM2 frames only. */
enum class CommandFamily { ELM, OpenPort, Kkl }

/**
 * How a command's text has to reach the wire — the crux of "what is hex vs
 * text". The router ([normalizeElm] / OpenPortConsole) keys off this:
 *
 *  - TEXT      = an adapter CONTROL command (`AT*` / `ST*` for ELM/STN, `at*`
 *                for OpenPort). Sent as ASCII verbatim; internal spaces are
 *                SIGNIFICANT (e.g. `STPX d:..., r:1`), so they're preserved.
 *  - HEX_FRAME = a raw bus/SSM2 frame given as hex bytes. Whitespace is
 *                INSIGNIFICANT: for ELM the spaces are stripped to a continuous
 *                ASCII-hex string the STN parses into bytes; for OpenPort the
 *                hex is parsed to RAW BINARY and wrapped in an `att` header.
 */
enum class CommandKind { TEXT, HEX_FRAME }

/**
 * A single tappable command for the Dev-panel quick-pick palette. Tapping one
 * drops [command] straight into the manual command box so the user can fire
 * commands one-by-one without typing. [label] is a dim hint shown beside it.
 * [family] decides which adapter shows it; [kind] decides how it's normalized
 * and routed onto the wire.
 */
data class QuickCommand(
    val command: String,
    val label: String,
    val family: CommandFamily = CommandFamily.ELM,
    val kind: CommandKind = CommandKind.TEXT,
    /** Section the command sits under in the palette (e.g. "SETUP · AT"). The
     *  palette renders one dim header per group, in first-seen order, so listing
     *  the entries in group order here is all that's needed. */
    val group: String = ""
)

object AdapterCommandLibrary {

    /** OBDLink (ELM/STN) palette, grouped for the manual command box. Order of
     *  the sections: the SETUP groups first (AT, then STN, then STPX request
     *  format), then the fire-after-init CONTINUOUS sends, the CAN MONITOR arm
     *  commands, and finally SINGLE-address poll frames. The palette renders one
     *  dim header per [QuickCommand.group] in this order. */
    val QUICK_COMMANDS: List<QuickCommand> = listOf(
        // ── SETUP · AT ── ELM control commands: reset, link config, CAN setup,
        //                  flow control, timing.
        QuickCommand("ATI", "adapter / ELM version", group = "SETUP · AT"),
        QuickCommand("ATZ", "full reset", group = "SETUP · AT"),
        QuickCommand("ATWS", "warm start", group = "SETUP · AT"),
        QuickCommand("ATD", "restore defaults", group = "SETUP · AT"),
        QuickCommand("ATE0", "echo off", group = "SETUP · AT"),
        QuickCommand("ATL0", "linefeeds off", group = "SETUP · AT"),
        QuickCommand("ATS0", "spaces off", group = "SETUP · AT"),
        QuickCommand("ATH1", "headers on", group = "SETUP · AT"),
        QuickCommand("ATH0", "headers off", group = "SETUP · AT"),
        QuickCommand("ATAL", "allow long messages", group = "SETUP · AT"),
        QuickCommand("ATCAF0", "CAN auto-format off (raw)", group = "SETUP · AT"),
        QuickCommand("ATCAF1", "CAN auto-format on", group = "SETUP · AT"),
        QuickCommand("ATSP6", "ISO 15765 CAN 11/500", group = "SETUP · AT"),
        QuickCommand("ATSH7E0", "tx header = ECM", group = "SETUP · AT"),
        QuickCommand("ATSH7E1", "tx header = TCM", group = "SETUP · AT"),
        QuickCommand("ATCRA7E8", "rx filter = ECM", group = "SETUP · AT"),
        QuickCommand("ATCRA7E9", "rx filter = TCM", group = "SETUP · AT"),
        QuickCommand("ATFCSH7E0", "flow-control header", group = "SETUP · AT"),
        QuickCommand("ATFCSD300000", "flow-control data", group = "SETUP · AT"),
        QuickCommand("ATFCSM1", "flow-control mode 1", group = "SETUP · AT"),
        QuickCommand("ATFCSM0", "flow-control mode 0", group = "SETUP · AT"),
        QuickCommand("ATAT0", "adaptive timing off", group = "SETUP · AT"),
        QuickCommand("ATAT2", "adaptive timing aggressive", group = "SETUP · AT"),
        QuickCommand("ATST10", "timeout 64 ms", group = "SETUP · AT"),
        QuickCommand("ATST08", "timeout 32 ms", group = "SETUP · AT"),
        // ── SETUP · STN ── STN-specific: identity, K-line bring-up (ISO9141,
        //                   SSM2 @ 4800), UART baud, and K-line timing levers.
        QuickCommand("STI", "STN firmware version", group = "SETUP · STN"),
        QuickCommand("@1", "device description", group = "SETUP · STN"),
        QuickCommand("STPRS", "protocol status", group = "SETUP · STN"),
        QuickCommand("STP 21", "K-line: ISO9141, no header/autoinit", group = "SETUP · STN"),
        QuickCommand("STIMCS 1", "K-line: self-checksum (auto off)", group = "SETUP · STN"),
        QuickCommand("STPBR 4800", "K-line: SSM2 baud 4800", group = "SETUP · STN"),
        QuickCommand("STPBR115200", "UART 115200", group = "SETUP · STN"),
        QuickCommand("STPBR2000000", "UART 2 Mbaud", group = "SETUP · STN"),
        QuickCommand("STIP4 0", "P4: TX interbyte 0 ms", group = "SETUP · STN"),
        QuickCommand("STIP1X 2", "P1: RX interbyte 2 ms (stream edge)", group = "SETUP · STN"),
        QuickCommand("STIP1X 5", "P1: RX interbyte 5 ms (aggressive)", group = "SETUP · STN"),
        QuickCommand("STIP1X 10", "P1: RX interbyte 10 ms", group = "SETUP · STN"),
        QuickCommand("STIP1X 20", "P1: RX interbyte 20 ms (safe)", group = "SETUP · STN"),
        QuickCommand("STIAT 0", "adaptive P1-max OFF (manual)", group = "SETUP · STN"),
        QuickCommand("STIAT 1", "adaptive P1-max ON (default)", group = "SETUP · STN"),
        QuickCommand("STIP3 0A", "P3 inter-msg gap tight (hex)", group = "SETUP · STN"),
        QuickCommand("STIP3 20", "P3 inter-msg gap smooth", group = "SETUP · STN"),
        QuickCommand("STP301", "P3 inter-msg gap 4 ms", group = "SETUP · STN"),
        QuickCommand("STPTO 200", "OBD request timeout 200 ms", group = "SETUP · STN"),
        // ── SETUP · STPX ── the STPX request grammar (d: data, r: replies,
        //                    t: timeout). Templates to build a precise send.
        QuickCommand("STPX d:8010F008A80000000E00000F4D", "STPX send only (no receive)", group = "SETUP · STPX"),
        QuickCommand("STPX d:8010F008A80000000E00000F4D,r:1", "STPX send, wait 1 reply", group = "SETUP · STPX"),
        QuickCommand("STPX d:8010F008A80000000E00000F4D,r:1,t:1000", "STPX send, 1 reply, 1 s timeout", group = "SETUP · STPX"),
        // ── CONTINUOUS / STREAM ── fire AFTER init; the ECU firehoses replies
        //    off one request (A8 01). Watch the RAW BYTES log fill with E8 frames.
        QuickCommand("8010F008A80100000E00000F4E", "RPM continuous (A8 01)", kind = CommandKind.HEX_FRAME, group = "CONTINUOUS / STREAM"),
        QuickCommand(
            "8010F02CA80100000E00000F00000800001C000046000113000012FF2578FF2579FF257AFF257B00002200003C00003D13",
            "full-page continuous (A8 01)", kind = CommandKind.HEX_FRAME, group = "CONTINUOUS / STREAM"
        ),
        QuickCommand("STPX d:8010F008A80100000E00000F4E,r:25,t:3000", "RPM continuous burst x25", group = "CONTINUOUS / STREAM"),
        QuickCommand(
            "STPX d:8010F02CA80100000E00000F00000800001C000046000113000012FF2578FF2579FF257AFF257B00002200003C00003D13,r:25,t:3000",
            "full-page continuous burst x25", group = "CONTINUOUS / STREAM"
        ),
        // ── CAN MONITOR ── listen-only sniff of a broadcast bus (the '06 3.0R
        //    powertrain CAN). Pair with ATH1 + ATCAF0 above.
        QuickCommand("STM", "start bus monitor", group = "CAN MONITOR"),
        QuickCommand("STMA", "monitor ALL frames", group = "CAN MONITOR"),
        // ── SINGLE POLL ── one-address K-line reads (full SSM2 80-frame +
        //    checksum) — pull a value once. ECU ID (BF) lives here too.
        QuickCommand("8010F001BF40", "ECU ID + capability bitmap (BF)", kind = CommandKind.HEX_FRAME, group = "SINGLE POLL"),
        QuickCommand("8010F005A80000000835", "coolant 0x08", kind = CommandKind.HEX_FRAME, group = "SINGLE POLL"),
        QuickCommand("8010F005A80000001C49", "battery 0x1C", kind = CommandKind.HEX_FRAME, group = "SINGLE POLL"),
        QuickCommand("8010F005A8000000123F", "IAT 0x12", kind = CommandKind.HEX_FRAME, group = "SINGLE POLL"),
        QuickCommand("8010F005A80000011341", "oil temp 0x113", kind = CommandKind.HEX_FRAME, group = "SINGLE POLL"),
        QuickCommand("8010F008A80000000E00000F4D", "RPM single 0x0E/0x0F (A8 00)", kind = CommandKind.HEX_FRAME, group = "SINGLE POLL"),
        QuickCommand(
            "8010F02CA80000000E00000F00000800001C000046000113000012FF2578FF2579FF257AFF257B00002200003C00003D12",
            "full-page single (A8 00)", kind = CommandKind.HEX_FRAME, group = "SINGLE POLL"
        )
    )

    // ── OpenPort 2.0 (Tactrix) quick commands ────────────────────────────
    // The Tactrix line protocol: lowercase `at*` verbs, auto-incrementing
    // reqId appended by the console. `ati` takes no reqId. The K-line / CAN
    // channel-open steps mirror the proven init the live path runs. Hex frames
    // here are bare SSM2 bytes — the console parses them to binary and wraps
    // them in the `att<ch>` header for whichever protocol is selected.
    val OPENPORT_QUICK_COMMANDS: List<QuickCommand> = listOf(
        // ── SETUP · LINK ──
        QuickCommand("ati", "adapter info (no reqid)", CommandFamily.OpenPort, group = "SETUP · LINK"),
        QuickCommand("ata", "reset/abort channels", CommandFamily.OpenPort, group = "SETUP · LINK"),
        QuickCommand("atv", "adapter voltage", CommandFamily.OpenPort, group = "SETUP · LINK"),
        // ── SETUP · K-LINE ── channel-open (SSM2 @ 4800 on Tactrix channel 3)
        QuickCommand("ato3 512 4800 0", "open ch3 @ 4800", CommandFamily.OpenPort, group = "SETUP · K-LINE"),
        QuickCommand("ats3 1 0", "channel setting", CommandFamily.OpenPort, group = "SETUP · K-LINE"),
        // ── SETUP · CAN ── channel-open (ISO15765 @ 500k on Tactrix channel 6)
        QuickCommand("ato6 0 500000 0", "open ch6 @ 500k", CommandFamily.OpenPort, group = "SETUP · CAN"),
        QuickCommand("ats6 3 0", "channel setting", CommandFamily.OpenPort, group = "SETUP · CAN"),
        QuickCommand("ats6 34 65535", "IOCTL", CommandFamily.OpenPort, group = "SETUP · CAN"),
        QuickCommand("ats6 35 65535", "IOCTL", CommandFamily.OpenPort, group = "SETUP · CAN"),
        // ── CONTINUOUS / STREAM ── full 80-frame A8 01; the console wraps it in
        //    the att<ch> header for the selected protocol. ECU streams replies.
        QuickCommand("8010F008A80100000E00000F4E", "RPM continuous (A8 01)", CommandFamily.OpenPort, CommandKind.HEX_FRAME, group = "CONTINUOUS / STREAM"),
        QuickCommand(
            "8010F02CA80100000E00000F00000800001C000046000113000012FF2578FF2579FF257AFF257B00002200003C00003D13",
            "full-page continuous (A8 01)", CommandFamily.OpenPort, CommandKind.HEX_FRAME, group = "CONTINUOUS / STREAM"
        ),
        // ── SINGLE POLL ── K-line: type the FULL 80-frame. CAN: type the SSM2
        //    payload (A8 00 <addr>); the console prepends the 7E0 request ID.
        QuickCommand("8010F008A80000000E00000F4D", "K-line RPM single (full frame)", CommandFamily.OpenPort, CommandKind.HEX_FRAME, group = "SINGLE POLL"),
        QuickCommand("A800000008", "CAN read coolant (payload)", CommandFamily.OpenPort, CommandKind.HEX_FRAME, group = "SINGLE POLL"),
        QuickCommand("A80000000E", "CAN read RPM hi (payload)", CommandFamily.OpenPort, CommandKind.HEX_FRAME, group = "SINGLE POLL")
    )

    // ── FT232RL (KKL dumb cable) quick commands ───────────────────────────
    // The cable has NO command language of its own — the phone is the protocol
    // master — so every entry is a raw SSM2 K-line frame (full 80-header +
    // checksum). The console sends the bytes straight onto the K-line and reads
    // the reply (src = request dest, byte 1). No AT/ST commands apply.
    val KKL_QUICK_COMMANDS: List<QuickCommand> = listOf(
        // ── READ / IDENTITY ──
        QuickCommand("8010F001BF40", "ECU ID + capability bitmap (BF)", CommandFamily.Kkl, CommandKind.HEX_FRAME, group = "READ / IDENTITY"),
        // ── CONTINUOUS / STREAM ── A8 01 arms the ECU's own stream; the KKL
        //    continuous source rides it. Fire after a connect.
        QuickCommand("8010F008A80100000E00000F4E", "RPM continuous (A8 01)", CommandFamily.Kkl, CommandKind.HEX_FRAME, group = "CONTINUOUS / STREAM"),
        QuickCommand(
            "8010F02CA80100000E00000F00000800001C000046000113000012FF2578FF2579FF257AFF257B00002200003C00003D13",
            "full-page continuous (A8 01)", CommandFamily.Kkl, CommandKind.HEX_FRAME, group = "CONTINUOUS / STREAM"
        ),
        // ── SINGLE POLL ── one-address reads (A8 00).
        QuickCommand("8010F005A80000000835", "coolant 0x08", CommandFamily.Kkl, CommandKind.HEX_FRAME, group = "SINGLE POLL"),
        QuickCommand("8010F005A80000001C49", "battery 0x1C", CommandFamily.Kkl, CommandKind.HEX_FRAME, group = "SINGLE POLL"),
        QuickCommand("8010F005A8000000123F", "IAT 0x12", CommandFamily.Kkl, CommandKind.HEX_FRAME, group = "SINGLE POLL"),
        QuickCommand("8010F005A80000011341", "oil temp 0x113", CommandFamily.Kkl, CommandKind.HEX_FRAME, group = "SINGLE POLL"),
        QuickCommand("8010F008A80000000E00000F4D", "RPM single 0x0E/0x0F (A8 00)", CommandFamily.Kkl, CommandKind.HEX_FRAME, group = "SINGLE POLL"),
        QuickCommand(
            "8010F02CA80000000E00000F00000800001C000046000113000012FF2578FF2579FF257AFF257B00002200003C00003D12",
            "full-page single (A8 00)", CommandFamily.Kkl, CommandKind.HEX_FRAME, group = "SINGLE POLL"
        )
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
        "ATAT0", "ATST10",
        tier = CommandTier.ADVANCED
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
        ),
        tier = CommandTier.ADVANCED
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
        "ATE0", "ATL0", "ATS0", "STP 21", "STIMCS 1", "STPBR 4800", "ATAL", "STIP4 0", "STIAT 0", "STIP1X 5", "STIP3 0A", "ATAT 2",
        tier = CommandTier.AGGRESSIVE
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
        "ATE0", "ATL0", "ATS0", "STP 21", "STIMCS 1", "STPBR 4800", "ATAL", "STIP4 0", "STIAT 0", "STIP1X 2", "ATAT 2",
        tier = CommandTier.AGGRESSIVE
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

    // ── Manual-command routing helpers (the hex-vs-text / spacing engine) ──

    /** Quick-command palette entries for one adapter family. */
    fun quickCommandsFor(family: CommandFamily): List<QuickCommand> = when (family) {
        CommandFamily.ELM -> QUICK_COMMANDS
        CommandFamily.OpenPort -> OPENPORT_QUICK_COMMANDS
        CommandFamily.Kkl -> KKL_QUICK_COMMANDS
    }

    /**
     * Best-effort classify a free-typed command. It's a HEX_FRAME only if, after
     * removing whitespace, the WHOLE string is hex digits and an even number of
     * them (a whole number of bytes). Anything with a letter that isn't a hex
     * digit — `ATSH7E0`, `STPX d:...`, `ati`, `ato6 ...` — is TEXT. Palette
     * entries carry an explicit [QuickCommand.kind] so they never rely on this.
     */
    fun classifyKind(input: String): CommandKind {
        val compact = input.filter { !it.isWhitespace() }
        val isHex = compact.length >= 2 && compact.length % 2 == 0 &&
            compact.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }
        return if (isHex) CommandKind.HEX_FRAME else CommandKind.TEXT
    }

    /**
     * Format a manual command for an ELM/STN (OBDLink) adapter — the OBDLink
     * half of the spacing rule. A control command (TEXT) is sent verbatim
     * (trimmed) so significant spaces survive (`STPX d:..., r:1`); a HEX_FRAME
     * has ALL whitespace stripped to a continuous upper-case hex string the STN
     * parses into bytes. So "80 10 F0 08" and "8010F008" both reach the wire as
     * "8010F008", while "STPX d:..." is left intact.
     */
    fun normalizeElm(input: String, kind: CommandKind = classifyKind(input)): String = when (kind) {
        CommandKind.TEXT -> input.trim()
        CommandKind.HEX_FRAME -> input.filter { !it.isWhitespace() }.uppercase()
    }

    /**
     * Parse a hex string (whitespace ignored) into bytes, or null if it has an
     * odd number of hex digits or any non-hex character. Used by OpenPortConsole
     * to turn a typed HEX_FRAME into the raw binary tail of an `att` frame.
     */
    fun hexToBytes(input: String): ByteArray? {
        val h = input.filter { !it.isWhitespace() }
        if (h.isEmpty() || h.length % 2 != 0) return null
        val out = ByteArray(h.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(h[i * 2], 16)
            val lo = Character.digit(h[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
