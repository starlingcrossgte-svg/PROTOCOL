package com.protocol.app.protocol

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import com.protocol.app.firmware.BeefKernelReadClient
import com.protocol.app.firmware.BeefKernelWriteClient
import com.protocol.app.firmware.FirmwareCanTransport
import com.protocol.app.firmware.KernelImagePrep
import com.protocol.app.firmware.KernelProtocol
import com.protocol.app.firmware.KernelReadClient
import com.protocol.app.firmware.UdsBootloaderClient
import com.protocol.app.kkl.KklKlineManager
import java.io.File
import com.protocol.app.obdlink.AdapterCommandLibrary
import com.protocol.app.obdlink.ObdLinkBtManager
import com.protocol.app.obdlink.ObdLinkSsm2Can
import com.protocol.app.obdlink.ObdLinkTrafficLog
import com.protocol.app.obdlink.ObdLinkUsbManager
import com.protocol.app.openport2.K_LINE_CHANNEL
import com.protocol.app.openport2.OpenPortCanBroadcastSource
import com.protocol.app.openport2.OpenPortConsole
import com.protocol.app.openport2.Ssm2AddressQuery
import com.protocol.app.openport2.Ssm2PidCategory
import com.protocol.app.openport2.Ssm2Pids
import com.protocol.app.openport2.TactrixClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Narrow bridge the [DevConsoleController] uses to borrow the live link that
 * [ProtocolViewModel] still owns. Only the pieces the dev tools need — NOT the
 * ViewModel's full surface. The ViewModel keeps owning the adapter managers and
 * the poll job; this contract just lets the console reach them.
 *
 * When connection ownership later moves to its own controller, that owner
 * becomes the host and this interface is its contract — so the split stays
 * forward-compatible.
 */
internal interface DevConsoleHost {
    /** Scope the dev op launches its coroutine on (the ViewModel's scope). */
    val scope: CoroutineScope

    /** Current UI state — read for the selected adapter / protocol / layout. */
    val state: ProtocolUiState

    /** Stop & join any running poll/probe/DTC job so a manual op owns the link. */
    suspend fun takeOverLink()

    fun setConnectionStatus(status: ConnectionStatus, message: String = "")
    fun setStatusMessage(message: String)

    // Borrowed transports / managers — the ViewModel still owns the fields.
    fun tactrixClient(): TactrixClient?
    fun kklManager(): KklKlineManager?
    fun obdLinkBtManager(): ObdLinkBtManager?
    fun obdLinkExManager(): ObdLinkUsbManager?

    /** Get-or-create the BT manager — the console can bring Bluetooth up itself. */
    fun ensureObdLinkBtManager(appContext: Context): ObdLinkBtManager
    fun clearObdLinkBtManager()
    fun clearObdLinkExManager()
}

/**
 * Dev Mode → Raw Command Interface operations, extracted from [ProtocolViewModel].
 *
 * These are the dev/diagnostic console ops: the K-line init hunt, the adapter
 * factory-reset, the manual command + sequence runner, the one-shot K-line
 * continuous test, and the passive CAN monitor. None of them are part of the
 * Live Data poll path — they take over the shared link for a single action and
 * report into the RAW BYTES log. The controller holds no state of its own; it
 * borrows the active link from the [host].
 */
internal class DevConsoleController(private val host: DevConsoleHost) {

    // Active listen-only monitor read loop for the OpenPort path (the OBDLink
    // path rides its own STMA reader thread instead). STOP / stopCanMonitor
    // cancels it. KKL has no CAN bus, so it never sets this.
    @Volatile private var monitorJob: Job? = null

