package tv.monotv.launcher.core

import java.text.Collator
import java.util.Locale

/**
 * Rules that decide which apps are shown where (Dock, "Приложения") and how the user's edits change
 * [LauncherSettings].
 *
 * Everything is a pure function of its arguments: the ViewModel recomputes the lists on every data change and the
 * whole behaviour is covered by plain JVM tests. Mutations never edit in place — they return a new
 * [LauncherSettings], or the very same instance when there is nothing to change so callers can skip persisting.
 */
object AppArrangement {

    /**
     * An app may appear on Home when the user has not hidden it and it is a real TV app; phone-style apps are
     * admitted only when the user asked for them in Settings.
     */
    fun isEligible(app: AppEntry, settings: LauncherSettings): Boolean =
        app.key !in settings.hiddenApps && (app.isLeanback || settings.showNonTvApps)

    /**
     * Order used wherever apps are sorted by name: Russian collation, case-insensitive, with the flattened key as a
     * tie-break so two apps with the same label never swap places between scans.
     *
     * A new comparator is created per call because [Collator] instances are not guaranteed to be thread-safe.
     */
    fun labelComparator(): Comparator<AppEntry> {
        val collator = Collator.getInstance(Locale.forLanguageTag("ru")).apply { strength = Collator.SECONDARY }
        return Comparator { a, b ->
            val byLabel = collator.compare(a.label, b.label)
            if (byLabel != 0) byLabel else a.key.flatten().compareTo(b.key.flatten())
        }
    }

    /**
     * Content of the Dock, at most `settings.dockSize` apps, all of them taken from [all].
     *
     * Manual mode (`settings.dockApps != null`) shows exactly what the user chose and never auto-fills: an empty
     * slot is a decision, not a gap to paper over. Automatic mode walks [LauncherConfig.dockPriority] (one app per
     * known id, packages in order of preference) and then tops up with TV apps — recently used first, then by name —
     * so a fresh install still gets a full, sensible Dock.
     */
    fun dock(
        all: List<AppEntry>,
        settings: LauncherSettings,
        config: LauncherConfig,
        recents: List<RecentLaunch>,
    ): List<AppEntry> {
        val size = dockCapacity(settings)
        val eligible = eligible(all, settings)

        val manual = settings.dockApps
        if (manual != null) {
            val byKey = eligible.associateBy { it.key }
            return manual.distinct().mapNotNull(byKey::get).take(size)
        }

        val picked = LinkedHashMap<AppKey, AppEntry>()
        for (id in config.dockPriority) {
            if (picked.size >= size) break
            val known = config.known(id) ?: continue
            val app = known.packages.firstNotNullOfOrNull { pkg ->
                eligible.firstOrNull { it.key.packageName == pkg && it.key !in picked }
            } ?: continue
            picked[app.key] = app
        }
        if (picked.size < size) {
            val candidates = eligible.filter { it.isLeanback && it.key !in picked }
            for (app in byRecencyThenLabel(candidates, recents)) {
                if (picked.size >= size) break
                picked[app.key] = app
            }
        }
        return picked.values.toList()
    }

