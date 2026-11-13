package tv.monotv.launcher.ui.home

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.HeroItem
import tv.monotv.launcher.core.HeroKind
import tv.monotv.launcher.nav.Section
import tv.monotv.launcher.ui.components.ArtCard
import tv.monotv.launcher.ui.components.Icon
import tv.monotv.launcher.ui.components.LocalPointerHandler
import tv.monotv.launcher.ui.components.MonoIcon
import tv.monotv.launcher.ui.components.PointerTarget
import tv.monotv.launcher.ui.components.ProgressBar
import tv.monotv.launcher.ui.components.Txt
import tv.monotv.launcher.ui.components.detectTap
import tv.monotv.launcher.ui.components.drawGlow
import tv.monotv.launcher.ui.components.drawSoftShadow
import tv.monotv.launcher.ui.theme.Mono
import kotlin.math.roundToInt

/** Height reserved for the hero texts. Content is bottom-aligned inside so the button never moves between items. */
private val HeroTextHeight = 176.dp
internal val HeroBlockWidth = 440.dp

/**
 * The text side of the cinematic hero: eyebrow, title, description, optional progress, the primary button and the
 * page dots. The picture itself is the screen backdrop (see Backdrop). Items crossfade; nothing plays automatically.
 */
@Composable
fun HeroBlock(item: HeroItem?, index: Int, count: Int, focused: Boolean, modifier: Modifier = Modifier) {
    val motion = Mono.motion
    Column(modifier.width(HeroBlockWidth)) {
        Crossfade(targetState = item, animationSpec = motion.crossfade(), label = "heroText") { current ->
            Box(Modifier.fillMaxWidth().height(HeroTextHeight), contentAlignment = Alignment.BottomStart) {
                if (current != null) HeroTexts(current)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            val pointer = LocalPointerHandler.current
            HeroButton(
                label = item?.actionLabel ?: "Открыть",
                focused = focused,
                onTap = { pointer(PointerTarget.HomeItem(Section.Hero, index)) },
            )
            if (count > 1) {
                Spacer(Modifier.width(18.dp))
                PageDots(index = index, count = count, highlighted = focused)
            }
        }
    }
}

@Composable
private fun HeroTexts(item: HeroItem) {
    val colors = Mono.colors
    val type = Mono.type
    Column {
        if (!item.eyebrow.isNullOrBlank()) {
            Txt(item.eyebrow.uppercase(), type.eyebrow, colors.accent)
            Spacer(Modifier.height(9.dp))
        }
        Txt(item.title, type.hero, colors.textPrimary, maxLines = 2)
        if (!item.description.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp))
            Txt(item.description, type.body, colors.textSecondary, Modifier.widthIn(max = 390.dp), maxLines = 2)
        }
        if (item.progress != null) {
            Spacer(Modifier.height(13.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressBar(item.progress, Modifier.size(width = 170.dp, height = 3.dp))
                Spacer(Modifier.width(10.dp))
                Txt("${(item.progress * 100).roundToInt()}%", type.micro, colors.textSecondary)
            }
        }
    }
}

/** Primary action. Unfocused: quiet glass. Focused: a solid light slab that lifts off the picture. */
@Composable
private fun HeroButton(label: String, focused: Boolean, onTap: () -> Unit) {
    val colors = Mono.colors
    val motion = Mono.motion
    val focus by animateFloatAsState(if (focused) 1f else 0f, motion.focus(), label = "heroButton")
    val content = { lerp(colors.textPrimary, colors.onFocusFill, focus.coerceIn(0f, 1f)) }
    val scale = motion.pillFocusScale
    Row(
        Modifier
            .height(36.dp)
            .graphicsLayer {
                val s = 1f + (scale - 1f) * focus
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                val f = focus.coerceIn(0f, 1f)
                if (motion.rich) drawGlow(Color.White, alpha = f * 0.20f, spread = 1.25f)
                drawSoftShadow(size.height / 2f, alpha = f)
                drawRoundRect(lerp(colors.glassFillStrong, colors.focusFill, f), cornerRadius = CornerRadius(size.height / 2f))
            }
            .semantics(mergeDescendants = true) {
                contentDescription = label
                selected = focused
                role = Role.Button
            }
            .pointerInput(onTap) { detectTap(onTap) }
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(MonoIcon.Play, Modifier.size(13.dp), tint = content)
        Spacer(Modifier.width(8.dp))
        Txt(label, Mono.type.label, content)
    }
}

@Composable
private fun PageDots(index: Int, count: Int, highlighted: Boolean) {
    val colors = Mono.colors
    val motion = Mono.motion
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(count) { i ->
            val width: Dp by animateDpAsState(if (i == index) 18.dp else 5.dp, motion.glide(), label = "dot")
            val color = when {
                i != index -> colors.textTertiary
                highlighted -> colors.textPrimary
                else -> colors.textSecondary
            }
            Box(Modifier.size(width = width, height = 5.dp).drawBehind { drawRoundRect(color, cornerRadius = CornerRadius(size.height / 2f)) })
        }
    }
}

/**
 * For app spotlights the backdrop is abstract artwork, so the app itself is shown as a large floating banner on the
 * right-hand side (crossfading together with the texts).
 */
@Composable
fun HeroAppCard(item: HeroItem?, modifier: Modifier = Modifier) {
    val motion = Mono.motion
    Crossfade(targetState = item, animationSpec = motion.crossfade(), modifier = modifier, label = "heroCard") { current ->
        val key = current?.appKey
        Box(Modifier.size(width = 304.dp, height = 171.dp)) {
            if (current != null && current.kind == HeroKind.App && key != null) {
                ArtCard(
                    source = ArtSource.AppBanner(key),
                    label = current.title,
                    width = 304.dp,
                    height = 171.dp,
                    focused = true,
                    focusScale = 1f,
                    corner = 20.dp,
                )
            }
        }
    }
}