    /**
     * Utility: factory-reset the paired OBDLink adapter over Bluetooth. Sends
     * ATPP FF OFF (clear all programmable parameters / NVM-persisted config),
     * ATD (restore default settings), ATZ (full reset). Returns the adapter to a
     * known factory state, clearing any persisted protocol/PP config. The adapter
     * reboots after ATZ, so the manager is discarded; the next connect builds a fresh one.
     */
    fun resetObdLinkAdapter(appContext: Context) {
        val isEx = host.state.settings.adapter == Adapter.OBDLinkEx
        host.setConnectionStatus(
            ConnectionStatus.PermissionRequired(if (isEx) "OBDLink EX" else "OBDLink"),
            "Resetting adapter…"
        )
        host.scope.launch(Dispatchers.IO) {
            if (isEx) {
                // The EX can't reopen its own USB link — it must already be
                // connected for the reset to reach the STN.
                val mgr = host.obdLinkExManager()
                if (mgr?.transport == null) {
                    host.setConnectionStatus(
                        ConnectionStatus.Error("OBDLink EX not connected"),
                        "Connect the EX first (Settings → OBDLink EX), then reset."
                    )
                    return@launch
                }
                val r = mgr.resetAdapter()
                // ATZ reboots the STN; the manager is torn down so the next
                // connect re-runs the baud sweep against the reset adapter.
                mgr.disconnect()
                host.clearObdLinkExManager()
                when (r) {
                    is ObdLinkUsbManager.ConnectResult.Connected ->
                        host.setConnectionStatus(
                            ConnectionStatus.NoDevice,
                            "OBDLink EX factory reset sent (ATPP FF OFF / ATD / ATZ) — reconnect from Settings."
                        )
                    is ObdLinkUsbManager.ConnectResult.Failure ->
                        host.setConnectionStatus(ConnectionStatus.Error(r.reason), r.reason)
                }
                return@launch
            }
            val mgr = host.ensureObdLinkBtManager(appContext)
            val r = mgr.resetAdapter()
            host.clearObdLinkBtManager() // adapter reboots after ATZ — force a fresh manager next time
            when (r) {
                is ObdLinkBtManager.ConnectResult.Connected ->
                    host.setConnectionStatus(
                        ConnectionStatus.NoDevice,
                        "OBDLink factory reset sent (ATPP FF OFF / ATD / ATZ) — power-cycle the adapter, then re-pair. See Developer BT log."
                    )
                is ObdLinkBtManager.ConnectResult.Failure ->
                    host.setConnectionStatus(ConnectionStatus.Error(r.reason), r.reason)
            }
        }
    }

    /**
     * Dev console: send a raw command typed in the Home BYTES box, routed to
     * whichever adapter is selected and auto-formatted for it (the reply lands
     * in the RAW BYTES log).
     *
     *  - OBDLink (BT / EX): ELM/STN ASCII. A hex frame is normalized to
     *    continuous hex (spaces stripped); a control command keeps its spaces.
     *    If nothing is connected (BT only), a raw channel is opened first
     *    (ATE0/ATL0/ATS0, no protocol init) so the console works standalone.
     *  - OpenPort 2.0: the Tactrix line protocol via [OpenPortConsole] — an
     *    `at*` verb is sent with an auto reqId; a hex frame is parsed to binary
     *    and wrapped in the `att<ch>` transmit header for the selected protocol.
     *
     * Either way, a running poll/log loop is cancelled and joined first so the
     * manual write can't interleave with the loop on the same link.
     */
    fun sendManualCommand(command: String, appContext: Context) {
        val cmd = command.trim()
        if (cmd.isEmpty()) return
        val adapter = host.state.settings.adapter
        host.scope.launch(Dispatchers.IO) {
            // Take over the link: stop any running poll/log loop and WAIT for it
            // to fully finish before we write, so the two can't collide.
            host.takeOverLink()

            when (adapter) {
                Adapter.OpenPort -> sendManualOpenPort(cmd)
                Adapter.OBDLinkEx -> sendManualObdLink(cmd, isEx = true, appContext)
                Adapter.Ft232rl -> sendManualFt232rl(cmd)
                else -> sendManualObdLink(cmd, isEx = false, appContext)  // OBDLink / null
            }
        }
    }

