package tv.monotv.launcher.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.HeroKind
import tv.monotv.launcher.core.LauncherUiState
import tv.monotv.launcher.core.Wallpapers
import tv.monotv.launcher.nav.NavKey
import tv.monotv.launcher.nav.Overlay
import tv.monotv.launcher.nav.Section
import tv.monotv.launcher.nav.TopBarItem
import tv.monotv.launcher.ui.components.AmbientGlow
import tv.monotv.launcher.ui.components.Backdrop
import tv.monotv.launcher.ui.components.GlassBox
import tv.monotv.launcher.ui.components.Txt
import tv.monotv.launcher.ui.components.rememberArtwork
import tv.monotv.launcher.ui.components.rememberBackdropState
import tv.monotv.launcher.ui.theme.Dimens
import tv.monotv.launcher.ui.theme.Mono

/**
 * The Home screen. Two "pages" share one backdrop:
 *
 *   page 0 — cinematic hero (the backdrop IS the hero picture) with the clock on top and the glass Dock at the bottom;
 *   page 1 — the library ("Продолжить просмотр", "Приложения") sliding up over the dimmed picture.
 *
 * Everything is a pure function of [state]; the only local state is animation.
 */
@Composable
fun HomeScreen(state: LauncherUiState, modifier: Modifier = Modifier) {
    val nav = state.nav.home
    val colors = Mono.colors
    val motion = Mono.motion
    val overlay = state.nav.topOverlay

    val library = nav.section == Section.Continue || nav.section == Section.Apps
    val page = animateFloatAsState(if (library) 1f else 0f, motion.page(), label = "page")
    // Settings keeps the home visible (it is a live preview of theme / wallpaper / dock changes) — dim it less.
    val overlayDim = animateFloatAsState(
        targetValue = when (overlay) {
            null -> 0f
            is Overlay.Settings -> 0.30f
            is Overlay.Inputs -> 0.34f
            else -> 0.56f
        },
        animationSpec = motion.fade(),
        label = "overlayDim",
    )

    val heroItem = state.hero.getOrNull(nav.heroIndex) ?: state.hero.firstOrNull()
    val backdropSource: ArtSource = when (val wallpaper = state.settings.wallpaper) {
        Wallpapers.DYNAMIC -> heroItem?.art ?: ArtSource.Wallpaper(Wallpapers.AURORA)
        else -> ArtSource.Wallpaper(wallpaper)
    }
    val backdrop = rememberBackdropState(backdropSource)

    val dockFocus = if (nav.section == Section.Dock) nav.dockIndex.coerceIn(0, (state.dock.size - 1).coerceAtLeast(0)) else null
    val dockApp = dockFocus?.let { state.dock.getOrNull(it) }
    val dockArt = rememberArtwork(dockApp?.let { ArtSource.AppBanner(it.key) }, dockCardWidth(state.dock.size), dockCardWidth(state.dock.size) * 9f / 16f)
    val ambient = dockArt?.accent?.let { Color(it) } ?: backdrop.accent?.let { Color(it) } ?: colors.accent

    // One-shot rubber band when navigation hits an edge.
    val bump = remember { Animatable(0f) }
    LaunchedEffect(state.bump?.seq) {
        if (state.bump != null && motion.rich) {
            bump.snapTo(1f)
            bump.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 520f))
        }
    }
    val bumpDirection = state.bump?.direction

    BoxWithConstraints(modifier.fillMaxSize()) {
        val canvasHeight = maxHeight
        Backdrop(backdrop, page = { page.value }, dim = { overlayDim.value })
        AmbientGlow(color = ambient, visible = dockApp != null && overlay == null)

        Box(
            Modifier.fillMaxSize().graphicsLayer {
                val b = bump.value * 7.dp.toPx()
                when (bumpDirection) {
                    NavKey.Left -> translationX = -b
                    NavKey.Right -> translationX = b
                    NavKey.Up -> translationY = -b
                    NavKey.Down -> translationY = b
                    else -> Unit
                }
                // Content steps back a little while an overlay is open.
                alpha = 1f - overlayDim.value * 0.75f
            },
        ) {
            // ---- page 0: hero + dock ------------------------------------------------------------------------------
            val dockCount = if (state.dock.isEmpty() && !state.loaded) state.settings.dockSize else state.dock.size
            val dockBlock = if (dockCount > 0) dockPanelHeight(dockCount) + DockLabelHeight else 0.dp
            Box(
                Modifier.fillMaxSize().graphicsLayer {
                    val p = page.value
                    alpha = (1f - p * 1.7f).coerceIn(0f, 1f)
                    translationY = -110.dp.toPx() * p
                },
            ) {
                HeroBlock(
                    item = heroItem,
                    index = nav.heroIndex.coerceIn(0, (state.hero.size - 1).coerceAtLeast(0)),
                    count = state.hero.size,
                    focused = nav.section == Section.Hero,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .padding(start = Dimens.SafeH, bottom = Dimens.SafeV + dockBlock + 22.dp),
                )
                // Always composed (with null for non-app items) so the card crossfades out instead of vanishing.
                HeroAppCard(
                    item = heroItem?.takeIf { it.kind == HeroKind.App },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = Dimens.SafeH + 26.dp, bottom = Dimens.SafeV + dockBlock + 44.dp),
                )
                Dock(
                    apps = state.dock,
                    focusedIndex = dockFocus,
                    reorder = state.nav.reorder?.section == Section.Dock,
                    frost = backdrop.frost,
                    skeletonCount = dockCount,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = Dimens.SafeV),
                )
            }

            // ---- page 1: library ----------------------------------------------------------------------------------
            val showLibrary by remember { derivedStateOf { page.value > 0.004f } }
            if (library || showLibrary) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val p = page.value
                        alpha = ((p - 0.15f) / 0.85f).coerceIn(0f, 1f)
                        translationY = (1f - p) * 170.dp.toPx()
                    },
                ) {
                    Library(
                        continueItems = state.continueWatching,
                        apps = state.apps,
                        columns = LauncherUiState.APPS_COLUMNS,
                        section = nav.section,
                        continueIndex = nav.continueIndex,
                        appsIndex = nav.appsIndex,
                        reorder = state.nav.reorder?.section == Section.Apps,
                        viewportHeight = canvasHeight - LibraryTop - Dimens.SafeV,
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = LibraryTop),
                    )
                    // Top scrim: whatever scrolls above the viewport dissolves into the background instead of colliding
                    // with the clock. Inside "Приложения" it reaches lower and carries the sticky section title.
                    val inApps = nav.section == Section.Apps
                    val scrimEnd by animateDpAsState(
                        if (inApps) LibraryTop + LibraryHeaderBlock - 4.dp else LibraryTop - 2.dp, motion.page(), label = "libraryScrim",
                    )
                    val stickyAlpha by animateFloatAsState(if (inApps) 1f else 0f, motion.fade(), label = "stickyHeader")
                    Box(
                        Modifier.fillMaxWidth().height(LibraryTop + LibraryHeaderBlock).drawBehind {
                            val end = scrimEnd.toPx()
                            val solid = (end - 11.dp.toPx()).coerceAtLeast(0f)
                            drawRect(
                                Brush.verticalGradient(
                                    0f to colors.scrim, (solid / end).coerceIn(0f, 1f) to colors.scrim, 1f to Color.Transparent,
                                    startY = 0f, endY = end,
                                ),
                                size = androidx.compose.ui.geometry.Size(size.width, end),
                            )
                        },
                    )
                    Box(Modifier.align(Alignment.TopCenter).padding(top = LibraryTop).width(LibraryWidth).graphicsLayer { alpha = stickyAlpha }) {
                        Txt("Приложения", Mono.type.section, colors.textPrimary)
                    }
                }
            }

            TopBar(
                clock = state.clock,
                network = state.network,
                focusedIndex = if (nav.section == Section.TopBar) nav.topBarIndex else null,
                activeIndex = when (overlay) {
                    is Overlay.Inputs -> TopBarItem.Inputs.ordinal
                    is Overlay.Settings -> TopBarItem.Settings.ordinal
                    else -> null
                },
                collapse = { page.value },
            )

            if (state.nav.reorder != null) {
                GlassBox(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = if (library) Dimens.SafeV else Dimens.SafeV + dockBlock + 10.dp),
                    corner = 15.dp, shade = 0.5f,
                ) {
                    Txt(
                        "Перемещение  ·  стрелки двигают  ·  ОК — готово",
                        Mono.type.caption, colors.textPrimary, Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}
