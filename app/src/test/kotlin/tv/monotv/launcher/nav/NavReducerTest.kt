package tv.monotv.launcher.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.monotv.launcher.core.AppKey

/**
 * One named test per navigation rule (sections A–F of the reducer contract).
 *
 * Default fixture: top bar 3 pills, hero 3, dock 5, continue 4, apps 12 in a 5-column grid
 * (rows 0..4 / 5..9 / 10..11), 3 inputs.
 */
class NavReducerTest {

    // =================================================================================================================
    // A. Section availability and the home position
    // =================================================================================================================

    @Test
    fun `sections are ordered top bar hero dock continue apps`() {
        assertEquals(
            listOf(Section.TopBar, Section.Hero, Section.Dock, Section.Continue, Section.Apps),
            sectionsTopToBottom(ctx(hero = 1, dock = 1, continueWatching = 1, apps = 1)),
        )
    }

    @Test
    fun `a section is available exactly when it has items`() {
        for (empty in Section.entries) {
            val context = ctx(
                topBar = if (empty == Section.TopBar) 0 else 1,
                hero = if (empty == Section.Hero) 0 else 1,
                dock = if (empty == Section.Dock) 0 else 1,
                continueWatching = if (empty == Section.Continue) 0 else 1,
                apps = if (empty == Section.Apps) 0 else 1,
            )
            assertEquals("without $empty", Section.entries - empty, sectionsTopToBottom(context))
        }
    }

    @Test
    fun `home position prefers dock then hero then apps then continue then top bar`() {
        assertEquals(Section.Dock, NavReducer.homeSection(ctx()))
        assertEquals(Section.Hero, NavReducer.homeSection(ctx(dock = 0)))
        assertEquals(Section.Apps, NavReducer.homeSection(ctx(dock = 0, hero = 0)))
        assertEquals(Section.Continue, NavReducer.homeSection(ctx(dock = 0, hero = 0, apps = 0)))
        assertEquals(Section.TopBar, NavReducer.homeSection(ctx(dock = 0, hero = 0, apps = 0, continueWatching = 0)))
    }

    @Test
    fun `home position is the top bar when the whole home screen is empty`() {
        val empty = ctx(dock = 0, hero = 0, apps = 0, continueWatching = 0, topBar = 0)
        assertEquals(Section.TopBar, NavReducer.homeSection(empty))
    }

    // =================================================================================================================
    // B. Home
    // =================================================================================================================

    @Test
    fun `down moves to the next section and up to the previous one`() {
        val start = home(Section.Dock)
        assertMoved(home(Section.Continue), press(start, NavKey.Down))
        assertMoved(home(Section.Hero), press(start, NavKey.Up))
    }

    @Test
    fun `vertical moves skip unavailable sections`() {
        val context = ctx(hero = 0, continueWatching = 0)
        assertMoved(home(Section.Apps), press(home(Section.Dock), NavKey.Down, context))
        assertMoved(home(Section.TopBar), press(home(Section.Dock), NavKey.Up, context))
    }

    @Test
    fun `entering a section restores its remembered index`() {
        val start = home(Section.Hero, top = 1, dock = 3)
        assertMoved(home(Section.Dock, top = 1, dock = 3), press(start, NavKey.Down))
        assertMoved(home(Section.TopBar, top = 1, dock = 3), press(start, NavKey.Up))
    }

    @Test
    fun `the remembered index is clamped when the section shrank meanwhile`() {
        val result = press(home(Section.Hero, dock = 4), NavKey.Down, ctx(dock = 2))
        assertMoved(home(Section.Dock, dock = 1), result)
    }

    @Test
    fun `up on the first available section bumps`() {
        val top = home(Section.TopBar)
        assertBump(NavKey.Up, top, press(top, NavKey.Up))

        val hero = home(Section.Hero)
        val noTopBar = ctx(topBar = 0)
        assertBump(NavKey.Up, NavReducer.normalize(hero, noTopBar), press(hero, NavKey.Up, noTopBar))
    }

    @Test
    fun `down on the last available section bumps`() {
        val start = home(Section.Continue)
        assertBump(NavKey.Down, start, press(start, NavKey.Down, ctx(apps = 0)))
    }

    @Test
    fun `down in apps moves one row down in the same column`() {
        assertMoved(home(Section.Apps, apps = 7), press(home(Section.Apps, apps = 2), NavKey.Down))
        assertMoved(home(Section.Apps, apps = 10), press(home(Section.Apps, apps = 5), NavKey.Down))
    }

    @Test
    fun `down in apps lands on the last item when the row below is shorter`() {
        assertMoved(home(Section.Apps, apps = 11), press(home(Section.Apps, apps = 7), NavKey.Down))
        assertMoved(home(Section.Apps, apps = 11), press(home(Section.Apps, apps = 9), NavKey.Down))
    }

    @Test
    fun `down in the last apps row bumps`() {
        val shortRow = home(Section.Apps, apps = 10)
        assertBump(NavKey.Down, shortRow, press(shortRow, NavKey.Down))

        val fullRow = home(Section.Apps, apps = 7)
        assertBump(NavKey.Down, fullRow, press(fullRow, NavKey.Down, ctx(apps = 10)))
    }

    @Test
    fun `up in apps moves one row up in the same column`() {
        assertMoved(home(Section.Apps, apps = 2), press(home(Section.Apps, apps = 7), NavKey.Up))
        assertMoved(home(Section.Apps, apps = 6), press(home(Section.Apps, apps = 11), NavKey.Up))
    }

    @Test
    fun `up in the first apps row leaves to the previous available section`() {
        val start = home(Section.Apps, apps = 3, cont = 2)
        assertMoved(home(Section.Continue, apps = 3, cont = 2), press(start, NavKey.Up))
        assertMoved(home(Section.Dock, apps = 3), press(home(Section.Apps, apps = 3), NavKey.Up, ctx(continueWatching = 0)))
    }

    @Test
    fun `up in the first apps row bumps when nothing is above`() {
        val start = home(Section.Apps, apps = 3)
        val onlyApps = ctx(topBar = 0, hero = 0, dock = 0, continueWatching = 0)
        // The reducer normalizes its input first, so indices of the now-empty sections are reset to 0.
        assertBump(NavKey.Up, NavReducer.normalize(start, onlyApps), press(start, NavKey.Up, onlyApps))
    }