    /** OBDLink (BT/EX) manual command: normalize for the ELM/STN, open a raw
     *  channel if needed (BT only), send, and let the transport log OUT/IN. */
    private suspend fun sendManualObdLink(cmd: String, isEx: Boolean, appContext: Context) {
        // ELM/STN spacing rule: hex frame -> continuous hex; control command ->
        // verbatim (significant spaces preserved, e.g. "STPX d:..., r:1").
        val wire = AdapterCommandLibrary.normalizeElm(cmd)
        ObdLinkTrafficLog.record("OUT", "· manual: $wire")
        var transport = if (isEx) host.obdLinkExManager()?.transport else host.obdLinkBtManager()?.transport
        if (transport == null) {
            if (isEx) {
                // The USB connection can only be opened by the Activity (USB
                // permission), so the console can't bring the EX up itself.
                ObdLinkTrafficLog.record(
                    "OUT", "· OBDLink EX not connected — Settings → tap OBDLink EX first"
                )
                return
            }
            ObdLinkTrafficLog.record("OUT", "· opening raw channel…")
            val mgr = host.ensureObdLinkBtManager(appContext)
            when (val r = mgr.connectBasic()) {
                is ObdLinkBtManager.ConnectResult.Failure -> {
                    ObdLinkTrafficLog.record("OUT", "· connect failed: ${r.reason}")
                    return
                }
                is ObdLinkBtManager.ConnectResult.Connected -> transport = mgr.transport
            }
        }
        try {
            transport?.sendAscii(wire, timeoutMs = 2000L)
        } catch (e: Exception) {
            ObdLinkTrafficLog.record("OUT", "· send failed: ${e.message}")
        }
    }

    /**
     * FT232RL (KKL) manual command. The cable has no command language of its own
     * — there is no ELM/STN to talk to — so a manual command IS a raw SSM2 hex
     * frame, sent straight onto the K-line via [KklKlineManager.transact]. The
     * reply source is the request's destination byte (frame[1]: 0x10 ECM /
     * 0x18 TCM). The connection is owned by the Activity, so this only drives an
     * already-connected cable.
     */
    private fun sendManualFt232rl(cmd: String) {
        fun log(m: String) = ObdLinkTrafficLog.record("OUT", "· $m")
        val mgr = host.kklManager()
        if (mgr?.isConnected() != true) {
            log("kkl: not connected — Dev Mode → FT232RL + K-line, then Connect")
            return
        }
        val bytes = AdapterCommandLibrary.hexToBytes(cmd)
        if (bytes == null || bytes.size < 5 || (bytes[0].toInt() and 0xFF) != 0x80) {
            log("kkl: manual command must be a raw SSM2 hex frame (e.g. 80 10 F0 01 BF 40)")
            return
        }
        val replySource = bytes[1].toInt() and 0xFF   // request dest = reply src
        host.scope.launch(Dispatchers.IO) {
            host.takeOverLink()
            val reply = mgr.transact(bytes, replySource, timeoutMs = 800L)
            if (reply == null) log("kkl: no reply (frame sent, ECU silent / bad checksum)")
        }
    }

    /** OpenPort 2.0 manual command via [OpenPortConsole]. The USB session is
     *  owned by the Activity, so the console can only drive an already-connected
     *  OpenPort (same constraint as the EX). */
    private fun sendManualOpenPort(cmd: String) {
        val client = host.tactrixClient()
        if (client == null) {
            host.setStatusMessage("OpenPort not connected — plug it in & grant USB first")
            return
        }
        // K-line vs CAN transmit framing follows the selected protocol (K-line
        // is the proven default when none is picked).
        val kline = host.state.settings.protocol != BusProtocol.CAN
        // A manual command may reconfigure the channel — invalidate the cached
        // init so the next Read Live re-runs the full ati..atv setup.
        client.channelInitialized = false
        val status = OpenPortConsole.send(client, cmd, kline)
        host.setStatusMessage(status)
    }

