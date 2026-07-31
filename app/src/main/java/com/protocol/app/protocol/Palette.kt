package com.protocol.app.protocol

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Dark Y2K palette. ScreenBg is the deepest layer; SurfaceBg sits one
// step up (header, cards); SurfaceAlt is for buttons + elevated chips.
// Body text is pure white (InkPrimary) per the user's "font is white"
// rule — accent colors are reserved for non-text decoration only.
// PassGreen / FailRed bumped saturation so they read clearly against
// the dark backgrounds. Solid colors are the "low" end of each surface;
// the matching gradient brushes blend to a darker shade for depth (see
// headerBrush / surfaceBrush).

internal val ScreenBg    = Color(0xFF181B22)
internal val SurfaceBg   = Color(0xFF22252C)
internal val SurfaceAlt  = Color(0xFF2E323A)
internal val BorderGray  = Color(0xFF3A3C42)

// Accent draws box outlines, selector buttons, stripes, and other
// non-body-text decoration. Bright white now, per the user's move away
// from the burnt-orange (former value 0xFFB85419, kept here in case we
// ever want it back).
//
// AccentDim is the dimmed counterpart that drives the selection state of
// reactive/selectable buttons: an UNCHOSEN selector shows a dim outline +
// dim text (AccentDim); when CHOSEN it switches to full-bright Accent.
// The button container stays dark in both states so the brightness change
// alone carries the meaning — no fill.
internal val Accent      = Color.White
internal val AccentDim   = Color(0xFF808080)
internal val PassGreen   = Color(0xFF22C55E)
internal val BrightGreen = Color(0xFF22FF66)
internal val FailRed     = Color(0xFFEF4444)
internal val NeutralGray = Color(0xFFB8B8C0)
internal val SectionGray = Color(0xFFB8B8C0)
internal val InkPrimary  = Color.White
internal val InkMuted    = Color(0xFFB8B8C0)

// Subtle angular shape used on the home menu + Test SSM2 Probe button
// for the Y2K "terminal panel" feel. Sparing — most surfaces stay
// rounded. Currently used by HomeMenuButton, ModeButton,
// SmallActionButton, SmallLogButton, SettingsButton, the Test SSM2
// Probe button.
internal fun y2kCornerShape() = CutCornerShape(topEnd = 10.dp, bottomStart = 10.dp)
internal fun y2kLeftButtonShape() = CutCornerShape(bottomStart = 10.dp)
internal fun y2kRightButtonShape() = CutCornerShape(topEnd = 10.dp)
internal fun y2kBottomEndCutShape() = CutCornerShape(bottomEnd = 10.dp)
// Top-only / bottom-only cuts to frame a stacked button group (Settings on top,
// Notices on the bottom).
internal fun y2kTopCutShape() = CutCornerShape(topStart = 10.dp, topEnd = 10.dp)
internal fun y2kBottomCutShape() = CutCornerShape(bottomStart = 10.dp, bottomEnd = 10.dp)
// Small right-side / left-side cuts for the log action buttons (Clear Log on the
// left cuts its right corners; Export CSV on the right cuts its left corners).
internal fun y2kRightCutShape() = CutCornerShape(topEnd = 6.dp, bottomEnd = 6.dp)
internal fun y2kLeftCutShape() = CutCornerShape(topStart = 6.dp, bottomStart = 6.dp)

/**
 * Button fill only, dimmed by the Configuration transparency slider. Borders and
 * labels are not routed through here, so a fully transparent button is still a
 * white outline with a white label.
 *
 * Log surfaces deliberately do not read this — they must stay legible over the
 * background photo.
 */
internal val LocalButtonFill = androidx.compose.runtime.compositionLocalOf { SurfaceBg }

// Vertical gradient brushes — top of the surface a touch lighter than
// the bottom, gives buttons and panels visible depth without needing
// real shadows or textures. headerBrush is used by ProtocolHeader.
// surfaceBrush is available for future card backgrounds.
internal val headerBrush = Brush.verticalGradient(
    colors = listOf(Color(0xFF2A2D34), Color(0xFF1A1C22))
)
internal val surfaceBrush = Brush.verticalGradient(
    colors = listOf(Color(0xFF272A31), Color(0xFF1B1D24))
)
