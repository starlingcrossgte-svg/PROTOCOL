package com.protocol.app.protocol

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.protocol.app.openport2.EcuIdDecoder
import com.protocol.app.openport2.OpenPortCommand
import com.protocol.app.openport2.OpenPortCommandParser
import com.protocol.app.openport2.Ssm2DecodeBundle
import com.protocol.app.openport2.Ssm2EcmProbe
import com.protocol.app.openport2.Ssm2FrameParser
import com.protocol.app.openport2.TactrixCommandLog
import com.protocol.app.openport2.TactrixHex

/**
 * Probe outcome card — shown on Home after any SSM2 probe runs.
 * Renders the four canonical sections (HUMAN SUMMARY / SSM2 DECODE /
 * OPENPORT FRAME / LOW LEVEL USB) so the user can see exactly what the
 * adapter and ECU did.
 */
@Composable
internal fun OutcomeCard(uiState: ProtocolUiState) {
    val outcome = uiState.lastOutcome ?: return
    val (pillText, pillColor) = pillFor(outcome)
    val bundle = uiState.ssm2DecodeBundle
    val lastStep = uiState.log.lastOrNull()
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = SurfaceBg),
        border = BorderStroke(1.dp, BorderGray)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(modifier = Modifier.background(pillColor, RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(pillText, color = Color.White, fontWeight = FontWeight.Bold)
                }
                Text("Last Probe Outcome", color = InkPrimary, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium)
            }
            HumanSummarySection(outcome, bundle, uiState.attStepDurationMs)
            Ssm2DecodeSection(outcome, bundle)
            OpenPortFrameSection(outcome, bundle)
            LowLevelUsbSection(outcome, lastStep)
        }
    }
}

/**
 * Detailed run log — the per-step ASCII / hex / parsed-command dump for
 * the most recent probe. Gated behind Settings → Developer Mode on the
 * Home page.
 */
@Composable
internal fun RunLogCard(log: List<TactrixCommandLog>) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF14161A)),
        border = BorderStroke(1.dp, BorderGray),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Text("Run Log", color = Color.White, fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 8.dp))
            if (log.isEmpty()) {
                Text("No probe runs yet.", color = InkMuted, fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium)
            } else {
                Column(modifier = Modifier.fillMaxWidth().height(520.dp).verticalScroll(rememberScrollState())) {
                    Row(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                        Text(formatLog(log), color = Color.White, fontFamily = FontFamily.Monospace,
                            style = MaterialTheme.typography.bodySmall, softWrap = false)
                    }
                }
            }
        }
    }
}

private fun pillFor(outcome: Ssm2EcmProbe.ProbeOutcome): Pair<String, Color> = when (outcome) {
    Ssm2EcmProbe.ProbeOutcome.SUCCESS_ECU_REPLIED -> "ECU REPLIED" to PassGreen
    Ssm2EcmProbe.ProbeOutcome.FAIL_INIT_STEP -> "INIT FAIL" to FailRed
    Ssm2EcmProbe.ProbeOutcome.FAIL_NO_ECU_REPLY -> "NO ECU REPLY" to FailRed
    Ssm2EcmProbe.ProbeOutcome.FAIL_TRANSPORT -> "TRANSPORT FAIL" to FailRed
    Ssm2EcmProbe.ProbeOutcome.FAIL_USB_DISCONNECTED -> "USB DISCONNECTED" to FailRed
}

@Composable
private fun HumanSummarySection(
    outcome: Ssm2EcmProbe.ProbeOutcome,
    bundle: Ssm2DecodeBundle?,
    attStepDurationMs: Long?
) {
    CategoryHeader("HUMAN SUMMARY")
    KvRow("Result", outcome.name)
    val ecuId = bundle?.ecuId
    val response = bundle?.response
    val placeholder = "—"
    val isTruncated = response?.truncated == true
    KvRow("ECU ID", ecuId?.ecuIdHex ?: placeholder)
    KvRow("Internal ID", if (isTruncated)
        "not in received bytes — response partially assembled"
    else
        EcuIdDecoder.INTERNAL_ID_NOT_PRESENT
    )
    KvRow("Calibration", ecuId?.calibrationBytes?.let(TactrixHex::bytesToHex) ?: placeholder)
    KvRow("SSM ID", ecuId?.ssmIdBytes?.let(TactrixHex::bytesToHex) ?: placeholder)
    KvRow("Source module", response?.let { "${Ssm2FrameParser.moduleLabel(it.source)} (0x%02X)".format(it.source) } ?: placeholder)
    KvRow("Header detected", response?.let { "%02X %02X %02X".format(it.format, it.destination, it.source) } ?: placeholder)
    KvRow("Response time", attStepDurationMs?.let { "$it ms" } ?: placeholder)
    if (isTruncated && response != null) {
        KvRow("Response status", "PARTIAL — ${response.payload.size} of ${response.length} bytes received")
        KvRow("ECU ID bytes", "found at expected offset")
        KvRow("Full assembly", "not complete — multi-frame fix pending")
    }
}