    /**
     * Dev: run a manual command SEQUENCE — each non-blank command top-to-bottom
     * on the active adapter, [delaysMs] between them. Each command's reply is
     * reported via [onResponse] (index, text) so the generator can show it beside
     * that row. Uses the page's adapter/protocol; takes over the link first so it
     * can't collide with a running poll.
     */
    fun runManualSequence(
        commands: List<String>,
        delaysMs: List<Long>,
        onResponse: (index: Int, response: String) -> Unit
    ) {
        val adapter = host.state.settings.adapter
        host.scope.launch(Dispatchers.IO) {
            host.takeOverLink()

            for ((i, raw) in commands.withIndex()) {
                val cmd = raw.trim()
                if (cmd.isEmpty()) continue
                val resp = when (adapter) {
                    Adapter.OpenPort -> {
                        val client = host.tactrixClient()
                        if (client == null) "OpenPort not connected"
                        else OpenPortConsole.send(
                            client, cmd, host.state.settings.protocol != BusProtocol.CAN
                        )
                    }
                    Adapter.OBDLink, Adapter.OBDLinkEx -> {
                        val transport = if (adapter == Adapter.OBDLinkEx) host.obdLinkExManager()?.transport
                            else host.obdLinkBtManager()?.transport
                        if (transport == null) "not connected"
                        else {
                            // Wait-for-prompt is implicit in the adapter config —
                            // the STN sendAscii waits for the '>' reply itself.
                            val wire = AdapterCommandLibrary.normalizeElm(cmd)
                            try {
                                transport.sendAscii(wire, timeoutMs = 3000L).trim()
                            } catch (e: Exception) {
                                "send failed: ${e.message}"
                            }
                        }
                    }
                    Adapter.Ft232rl -> {
                        // KKL has no command language — each step is a raw SSM2
                        // hex frame onto the K-line. Reply src = request dest (byte 1).
                        val mgr = host.kklManager()
                        val bytes = AdapterCommandLibrary.hexToBytes(cmd)
                        when {
                            mgr?.isConnected() != true -> "FT232RL not connected"
                            bytes == null || bytes.size < 5 || (bytes[0].toInt() and 0xFF) != 0x80 ->
                                "not a raw SSM2 frame (e.g. 80 10 F0 01 BF 40)"
                            else -> {
                                val src = bytes[1].toInt() and 0xFF
                                val reply = mgr.transact(bytes, src, timeoutMs = 800L)
                                reply?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
                                    ?: "no reply"
                            }
                        }
                    }
                    else -> "pick an adapter first"  // null
                }
                // SnapshotStateList writes are thread-safe; update the row's log.
                onResponse(i, resp)
                // Per-row delay between commands (mostly for baud-switch timing).
                val d = delaysMs.getOrElse(i) { 0L }
                if (d > 0) delay(d)
            }
        }
    }