    @Test
    fun `left and right move inside the current section`() {
        assertMoved(home(Section.TopBar, top = 0), press(home(Section.TopBar, top = 1), NavKey.Left))
        assertMoved(home(Section.TopBar, top = 2), press(home(Section.TopBar, top = 1), NavKey.Right))
        assertMoved(home(Section.Hero, hero = 0), press(home(Section.Hero, hero = 1), NavKey.Left))
        assertMoved(home(Section.Hero, hero = 2), press(home(Section.Hero, hero = 1), NavKey.Right))
        assertMoved(home(Section.Dock, dock = 2), press(home(Section.Dock, dock = 3), NavKey.Left))
        assertMoved(home(Section.Dock, dock = 4), press(home(Section.Dock, dock = 3), NavKey.Right))
        assertMoved(home(Section.Continue, cont = 0), press(home(Section.Continue, cont = 1), NavKey.Left))
        assertMoved(home(Section.Continue, cont = 2), press(home(Section.Continue, cont = 1), NavKey.Right))
        assertMoved(home(Section.Apps, apps = 5), press(home(Section.Apps, apps = 6), NavKey.Left))
        assertMoved(home(Section.Apps, apps = 7), press(home(Section.Apps, apps = 6), NavKey.Right))
    }

    @Test
    fun `left on the first item and right on the last item bump`() {
        val first = home(Section.Dock, dock = 0)
        assertBump(NavKey.Left, first, press(first, NavKey.Left))

        val last = home(Section.Dock, dock = 4)
        assertBump(NavKey.Right, last, press(last, NavKey.Right))

        val lastPill = home(Section.TopBar, top = 2)
        assertBump(NavKey.Right, lastPill, press(lastPill, NavKey.Right))
    }

    @Test
    fun `left on column zero of the apps grid bumps instead of wrapping`() {
        val start = home(Section.Apps, apps = 5)
        assertBump(NavKey.Left, start, press(start, NavKey.Left))
    }

    @Test
    fun `right on the last column of an apps row bumps instead of wrapping`() {
        for (index in listOf(4, 9)) {
            val start = home(Section.Apps, apps = index)
            assertBump(NavKey.Right, start, press(start, NavKey.Right))
        }
    }

    @Test
    fun `right on the last app bumps`() {
        val start = home(Section.Apps, apps = 11)
        assertBump(NavKey.Right, start, press(start, NavKey.Right))
    }

    @Test
    fun `ok on the network pill opens network settings`() {
        val start = home(Section.TopBar, top = TopBarItem.Network.ordinal)
        assertEffect(NavEffect.OpenNetworkSettings, start, press(start, NavKey.Ok))
    }

    @Test
    fun `ok on the inputs pill opens the inputs overlay on the first input`() {
        val start = home(Section.TopBar, top = TopBarItem.Inputs.ordinal)
        assertMoved(start.copy(overlays = listOf(Overlay.Inputs(0))), press(start, NavKey.Ok))
    }

    @Test
    fun `ok on the inputs pill bumps when there are no inputs`() {
        val start = home(Section.TopBar, top = TopBarItem.Inputs.ordinal)
        assertBump(NavKey.Ok, start, press(start, NavKey.Ok, ctx(inputs = 0)))
    }

    @Test
    fun `ok on the settings pill opens the settings root page`() {
        val start = home(Section.TopBar, top = TopBarItem.Settings.ordinal)
        val result = press(start, NavKey.Ok)
        assertMoved(start.copy(overlays = listOf(Overlay.Settings(listOf(SettingsFrame(SettingsPageId.Root, 0))))), result)
    }

    @Test
    fun `ok on hero opens the focused hero item`() {
        val start = home(Section.Hero, hero = 2)
        assertEffect(NavEffect.OpenHero(2), start, press(start, NavKey.Ok))
    }

    @Test
    fun `ok on continue opens the focused continue item`() {
        val start = home(Section.Continue, cont = 3)
        assertEffect(NavEffect.OpenContinue(3), start, press(start, NavKey.Ok))
    }

    @Test
    fun `ok on dock and apps launches the focused app`() {
        val dock = home(Section.Dock, dock = 2)
        assertEffect(NavEffect.LaunchApp(app(Section.Dock, 2)), dock, press(dock, NavKey.Ok))

        val apps = home(Section.Apps, apps = 8)
        assertEffect(NavEffect.LaunchApp(app(Section.Apps, 8)), apps, press(apps, NavKey.Ok))
    }

    @Test
    fun `ok bumps when there is no app under the focus`() {
        val start = home(Section.Dock, dock = 2)
        assertBump(NavKey.Ok, start, press(start, NavKey.Ok, ctx(appAt = { _, _ -> null })))
    }

    @Test
    fun `long ok on an app opens its context menu built for that section`() {
        val dock = home(Section.Dock, dock = 2)
        assertMoved(
            dock.copy(overlays = listOf(Overlay.AppMenu(app(Section.Dock, 2), DOCK_MENU, 0))),
            press(dock, NavKey.OkLong),
        )

        val apps = home(Section.Apps, apps = 8)
        assertMoved(
            apps.copy(overlays = listOf(Overlay.AppMenu(app(Section.Apps, 8), APPS_MENU, 0))),
            press(apps, NavKey.OkLong),
        )
    }

    @Test
    fun `long ok outside dock and apps behaves exactly like ok`() {
        val starts = TopBarItem.entries.map { home(Section.TopBar, top = it.ordinal) } +
            home(Section.Hero, hero = 1) +
            home(Section.Continue, cont = 2)
        for (start in starts) {
            assertEquals(press(start, NavKey.Ok), press(start, NavKey.OkLong))
        }
        val noInputs = ctx(inputs = 0)
        val inputsPill = home(Section.TopBar, top = TopBarItem.Inputs.ordinal)
        assertEquals(press(inputsPill, NavKey.Ok, noInputs), press(inputsPill, NavKey.OkLong, noInputs))
    }

    @Test
    fun `long ok without an app under the focus behaves like ok`() {
        val start = home(Section.Apps, apps = 4)
        assertBump(NavKey.Ok, start, press(start, NavKey.OkLong, ctx(appAt = { _, _ -> null })))
    }

    @Test
    fun `menu on an app opens the same context menu as long ok`() {
        for (start in listOf(home(Section.Dock, dock = 4), home(Section.Apps, apps = 11))) {
            val result = press(start, NavKey.Menu)
            assertTrue(result.state.topOverlay is Overlay.AppMenu)
            assertEquals(press(start, NavKey.OkLong), result)
        }
    }

    @Test
    fun `menu elsewhere opens settings`() {
        val opened = listOf(Overlay.Settings())
        for (start in listOf(home(Section.TopBar, top = 0), home(Section.Hero, hero = 1), home(Section.Continue))) {
            assertMoved(start.copy(overlays = opened), press(start, NavKey.Menu))
        }
        val dock = home(Section.Dock)
        assertMoved(dock.copy(overlays = opened), press(dock, NavKey.Menu, ctx(appAt = { _, _ -> null })))
    }

