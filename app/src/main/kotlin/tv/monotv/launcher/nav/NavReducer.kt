package tv.monotv.launcher.nav

import java.util.EnumMap
import tv.monotv.launcher.core.AppKey

/**
 * The complete D-pad behaviour of the launcher as pure functions of ([NavState], [NavKey], [NavContext]).
 *
 * Why a reducer instead of the platform focus system: on a home screen a lost or surprising focus is the worst
 * possible bug — the user is left holding a remote in front of a screen that does not react. Keeping "what is
 * focused" in one immutable value that only this object can change rules that class of bug out by construction and
 * lets every rule be unit-tested (and fuzzed) on the JVM without a device.
 *
 * Guarantees the ViewModel and the UI rely on:
 *  - nothing here throws, whatever the input: stale indices, negative counts, even a failing [NavContext] callback
 *    degrade to "nothing there" (a crash of the HOME app on a key press would restart the whole home screen);
 *  - a state returned by [reduce] or [normalize] is valid for the context it was computed with: every index is in
 *    range, the focused section exists, overlays point at existing rows. The one deliberate exception are
 *    [NavEffect.MoveItem] / [NavEffect.SettingsMove]: the returned index already follows the carried item to its new
 *    position, so the ViewModel has to apply the move before the next key press;
 *  - the functions are deterministic and touch no Android / Compose API, so they are safe on any thread.
 */
object NavReducer {

    /**
     * Applies one key press and returns the new state plus the side effects the ViewModel has to execute.
     *
     * [state] is re-validated against [ctx] first (see [normalize]) because the data behind it can change between
     * two key presses — an app is uninstalled, an input disappears — and the key must then act on what is really on
     * screen instead of on a stale index. Key / state combinations without a defined meaning are no-ops.
     */
    fun reduce(state: NavState, key: NavKey, ctx: NavContext): NavResult {
        val safe = SafeContext(ctx)
        val current = normalize(state, safe)
        return when (val overlay = current.topOverlay) {
            is Overlay.Inputs -> onInputs(current, overlay, key, safe)
            is Overlay.Settings -> onSettings(current, overlay, key, safe)
            is Overlay.AppMenu -> onAppMenu(current, overlay, key, safe)
            is Overlay.Confirm -> onConfirm(current, overlay, key)
            null -> {
                val reorder = current.reorder
                if (reorder == null) onHome(current, key, safe) else onReorder(current, reorder.section, key, safe)
            }
        }
    }

    /**
     * Re-validates [state] after the data changed, without any key press: indices are clamped, the focus leaves a
     * section that became empty, overlays whose content vanished are closed and move mode ends when there is nothing
     * left to move. The ViewModel calls this whenever it rebuilds its data so the UI never renders a focus that
     * points at nothing.
     *
     * Returns the very same instance when [state] is already valid, so callers can cheaply detect "nothing changed".
     */
    fun normalize(state: NavState, ctx: NavContext): NavState = normalize(state, SafeContext(ctx))

    /**
     * Pushes [overlay] on top of the stack. Exists for overlays that are not opened by a key press on a focused
     * element but by the ViewModel in response to an effect (e.g. a [Overlay.Confirm] before a destructive action).
     */
    fun openOverlay(state: NavState, overlay: Overlay): NavState = state.copy(overlays = state.overlays + overlay)

    /** Pops the top-most overlay; a no-op when none is open so callers do not need to check first. */
    fun closeTopOverlay(state: NavState): NavState =
        if (state.overlays.isEmpty()) state else state.copy(overlays = state.overlays.dropLast(1))

    /**
     * The "home position" section: where the focus rests after start and where BACK returns to. It is the Dock (the
     * user's favourite apps are one OK away), falling back to the next most useful non-empty section so that there
     * is always a sensible resting place even on a device with no apps at all.
     */
    fun homeSection(ctx: NavContext): Section = SafeContext(ctx).homeSection

    // -----------------------------------------------------------------------------------------------------------------
    // Normalisation
    // -----------------------------------------------------------------------------------------------------------------