    /**
     * One-shot K-line CONTINUOUS test. Builds the on-page ECM A8 **01**
     * (respond-continuously) frame from the same PIDs the poller uses — no
     * hand-typed hex to fat-finger — and fires it through STPX with a burst
     * response count, so the ECU streams many replies off a SINGLE request. The
     * stream lands in the BYTES log; a wall of `80 F0 10 .. E8` frames = the STN
     * holds continuous (the OpenPort path). One frame / STOPPED = it doesn't.
     * Does NOT touch the live poller; takes over the link for the test only.
     */
    fun runKlineContinuousTest() {
        host.scope.launch(Dispatchers.IO) {
            host.takeOverLink()

            fun log(m: String) = ObdLinkTrafficLog.record("OUT", "· $m")
            val state = host.state
            // The A8 01 (respond-continuously) frame is built from the same
            // on-page ECM PIDs the poller uses — no hand-typed hex to fat-finger.
            val addrs = Ssm2Pids.DEFAULT_DEMO_PIDS
                .filter { it.id in state.gaugeLayout.pidIds && it.category == Ssm2PidCategory.ECU }
                .flatMap { it.addresses }
            if (addrs.isEmpty()) { log("continuous test: no ECM params on the page"); return@launch }
            val frame = Ssm2AddressQuery.buildA8Query(addrs, Ssm2AddressQuery.DEST_ECM, flags = 0x01)

            when (state.settings.adapter) {
                Adapter.OBDLink, Adapter.OBDLinkEx -> {
                    val transport = if (state.settings.adapter == Adapter.OBDLinkEx)
                        host.obdLinkExManager()?.transport else host.obdLinkBtManager()?.transport
                    if (transport == null) { log("continuous test: connect the OBDLink on K-line first"); return@launch }
                    val hex = ObdLinkSsm2Can.toElmHex(frame)
                    log("continuous test: A8 01 burst (${addrs.size} addrs, r:25) — watch for a wall of E8 frames")
                    try {
                        val reply = transport.sendAscii("STPX d:$hex,r:25,t:3000", timeoutMs = 4000L)
                        val frames = reply.replace(" ", "").replace("\r", "").replace("\n", "")
                            .split("80F010").size - 1
                        log("continuous test: ~$frames frames streamed (>1 = continuous WORKS)")
                        transport.drain()
                    } catch (e: Exception) {
                        log("continuous test failed: ${e.message}")
                    }
                }
                Adapter.OpenPort -> {
                    val client = host.tactrixClient()
                    if (client == null) { log("continuous test: connect the OpenPort on K-line first"); return@launch }
                    log("continuous test: A8 01 on OpenPort K-line (${addrs.size} addrs) — harvesting ~3 s of stream")
                    try {
                        // Arm: one A8 01 request on the K-line channel; the first
                        // streamed reply is matched here.
                        val armed = client.sendAsciiPlusBinary(
                            asciiBodyWithoutReqId = "att$K_LINE_CHANNEL ${frame.size} 0 400000",
                            binaryTail = frame,
                            appendReqId = true,
                            expectVehicleFrameOnChannel = K_LINE_CHANNEL,
                            expectedReplySource = Ssm2AddressQuery.DEST_ECM
                        )
                        var frames = if (armed.matched) 1 else 0
                        // Harvest the remaining streamed replies with NO new
                        // transmit — every frame after the first proves continuous.
                        val deadline = SystemClock.elapsedRealtime() + 3000L
                        while (SystemClock.elapsedRealtime() < deadline) {
                            client.readNextVehicleFrame(K_LINE_CHANNEL, Ssm2AddressQuery.DEST_ECM, 500L)
                                ?: continue
                            frames++
                        }
                        log("continuous test: ~$frames frames streamed (>1 = continuous WORKS)")
                    } catch (e: Exception) {
                        log("continuous test failed: ${e.message}")
                    }
                }
                Adapter.Ft232rl -> {
                    val mgr = host.kklManager()
                    if (mgr?.isConnected() != true) { log("continuous test: connect FT232RL on K-line first"); return@launch }
                    log("continuous test: A8 01 armed on KKL (${addrs.size} addrs) — reading ~3 s of stream")
                    if (!mgr.armStream(frame)) { log("continuous test: stream arm failed"); return@launch }
                    // Pull raw UART for the window; log each chunk so the stream is
                    // visible, and count reply headers (80 F0 10) to score it.
                    val tmp = ByteArray(512)
                    val seen = StringBuilder()
                    val deadline = SystemClock.elapsedRealtime() + 3000L
                    while (SystemClock.elapsedRealtime() < deadline) {
                        val n = mgr.readAvailable(tmp)
                        if (n < 0) break
                        if (n > 0) {
                            val chunk = StringBuilder(n * 2)
                            for (k in 0 until n) chunk.append("%02X".format(tmp[k].toInt() and 0xFF))
                            ObdLinkTrafficLog.record("IN", chunk.toString())
                            seen.append(chunk)
                        } else {
                            delay(5)
                        }
                    }
                    val frames = seen.toString().split("80F010").size - 1
                    log("continuous test: ~$frames frames streamed (>1 = continuous WORKS)")
                    mgr.drain()
                }
                else -> log("continuous test: pick an adapter first")
            }
        }
    }