    @Test
    fun `back jumps to the home position section keeping every index`() {
        val start = home(Section.Apps, top = 1, hero = 2, dock = 3, cont = 1, apps = 7)
        assertMoved(home(Section.Dock, top = 1, hero = 2, dock = 3, cont = 1, apps = 7), press(start, NavKey.Back))

        val noDock = ctx(dock = 0)
        assertMoved(home(Section.Hero, hero = 2, apps = 7), press(home(Section.Apps, hero = 2, apps = 7), NavKey.Back, noDock))
    }

    @Test
    fun `back on the dock returns to its first item`() {
        assertMoved(home(Section.Dock, dock = 0), press(home(Section.Dock, dock = 3), NavKey.Back))
    }

    @Test
    fun `back on the first dock item asks to exit`() {
        val start = home(Section.Dock, dock = 0)
        assertEffect(NavEffect.ExitApp, start, press(start, NavKey.Back))
    }

    @Test
    fun `back on a home position other than the dock asks to exit without resetting its index`() {
        val start = home(Section.Hero, hero = 2)
        assertEffect(NavEffect.ExitApp, start, press(start, NavKey.Back, ctx(dock = 0)))
    }

    // =================================================================================================================
    // C. Reorder mode
    // =================================================================================================================

    @Test
    fun `reorder left and right carry the item to the neighbouring position`() {
        val start = reordering(Section.Dock, 2)

        val right = press(start, NavKey.Right)
        assertEquals(reordering(Section.Dock, 3), right.state)
        assertEquals(listOf(NavEffect.MoveItem(Section.Dock, from = 2, to = 3)), right.effects)

        val left = press(start, NavKey.Left)
        assertEquals(reordering(Section.Dock, 1), left.state)
        assertEquals(listOf(NavEffect.MoveItem(Section.Dock, from = 2, to = 1)), left.effects)
    }

    @Test
    fun `reorder left and right cross rows in the apps grid`() {
        val right = press(reordering(Section.Apps, 4), NavKey.Right)
        assertEquals(reordering(Section.Apps, 5), right.state)
        assertEquals(listOf(NavEffect.MoveItem(Section.Apps, from = 4, to = 5)), right.effects)

        val left = press(reordering(Section.Apps, 5), NavKey.Left)
        assertEquals(reordering(Section.Apps, 4), left.state)
        assertEquals(listOf(NavEffect.MoveItem(Section.Apps, from = 5, to = 4)), left.effects)
    }

    @Test
    fun `reorder up and down carry an app by one grid row`() {
        val up = press(reordering(Section.Apps, 7), NavKey.Up)
        assertEquals(reordering(Section.Apps, 2), up.state)
        assertEquals(listOf(NavEffect.MoveItem(Section.Apps, from = 7, to = 2)), up.effects)

        val down = press(reordering(Section.Apps, 2), NavKey.Down)
        assertEquals(reordering(Section.Apps, 7), down.state)
        assertEquals(listOf(NavEffect.MoveItem(Section.Apps, from = 2, to = 7)), down.effects)
    }

    @Test
    fun `reorder up and down bump in the dock`() {
        val start = reordering(Section.Dock, 2)
        assertBump(NavKey.Up, start, press(start, NavKey.Up))
        assertBump(NavKey.Down, start, press(start, NavKey.Down))
    }

    @Test
    fun `reorder bumps when the target position does not exist`() {
        val cases = listOf(
            reordering(Section.Dock, 0) to NavKey.Left,
            reordering(Section.Dock, 4) to NavKey.Right,
            reordering(Section.Apps, 0) to NavKey.Left,
            reordering(Section.Apps, 11) to NavKey.Right,
            reordering(Section.Apps, 2) to NavKey.Up,
            // Index 12 does not exist; unlike plain navigation there is no "land on the last item" shortcut.
            reordering(Section.Apps, 7) to NavKey.Down,
        )
        for ((start, key) in cases) assertBump(key, start, press(start, key))
    }

    @Test
    fun `ok back and menu leave reorder mode without effects`() {
        for (key in listOf(NavKey.Ok, NavKey.Back, NavKey.Menu)) {
            assertMoved(home(Section.Apps, apps = 6), press(reordering(Section.Apps, 6), key))
            assertMoved(home(Section.Dock, dock = 3), press(reordering(Section.Dock, 3), key))
        }
    }

    @Test
    fun `long ok is ignored in reorder mode`() {
        val start = reordering(Section.Dock, 3)
        assertMoved(start, press(start, NavKey.OkLong))
    }

    // =================================================================================================================
    // D. Overlays
    // =================================================================================================================

    @Test
    fun `up and down move inside the inputs overlay and bump at its edges`() {
        assertMoved(overlay(Overlay.Inputs(2)), press(overlay(Overlay.Inputs(1)), NavKey.Down))
        assertMoved(overlay(Overlay.Inputs(0)), press(overlay(Overlay.Inputs(1)), NavKey.Up))

        val first = overlay(Overlay.Inputs(0))
        assertBump(NavKey.Up, first, press(first, NavKey.Up))
        val last = overlay(Overlay.Inputs(2))
        assertBump(NavKey.Down, last, press(last, NavKey.Down))
    }

    @Test
    fun `ok in the inputs overlay switches to the input and closes`() {
        assertEffect(NavEffect.SwitchInput(1), BASE, press(overlay(Overlay.Inputs(1)), NavKey.Ok))
    }

    @Test
    fun `back menu and left close the inputs overlay`() {
        for (key in listOf(NavKey.Back, NavKey.Menu, NavKey.Left)) {
            assertMoved(BASE, press(overlay(Overlay.Inputs(1)), key))
        }
    }

    @Test
    fun `right and long ok do nothing in the inputs overlay`() {
        val start = overlay(Overlay.Inputs(1))
        assertMoved(start, press(start, NavKey.Right))
        assertMoved(start, press(start, NavKey.OkLong))
    }

    @Test
    fun `up and down reach every settings row and bump at the edges`() {
        val rows = PAGES.getValue(SettingsPageId.Root)
        var state = settings(SettingsFrame(SettingsPageId.Root, 0))
        assertBump(NavKey.Up, state, press(state, NavKey.Up))
        for (index in 1..rows.lastIndex) {
            // Info rows and disabled rows are focusable like any other.
            state = press(state, NavKey.Down).also { assertTrue(it.effects.isEmpty()) }.state
            assertEquals(settings(SettingsFrame(SettingsPageId.Root, index)), state)
        }
        assertBump(NavKey.Down, state, press(state, NavKey.Down))
        assertMoved(settings(SettingsFrame(SettingsPageId.Root, rows.lastIndex - 1)), press(state, NavKey.Up))
    }

