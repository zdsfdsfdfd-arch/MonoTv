package tv.monotv.launcher.nav

import java.util.EnumMap
import kotlin.math.abs
import kotlin.random.Random
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.monotv.launcher.core.AppKey

/**
 * Seeded random walks through the reducer. Each scenario owns a small fake "device" ([World]) whose apps, inputs and
 * settings pages change under the reducer's feet, and after every single key press the complete set of state
 * invariants is checked. A failure names its seed, so it can be replayed by running that one scenario.
 */
class NavReducerFuzzTest {

    @Test
    fun `random key sequences keep every invariant and stay deterministic`() {
        val coverage = Coverage()
        for (seed in 0 until SCENARIOS) {
            try {
                Scenario(seed, coverage).run()
            } catch (failure: Throwable) {
                throw AssertionError("scenario with seed $seed failed: $failure", failure)
            }
        }
        coverage.assertEverythingWasExercised()
    }

    @Test
    fun `hostile contexts and garbage states never throw`() {
        for (seed in 0 until HOSTILE_SCENARIOS) {
            val random = Random(seed)
            val context = hostileContext(random)
            var state = garbageState(random, extremes = true)
            try {
                assertTrue(NavReducer.homeSection(context) in Section.entries)
                repeat(KEYS_PER_SCENARIO) {
                    val key = NavKey.entries.random(random)
                    val result = NavReducer.reduce(state, key, context)
                    assertEquals("reduce must be deterministic", result, NavReducer.reduce(state, key, context))
                    // A SettingsMove result is only valid once the ViewModel has applied the move to the data.
                    if (result.effects.none { it is NavEffect.SettingsMove }) {
                        assertSame("output must already be normalized", result.state, NavReducer.normalize(result.state, context))
                    }
                    state = if (random.nextInt(10) == 0) garbageState(random, extremes = true) else result.state
                }
            } catch (failure: Throwable) {
                throw AssertionError("hostile scenario with seed $seed failed: $failure", failure)
            }
        }
    }

    // =================================================================================================================
    // Scenario
    // =================================================================================================================

    private class Scenario(seed: Int, private val coverage: Coverage) {
        private val random = Random(seed)
        private val world = World(random)

        fun run() {
            var context = world.context()
            // Start from garbage on purpose: reduce has to cope with whatever state it is handed.
            var state = garbageState(random, extremes = false)
            repeat(KEYS_PER_SCENARIO) {
                when (random.nextInt(14)) {
                    // Apps / inputs / settings rows appear and disappear, and the ViewModel re-validates right away.
                    0 -> {
                        world.mutate()
                        context = world.context()
                        state = NavReducer.normalize(state, context)
                        assertValid(state, context)
                        assertSame("normalize must be idempotent", state, NavReducer.normalize(state, context))
                    }
                    // Same, but the next key press arrives before anybody normalized.
                    1 -> {
                        world.mutate()
                        context = world.context()
                    }
                    // The ViewModel opens a confirmation in response to an effect.
                    2 -> state = NavReducer.openOverlay(state, Overlay.Confirm(ConfirmId.entries.random(random)))
                    // A stale state from somewhere else entirely.
                    3 -> if (random.nextInt(4) == 0) state = garbageState(random, extremes = false)
                }

                val key = KEYS[random.nextInt(KEYS.size)]
                val acted = NavReducer.normalize(state, context)
                val result = NavReducer.reduce(state, key, context)
                assertEquals("reduce must be deterministic", result, NavReducer.reduce(state, key, context))

                for (effect in result.effects) {
                    checkEffect(effect, key, acted, result.state)
                    world.apply(effect)
                }
                context = world.context()
                assertValid(result.state, context)
                assertSame("output must already be normalized", result.state, NavReducer.normalize(result.state, context))

                coverage.record(acted, result)
                state = result.state
            }
        }

