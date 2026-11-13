package tv.monotv.launcher.core

import androidx.compose.runtime.Immutable

/**
 * Shared, UI-facing models. Everything here is immutable and free of Android framework types so it can be
 * built in plain JVM tests and passed straight into composables without stability problems.
 */

/** Identity of a launchable activity. `flatten()` is the persisted form ("pkg/activity"). */
@Immutable
data class AppKey(val packageName: String, val activityName: String) {
    fun flatten(): String = "$packageName/$activityName"

    companion object {
        fun parse(flat: String): AppKey? {
            val i = flat.indexOf('/')
            if (i <= 0 || i >= flat.length - 1) return null
            return AppKey(flat.substring(0, i), flat.substring(i + 1))
        }
    }
}

@Immutable
data class AppEntry(
    val key: AppKey,
    val label: String,
    /** Declares CATEGORY_LEANBACK_LAUNCHER (a real TV app). False = phone-style LAUNCHER activity only. */
    val isLeanback: Boolean,
    /** The app ships a 16:9 TV banner. When false the UI composes a tile from the icon. */
    val hasBanner: Boolean,
    val isSystem: Boolean,
    /** Id from the known-apps registry ("youtube", "netflix", ...), or null for unknown apps. */
    val knownId: String?,
    val versionCode: Long,
    val installedAt: Long,
) {
    /** The app's banner (or icon tile) as an [ArtSource]. */
    val banner: ArtSource.AppBanner get() = ArtSource.AppBanner(key, versionCode)
}

/** Where a piece of artwork comes from. Resolved to pixels by [ArtworkLoader] only. No network sources by design. */
@Immutable
sealed interface ArtSource {
    /** Built-in wallpaper / demo artwork, see [Wallpapers]. */
    data class Wallpaper(val id: String) : ArtSource

    /**
     * TV banner (or icon tile) of an installed app. [version] is the app's versionCode: it is part of the identity so
     * that an updated app is a different picture for every cache and for every composable that remembered the old one.
     */
    data class AppBanner(val key: AppKey, val version: Long = 0L) : ArtSource

    /** content:// , file:// or android.resource:// uri (e.g. a Watch Next poster). http(s) is intentionally unsupported. */
    data class LocalUri(val uri: String) : ArtSource
}

enum class HeroKind { Content, App, Demo }

@Immutable
data class HeroItem(
    val id: String,
    val kind: HeroKind,
    /** Small uppercase line above the title: source app name, "Рекомендуем", "Продолжить просмотр"... */
    val eyebrow: String?,
    val title: String,
    val description: String?,
    /** Label of the primary button: "Смотреть", "Открыть", "Продолжить". */
    val actionLabel: String,
    val art: ArtSource,
    val appKey: AppKey?,
    /** Intent.toUri(URI_INTENT_SCHEME) deep link, or null to just launch [appKey]. */
    val intentUri: String?,
    /** 0f..1f watch progress, null when unknown. */
    val progress: Float?,
)

@Immutable
data class ContinueItem(
    val id: String,
    val title: String,
    /** Second line: app name, episode, "Осталось 24 мин"... */
    val subtitle: String?,
    val art: ArtSource,
    /** 0f..1f, null when the source cannot tell (then no progress bar is drawn — never fake it). */
    val progress: Float?,
    val appKey: AppKey?,
    val intentUri: String?,
)

enum class InputKind { Hdmi, Av, Component, Tuner, Other }

@Immutable
data class InputItem(
    val id: String,
    val label: String,
    val kind: InputKind,
    /** Cable/source connected, null when the platform does not report it. */
    val connected: Boolean?,
    /** True when the system API can actually switch to it. False = shown for layout only (placeholder). */
    val switchable: Boolean,
)

enum class NetworkKind { Wifi, Ethernet, Other, Offline }

@Immutable
data class NetworkState(val kind: NetworkKind = NetworkKind.Offline, val validated: Boolean = false)

@Immutable
data class ClockState(
    /** "19:42" */
    val time: String = "",
    /** "13 ноября, пятница" */
    val date: String = "",
    /** "Добрый вечер" */
    val greeting: String = "",
)

