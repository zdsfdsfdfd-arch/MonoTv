package tv.monotv.launcher.data

import kotlinx.coroutines.flow.StateFlow
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.ClockState
import tv.monotv.launcher.core.ContinueItem
import tv.monotv.launcher.core.DeviceInfo
import tv.monotv.launcher.core.EffectsLevel
import tv.monotv.launcher.core.InputItem
import tv.monotv.launcher.core.LauncherConfig
import tv.monotv.launcher.core.LauncherSettings
import tv.monotv.launcher.core.NetworkState
import tv.monotv.launcher.core.RecentLaunch

/**
 * Contracts of the data layer. The ViewModel talks only to these interfaces; tests use fakes.
 *
 * Global rules for every implementation:
 *  - NO polling and no timers: react to broadcasts / callbacks only.
 *  - NO network access (the app does not even request INTERNET).
 *  - Never throw out of a public method; missing APIs, SecurityException etc. degrade to empty data / false.
 *  - Never disable, enable, uninstall or otherwise modify other packages or system settings.
 *  - `start()` / `stop()` are idempotent and called from the main thread (activity onStart / onStop).
 */

interface AppsRepository {
    /**
     * Every launchable app found on the device, blocklist applied, own package excluded, sorted by label.
     * Includes apps with a plain LAUNCHER activity only (isLeanback = false) — the ViewModel filters them by setting.
     * null until the first scan finished.
     */
    val apps: StateFlow<List<AppEntry>?>

    val config: LauncherConfig

    /** Registers package add/remove/change receivers and triggers the first scan. */
    fun start()

    fun stop()

    /** Rescan now (off the main thread). */
    fun refresh()

    /** Starts the app. Returns false when it is gone or cannot be started. */
    fun launch(key: AppKey): Boolean

    /**
     * Starts a deep link produced by Intent.toUri(URI_INTENT_SCHEME) (e.g. a Watch Next entry); falls back to
     * launching [fallback] when the uri is null / unparsable / unresolvable.
     */
    fun launchUri(intentUri: String?, fallback: AppKey?): Boolean

    /** Opens the system "App info" screen for the package. */
    fun openAppInfo(key: AppKey): Boolean
}

interface SettingsRepository {
    val settings: StateFlow<LauncherSettings>

    /** Most recent first. */
    val recents: StateFlow<List<RecentLaunch>>

    /** Applies [transform] synchronously to the in-memory value (so the next key press sees it) and persists async. */
    fun update(transform: (LauncherSettings) -> LauncherSettings)

    fun recordLaunch(key: AppKey, now: Long = System.currentTimeMillis())

    /** Drops remembered launches of apps that are no longer installed. */
    fun pruneRecents(installed: Set<AppKey>)
}

interface SystemRepository {
    /** Updates on ACTION_TIME_TICK / TIME_SET / TIMEZONE_CHANGED / LOCALE_CHANGED only. */
    val clock: StateFlow<ClockState>

    val network: StateFlow<NetworkState>

    val device: StateFlow<DeviceInfo>

    fun start()

    fun stop()

    /** Re-reads HOME resolution state etc. (cheap). */
    fun refreshDevice()

    /** Effects level recommended for this hardware when the user setting is Auto. */
    fun recommendedEffects(): EffectsLevel

    fun openNetworkSettings(): Boolean

    fun openSystemSettings(): Boolean

    /**
     * Enables / disables OUR OWN optional HOME activity-alias (the only component state this app ever changes).
     * Enabling it merely makes this app a candidate for the HOME role; the stock launcher is never touched.
     */
    fun setHomeAliasEnabled(enabled: Boolean): Boolean

    /** Opens the system UI where the user picks the default HOME app (role request / home settings), if any. */
    fun openHomeChooser(): Boolean

    /**
     * Writes a read-only diagnostics report (device, display, memory, HOME candidates, launchable apps, TV inputs,
     * presence of known system packages) to the app's external files dir. Returns the absolute path or null.
     */
    fun writeDiagnosticsReport(): String?
}

interface InputsRepository {
    /**
     * Inputs to show in the "Входы" menu. Real TvInputManager inputs when the platform exposes them (switchable =
     * true); otherwise a fixed placeholder list (HDMI 1–3, AV, Live TV) with switchable = false.
     */
    val inputs: StateFlow<List<InputItem>>

    fun start()

    fun stop()

    /** Switches via the documented public API only. Returns false when the input is not switchable. */
    fun switchTo(item: InputItem): Boolean
}

interface ContentRepository {
    /**
     * "Continue watching" entries published by other apps through the platform TV provider (Watch Next), newest first.
     * Empty when the provider is missing or this (non-system) app is not allowed to read it. Runs off the main thread.
     */
    suspend fun loadWatchNext(limit: Int = 12): List<ContinueItem>

    /** Emits Unit-like ticks (a counter) whenever the Watch Next data may have changed (ContentObserver). */
    val watchNextInvalidations: StateFlow<Int>

    fun start()

    fun stop()
}
