package tv.monotv.launcher.ui.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.ui.components.Icon
import tv.monotv.launcher.ui.components.LocalPointerHandler
import tv.monotv.launcher.ui.components.MonoIcon
import tv.monotv.launcher.ui.components.PointerTarget
import tv.monotv.launcher.ui.components.Txt
import tv.monotv.launcher.ui.components.tappable
import tv.monotv.launcher.ui.components.drawImageCover
import tv.monotv.launcher.ui.components.drawSoftShadow
import tv.monotv.launcher.ui.components.rememberArtwork
import tv.monotv.launcher.ui.theme.Dimens
import tv.monotv.launcher.ui.theme.Mono

enum class RowTrailing { None, Chevron, Check, Empty, Reorder }

/** Visual model of one row of any menu (settings page, inputs menu, app menu). */
@Immutable
data class MenuRow(
    val title: String,
    val description: String? = null,
    val value: String? = null,
    val icon: MonoIcon? = null,
    val appKey: AppKey? = null,
    val trailing: RowTrailing = RowTrailing.None,
    val enabled: Boolean = true,
    /** Small status dot before the value (e.g. "input connected"). */
    val statusDot: Boolean = false,
)

private val PlainRow = Dimens.RowHeight
private val TallRow = 58.dp
private val RowGap = 4.dp

private fun rowHeight(row: MenuRow): Dp = if (row.description.isNullOrBlank()) PlainRow else TallRow

/**
 * A vertical menu whose selection is a solid light slab that glides from row to row (and stretches to the height of
 * the row it lands on). The list scrolls by itself to keep the selection in view; only rows near the viewport are
 * composed, so a 100-app list in Settings stays cheap.
 *
 * @param grabbed the focused row is picked up (reorder lists): the slab takes the accent colour.
 */
@Composable
fun MenuRows(
    rows: List<MenuRow>,
    focusedIndex: Int,
    viewportHeight: Dp,
    modifier: Modifier = Modifier,
    grabbed: Boolean = false,
) {
    if (rows.isEmpty()) return
    val colors = Mono.colors
    val motion = Mono.motion
    val pointer = LocalPointerHandler.current
    val focus = focusedIndex.coerceIn(0, rows.lastIndex)

    // Row geometry (tiny lists; recomputed only when rows change).
    val tops = ArrayList<Dp>(rows.size)
    var y = 0.dp
    for (row in rows) {
        tops.add(y)
        y += rowHeight(row) + RowGap
    }
    val contentHeight = (y - RowGap).coerceAtLeast(0.dp)
    val focusTop = tops[focus]
    val focusHeight = rowHeight(rows[focus])
    val maxScroll = (contentHeight - viewportHeight).coerceAtLeast(0.dp)
    val targetScroll = (focusTop + focusHeight / 2 - viewportHeight / 2).coerceIn(0.dp, maxScroll)

    val scroll by animateDpAsState(targetScroll, motion.page(), label = "menuScroll")
    val slabY by animateDpAsState(focusTop, motion.glide(), label = "slabY")
    val slabH by animateDpAsState(focusHeight, motion.glide(), label = "slabH")
    val slabColor by animateColorAsState(if (grabbed) colors.accent else colors.focusFill, motion.fade(), label = "slabColor")
    val grab by animateFloatAsState(if (grabbed) 1f else 0f, motion.focus(), label = "grab")

    Box(modifier.fillMaxWidth().height(minOf(viewportHeight.value, contentHeight.value).dp).clip(RoundedCornerShape(Dimens.RowCorner))) {
        Box(Modifier.fillMaxWidth().graphicsLayer { translationY = -scroll.toPx() }) {
            Box(
                Modifier
                    .offset(y = slabY)
                    .fillMaxWidth()
                    .height(slabH)
                    .drawBehind {
                        drawSoftShadow(Dimens.RowCorner.toPx(), alpha = 0.5f + 0.5f * grab)
                        drawRoundRect(slabColor, cornerRadius = CornerRadius(Dimens.RowCorner.toPx()))
                    },
            )
            val visibleFrom = targetScroll - 120.dp
            val visibleTo = targetScroll + viewportHeight + 120.dp
            rows.forEachIndexed { index, row ->
                val top = tops[index]
                if (top + rowHeight(row) < visibleFrom || top > visibleTo) return@forEachIndexed
                val focused = index == focus
                MenuRowContent(
                    row = row,
                    focused = focused,
                    grabbed = grabbed && focused,
                    modifier = Modifier
                        .offset(y = top)
                        .height(rowHeight(row))
                        .tappable { pointer(PointerTarget.OverlayRow(index)) },
                )
            }
        }
    }
}