@Immutable
data class DeviceInfo(
    val manufacturer: String = "",
    val brand: String = "",
    val model: String = "",
    val device: String = "",
    val androidRelease: String = "",
    val sdkInt: Int = 0,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val densityDpi: Int = 0,
    val totalRamMb: Int = 0,
    val lowRam: Boolean = false,
    val cpuCores: Int = 0,
    /** FEATURE_LEANBACK present (Android TV-like system UI contract). */
    val hasLeanback: Boolean = false,
    val launcherVersion: String = "",
    /** Package currently resolving the HOME intent, null if ambiguous/unknown. */
    val currentHomePackage: String? = null,
    /** True when this app is the current default HOME activity. */
    val isDefaultHome: Boolean = false,
    /** True when our optional HOME activity-alias is enabled. */
    val homeAliasEnabled: Boolean = false,
)

// ---------------------------------------------------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------------------------------------------------

/** "Dark mode" variants. The launcher is dark-only by design; Black is true OLED black, Graphite is a softer dark. */
enum class ThemeMode(val title: String) { Black("Чёрная"), Graphite("Графит") }

enum class AccentColor(val title: String, val argb: Long) {
    Emerald("Изумруд", 0xFF21D07A),
    Lime("Лайм", 0xFFB6E33A),
    Sky("Небо", 0xFF3DA5FF),
    Amethyst("Аметист", 0xFFA98BFF),
    Amber("Янтарь", 0xFFFFB13D),
    Coral("Коралл", 0xFFFF6B5E),
    Snow("Белый", 0xFFF2F4F7),
}

/** User-facing animation setting. Auto picks a level from the device performance tier. */
enum class AnimationLevel(val title: String) { Auto("Авто"), Full("Полные"), Reduced("Упрощённые"), Off("Выключены") }

/** Resolved effects level actually used by the UI. */
enum class EffectsLevel { Full, Reduced, Off }

@Immutable
data class LauncherSettings(
    /** 4..7 */
    val dockSize: Int = DEFAULT_DOCK_SIZE,
    /** Explicit dock content in order. null = automatic (priority list from launcher-config). */
    val dockApps: List<AppKey>? = null,
    /** User-defined order of the Apps section. Apps that are not listed follow the default sort. */
    val appOrder: List<AppKey> = emptyList(),
    val hiddenApps: Set<AppKey> = emptySet(),
    /** Also list apps that only have a phone-style LAUNCHER activity. */
    val showNonTvApps: Boolean = false,
    val theme: ThemeMode = ThemeMode.Black,
    /** Wallpaper id from [Wallpapers], [Wallpapers.DYNAMIC] (follow hero) or [Wallpapers.CUSTOM] (user file). */
    val wallpaper: String = Wallpapers.DYNAMIC,
    val accent: AccentColor = AccentColor.Emerald,
    val animation: AnimationLevel = AnimationLevel.Auto,
    /** Show clearly-labelled sample content in Hero / Continue Watching (for demos and screenshots). */
    val demoContent: Boolean = false,
) {
    companion object {
        const val MIN_DOCK_SIZE = 4
        const val MAX_DOCK_SIZE = 7
        const val DEFAULT_DOCK_SIZE = 5
    }
}

@Immutable
data class WallpaperInfo(val id: String, val title: String)

/** Built-in artwork ids. Files live in res/drawable-nodpi as `wp_<id>.webp`. */
object Wallpapers {
    /** Backdrop follows the current hero item (default). */
    const val DYNAMIC = "dynamic"

    /** User file: <external files dir>/wallpaper.(jpg|png|webp). */
    const val CUSTOM = "custom"

    const val AURORA = "aurora"
    const val DUNES = "dunes"
    const val HORIZON = "horizon"
    const val NEBULA = "nebula"
    const val RIDGE = "ridge"
    const val TIDE = "tide"

    val builtIn: List<WallpaperInfo> = listOf(
        WallpaperInfo(AURORA, "Сияние"),
        WallpaperInfo(DUNES, "Дюны"),
        WallpaperInfo(HORIZON, "Горизонт"),
        WallpaperInfo(NEBULA, "Туманность"),
        WallpaperInfo(RIDGE, "Хребет"),
        WallpaperInfo(TIDE, "Прилив"),
    )

    /** All choices in the order shown in Settings → Оформление → Обои. */
    val choices: List<WallpaperInfo> =
        listOf(WallpaperInfo(DYNAMIC, "Динамические")) + builtIn + WallpaperInfo(CUSTOM, "Свой файл")
}

/** A remembered app launch (for "recently used" sorting and the Continue Watching fallback). */
@Immutable
data class RecentLaunch(val key: AppKey, val lastUsedAt: Long, val launchCount: Int)
