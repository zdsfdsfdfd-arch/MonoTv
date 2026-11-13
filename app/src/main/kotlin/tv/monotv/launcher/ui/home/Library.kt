package tv.monotv.launcher.ui.home

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.ContinueItem
import tv.monotv.launcher.nav.Section
import tv.monotv.launcher.ui.components.ArtCard
import tv.monotv.launcher.ui.components.LocalPointerHandler
import tv.monotv.launcher.ui.components.PointerTarget
import tv.monotv.launcher.ui.components.Txt
import tv.monotv.launcher.ui.theme.Dimens
import tv.monotv.launcher.ui.theme.Mono

/** Width of the content column: 5 app cards + 4 gaps = exactly the 864dp between the safe margins of a 960dp canvas. */
internal val LibraryWidth = Dimens.AppCardWidth * 5 + Dimens.AppGridGap * 4

/** Screen y at which library content starts (below the clock block). */
internal val LibraryTop = 96.dp

internal val LibraryHeaderBlock = 38.dp   // section title + gap to its cards
private val HeaderBlock = LibraryHeaderBlock
private val ContinueLabels = 42.dp        // title + subtitle under a continue card
private val SectionGap = 20.dp
private val RowPitch = Dimens.AppCardHeight + Dimens.AppRowLabelSpace
private val ContinuePitch = Dimens.ContinueCardWidth + Dimens.ContinueGap

/**
 * "Продолжить просмотр" + "Приложения". One vertically scrolling surface; the scroll position is a pure function of
 * the navigation state (no scroll state, no fling) and is animated with the page spring. Sections snap like pages:
 * on "Продолжить просмотр" the scroll is 0; inside "Приложения" the focused grid row is parked right under the
 * (sticky) section title and everything above it is tucked away under the top scrim drawn by HomeScreen.
 *
 * Only rows / cards near the viewport are composed, and artwork is requested by composed cards only — so a TV with
 * 80 apps decodes about 15 banners when this page opens, not 80.
 */
@Composable
fun Library(
    continueItems: List<ContinueItem>,
    apps: List<AppEntry>,
    columns: Int,
    section: Section,
    continueIndex: Int,
    appsIndex: Int,
    reorder: Boolean,
    viewportHeight: Dp,
    modifier: Modifier = Modifier,
) {
    val motion = Mono.motion
    val colors = Mono.colors
    val type = Mono.type
    val pointer = LocalPointerHandler.current

    val hasContinue = continueItems.isNotEmpty()
    val continueBlock = if (hasContinue) HeaderBlock + Dimens.ContinueCardHeight + ContinueLabels + SectionGap else 0.dp
    val rowsTop = continueBlock + HeaderBlock
    val rowCount = if (apps.isEmpty()) 0 else (apps.size + columns - 1) / columns
    val contentHeight = rowsTop + RowPitch * rowCount
    val focusRow = if (section == Section.Apps && columns > 0) appsIndex / columns else 0
    val targetScroll = if (section == Section.Apps) {
        // Never scroll past the end of the grid (the last rows then simply sit lower on screen).
        val maxScroll = maxOf(continueBlock.value, (contentHeight - viewportHeight).value).dp
        (continueBlock + RowPitch * focusRow).coerceIn(continueBlock, maxScroll)
    } else {
        0.dp
    }
    val scroll by animateDpAsState(targetScroll, motion.page(), label = "libraryScroll")

    Box(modifier.width(LibraryWidth).fillMaxHeight()) {
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = -scroll.toPx() }) {
            if (hasContinue) {
                Txt("Продолжить просмотр", type.section, colors.textPrimary)
                ContinueRow(
                    items = continueItems,
                    focusedIndex = if (section == Section.Continue) continueIndex else null,
                    rememberedIndex = continueIndex,
                    modifier = Modifier.offset(y = HeaderBlock),
                )
            }
            if (apps.isNotEmpty()) {
                Txt("Приложения", type.section, colors.textPrimary, Modifier.offset(y = continueBlock))
                // Rows near the focused one only; everything else is not composed at all.
                val firstRow = (focusRow - 2).coerceAtLeast(0)
                val lastRow = (focusRow + 3).coerceAtMost(rowCount - 1)
                for (row in firstRow..lastRow) {
                    for (column in 0 until columns) {
                        val index = row * columns + column
                        val app = apps.getOrNull(index) ?: break
                        val focused = section == Section.Apps && appsIndex == index
                        Column(
                            Modifier.offset(
                                x = (Dimens.AppCardWidth + Dimens.AppGridGap) * column,
                                y = rowsTop + RowPitch * row,
                            ),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            ArtCard(
                                source = app.banner,
                                label = app.label,
                                width = Dimens.AppCardWidth,
                                height = Dimens.AppCardHeight,
                                focused = focused,
                                focusScale = motion.cardFocusScale,
                                lifted = reorder && focused,
                                onTap = { pointer(PointerTarget.HomeItem(Section.Apps, index)) },
                            )
                            Spacer(Modifier.height(9.dp))
                            // Icon tiles already carry the app name inside; only banners get the name underneath.
                            val showLabel = focused && (app.hasBanner || reorder)
                            val labelAlpha by animateFloatAsState(if (showLabel) 1f else 0f, motion.fade(), label = "appLabel")
                            Txt(
                                text = if (reorder && focused) "‹  ${app.label}  ›" else app.label,
                                style = type.caption,
                                color = colors.textPrimary,
                                modifier = Modifier.width(Dimens.AppCardWidth).graphicsLayer { alpha = labelAlpha },
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Horizontal row of 16:9 cards with a title, a second line and — only when the source really knows it — a progress
 * bar. The row scrolls so the focused card rests in the second slot.
 */
@Composable
private fun ContinueRow(
    items: List<ContinueItem>,
    focusedIndex: Int?,
    rememberedIndex: Int,
    modifier: Modifier = Modifier,
) {
    val motion = Mono.motion
    val colors = Mono.colors
    val type = Mono.type
    val pointer = LocalPointerHandler.current
    val anchor = (focusedIndex ?: rememberedIndex).coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val maxScroll = (ContinuePitch * items.size - Dimens.ContinueGap - LibraryWidth).coerceAtLeast(0.dp)
    val targetScroll = (ContinuePitch * (anchor - 1)).coerceIn(0.dp, maxScroll)
    val scroll by animateDpAsState(targetScroll, motion.page(), label = "continueScroll")
    val first = (anchor - 3).coerceAtLeast(0)
    val last = (anchor + 4).coerceAtMost(items.size - 1)

    Box(modifier.graphicsLayer { translationX = -scroll.toPx() }) {
        for (index in first..last) {
            val item = items[index]
            val focused = focusedIndex == index
            Column(Modifier.offset(x = ContinuePitch * index).width(Dimens.ContinueCardWidth)) {
                ArtCard(
                    source = item.art,
                    label = item.title,
                    width = Dimens.ContinueCardWidth,
                    height = Dimens.ContinueCardHeight,
                    focused = focused,
                    focusScale = Mono.motion.cardFocusScale,
                    progress = item.progress,
                    onTap = { pointer(PointerTarget.HomeItem(Section.Continue, index)) },
                )
                Spacer(Modifier.height(10.dp))
                Txt(item.title, type.caption, if (focused) colors.textPrimary else colors.textSecondary)
                if (!item.subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Txt(item.subtitle, type.micro, colors.textTertiary)
                }
            }
        }
    }
}
