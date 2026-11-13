package tv.monotv.launcher.data.settings

import tv.monotv.launcher.core.AccentColor
import tv.monotv.launcher.core.AnimationLevel
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.LauncherSettings
import tv.monotv.launcher.core.RecentLaunch
import tv.monotv.launcher.core.ThemeMode
import tv.monotv.launcher.core.Wallpapers

/**
 * Text form of everything the launcher remembers: [LauncherSettings] as a flat string map (one entry per field, so a
 * field added in a later version never invalidates the others) and the recent launches as one string.
 *
 * Kept free of Android types so the format is pinned down by plain JVM tests — a launcher that forgets the user's
 * Dock after an update is worse than one that never had the feature.
 *
 * Decoding never fails. The store may have been written by an older or newer build, edited by hand or cut short by
 * a power loss (TVs are switched off at the wall), so every field is validated on its own and falls back to its
 * default without dragging the rest of the settings down with it.
 */
object SettingsCodec {

    const val KEY_DOCK_SIZE = "dockSize"
    const val KEY_DOCK_APPS = "dockApps"
    const val KEY_APP_ORDER = "appOrder"
    const val KEY_HIDDEN_APPS = "hiddenApps"
    const val KEY_SHOW_NON_TV_APPS = "showNonTvApps"
    const val KEY_THEME = "theme"
    const val KEY_WALLPAPER = "wallpaper"
    const val KEY_ACCENT = "accent"
    const val KEY_ANIMATION = "animation"
    const val KEY_DEMO_CONTENT = "demoContent"

    /**
     * Upper bound of the recent-launch history. Thirty entries cover "recently used" sorting many times over while
     * keeping the stored string — rewritten on every app launch — a couple of kilobytes at most.
     */
    const val MAX_RECENTS = 30

    /** Between app keys and between recent entries; cannot occur in a package or class name. */
    private const val LINE_SEPARATOR = '\n'

    /** Between the fields of one recent entry. The key comes last, so it is taken verbatim whatever it contains. */
    private const val FIELD_SEPARATOR = ' '

    private val wallpaperIds: Set<String> = Wallpapers.choices.mapTo(HashSet()) { it.id }