@Composable
private fun MenuRowContent(row: MenuRow, focused: Boolean, grabbed: Boolean, modifier: Modifier = Modifier) {
    val colors = Mono.colors
    val type = Mono.type
    val onSlab = if (grabbed) colors.onAccent else colors.onFocusFill
    val primary = when {
        focused -> onSlab
        !row.enabled -> colors.textTertiary
        else -> colors.textPrimary
    }
    val secondary = if (focused) onSlab.copy(alpha = 0.62f) else colors.textSecondary.copy(alpha = if (row.enabled) 0.66f else 0.4f)
    Row(
        modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                contentDescription = listOfNotNull(row.title, row.value).joinToString(", ")
                selected = focused
                role = Role.Button
            }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            row.appKey != null -> {
                AppThumb(row.appKey, dimmed = !row.enabled && !focused)
                Spacer(Modifier.width(12.dp))
            }
            row.icon != null -> {
                Icon(row.icon, primary, Modifier.size(17.dp))
                Spacer(Modifier.width(12.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Txt(row.title, type.label, primary)
            if (!row.description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Txt(row.description, type.micro, secondary)
            }
        }
        if (row.statusDot) {
            val dot = if (focused) onSlab else colors.accent
            Box(Modifier.size(7.dp).drawBehind { drawCircle(dot) })
            Spacer(Modifier.width(2.dp))
        }
        if (!row.value.isNullOrBlank()) {
            Spacer(Modifier.width(10.dp))
            Txt(row.value, type.caption, secondary)
        }
        when (row.trailing) {
            RowTrailing.None -> Unit
            RowTrailing.Chevron -> {
                Spacer(Modifier.width(6.dp))
                Icon(MonoIcon.ChevronRight, secondary, Modifier.size(15.dp))
            }
            RowTrailing.Check -> {
                Spacer(Modifier.width(8.dp))
                Icon(MonoIcon.Check, if (focused) onSlab else colors.accent, Modifier.size(16.dp))
            }
            RowTrailing.Empty -> {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier.size(16.dp).drawBehind {
                        drawCircle(secondary.copy(alpha = 0.5f), radius = size.minDimension * 0.34f, style = Stroke(1.4.dp.toPx()))
                    },
                )
            }
            RowTrailing.Reorder -> {
                Spacer(Modifier.width(8.dp))
                Icon(MonoIcon.Reorder, if (grabbed) onSlab else secondary, Modifier.size(16.dp))
            }
        }
    }
}

/** Tiny 16:9 thumbnail of an app (banner, or its icon on a dark chip). */
@Composable
internal fun AppThumb(key: AppKey, dimmed: Boolean = false, width: Dp = 46.dp) {
    val height = width * 9f / 16f
    val art = rememberArtwork(ArtSource.AppBanner(key), width, height)
    Box(
        Modifier
            .size(width, height)
            .clip(RoundedCornerShape(5.dp))
            .drawBehind {
                drawRect(Color(0xFF15181C))
                if (art != null) {
                    if (art.isBanner) {
                        drawImageCover(art.image, filterQuality = FilterQuality.Medium)
                    } else {
                        val side = size.height * 0.8f
                        drawImageCover(
                            art.image, dstSize = Size(side, side),
                            dstOffset = Offset((size.width - side) / 2f, (size.height - side) / 2f),
                            filterQuality = FilterQuality.Medium,
                        )
                    }
                }
                if (dimmed) drawRect(Color.Black, alpha = 0.5f)
            },
    )
}
