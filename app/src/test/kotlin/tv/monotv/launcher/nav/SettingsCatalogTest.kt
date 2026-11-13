package tv.monotv.launcher.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.monotv.launcher.core.AccentColor
import tv.monotv.launcher.core.AnimationLevel
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.DeviceInfo
import tv.monotv.launcher.core.EffectsLevel
import tv.monotv.launcher.core.LauncherSettings
import tv.monotv.launcher.core.LauncherUiState
import tv.monotv.launcher.core.ThemeMode
import tv.monotv.launcher.core.Wallpapers

class SettingsCatalogTest {
    private fun app(id: String, leanback: Boolean = true) = AppEntry(
        key = AppKey("pkg.$id", "pkg.$id.Main"), label = id, isLeanback = leanback, hasBanner = true, isSystem = false,
        knownId = null, versionCode = 1, installedAt = 0,
    )

    private val tv = (1..8).map { app("tv$it") }
    private val phone = app("phone", leanback = false)
    private val all = tv + phone

    private fun ui(
        settings: LauncherSettings = LauncherSettings(),
        dock: List<AppEntry> = tv.take(settings.dockSize),
        device: DeviceInfo = DeviceInfo(),
        effects: EffectsLevel = EffectsLevel.Full,
    ): LauncherUiState {
        val visible = all.filter { (it.isLeanback || settings.showNonTvApps) && it.key !in settings.hiddenApps }
        return LauncherUiState(dock = dock, apps = visible, allApps = all, settings = settings, device = device, effects = effects, loaded = true)
    }

    private fun rows(page: SettingsPageId, state: LauncherUiState = ui()) = SettingsCatalog.rows(page, state)

    @Test
    fun `every page has unique row ids`() {
        for (page in SettingsPageId.entries) {
            val ids = rows(page).map { it.id }
            assertEquals("duplicate ids on $page", ids.size, ids.toSet().size)
        }
    }

    @Test
    fun `root links to the four sections`() {
        val root = rows(SettingsPageId.Root)
        assertEquals(listOf("appearance", "dock", "apps", "system"), root.map { it.id })
        assertEquals(
            listOf(SettingsPageId.Appearance, SettingsPageId.Dock, SettingsPageId.Apps, SettingsPageId.System),
            root.map { (it.kind as RowKind.Navigate).page },
        )
        assertTrue(root.all { !it.description.isNullOrBlank() })
    }

    @Test
    fun `appearance shows the current values`() {
        val state = ui(
            LauncherSettings(theme = ThemeMode.Graphite, wallpaper = Wallpapers.DUNES, accent = AccentColor.Amber, animation = AnimationLevel.Reduced, demoContent = true),
        )
        val byId = rows(SettingsPageId.Appearance, state).associateBy { it.id }
        assertEquals(listOf("theme", "wallpaper", "accent", "animation", "demo"), rows(SettingsPageId.Appearance, state).map { it.id })
        assertEquals("Графит", byId.getValue("theme").value)
        assertEquals("Дюны", byId.getValue("wallpaper").value)
        assertEquals("Янтарь", byId.getValue("accent").value)
        assertEquals("Упрощённые", byId.getValue("animation").value)
        assertEquals("Вкл", byId.getValue("demo").value)
        assertEquals(RowKind.Action, byId.getValue("demo").kind)
    }

    @Test
    fun `auto animation names the level the device actually got`() {
        val full = rows(SettingsPageId.Appearance, ui(effects = EffectsLevel.Full)).first { it.id == "animation" }
        val reduced = rows(SettingsPageId.Appearance, ui(effects = EffectsLevel.Reduced)).first { it.id == "animation" }
        assertEquals("Авто · полные", full.value)
        assertEquals("Авто · упрощённые", reduced.value)
    }

    @Test
    fun `choice pages list every option and check exactly the selected one`() {
        val state = ui(LauncherSettings(theme = ThemeMode.Graphite, accent = AccentColor.Sky, animation = AnimationLevel.Off, wallpaper = Wallpapers.TIDE, dockSize = 6))
        val expected = mapOf(
            SettingsPageId.Theme to (ThemeMode.entries.map { "theme:${it.name}" } to "theme:Graphite"),
            SettingsPageId.Accent to (AccentColor.entries.map { "accent:${it.name}" } to "accent:Sky"),
            SettingsPageId.Animation to (AnimationLevel.entries.map { "animation:${it.name}" } to "animation:Off"),
            SettingsPageId.Wallpaper to (Wallpapers.choices.map { "wallpaper:${it.id}" } to "wallpaper:tide"),
            SettingsPageId.DockSize to ((4..7).map { "size:$it" } to "size:6"),
        )
        for ((page, value) in expected) {
            val (ids, selected) = value
            val list = rows(page, state)
            assertEquals(ids, list.map { it.id })
            assertTrue(list.all { it.kind == RowKind.Action && it.checked != null })
            assertEquals("selected on $page", listOf(selected), list.filter { it.checked == true }.map { it.id })
        }
    }

