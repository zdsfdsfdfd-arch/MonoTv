package tv.monotv.launcher.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.monotv.launcher.core.LauncherUiState
import tv.monotv.launcher.nav.HomeNav
import tv.monotv.launcher.nav.NavState
import tv.monotv.launcher.nav.RowKind
import tv.monotv.launcher.nav.Section
import tv.monotv.launcher.nav.SettingsPageId
import tv.monotv.launcher.nav.SettingsRow

/**
 * Renders the real launcher UI to PNG on the JVM (Robolectric native graphics + Roborazzi) — the stand-in for
 * `adb exec-out screencap` while no TV is attached. Record with `./gradlew :app:recordRoborazziDebug`; images land in
 * app/build/screenshots/.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-xhdpi")
class ScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    private val loader = FakeArtworkLoader(ApplicationProvider.getApplicationContext()).also { SampleData.install(it) }

    private val sampleRows: (SettingsPageId) -> List<SettingsRow> = { page ->
        when (page) {
            SettingsPageId.Root -> listOf(
                SettingsRow("appearance", "Оформление", kind = RowKind.Navigate(SettingsPageId.Appearance), description = "Тема, обои, акцент, анимации"),
                SettingsRow("dock", "Dock", kind = RowKind.Navigate(SettingsPageId.Dock), description = "Состав и размер"),
                SettingsRow("apps", "Приложения", kind = RowKind.Navigate(SettingsPageId.Apps), description = "Показ и порядок"),
                SettingsRow("system", "Система", kind = RowKind.Navigate(SettingsPageId.System), description = "Устройство и версия"),
            )
            SettingsPageId.Appearance -> listOf(
                SettingsRow("theme", "Тёмная тема", value = "Чёрная", kind = RowKind.Navigate(SettingsPageId.Theme)),
                SettingsRow("wallpaper", "Обои", value = "Динамические", kind = RowKind.Navigate(SettingsPageId.Wallpaper)),
                SettingsRow("accent", "Акцентный цвет", value = "Изумруд", kind = RowKind.Navigate(SettingsPageId.Accent)),
                SettingsRow("animation", "Анимации", value = "Авто · полные", kind = RowKind.Navigate(SettingsPageId.Animation)),
                SettingsRow("demo", "Демо-контент", value = "Выкл", kind = RowKind.Action, description = "Примеры карточек для проверки интерфейса"),
            )
            SettingsPageId.Accent -> tv.monotv.launcher.core.AccentColor.entries.map {
                SettingsRow("accent:${it.name}", it.title, kind = RowKind.Action, checked = it == tv.monotv.launcher.core.AccentColor.Emerald)
            }
            SettingsPageId.DockOrder -> SampleData.dock().map {
                SettingsRow("app:${it.key.flatten()}", it.label, kind = RowKind.Reorderable, appKey = it.key)
            }
            SettingsPageId.AppsVisibility -> SampleData.allApps.mapIndexed { i, it ->
                SettingsRow("app:${it.key.flatten()}", it.label, kind = RowKind.Action, appKey = it.key, checked = i % 4 != 3)
            }
            else -> emptyList()
        }
    }

    private fun overlay(vararg overlays: tv.monotv.launcher.nav.Overlay, section: Section = Section.TopBar) =
        SampleData.state().copy(nav = NavState(home = HomeNav(section = section), overlays = overlays.toList()))

    private fun settings(vararg frames: tv.monotv.launcher.nav.SettingsFrame) =
        overlay(tv.monotv.launcher.nav.Overlay.Settings(frames.toList()))

    private fun shot(name: String, state: LauncherUiState) {
        rule.setContent { LauncherScreen(state, loader, sampleRows) }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    private fun home(section: Section, block: HomeNav.() -> HomeNav = { this }) =
        SampleData.state().copy(nav = NavState(home = HomeNav(section = section).block()))

    @Test fun home_dock() = shot("01_home_dock", home(Section.Dock))

    @Test fun home_dock_third() = shot("02_home_dock_third", home(Section.Dock) { copy(dockIndex = 2) })

    @Test fun home_hero() = shot("03_home_hero", home(Section.Hero) { copy(heroIndex = 1) })

    @Test fun home_hero_app() = shot("04_home_hero_app", home(Section.Hero) { copy(heroIndex = 2) })

    @Test fun home_topbar() = shot("05_home_topbar", home(Section.TopBar) { copy(topBarIndex = 1) })

    @Test fun library_continue() = shot("06_library_continue", home(Section.Continue) { copy(continueIndex = 1) })

    @Test fun library_apps() = shot("07_library_apps", home(Section.Apps) { copy(appsIndex = 1) })

    @Test fun library_apps_deep() = shot("08_library_apps_deep", home(Section.Apps) { copy(appsIndex = 17) })

    @Test fun inputs_menu() = shot("09_inputs", overlay(tv.monotv.launcher.nav.Overlay.Inputs(index = 0)).let { it.copy(nav = it.nav.copy(home = it.nav.home.copy(topBarIndex = 1))) })

    @Test fun settings_root() = shot("10_settings_root", settings(tv.monotv.launcher.nav.SettingsFrame(SettingsPageId.Root, 0)))

    @Test fun settings_appearance() = shot(
        "11_settings_appearance",
        settings(tv.monotv.launcher.nav.SettingsFrame(SettingsPageId.Root, 0), tv.monotv.launcher.nav.SettingsFrame(SettingsPageId.Appearance, 2)),
    )

    @Test fun settings_accent() = shot(
        "12_settings_accent",
        settings(
            tv.monotv.launcher.nav.SettingsFrame(SettingsPageId.Root, 0), tv.monotv.launcher.nav.SettingsFrame(SettingsPageId.Appearance, 2),
            tv.monotv.launcher.nav.SettingsFrame(SettingsPageId.Accent, 3),
        ),
    )

    @Test fun settings_dock_order_grabbed() = shot(
        "13_settings_dock_order",
        settings(
            tv.monotv.launcher.nav.SettingsFrame(SettingsPageId.Root, 1), tv.monotv.launcher.nav.SettingsFrame(SettingsPageId.Dock, 2),
            tv.monotv.launcher.nav.SettingsFrame(SettingsPageId.DockOrder, 2, grabbed = true),
        ),
    )

    @Test fun settings_apps_visibility() = shot(
        "14_settings_apps_visibility",
        settings(
            tv.monotv.launcher.nav.SettingsFrame(SettingsPageId.Root, 2), tv.monotv.launcher.nav.SettingsFrame(SettingsPageId.Apps, 0),
            tv.monotv.launcher.nav.SettingsFrame(SettingsPageId.AppsVisibility, 9),
        ),
    )

    @Test fun app_menu() = shot(
        "15_app_menu",
        overlay(
            tv.monotv.launcher.nav.Overlay.AppMenu(SampleData.allApps[1].key, tv.monotv.launcher.nav.AppMenuItem.entries.filter { it != tv.monotv.launcher.nav.AppMenuItem.AddToDock }, 1),
            section = Section.Dock,
        ).let { it.copy(nav = it.nav.copy(home = it.nav.home.copy(dockIndex = 1))) },
    )

    @Test fun confirm() = shot(
        "16_confirm",
        overlay(
            tv.monotv.launcher.nav.Overlay.Settings(listOf(tv.monotv.launcher.nav.SettingsFrame(SettingsPageId.Root, 3))),
            tv.monotv.launcher.nav.Overlay.Confirm(tv.monotv.launcher.nav.ConfirmId.EnableHome, 1),
        ),
    )

    @Test fun dock_reorder() = shot(
        "17_dock_reorder",
        SampleData.state().copy(nav = NavState(home = HomeNav(section = Section.Dock, dockIndex = 3), reorder = tv.monotv.launcher.nav.ReorderState(Section.Dock))),
    )

    @Test fun dock_seven() = shot("18_dock_seven", SampleData.state(dockSize = 7).copy(nav = NavState(home = HomeNav(section = Section.Dock, dockIndex = 5))))

    @Test fun dock_four_graphite_amber() = shot(
        "19_dock_four_graphite",
        SampleData.state(
            dockSize = 4,
            settings = tv.monotv.launcher.core.LauncherSettings(
                dockSize = 4, theme = tv.monotv.launcher.core.ThemeMode.Graphite, accent = tv.monotv.launcher.core.AccentColor.Amber,
                wallpaper = tv.monotv.launcher.core.Wallpapers.RIDGE,
            ),
        ).copy(nav = NavState(home = HomeNav(section = Section.Hero, heroIndex = 3))),
    )

    @Test fun toast() = shot("20_toast", home(Section.Dock).copy(toast = "Переключение входа недоступно на этом устройстве"))

    @Test fun loading_skeleton() = shot(
        "21_loading",
        SampleData.state().copy(dock = emptyList(), apps = emptyList(), allApps = emptyList(), continueWatching = emptyList(), hero = emptyList(), loaded = false),
    )

    @Test
    @Config(sdk = [34], qualifiers = "w961dp-h541dp-tvdpi")
    fun hd_720p() = shot("22_720p_tvdpi", home(Section.Dock) { copy(dockIndex = 1) })

    @Test
    @Config(sdk = [34], qualifiers = "w960dp-h540dp-xxxhdpi")
    fun uhd_2160p() = shot("23_2160p", home(Section.Apps) { copy(appsIndex = 7) })

    @Test
    @Config(sdk = [34], qualifiers = "w1280dp-h540dp-xhdpi")
    fun ultrawide() = shot("24_ultrawide", home(Section.Dock) { copy(dockIndex = 2) })
}