    @Test
    fun `ok on a disabled settings row bumps`() {
        for (index in listOf(ROW_DISABLED_ACTION, ROW_DISABLED_NAVIGATE)) {
            val start = settings(SettingsFrame(SettingsPageId.Root, index))
            assertBump(NavKey.Ok, start, press(start, NavKey.Ok))
        }
    }

    @Test
    fun `ok on a navigate row pushes the page focused on its checked row`() {
        val root = SettingsFrame(SettingsPageId.Root, ROW_TO_THEME)
        assertMoved(settings(root, SettingsFrame(SettingsPageId.Theme, 1)), press(settings(root), NavKey.Ok))
    }

    @Test
    fun `ok on a navigate row starts on the first row when nothing is checked`() {
        val root = SettingsFrame(SettingsPageId.Root, ROW_TO_ACCENT)
        assertMoved(settings(root, SettingsFrame(SettingsPageId.Accent, 0)), press(settings(root), NavKey.Ok))
    }

    @Test
    fun `ok on a navigate row bumps when the target page has no rows`() {
        val start = settings(SettingsFrame(SettingsPageId.Root, ROW_TO_EMPTY_PAGE))
        assertBump(NavKey.Ok, start, press(start, NavKey.Ok))
    }

    @Test
    fun `ok on an action row emits the settings action of its page`() {
        val root = settings(SettingsFrame(SettingsPageId.Root, ROW_ACTION))
        assertEffect(NavEffect.SettingsAction(SettingsPageId.Root, "demo"), root, press(root, NavKey.Ok))

        val theme = settings(SettingsFrame(SettingsPageId.Root, ROW_TO_THEME), SettingsFrame(SettingsPageId.Theme, 0))
        assertEffect(NavEffect.SettingsAction(SettingsPageId.Theme, "theme:Black"), theme, press(theme, NavKey.Ok))
    }

    @Test
    fun `ok on an info row does nothing`() {
        val start = settings(SettingsFrame(SettingsPageId.Root, ROW_INFO))
        assertMoved(start, press(start, NavKey.Ok))
    }

