package tv.monotv.launcher.core

import androidx.compose.runtime.Immutable

/**
 * Static launcher configuration, loaded from `assets/launcher-config.json` and optionally overridden by a file with the
 * same name in the app's external files dir (`/sdcard/Android/data/tv.monotv.launcher/files/launcher-config.json`).
 * See README → "Как изменить Dock" / "Как добавить приложение".
 */
@Immutable
data class KnownApp(
    /** Stable id used in [LauncherConfig.dockPriority], e.g. "youtube". */
    val id: String,
    /** Human name, used only when the system label is unusable. */
    val title: String,
    /** Package names that are "this app" on various firmwares, most preferred first. */
    val packages: List<String>,
    /** One-line Russian tagline for the hero spotlight, optional. */
    val tagline: String? = null,
)

@Immutable
data class LauncherConfig(
    val version: Int = 1,
    /** Known-app ids in the order they should fill the automatic Dock. Not-installed apps are skipped. */
    val dockPriority: List<String> = emptyList(),
    val knownApps: List<KnownApp> = emptyList(),
    /**
     * Packages that must never be listed or started (factory / engineering / service menus). Entries are exact
     * package names or prefixes ending in '*', e.g. "com.cvte.fac.*".
     */
    val blockedPackages: List<String> = emptyList(),
    /**
     * Extra launcher categories to query besides LEANBACK_LAUNCHER (and LAUNCHER when enabled in settings), e.g. Sber's
     * private "ru.sberdevices.category.STAR_LAUNCHER".
     */
    val extraLauncherCategories: List<String> = emptyList(),
) {
    fun knownIdFor(packageName: String): String? = knownApps.firstOrNull { packageName in it.packages }?.id

    fun known(id: String?): KnownApp? = if (id == null) null else knownApps.firstOrNull { it.id == id }

    fun isBlocked(packageName: String): Boolean = blockedPackages.any { pattern ->
        if (pattern.endsWith("*")) packageName.startsWith(pattern.dropLast(1)) else packageName == pattern
    }
}
