package com.protocol.wear

import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable

/**
 * Wear OS remote for the phone's Live Data tap loop. The whole face is one tap
 * target and every tap buzzes, because it is used without looking.
 *
 * Holds NO logic: sends [PATH_TAP], displays what the phone reports on
 * [PATH_STATE]. The phone decides what a tap means, so the two can never
 * disagree about whether logging is running.
 */
class WatchTapActivity : ComponentActivity(), MessageClient.OnMessageReceivedListener {

    private var phoneState by mutableStateOf(PhoneState.NoPhone)

    /** Display only; the phone owns it. */
    private var stagedPreset by mutableStateOf<String?>(null)

    /** Accumulates sub-detent rotary movement so one flick does not race
     *  through the whole preset list. */
    private var rotaryAccumulator = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            TapFace(state = phoneState, staged = stagedPreset, onTap = ::sendTap)
        }
    }

    /** Bezel and rotating side button. Guard is narrow on purpose so a stray
     *  touch cannot scroll presets. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val isRotary = event.action == MotionEvent.ACTION_SCROLL &&
            event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)
        if (!isRotary) return super.onGenericMotionEvent(event)

        // Negated so clockwise moves DOWN the list, like every other Wear surface.
        rotaryAccumulator += -event.getAxisValue(MotionEvent.AXIS_SCROLL)

        var steps = 0
        while (rotaryAccumulator >= DETENT) { steps++; rotaryAccumulator -= DETENT }
        while (rotaryAccumulator <= -DETENT) { steps--; rotaryAccumulator += DETENT }
        if (steps != 0) sendPresetStep(steps)
        return true
    }

    // Foreground only: a background listener would just cost battery.
    override fun onResume() {
        super.onResume()
        Wearable.getMessageClient(this).addListener(this)
        refreshReachability()
    }

    override fun onPause() {
        super.onPause()
        Wearable.getMessageClient(this).removeListener(this)
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != PATH_STATE) return
        // One message carries both, so the face cannot show a pending preset
        // next to a stale phone state.
        val payload = String(event.data, Charsets.UTF_8)
        val separator = payload.indexOf('|')
        if (separator < 0) {
            phoneState = PhoneState.fromWire(payload)
            stagedPreset = null
        } else {
            phoneState = PhoneState.fromWire(payload.substring(0, separator))
            stagedPreset = payload.substring(separator + 1).takeIf { it.isNotBlank() }
        }
    }

    /** Says which way and how far; the phone owns where it lands. */
    private fun sendPresetStep(steps: Int) {
        buzz(PRESET_BUZZ_MS)
        val payload = steps.toString().toByteArray(Charsets.UTF_8)
        val messages = Wearable.getMessageClient(this)
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    phoneState = PhoneState.NoPhone
                    return@addOnSuccessListener
                }
                for (node in nodes) messages.sendMessage(node.id, PATH_PRESET, payload)
            }
            .addOnFailureListener { phoneState = PhoneState.NoPhone }
    }

    private fun sendTap() {
        buzz()
        val messages = Wearable.getMessageClient(this)
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) {
                    phoneState = PhoneState.NoPhone
                    return@addOnSuccessListener
                }
                for (node in nodes) {
                    messages.sendMessage(node.id, PATH_TAP, ByteArray(0))
                }
            }
            .addOnFailureListener { phoneState = PhoneState.NoPhone }
    }

    /** Show NO PHONE up front rather than letting the user tap into silence. */
    private fun refreshReachability() {
        Wearable.getNodeClient(this).connectedNodes
            .addOnSuccessListener { nodes ->
                if (nodes.isEmpty()) phoneState = PhoneState.NoPhone
            }
            .addOnFailureListener { phoneState = PhoneState.NoPhone }
    }

    private fun buzz(millis: Long = TAP_BUZZ_MS) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (getSystemService(VibratorManager::class.java))?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)
        } ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    companion object {
        // These paths must match the phone side exactly.
        /** Watch -> phone, no payload. */
        const val PATH_TAP = "/protocol/tap"
        /** Watch -> phone, signed step count as UTF-8 text. */
        const val PATH_PRESET = "/protocol/preset"
        /** Phone -> watch, PhoneState name, optionally "|<staged preset>". */
        const val PATH_STATE = "/protocol/state"

        /** Rotary units per step. PROVISIONAL — AXIS_SCROLL scale unmeasured on
         *  this hardware; change this if one detent jumps several presets. */
        private const val DETENT = 1.0f

        private const val TAP_BUZZ_MS = 40L
        private const val PRESET_BUZZ_MS = 15L
    }
}

/** What the phone says it is doing. Display only — never decided here. */
enum class PhoneState(val caption: String, val hint: String) {
    NoPhone("NO PHONE", "open PROTOCOL"),
    Unlocked("TAP TO LOCK", "Live Data"),
    Idle("READY", "tap to read"),
    Reading("READING", "tap to log"),
    Logging("LOGGING", "tap to stop"),
    Saving("SAVING", "tap to cancel");

    companion object {
        fun fromWire(name: String): PhoneState =
            values().firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NoPhone
    }
}

@Composable
private fun TapFace(state: PhoneState, staged: String?, onTap: () -> Unit) {
    // Whole face is the target. Ripple suppressed; the buzz is the feedback.
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onTap
            ),
        contentAlignment = Alignment.Center
    ) {
        // Ring, inset from the edge so it clears the round bezel.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val inset = size.minDimension * 0.06f
            val diameter = size.minDimension - inset * 2
            drawCircle(
                color = if (state == PhoneState.NoPhone) Color.DarkGray else Color.White,
                radius = diameter / 2f,
                center = Offset(size.width / 2f, size.height / 2f),
                style = Stroke(width = size.minDimension * 0.02f)
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 28.dp)
        ) {
            // A staged preset takes over the face: it is what the next tap does.
            if (staged != null) {
                Text(
                    text = staged,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "tap to apply",
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall
                )
            } else {
                Text(
                    text = state.caption,
                    color = Color.White,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = state.hint,
                    color = Color.Gray,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}
