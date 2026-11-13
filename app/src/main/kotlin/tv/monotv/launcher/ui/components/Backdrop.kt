package tv.monotv.launcher.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.Artwork
import tv.monotv.launcher.core.LocalArtworkLoader
import tv.monotv.launcher.ui.theme.Mono

/** Full-screen artwork with a crossfade between the previous and the current image. */
@Stable
class BackdropState {
    internal var currentSource: ArtSource? = null
    internal var current: Artwork? by mutableStateOf(null)
    internal var previous: Artwork? by mutableStateOf(null)
    internal val fade = Animatable(0f)

    /** Hand this to [GlassBox] so panels are frosted with the same artwork and the same crossfade. */
    val frost = FrostSource()

    /** Dominant colour of the current artwork, or null before the first image arrived. */
    val accent: Int? get() = current?.accent
}

/**
 * Loads [source] at (at most) 1920x1080 — a 4K UI simply upscales it, which keeps bitmap memory at ~8 MB per layer —
 * and crossfades to it. No video, no continuous animation: the backdrop is static between changes.
 */
@Composable
fun rememberBackdropState(source: ArtSource?): BackdropState {
    val loader = LocalArtworkLoader.current
    val motion = Mono.motion
    val state = remember { BackdropState() }
    LaunchedEffect(source) {
        if (source == null || state.currentSource == source) return@LaunchedEffect
        val root = state.frost.rootSize
        val scale = if (root.width > 1920f) 1920f / root.width else 1f
        val w = if (root.width > 0f) (root.width * scale).toInt() else 1920
        val h = if (root.height > 0f) (root.height * scale).toInt() else 1080
        val sharp = loader.cached(source, w, h) ?: loader.load(source, w, h)
        val blurred = loader.cachedBlurred(source) ?: loader.loadBlurred(source)
        // If a crossfade is still running keep whichever layer is currently dominant as the "from" image.
        if (state.fade.value >= 0.5f || state.previous == null) {
            state.previous = state.current
            state.frost.previous = state.frost.current
        }
        state.currentSource = source
        state.current = sharp
        state.frost.current = blurred?.image
        state.fade.snapTo(0f)
        state.frost.fade.snapTo(0f)
        // The two Animatables run the same spec from the same frame, so glass and backdrop stay in lock-step.
        coroutineScope {
            launch { state.frost.fade.animateTo(1f, motion.crossfade()) }
            state.fade.animateTo(1f, motion.crossfade())
        }
        state.previous = null
        state.frost.previous = null
    }
    return state
}

/**
 * Draws the backdrop plus every scrim in ONE canvas:
 *  - a left-to-right falloff so the hero text always sits on a dark area;
 *  - a bottom falloff grounding the dock and the rows;
 *  - a short top falloff under the clock;
 *  - a uniform dim driven by [page] (0 = home, 1 = library) and [dim] (overlays).
 * [page] and [dim] are read during draw only, so scrolling between pages never recomposes this.
 */
@Composable
fun Backdrop(state: BackdropState, page: () -> Float, dim: () -> Float, modifier: Modifier = Modifier) {
    val colors = Mono.colors
    val rich = Mono.motion.rich
    Canvas(
        modifier
            .fillMaxSize()
            .onSizeChanged { state.frost.rootSize = it.toSize() }
            .graphicsLayer {
                if (rich) {
                    // Gentle parallax: the artwork settles back a little when the library slides over it.
                    val s = 1.045f - 0.03f * page()
                    scaleX = s
                    scaleY = s
                }
            }
            .drawWithCache {
                val scrim = colors.scrim
                val left = Brush.horizontalGradient(
                    0f to scrim.copy(alpha = 0.90f), 0.28f to scrim.copy(alpha = 0.62f), 0.66f to Color.Transparent,
                    endX = size.width,
                )
                val bottom = Brush.verticalGradient(
                    0.38f to Color.Transparent, 0.74f to scrim.copy(alpha = 0.62f), 1f to scrim.copy(alpha = 0.96f),
                    endY = size.height,
                )
                val top = Brush.verticalGradient(
                    0f to scrim.copy(alpha = 0.55f), 0.24f to Color.Transparent, endY = size.height,
                )
                onDrawBehind {
                    drawRect(scrim)
                    state.previous?.let { drawImageCover(it.image, filterQuality = FilterQuality.Medium) }
                    state.current?.let {
                        drawImageCover(it.image, alpha = state.fade.value.coerceIn(0f, 1f), filterQuality = FilterQuality.Medium)
                    }
                    drawRect(left)
                    drawRect(bottom)
                    drawRect(top)
                    val uniform = (0.80f * page() + dim()).coerceIn(0f, 0.94f)
                    if (uniform > 0.003f) drawRect(scrim, alpha = uniform)
                }
            },
    ) {}
}

/**
 * A wide pool of coloured light rising from the bottom edge. Its colour follows the focused dock app, which is the
 * "background changes a little" cue when moving along the dock. One gradient, no blur.
 */
@Composable
fun AmbientGlow(color: Color, visible: Boolean, modifier: Modifier = Modifier) {
    val motion = Mono.motion
    if (!motion.rich) return
    val animated by animateColorAsState(
        targetValue = color.copy(alpha = if (visible) 0.34f else 0f),
        animationSpec = motion.fade(),
        label = "ambient",
    )
    Canvas(modifier.fillMaxSize()) {
        if (animated.alpha < 0.005f) return@Canvas
        val center = Offset(size.width / 2f, size.height * 1.12f)
        val radius = size.width * 0.52f
        drawCircle(
            brush = Brush.radialGradient(
                0f to animated, 0.55f to animated.copy(alpha = animated.alpha * 0.35f), 1f to Color.Transparent,
                center = center, radius = radius,
            ),
            radius = radius,
            center = center,
        )
    }
}
