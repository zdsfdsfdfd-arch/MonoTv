package tv.monotv.launcher.data.content

import tv.monotv.launcher.ContentPlanner
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.ContinueItem
import tv.monotv.launcher.core.HeroItem
import tv.monotv.launcher.core.HeroKind
import tv.monotv.launcher.core.LauncherConfig
import tv.monotv.launcher.core.RecentLaunch
import tv.monotv.launcher.core.Wallpapers

/**
 * Decides what the Hero carousel and the "Продолжить просмотр" row show.
 *
 * The launcher has no content catalogue of its own (no network, no account), so everything on the home screen is
 * assembled from what the device really knows, strongest signal first:
 *  1. Watch Next rows published by apps — the only source with real playback progress;
 *  2. apps the user opened recently — an honest "pick up where you left off" that never pretends to know a position;
 *  3. the Dock apps — a spotlight per app, so the hero is useful on a TV where no app publishes anything;
 *  4. clearly labelled demo entries, only when the user switched them on.
 *
 * Everything here is a pure function of its arguments (no Android types, no clock, no randomness): the ViewModel
 * recomputes it synchronously on every state change and plain JVM tests pin the rules down.
 */
object ContentComposer : ContentPlanner {
    /** Default length of the "Продолжить просмотр" row: about two screens of cards, enough to never feel endless. */
    const val DEFAULT_MAX_CONTINUE = 10

    /** Default length of the hero carousel: more pages than this are never reached with a remote. */
    const val DEFAULT_MAX_HERO = 6

    /** Recently opened apps are a fallback, not the point of the row — keep them from crowding it. */
    const val MAX_RECENT_FALLBACK = 6

    /** Continue-watching entries promoted into the hero; the rest of the carousel belongs to the Dock apps. */
    const val MAX_HERO_CONTINUE = 2

    /** Id prefix of items that come from the platform Watch Next table (`"wn:<row id>"`). */
    const val WATCH_NEXT_ID_PREFIX = "wn:"

    /** Id prefix of the recently-opened-app fallback (`"recent:<AppKey.flatten()>"`). */
    const val RECENT_ID_PREFIX = "recent:"

    /** Id prefix of a Dock app spotlight in the hero (`"app:<AppKey.flatten()>"`). */
    const val APP_ID_PREFIX = "app:"

    /** Id of the single hero page shown when there is nothing else to show. */
    const val WELCOME_ID = "welcome"

    private const val SUBTITLE_RECENT = "Недавно открывали"
    private const val EYEBROW_CONTINUE = "Продолжить просмотр"
    private const val ACTION_CONTINUE = "Продолжить"
    private const val EYEBROW_APP = "Приложение"
    private const val ACTION_OPEN = "Открыть"

    /**
     * Shown instead of an empty hero (fresh install, no Dock yet). It has neither an app nor a deep link, which the
     * ViewModel reads as "open the launcher settings".
     */
    private val welcome = HeroItem(
        id = WELCOME_ID,
        kind = HeroKind.App,
        eyebrow = "Mono TV",
        title = "Добро пожаловать",
        description = "Откройте настройки, чтобы собрать Dock и выбрать обои.",
        actionLabel = "Настройки",
        art = ArtSource.Wallpaper(Wallpapers.AURORA),
        appKey = null,
        intentUri = null,
        progress = null,
    )

    override fun continueItems(
        watchNext: List<ContinueItem>,
        recents: List<RecentLaunch>,
        apps: List<AppEntry>,
        demo: Boolean,
    ): List<ContinueItem> = continueItems(watchNext, recents, apps, demo, DEFAULT_MAX_CONTINUE)

    /**
     * Builds the "Продолжить просмотр" row.
     *
     * Watch Next entries come first because they are the only ones that resume real playback. They are followed by
     * at most [MAX_RECENT_FALLBACK] recently opened apps (most recent first) that are still in [apps] and do not
     * already have a Watch Next entry — showing "Кинопоиск" right after a film from Кинопоиск would be noise. Those
     * fallback cards deliberately carry `progress = null`: the launcher does not know what was watched and never
     * draws a made-up progress bar. Demo cards, when enabled, go last so they can not push real content out.
     *
     * @param apps the apps the user may currently see; a recent launch of anything else (uninstalled, hidden) is
     *   dropped.
     * @param max upper bound of the result; the row is cut, never padded.
     */
    fun continueItems(
        watchNext: List<ContinueItem>,
        recents: List<RecentLaunch>,
        apps: List<AppEntry>,
        demo: Boolean,
        max: Int = DEFAULT_MAX_CONTINUE,
    ): List<ContinueItem> {
        if (max <= 0) return emptyList()
        val installed = apps.associateBy { it.key }
        // Compared by package: one Watch Next entry represents the app whichever of its activities was launched.
        val representedPackages = watchNext.mapNotNullTo(HashSet()) { it.appKey?.packageName }
        val recentItems = recents.asSequence()
            .sortedByDescending { it.lastUsedAt }
            .mapNotNull { installed[it.key] }
            .filter { it.key.packageName !in representedPackages }
            .distinctBy { it.key }
            .take(MAX_RECENT_FALLBACK)
            .map { app ->
                ContinueItem(
                    id = RECENT_ID_PREFIX + app.key.flatten(),
                    title = app.label,
                    subtitle = SUBTITLE_RECENT,
                    art = ArtSource.AppBanner(app.key),
                    progress = null,
                    appKey = app.key,
                    intentUri = null,
                )
            }
        val demoItems = if (demo) DemoContent.continueItems else emptyList()
        return (watchNext.asSequence() + recentItems + demoItems).distinctBy { it.id }.take(max).toList()
    }