        /** Every structural invariant of a state that is valid for the current [world]. */
        private fun assertValid(state: NavState, context: NavContext) {
            val home = state.home
            for (section in Section.entries) {
                assertInRange("${section.name} index", home.indexOf(section), world.count(section))
            }

            val homePosition = HOME_PRIORITY.firstOrNull { world.count(it) > 0 } ?: Section.TopBar
            assertEquals(homePosition, NavReducer.homeSection(context))
            assertTrue(
                "focused section ${home.section} is neither available nor the home position",
                world.count(home.section) > 0 || home.section == homePosition,
            )

            state.reorder?.let { reorder ->
                assertTrue("reorder outside Dock / Apps", reorder.section == Section.Dock || reorder.section == Section.Apps)
                assertEquals("reorder on an unfocused section", home.section, reorder.section)
                assertTrue("reorder with nothing to move", world.count(reorder.section) >= 2)
            }

            for (overlay in state.overlays) {
                when (overlay) {
                    is Overlay.Inputs -> {
                        assertTrue("inputs overlay without inputs", world.inputs > 0)
                        assertInRange("inputs index", overlay.index, world.inputs)
                    }
                    is Overlay.Settings -> {
                        assertTrue("empty settings stack", overlay.stack.isNotEmpty())
                        for (frame in overlay.stack) {
                            val rows = world.rows(frame.page)
                            assertTrue("frame of a page without rows: ${frame.page}", rows.isNotEmpty())
                            assertInRange("settings index on ${frame.page}", frame.index, rows.size)
                            if (frame.grabbed) {
                                assertEquals("grabbed row that cannot be reordered", RowKind.Reorderable, rows[frame.index].kind)
                                assertSame("grabbed row on a buried page", overlay.stack.last(), frame)
                            }
                        }
                    }
                    is Overlay.AppMenu -> {
                        assertTrue("app menu without items", overlay.items.isNotEmpty())
                        assertInRange("app menu index", overlay.index, overlay.items.size)
                    }
                    is Overlay.Confirm -> assertTrue("confirm index ${overlay.index}", overlay.index in 0..1)
                }
            }
        }

        /**
         * Each effect must be consistent with the state the key acted on ([acted] = the normalized input) and with
         * the data: no effect may ever point at something that is not there.
         */
        private fun checkEffect(effect: NavEffect, key: NavKey, acted: NavState, next: NavState) {
            val home = acted.home
            when (effect) {
                is NavEffect.LaunchApp -> {
                    assertNull(acted.topOverlay)
                    assertEquals(world.appAt(home.section, home.indexOf(home.section)), effect.app)
                }
                is NavEffect.OpenHero -> {
                    assertEquals(Section.Hero, home.section)
                    assertEquals(home.heroIndex, effect.index)
                    assertTrue(effect.index in 0 until world.hero)
                }
                is NavEffect.OpenContinue -> {
                    assertEquals(Section.Continue, home.section)
                    assertEquals(home.continueIndex, effect.index)
                    assertTrue(effect.index in 0 until world.continueWatching)
                }
                NavEffect.OpenNetworkSettings -> {
                    assertEquals(Section.TopBar, home.section)
                    assertTrue(world.topBar > 0)
                    assertEquals(TopBarItem.Network.ordinal, home.topBarIndex)
                }
                is NavEffect.SwitchInput -> {
                    assertEquals(Overlay.Inputs(effect.index), acted.topOverlay)
                    assertTrue(effect.index in 0 until world.inputs)
                }
                is NavEffect.SettingsAction -> {
                    val row = world.rows(effect.page).singleOrNull { it.id == effect.rowId }
                    assertNotNull("action on a row that does not exist", row)
                    assertEquals(RowKind.Action, row!!.kind)
                    assertTrue("action on a disabled row", row.enabled)
                    assertEquals(acted, next)
                }
                is NavEffect.SettingsMove -> {
                    val rows = world.rows(effect.page)
                    assertTrue(effect.from in rows.indices && effect.to in rows.indices)
                    assertEquals(1, abs(effect.from - effect.to))
                    assertEquals(RowKind.Reorderable, rows[effect.from].kind)
                    val frame = (next.topOverlay as Overlay.Settings).stack.last()
                    assertEquals(SettingsFrame(effect.page, effect.to, grabbed = true), frame)
                }
                is NavEffect.MoveItem -> {
                    assertNull(acted.topOverlay)
                    assertEquals(ReorderState(effect.section), acted.reorder)
                    assertEquals(acted.reorder, next.reorder)
                    assertEquals(home.indexOf(effect.section), effect.from)
                    assertEquals(next.home.indexOf(effect.section), effect.to)
                    assertTrue(effect.to in 0 until world.count(effect.section))
                    val distance = abs(effect.from - effect.to)
                    assertTrue(distance == 1 || (effect.section == Section.Apps && distance == world.columns))
                }
                is NavEffect.AppMenuAction -> {
                    val menu = acted.topOverlay as Overlay.AppMenu
                    assertEquals(menu.app, effect.app)
                    assertEquals(menu.items[menu.index], effect.item)
                    assertTrue(effect.item != AppMenuItem.Move)
                }
                is NavEffect.Confirmed -> assertEquals(Overlay.Confirm(effect.id, 1), acted.topOverlay)
                is NavEffect.Bump -> {
                    // A long press that falls back to OK bumps as OK; every other bump names the pressed key.
                    assertTrue(effect.direction == key || (key == NavKey.OkLong && effect.direction == NavKey.Ok))
                    assertEquals("a bump must not change the state", acted, next)
                }
                NavEffect.ExitApp -> {
                    assertEquals(NavKey.Back, key)
                    assertTrue(acted.overlays.isEmpty() && acted.reorder == null)
                    assertEquals(NavReducer.homeSection(world.context()), home.section)
                    assertEquals(acted, next)
                }
            }
        }
    }

