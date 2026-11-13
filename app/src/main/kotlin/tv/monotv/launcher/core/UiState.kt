package tv.monotv.launcher.core

import androidx.compose.runtime.Immutable
import tv.monotv.launcher.nav.NavState

/**
 * Everything the launcher UI needs to draw one frame. Built by LauncherViewModel; built by hand in screenshot tests.
 * The UI is a pure function of this state — it owns no navigation or focus state of its own.
 */
@Immutable
data class LauncherUiState(
    val clock: ClockState = ClockState(),
    val network: NetworkState = NetworkState(),
    val hero: List<HeroItem> = emptyList(),
    /** Apps in the dock, already limited to settings.dockSize and to installed apps (never placeholders). */
    val dock: List<AppEntry> = emptyList(),
    val continueWatching: List<ContinueItem> = emptyList(),
    /** Visible apps of the "Приложения" section, already sorted (dock apps, recently used, the rest). */
    val apps: List<AppEntry> = emptyList(),
    /** Every discovered app including hidden ones (for Settings lists). */
    val allApps: List<AppEntry> = emptyList(),
    val inputs: List<InputItem> = emptyList(),
    val settings: LauncherSettings = LauncherSettings(),
    val device: DeviceInfo = DeviceInfo(),
    val effects: EffectsLevel = EffectsLevel.Full,
    val nav: NavState = NavState(),
    /** One-shot message shown as a small glass toast at the bottom, null when hidden. */
    val toast: String? = null,
    /** Monotonic counter + direction of the last "edge bump" so the UI can play a small rubber-band once. */
    val bump: BumpEvent? = null,
    /** False until the first app scan finished (UI shows skeleton tiles instead of an empty home). */
    val loaded: Boolean = false,
    /** The optional READ_TV_LISTINGS permission is granted: other apps' "continue watching" rows can be read. */
    val watchNextAccess: Boolean = false,
) {
    companion object {
        /** Number of columns of the Apps grid on the 960dp design canvas. */
        const val APPS_COLUMNS = 5
    }
}

@Immutable
data class BumpEvent(val seq: Int, val direction: tv.monotv.launcher.nav.NavKey)
