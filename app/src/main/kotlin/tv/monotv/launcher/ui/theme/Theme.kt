package tv.monotv.launcher.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import tv.monotv.launcher.R
import tv.monotv.launcher.core.AccentColor
import tv.monotv.launcher.core.EffectsLevel
import tv.monotv.launcher.core.ThemeMode

// =====================================================================================================================
// Design canvas
// =====================================================================================================================

/**
 * The whole UI is designed on a 960 x 540 dp canvas (the classic Android TV canvas: 1080p @ xhdpi, 4K @ xxxhdpi).
 * [MonoTheme] overrides LocalDensity so that this holds on ANY panel resolution / reported dpi: 1 design dp is
 * always min(widthPx / 960, heightPx / 540) pixels. Layouts therefore stay proportional on 1920x1080, 3840x2160,
 * 1280x720 and odd-density firmwares. On wider-than-16:9 panels the canvas is 540dp tall and wider than 960dp, so
 * layouts must use fillMaxWidth / alignment rather than fixed x positions.
 */
object DesignCanvas {
    const val WIDTH_DP = 960f
    const val HEIGHT_DP = 540f
}

// =====================================================================================================================
// Colours
// =====================================================================================================================

@Immutable
data class MonoColors(
    /** Window / page background. */
    val background: Color,
    /** Slightly lifted surface for opaque panels (settings pane). */
    val surface: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val textTertiary: Color,
    /** Translucent white fill of glass panels. */
    val glassFill: Color,
    /** Fill of a glass element that is focused / pressed-in. */
    val glassFillStrong: Color,
    /** Top edge highlight of glass (stroke gradient start). */
    val glassStrokeTop: Color,
    /** Bottom edge of glass (stroke gradient end). */
    val glassStrokeBottom: Color,
    /** Colour of the dark scrims laid over artwork. Always the background colour family. */
    val scrim: Color,
    val accent: Color,
    /** Text/icon colour on top of an accent-filled element. */
    val onAccent: Color,
    /** Fill of a focused solid button / row ("physical" white slab, tvOS style). */
    val focusFill: Color,
    /** Text/icon colour on [focusFill]. */
    val onFocusFill: Color,
)

fun monoColors(theme: ThemeMode, accent: AccentColor): MonoColors {
    val bg = when (theme) {
        ThemeMode.Black -> Color(0xFF000000)
        ThemeMode.Graphite -> Color(0xFF0B0D10)
    }
    val surface = when (theme) {
        ThemeMode.Black -> Color(0xFF0C0D0F)
        ThemeMode.Graphite -> Color(0xFF14171B)
    }
    val accentColor = Color(accent.argb)
    return MonoColors(
        background = bg,
        surface = surface,
        textPrimary = Color(0xF5FFFFFF),
        textSecondary = Color(0xA8FFFFFF),
        textTertiary = Color(0x66FFFFFF),
        glassFill = Color(0x14FFFFFF),
        glassFillStrong = Color(0x29FFFFFF),
        glassStrokeTop = Color(0x38FFFFFF),
        glassStrokeBottom = Color(0x0AFFFFFF),
        scrim = bg,
        accent = accentColor,
        onAccent = if (accent == AccentColor.Snow || accent == AccentColor.Lime || accent == AccentColor.Amber) Color(0xFF0A0B0C) else Color(0xFF04140C),
        focusFill = Color(0xFFF4F5F7),
        onFocusFill = Color(0xFF0A0B0C),
    )
}

// =====================================================================================================================
// Typography — Inter (text) and Inter Display (large sizes). Static, subset TTFs: Latin + Cyrillic.
// =====================================================================================================================

val InterFamily = FontFamily(
    Font(R.font.inter_light, FontWeight.Light),
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val InterDisplayFamily = FontFamily(
    Font(R.font.inter_display_thin, FontWeight.Thin),
    Font(R.font.inter_display_extralight, FontWeight.ExtraLight),
    Font(R.font.inter_display_light, FontWeight.Light),
    Font(R.font.inter_display_regular, FontWeight.Normal),
    Font(R.font.inter_display_medium, FontWeight.Medium),
    Font(R.font.inter_display_semibold, FontWeight.SemiBold),
    Font(R.font.inter_display_bold, FontWeight.Bold),
)

private val TightLines = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both)