    // =================================================================================================================
    // Fake device
    // =================================================================================================================

    /** Mutable stand-in for the launcher data; [context] exposes it to the reducer exactly like the ViewModel would. */
    private class World(private val random: Random) {
        var topBar = randomCount(TopBarItem.entries.size)
        var hero = randomCount(4)
        var continueWatching = randomCount(5)
        var inputs = randomCount(4)
        var columns = random.nextInt(1, 8)
        private var menuSalt = random.nextInt()
        private var nextId = 0

        private val dock = ArrayList<AppKey>()
        private val apps = ArrayList<AppKey>()
        private val pages = EnumMap<SettingsPageId, MutableList<SettingsRow>>(SettingsPageId::class.java)

        init {
            resize(dock, randomCount(7))
            resize(apps, randomCount(23))
            for (page in SettingsPageId.entries) pages[page] = randomPage(page)
        }

        fun count(section: Section): Int = when (section) {
            Section.TopBar -> topBar
            Section.Hero -> hero
            Section.Dock -> dock.size
            Section.Continue -> continueWatching
            Section.Apps -> apps.size
        }

        fun rows(page: SettingsPageId): List<SettingsRow> = pages.getValue(page)

        fun appAt(section: Section, index: Int): AppKey? = when (section) {
            Section.Dock -> dock.getOrNull(index)
            Section.Apps -> apps.getOrNull(index)
            Section.TopBar, Section.Hero, Section.Continue -> null
        }

        fun context() = NavContext(
            heroCount = hero,
            dockCount = dock.size,
            continueCount = continueWatching,
            appsCount = apps.size,
            appsColumns = columns,
            inputsCount = inputs,
            topBarCount = topBar,
            appAt = ::appAt,
            appMenuFor = ::menuFor,
            settingsRows = ::rows,
        )

        /** Changes a few random facts: apps get (un)installed anywhere in their lists, pages are rebuilt, ... */
        fun mutate() {
            repeat(random.nextInt(1, 4)) {
                when (random.nextInt(9)) {
                    0 -> topBar = randomCount(TopBarItem.entries.size)
                    1 -> hero = randomCount(4)
                    2 -> continueWatching = randomCount(5)
                    3 -> inputs = randomCount(4)
                    4 -> columns = random.nextInt(1, 8)
                    5 -> resize(dock, randomCount(7))
                    6 -> resize(apps, randomCount(23))
                    7 -> repeat(random.nextInt(1, 5)) {
                        val page = SettingsPageId.entries.random(random)
                        pages[page] = randomPage(page)
                    }
                    else -> menuSalt = random.nextInt()
                }
            }
        }

        /** What the ViewModel does with the effects that change data the reducer indexes into. */
        fun apply(effect: NavEffect) {
            when (effect) {
                is NavEffect.MoveItem -> {
                    val list = if (effect.section == Section.Dock) dock else apps
                    list.add(effect.to, list.removeAt(effect.from))
                }
                is NavEffect.SettingsMove -> {
                    val rows = pages.getValue(effect.page)
                    rows.add(effect.to, rows.removeAt(effect.from))
                }
                else -> Unit
            }
        }

        private fun menuFor(app: AppKey, from: Section): List<AppMenuItem> {
            val bits = app.hashCode() * 31 + from.ordinal + menuSalt
            // About one menu in sixteen breaks the "never empty" contract on purpose.
            if ((bits and 0xF) == 0) return emptyList()
            return AppMenuItem.entries.filter { item ->
                when (item) {
                    AppMenuItem.Open -> true
                    AppMenuItem.Move -> (bits and 0x30) != 0
                    else -> ((bits shr (6 + item.ordinal)) and 1) == 1
                }
            }
        }

