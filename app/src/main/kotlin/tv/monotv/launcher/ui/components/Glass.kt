package tv.monotv.launcher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.monotv.launcher.ui.theme.Dimens
import tv.monotv.launcher.ui.theme.Mono

/**
 * What a frosted panel shows "through" itself: tiny pre-blurred copies of the backdrop (64x36 px, blurred once on a
 * background thread) plus the backdrop's crossfade progress. Drawing them stretched over the screen costs nothing per
 * frame — there is NO runtime blur anywhere in the launcher, which is what makes glass affordable on a Mali-G31.
 */
@Stable
class FrostSource {
    var current: ImageBitmap? by mutableStateOf(null)
    var previous: ImageBitmap? by mutableStateOf(null)
    val fade = Animatable(1f)

    /** Size of the root the backdrop covers, in px. */
    var rootSize: Size by mutableStateOf(Size.Zero)
}

/**
 * A glass surface: optional frosted backdrop, translucent fill, a soft top sheen and a hairline edge whose brightness
 * falls off from top to bottom (so it reads as a lit edge, not as a border).
 */
@Composable
fun GlassBox(
    modifier: Modifier = Modifier,
    corner: Dp = Dimens.PanelCorner,
    frost: FrostSource? = null,
    fill: Color = Mono.colors.glassFill,
    /** Extra darkening under the fill, for panels that carry text over bright artwork. */
    shade: Float = 0f,
    sheen: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val colors = Mono.colors
    val origin = remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier
            .then(if (frost != null) Modifier.onGloballyPositioned { origin.value = it.positionInRoot() } else Modifier)
            .drawWithCache {
                val radius = CornerRadius(corner.toPx())
                val outline = Path().apply { addRoundRect(RoundRect(0f, 0f, size.width, size.height, radius)) }
                val edge = Brush.verticalGradient(listOf(colors.glassStrokeTop, colors.glassStrokeBottom))
                val sheenBrush = Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.07f), 0.55f to Color.Transparent, endY = size.height,
                )
                val strokeWidth = Dimens.GlassStroke.toPx()
                onDrawBehind {
                    if (frost != null) {
                        val root = frost.rootSize
                        if (root.width > 0f) {
                            clipPath(outline) {
                                val offset = Offset(-origin.value.x, -origin.value.y)
                                frost.previous?.let { drawImageCover(it, root, offset, filterQuality = FilterQuality.Medium) }
                                frost.current?.let {
                                    drawImageCover(it, root, offset, alpha = frost.fade.value.coerceIn(0f, 1f), filterQuality = FilterQuality.Medium)
                                }
                                drawRect(Color.Black, alpha = 0.34f)
                            }
                        }
                    }
                    if (shade > 0f) drawRoundRect(Color.Black, cornerRadius = radius, alpha = shade)
                    drawRoundRect(fill, cornerRadius = radius)
                    if (sheen) drawRoundRect(sheenBrush, cornerRadius = radius)
                    val inset = strokeWidth / 2f
                    drawRoundRect(
                        brush = edge,
                        topLeft = Offset(inset, inset),
                        size = Size(size.width - strokeWidth, size.height - strokeWidth),
                        cornerRadius = CornerRadius((corner.toPx() - inset).coerceAtLeast(0f)),
                        style = Stroke(strokeWidth),
                    )
                }
            },
        content = content,
    )
}

/** Soft elliptical light pool under a focused element (pure gradient, no blur). [alpha] is read at draw time. */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawGlow(color: Color, alpha: Float, spread: Float = 1f) {
    if (alpha <= 0.005f) return
    val w = size.width
    val h = size.height
    val radius = w * 0.62f * spread
    val squash = (h * 0.95f * spread) / radius
    val center = Offset(w / 2f, h * 0.62f)
    drawContext.canvas.save()
    drawContext.transform.scale(1f, squash, center)
    drawCircle(
        brush = Brush.radialGradient(
            0f to color.copy(alpha = 0.55f * alpha),
            0.45f to color.copy(alpha = 0.22f * alpha),
            1f to Color.Transparent,
            center = center,
            radius = radius,
        ),
        radius = radius,
        center = center,
    )
    drawContext.canvas.restore()
}

/** Layered drop shadow built from a few translucent rounded rects (cheap, works on every API level). */
fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSoftShadow(corner: Float, alpha: Float) {
    if (alpha <= 0.005f) return
    val steps = 5
    for (i in 1..steps) {
        val grow = i * 2.2.dp.toPx()
        val drop = i * 2.6.dp.toPx()
        drawRoundRect(
            color = Color.Black,
            topLeft = Offset(-grow, -grow * 0.4f + drop),
            size = Size(size.width + 2 * grow, size.height + grow * 0.8f),
            cornerRadius = CornerRadius(corner + grow),
            alpha = 0.085f * alpha * (1f - (i - 1) / (steps.toFloat() + 1f)),
        )
    }
}
