package com.protocol.app.protocol

import com.protocol.app.openport2.EcuIdDecoder
import com.protocol.app.openport2.OpenPortCommandParser
import com.protocol.app.openport2.PollSample
import com.protocol.app.openport2.Ssm2Frame
import com.protocol.app.openport2.Ssm2FrameParser
import com.protocol.app.openport2.Ssm2Pids
import com.protocol.app.openport2.TactrixHex
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Top-level helpers shared between the on-screen probe sections
// (OutcomeCard.kt) and the export formatter below. Kept here so both
// callers stay in sync on length / checksum rendering.

internal fun lengthLine(frame: Ssm2Frame): String {
    val declared = frame.length; val received = frame.payload.size
    return if (frame.truncated || declared != received)
        "  length = 0x%02X ($declared declared, $received received — truncated)".format(declared)
    else
        "  length = 0x%02X ($declared byte${if (declared == 1) "" else "s"})".format(declared)
}

internal fun checksumLine(frame: Ssm2Frame): String = when {
    frame.truncated || frame.checksum < 0 -> "  checksum = (not received — truncated)"
    frame.checksumValid -> "  checksum = 0x%02X (valid)".format(frame.checksum)
    else -> "  checksum = 0x%02X (invalid)".format(frame.checksum)
}

private val csvTimeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

object ProtocolLogFormatter {

