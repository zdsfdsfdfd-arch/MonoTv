package tv.monotv.launcher.ui.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tv.monotv.launcher.core.ClockState
import tv.monotv.launcher.core.NetworkKind
import tv.monotv.launcher.core.NetworkState
import tv.monotv.launcher.nav.Section
import tv.monotv.launcher.nav.TopBarItem
import tv.monotv.launcher.ui.components.Icon
import tv.monotv.launcher.ui.components.LocalPointerHandler
import tv.monotv.launcher.ui.components.MonoIcon
import tv.monotv.launcher.ui.components.PointerTarget
import tv.monotv.launcher.ui.components.Txt
import tv.monotv.launcher.ui.components.detectTap
import tv.monotv.launcher.ui.components.tappable
import tv.monotv.launcher.ui.components.drawSoftShadow
import tv.monotv.launcher.ui.theme.Dimens
import tv.monotv.launcher.ui.theme.Mono

/**
 * Clock block on the left (time, date, greeting) and the glass pills on the right.
 *
 * @param focusedIndex index of the focused pill, or null when the top bar is not the focused section
 * @param activeIndex  pill whose menu is currently open (kept highlighted as the menu's anchor)
 */
@Composable
fun TopBar(
    clock: ClockState,
    network: NetworkState,
    focusedIndex: Int?,
    activeIndex: Int?,
    modifier: Modifier = Modifier,
    /** 0 = full clock block, 1 = time only (the library page needs the room). Read at draw time. */
    collapse: () -> Float = { 0f },
) {
    val colors = Mono.colors
    val type = Mono.type
    Box(modifier.fillMaxWidth().padding(horizontal = Dimens.SafeH, vertical = Dimens.SafeV)) {
        Column(Modifier.align(Alignment.TopStart)) {
            Txt(clock.time, type.clock, colors.textPrimary)
            Column(Modifier.graphicsLayer { alpha = (1f - collapse() * 1.6f).coerceIn(0f, 1f) }) {
                Spacer(Modifier.height(5.dp))
                Txt(clock.date, type.date, colors.textSecondary)
                Spacer(Modifier.height(3.dp))
                Txt(clock.greeting, type.greeting, colors.textTertiary)
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val pointer = LocalPointerHandler.current
            TopBarItem.entries.forEachIndexed { index, item ->
                val (icon, label) = when (item) {
                    TopBarItem.Network -> when (network.kind) {
                        NetworkKind.Wifi -> MonoIcon.Wifi to "Wi-Fi"
                        NetworkKind.Ethernet -> MonoIcon.Ethernet to "Ethernet"
                        NetworkKind.Other -> MonoIcon.Wifi to "Сеть"
                        NetworkKind.Offline -> MonoIcon.Offline to "Нет сети"
                    }
                    TopBarItem.Inputs -> MonoIcon.Inputs to item.title
                    TopBarItem.Settings -> MonoIcon.Settings to item.title
                }
                Pill(
                    icon = icon,
                    label = label,
                    focused = focusedIndex == index || activeIndex == index,
                    onTap = { pointer(PointerTarget.HomeItem(Section.TopBar, index)) },
                )
            }
        }
    }
}

/** Small glass capsule. Focus turns it into a solid light slab that grows slightly — no outline. */
@Composable
fun Pill(icon: MonoIcon, label: String, focused: Boolean, onTap: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Mono.colors
    val motion = Mono.motion
    val focus by animateFloatAsState(if (focused) 1f else 0f, motion.focus(), label = "pill")
    val content = { lerp(colors.textPrimary, colors.onFocusFill, focus.coerceIn(0f, 1f)) }
    val scale = motion.pillFocusScale
    Row(
        modifier
            .height(Dimens.PillHeight)
            .graphicsLayer {
                val s = 1f + (scale - 1f) * focus
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                val f = focus.coerceIn(0f, 1f)
                val radius = CornerRadius(size.height / 2f)
                drawSoftShadow(size.height / 2f, alpha = f * 0.8f)
                drawRoundRect(lerp(colors.glassFill, colors.focusFill, f), cornerRadius = radius)
                if (f < 0.99f) {
                    val w = Dimens.GlassStroke.toPx()
                    drawRoundRect(
                        brush = Brush.verticalGradient(listOf(colors.glassStrokeTop, colors.glassStrokeBottom)),
                        topLeft = Offset(w / 2f, w / 2f), size = Size(size.width - w, size.height - w),
                        cornerRadius = radius, style = Stroke(w), alpha = 1f - f,
                    )
                }
            }
            .semantics(mergeDescendants = true) {
                contentDescription = label
                selected = focused
                role = Role.Button
            }
            .tappable(onTap)
            .padding(start = 11.dp, end = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, Modifier.size(15.dp), tint = content)
        Spacer(Modifier.width(7.dp))
        Txt(label, Mono.type.caption, content)
    }
}