@Composable
private fun Ssm2DecodeSection(outcome: Ssm2EcmProbe.ProbeOutcome, bundle: Ssm2DecodeBundle?) {
    CategoryHeader("SSM2 DECODE")
    if (bundle == null) { BodyMono("att3 never reached — no SSM2 frame transmitted."); return }
    val req = bundle.request
    if (req != null) {
        BodyMono("Request : ${TactrixHex.bytesToHex(req.rawBytes)}")
        BodyMono("  format = 0x%02X".format(req.format))
        BodyMono("  destination = 0x%02X (${Ssm2FrameParser.moduleLabel(req.destination)})".format(req.destination))
        BodyMono("  source = 0x%02X (${Ssm2FrameParser.moduleLabel(req.source)})".format(req.source))
        BodyMono("  length = 0x%02X (${req.length} byte${if (req.length == 1) "" else "s"})".format(req.length))
        if (req.payload.isNotEmpty()) {
            val cmd = req.payload[0].toInt() and 0xFF
            BodyMono("  command = 0x%02X (${Ssm2FrameParser.commandLabel(cmd)})".format(cmd))
        }
        BodyMono(checksumLine(req))
    } else { BodyMono("Request : (not parsed)") }
    val rsp = bundle.response
    if (rsp != null) {
        BodyMono("Response: ${TactrixHex.bytesToHex(rsp.rawBytes)}")
        BodyMono("  format = 0x%02X".format(rsp.format))
        BodyMono("  destination = 0x%02X (${Ssm2FrameParser.moduleLabel(rsp.destination)})".format(rsp.destination))
        BodyMono("  source = 0x%02X (${Ssm2FrameParser.moduleLabel(rsp.source)})".format(rsp.source))
        BodyMono(lengthLine(rsp))
        if (rsp.payload.isNotEmpty()) {
            val code = rsp.payload[0].toInt() and 0xFF
            BodyMono("  response = 0x%02X (${Ssm2FrameParser.commandLabel(code)})".format(code))
        }
        BodyMono(checksumLine(rsp))
    } else { BodyMono("Response: not parsed — no vehicle frame received.") }
}

@Composable
private fun OpenPortFrameSection(outcome: Ssm2EcmProbe.ProbeOutcome, bundle: Ssm2DecodeBundle?) {
    CategoryHeader("OPENPORT FRAME")
    if (bundle == null) { BodyMono("att3 never reached."); return }
    BodyMono("att3 ack (aro)        : ${if (bundle.aroAcknowledged) "acknowledged" else "not acknowledged"}")
    BodyMono("Vehicle frame (ar3)   : ${if (bundle.ar3FrameDetected) "detected (channel 3)" else "not detected"}")
    if (bundle.extractedFrameHex.isNotBlank())
        BodyMono("Extracted payload     : ${bundle.extractedFrameHex}")
    else
        BodyMono("Extracted payload     : (none)")
}

@Composable
private fun LowLevelUsbSection(outcome: Ssm2EcmProbe.ProbeOutcome, lastStep: TactrixCommandLog?) {
    CategoryHeader("LOW LEVEL USB / OPENPORT")
    if (lastStep == null) { BodyMono("No step recorded."); return }
    BodyMono("Step       : ${lastStep.stepIndex} — ${lastStep.stepLabel}")
    BodyMono("Request    : ${lastStep.requestAscii.ifBlank { "(empty)" }}")
    BodyMono("USB hex out: ${lastStep.requestHex.ifBlank { "(empty)" }}")
    val cmd = OpenPortCommandParser.parseOpenPortCommand(lastStep.requestAscii)
    val payloadLen = cmd?.payloadLen
    if (payloadLen != null && payloadLen > 0) {
        val hex = lastStep.requestHex.trim().split(" ").filter { it.isNotBlank() }
        if (hex.size >= payloadLen) BodyMono("Payload    : ${hex.takeLast(payloadLen).joinToString(" ")}")
    }
    BodyMono("USB hex in : ${lastStep.responseHex.ifBlank { "(empty)" }}")
    BodyMono("Duration   : ${lastStep.durationMs} ms")
}

@Composable
private fun KvRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label.padEnd(16), color = InkPrimary, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
        Text(": $value", color = InkPrimary, fontFamily = FontFamily.Monospace,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun BodyMono(text: String) {
    Text(text, color = InkPrimary, fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodySmall)
}

private fun formatLog(log: List<TactrixCommandLog>): String {
    val sb = StringBuilder()
    for (entry in log) appendStepBlock(sb, entry)
    return sb.toString()
}

private fun appendStepBlock(sb: StringBuilder, entry: TactrixCommandLog) {
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

private fun parsedCmdLine(cmd: OpenPortCommand?): String {
    if (cmd == null) return "(no command on the wire)"
    val ch = cmd.channel?.let { "$it" } ?: "-"
    val reqId = cmd.reqId?.let { "$it" } ?: "-"
    val timeout = cmd.timeoutMicros?.let { "${it}µs" } ?: "-"
    val payloadLen = cmd.payloadLen?.let { "$it" } ?: "-"
    return "verb=${cmd.verb} channel=$ch payloadLen=$payloadLen timeout=$timeout reqId=$reqId"
}
