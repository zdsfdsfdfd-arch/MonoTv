package tv.monotv.launcher.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.Artwork
import tv.monotv.launcher.ui.theme.Dimens
import tv.monotv.launcher.ui.theme.Mono

/**
 * The launcher's one and only "card": artwork in a rounded rectangle that reacts to focus the way a physical object
 * would — it rises (scale + lift), gains a pool of light in its own dominant colour underneath, a soft shadow, and a
 * specular sheen with a lit top edge. There is deliberately no focus ring / border.
 *
 * Everything animated is read in the layer / draw phase, so a focus change costs zero recompositions of the content.
 *
 * @param showTileLabel for icon-only apps: draw the label next to the icon so the tile is self-describing
 * @param dim           0..1 darkening of the picture (used for non-focused neighbours)
 * @param lifted        "picked up" state of move mode: larger scale and lift
 */
@Composable
fun ArtCard(
    source: ArtSource?,
    label: String,
    width: Dp,
    height: Dp,
    focused: Boolean,
    focusScale: Float,
    modifier: Modifier = Modifier,
    corner: Dp = Dimens.CardCorner,
    showTileLabel: Boolean = true,
    dim: Float = 0f,
    lifted: Boolean = false,
    progress: Float? = null,
    /** False when the parent draws one shared, sliding light pool instead (the dock). */
    glow: Boolean = true,
    onTap: (() -> Unit)? = null,
) {
    val motion = Mono.motion
    val colors = Mono.colors
    val art = rememberArtwork(source, width, height)
    val focus: State<Float> = animateFloatAsState(if (focused) 1f else 0f, motion.focus(), label = "cardFocus")
    val lift: State<Float> = animateFloatAsState(if (lifted) 1f else 0f, motion.focus(), label = "cardLift")
    val dimmed: State<Float> = animateFloatAsState(dim, motion.fade(), label = "cardDim")
    val accent = art?.accent?.let { Color(it) } ?: colors.accent
    val rich = motion.rich

    Box(
        modifier
            .zIndex(if (focused || lifted) 1f else 0f)
            .size(width, height)
            .graphicsLayer {
                val f = focus.value
                val l = lift.value
                val s = 1f + (focusScale - 1f) * f + 0.07f * l
                scaleX = s
                scaleY = s
                translationY = -(2.dp.toPx() * f + 9.dp.toPx() * l)
            }
            .drawBehind {
                val f = focus.value.coerceIn(0f, 1f)
                val l = lift.value.coerceIn(0f, 1f)
                if (rich && glow) drawGlow(accent, alpha = f, spread = 1f + 0.12f * l)
                drawSoftShadow(corner.toPx(), alpha = (0.55f + 0.45f * l) * maxOf(f, l))
            }
            .semantics(mergeDescendants = true) {
                contentDescription = label
                selected = focused
                role = Role.Button
            }
            .then(if (onTap != null) Modifier.pointerInput(onTap) { detectTap(onTap) } else Modifier),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(corner))
                .drawWithCache {
                    val radius = CornerRadius(corner.toPx())
                    val sheen = Brush.linearGradient(
                        0f to Color.White.copy(alpha = 0.16f), 0.42f to Color.Transparent,
                        start = Offset.Zero, end = Offset(size.width * 0.8f, size.height * 1.4f),
                    )
                    // A lit TOP edge only (it fades out half-way down), so it reads as light on glass, not as a frame.
                    val rim = Brush.verticalGradient(
                        0f to Color.White.copy(alpha = 0.42f), 0.5f to Color.White.copy(alpha = 0.05f), 1f to Color.Transparent,
                    )
                    val tile = art?.takeIf { !it.isBanner }?.let { tileBrush(Color(it.accent), size) }
                    val rimWidth = 1.dp.toPx()
                    onDrawWithContent {
                        when {
                            art == null -> drawRect(colors.glassFill)
                            art.isBanner -> drawImageCover(art.image, filterQuality = FilterQuality.Medium)
                            else -> drawRect(tile!!)
                        }
                        drawContent()
                        val d = dimmed.value
                        if (d > 0.004f) drawRect(Color.Black, alpha = d)
                        val f = focus.value.coerceIn(0f, 1f)
                        if (f > 0.004f) {
                            drawRect(sheen, alpha = f)
                            drawRoundRect(
                                brush = rim, alpha = f,
                                topLeft = Offset(rimWidth / 2f, rimWidth / 2f),
                                size = Size(size.width - rimWidth, size.height - rimWidth),
                                cornerRadius = CornerRadius((radius.x - rimWidth / 2f).coerceAtLeast(0f)),
                                style = Stroke(rimWidth),
                            )
                        }
                    }
                },
        ) {
            when {
                art == null -> Txt(
                    label, Mono.type.caption, colors.textSecondary,
                    Modifier.align(Alignment.Center).padding(horizontal = 10.dp), maxLines = 2, textAlign = TextAlign.Center,
                )
                !art.isBanner -> IconTile(art, label, height, showTileLabel)
            }
            if (progress != null) {
                ProgressBar(
                    progress = progress,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 10.dp, vertical = 9.dp),
                )
            }
        }
    }
}

/** Tile for apps without a TV banner: the icon on a dark wash of its own dominant colour, plus the app name. */
@Composable
private fun IconTile(art: Artwork, label: String, height: Dp, showLabel: Boolean) {
    val iconSize = height * 0.54f
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .align(if (showLabel) Alignment.CenterStart else Alignment.Center)
                .padding(start = if (showLabel) 14.dp else 0.dp)
                .size(iconSize)
                .drawBehind { drawImageCover(art.image, filterQuality = FilterQuality.Medium) },
        )
        if (showLabel) {
            Txt(
                label, Mono.type.caption, Mono.colors.textPrimary,
                Modifier.align(Alignment.CenterStart).padding(start = 14.dp + iconSize + 10.dp, end = 10.dp), maxLines = 2,
            )
        }
    }
}

private fun tileBrush(accent: Color, size: Size): Brush {
    val base = Color(0xFF0C0E11)
    return Brush.linearGradient(
        0f to lerp(base, accent, 0.46f), 1f to lerp(base, accent, 0.10f),
        start = Offset.Zero, end = Offset(size.width, size.height),
    )
}

/** Thin watch-progress bar ("████████░░"). Only drawn for sources that really know the progress. */
@Composable
fun ProgressBar(progress: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    val accent = Mono.colors.accent
    Box(
        modifier
            .fillMaxSize()
            .drawBehind {
                val h = height.toPx()
                val y = size.height - h
                val radius = CornerRadius(h / 2f)
                drawRoundRect(Color.White.copy(alpha = 0.26f), topLeft = Offset(0f, y), size = Size(size.width, h), cornerRadius = radius)
                val w = size.width * progress.coerceIn(0f, 1f)
                if (w > 0f) drawRoundRect(accent, topLeft = Offset(0f, y), size = Size(maxOf(w, h), h), cornerRadius = radius)
            },
    )
}

internal suspend fun PointerInputScope.detectTap(onTap: () -> Unit) {
    detectTapGestures(onTap = { onTap() })
}