    /**
     * Dev: passive CAN sniff (listen-only). For the 2006 EZ30R CAN tap, which
     * is **broadcast-only** — the ECM drives 500k CAN but offers NO 7E0/7E8
     * diagnostic channel, so polling can't work; the only thing to do is
     * listen. Sets `ATH1` (so each frame's CAN-ID shows) + `ATCAF0` (raw, no
     * auto-format), then `STMA` (monitor ALL frames, ignores the RX filter,
     * transmits nothing). Broadcast frames stream into the RAW BYTES log via the
     * transport's reader thread. Requires an OBDLink (BT or EX) already
     * connected on CAN; stop with [stopCanMonitor].
     */
    fun startCanMonitor() {
        host.scope.launch(Dispatchers.IO) {
            host.takeOverLink()
            fun log(m: String) = ObdLinkTrafficLog.record("OUT", "· $m")
            when (host.state.settings.adapter) {
                Adapter.OBDLink, Adapter.OBDLinkEx -> {
                    val transport = if (host.state.settings.adapter == Adapter.OBDLinkEx)
                        host.obdLinkExManager()?.transport else host.obdLinkBtManager()?.transport
                    if (transport == null) {
                        log("CAN monitor: connect the OBDLink on CAN first (Dev Mode → adapter + CAN-BUS)")
                        return@launch
                    }
                    log("CAN monitor: ATH1 + STMA (listen-only, all frames) — watch for broadcast frames")
                    try {
                        transport.sendAscii("ATH1", timeoutMs = 800L)   // headers on -> show CAN IDs
                        transport.sendAscii("ATCAF0", timeoutMs = 800L)  // raw frames, no auto-format
                        transport.beginMonitor("STMA")                   // monitor all; no '>' until stopped
                    } catch (e: Exception) {
                        log("CAN monitor failed: ${e.message}")
                    }
                }
                Adapter.OpenPort -> {
                    val client = host.tactrixClient()
                    if (client == null) { log("CAN monitor: connect the OpenPort first"); return@launch }
                    log("CAN monitor: OpenPort ch6 @ 500k, accept-all (listen-only) — watch for broadcast frames")
                    // The monitor reconfigures the adapter to ch6 — invalidate the
                    // cached K-line init so the next live read re-runs setup.
                    client.channelInitialized = false
                    // Reuse the Live Data broadcast source: it opens ch6 with an
                    // accept-all filter and records every distinct frame to the
                    // log. Collect-and-ignore (frames are logged inside) until STOP
                    // cancels the job.
                    val src = OpenPortCanBroadcastSource(client.rawIo)
                    monitorJob?.cancel()
                    monitorJob = host.scope.launch(Dispatchers.IO) {
                        try {
                            src.startFlow(200L).collect { /* frames logged in source */ }
                        } catch (e: Exception) {
                            ObdLinkTrafficLog.record("OUT", "· CAN monitor ended: ${e.message}")
                        } finally {
                            src.close()
                        }
                    }
                }
                Adapter.Ft232rl ->
                    log("CAN monitor: FT232RL is K-line only — no CAN bus to monitor")
                else -> log("CAN monitor: pick an adapter first")
            }
        }
    }

    /** Stop the passive CAN sniff started by [startCanMonitor]: cancels the
     *  OpenPort read loop, and for an OBDLink sends the bare 0x0D that halts
     *  STMA and re-enables the prompt. Safe if not monitoring. */
    fun stopCanMonitor() {
        host.scope.launch(Dispatchers.IO) {
            monitorJob?.cancel()
            monitorJob = null
            val transport = when (host.state.settings.adapter) {
                Adapter.OBDLinkEx -> host.obdLinkExManager()?.transport
                Adapter.OBDLink -> host.obdLinkBtManager()?.transport
                else -> null
            } ?: return@launch
            transport.stopMonitor()
            ObdLinkTrafficLog.record("OUT", "· CAN monitor stopped")
        }
    }

