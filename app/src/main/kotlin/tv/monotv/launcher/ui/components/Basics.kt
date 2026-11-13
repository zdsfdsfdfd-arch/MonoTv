package tv.monotv.launcher.ui.components

import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.Artwork
import tv.monotv.launcher.core.LocalArtworkLoader
import tv.monotv.launcher.nav.Section
import kotlin.math.max
import kotlin.math.roundToInt

/** Single text primitive of the launcher (no Material). Colour is read at draw time, so animating it never recomposes. */
@Composable
fun Txt(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    textAlign: TextAlign? = null,
) {
    BasicText(
        text = text,
        modifier = modifier,
        style = if (textAlign != null) style.copy(textAlign = textAlign) else style,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        color = { color },
    )
}

/** Same as [Txt] but the colour is a lambda read at draw time (for colours animated through a State). */
@Composable
fun Txt(
    text: String,
    style: TextStyle,
    color: () -> Color,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
) {
    BasicText(
        text = text, modifier = modifier, style = style, maxLines = maxLines, overflow = TextOverflow.Ellipsis,
        color = { color() },
    )
}

/**
 * Resolves [source] to pixels at (roughly) the on-screen size. Shows the memory-cached bitmap on the very first frame
 * when there is one; otherwise loads off the main thread. Only composed (= visible) items ever trigger a load, which
 * is what keeps the launcher from decoding every banner at start-up.
 */
@Composable
fun rememberArtwork(source: ArtSource?, width: Dp, height: Dp): Artwork? {
    val loader = LocalArtworkLoader.current
    val density = LocalDensity.current
    val widthPx = with(density) { width.roundToPx() }
    val heightPx = with(density) { height.roundToPx() }
    var art by remember(source, widthPx, heightPx) {
        mutableStateOf(source?.let { loader.cached(it, widthPx, heightPx) })
    }
    LaunchedEffect(source, widthPx, heightPx) {
        if (art == null && source != null) art = loader.load(source, widthPx, heightPx)
    }
    return art
}

/** Draws [image] scaled to cover [dstSize] (centre crop), like ContentScale.Crop but without any Image composable. */
fun DrawScope.drawImageCover(
    image: ImageBitmap,
    dstSize: Size = size,
    dstOffset: Offset = Offset.Zero,
    alpha: Float = 1f,
    filterQuality: FilterQuality = FilterQuality.Low,
    colorFilter: ColorFilter? = null,
) {
    if (dstSize.width <= 0f || dstSize.height <= 0f || image.width == 0 || image.height == 0) return
    val scale = max(dstSize.width / image.width, dstSize.height / image.height)
    val srcW = (dstSize.width / scale).roundToInt().coerceIn(1, image.width)
    val srcH = (dstSize.height / scale).roundToInt().coerceIn(1, image.height)
    val srcX = ((image.width - srcW) / 2).coerceAtLeast(0)
    val srcY = ((image.height - srcH) / 2).coerceAtLeast(0)
    drawImage(
        image = image,
        srcOffset = IntOffset(srcX, srcY),
        srcSize = IntSize(srcW, srcH),
        dstOffset = IntOffset(dstOffset.x.roundToInt(), dstOffset.y.roundToInt()),
        dstSize = IntSize(dstSize.width.roundToInt(), dstSize.height.roundToInt()),
        alpha = alpha,
        colorFilter = colorFilter,
        filterQuality = filterQuality,
    )
}

/**
 * Pointer (air-mouse / phone-remote touchpad) support. The D-pad is the primary input; a tap on an item simply asks
 * the ViewModel to focus and activate it, so pointer and D-pad always share one focus model.
 */
sealed interface PointerTarget {
    data class HomeItem(val section: Section, val index: Int) : PointerTarget

    /** A row of the top overlay (inputs menu, settings page, app menu) or a confirm button. */
    data class OverlayRow(val index: Int) : PointerTarget

    /** Tap on the dimmed area outside an overlay. */
    data object Outside : PointerTarget
}

val LocalPointerHandler = staticCompositionLocalOf<(PointerTarget) -> Unit> { {} }