/** Text styles carry no colour; pass colour at the call site from [MonoColors]. Sizes are tuned for a 3 m viewing distance. */
@Immutable
data class MonoType(
    /** 19:42 — top-left clock. Tabular digits so the width never jitters. */
    val clock: TextStyle = TextStyle(
        fontFamily = InterDisplayFamily, fontWeight = FontWeight.ExtraLight, fontSize = 44.sp, lineHeight = 46.sp,
        letterSpacing = (-0.02).em, fontFeatureSettings = "tnum", lineHeightStyle = TightLines,
    ),
    /** 13 ноября, пятница */
    val date: TextStyle = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.005.em,
    ),
    /** Добрый вечер */
    val greeting: TextStyle = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.02.em,
    ),
    /** Small uppercase line above the hero title. */
    val eyebrow: TextStyle = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.14.em,
    ),
    /** Hero title. */
    val hero: TextStyle = TextStyle(
        fontFamily = InterDisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 44.sp,
        letterSpacing = (-0.022).em, lineHeightStyle = TightLines,
    ),
    /** Hero description, settings descriptions. */
    val body: TextStyle = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 13.5.sp, lineHeight = 19.sp, letterSpacing = 0.em,
    ),
    /** Section headers: "Продолжить просмотр", "Приложения". */
    val section: TextStyle = TextStyle(
        fontFamily = InterDisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 22.sp, letterSpacing = (-0.01).em,
    ),
    /** Big page titles in settings. */
    val title: TextStyle = TextStyle(
        fontFamily = InterDisplayFamily, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.018).em,
    ),
    /** Buttons, settings rows, menu rows. */
    val label: TextStyle = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.em,
    ),
    /** Card titles, pills. */
    val caption: TextStyle = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Medium, fontSize = 11.5.sp, lineHeight = 15.sp, letterSpacing = 0.005.em,
    ),
    /** Card subtitles, tiny meta text. */
    val micro: TextStyle = TextStyle(
        fontFamily = InterFamily, fontWeight = FontWeight.Normal, fontSize = 10.sp, lineHeight = 13.sp, letterSpacing = 0.01.em,
    ),
)

// =====================================================================================================================
// Dimensions (design dp on the 960x540 canvas)
// =====================================================================================================================

object Dimens {
    /** Overscan-safe margins (5 % of the canvas, Android TV guidance). Backdrops bleed past them, content never does. */
    val SafeH = 48.dp
    val SafeV = 27.dp

    // Cards
    val CardCorner = 12.dp
    val AppCardWidth = 160.dp
    val AppCardHeight = 90.dp
    val AppGridGap = 16.dp
    /** Vertical room under a grid row for the focused card's label. */
    val AppRowLabelSpace = 26.dp
    val ContinueCardWidth = 208.dp
    val ContinueCardHeight = 117.dp
    val ContinueGap = 16.dp

    // Dock
    val DockCardWidth = 128.dp
    val DockCardHeight = 72.dp
    val DockGap = 14.dp
    val DockPadding = 14.dp
    val DockCorner = 26.dp
    /** Largest dock width; cards shrink (keeping 16:9) when dockSize would exceed it. */
    val DockMaxWidth = 864.dp

    // Top bar
    val PillHeight = 30.dp
    val PillGap = 8.dp

    // Glass
    val GlassStroke = 1.dp
    val PanelCorner = 22.dp
    val RowCorner = 12.dp
    val RowHeight = 44.dp
}

// =====================================================================================================================
// Motion
// =====================================================================================================================

/** iOS-like "ease out" used for fades and crossfades. */
val EaseOut = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
val EaseInOut = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)

/**
 * All animation specs come from here so the "Animation intensity" setting and the automatic low-end fallback can
 * retune the whole UI at once. Nothing in the UI may run an infinite / repeating animation.
 */
@Immutable
class Motion(val level: EffectsLevel) {
    val enabled: Boolean get() = level != EffectsLevel.Off

    /** Glows, ambient tint, parallax, specular sheen, rubber-band bump: only at Full. */
    val rich: Boolean get() = level == EffectsLevel.Full