    /**
     * Dev Mode: read the ECU firmware image over OpenPort CAN. Uploads a RAM
     * kernel, jumps to it, and dumps the image via READ_AREA — READ-ONLY (no
     * erase / no write is ever sent). OpenPort only (CAN read path).
     *
     * The RAM kernel binary is NEVER bundled in the APK and never read from a
     * fixed path: it is a file the user selects via the system file picker
     * (Dev Mode -> SELECT KERNEL FILE), persisted as a SAF URI and read through
     * the ContentResolver — so the kernel can live anywhere the user puts it and
     * is swappable without an app rebuild.
     *
     * Progress + the saved-image path land in the RAW BYTES log; the att6/ar6
     * wire bytes auto-log through the USB transport. Borrows the live link like
     * every other dev op; never touches the logging poll path.
     */
    fun runFirmwareRead(appContext: Context) {
        fun log(m: String) = ObdLinkTrafficLog.record("OUT", "· fw: $m")
        if (host.state.settings.adapter != Adapter.OpenPort) {
            log("read firmware: select OpenPort 2.0 first (CAN read path)")
            return
        }
        host.scope.launch(Dispatchers.IO) {
          try {
            host.takeOverLink()
            val client = host.tactrixClient()
            if (client == null) {
                log("read firmware: OpenPort not connected — plug in & grant USB first")
                return@launch
            }
            // We reconfigure the adapter to CAN ch6 — invalidate the cached
            // K-line init so the next live read re-runs its setup.
            client.channelInitialized = false

            // The RAM kernel is the user-selected file (Dev Mode -> SELECT KERNEL
            // FILE), persisted as a SAF URI — never bundled, never a fixed path.
            // Read it through the ContentResolver so it can live anywhere.
            val kernelUriStr = host.state.settings.kernelUri
            if (kernelUriStr == null) {
                log("no kernel selected — Flash -> SELECT KERNEL FILE, then read")
                host.setStatusMessage("Firmware read: no kernel selected (see BYTES log)")
                return@launch
            }
            val kernel = try {
                appContext.contentResolver.openInputStream(Uri.parse(kernelUriStr))?.use { it.readBytes() }
            } catch (e: Exception) {
                log("kernel read failed: ${e.message}")
                null
            }
            if (kernel == null || kernel.isEmpty()) {
                log("kernel unreadable — re-select it (SELECT KERNEL FILE)")
                host.setStatusMessage("Firmware read: kernel unreadable (see BYTES log)")
                return@launch
            }
            log("kernel ${kernel.size} B loaded (user-selected file)")
            host.setStatusMessage("Firmware read: starting...")

            // Which kernel grammar to drive, and whether the file is RAW (needs the
            // pad + integrity + encrypt prep) or already in upload form (verbatim).
            // Both are user choices — neither is derivable from the binary.
            val protocol = host.state.settings.kernelProtocol
            val uploadImage = if (host.state.settings.kernelNeedsPrep) {
                val prepped = KernelImagePrep.prepare(kernel)
                log("raw kernel ${kernel.size} B -> ${prepped.size} B upload image (pad + integrity + encrypt)")
                prepped
            } else kernel
            log("kernel: protocol=$protocol, prep=${host.state.settings.kernelNeedsPrep}")

            val transport = FirmwareCanTransport(client.rawIo)
            val boot = UdsBootloaderClient(transport) { m -> log(m) }
            if (!boot.connectAndStartKernel(uploadImage, protocol = protocol)) {
                host.setStatusMessage("Firmware read: kernel did not start (see BYTES log)")
                return@launch
            }

            val onProgress: (Int, Int) -> Unit = { done, total ->
                if (done % (64 * 1024) == 0 || done >= total)
                    host.setStatusMessage("Firmware read: ${done / 1024} / ${total / 1024} KB")
            }
            val image = if (protocol == KernelProtocol.BEEF)
                BeefKernelReadClient(transport) { m -> log(m) }.readImage(onProgress = onProgress)
            else
                KernelReadClient(transport) { m -> log(m) }.readImage(onProgress = onProgress)
            if (image == null) {
                host.setStatusMessage("Firmware read: READ_AREA failed (see BYTES log)")
                return@launch
            }

            val outFile = File(
                appContext.getExternalFilesDir(null),
                "firmware_image_${System.currentTimeMillis()}.bin"
            )
            try {
                outFile.writeBytes(image)
                log("IMAGE SAVED: ${outFile.absolutePath} (${image.size} B)")
                host.setStatusMessage("Firmware read DONE: ${image.size / 1024} KB -> ${outFile.name}")
            } catch (e: Exception) {
                log("save failed: ${e.message}")
                host.setStatusMessage("Firmware read: save failed: ${e.message}")
            }
          } catch (t: Throwable) {
              // A firmware-read op must never take the whole app down — surface the
              // failure in the BYTES log and the status line instead of crashing.
              log("FATAL ${t.javaClass.simpleName}: ${t.message}")
              host.setStatusMessage("Firmware read crashed — see BYTES log")
          }
        }
    }