    /**
     * The persisted form of [settings].
     *
     * [KEY_DOCK_APPS] is left out for an automatic Dock (`dockApps == null`) and present — possibly empty — for a
     * manual one: an emptied Dock is a choice the user made and must not turn back into the automatic one after a
     * restart. A store therefore has to remove keys that are absent from the returned map.
     */
    fun encode(settings: LauncherSettings): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        map[KEY_DOCK_SIZE] = settings.dockSize.toString()
        settings.dockApps?.let { map[KEY_DOCK_APPS] = encodeKeys(it) }
        map[KEY_APP_ORDER] = encodeKeys(settings.appOrder)
        map[KEY_HIDDEN_APPS] = encodeKeys(settings.hiddenApps)
        map[KEY_SHOW_NON_TV_APPS] = settings.showNonTvApps.toString()
        map[KEY_THEME] = settings.theme.name
        map[KEY_WALLPAPER] = settings.wallpaper
        map[KEY_ACCENT] = settings.accent.name
        map[KEY_ANIMATION] = settings.animation.name
        map[KEY_DEMO_CONTENT] = settings.demoContent.toString()
        return map
    }

    /**
     * Rebuilds settings from [stored]. Missing, null or unreadable entries yield the field's default; unknown keys
     * are ignored. Besides parsing, the result is made safe to use: the Dock size is clamped to the supported range,
     * an unknown wallpaper id becomes the default one and app lists lose unparsable and duplicate entries.
     */
    fun decode(stored: Map<String, String?>): LauncherSettings {
        val defaults = LauncherSettings()
        return LauncherSettings(
            dockSize = stored[KEY_DOCK_SIZE]?.trim()?.toIntOrNull()
                ?.coerceIn(LauncherSettings.MIN_DOCK_SIZE, LauncherSettings.MAX_DOCK_SIZE)
                ?: defaults.dockSize,
            dockApps = stored[KEY_DOCK_APPS]?.let(::decodeDockApps),
            appOrder = stored[KEY_APP_ORDER]?.let(::decodeKeys) ?: defaults.appOrder,
            hiddenApps = stored[KEY_HIDDEN_APPS]?.let { decodeKeys(it).toSet() } ?: defaults.hiddenApps,
            showNonTvApps = decodeBoolean(stored[KEY_SHOW_NON_TV_APPS], defaults.showNonTvApps),
            theme = decodeEnum(stored[KEY_THEME], defaults.theme),
            wallpaper = stored[KEY_WALLPAPER]?.trim()?.takeIf { it in wallpaperIds } ?: defaults.wallpaper,
            accent = decodeEnum(stored[KEY_ACCENT], defaults.accent),
            animation = decodeEnum(stored[KEY_ANIMATION], defaults.animation),
            demoContent = decodeBoolean(stored[KEY_DEMO_CONTENT], defaults.demoContent),
        )
    }

    /** One line per launch — `<lastUsedAt> <launchCount> <package/activity>` — in list order. */
    fun encodeRecents(recents: List<RecentLaunch>): String =
        recents.asSequence()
            .filter { isStorable(it.key) }
            .joinToString(LINE_SEPARATOR.toString()) { recent ->
                "${recent.lastUsedAt}$FIELD_SEPARATOR${recent.launchCount}$FIELD_SEPARATOR${recent.key.flatten()}"
            }

    /**
     * Reads what [encodeRecents] wrote, skipping damaged lines and repeated apps and keeping at most [MAX_RECENTS]
     * entries.
     *
     * The stored order is kept as it is instead of being re-sorted by time: TV boxes have no battery-backed clock
     * and boot with a date years in the past until the network sets it, so "which app was launched last" is only
     * reliable as a position in the list.
     */
    fun decodeRecents(stored: String?): List<RecentLaunch> {
        if (stored.isNullOrEmpty()) return emptyList()
        val seen = HashSet<AppKey>()
        val result = ArrayList<RecentLaunch>()
        for (line in stored.lineSequence()) {
            if (result.size >= MAX_RECENTS) break
            val recent = decodeRecent(line) ?: continue
            if (seen.add(recent.key)) result += recent
        }
        return result
    }

    private fun decodeRecent(line: String): RecentLaunch? {
        val fields = line.trim().split(FIELD_SEPARATOR, limit = 3)
        if (fields.size != 3) return null
        val lastUsedAt = fields[0].toLongOrNull() ?: return null
        val launchCount = fields[1].toIntOrNull() ?: return null
        val key = AppKey.parse(fields[2].trim()) ?: return null
        return RecentLaunch(key, lastUsedAt, launchCount.coerceAtLeast(1))
    }

    private fun encodeKeys(keys: Collection<AppKey>): String =
        keys.asSequence().filter(::isStorable).joinToString(LINE_SEPARATOR.toString()) { it.flatten() }

    /**
     * An empty value is a deliberately emptied manual Dock. Text without a single readable key, on the other hand,
     * is damage — and the safe answer to a damaged Dock is the automatic one, not a blank row.
     */
    private fun decodeDockApps(text: String): List<AppKey>? {
        if (text.isEmpty()) return emptyList()
        return decodeKeys(text).takeIf { it.isNotEmpty() }
    }

    private fun decodeKeys(text: String): List<AppKey> {
        if (text.isEmpty()) return emptyList()
        val keys = LinkedHashSet<AppKey>()
        for (line in text.lineSequence()) AppKey.parse(line.trim())?.let(keys::add)
        return keys.toList()
    }

    /**
     * A key is written only when it reads back as itself. Real component names always do; this merely stops a
     * malformed key (a line break in a name, say) from corrupting its neighbours in the same list.
     */
    private fun isStorable(key: AppKey): Boolean {
        val flat = key.flatten()
        return flat.none { it == '\n' || it == '\r' } && flat == flat.trim() && AppKey.parse(flat) == key
    }

    /** Strict on purpose: anything other than the two literals is damage, not an opinion about the setting. */
    private fun decodeBoolean(text: String?, default: Boolean): Boolean = when (text?.trim()) {
        "true" -> true
        "false" -> false
        else -> default
    }

    private inline fun <reified E : Enum<E>> decodeEnum(text: String?, default: E): E {
        val name = text?.trim() ?: return default
        return enumValues<E>().firstOrNull { it.name == name } ?: default
    }
}
