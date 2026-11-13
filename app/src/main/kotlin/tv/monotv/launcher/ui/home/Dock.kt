package tv.monotv.launcher.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.nav.Section
import tv.monotv.launcher.ui.components.ArtCard
import tv.monotv.launcher.ui.components.FrostSource
import tv.monotv.launcher.ui.components.GlassBox
import tv.monotv.launcher.ui.components.LocalPointerHandler
import tv.monotv.launcher.ui.components.PointerTarget
import tv.monotv.launcher.ui.components.Txt
import tv.monotv.launcher.ui.components.rememberArtwork
import tv.monotv.launcher.ui.theme.Dimens
import tv.monotv.launcher.ui.theme.Mono

/** Room above the glass panel for the focused app's name. */
internal val DockLabelHeight = 26.dp

/** Card width for [count] dock items: 128dp normally, shrinking (16:9 kept) so 6–7 items still fit the safe area. */
internal fun dockCardWidth(count: Int): Dp {
    if (count <= 0) return Dimens.DockCardWidth
    val fit = (Dimens.DockMaxWidth - Dimens.DockPadding * 2 - Dimens.DockGap * (count - 1)) / count
    return min(Dimens.DockCardWidth, fit)
}

internal fun dockPanelHeight(count: Int): Dp = dockCardWidth(count) * 9f / 16f + Dimens.DockPadding * 2

/**
 * The Dock: a frosted glass panel with 4–7 app cards.
 *
 * Focus here is one physical object — a pool of light under the cards plus a small indicator bar and the app's name
 * above the panel — that GLIDES from the previous app to the next on a spring and takes on the colour of the app it
 * lands on. The focused card itself rises (scale + lift); its neighbours dim a touch. No focus ring.
 *
 * @param focusedIndex index of the focused card, or null while the dock is not the focused section
 * @param reorder      move mode: the focused card is "picked up"
 * @param skeletonCount number of placeholder cards to show while the app list is still loading
 */
@Composable
fun Dock(
    apps: List<AppEntry>,
    focusedIndex: Int?,
    reorder: Boolean,
    frost: FrostSource,
    skeletonCount: Int,
    modifier: Modifier = Modifier,
) {
    val count = if (apps.isEmpty()) skeletonCount else apps.size
    if (count <= 0) return
    // Skeleton tiles (apps still loading) are not focusable: no light, no label.
    @Suppress("NAME_SHADOWING")
    val focusedIndex = if (apps.isEmpty()) null else focusedIndex
    val colors = Mono.colors
    val motion = Mono.motion
    val pointer = LocalPointerHandler.current

    val gap = Dimens.DockGap
    val pad = Dimens.DockPadding
    val cardW = dockCardWidth(count)
    val cardH = cardW * 9f / 16f
    val panelW = cardW * count + gap * (count - 1) + pad * 2
    val panelH = cardH + pad * 2

    // The light keeps its last position while the dock is unfocused, so it re-appears where the user left it.
    // (A plain holder rather than snapshot state: it is written during composition.)
    val lastIndex = remember { intArrayOf(0) }
    if (focusedIndex != null) lastIndex[0] = focusedIndex
    val lensIndex = lastIndex[0].coerceIn(0, count - 1)
    val lensX by animateDpAsState(pad + (cardW + gap) * lensIndex, motion.glide(), label = "dockLensX")
    val lensAlpha by animateFloatAsState(if (focusedIndex != null) 1f else 0f, motion.fade(), label = "dockLensAlpha")

    val focusedApp = apps.getOrNull(lensIndex)
    val focusedArt = rememberArtwork(focusedApp?.banner, cardW, cardH)
    val lightColor by animateColorAsState(
        targetValue = focusedArt?.accent?.let { Color(it) } ?: colors.accent,
        animationSpec = motion.fade(),
        label = "dockLight",
    )

    Column(modifier.width(panelW)) {
        // Name of the focused app; it rides along with the light.
        Box(Modifier.fillMaxWidth().height(DockLabelHeight)) {
            val name = focusedApp?.label.orEmpty()
            Txt(
                text = if (reorder) "‹  $name  ›" else name,
                style = Mono.type.caption,
                color = colors.textPrimary,
                modifier = Modifier
                    .graphicsLayer { alpha = lensAlpha }
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints.copy(minWidth = 0))
                        layout(constraints.maxWidth, placeable.height) {
                            val center = (lensX + cardW / 2).roundToPx()
                            val x = (center - placeable.width / 2).coerceIn(0, (constraints.maxWidth - placeable.width).coerceAtLeast(0))
                            placeable.place(x, 0)
                        }
                    },
            )
        }
        GlassBox(Modifier.size(panelW, panelH), corner = Dimens.DockCorner, frost = frost, fill = colors.glassFill) {
            // The gliding light: a colour pool behind the cards and an indicator bar under them.
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val a = lensAlpha
                    if (a <= 0.004f) return@drawBehind
                    val cx = lensX.toPx() + cardW.toPx() / 2f
                    if (motion.rich) {
                        val radius = cardW.toPx() * 0.95f
                        val center = Offset(cx, size.height * 0.62f)
                        drawCircle(
                            brush = Brush.radialGradient(
                                0f to lightColor.copy(alpha = 0.50f * a), 0.5f to lightColor.copy(alpha = 0.18f * a), 1f to Color.Transparent,
                                center = center, radius = radius,
                            ),
                            radius = radius, center = center,
                        )
                    }
                    val barW = 22.dp.toPx()
                    val barH = 3.dp.toPx()
                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.92f * a),
                        topLeft = Offset(cx - barW / 2f, size.height - 5.5.dp.toPx() - barH / 2f),
                        size = Size(barW, barH),
                        cornerRadius = CornerRadius(barH / 2f),
                    )
                },
            )
            Row(Modifier.padding(pad), horizontalArrangement = Arrangement.spacedBy(gap)) {
                if (apps.isEmpty()) {
                    repeat(count) {
                        Box(Modifier.size(cardW, cardH).drawBehind { drawRoundRect(colors.glassFill, cornerRadius = CornerRadius(11.dp.toPx())) })
                    }
                } else {
                    apps.forEachIndexed { index, app ->
                        val focused = focusedIndex == index
                        ArtCard(
                            source = app.banner,
                            label = app.label,
                            width = cardW,
                            height = cardH,
                            focused = focused,
                            focusScale = motion.dockFocusScale,
                            corner = 11.dp,
                            showTileLabel = false,
                            dim = if (focusedIndex != null && !focused) 0.24f else 0f,
                            lifted = reorder && focused,
                            glow = false,
                            onTap = { pointer(PointerTarget.HomeItem(Section.Dock, index)) },
                        )
                    }
                }
            }
        }
    }
}