    /**
     * Dev-Mode firmware WRITE (reflash) — OpenPort CAN, BEEF kernel only. Uploads the
     * same user-selected kernel as the read path, jumps to it, then drives the
     * reflash via [BeefKernelWriteClient]: a read-only per-block CRC compare, then
     * (only for blocks that differ) erase + write + commit.
     *
     * Two user files, both SAF URIs, never bundled: the KERNEL (SELECT KERNEL FILE)
     * and the ROM image to flash (SELECT ROM TO WRITE). In TEST
     * mode (caller-supplied testMode) — flash stays protected, the kernel only
     * VALIDATEs, nothing is modified — until COMMIT is explicitly selected. Writing
     * an image identical to what's on the ECU is a no-op (0 blocks differ).
     *
     * Bench targets only. Borrows the live link like every other dev op.
     */
    fun runFirmwareWrite(appContext: Context, testMode: Boolean) {
        fun log(m: String) = ObdLinkTrafficLog.record("OUT", "· fw: $m")
        if (host.state.settings.adapter != Adapter.OpenPort) {
            log("write firmware: select OpenPort 2.0 first (CAN write path)")
            return
        }
        host.scope.launch(Dispatchers.IO) {
          try {
            host.takeOverLink()
            val client = host.tactrixClient()
            if (client == null) {
                log("write firmware: OpenPort not connected — plug in & grant USB first")
                return@launch
            }
            client.channelInitialized = false

            val kernelUriStr = host.state.settings.kernelUri
            if (kernelUriStr == null) {
                log("no kernel selected — Flash -> SELECT KERNEL FILE, then write")
                host.setStatusMessage("Firmware write: no kernel selected (see BYTES log)")
                return@launch
            }
            val romUriStr = host.state.settings.writeRomUri
            if (romUriStr == null) {
                log("no ROM selected — Flash -> SELECT ROM TO WRITE, then write")
                host.setStatusMessage("Firmware write: no ROM selected (see BYTES log)")
                return@launch
            }
            val kernel = try {
                appContext.contentResolver.openInputStream(Uri.parse(kernelUriStr))?.use { it.readBytes() }
            } catch (e: Exception) { log("kernel read failed: ${e.message}"); null }
            if (kernel == null || kernel.isEmpty()) {
                log("kernel unreadable — re-select it (SELECT KERNEL FILE)")
                host.setStatusMessage("Firmware write: kernel unreadable (see BYTES log)")
                return@launch
            }
            val rom = try {
                appContext.contentResolver.openInputStream(Uri.parse(romUriStr))?.use { it.readBytes() }
            } catch (e: Exception) { log("ROM read failed: ${e.message}"); null }
            if (rom == null || rom.size != BeefKernelWriteClient.ROM_SIZE) {
                log("ROM unreadable or wrong size (${rom?.size ?: 0} B, need ${BeefKernelWriteClient.ROM_SIZE}) — re-select (SELECT ROM TO WRITE)")
                host.setStatusMessage("Firmware write: bad ROM (see BYTES log)")
                return@launch
            }
            log("kernel ${kernel.size} B + ROM ${rom.size} B loaded; mode=${if (testMode) "TEST (non-destructive)" else "COMMIT (real write)"}")

            val protocol = host.state.settings.kernelProtocol
            val uploadImage = if (host.state.settings.kernelNeedsPrep) {
                val prepped = KernelImagePrep.prepare(kernel)
                log("raw kernel ${kernel.size} B -> ${prepped.size} B upload image (pad + integrity + encrypt)")
                prepped
            } else kernel

            val transport = FirmwareCanTransport(client.rawIo)
            val boot = UdsBootloaderClient(transport) { m -> log(m) }
            if (!boot.connectAndStartKernel(uploadImage, protocol = protocol)) {
                host.setStatusMessage("Firmware write: kernel did not start (see BYTES log)")
                return@launch
            }

            val onProgress: (Int, Int) -> Unit = { done, total ->
                if (total > 0 && (done % (32 * 1024) == 0 || done >= total))
                    host.setStatusMessage("Firmware write: ${done / 1024} / ${total / 1024} KB")
            }
            val outcome = BeefKernelWriteClient(transport) { m -> log(m) }.writeImage(rom, testMode, onProgress)
            host.setStatusMessage(
                if (!outcome.ok) "Firmware write FAILED — see BYTES log"
                else if (outcome.modifiedBlocks == 0) "Firmware write: ROM already matches ECU (no-op)"
                else if (testMode) "Firmware TEST write PASS (${outcome.modifiedBlocks} blk) — see BYTES log"
                else if (outcome.verified) "Firmware write DONE + VERIFIED (${outcome.modifiedBlocks} blk)"
                else "Firmware write done — VERIFY FAILED (see BYTES log)"
            )
          } catch (t: Throwable) {
              log("FATAL ${t.javaClass.simpleName}: ${t.message}")
              host.setStatusMessage("Firmware write crashed — see BYTES log")
          }
        }
    }

}