    /**
     * Apps of the "Приложения" section in display order.
     *
     * Default order: Dock apps (same order as the Dock), then recently used, then the rest by name. Once the user
     * has rearranged the grid, the apps listed in `settings.appOrder` come first in exactly that order and anything
     * unlisted (typically newly installed apps) follows in the default order.
     */
    fun visibleApps(
        all: List<AppEntry>,
        settings: LauncherSettings,
        dock: List<AppEntry>,
        recents: List<RecentLaunch>,
    ): List<AppEntry> {
        val eligible = eligible(all, settings)
        val byKey = eligible.associateBy { it.key }

        val dockFirst = dock.mapNotNull { byKey[it.key] }.distinctBy { it.key }
        val dockKeys = dockFirst.mapTo(HashSet()) { it.key }
        val defaultOrder = dockFirst + byRecencyThenLabel(eligible.filter { it.key !in dockKeys }, recents)
        if (settings.appOrder.isEmpty()) return defaultOrder

        val listed = settings.appOrder.distinct().mapNotNull(byKey::get)
        val listedKeys = listed.mapTo(HashSet()) { it.key }
        return listed + defaultOrder.filter { it.key !in listedKeys }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Moves a Dock item. The Dock the user sees ([currentDock]) is frozen into `dockApps` first, because rearranging
     * an automatic Dock is the moment it becomes the user's own. Invalid or no-op indices change nothing.
     */
    fun moveDockItem(settings: LauncherSettings, currentDock: List<AppEntry>, from: Int, to: Int): LauncherSettings {
        val moved = currentDock.map { it.key }.moved(from, to) ?: return settings
        return settings.copy(dockApps = moved)
    }

    /**
     * Moves an app inside the Apps grid. The visible order is frozen into `appOrder` first so that every other tile
     * stays where the user sees it. Invalid or no-op indices change nothing.
     */
    fun moveApp(settings: LauncherSettings, currentVisible: List<AppEntry>, from: Int, to: Int): LauncherSettings {
        val moved = currentVisible.map { it.key }.moved(from, to) ?: return settings
        return settings.copy(appOrder = moved)
    }

    /** Appends [key] to the Dock when there is a free slot. A full Dock or an app that is already there is a no-op. */
    fun addToDock(settings: LauncherSettings, currentDock: List<AppEntry>, key: AppKey): LauncherSettings {
        val keys = currentDock.map { it.key }
        if (key in keys || keys.size >= dockCapacity(settings)) return settings
        return settings.copy(dockApps = keys + key)
    }

    /** Removes [key] from the Dock (which becomes a manual Dock). An app that is not in the Dock is a no-op. */
    fun removeFromDock(settings: LauncherSettings, currentDock: List<AppEntry>, key: AppKey): LauncherSettings {
        val keys = currentDock.map { it.key }
        if (key !in keys) return settings
        return settings.copy(dockApps = keys.filter { it != key })
    }

    /** Check-box semantics for Settings → Dock → "Приложения в Dock". */
    fun setDockMembership(
        settings: LauncherSettings,
        currentDock: List<AppEntry>,
        key: AppKey,
        inDock: Boolean,
    ): LauncherSettings =
        if (inDock) addToDock(settings, currentDock, key) else removeFromDock(settings, currentDock, key)

    /**
     * Hides or un-hides an app. Hiding also takes the app out of a manual Dock: otherwise un-hiding it weeks later
     * would silently push it back into the Dock. An automatic Dock needs no edit — hidden apps are not eligible, so
     * it simply refills the slot.
     *
     * [currentDock] is part of the signature for symmetry with the other Dock edits; the stored `dockApps` is what
     * gets filtered, so entries that are only temporarily not displayed (e.g. phone apps while they are switched
     * off) keep their place.
     */
    fun setHidden(
        settings: LauncherSettings,
        @Suppress("UNUSED_PARAMETER") currentDock: List<AppEntry>,
        key: AppKey,
        hidden: Boolean,
    ): LauncherSettings {
        if (hidden == (key in settings.hiddenApps)) return settings
        if (!hidden) return settings.copy(hiddenApps = settings.hiddenApps - key)
        return settings.copy(
            hiddenApps = settings.hiddenApps + key,
            dockApps = settings.dockApps?.filter { it != key },
        )
    }

    /** Sets the Dock size, clamped to the supported range. A manual Dock that no longer fits loses its tail. */
    fun setDockSize(settings: LauncherSettings, size: Int): LauncherSettings {
        val clamped = size.coerceIn(LauncherSettings.MIN_DOCK_SIZE, LauncherSettings.MAX_DOCK_SIZE)
        val manual = settings.dockApps
        val fits = manual == null || manual.size <= clamped
        if (clamped == settings.dockSize && fits) return settings
        return settings.copy(dockSize = clamped, dockApps = if (fits) manual else manual.take(clamped))
    }

    /** Back to the automatic Dock. */
    fun resetDock(settings: LauncherSettings): LauncherSettings =
        if (settings.dockApps == null) settings else settings.copy(dockApps = null)

    /** Back to the default order with nothing hidden. */
    fun resetApps(settings: LauncherSettings): LauncherSettings =
        if (settings.appOrder.isEmpty() && settings.hiddenApps.isEmpty()) {
            settings
        } else {
            settings.copy(appOrder = emptyList(), hiddenApps = emptySet())
        }

    /**
     * Forgets apps that are no longer installed so stale keys do not pile up in the stored settings.
     *
     * Call it with the result of a complete scan only. An empty [installed] set is treated as "the scan failed"
     * (a TV always has at least its system apps) and changes nothing — wiping the user's Dock because the package
     * manager hiccuped would be unforgivable.
     */
    fun pruneMissing(settings: LauncherSettings, installed: Set<AppKey>): LauncherSettings {
        if (installed.isEmpty()) return settings
        val dock = settings.dockApps?.filter { it in installed }
        val order = settings.appOrder.filter { it in installed }
        val hidden = settings.hiddenApps.filterTo(LinkedHashSet()) { it in installed }
        val unchanged = dock?.size == settings.dockApps?.size &&
            order.size == settings.appOrder.size &&
            hidden.size == settings.hiddenApps.size
        return if (unchanged) settings else settings.copy(dockApps = dock, appOrder = order, hiddenApps = hidden)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------------------------------

    /** Stored sizes are clamped on read as well: settings written by another build must never break the layout. */
    private fun dockCapacity(settings: LauncherSettings): Int =
        settings.dockSize.coerceIn(LauncherSettings.MIN_DOCK_SIZE, LauncherSettings.MAX_DOCK_SIZE)

    private fun eligible(all: List<AppEntry>, settings: LauncherSettings): List<AppEntry> =
        all.filter { isEligible(it, settings) }.distinctBy { it.key }

    /**
     * Recently used apps first (most recent first), the rest by label. [recents] is documented as already sorted;
     * sorting again by timestamp is stable, so it costs nothing when that holds and saves us when it does not.
     */
    private fun byRecencyThenLabel(apps: List<AppEntry>, recents: List<RecentLaunch>): List<AppEntry> {
        if (apps.isEmpty()) return apps
        val rank = HashMap<AppKey, Int>()
        for (recent in recents.sortedByDescending { it.lastUsedAt }) {
            if (recent.key !in rank) rank[recent.key] = rank.size
        }
        val (used, unused) = apps.partition { it.key in rank }
        return used.sortedBy { rank.getValue(it.key) } + unused.sortedWith(labelComparator())
    }

    private fun <T> List<T>.moved(from: Int, to: Int): List<T>? {
        if (from == to || from !in indices || to !in indices) return null
        return toMutableList().apply { add(to, removeAt(from)) }
    }
}