    private fun normalize(state: NavState, ctx: SafeContext): NavState {
        val home = normalizeHome(state.home, ctx)
        val normalized = NavState(
            home = home,
            reorder = state.reorder?.takeIf { canReorder(it.section, home, ctx) },
            overlays = state.overlays.mapNotNull { normalizeOverlay(it, ctx) },
        )
        return if (normalized == state) state else normalized
    }

    private fun normalizeHome(home: HomeNav, ctx: SafeContext): HomeNav = HomeNav(
        section = if (ctx.available(home.section)) home.section else ctx.homeSection,
        topBarIndex = clampIndex(home.topBarIndex, ctx.count(Section.TopBar)),
        heroIndex = clampIndex(home.heroIndex, ctx.count(Section.Hero)),
        dockIndex = clampIndex(home.dockIndex, ctx.count(Section.Dock)),
        continueIndex = clampIndex(home.continueIndex, ctx.count(Section.Continue)),
        appsIndex = clampIndex(home.appsIndex, ctx.count(Section.Apps)),
    )

    /**
     * Move mode is only meaningful on the focused Dock / Apps section while it has at least two items; anything
     * else (including a hand-built state whose reorder section is not the focused one) would leave the user in a
     * mode where the arrow keys do nothing visible.
     */
    private fun canReorder(section: Section, home: HomeNav, ctx: SafeContext): Boolean =
        section.holdsApps && section == home.section && ctx.count(section) >= 2

    /** Returns the valid form of [overlay], or null when it has nothing left to show and must be closed. */
    private fun normalizeOverlay(overlay: Overlay, ctx: SafeContext): Overlay? = when (overlay) {
        is Overlay.Inputs ->
            if (ctx.inputsCount == 0) null else overlay.copy(index = clampIndex(overlay.index, ctx.inputsCount))

        is Overlay.Settings -> normalizeSettings(overlay, ctx)

        is Overlay.AppMenu ->
            if (overlay.items.isEmpty()) null else overlay.copy(index = clampIndex(overlay.index, overlay.items.size))

        is Overlay.Confirm -> overlay.copy(index = overlay.index.coerceIn(CONFIRM_CANCEL, CONFIRM_ACCEPT))
    }

    private fun normalizeSettings(overlay: Overlay.Settings, ctx: SafeContext): Overlay.Settings? {
        val frames = overlay.stack.mapNotNull { normalizeFrame(it, ctx) }
        if (frames.isEmpty()) return null
        // Only the visible page can hold a row: a buried "grabbed" flag would resurface as a row that is mysteriously
        // picked up when the user comes back to that page.
        return Overlay.Settings(
            frames.mapIndexed { i, frame -> if (frame.grabbed && i < frames.lastIndex) frame.copy(grabbed = false) else frame },
        )
    }

    private fun normalizeFrame(frame: SettingsFrame, ctx: SafeContext): SettingsFrame? {
        val rows = ctx.rows(frame.page)
        if (rows.isEmpty()) return null
        val index = clampIndex(frame.index, rows.size)
        return SettingsFrame(frame.page, index, grabbed = frame.grabbed && rows[index].kind is RowKind.Reorderable)
    }

    private fun clampIndex(index: Int, count: Int): Int = if (count <= 0) 0 else index.coerceIn(0, count - 1)

    // -----------------------------------------------------------------------------------------------------------------
    // Home
    // -----------------------------------------------------------------------------------------------------------------

    private fun onHome(state: NavState, key: NavKey, ctx: SafeContext): NavResult = when (key) {
        NavKey.Up, NavKey.Down -> moveVertically(state, key, ctx)
        NavKey.Left, NavKey.Right -> moveHorizontally(state, key, ctx)
        NavKey.Ok -> activate(state, ctx)
        NavKey.OkLong -> openAppMenu(state, ctx) ?: activate(state, ctx)
        NavKey.Menu -> openAppMenu(state, ctx) ?: openSettings(state, key, ctx)
        NavKey.Back -> goBack(state, ctx)
    }

