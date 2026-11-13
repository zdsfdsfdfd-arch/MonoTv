package tv.monotv.launcher

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.monotv.launcher.core.AccentColor
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.ClockState
import tv.monotv.launcher.core.ContinueItem
import tv.monotv.launcher.core.DeviceInfo
import tv.monotv.launcher.core.EffectsLevel
import tv.monotv.launcher.core.HeroItem
import tv.monotv.launcher.core.HeroKind
import tv.monotv.launcher.core.InputItem
import tv.monotv.launcher.core.InputKind
import tv.monotv.launcher.core.KnownApp
import tv.monotv.launcher.core.LauncherConfig
import tv.monotv.launcher.core.LauncherSettings
import tv.monotv.launcher.core.NetworkState
import tv.monotv.launcher.core.RecentLaunch
import tv.monotv.launcher.core.Wallpapers
import tv.monotv.launcher.data.AppsRepository
import tv.monotv.launcher.data.ContentRepository
import tv.monotv.launcher.data.InputsRepository
import tv.monotv.launcher.data.SettingsRepository
import tv.monotv.launcher.data.SystemRepository
import tv.monotv.launcher.nav.NavKey
import tv.monotv.launcher.nav.Overlay
import tv.monotv.launcher.nav.Section
import tv.monotv.launcher.nav.SettingsPageId
import tv.monotv.launcher.ui.components.PointerTarget

/** End-to-end wiring: remote key -> NavReducer -> effect -> repositories -> new UI state, with fake repositories. */
@OptIn(ExperimentalCoroutinesApi::class)
class LauncherViewModelTest {
    private val dispatcher = UnconfinedTestDispatcher()

    private fun app(id: String, leanback: Boolean = true) = AppEntry(
        key = AppKey("pkg.$id", "pkg.$id.Main"), label = id.replaceFirstChar { it.uppercase() }, isLeanback = leanback,
        hasBanner = true, isSystem = false, knownId = id, versionCode = 1, installedAt = 0,
    )

    private val names = listOf("youtube", "netflix", "hbomax", "appletv", "spotify", "kinopoisk", "okko", "wink", "ivi", "rutube", "vlc", "kodi")
    private val installed = names.map { app(it) }
    private val config = LauncherConfig(
        dockPriority = names.take(7),
        knownApps = names.map { KnownApp(it, it, listOf("pkg.$it")) },
    )

    private class FakeApps(initial: List<AppEntry>?, override val config: LauncherConfig) : AppsRepository {
        override val apps = MutableStateFlow(initial)
        val launched = ArrayList<AppKey>()
        val launchedUris = ArrayList<Pair<String?, AppKey?>>()
        val infoOpened = ArrayList<AppKey>()
        var launchResult = true
        var starts = 0
        var stops = 0
        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun refresh() = Unit
        override fun launch(key: AppKey): Boolean { launched += key; return launchResult }
        override fun launchUri(intentUri: String?, fallback: AppKey?): Boolean { launchedUris += intentUri to fallback; return launchResult }
        override fun openAppInfo(key: AppKey): Boolean { infoOpened += key; return true }
    }

    private class FakeSettings : SettingsRepository {
        override val settings = MutableStateFlow(LauncherSettings())
        override val recents = MutableStateFlow<List<RecentLaunch>>(emptyList())
        override fun update(transform: (LauncherSettings) -> LauncherSettings) { settings.value = transform(settings.value) }
        override fun recordLaunch(key: AppKey, now: Long) {
            recents.value = listOf(RecentLaunch(key, now, 1)) + recents.value.filter { it.key != key }
        }
        override fun pruneRecents(installed: Set<AppKey>) = Unit
    }

    private class FakeSystem : SystemRepository {
        override val clock = MutableStateFlow(ClockState("19:42", "13 ноября, пятница", "Добрый вечер"))
        override val network = MutableStateFlow(NetworkState())
        override val device = MutableStateFlow(DeviceInfo())
        var networkSettingsOpened = 0
        var systemSettingsOpened = 0
        var homeChooserOpened = 0
        var reportPath: String? = "/sdcard/Android/data/tv.monotv.launcher/files/device-report.txt"
        override fun start() = Unit
        override fun stop() = Unit
        override fun refreshDevice() = Unit
        override fun recommendedEffects() = EffectsLevel.Reduced
        override fun openNetworkSettings(): Boolean { networkSettingsOpened++; return true }
        override fun openSystemSettings(): Boolean { systemSettingsOpened++; return true }
        override fun setHomeAliasEnabled(enabled: Boolean): Boolean { device.value = device.value.copy(homeAliasEnabled = enabled); return true }
        override fun openHomeChooser(): Boolean { homeChooserOpened++; return false }
        override fun writeDiagnosticsReport(): String? = reportPath
    }