    @Test
    fun `dock page reports size and membership`() {
        val state = ui(LauncherSettings(dockSize = 6), dock = tv.take(4))
        val byId = rows(SettingsPageId.Dock, state).associateBy { it.id }
        assertEquals("6", byId.getValue("size").value)
        assertEquals("4 из 6", byId.getValue("dock_apps").value)
        assertTrue(byId.getValue("dock_order").enabled)
        assertEquals(RowKind.Action, byId.getValue("dock_reset").kind)
        assertFalse(rows(SettingsPageId.Dock, ui(dock = tv.take(1))).first { it.id == "dock_order" }.enabled)
    }

    @Test
    fun `dock apps can be added only while there is room`() {
        val full = rows(SettingsPageId.DockApps, ui(LauncherSettings(dockSize = 4), dock = tv.take(4)))
        assertEquals(tv.map { "app:${it.key.flatten()}" }, full.map { it.id })
        assertTrue(full.take(4).all { it.checked == true && it.enabled })
        assertTrue(full.drop(4).all { it.checked == false && !it.enabled && it.description == "Dock заполнен" })

        val room = rows(SettingsPageId.DockApps, ui(LauncherSettings(dockSize = 5), dock = tv.take(4)))
        assertTrue(room.all { it.enabled })
        assertNull(room.last().description)
    }

    @Test
    fun `hidden and phone apps are not offered for the dock`() {
        val hidden = tv[5].key
        val list = rows(SettingsPageId.DockApps, ui(LauncherSettings(hiddenApps = setOf(hidden))))
        assertFalse(list.any { it.appKey == hidden })
        assertFalse(list.any { it.appKey == phone.key })
        val withPhone = rows(SettingsPageId.DockApps, ui(LauncherSettings(showNonTvApps = true)))
        assertTrue(withPhone.any { it.appKey == phone.key })
    }

    @Test
    fun `order pages mirror the dock and the visible apps as reorderable rows`() {
        val state = ui()
        val dockOrder = rows(SettingsPageId.DockOrder, state)
        assertEquals(state.dock.map { it.key }, dockOrder.map { it.appKey })
        assertTrue(dockOrder.all { it.kind == RowKind.Reorderable })
        val appsOrder = rows(SettingsPageId.AppsOrder, state)
        assertEquals(state.apps.map { it.key }, appsOrder.map { it.appKey })
        assertTrue(appsOrder.all { it.kind == RowKind.Reorderable })
    }

    @Test
    fun `apps page counts visible apps and visibility lists hidden ones unchecked`() {
        val hidden = setOf(tv[0].key, tv[1].key)
        val state = ui(LauncherSettings(hiddenApps = hidden))
        val byId = rows(SettingsPageId.Apps, state).associateBy { it.id }
        assertEquals("6 из 8", byId.getValue("visibility").value)
        assertEquals("Выкл", byId.getValue("non_tv").value)
        val visibility = rows(SettingsPageId.AppsVisibility, state)
        assertEquals(tv.size, visibility.size)
        assertEquals(hidden, visibility.filter { it.checked == false }.mapNotNull { it.appKey }.toSet())
        assertTrue(visibility.all { it.enabled })
    }

    @Test
    fun `system page describes the device and words the home toggle by state`() {
        val device = DeviceInfo(
            manufacturer = "SberDevices", model = "SaluteTV", androidRelease = "13", sdkInt = 33, widthPx = 1920, heightPx = 1080,
            densityDpi = 320, totalRamMb = 2048, launcherVersion = "0.1.0", currentHomePackage = "ru.sberdevices.starlauncher",
        )
        val byId = rows(SettingsPageId.System, ui(device = device)).associateBy { it.id }
        assertEquals("SberDevices SaluteTV", byId.getValue("model").value)
        assertEquals("13 (API 33)", byId.getValue("android").value)
        assertEquals("1920×1080 · 320 dpi", byId.getValue("display").value)
        assertEquals("2,0 ГБ", byId.getValue("memory").value)
        assertEquals("0.1.0", byId.getValue("version").value)
        assertEquals("ru.sberdevices.starlauncher", byId.getValue("home").value)
        assertEquals("Сделать домашним экраном", byId.getValue("home_toggle").title)
        assertTrue(listOf("model", "android", "display", "memory", "version", "home").all { byId.getValue(it).kind == RowKind.Info })
        assertTrue(listOf("system_settings", "home_toggle", "diagnostics").all { byId.getValue(it).kind == RowKind.Action })

        val asHome = rows(SettingsPageId.System, ui(device = device.copy(isDefaultHome = true, homeAliasEnabled = true))).associateBy { it.id }
        assertEquals("Mono TV", asHome.getValue("home").value)
        assertEquals("Вернуть стандартный домашний экран", asHome.getValue("home_toggle").title)
        val unknown = rows(SettingsPageId.System, ui()).associateBy { it.id }
        assertEquals("Не определён", unknown.getValue("home").value)
    }
}