    private fun moveVertically(state: NavState, key: NavKey, ctx: SafeContext): NavResult {
        val home = state.home
        if (home.section == Section.Apps) {
            val inGrid = appsGridTarget(home.appsIndex, key, ctx)
            if (inGrid != null) return NavResult(state.focus(Section.Apps, inGrid))
        }
        val target = neighbour(home.section, key, ctx) ?: return bump(state, key)
        // Only the section changes: its remembered index is still in HomeNav, which is what "restores" it.
        return NavResult(state.copy(home = home.copy(section = target)))
    }

    /** Where UP / DOWN lands inside the Apps grid, or null when the key leaves the grid. */
    private fun appsGridTarget(index: Int, key: NavKey, ctx: SafeContext): Int? {
        val count = ctx.count(Section.Apps)
        val columns = ctx.columns
        if (key == NavKey.Up) return (index - columns).takeIf { it >= 0 }
        return when {
            // Written as a subtraction because index + columns could overflow for an absurd column count.
            columns < count - index -> index + columns
            // The row below is shorter than this column: land on its last item rather than refusing to move.
            index / columns < (count - 1) / columns -> count - 1
            else -> null
        }
    }

    /** Closest available section above (UP) or below (any other key) [section], or null when there is none. */
    private fun neighbour(section: Section, key: NavKey, ctx: SafeContext): Section? {
        val sections = Section.entries
        val step = if (key == NavKey.Up) -1 else 1
        var ordinal = section.ordinal + step
        while (ordinal in sections.indices) {
            if (ctx.available(sections[ordinal])) return sections[ordinal]
            ordinal += step
        }
        return null
    }

    private fun moveHorizontally(state: NavState, key: NavKey, ctx: SafeContext): NavResult {
        val section = state.home.section
        val index = state.home.index(section)
        val forward = key == NavKey.Right
        val target = if (forward) index + 1 else index - 1
        // Row ends of the Apps grid are hard edges: wrapping would throw the focus to the opposite side of the screen.
        val rowEdge = section == Section.Apps && index % ctx.columns == (if (forward) ctx.columns - 1 else 0)
        return if (rowEdge || target !in 0 until ctx.count(section)) bump(state, key) else NavResult(state.focus(section, target))
    }

    private fun activate(state: NavState, ctx: SafeContext): NavResult {
        val section = state.home.section
        // Only possible when the whole home screen is empty: there is nothing under the focus to open.
        if (!ctx.available(section)) return bump(state, NavKey.Ok)
        val index = state.home.index(section)
        return when (section) {
            Section.TopBar -> when (TopBarItem.entries[index]) {
                TopBarItem.Network -> effect(state, NavEffect.OpenNetworkSettings)
                TopBarItem.Inputs ->
                    if (ctx.inputsCount == 0) bump(state, NavKey.Ok) else NavResult(openOverlay(state, Overlay.Inputs()))
                TopBarItem.Settings -> openSettings(state, NavKey.Ok, ctx)
            }
            Section.Hero -> effect(state, NavEffect.OpenHero(index))
            Section.Continue -> effect(state, NavEffect.OpenContinue(index))
            Section.Dock, Section.Apps -> {
                val app = ctx.appAt(section, index)
                if (app == null) bump(state, NavKey.Ok) else effect(state, NavEffect.LaunchApp(app))
            }
        }
    }

    /** Context menu of the focused app, or null when the focus is not on an app (the caller then falls back). */
    private fun openAppMenu(state: NavState, ctx: SafeContext): NavResult? {
        val section = state.home.section
        if (!section.holdsApps) return null
        val app = ctx.appAt(section, state.home.index(section)) ?: return null
        val items = ctx.appMenu(app, section)
        // An empty menu could not be closed by choosing something; treat it as "no menu".
        if (items.isEmpty()) return null
        return NavResult(openOverlay(state, Overlay.AppMenu(app, items)))
    }

    private fun openSettings(state: NavState, key: NavKey, ctx: SafeContext): NavResult =
        if (ctx.rows(SettingsPageId.Root).isEmpty()) bump(state, key) else NavResult(openOverlay(state, Overlay.Settings()))

