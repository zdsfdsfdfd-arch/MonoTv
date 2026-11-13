package tv.monotv.launcher.ui.overlay

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.InputItem
import tv.monotv.launcher.core.InputKind
import tv.monotv.launcher.core.LauncherUiState
import tv.monotv.launcher.nav.Overlay
import tv.monotv.launcher.nav.RowKind
import tv.monotv.launcher.nav.SettingsFrame
import tv.monotv.launcher.nav.SettingsPageId
import tv.monotv.launcher.nav.SettingsRow
import tv.monotv.launcher.ui.components.GlassBox
import tv.monotv.launcher.ui.components.LocalPointerHandler
import tv.monotv.launcher.ui.components.MonoIcon
import tv.monotv.launcher.ui.components.PointerTarget
import tv.monotv.launcher.ui.components.Txt
import tv.monotv.launcher.ui.components.detectTap
import tv.monotv.launcher.ui.components.drawSoftShadow
import tv.monotv.launcher.ui.theme.Dimens
import tv.monotv.launcher.ui.theme.Mono

/**
 * Hosts every overlay of the launcher. Each kind keeps the last value it showed so its exit animation can still draw
 * after the navigation state has already dropped it.
 */
@Composable
fun Overlays(
    state: LauncherUiState,
    settingsRows: (SettingsPageId) -> List<SettingsRow>,
    modifier: Modifier = Modifier,
) {
    val motion = Mono.motion
    val overlays = state.nav.overlays
    val pointer = LocalPointerHandler.current

    val inputs = overlays.lastOrNull { it is Overlay.Inputs } as Overlay.Inputs?
    val settings = overlays.lastOrNull { it is Overlay.Settings } as Overlay.Settings?
    val appMenu = overlays.lastOrNull { it is Overlay.AppMenu } as Overlay.AppMenu?
    val confirm = overlays.lastOrNull { it is Overlay.Confirm } as Overlay.Confirm?

    var lastInputs by remember { mutableStateOf<Overlay.Inputs?>(null) }
    var lastSettings by remember { mutableStateOf<Overlay.Settings?>(null) }
    var lastAppMenu by remember { mutableStateOf<Overlay.AppMenu?>(null) }
    var lastConfirm by remember { mutableStateOf<Overlay.Confirm?>(null) }
    if (inputs != null) lastInputs = inputs
    if (settings != null) lastSettings = settings
    if (appMenu != null) lastAppMenu = appMenu
    if (confirm != null) lastConfirm = confirm

    Box(modifier.fillMaxSize()) {
        if (overlays.isNotEmpty()) {
            // Taps outside an overlay behave like BACK.
            Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTap { pointer(PointerTarget.Outside) } })
        }

        AnimatedVisibility(
            visible = settings != null,
            enter = slideInHorizontally(motion.overlay()) { it / 3 } + fadeIn(motion.overlay()),
            exit = slideOutHorizontally(motion.overlay()) { it / 3 } + fadeOut(motion.overlay()),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            lastSettings?.let { SettingsSheet(it, settingsRows, dimmed = confirm != null) }
        }

        AnimatedVisibility(
            visible = inputs != null,
            enter = fadeIn(motion.overlay()) + scaleIn(motion.overlay(), initialScale = 0.94f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(motion.overlay()) + scaleOut(motion.overlay(), targetScale = 0.96f, transformOrigin = TransformOrigin(1f, 0f)),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = Dimens.SafeV + 4.dp + Dimens.PillHeight + 10.dp, end = Dimens.SafeH),
        ) {
            lastInputs?.let { InputsMenu(state.inputs, it.index) }
        }

        AnimatedVisibility(
            visible = appMenu != null,
            enter = fadeIn(motion.overlay()) + scaleIn(motion.overlay(), initialScale = 0.94f),
            exit = fadeOut(motion.overlay()) + scaleOut(motion.overlay(), targetScale = 0.96f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            lastAppMenu?.let { menu ->
                val app = state.allApps.firstOrNull { it.key == menu.app } ?: state.apps.firstOrNull { it.key == menu.app }
                AppMenu(app, menu)
            }
        }

        AnimatedVisibility(
            visible = confirm != null,
            enter = fadeIn(motion.overlay()) + scaleIn(motion.overlay(), initialScale = 0.94f),
            exit = fadeOut(motion.overlay()) + scaleOut(motion.overlay(), targetScale = 0.96f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            lastConfirm?.let { ConfirmDialog(it) }
        }

        var lastToast by remember { mutableStateOf("") }
        if (state.toast != null) lastToast = state.toast
        AnimatedVisibility(
            visible = state.toast != null,
            enter = fadeIn(motion.overlay()) + slideInVertically(motion.overlay()) { it / 2 },
            exit = fadeOut(motion.overlay()) + slideOutVertically(motion.overlay()) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Dimens.SafeV + 6.dp),
        ) {
            GlassBox(corner = 17.dp, shade = 0.72f) {
                Txt(lastToast, Mono.type.caption, Mono.colors.textPrimary, Modifier.padding(horizontal = 18.dp, vertical = 9.dp), maxLines = 2)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Inputs
// ---------------------------------------------------------------------------------------------------------------------

/** Compact menu under the "Входы" pill: HDMI 1–3, AV, Live TV (or whatever the TV input framework reports). */
@Composable
private fun InputsMenu(inputs: List<InputItem>, focusedIndex: Int) {
    val colors = Mono.colors
    GlassBox(Modifier.width(272.dp), corner = 20.dp, shade = 0.72f) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
            Txt("ВХОДЫ", Mono.type.eyebrow, colors.textTertiary, Modifier.padding(start = 14.dp, top = 4.dp, bottom = 8.dp))
            MenuRows(
                rows = inputs.map { input ->
                    MenuRow(
                        title = input.label,
                        icon = when (input.kind) {
                            InputKind.Hdmi -> MonoIcon.Hdmi
                            InputKind.Av, InputKind.Component -> MonoIcon.Av
                            InputKind.Tuner -> MonoIcon.Tuner
                            InputKind.Other -> MonoIcon.Inputs
                        },
                        value = when {
                            !input.switchable -> "Недоступно"
                            input.connected == true -> "Подключён"
                            input.connected == false -> "Нет сигнала"
                            else -> null
                        },
                        statusDot = input.switchable && input.connected == true,
                        enabled = input.switchable,
                    )
                },
                focusedIndex = focusedIndex,
                viewportHeight = 300.dp,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------------------------------

/**
 * Launcher settings: a sheet on the right; the home screen stays visible on the left as a LIVE preview of every
 * appearance / dock change. Pages form a stack (BACK pops).
 */
@Composable
private fun SettingsSheet(
    overlay: Overlay.Settings,
    settingsRows: (SettingsPageId) -> List<SettingsRow>,
    dimmed: Boolean,
) {
    val colors = Mono.colors
    val type = Mono.type
    val motion = Mono.motion
    val frame = overlay.stack.lastOrNull() ?: SettingsFrame(SettingsPageId.Root)
    val parent = overlay.stack.getOrNull(overlay.stack.size - 2)
    val dim by animateFloatAsState(if (dimmed) 0.5f else 0f, motion.fade(), label = "settingsDim")

    BoxWithConstraints(
        Modifier
            .width(436.dp)
            .fillMaxHeight()
            .drawBehind {
                // Soft shadow falling onto the home screen, then the sheet itself with a lit left edge.
                drawRect(
                    Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.45f)), startX = -70.dp.toPx(), endX = 0f),
                    topLeft = Offset(-70.dp.toPx(), 0f), size = androidx.compose.ui.geometry.Size(70.dp.toPx(), size.height),
                )
                drawRect(colors.surface.copy(alpha = 0.955f))
                drawRect(
                    Brush.verticalGradient(listOf(colors.glassStrokeTop, colors.glassStrokeBottom)),
                    size = androidx.compose.ui.geometry.Size(Dimens.GlassStroke.toPx(), size.height),
                )
            }
            .graphicsLayer { alpha = 1f - dim },
    ) {
        val listHeight = maxHeight - Dimens.SafeV - 96.dp - 44.dp
        Crossfade(targetState = frame.page, animationSpec = motion.overlay(), label = "settingsPage") { page ->
            // While a page fades out it is no longer the top frame: freeze its focus where it was.
            val shown = overlay.stack.lastOrNull { it.page == page } ?: SettingsFrame(page)
            val rows = settingsRows(page)
            Column(Modifier.fillMaxSize().padding(start = 26.dp, end = Dimens.SafeH - 14.dp, top = Dimens.SafeV + 8.dp)) {
                Txt(
                    text = (if (page == frame.page) parent?.page?.title else null)?.uppercase() ?: "MONO TV",
                    style = type.eyebrow, color = colors.accent, modifier = Modifier.padding(start = 14.dp),
                )
                Spacer(Modifier.height(8.dp))
                Txt(page.title, type.title, colors.textPrimary, Modifier.padding(start = 14.dp))
                Spacer(Modifier.height(20.dp))
                MenuRows(
                    rows = rows.map { it.toMenuRow() },
                    focusedIndex = shown.index,
                    viewportHeight = listHeight,
                    grabbed = shown.grabbed,
                )
            }
        }
        Txt(
            text = when {
                frame.grabbed -> "↑ ↓ — переместить  ·  ОК — отпустить"
                settingsRows(frame.page).getOrNull(frame.index)?.kind is RowKind.Reorderable -> "ОК — взять и переместить  ·  НАЗАД — вернуться"
                else -> "ОК — выбрать  ·  НАЗАД — вернуться"
            },
            style = type.micro, color = colors.textTertiary,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = Dimens.SafeV),
        )
    }
}

private fun SettingsRow.toMenuRow(): MenuRow = MenuRow(
    title = title,
    description = description,
    value = value,
    appKey = appKey,
    enabled = enabled,
    trailing = when {
        kind is RowKind.Navigate -> RowTrailing.Chevron
        kind is RowKind.Reorderable -> RowTrailing.Reorder
        checked == true -> RowTrailing.Check
        checked == false -> RowTrailing.Empty
        else -> RowTrailing.None
    },
)

// ---------------------------------------------------------------------------------------------------------------------
// App menu
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun AppMenu(app: AppEntry?, menu: Overlay.AppMenu) {
    val colors = Mono.colors
    GlassBox(Modifier.width(300.dp), corner = 22.dp, shade = 0.78f) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
            Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 6.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                AppThumb(menu.app, width = 64.dp)
                Spacer(Modifier.width(12.dp))
                Txt(app?.label ?: menu.app.packageName, Mono.type.section, colors.textPrimary, Modifier.weight(1f))
            }
            MenuRows(
                rows = menu.items.map { MenuRow(title = it.title) },
                focusedIndex = menu.index,
                viewportHeight = 320.dp,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------------
// Confirm
// ---------------------------------------------------------------------------------------------------------------------

@Composable
private fun ConfirmDialog(confirm: Overlay.Confirm) {
    val colors = Mono.colors
    val type = Mono.type
    val pointer = LocalPointerHandler.current
    GlassBox(Modifier.width(452.dp), corner = 24.dp, shade = 0.82f) {
        Column(Modifier.padding(start = 28.dp, end = 28.dp, top = 26.dp, bottom = 22.dp)) {
            Txt(confirm.id.title, type.section.copy(fontSize = type.section.fontSize * 1.18f), colors.textPrimary, maxLines = 2)
            Spacer(Modifier.height(10.dp))
            Txt(confirm.id.message, type.body, colors.textSecondary, maxLines = 5)
            Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                DialogButton("Отмена", focused = confirm.index == 0, onTap = { pointer(PointerTarget.OverlayRow(0)) })
                DialogButton(confirm.id.confirmLabel, focused = confirm.index == 1, onTap = { pointer(PointerTarget.OverlayRow(1)) })
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, focused: Boolean, onTap: () -> Unit) {
    val colors = Mono.colors
    val motion = Mono.motion
    val focus by animateFloatAsState(if (focused) 1f else 0f, motion.focus(), label = "dialogButton")
    val scale = motion.pillFocusScale
    Box(
        Modifier
            .height(38.dp)
            .graphicsLayer {
                val s = 1f + (scale - 1f) * focus
                scaleX = s
                scaleY = s
            }
            .drawBehind {
                val f = focus.coerceIn(0f, 1f)
                drawSoftShadow(size.height / 2f, alpha = f)
                drawRoundRect(lerp(colors.glassFill, colors.focusFill, f), cornerRadius = CornerRadius(size.height / 2f))
            }
            .pointerInput(onTap) { detectTap(onTap) }
            .padding(horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Txt(label, Mono.type.label, color = { lerp(colors.textPrimary, colors.onFocusFill, focus.coerceIn(0f, 1f)) })
    }
}