    override fun heroItems(
        continueItems: List<ContinueItem>,
        dock: List<AppEntry>,
        config: LauncherConfig,
        demo: Boolean,
    ): List<HeroItem> = heroItems(continueItems, dock, config, demo, DEFAULT_MAX_HERO)

    /**
     * Builds the hero carousel.
     *
     * Order: demo spotlights (only when [demo] is on — the user asked for a showcase, so it leads), then up to
     * [MAX_HERO_CONTINUE] entries of [continueItems] that have real playback progress, then one spotlight per Dock
     * app. Only Watch Next entries are promoted: a "recently opened" card has nothing to continue, and its app is
     * usually in the Dock anyway.
     *
     * The result is never empty — a hero without pages would leave the top half of the home screen blank and the
     * focus with nowhere to go — so a welcome page that leads to Settings stands in when nothing else exists.
     *
     * @param max upper bound of the result (at least one page is always returned).
     */
    fun heroItems(
        continueItems: List<ContinueItem>,
        dock: List<AppEntry>,
        config: LauncherConfig,
        demo: Boolean,
        max: Int = DEFAULT_MAX_HERO,
    ): List<HeroItem> {
        val demoItems = if (demo) DemoContent.heroItems else emptyList()
        val resumable = continueItems.asSequence()
            .filter { it.progress != null && it.id.startsWith(WATCH_NEXT_ID_PREFIX) }
            .take(MAX_HERO_CONTINUE)
            .map { item ->
                HeroItem(
                    id = item.id,
                    kind = HeroKind.Content,
                    eyebrow = EYEBROW_CONTINUE,
                    title = item.title,
                    description = item.subtitle,
                    actionLabel = ACTION_CONTINUE,
                    art = item.art,
                    appKey = item.appKey,
                    intentUri = item.intentUri,
                    progress = item.progress,
                )
            }
        val backdrops = backdrops(dock.map { it.key.packageName })
        val spotlights = dock.asSequence().map { app ->
            HeroItem(
                id = APP_ID_PREFIX + app.key.flatten(),
                kind = HeroKind.App,
                eyebrow = EYEBROW_APP,
                title = app.label,
                description = config.known(app.knownId)?.tagline,
                actionLabel = ACTION_OPEN,
                art = ArtSource.Wallpaper(backdrops.getValue(app.key.packageName)),
                appKey = app.key,
                intentUri = null,
                progress = null,
            )
        }
        return (demoItems.asSequence() + resumable + spotlights)
            .distinctBy { it.id }
            .take(max.coerceAtLeast(1))
            .toList()
            .ifEmpty { listOf(welcome) }
    }

    /**
     * Built-in wallpaper (id) used as the backdrop of each app spotlight, keyed by package name.
     *
     * Apps ship a small banner, not a full-screen picture, so a spotlight borrows one of the launcher's own
     * wallpapers. Which one is decided by a stable hash of the package name: the same Dock looks the same after
     * every restart, app update and on every device, instead of the home screen reshuffling itself.
     *
     * A hash alone would repeat itself all the time, though: with six wallpapers, five Dock apps get five different
     * ones in fewer than one case out of ten, and identical neighbours make paging through the hero look frozen.
     * So when two apps of the same Dock prefer the same wallpaper, the one whose package name sorts first keeps it
     * and the other takes the next free one. Resolving in package-name order (not Dock order) makes the result
     * depend only on which apps are in the Dock: rearranging the Dock never changes a backdrop.
     */
    internal fun backdrops(packageNames: Collection<String>): Map<String, String> {
        val wallpapers = Wallpapers.builtIn
        val taken = BooleanArray(wallpapers.size)
        var free = wallpapers.size
        val result = HashMap<String, String>()
        for (packageName in packageNames.toSortedSet()) {
            if (free == 0) {
                // More apps than wallpapers: repeats are unavoidable, start the next round.
                taken.fill(false)
                free = wallpapers.size
            }
            var index = preferredBackdrop(packageName)
            while (taken[index]) index = (index + 1) % wallpapers.size
            taken[index] = true
            free--
            result[packageName] = wallpapers[index].id
        }
        return result
    }

    /** Index into [Wallpapers.builtIn] that the package would get if no other app wanted the same one. */
    private fun preferredBackdrop(packageName: String): Int = stableHash(packageName).mod(Wallpapers.builtIn.size)

    /**
     * 32-bit FNV-1a over the UTF-16 code units, with the high half folded into the low half because the result is
     * reduced modulo a small number and FNV's lowest bits are weak (the last bit is merely the parity of the input).
     * Written out instead of relying on a runtime-provided hash so the mapping is part of this file: the same
     * package gives the same value on every device, Android version and build of the app.
     */
    private fun stableHash(text: String): Int {
        var hash = FNV_OFFSET_BASIS
        for (unit in text) {
            hash = (hash xor unit.code) * FNV_PRIME
        }
        return hash xor (hash ushr 16)
    }

    private const val FNV_OFFSET_BASIS = 0x811C9DC5.toInt()
    private const val FNV_PRIME = 0x01000193
}