    fun formatForExport(uiState: ProtocolUiState): String {
        val sb = StringBuilder()
        sb.append("========================================\n")
        sb.append("PROTOCOL — OpenPort SSM2 ECM Probe Log\n")
        sb.append("Exported: ").append(timestampNow()).append("\n")
        sb.append("========================================\n\n")
        sb.append("==== HUMAN SUMMARY ====\n")
        val outcome = uiState.lastOutcome
        val bundle = uiState.ssm2DecodeBundle
        val ecuId = bundle?.ecuId
        val response = bundle?.response
        val isTruncated = response?.truncated == true
        sb.append(kv("Result", outcome?.name ?: "(no run)"))
        sb.append(kv("ECU ID", ecuId?.ecuIdHex ?: "—"))
        sb.append(kv("Internal ID", if (isTruncated)
            "not in received bytes — response partially assembled"
        else
            EcuIdDecoder.INTERNAL_ID_NOT_PRESENT))
        sb.append(kv("Calibration", ecuId?.calibrationBytes?.let(TactrixHex::bytesToHex) ?: "—"))
        sb.append(kv("SSM ID", ecuId?.ssmIdBytes?.let(TactrixHex::bytesToHex) ?: "—"))
        sb.append(kv("Source module", response?.let { "${Ssm2FrameParser.moduleLabel(it.source)} (0x%02X)".format(it.source) } ?: "—"))
        sb.append(kv("Header detected", response?.let { "%02X %02X %02X".format(it.format, it.destination, it.source) } ?: "—"))
        sb.append(kv("Response time", uiState.attStepDurationMs?.let { "$it ms" } ?: "—"))
        if (isTruncated && response != null) {
            sb.append(kv("Response status", "PARTIAL — ${response.payload.size} of ${response.length} bytes received"))
            sb.append(kv("ECU ID bytes", "found at expected offset"))
            sb.append(kv("Full assembly", "not complete — multi-frame fix pending"))
        }
        sb.append("\n")
        sb.append("==== SSM2 DECODE ====\n")
        if (bundle == null) {
            sb.append("att3 never reached — no SSM2 frame transmitted.\n")
        } else {
            val req = bundle.request
            if (req != null) {
                sb.append("Request : ").append(TactrixHex.bytesToHex(req.rawBytes)).append("\n")
                sb.append("  format = 0x%02X\n".format(req.format))
                sb.append("  destination = 0x%02X (${Ssm2FrameParser.moduleLabel(req.destination)})\n".format(req.destination))
                sb.append("  source = 0x%02X (${Ssm2FrameParser.moduleLabel(req.source)})\n".format(req.source))
                sb.append("  length = 0x%02X (${req.length} byte${if (req.length == 1) "" else "s"})\n".format(req.length))
                if (req.payload.isNotEmpty()) {
                    val cmd = req.payload[0].toInt() and 0xFF
                    sb.append("  command = 0x%02X (${Ssm2FrameParser.commandLabel(cmd)})\n".format(cmd))
                }
                sb.append(checksumLine(req)).append("\n")
            } else { sb.append("Request : (not parsed)\n") }
            val rsp = bundle.response
            if (rsp != null) {
                sb.append("Response: ").append(TactrixHex.bytesToHex(rsp.rawBytes)).append("\n")
                sb.append("  format = 0x%02X\n".format(rsp.format))
                sb.append("  destination = 0x%02X (${Ssm2FrameParser.moduleLabel(rsp.destination)})\n".format(rsp.destination))
                sb.append("  source = 0x%02X (${Ssm2FrameParser.moduleLabel(rsp.source)})\n".format(rsp.source))
                sb.append(lengthLine(rsp)).append("\n")
                if (rsp.payload.isNotEmpty()) {
                    val code = rsp.payload[0].toInt() and 0xFF
                    sb.append("  response = 0x%02X (${Ssm2FrameParser.commandLabel(code)})\n".format(code))
                }
                sb.append(checksumLine(rsp)).append("\n")
            } else { sb.append("Response: not parsed — no vehicle frame received.\n") }
        }
        sb.append("\n")
        sb.append("==== OPENPORT FRAME ====\n")
        if (bundle == null) {
            sb.append("att3 never reached.\n")
        } else {
            sb.append(kv("att3 ack (aro)", if (bundle.aroAcknowledged) "acknowledged" else "not acknowledged"))
            sb.append(kv("Vehicle frame (ar3)", if (bundle.ar3FrameDetected) "detected (channel 3)" else "not detected"))
            sb.append(kv("Extracted payload", bundle.extractedFrameHex.ifBlank { "(none)" }))
        }
        sb.append("\n")
        sb.append("==== LOW LEVEL USB / OPENPORT (final step) ====\n")
        val lastStep = uiState.log.lastOrNull()
        if (lastStep == null) {
            sb.append("(no run)\n")
        } else {
            sb.append(kv("Step", "${lastStep.stepIndex} — ${lastStep.stepLabel}"))
            sb.append(kv("Request", lastStep.requestAscii.ifBlank { "(empty)" }))
            sb.append(kv("USB hex out", lastStep.requestHex.ifBlank { "(empty)" }))
            val cmd = OpenPortCommandParser.parseOpenPortCommand(lastStep.requestAscii)
            val payloadLen = cmd?.payloadLen
            if (payloadLen != null && payloadLen > 0) {
                val hex = lastStep.requestHex.trim().split(" ").filter { it.isNotBlank() }
                if (hex.size >= payloadLen) sb.append(kv("Payload", hex.takeLast(payloadLen).joinToString(" ")))
            }
            sb.append(kv("USB hex in", lastStep.responseHex.ifBlank { "(empty)" }))
            sb.append(kv("Duration", "${lastStep.durationMs} ms"))
        }
        sb.append("\n")
        sb.append("==== FULL RUN LOG (all steps) ====\n")
        if (uiState.log.isEmpty()) sb.append("(no run)\n")
        else for (entry in uiState.log) appendStepBlock(sb, entry)
        return sb.toString()
    }