    @Test
    fun `ok on a reorderable row grabs it`() {
        val start = settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 1))
        assertMoved(settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 1, grabbed = true)), press(start, NavKey.Ok))
    }

    @Test
    fun `a grabbed row moves with up and down`() {
        val start = settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 1, grabbed = true))

        val up = press(start, NavKey.Up)
        assertEquals(settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 0, grabbed = true)), up.state)
        assertEquals(listOf(NavEffect.SettingsMove(SettingsPageId.DockOrder, from = 1, to = 0)), up.effects)

        val down = press(start, NavKey.Down)
        assertEquals(settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 2, grabbed = true)), down.state)
        assertEquals(listOf(NavEffect.SettingsMove(SettingsPageId.DockOrder, from = 1, to = 2)), down.effects)
    }

    @Test
    fun `a grabbed row bumps at the ends of the list`() {
        val first = settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 0, grabbed = true))
        assertBump(NavKey.Up, first, press(first, NavKey.Up))

        val last = settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 2, grabbed = true))
        assertBump(NavKey.Down, last, press(last, NavKey.Down))
    }

    @Test
    fun `ok and back drop a grabbed row where it is`() {
        val start = settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 2, grabbed = true))
        for (key in listOf(NavKey.Ok, NavKey.Back)) {
            assertMoved(settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 2)), press(start, key))
        }
    }

    @Test
    fun `other keys are ignored while a row is grabbed`() {
        val start = settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 1, grabbed = true))
        for (key in listOf(NavKey.Left, NavKey.Right, NavKey.Menu, NavKey.OkLong)) {
            assertMoved(start, press(start, key))
        }
    }

    @Test
    fun `right follows navigate rows like ok`() {
        val toTheme = settings(SettingsFrame(SettingsPageId.Root, ROW_TO_THEME))
        assertEquals(press(toTheme, NavKey.Ok), press(toTheme, NavKey.Right))
        assertEquals(SettingsPageId.Theme, (press(toTheme, NavKey.Right).state.topOverlay as Overlay.Settings).stack.last().page)

        val disabled = settings(SettingsFrame(SettingsPageId.Root, ROW_DISABLED_NAVIGATE))
        assertBump(NavKey.Right, disabled, press(disabled, NavKey.Right))
    }

    @Test
    fun `right does nothing on rows that do not navigate`() {
        val starts = listOf(
            settings(SettingsFrame(SettingsPageId.Root, ROW_ACTION)),
            settings(SettingsFrame(SettingsPageId.Root, ROW_INFO)),
            settings(SettingsFrame(SettingsPageId.Root, ROW_DISABLED_ACTION)),
            settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 1)),
        )
        for (start in starts) assertMoved(start, press(start, NavKey.Right))
    }

    @Test
    fun `back and left pop the top settings frame`() {
        val root = SettingsFrame(SettingsPageId.Root, ROW_TO_THEME)
        for (key in listOf(NavKey.Back, NavKey.Left)) {
            assertMoved(settings(root), press(settings(root, SettingsFrame(SettingsPageId.Theme, 1)), key))
        }
    }

    @Test
    fun `back and left on the last settings frame close the overlay`() {
        for (key in listOf(NavKey.Back, NavKey.Left)) {
            assertMoved(BASE, press(settings(SettingsFrame(SettingsPageId.Root, 2)), key))
        }
    }

    @Test
    fun `menu closes the whole settings overlay`() {
        val deep = settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 1))
        assertMoved(BASE, press(deep, NavKey.Menu))
    }

    @Test
    fun `long ok does nothing in settings`() {
        val start = settings(SettingsFrame(SettingsPageId.Root, ROW_ACTION))
        assertMoved(start, press(start, NavKey.OkLong))
    }

    @Test
    fun `up and down move inside the app menu and bump at its edges`() {
        assertMoved(overlay(appMenu(index = 2)), press(overlay(appMenu(index = 1)), NavKey.Down))
        assertMoved(overlay(appMenu(index = 0)), press(overlay(appMenu(index = 1)), NavKey.Up))

        val first = overlay(appMenu(index = 0))
        assertBump(NavKey.Up, first, press(first, NavKey.Up))
        val last = overlay(appMenu(index = APPS_MENU.lastIndex))
        assertBump(NavKey.Down, last, press(last, NavKey.Down))
    }

    @Test
    fun `choosing move closes the menu and enters reorder mode on the focused section`() {
        val moveIndex = APPS_MENU.indexOf(AppMenuItem.Move)
        for (base in listOf(home(Section.Apps, apps = 6), home(Section.Dock, dock = 2))) {
            val result = press(overlay(appMenu(index = moveIndex), base = base), NavKey.Ok)
            assertMoved(base.copy(reorder = ReorderState(base.home.section)), result)
        }
    }

    @Test
    fun `choosing move outside dock and apps only closes the menu`() {
        val base = home(Section.Hero, hero = 1)
        val result = press(overlay(appMenu(index = APPS_MENU.indexOf(AppMenuItem.Move)), base = base), NavKey.Ok)
        assertMoved(base, result)
    }

    @Test
    fun `choosing move only closes the menu when nothing is left to reorder`() {
        val base = home(Section.Dock)
        val result = press(overlay(appMenu(index = APPS_MENU.indexOf(AppMenuItem.Move)), base = base), NavKey.Ok, ctx(dock = 1))
        assertMoved(base, result)
    }

    @Test
    fun `choosing any other item emits the app menu action and closes the menu`() {
        val base = home(Section.Apps, apps = 6)
        for ((index, item) in APPS_MENU.withIndex()) {
            if (item == AppMenuItem.Move) continue
            val result = press(overlay(appMenu(index = index), base = base), NavKey.Ok)
            assertEffect(NavEffect.AppMenuAction(MENU_APP, item), base, result)
        }
    }

    @Test
    fun `back menu and left close the app menu`() {
        for (key in listOf(NavKey.Back, NavKey.Menu, NavKey.Left)) {
            assertMoved(BASE, press(overlay(appMenu(index = 2)), key))
        }
    }

    @Test
    fun `right and long ok do nothing in the app menu`() {
        val start = overlay(appMenu(index = 1))
        assertMoved(start, press(start, NavKey.Right))
        assertMoved(start, press(start, NavKey.OkLong))
    }

    @Test
    fun `left and right select the confirm buttons and bump when already there`() {
        val cancel = overlay(Overlay.Confirm(ConfirmId.ResetDock, 0))
        val confirm = overlay(Overlay.Confirm(ConfirmId.ResetDock, 1))
        assertMoved(confirm, press(cancel, NavKey.Right))
        assertMoved(cancel, press(confirm, NavKey.Left))
        assertBump(NavKey.Left, cancel, press(cancel, NavKey.Left))
        assertBump(NavKey.Right, confirm, press(confirm, NavKey.Right))
    }

    @Test
    fun `up down menu and long ok do nothing in a confirmation`() {
        for (index in 0..1) {
            val start = overlay(Overlay.Confirm(ConfirmId.EnableHome, index))
            for (key in listOf(NavKey.Up, NavKey.Down, NavKey.Menu, NavKey.OkLong)) {
                assertMoved(start, press(start, key))
            }
        }
    }

    @Test
    fun `ok on the confirm button emits confirmed and closes`() {
        for (id in ConfirmId.entries) {
            assertEffect(NavEffect.Confirmed(id), BASE, press(overlay(Overlay.Confirm(id, 1)), NavKey.Ok))
        }
    }

    @Test
    fun `ok on the cancel button only closes`() {
        assertMoved(BASE, press(overlay(Overlay.Confirm(ConfirmId.ResetApps, 0)), NavKey.Ok))
    }

    @Test
    fun `back closes a confirmation without confirming`() {
        assertMoved(BASE, press(overlay(Overlay.Confirm(ConfirmId.ResetApps, 1)), NavKey.Back))
    }

    @Test
    fun `only the top overlay receives keys`() {
        val below = Overlay.Settings(listOf(SettingsFrame(SettingsPageId.Root, ROW_ACTION)))
        val start = overlay(below, Overlay.Confirm(ConfirmId.ResetDock, 0))

        val right = press(start, NavKey.Right)
        assertMoved(overlay(below, Overlay.Confirm(ConfirmId.ResetDock, 1)), right)

        // DOWN would move the settings focus if the key leaked through the modal.
        assertMoved(start, press(start, NavKey.Down))

        assertEffect(NavEffect.Confirmed(ConfirmId.ResetDock), overlay(below), press(right.state, NavKey.Ok))
    }

    @Test
    fun `an overlay takes keys before reorder mode and home`() {
        val base = reordering(Section.Dock, 2)
        val start = overlay(Overlay.Inputs(1), base = base)
        assertMoved(overlay(Overlay.Inputs(2), base = base), press(start, NavKey.Down))
        // LEFT closes the overlay instead of carrying the picked-up dock item.
        assertMoved(base, press(start, NavKey.Left))
    }

    // =================================================================================================================
    // E. normalize
    // =================================================================================================================

    @Test
    fun `normalize clamps every section index into range`() {
        val stale = NavState(HomeNav(Section.Dock, topBarIndex = 9, heroIndex = -2, dockIndex = 5, continueIndex = 99, appsIndex = 12))
        assertEquals(home(Section.Dock, top = 2, hero = 0, dock = 4, cont = 3, apps = 11), NavReducer.normalize(stale, ctx()))
    }

    @Test
    fun `normalize resets the index of an empty section to zero`() {
        val stale = home(Section.Dock, top = 2, hero = 2, dock = 1, cont = 3, apps = 7)
        val context = ctx(topBar = 0, hero = 0, continueWatching = 0, apps = 0)
        assertEquals(home(Section.Dock, top = 0, hero = 0, dock = 1, cont = 0, apps = 0), NavReducer.normalize(stale, context))
    }

    @Test
    fun `normalize moves the focus to the home position when its section became unavailable`() {
        assertEquals(home(Section.Dock, dock = 2), NavReducer.normalize(home(Section.Continue, dock = 2), ctx(continueWatching = 0)))
        assertEquals(home(Section.Hero, hero = 1), NavReducer.normalize(home(Section.Dock, hero = 1), ctx(dock = 0)))
        assertEquals(
            home(Section.TopBar, top = 0),
            NavReducer.normalize(home(Section.Apps), ctx(topBar = 0, hero = 0, dock = 0, continueWatching = 0, apps = 0)),
        )
    }

    @Test
    fun `normalize keeps the focus on a section that is still available`() {
        val state = home(Section.Continue, cont = 1)
        assertSame(state, NavReducer.normalize(state, ctx(dock = 0)))
    }

    @Test
    fun `normalize drops reorder when its section has fewer than two items`() {
        assertNull(NavReducer.normalize(reordering(Section.Dock, 0), ctx(dock = 1)).reorder)
        assertNull(NavReducer.normalize(reordering(Section.Apps, 3), ctx(apps = 0)).reorder)
        assertEquals(reordering(Section.Dock, 1), NavReducer.normalize(reordering(Section.Dock, 4), ctx(dock = 2)))
    }

    @Test
    fun `normalize drops reorder that is not on the focused dock or apps section`() {
        assertNull(NavReducer.normalize(home(Section.Hero).copy(reorder = ReorderState(Section.Hero)), ctx()).reorder)
        assertNull(NavReducer.normalize(home(Section.Dock).copy(reorder = ReorderState(Section.Apps)), ctx()).reorder)
    }

    @Test
    fun `normalize closes the inputs overlay when no inputs are left and clamps it otherwise`() {
        assertEquals(BASE, NavReducer.normalize(overlay(Overlay.Inputs(1)), ctx(inputs = 0)))
        assertEquals(overlay(Overlay.Inputs(1)), NavReducer.normalize(overlay(Overlay.Inputs(2)), ctx(inputs = 2)))
        assertEquals(overlay(Overlay.Inputs(0)), NavReducer.normalize(overlay(Overlay.Inputs(-4)), ctx()))
    }

    @Test
    fun `normalize clamps settings frames to their last row`() {
        val stale = settings(SettingsFrame(SettingsPageId.Root, 50), SettingsFrame(SettingsPageId.Theme, -1), SettingsFrame(SettingsPageId.DockOrder, 3))
        val expected = settings(
            SettingsFrame(SettingsPageId.Root, PAGES.getValue(SettingsPageId.Root).lastIndex),
            SettingsFrame(SettingsPageId.Theme, 0),
            SettingsFrame(SettingsPageId.DockOrder, 2),
        )
        assertEquals(expected, NavReducer.normalize(stale, ctx()))
    }

    @Test
    fun `normalize pops settings frames whose page has no rows`() {
        val root = SettingsFrame(SettingsPageId.Root, ROW_TO_EMPTY_PAGE)
        assertEquals(settings(root), NavReducer.normalize(settings(root, SettingsFrame(SettingsPageId.AppsOrder, 0)), ctx()))

        val theme = SettingsFrame(SettingsPageId.Theme, 1)
        assertEquals(settings(root, theme), NavReducer.normalize(settings(root, SettingsFrame(SettingsPageId.AppsOrder, 2), theme), ctx()))
    }

    @Test
    fun `normalize closes settings when no frame is left`() {
        assertEquals(BASE, NavReducer.normalize(settings(SettingsFrame(SettingsPageId.AppsOrder, 0)), ctx()))
        assertEquals(BASE, NavReducer.normalize(overlay(Overlay.Settings(emptyList())), ctx()))
        assertEquals(BASE, NavReducer.normalize(settings(SettingsFrame(SettingsPageId.Root, 1)), ctx(rows = { emptyList() })))
    }

    @Test
    fun `normalize clears grabbed when the focused row is not reorderable`() {
        val onAction = settings(SettingsFrame(SettingsPageId.Root, ROW_ACTION, grabbed = true))
        assertEquals(settings(SettingsFrame(SettingsPageId.Root, ROW_ACTION)), NavReducer.normalize(onAction, ctx()))

        val stillReorderable = settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 9, grabbed = true))
        assertEquals(
            settings(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 2, grabbed = true)),
            NavReducer.normalize(stillReorderable, ctx()),
        )
    }

    @Test
    fun `normalize clears grabbed on settings frames below the visible page`() {
        val buried = settings(SettingsFrame(SettingsPageId.DockOrder, 1, grabbed = true), SettingsFrame(SettingsPageId.Theme, 0))
        assertEquals(
            settings(SettingsFrame(SettingsPageId.DockOrder, 1), SettingsFrame(SettingsPageId.Theme, 0)),
            NavReducer.normalize(buried, ctx()),
        )
    }

    @Test
    fun `normalize clamps the app menu index and closes a menu without items`() {
        assertEquals(overlay(appMenu(index = APPS_MENU.lastIndex)), NavReducer.normalize(overlay(appMenu(index = 40)), ctx()))
        assertEquals(overlay(appMenu(index = 0)), NavReducer.normalize(overlay(appMenu(index = -1)), ctx()))
        assertEquals(BASE, NavReducer.normalize(overlay(Overlay.AppMenu(MENU_APP, emptyList(), 0)), ctx()))
    }

    @Test
    fun `normalize clamps the confirm index to its two buttons`() {
        assertEquals(overlay(Overlay.Confirm(ConfirmId.ResetDock, 1)), NavReducer.normalize(overlay(Overlay.Confirm(ConfirmId.ResetDock, 5)), ctx()))
        assertEquals(overlay(Overlay.Confirm(ConfirmId.ResetDock, 0)), NavReducer.normalize(overlay(Overlay.Confirm(ConfirmId.ResetDock, -3)), ctx()))
    }

    @Test
    fun `normalize validates overlays below the top one too`() {
        val stale = overlay(Overlay.Inputs(7), Overlay.Settings(listOf(SettingsFrame(SettingsPageId.Theme, 9))), Overlay.Confirm(ConfirmId.ResetApps, 1))
        val expected = overlay(Overlay.Inputs(2), Overlay.Settings(listOf(SettingsFrame(SettingsPageId.Theme, 1))), Overlay.Confirm(ConfirmId.ResetApps, 1))
        assertEquals(expected, NavReducer.normalize(stale, ctx()))
        assertEquals(overlay(expected.overlays[1], expected.overlays[2]), NavReducer.normalize(stale, ctx(inputs = 0)))
    }

    @Test
    fun `normalize returns the same instance for a valid state`() {
        val valid = overlay(
            Overlay.Settings(listOf(ROOT_AT_ORDER, SettingsFrame(SettingsPageId.DockOrder, 1, grabbed = true))),
            base = home(Section.Apps, top = 1, hero = 2, dock = 4, cont = 3, apps = 11).copy(reorder = ReorderState(Section.Apps)),
        )
        assertSame(valid, NavReducer.normalize(valid, ctx()))
    }

    // =================================================================================================================
    // F. Robustness
    // =================================================================================================================

    @Test
    fun `reduce normalizes stale indices before handling the key`() {
        // Index 40 of 12 apps is clamped to 11 first, so LEFT lands on 10.
        assertMoved(home(Section.Apps, apps = 10), press(home(Section.Apps, apps = 40), NavKey.Left))
    }

    @Test
    fun `reduce acts on the home position when the focused section vanished`() {
        val result = press(home(Section.Continue, dock = 1), NavKey.Ok, ctx(continueWatching = 0))
        assertEffect(NavEffect.LaunchApp(app(Section.Dock, 1)), home(Section.Dock, dock = 1), result)
    }

    @Test
    fun `reduce returns the normalized state even when the key does nothing`() {
        val stale = overlay(Overlay.Inputs(9))
        assertMoved(overlay(Overlay.Inputs(2)), press(stale, NavKey.Right))
    }

    @Test
    fun `reduce never throws for hostile states and contexts`() {
        val failing = IllegalStateException("context callback failed")
        val contexts = listOf(
            ctx(),
            ctx(topBar = -2, hero = -1, dock = -5, continueWatching = Int.MIN_VALUE, apps = -3, columns = 0, inputs = -1),
            ctx(columns = -4),
            ctx(columns = Int.MAX_VALUE),
            ctx(topBar = Int.MAX_VALUE, hero = Int.MAX_VALUE, dock = Int.MAX_VALUE, continueWatching = Int.MAX_VALUE, apps = Int.MAX_VALUE, columns = Int.MAX_VALUE, inputs = Int.MAX_VALUE),
            ctx(appAt = { _, _ -> throw failing }, menu = { _, _ -> throw failing }, rows = { throw failing }),
            ctx(appAt = { _, _ -> null }, menu = { _, _ -> emptyList() }, rows = { emptyList() }),
        )
        val states = listOf(
            NavState(),
            NavState(HomeNav(Section.Apps, Int.MAX_VALUE, Int.MIN_VALUE, -1, Int.MAX_VALUE, Int.MIN_VALUE)),
            NavState(HomeNav(Section.Apps, appsIndex = Int.MAX_VALUE), reorder = ReorderState(Section.Apps)),
            NavState(HomeNav(Section.Dock, dockIndex = Int.MAX_VALUE), reorder = ReorderState(Section.Dock)),
            NavState(reorder = ReorderState(Section.TopBar)),
            NavState(overlays = listOf(Overlay.Settings(emptyList()))),
            NavState(overlays = listOf(Overlay.Settings(listOf(SettingsFrame(SettingsPageId.System, Int.MAX_VALUE, grabbed = true))))),
            NavState(overlays = listOf(Overlay.Settings(listOf(SettingsFrame(SettingsPageId.DockOrder, Int.MIN_VALUE, grabbed = true))))),
            NavState(overlays = listOf(Overlay.AppMenu(MENU_APP, emptyList(), 7))),
            NavState(overlays = listOf(Overlay.AppMenu(MENU_APP, listOf(AppMenuItem.Move), -3))),
            NavState(overlays = listOf(Overlay.Inputs(Int.MIN_VALUE), Overlay.Confirm(ConfirmId.ResetApps, Int.MAX_VALUE))),
            NavState(overlays = listOf(Overlay.Inputs(Int.MAX_VALUE))),
        )
        for (context in contexts) for (state in states) for (key in NavKey.entries) {
            val result = NavReducer.reduce(state, key, context)
            assertEquals(result, NavReducer.reduce(state, key, context))
            assertSame("output must already be normalized", result.state, NavReducer.normalize(result.state, context))
        }
    }

    @Test
    fun `a failing context callback is treated as missing data`() {
        val failing = IllegalStateException("context callback failed")
        val dock = home(Section.Dock, dock = 1)
        assertBump(NavKey.Ok, dock, press(dock, NavKey.Ok, ctx(appAt = { _, _ -> throw failing })))

        // No menu can be built, so the long press falls back to a plain OK.
        val launch = press(dock, NavKey.OkLong, ctx(menu = { _, _ -> throw failing }))
        assertEffect(NavEffect.LaunchApp(app(Section.Dock, 1)), dock, launch)

        val pill = home(Section.TopBar, top = TopBarItem.Settings.ordinal)
        assertBump(NavKey.Ok, pill, press(pill, NavKey.Ok, ctx(rows = { throw failing })))
        assertBump(NavKey.Menu, pill, press(pill, NavKey.Menu, ctx(rows = { throw failing })))
    }

    @Test
    fun `an empty home screen has nothing to open but still reaches settings`() {
        val context = ctx(topBar = 0, hero = 0, dock = 0, continueWatching = 0, apps = 0)
        val start = NavReducer.normalize(home(Section.Dock), context)
        assertEquals(home(Section.TopBar, top = 0), start)
        for (key in listOf(NavKey.Up, NavKey.Down, NavKey.Left, NavKey.Right)) {
            assertBump(key, start, press(start, key, context))
        }
        // The top bar has no pill: OK must not open the network settings of a pill that is not there.
        assertBump(NavKey.Ok, start, press(start, NavKey.Ok, context))
        assertBump(NavKey.Ok, start, press(start, NavKey.OkLong, context))
        assertEffect(NavEffect.ExitApp, start, press(start, NavKey.Back, context))
        assertMoved(start.copy(overlays = listOf(Overlay.Settings())), press(start, NavKey.Menu, context))
    }

    @Test
    fun `the top bar never offers more pills than exist`() {
        val start = home(Section.TopBar, top = 7)
        val context = ctx(topBar = 10)
        val clamped = home(Section.TopBar, top = TopBarItem.entries.lastIndex)
        assertEquals(clamped, NavReducer.normalize(start, context))
        assertBump(NavKey.Right, clamped, press(start, NavKey.Right, context))
    }

    @Test
    fun `an absurd grid width cannot overflow into another item`() {
        val context = ctx(columns = Int.MAX_VALUE)
        val start = home(Section.Apps, apps = 3)
        assertBump(NavKey.Down, start, press(start, NavKey.Down, context))

        val carrying = reordering(Section.Apps, 3)
        assertBump(NavKey.Down, carrying, press(carrying, NavKey.Down, context))
        assertBump(NavKey.Up, carrying, press(carrying, NavKey.Up, context))
    }

    @Test
    fun `a grid with a non positive column count behaves as a single column`() {
        val context = ctx(columns = 0)
        assertMoved(home(Section.Apps, apps = 4), press(home(Section.Apps, apps = 3), NavKey.Down, context))
        assertMoved(home(Section.Apps, apps = 2), press(home(Section.Apps, apps = 3), NavKey.Up, context))
        val start = home(Section.Apps, apps = 3)
        assertBump(NavKey.Left, start, press(start, NavKey.Left, context))
        assertBump(NavKey.Right, start, press(start, NavKey.Right, context))
    }

    // =================================================================================================================
    // openOverlay / closeTopOverlay
    // =================================================================================================================

    @Test
    fun `open overlay pushes on top of the stack`() {
        val panel = Overlay.Settings()
        val confirm = Overlay.Confirm(ConfirmId.ResetDock)
        val opened = NavReducer.openOverlay(NavReducer.openOverlay(BASE, panel), confirm)
        assertEquals(BASE.copy(overlays = listOf(panel, confirm)), opened)
        assertEquals(confirm, opened.topOverlay)
    }

    @Test
    fun `close top overlay pops only the top overlay and tolerates an empty stack`() {
        val panel = Overlay.Settings()
        val both = overlay(panel, Overlay.Confirm(ConfirmId.ResetDock))
        assertEquals(overlay(panel), NavReducer.closeTopOverlay(both))
        assertEquals(BASE, NavReducer.closeTopOverlay(overlay(panel)))
        assertSame(BASE, NavReducer.closeTopOverlay(BASE))
    }

    // =================================================================================================================
    // Fixture
    // =================================================================================================================

    private fun press(state: NavState, key: NavKey, context: NavContext = ctx()): NavResult = NavReducer.reduce(state, key, context)

    /** The key changed (or deliberately kept) the state and requested nothing else. */
    private fun assertMoved(expected: NavState, result: NavResult) {
        assertEquals(expected, result.state)
        assertEquals(emptyList<NavEffect>(), result.effects)
    }

    private fun assertBump(key: NavKey, unchanged: NavState, result: NavResult) =
        assertEffect(NavEffect.Bump(key), unchanged, result)

    private fun assertEffect(effect: NavEffect, expected: NavState, result: NavResult) {
        assertEquals(expected, result.state)
        assertEquals(listOf(effect), result.effects)
    }

    /** Sections visited when walking DOWN from the top-most reachable one (every section must have one row). */
    private fun sectionsTopToBottom(context: NavContext): List<Section> {
        var state = NavReducer.normalize(NavState(), context)
        repeat(Section.entries.size) { state = NavReducer.reduce(state, NavKey.Up, context).state }
        val visited = mutableListOf(state.home.section)
        repeat(Section.entries.size) {
            state = NavReducer.reduce(state, NavKey.Down, context).state
            if (state.home.section != visited.last()) visited += state.home.section
        }
        return visited
    }

    private companion object {
        val MENU_APP = AppKey("test.menu", "Main")
        val DOCK_MENU = listOf(AppMenuItem.Open, AppMenuItem.Move, AppMenuItem.RemoveFromDock, AppMenuItem.Info)
        val APPS_MENU = listOf(AppMenuItem.Open, AppMenuItem.Move, AppMenuItem.AddToDock, AppMenuItem.Hide, AppMenuItem.Info)

        const val ROW_TO_THEME = 0
        const val ROW_TO_ACCENT = 1
        const val ROW_ACTION = 2
        const val ROW_INFO = 3
        const val ROW_TO_ORDER = 4
        const val ROW_DISABLED_ACTION = 5
        const val ROW_DISABLED_NAVIGATE = 6
        const val ROW_TO_EMPTY_PAGE = 7

        /** A small hand-made catalog covering every row kind; AppsOrder deliberately has no rows. */
        val PAGES: Map<SettingsPageId, List<SettingsRow>> = mapOf(
            SettingsPageId.Root to listOf(
                SettingsRow("theme", "Theme", kind = RowKind.Navigate(SettingsPageId.Theme)),
                SettingsRow("accent", "Accent", kind = RowKind.Navigate(SettingsPageId.Accent)),
                SettingsRow("demo", "Demo", kind = RowKind.Action),
                SettingsRow("model", "Model", kind = RowKind.Info),
                SettingsRow("order", "Order", kind = RowKind.Navigate(SettingsPageId.DockOrder)),
                SettingsRow("locked", "Locked", kind = RowKind.Action, enabled = false),
                SettingsRow("locked_page", "Locked page", kind = RowKind.Navigate(SettingsPageId.Apps), enabled = false),
                SettingsRow("empty", "Empty", kind = RowKind.Navigate(SettingsPageId.AppsOrder)),
            ),
            SettingsPageId.Theme to listOf(
                SettingsRow("theme:Black", "Black", kind = RowKind.Action, checked = false),
                SettingsRow("theme:Graphite", "Graphite", kind = RowKind.Action, checked = true),
            ),
            SettingsPageId.Accent to listOf(
                SettingsRow("accent:Emerald", "Emerald", kind = RowKind.Action),
                SettingsRow("accent:Lime", "Lime", kind = RowKind.Action),
            ),
            SettingsPageId.DockOrder to listOf(
                SettingsRow("app:a", "A", kind = RowKind.Reorderable),
                SettingsRow("app:b", "B", kind = RowKind.Reorderable),
                SettingsRow("app:c", "C", kind = RowKind.Reorderable),
            ),
            SettingsPageId.Apps to listOf(SettingsRow("non_tv", "Non TV", kind = RowKind.Action)),
        )

        val ROOT_AT_ORDER = SettingsFrame(SettingsPageId.Root, ROW_TO_ORDER)

        /** Home state under the overlays in the overlay tests. */
        val BASE = NavState(HomeNav(Section.TopBar, topBarIndex = TopBarItem.Inputs.ordinal))

        fun app(section: Section, index: Int) = AppKey("test.${section.name.lowercase()}", "Item$index")

        fun ctx(
            topBar: Int = TopBarItem.entries.size,
            hero: Int = 3,
            dock: Int = 5,
            continueWatching: Int = 4,
            apps: Int = 12,
            columns: Int = 5,
            inputs: Int = 3,
            appAt: (Section, Int) -> AppKey? = ::app,
            menu: (AppKey, Section) -> List<AppMenuItem> = { _, from -> if (from == Section.Dock) DOCK_MENU else APPS_MENU },
            rows: (SettingsPageId) -> List<SettingsRow> = { PAGES[it].orEmpty() },
        ) = NavContext(
            heroCount = hero,
            dockCount = dock,
            continueCount = continueWatching,
            appsCount = apps,
            appsColumns = columns,
            inputsCount = inputs,
            topBarCount = topBar,
            appAt = appAt,
            appMenuFor = menu,
            settingsRows = rows,
        )

        fun home(section: Section, top: Int = TopBarItem.Settings.ordinal, hero: Int = 0, dock: Int = 0, cont: Int = 0, apps: Int = 0) =
            NavState(HomeNav(section, topBarIndex = top, heroIndex = hero, dockIndex = dock, continueIndex = cont, appsIndex = apps))

        /** Move mode with the item at [index] of [section] picked up. */
        fun reordering(section: Section, index: Int): NavState {
            val focused = if (section == Section.Dock) home(section, dock = index) else home(section, apps = index)
            return focused.copy(reorder = ReorderState(section))
        }

        fun overlay(vararg overlays: Overlay, base: NavState = BASE): NavState = base.copy(overlays = overlays.toList())

        fun settings(vararg frames: SettingsFrame): NavState = overlay(Overlay.Settings(frames.toList()))

        fun appMenu(index: Int) = Overlay.AppMenu(MENU_APP, APPS_MENU, index)
    }
}