        private fun randomCount(max: Int): Int = if (random.nextInt(4) == 0) 0 else random.nextInt(1, max + 1)

        private fun resize(list: MutableList<AppKey>, size: Int) {
            while (list.size > size) list.removeAt(random.nextInt(list.size))
            while (list.size < size) list.add(random.nextInt(list.size + 1), AppKey("fuzz.app", "A${nextId++}"))
        }

        private fun randomPage(page: SettingsPageId): MutableList<SettingsRow> {
            // The root page is rarely empty so that settings can usually be opened at all.
            if (random.nextInt(if (page == SettingsPageId.Root) 12 else 6) == 0) return ArrayList()
            val orderPage = random.nextInt(4) == 0
            return MutableList(random.nextInt(1, 7)) {
                val kind = if (orderPage) {
                    RowKind.Reorderable
                } else {
                    when (random.nextInt(10)) {
                        in 0..3 -> RowKind.Navigate(SettingsPageId.entries.random(random))
                        in 4..6 -> RowKind.Action
                        7 -> RowKind.Info
                        else -> RowKind.Reorderable
                    }
                }
                SettingsRow(
                    id = "row${nextId++}",
                    title = "Row",
                    kind = kind,
                    checked = when (random.nextInt(4)) {
                        0 -> true
                        1 -> false
                        else -> null
                    },
                    enabled = random.nextInt(7) != 0,
                )
            }
        }
    }

    /** Proof that the random walks actually reach every corner instead of bumping around the home screen. */
    private class Coverage {
        private val effects = HashSet<KClass<out NavEffect>>()
        private val milestones = HashSet<String>()

        fun record(acted: NavState, result: NavResult) {
            result.effects.mapTo(effects) { it::class }
            val before = acted.topOverlay
            val after = result.state.topOverlay
            if (acted.reorder == null && result.state.reorder != null) milestones += REORDER_ENTERED
            if (acted.reorder != null && result.state.reorder == null) milestones += REORDER_LEFT
            if (before == null && after is Overlay.Inputs) milestones += INPUTS_OPENED
            if (before == null && after is Overlay.AppMenu) milestones += APP_MENU_OPENED
            if (before == null && after is Overlay.Settings) milestones += SETTINGS_OPENED
            if (before is Overlay.Settings && after is Overlay.Settings) {
                if (after.stack.size > before.stack.size) milestones += SETTINGS_PAGE_PUSHED
                if (after.stack.size < before.stack.size) milestones += SETTINGS_PAGE_POPPED
                if (after.stack.size >= 3) milestones += SETTINGS_THREE_DEEP
                if (!before.stack.last().grabbed && after.stack.last().grabbed) milestones += ROW_GRABBED
                if (before.stack.last().grabbed && !after.stack.last().grabbed) milestones += ROW_DROPPED
            }
        }

        fun assertEverythingWasExercised() {
            assertEquals(ALL_EFFECTS, effects)
            assertEquals(ALL_MILESTONES, milestones)
        }

        private companion object {
            const val REORDER_ENTERED = "reorder entered"
            const val REORDER_LEFT = "reorder left"
            const val INPUTS_OPENED = "inputs opened"
            const val APP_MENU_OPENED = "app menu opened"
            const val SETTINGS_OPENED = "settings opened"
            const val SETTINGS_PAGE_PUSHED = "settings page pushed"
            const val SETTINGS_PAGE_POPPED = "settings page popped"
            const val SETTINGS_THREE_DEEP = "settings three pages deep"
            const val ROW_GRABBED = "row grabbed"
            const val ROW_DROPPED = "row dropped"

            val ALL_MILESTONES = setOf(
                REORDER_ENTERED, REORDER_LEFT, INPUTS_OPENED, APP_MENU_OPENED, SETTINGS_OPENED,
                SETTINGS_PAGE_PUSHED, SETTINGS_PAGE_POPPED, SETTINGS_THREE_DEEP, ROW_GRABBED, ROW_DROPPED,
            )

            val ALL_EFFECTS: Set<KClass<out NavEffect>> = setOf(
                NavEffect.LaunchApp::class,
                NavEffect.OpenHero::class,
                NavEffect.OpenContinue::class,
                NavEffect.OpenNetworkSettings::class,
                NavEffect.SwitchInput::class,
                NavEffect.SettingsAction::class,
                NavEffect.SettingsMove::class,
                NavEffect.MoveItem::class,
                NavEffect.AppMenuAction::class,
                NavEffect.Confirmed::class,
                NavEffect.Bump::class,
                NavEffect.ExitApp::class,
            )
        }
    }