    fun formatSessionLogCsv(uiState: ProtocolUiState): String {
        val log = uiState.sessionLog
        if (log.isEmpty()) return ""
        val pids = Ssm2Pids.DEFAULT_DEMO_PIDS.filter { it.id in uiState.pidIdsOnLiveData }
        if (pids.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("Timestamp")
        for (pid in pids) sb.append(",${pid.displayName} (${pid.unit})")
        sb.append("\n")
        for (sample in log) {
            sb.append(csvTimeFmt.format(Date(sample.timestampMs)))
            for (pid in pids) {
                val v = sample.values[pid.id]
                sb.append(",").append(v?.let { formatPidValueCsv(pid.id, it) } ?: "")
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * Aligned text table for the on-screen session log and for clipboard
     * copy. Designed to survive paste into any monospaced or proportional
     * context: fixed-width columns, single-space delimiters, ASCII-only
     * (degree sign stripped from unit labels).
     *
     * Only PIDs in [pidIdsOnPage] (i.e., gauges currently placed on the
     * Live Data page) become columns. If the page is empty, an
     * explanatory placeholder is returned.
     */
    fun formatSessionLogCleanText(
        log: List<PollSample>,
        pidIdsOnPage: Set<String>,
        oldestFirst: Boolean = false
    ): String {
        val pids = Ssm2Pids.DEFAULT_DEMO_PIDS.filter { it.id in pidIdsOnPage }
        if (pids.isEmpty()) {
            return "(no gauges — open the menu and add parameters to log them)"
        }
        val headers = pids.map(::pidHeaderText)
        val maxValueWidths = pids.map { maxValueWidth(it.id) }
        val colWidths = headers.mapIndexed { i, h -> maxOf(h.length, maxValueWidths[i]) }
        val sb = StringBuilder()
        sb.append("Time".padEnd(12))
        for (i in pids.indices) {
            sb.append("  ").append(headers[i].padEnd(colWidths[i]))
        }
        sb.append("\n")
        if (log.isEmpty()) {
            sb.append("(no samples yet — press Log Live Data and select parameters)")
            return sb.toString()
        }
        val ordered = if (oldestFirst) log else log.asReversed()
        for (sample in ordered) {
            sb.append(logTimeFmt.format(Date(sample.timestampMs)))
            for (i in pids.indices) {
                val v = sample.values[pids[i].id]
                val s = v?.let { formatPidValueText(pids[i].id, it) } ?: "--"
                sb.append("  ").append(s.padStart(colWidths[i]))
            }
            sb.append("\n")
        }
        return sb.toString()
    }

    fun formatPidValueText(pidId: String, value: Double): String = when (pidId) {
        "rpm"             -> "%.0f".format(value)
        "coolant"         -> "%.0f".format(value)
        "battery"         -> "%.1f".format(value)
        "afc1", "afc2"    -> "%.1f".format(value)
        "afl1", "afl2"    -> "%.1f".format(value)
        "afs1", "afs2"    -> "%.2f".format(value)
        "ign"             -> "%.1f".format(value)
        "maf"             -> "%.2f".format(value)
        "oil"             -> "%.0f".format(value)
        "iat"             -> "%.0f".format(value)
        "fbkc"            -> "%.2f".format(value)
        "load"            -> "%.2f".format(value)
        "flkc"            -> "%.2f".format(value)
        "iam"             -> "%.3f".format(value)
        "speed"           -> "%.0f".format(value)
        "kca"             -> "%.1f".format(value)
        // ECM additions
        "load_rel"        -> "%.1f".format(value)
        "map", "atm"      -> "%.2f".format(value)
        "mrp", "mrp_corr" -> "%.2f".format(value)
        "throttle"        -> "%.1f".format(value)
        "fo2_1", "fo2_2", "ro2" -> "%.3f".format(value)
        "maf_v", "tps_v", "tps_sub", "tps_main",
        "pedal_sub", "pedal_main", "tumble_r", "tumble_l",
        "tm_v" -> "%.2f".format(value)
        "inj1_pw", "inj2_pw" -> "%.2f".format(value)
        "learn_ign_corr" -> "%.1f".format(value)
        "fuel_t"          -> "%.0f".format(value)
        "fan_ctrl", "cpc_duty", "iscv_duty", "af_lean",
        "af_heater", "alt_duty", "fp_duty",
        "ocv_dr", "ocv_dl", "tm_duty", "osv_dr", "osv_dl" -> "%.1f".format(value)
        "iscv_step"       -> "%.0f".format(value)
        "avcs_r", "avcs_l" -> "%.0f".format(value)
        "ocv_cr", "ocv_cl", "osv_cr", "osv_cl" -> "%.0f".format(value)
        "afs1_curr", "afs2_curr" -> "%.2f".format(value)
        "afs1_res", "afs2_res" -> "%.0f".format(value)
        "cyl1_rough", "cyl2_rough", "cyl3_rough",
        "cyl4_rough", "cyl5_rough", "cyl6_rough" -> "%.0f".format(value)
        "idc"             -> "%.1f".format(value)
        "mpg"             -> "%.1f".format(value)
        // TCM
        "tcm_gear"        -> "%.0f".format(value)
        "tcm_turbine", "tcm_atts1", "tcm_atts2", "tcm_rpm" -> "%.0f".format(value)
        "tcm_atf"         -> "%.0f".format(value)
        "tcm_fws", "tcm_rws", "tcm_whl_fr", "tcm_whl_fl",
        "tcm_whl_rr", "tcm_whl_rl",
        "tcm_abs_fm", "tcm_abs_rm" -> "%.1f".format(value)
        "tcm_pedal"       -> "%.1f".format(value)
        "tcm_lu_press", "tcm_pl_press",
        "tcm_hlrc_p", "tcm_dc_p", "tcm_fb_p",
        "tcm_ic_p", "tcm_awd_p", "tcm_fwdb_p" -> "%.1f".format(value)
        "tcm_lp_duty", "tcm_lu_duty", "tcm_xfer_duty",
        "tcm_bc_duty", "tcm_lc_duty", "tcm_hc_duty",
        "tcm_lrb_duty"    -> "%.1f".format(value)
        "tcm_latg_v", "tcm_cd_sw_v", "tcm_yaw_v",
        "tcm_yawg_ref"    -> "%.2f".format(value)
        "tcm_cd_real_i", "tcm_cd_ind_i" -> "%.2f".format(value)
        "tcm_hlrc_i", "tcm_dc_i", "tcm_fb_i", "tcm_ic_i",
        "tcm_pl_i", "tcm_lu_i", "tcm_awd_i", "tcm_fwdb_i" -> "%.3f".format(value)
        "tcm_fr_ratio", "tcm_atf_deg" -> "%.2f".format(value)
        else              -> "%.2f".format(value)
    }

    private fun pidHeaderText(pid: com.protocol.app.openport2.Ssm2Pid): String {
        val ascii = pid.unit.replace("°", "")
        return if (ascii.isBlank() || ascii.equals(pid.displayName, ignoreCase = true)) pid.displayName
        else "${pid.displayName}($ascii)"
    }

    private fun maxValueWidth(pidId: String): Int = when (pidId) {
        "rpm"             -> 5   // up to "8000"
        "coolant"         -> 3   // up to "240"
        "battery"         -> 4   // "14.5"
        "afc1", "afc2"    -> 6   // "-99.9" / " 99.9"
        "afl1", "afl2"    -> 6
        "afs1", "afs2"    -> 5   // "14.70"
        "ign"             -> 5   // "-30.0" / " 60.0"
        "maf"             -> 6   // up to "300.00"
        "oil"             -> 3   // up to "260"
        "iat"             -> 3
        "fbkc"            -> 6   // signed degrees
        "load"            -> 5   // "9.99"
        "flkc"            -> 6
        "iam"             -> 5   // "1.000"
        "speed"           -> 3   // up to "200"
        "kca"             -> 6   // signed degrees
        // ECM additions
        "load_rel", "throttle", "fan_ctrl", "cpc_duty", "iscv_duty",
        "af_lean", "af_heater", "alt_duty", "fp_duty",
        "ocv_dr", "ocv_dl", "osv_dr", "osv_dl",
        "tm_duty"         -> 5   // "100.0" or signed pct
        "map", "atm"      -> 5   // "14.50" psi
        "mrp", "mrp_corr" -> 6   // signed psi "-12.34"
        "fo2_1", "fo2_2", "ro2" -> 5  // "1.234"
        "maf_v", "tps_v", "tps_sub", "tps_main",
        "pedal_sub", "pedal_main", "tumble_r", "tumble_l",
        "tm_v" -> 4  // "5.00"
        "inj1_pw", "inj2_pw" -> 5  // "12.34"
        "learn_ign_corr" -> 5
        "fuel_t"          -> 3   // up to "240"
        "iscv_step"       -> 4   // up to "500"
        "avcs_r", "avcs_l" -> 3  // "0..50"
        "ocv_cr", "ocv_cl", "osv_cr", "osv_cl" -> 5  // mA up to 8160
        "afs1_curr", "afs2_curr" -> 6  // signed mA
        "afs1_res", "afs2_res" -> 3
        "cyl1_rough", "cyl2_rough", "cyl3_rough",
        "cyl4_rough", "cyl5_rough", "cyl6_rough" -> 3
        "idc"             -> 5   // "100.0"
        "mpg"             -> 5   // "99.9"
        // TCM
        "tcm_gear"        -> 2
        "tcm_turbine", "tcm_atts1", "tcm_atts2", "tcm_rpm" -> 5
        "tcm_atf"         -> 3
        "tcm_fws", "tcm_rws", "tcm_whl_fr", "tcm_whl_fl",
        "tcm_whl_rr", "tcm_whl_rl",
        "tcm_abs_fm", "tcm_abs_rm" -> 5
        "tcm_pedal"       -> 5
        "tcm_lu_press", "tcm_pl_press",
        "tcm_hlrc_p", "tcm_dc_p", "tcm_fb_p",
        "tcm_ic_p", "tcm_awd_p", "tcm_fwdb_p" -> 5
        "tcm_lp_duty", "tcm_lu_duty", "tcm_xfer_duty",
        "tcm_bc_duty", "tcm_lc_duty", "tcm_hc_duty",
        "tcm_lrb_duty"    -> 5
        "tcm_latg_v", "tcm_cd_sw_v", "tcm_yaw_v",
        "tcm_yawg_ref"    -> 4
        "tcm_cd_real_i", "tcm_cd_ind_i" -> 5
        "tcm_hlrc_i", "tcm_dc_i", "tcm_fb_i", "tcm_ic_i",
        "tcm_pl_i", "tcm_lu_i", "tcm_awd_i", "tcm_fwdb_i" -> 5
        "tcm_fr_ratio", "tcm_atf_deg" -> 5
        else              -> 6
    }

    private val logTimeFmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun suggestedExportFileName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            .let { "protocol_probe_$it.txt" }

    fun suggestedCsvFileName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            .let { "protocol_session_$it.csv" }

    private fun timestampNow(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    private fun kv(label: String, value: String): String = "${label.padEnd(16)}: $value\n"

    private fun formatPidValueCsv(pidId: String, value: Double): String = when (pidId) {
        "rpm"             -> "%.1f".format(value)
        "coolant"         -> "%.1f".format(value)
        "battery"         -> "%.2f".format(value)
        "afc1", "afc2"    -> "%.2f".format(value)
        "afl1", "afl2"    -> "%.2f".format(value)
        "afs1", "afs2"    -> "%.3f".format(value)
        "ign"             -> "%.2f".format(value)
        "maf"             -> "%.3f".format(value)
        "oil"             -> "%.1f".format(value)
        "iat"             -> "%.1f".format(value)
        "fbkc"            -> "%.4f".format(value)
        "load"            -> "%.4f".format(value)
        "flkc"            -> "%.4f".format(value)
        "iam"             -> "%.4f".format(value)
        "speed"           -> "%.1f".format(value)
        "kca"             -> "%.2f".format(value)
        // ECM additions
        "load_rel"        -> "%.2f".format(value)
        "map", "atm"      -> "%.3f".format(value)
        "mrp", "mrp_corr" -> "%.3f".format(value)
        "throttle"        -> "%.2f".format(value)
        "fo2_1", "fo2_2", "ro2" -> "%.4f".format(value)
        "maf_v", "tps_v", "tps_sub", "tps_main",
        "pedal_sub", "pedal_main", "tumble_r", "tumble_l",
        "tm_v" -> "%.3f".format(value)
        "inj1_pw", "inj2_pw" -> "%.3f".format(value)
        "learn_ign_corr" -> "%.2f".format(value)
        "fuel_t"          -> "%.1f".format(value)
        "fan_ctrl", "cpc_duty", "iscv_duty", "af_lean",
        "af_heater", "alt_duty", "fp_duty",
        "ocv_dr", "ocv_dl", "tm_duty", "osv_dr", "osv_dl" -> "%.2f".format(value)
        "iscv_step"       -> "%.0f".format(value)
        "avcs_r", "avcs_l" -> "%.1f".format(value)
        "ocv_cr", "ocv_cl", "osv_cr", "osv_cl" -> "%.1f".format(value)
        "afs1_curr", "afs2_curr" -> "%.3f".format(value)
        "afs1_res", "afs2_res" -> "%.1f".format(value)
        "cyl1_rough", "cyl2_rough", "cyl3_rough",
        "cyl4_rough", "cyl5_rough", "cyl6_rough" -> "%.0f".format(value)
        "idc"             -> "%.2f".format(value)
        "mpg"             -> "%.2f".format(value)
        // TCM
        "tcm_gear"        -> "%.0f".format(value)
        "tcm_turbine", "tcm_atts1", "tcm_atts2", "tcm_rpm" -> "%.1f".format(value)
        "tcm_atf"         -> "%.1f".format(value)
        "tcm_fws", "tcm_rws", "tcm_whl_fr", "tcm_whl_fl",
        "tcm_whl_rr", "tcm_whl_rl",
        "tcm_abs_fm", "tcm_abs_rm" -> "%.2f".format(value)
        "tcm_pedal"       -> "%.2f".format(value)
        "tcm_lu_press", "tcm_pl_press",
        "tcm_hlrc_p", "tcm_dc_p", "tcm_fb_p",
        "tcm_ic_p", "tcm_awd_p", "tcm_fwdb_p" -> "%.2f".format(value)
        "tcm_lp_duty", "tcm_lu_duty", "tcm_xfer_duty",
        "tcm_bc_duty", "tcm_lc_duty", "tcm_hc_duty",
        "tcm_lrb_duty"    -> "%.2f".format(value)
        "tcm_latg_v", "tcm_cd_sw_v", "tcm_yaw_v",
        "tcm_yawg_ref"    -> "%.3f".format(value)
        "tcm_cd_real_i", "tcm_cd_ind_i" -> "%.3f".format(value)
        "tcm_hlrc_i", "tcm_dc_i", "tcm_fb_i", "tcm_ic_i",
        "tcm_pl_i", "tcm_lu_i", "tcm_awd_i", "tcm_fwdb_i" -> "%.4f".format(value)
        "tcm_fr_ratio", "tcm_atf_deg" -> "%.3f".format(value)
        else              -> "%.3f".format(value)
    }

    private fun appendStepBlock(sb: StringBuilder, entry: com.protocol.app.openport2.TactrixCommandLog) {
        sb.append("[").append(entry.stepIndex).append("] ").append(entry.stepLabel).append("\n")
        sb.append("  REQ ASCII : ").append(entry.requestAscii).append("\n")
        sb.append("  REQ HEX   : ").append(entry.requestHex).append("\n")
        sb.append("  RSP ASCII : ").append(entry.responseAscii).append("\n")
        sb.append("  RSP HEX   : ").append(entry.responseHex).append("\n")
        val parsed = OpenPortCommandParser.parseOpenPortCommand(entry.requestAscii)
        sb.append("  PARSED CMD: ").append(parsedCmdLine(parsed)).append("\n")
        sb.append("  TIME      : ").append(entry.durationMs).append(" ms\n")
        sb.append("  OUTCOME   : ").append(entry.outcome.name).append("\n")
        if (entry.notes.isNotBlank()) sb.append("  NOTES     : ").append(entry.notes).append("\n")
        sb.append("\n")
    }

    private fun parsedCmdLine(cmd: com.protocol.app.openport2.OpenPortCommand?): String {
        if (cmd == null) return "(no command on the wire)"
        val ch = cmd.channel?.let { "$it" } ?: "-"
        val reqId = cmd.reqId?.let { "$it" } ?: "-"
        val timeout = cmd.timeoutMicros?.let { "${it}µs" } ?: "-"
        val payloadLen = cmd.payloadLen?.let { "$it" } ?: "-"
        return "verb=${cmd.verb} channel=$ch payloadLen=$payloadLen timeout=$timeout reqId=$reqId"
    }
}