    /** Focus scale / lift of cards and pills. Slightly under-damped so focus "lands" like a physical object. */
    fun <T> focus(): AnimationSpec<T> = when (level) {
        EffectsLevel.Full -> spring(dampingRatio = 0.72f, stiffness = 420f)
        EffectsLevel.Reduced -> tween(140, easing = EaseOut)
        EffectsLevel.Off -> snap()
    }

    /** The sliding highlight that travels between neighbouring items (dock lens, row selection slab). */
    fun <T> glide(): AnimationSpec<T> = when (level) {
        EffectsLevel.Full -> spring(dampingRatio = 0.82f, stiffness = 520f)
        EffectsLevel.Reduced -> tween(160, easing = EaseOut)
        EffectsLevel.Off -> snap()
    }

    /** Whole-page movement: Home <-> Library scroll, grid row scroll. Critically damped, no overshoot. */
    fun <T> page(): AnimationSpec<T> = when (level) {
        EffectsLevel.Full -> spring(dampingRatio = 1f, stiffness = 210f)
        EffectsLevel.Reduced -> tween(220, easing = EaseOut)
        EffectsLevel.Off -> snap()
    }

    /** Opacity changes of labels, scrims, dimming. */
    fun <T> fade(): FiniteAnimationSpec<T> = when (level) {
        EffectsLevel.Full -> tween(260, easing = EaseOut)
        EffectsLevel.Reduced -> tween(140, easing = EaseOut)
        EffectsLevel.Off -> snap()
    }

    /** Hero / backdrop crossfade. */
    fun <T> crossfade(): FiniteAnimationSpec<T> = when (level) {
        EffectsLevel.Full -> tween(620, easing = EaseInOut)
        EffectsLevel.Reduced -> tween(240, easing = EaseOut)
        EffectsLevel.Off -> snap()
    }

    /** Overlays (settings, inputs menu, dialogs) appearing / disappearing. */
    fun <T> overlay(): FiniteAnimationSpec<T> = when (level) {
        EffectsLevel.Full -> tween(300, easing = EaseOut)
        EffectsLevel.Reduced -> tween(160, easing = EaseOut)
        EffectsLevel.Off -> snap()
    }

    /** Scale of a focused card (the spec asks for roughly 1.06–1.10). */
    val cardFocusScale: Float get() = if (level == EffectsLevel.Off) 1.04f else 1.08f
    val dockFocusScale: Float get() = if (level == EffectsLevel.Off) 1.05f else 1.10f
    val pillFocusScale: Float get() = if (level == EffectsLevel.Off) 1.03f else 1.06f
}

// =====================================================================================================================
// Theme
// =====================================================================================================================

val LocalMonoColors = staticCompositionLocalOf { monoColors(ThemeMode.Black, AccentColor.Emerald) }
val LocalMonoType = staticCompositionLocalOf { MonoType() }
val LocalMotion = staticCompositionLocalOf { Motion(EffectsLevel.Full) }

object Mono {
    val colors: MonoColors @Composable get() = LocalMonoColors.current
    val type: MonoType @Composable get() = LocalMonoType.current
    val motion: Motion @Composable get() = LocalMotion.current
}

/**
 * Root of the launcher UI: installs colours, type, motion and the 960x540 design canvas density.
 * Content gets the full window size; inside, `dp` and `sp` are design units.
 */
@Composable
fun MonoTheme(
    theme: ThemeMode = ThemeMode.Black,
    accent: AccentColor = AccentColor.Emerald,
    effects: EffectsLevel = EffectsLevel.Full,
    content: @Composable () -> Unit,
) {
    val colors = remember(theme, accent) { monoColors(theme, accent) }
    val motion = remember(effects) { Motion(effects) }
    val type = remember { MonoType() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val scale = minOf(widthPx / DesignCanvas.WIDTH_DP, heightPx / DesignCanvas.HEIGHT_DP).coerceAtLeast(0.5f)
        val density = remember(scale) { Density(density = scale, fontScale = 1f) }
        CompositionLocalProvider(
            LocalDensity provides density,
            LocalMonoColors provides colors,
            LocalMonoType provides type,
            LocalMotion provides motion,
            content = content,
        )
    }
}
