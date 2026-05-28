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

// Accent is the burnt-orange tone the user settled on (the "shaded"
// probe-button color). Less saturated than #FF6A00 so it reads with
// depth rather than retina burn. Used for non-text decoration only:
// hamburger lines, close-X strokes, header/footer stripes, edit-mode
// gauge border, drag-bar visuals, button outlines.
internal val Accent      = Color(0xFFB85419)
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
