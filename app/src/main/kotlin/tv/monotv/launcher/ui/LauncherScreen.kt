package tv.monotv.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import tv.monotv.launcher.core.ArtworkLoader
import tv.monotv.launcher.core.LauncherUiState
import tv.monotv.launcher.core.LocalArtworkLoader
import tv.monotv.launcher.nav.SettingsPageId
import tv.monotv.launcher.nav.SettingsRow
import tv.monotv.launcher.ui.components.LocalPointerHandler
import tv.monotv.launcher.ui.components.PointerTarget
import tv.monotv.launcher.ui.home.HomeScreen
import tv.monotv.launcher.ui.overlay.Overlays
import tv.monotv.launcher.ui.theme.Mono
import tv.monotv.launcher.ui.theme.MonoTheme

/**
 * Root composable of the launcher: theme + home + overlays. It is a pure function of [state] — navigation and focus
 * live in the ViewModel (see nav/NavModel.kt) — which is also what lets the screenshot tests render any screen state
 * by simply constructing a [LauncherUiState].
 *
 * @param settingsRows rows of a settings page for the current state (SettingsCatalog in production)
 * @param onPointer    taps from an air-mouse / touchpad; the D-pad never goes through here
 */
@Composable
fun LauncherScreen(
    state: LauncherUiState,
    artwork: ArtworkLoader,
    settingsRows: (SettingsPageId) -> List<SettingsRow>,
    modifier: Modifier = Modifier,
    onPointer: (PointerTarget) -> Unit = {},
) {
    MonoTheme(theme = state.settings.theme, accent = state.settings.accent, effects = state.effects) {
        CompositionLocalProvider(LocalArtworkLoader provides artwork, LocalPointerHandler provides onPointer) {
            Box(modifier.fillMaxSize().background(Mono.colors.background)) {
                HomeScreen(state)
                Overlays(state, settingsRows)
            }
        }
    }
}