    private companion object {
        const val SCENARIOS = 3000
        const val HOSTILE_SCENARIOS = 600
        const val KEYS_PER_SCENARIO = 60

        /** Directions and OK dominate, as on a real remote; BACK / MENU are rare enough to let overlays live. */
        val KEYS: List<NavKey> = buildList {
            repeat(3) { addAll(listOf(NavKey.Up, NavKey.Down, NavKey.Left, NavKey.Right)) }
            repeat(4) { add(NavKey.Ok) }
            addAll(listOf(NavKey.OkLong, NavKey.OkLong, NavKey.Menu, NavKey.Back))
        }

        val HOME_PRIORITY = listOf(Section.Dock, Section.Hero, Section.Apps, Section.Continue, Section.TopBar)

        val EXTREMES = intArrayOf(Int.MIN_VALUE, -7, -1, 0, 1, 2, 5, 40, Int.MAX_VALUE)

        fun assertInRange(what: String, index: Int, count: Int) {
            if (count == 0) assertEquals("$what of an empty list", 0, index) else assertTrue("$what = $index of $count", index in 0 until count)
        }

        fun HomeNav.indexOf(section: Section): Int = when (section) {
            Section.TopBar -> topBarIndex
            Section.Hero -> heroIndex
            Section.Dock -> dockIndex
            Section.Continue -> continueIndex
            Section.Apps -> appsIndex
        }

        /** A state nobody validated: wild indices, random overlays (even contract-breaking empty ones). */
        fun garbageState(random: Random, extremes: Boolean): NavState {
            fun index(bound: Int) = if (extremes && random.nextInt(3) == 0) EXTREMES[random.nextInt(EXTREMES.size)] else random.nextInt(-3, bound)
            fun overlay(): Overlay = when (random.nextInt(4)) {
                0 -> Overlay.Inputs(index(7))
                1 -> Overlay.Settings(
                    List(random.nextInt(0, 4)) { SettingsFrame(SettingsPageId.entries.random(random), index(9), random.nextBoolean()) },
                )
                2 -> Overlay.AppMenu(
                    AppKey("fuzz.menu", "Main"),
                    AppMenuItem.entries.shuffled(random).take(random.nextInt(0, AppMenuItem.entries.size + 1)),
                    index(8),
                )
                else -> Overlay.Confirm(ConfirmId.entries.random(random), index(4))
            }
            return NavState(
                home = HomeNav(Section.entries.random(random), index(6), index(8), index(10), index(8), index(30)),
                reorder = if (random.nextInt(4) == 0) ReorderState(Section.entries.random(random)) else null,
                overlays = List(random.nextInt(0, 3)) { overlay() },
            )
        }

        /** Counts and callbacks a correct ViewModel would never produce: negative, absurdly large, throwing. */
        fun hostileContext(random: Random): NavContext {
            fun extreme() = EXTREMES[random.nextInt(EXTREMES.size)]
            val salt = random.nextInt(2, 6)
            val rows = listOf(
                SettingsRow("to_next", "Next", kind = RowKind.Navigate(SettingsPageId.entries.random(random))),
                SettingsRow("action", "Action", kind = RowKind.Action, checked = true),
                SettingsRow("grab", "Grab", kind = RowKind.Reorderable),
                SettingsRow("info", "Info", kind = RowKind.Info, enabled = false),
            )
            return NavContext(
                heroCount = extreme(),
                dockCount = extreme(),
                continueCount = extreme(),
                appsCount = extreme(),
                appsColumns = extreme(),
                inputsCount = extreme(),
                topBarCount = extreme(),
                appAt = { section, index ->
                    when ((index + section.ordinal).mod(salt)) {
                        0 -> throw IllegalStateException("appAt failed")
                        1 -> null
                        else -> AppKey("fuzz.hostile", "A$index")
                    }
                },
                appMenuFor = { app, from ->
                    when ((app.hashCode() + from.ordinal).mod(salt)) {
                        0 -> throw IndexOutOfBoundsException("appMenuFor failed")
                        1 -> emptyList()
                        else -> listOf(AppMenuItem.Open, AppMenuItem.Move, AppMenuItem.Info)
                    }
                },
                settingsRows = { page ->
                    when ((page.ordinal + salt).mod(3)) {
                        0 -> throw NoSuchElementException("settingsRows failed")
                        1 -> emptyList()
                        else -> rows
                    }
                },
            )
        }
    }
}