    private fun goBack(state: NavState, ctx: SafeContext): NavResult {
        val home = state.home
        val resting = ctx.homeSection
        return when {
            home.section != resting -> NavResult(state.copy(home = home.copy(section = resting)))
            resting == Section.Dock && home.dockIndex != 0 -> NavResult(state.copy(home = home.copy(dockIndex = 0)))
            else -> effect(state, NavEffect.ExitApp)
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Move mode
    // -----------------------------------------------------------------------------------------------------------------

    private fun onReorder(state: NavState, section: Section, key: NavKey, ctx: SafeContext): NavResult {
        val step = when (key) {
            NavKey.Left -> -1
            NavKey.Right -> 1
            // The Dock is a single row: there is nowhere to carry the item vertically.
            NavKey.Up -> if (section == Section.Apps) -ctx.columns else return bump(state, key)
            NavKey.Down -> if (section == Section.Apps) ctx.columns else return bump(state, key)
            NavKey.Ok, NavKey.Back, NavKey.Menu -> return NavResult(state.copy(reorder = null))
            // The long press that opened the menu must not immediately do something else in the mode it led to.
            NavKey.OkLong -> return NavResult(state)
        }
        val from = state.home.index(section)
        // Long arithmetic: an absurd column count has to bump instead of overflowing into a "valid" index.
        val to = from.toLong() + step
        if (to < 0 || to >= ctx.count(section)) return bump(state, key)
        return NavResult(state.focus(section, to.toInt()), listOf(NavEffect.MoveItem(section, from, to.toInt())))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Overlays
    // -----------------------------------------------------------------------------------------------------------------

    private fun onInputs(state: NavState, overlay: Overlay.Inputs, key: NavKey, ctx: SafeContext): NavResult = when (key) {
        NavKey.Up, NavKey.Down -> {
            val target = listTarget(overlay.index, key, ctx.inputsCount)
            if (target == null) bump(state, key) else NavResult(replaceTop(state, overlay.copy(index = target)))
        }
        NavKey.Ok -> NavResult(closeTopOverlay(state), listOf(NavEffect.SwitchInput(overlay.index)))
        NavKey.Back, NavKey.Menu, NavKey.Left -> NavResult(closeTopOverlay(state))
        NavKey.Right, NavKey.OkLong -> NavResult(state)
    }

    private fun onSettings(state: NavState, overlay: Overlay.Settings, key: NavKey, ctx: SafeContext): NavResult {
        // normalize() guarantees a non-empty stack whose top frame points at an existing row.
        val frame = overlay.stack.last()
        val rows = ctx.rows(frame.page)
        return if (frame.grabbed) onGrabbedRow(state, overlay, frame, rows, key) else onSettingsPage(state, overlay, frame, rows, key, ctx)
    }

    private fun onGrabbedRow(
        state: NavState,
        overlay: Overlay.Settings,
        frame: SettingsFrame,
        rows: List<SettingsRow>,
        key: NavKey,
    ): NavResult = when (key) {
        NavKey.Up, NavKey.Down -> {
            val target = listTarget(frame.index, key, rows.size)
            if (target == null) {
                bump(state, key)
            } else {
                NavResult(
                    replaceTop(state, overlay.withTopFrame(frame.copy(index = target))),
                    listOf(NavEffect.SettingsMove(frame.page, frame.index, target)),
                )
            }
        }
        NavKey.Ok, NavKey.Back -> NavResult(replaceTop(state, overlay.withTopFrame(frame.copy(grabbed = false))))
        // Leaving the page while a row is in the air would hide where it was dropped.
        NavKey.Left, NavKey.Right, NavKey.Menu, NavKey.OkLong -> NavResult(state)
    }

    private fun onSettingsPage(
        state: NavState,
        overlay: Overlay.Settings,
        frame: SettingsFrame,
        rows: List<SettingsRow>,
        key: NavKey,
        ctx: SafeContext,
    ): NavResult {
        val row = rows[frame.index]
        return when (key) {
            NavKey.Up, NavKey.Down -> {
                val target = listTarget(frame.index, key, rows.size)
                if (target == null) bump(state, key) else NavResult(replaceTop(state, overlay.withTopFrame(frame.copy(index = target))))
            }
            NavKey.Ok -> activateRow(state, overlay, frame, row, key, ctx)
            // RIGHT reads as "go deeper", so it follows chevron rows only and never toggles or runs anything.
            NavKey.Right -> if (row.kind is RowKind.Navigate) activateRow(state, overlay, frame, row, key, ctx) else NavResult(state)
            NavKey.Back, NavKey.Left ->
                if (overlay.stack.size > 1) {
                    NavResult(replaceTop(state, Overlay.Settings(overlay.stack.dropLast(1))))
                } else {
                    NavResult(closeTopOverlay(state))
                }
            NavKey.Menu -> NavResult(closeTopOverlay(state))
            NavKey.OkLong -> NavResult(state)
        }
    }

    private fun activateRow(
        state: NavState,
        overlay: Overlay.Settings,
        frame: SettingsFrame,
        row: SettingsRow,
        key: NavKey,
        ctx: SafeContext,
    ): NavResult {
        if (!row.enabled) return bump(state, key)
        return when (val kind = row.kind) {
            is RowKind.Navigate -> {
                val target = ctx.rows(kind.page)
                // A page without rows could not hold the focus (normalize would pop it straight away).
                if (target.isEmpty()) return bump(state, key)
                // Open choice lists on the current choice so the user sees it without scrolling.
                val start = target.indexOfFirst { it.checked == true }.coerceAtLeast(0)
                NavResult(replaceTop(state, Overlay.Settings(overlay.stack + SettingsFrame(kind.page, start))))
            }
            RowKind.Action -> effect(state, NavEffect.SettingsAction(frame.page, row.id))
            RowKind.Info -> NavResult(state)
            RowKind.Reorderable -> NavResult(replaceTop(state, overlay.withTopFrame(frame.copy(grabbed = true))))
        }
    }

    private fun onAppMenu(state: NavState, overlay: Overlay.AppMenu, key: NavKey, ctx: SafeContext): NavResult = when (key) {
        NavKey.Up, NavKey.Down -> {
            val target = listTarget(overlay.index, key, overlay.items.size)
            if (target == null) bump(state, key) else NavResult(replaceTop(state, overlay.copy(index = target)))
        }
        NavKey.Ok -> {
            val closed = closeTopOverlay(state)
            val item = overlay.items[overlay.index]
            if (item == AppMenuItem.Move) {
                // Move is pure navigation state (no effect): the item under the focus gets picked up — unless the
                // data changed while the menu was open and there is nothing to move any more.
                val section = closed.home.section
                NavResult(if (canReorder(section, closed.home, ctx)) closed.copy(reorder = ReorderState(section)) else closed)
            } else {
                NavResult(closed, listOf(NavEffect.AppMenuAction(overlay.app, item)))
            }
        }
        NavKey.Back, NavKey.Menu, NavKey.Left -> NavResult(closeTopOverlay(state))
        NavKey.Right, NavKey.OkLong -> NavResult(state)
    }

    private fun onConfirm(state: NavState, overlay: Overlay.Confirm, key: NavKey): NavResult = when (key) {
        NavKey.Left -> selectConfirmButton(state, overlay, CONFIRM_CANCEL, key)
        NavKey.Right -> selectConfirmButton(state, overlay, CONFIRM_ACCEPT, key)
        NavKey.Ok -> {
            val closed = closeTopOverlay(state)
            if (overlay.index == CONFIRM_ACCEPT) NavResult(closed, listOf(NavEffect.Confirmed(overlay.id))) else NavResult(closed)
        }
        NavKey.Back -> NavResult(closeTopOverlay(state))
        // A modal question is answered or cancelled explicitly; MENU and long presses must not dismiss it by accident.
        NavKey.Up, NavKey.Down, NavKey.Menu, NavKey.OkLong -> NavResult(state)
    }

    private fun selectConfirmButton(state: NavState, overlay: Overlay.Confirm, button: Int, key: NavKey): NavResult =
        if (overlay.index == button) bump(state, key) else NavResult(replaceTop(state, overlay.copy(index = button)))

    // -----------------------------------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------------------------------

    /** Target of UP (any other key: DOWN) in a vertical list of [count] items, or null at the edge. */
    private fun listTarget(index: Int, key: NavKey, count: Int): Int? =
        (if (key == NavKey.Up) index - 1 else index + 1).takeIf { it in 0 until count }

    private fun bump(state: NavState, key: NavKey): NavResult = effect(state, NavEffect.Bump(key))

    private fun effect(state: NavState, effect: NavEffect): NavResult = NavResult(state, listOf(effect))

    private fun replaceTop(state: NavState, overlay: Overlay): NavState =
        state.copy(overlays = state.overlays.dropLast(1) + overlay)

    private fun Overlay.Settings.withTopFrame(frame: SettingsFrame): Overlay.Settings =
        Overlay.Settings(stack.dropLast(1) + frame)

    private fun NavState.focus(section: Section, index: Int): NavState = copy(home = home.withIndex(section, index))

    private fun HomeNav.index(section: Section): Int = when (section) {
        Section.TopBar -> topBarIndex
        Section.Hero -> heroIndex
        Section.Dock -> dockIndex
        Section.Continue -> continueIndex
        Section.Apps -> appsIndex
    }

    private fun HomeNav.withIndex(section: Section, index: Int): HomeNav = when (section) {
        Section.TopBar -> copy(topBarIndex = index)
        Section.Hero -> copy(heroIndex = index)
        Section.Dock -> copy(dockIndex = index)
        Section.Continue -> copy(continueIndex = index)
        Section.Apps -> copy(appsIndex = index)
    }

    /** Dock and Apps are the sections whose items are apps: they launch, have a context menu and can be reordered. */
    private val Section.holdsApps: Boolean get() = this == Section.Dock || this == Section.Apps

    private const val CONFIRM_CANCEL = 0
    private const val CONFIRM_ACCEPT = 1
}

/** Sections in the order they are tried as the "home position". */
private val HOME_POSITION_PRIORITY = listOf(Section.Dock, Section.Hero, Section.Apps, Section.Continue, Section.TopBar)

/**
 * Defensive, per-call view of a [NavContext].
 *
 * The context is assembled by the ViewModel from live data, so the reducer treats it like any other outside input:
 * counts are never negative, the grid always has at least one column, the top bar never claims more pills than
 * [TopBarItem] defines, and a callback that throws is read as "nothing there". Settings rows are fetched at most once
 * per page and call, which keeps one key press consistent with itself and avoids rebuilding the same list for
 * normalisation and for handling the key.
 */
private class SafeContext(private val ctx: NavContext) {
    val columns: Int = ctx.appsColumns.coerceAtLeast(1)
    val inputsCount: Int = ctx.inputsCount.coerceAtLeast(0)

    private val rowsByPage = EnumMap<SettingsPageId, List<SettingsRow>>(SettingsPageId::class.java)

    val homeSection: Section
        get() = HOME_POSITION_PRIORITY.firstOrNull(::available) ?: Section.TopBar

    fun count(section: Section): Int = when (section) {
        Section.TopBar -> ctx.topBarCount.coerceIn(0, TopBarItem.entries.size)
        Section.Hero -> ctx.heroCount.coerceAtLeast(0)
        Section.Dock -> ctx.dockCount.coerceAtLeast(0)
        Section.Continue -> ctx.continueCount.coerceAtLeast(0)
        Section.Apps -> ctx.appsCount.coerceAtLeast(0)
    }

    fun available(section: Section): Boolean = count(section) > 0

    fun appAt(section: Section, index: Int): AppKey? = try {
        ctx.appAt(section, index)
    } catch (_: RuntimeException) {
        null
    }

    fun appMenu(app: AppKey, from: Section): List<AppMenuItem> = try {
        ctx.appMenuFor(app, from)
    } catch (_: RuntimeException) {
        emptyList()
    }

    fun rows(page: SettingsPageId): List<SettingsRow> = rowsByPage.getOrPut(page) {
        try {
            ctx.settingsRows(page)
        } catch (_: RuntimeException) {
            emptyList()
        }
    }
}