    private class FakeInputs : InputsRepository {
        override val inputs = MutableStateFlow(
            listOf(
                InputItem("hdmi1", "HDMI 1", InputKind.Hdmi, connected = true, switchable = true),
                InputItem("hdmi2", "HDMI 2", InputKind.Hdmi, connected = false, switchable = true),
                InputItem("av", "AV", InputKind.Av, connected = null, switchable = false),
            ),
        )
        val switched = ArrayList<InputItem>()
        override fun start() = Unit
        override fun stop() = Unit
        override fun switchTo(item: InputItem): Boolean { switched += item; return item.switchable }
    }

    private class FakeContent : ContentRepository {
        var watchNext: List<ContinueItem> = emptyList()
        override val watchNextInvalidations = MutableStateFlow(0)
        override suspend fun loadWatchNext(limit: Int): List<ContinueItem> = watchNext
        override fun start() = Unit
        override fun stop() = Unit
    }

    private val planner = object : ContentPlanner {
        override fun continueItems(watchNext: List<ContinueItem>, recents: List<RecentLaunch>, apps: List<AppEntry>, demo: Boolean) =
            watchNext + recents.mapNotNull { r -> apps.firstOrNull { it.key == r.key } }
                .map { ContinueItem("recent:${it.key.flatten()}", it.label, null, ArtSource.AppBanner(it.key), null, it.key, null) } +
                if (demo) listOf(ContinueItem("demo:1", "Демо", null, ArtSource.Wallpaper(Wallpapers.AURORA), 0.5f, null, null)) else emptyList()

        override fun heroItems(continueItems: List<ContinueItem>, dock: List<AppEntry>, config: LauncherConfig, demo: Boolean): List<HeroItem> {
            val demoItems = if (demo) {
                listOf(HeroItem("demo:h", HeroKind.Demo, null, "Демо", null, "Смотреть", ArtSource.Wallpaper(Wallpapers.AURORA), null, null, null))
            } else {
                emptyList()
            }
            val appItems = dock.map {
                HeroItem("app:${it.key.flatten()}", HeroKind.App, "Приложение", it.label, null, "Открыть", ArtSource.Wallpaper(Wallpapers.NEBULA), it.key, null, null)
            }
            return (demoItems + appItems).ifEmpty {
                listOf(HeroItem("welcome", HeroKind.App, "Mono TV", "Добро пожаловать", null, "Настройки", ArtSource.Wallpaper(Wallpapers.AURORA), null, null, null))
            }
        }
    }

    private lateinit var apps: FakeApps
    private lateinit var settings: FakeSettings
    private lateinit var system: FakeSystem
    private lateinit var inputs: FakeInputs
    private lateinit var content: FakeContent
    private lateinit var vm: LauncherViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        apps = FakeApps(installed, config)
        settings = FakeSettings()
        system = FakeSystem()
        inputs = FakeInputs()
        content = FakeContent()
        vm = LauncherViewModel(apps, settings, system, inputs, content, planner, io = dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun press(vararg keys: NavKey) = keys.forEach(vm::onKey)

    private val ui get() = vm.ui.value

    @Test
    fun `starts on the dock with the automatic priority dock`() {
        assertTrue(ui.loaded)
        assertEquals(Section.Dock, ui.nav.home.section)
        assertEquals(names.take(5), ui.dock.map { it.knownId })
        assertEquals(EffectsLevel.Reduced, ui.effects)
    }

    @Test
    fun `ok on a dock card launches it and records the launch`() {
        press(NavKey.Right, NavKey.Right, NavKey.Ok)
        assertEquals(listOf(installed[2].key), apps.launched)
        assertEquals(installed[2].key, settings.recents.value.first().key)
        // The launched app now shows up in "Продолжить просмотр".
        assertEquals("recent:${installed[2].key.flatten()}", ui.continueWatching.first().id)
    }

    @Test
    fun `a failed launch tells the user and records nothing`() {
        apps.launchResult = false
        press(NavKey.Ok)
        assertTrue(settings.recents.value.isEmpty())
        assertNotNull(ui.toast)
    }

    @Test
    fun `long ok opens the app menu and remove from dock edits the dock`() {
        press(NavKey.Right, NavKey.OkLong)
        val menu = ui.nav.topOverlay as Overlay.AppMenu
        assertEquals(installed[1].key, menu.app)
        val removeIndex = menu.items.indexOfFirst { it.name == "RemoveFromDock" }
        repeat(removeIndex) { press(NavKey.Down) }
        press(NavKey.Ok)
        assertNull(ui.nav.topOverlay)
        assertEquals(listOf("youtube", "hbomax", "appletv", "spotify"), ui.dock.map { it.knownId })
        assertEquals(4, settings.settings.value.dockApps?.size)
    }

    @Test
    fun `move mode reorders the dock with the arrows`() {
        press(NavKey.OkLong)
        val menu = ui.nav.topOverlay as Overlay.AppMenu
        repeat(menu.items.indexOfFirst { it.name == "Move" }) { press(NavKey.Down) }
        press(NavKey.Ok)
        assertEquals(Section.Dock, ui.nav.reorder?.section)
        press(NavKey.Right, NavKey.Right)
        assertEquals(listOf("netflix", "hbomax", "youtube", "appletv", "spotify"), ui.dock.map { it.knownId })
        assertEquals(2, ui.nav.home.dockIndex)
        press(NavKey.Ok)
        assertNull(ui.nav.reorder)
        assertTrue(apps.launched.isEmpty())
    }

    @Test
    fun `settings pill opens settings and choosing an accent applies it live`() {
        press(NavKey.Up, NavKey.Up, NavKey.Ok)
        assertTrue(ui.nav.topOverlay is Overlay.Settings)
        press(NavKey.Ok) // Root -> Оформление
        val appearance = tv.monotv.launcher.nav.SettingsCatalog.rows(SettingsPageId.Appearance, ui)
        repeat(appearance.indexOfFirst { it.id == "accent" }) { press(NavKey.Down) }
        press(NavKey.Ok)
        val accents = tv.monotv.launcher.nav.SettingsCatalog.rows(SettingsPageId.Accent, ui)
        val target = accents.indexOfFirst { it.id == "accent:${AccentColor.Sky.name}" }
        val start = (ui.nav.topOverlay as Overlay.Settings).stack.last().index
        repeat(target - start) { press(NavKey.Down) }
        press(NavKey.Ok)
        assertEquals(AccentColor.Sky, ui.settings.accent)
        // BACK pops page by page and finally closes the overlay.
        press(NavKey.Back, NavKey.Back, NavKey.Back)
        assertNull(ui.nav.topOverlay)
        assertEquals(Section.TopBar, ui.nav.home.section)
    }

    @Test
    fun `dock size setting changes the dock`() {
        press(NavKey.Menu) // dock card -> app menu; close it and use the pill instead
        press(NavKey.Back, NavKey.Up, NavKey.Up, NavKey.Ok)
        val root = tv.monotv.launcher.nav.SettingsCatalog.rows(SettingsPageId.Root, ui)
        repeat(root.indexOfFirst { it.id == "dock" }) { press(NavKey.Down) }
        press(NavKey.Ok)
        val dockRows = tv.monotv.launcher.nav.SettingsCatalog.rows(SettingsPageId.Dock, ui)
        repeat(dockRows.indexOfFirst { it.id == "size" }) { press(NavKey.Down) }
        press(NavKey.Ok)
        val sizes = tv.monotv.launcher.nav.SettingsCatalog.rows(SettingsPageId.DockSize, ui)
        val start = (ui.nav.topOverlay as Overlay.Settings).stack.last().index
        repeat(sizes.indexOfFirst { it.id == "size:7" } - start) { press(NavKey.Down) }
        press(NavKey.Ok)
        assertEquals(7, ui.settings.dockSize)
        assertEquals(names.take(7), ui.dock.map { it.knownId })
    }

    @Test
    fun `inputs menu switches through the repository and reports unavailable inputs`() {
        press(NavKey.Up, NavKey.Up, NavKey.Left, NavKey.Ok)
        assertTrue(ui.nav.topOverlay is Overlay.Inputs)
        press(NavKey.Down, NavKey.Ok)
        assertEquals("hdmi2", inputs.switched.single().id)
        assertNull(ui.nav.topOverlay)
        assertNull(ui.toast)
        press(NavKey.Ok, NavKey.Down, NavKey.Down, NavKey.Ok)
        assertEquals("av", inputs.switched.last().id)
        assertNotNull(ui.toast)
    }

    @Test
    fun `network pill opens the system network settings`() {
        press(NavKey.Up, NavKey.Up, NavKey.Left, NavKey.Left, NavKey.Ok)
        assertEquals(1, system.networkSettingsOpened)
    }

    @Test
    fun `back at the root exits only when we are not the default home`() = runTest(dispatcher) {
        var exits = 0
        val job = launch { vm.exit.collect { exits++ } }
        press(NavKey.Back)
        assertEquals(1, exits)
        system.device.value = system.device.value.copy(isDefaultHome = true)
        press(NavKey.Back)
        assertEquals(1, exits)
        job.cancel()
    }

    @Test
    fun `back returns to the dock before it exits`() = runTest(dispatcher) {
        var exits = 0
        val job = launch { vm.exit.collect { exits++ } }
        press(NavKey.Right, NavKey.Down, NavKey.Down)
        assertEquals(Section.Apps, ui.nav.home.section)
        press(NavKey.Back)
        assertEquals(Section.Dock, ui.nav.home.section)
        press(NavKey.Back)
        assertEquals(0, ui.nav.home.dockIndex)
        assertEquals(0, exits)
        press(NavKey.Back)
        assertEquals(1, exits)
        job.cancel()
    }

    @Test
    fun `focus stays valid when apps disappear under the cursor`() {
        press(NavKey.Down, NavKey.Down, NavKey.Down, NavKey.Right)
        assertEquals(Section.Apps, ui.nav.home.section)
        assertEquals(11, ui.nav.home.appsIndex)
        apps.apps.value = installed.take(3)
        assertEquals(Section.Apps, ui.nav.home.section)
        assertTrue(ui.nav.home.appsIndex in 0..2)
        apps.apps.value = emptyList()
        assertTrue(ui.dock.isEmpty())
        assertEquals(Section.Hero, ui.nav.home.section)
        press(NavKey.Left, NavKey.Right, NavKey.Up, NavKey.Down, NavKey.Ok) // must never throw
    }

    @Test
    fun `hiding an app from the grid removes it and can be undone in settings`() {
        press(NavKey.Down, NavKey.Menu)
        val menu = ui.nav.topOverlay as Overlay.AppMenu
        val hidden = menu.app
        repeat(menu.items.indexOfFirst { it.name == "Hide" }) { press(NavKey.Down) }
        press(NavKey.Ok)
        assertFalse(ui.apps.any { it.key == hidden })
        assertTrue(ui.allApps.any { it.key == hidden })
        assertNotNull(ui.toast)
        vm.onKey(NavKey.Back)
    }

    @Test
    fun `welcome hero opens settings when nothing is installed`() {
        apps.apps.value = emptyList()
        assertEquals("welcome", ui.hero.single().id)
        press(NavKey.Ok)
        assertTrue(ui.nav.topOverlay is Overlay.Settings)
    }

    @Test
    fun `demo cards explain themselves instead of launching`() {
        settings.update { it.copy(demoContent = true) }
        press(NavKey.Up)
        assertEquals(Section.Hero, ui.nav.home.section)
        press(NavKey.Ok)
        assertNotNull(ui.toast)
        assertTrue(apps.launchedUris.isEmpty())
    }

    @Test
    fun `enabling the home role asks first and only touches our own alias`() {
        press(NavKey.Up, NavKey.Up, NavKey.Ok)
        val root = tv.monotv.launcher.nav.SettingsCatalog.rows(SettingsPageId.Root, ui)
        repeat(root.indexOfFirst { it.id == "system" }) { press(NavKey.Down) }
        press(NavKey.Ok)
        val rows = tv.monotv.launcher.nav.SettingsCatalog.rows(SettingsPageId.System, ui)
        repeat(rows.indexOfFirst { it.id == "home_toggle" }) { press(NavKey.Down) }
        press(NavKey.Ok)
        assertTrue(ui.nav.topOverlay is Overlay.Confirm)
        assertFalse(ui.device.homeAliasEnabled)
        press(NavKey.Ok) // default button is "Отмена"
        assertFalse(ui.device.homeAliasEnabled)
        press(NavKey.Ok, NavKey.Right, NavKey.Ok)
        assertTrue(ui.device.homeAliasEnabled)
        assertEquals(1, system.homeChooserOpened)
    }

    @Test
    fun `home key returns to the pristine home position`() {
        press(NavKey.Right, NavKey.Down, NavKey.Menu)
        vm.goHome()
        assertNull(ui.nav.topOverlay)
        assertEquals(Section.Dock, ui.nav.home.section)
        assertEquals(0, ui.nav.home.dockIndex)
    }

    @Test
    fun `pointer taps focus and activate like ok`() {
        vm.onPointer(PointerTarget.HomeItem(Section.Dock, 3))
        assertEquals(listOf(installed[3].key), apps.launched)
        assertEquals(3, ui.nav.home.dockIndex)
        press(NavKey.Menu)
        vm.onPointer(PointerTarget.Outside)
        assertNull(ui.nav.topOverlay)
    }

    @Test
    fun `start and stop attach and detach the sources once`() {
        vm.start()
        vm.start()
        vm.stop()
        vm.stop()
        assertEquals(1, apps.starts)
        assertEquals(1, apps.stops)
    }

    @Test
    fun `watch next rows are loaded and reloaded on invalidation`() {
        assertTrue(ui.continueWatching.isEmpty())
        content.watchNext = listOf(ContinueItem("wn:1", "Фильм", null, ArtSource.AppBanner(installed[0].key), 0.4f, installed[0].key, "intent:#Intent;end"))
        content.watchNextInvalidations.value = 1
        assertEquals("wn:1", ui.continueWatching.single().id)
        press(NavKey.Down, NavKey.Ok)
        assertEquals("intent:#Intent;end" to installed[0].key, apps.launchedUris.single())
    }
}
