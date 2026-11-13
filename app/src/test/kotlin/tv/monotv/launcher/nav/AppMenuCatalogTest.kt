package tv.monotv.launcher.nav

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.LauncherSettings
import tv.monotv.launcher.core.LauncherUiState
import tv.monotv.launcher.nav.AppMenuItem.AddToDock
import tv.monotv.launcher.nav.AppMenuItem.Hide
import tv.monotv.launcher.nav.AppMenuItem.Info
import tv.monotv.launcher.nav.AppMenuItem.Move
import tv.monotv.launcher.nav.AppMenuItem.Open
import tv.monotv.launcher.nav.AppMenuItem.RemoveFromDock

class AppMenuCatalogTest {
    private fun app(id: String) = AppEntry(
        key = AppKey("pkg.$id", "pkg.$id.Main"), label = id, isLeanback = true, hasBanner = true, isSystem = false,
        knownId = null, versionCode = 1, installedAt = 0,
    )

    private val apps = (1..6).map { app("a$it") }

    private fun ui(dock: Int, dockSize: Int = 5, visible: Int = apps.size) = LauncherUiState(
        dock = apps.take(dock), apps = apps.take(visible), allApps = apps, settings = LauncherSettings(dockSize = dockSize), loaded = true,
    )

    @Test
    fun `dock item can be opened moved and removed`() {
        assertEquals(listOf(Open, Move, RemoveFromDock, Info), AppMenuCatalog.itemsFor(apps[0].key, Section.Dock, ui(dock = 3)))
    }

    @Test
    fun `a lonely dock item has nowhere to move`() {
        assertEquals(listOf(Open, RemoveFromDock, Info), AppMenuCatalog.itemsFor(apps[0].key, Section.Dock, ui(dock = 1)))
    }

    @Test
    fun `grid app outside the dock can be added while there is room and hidden`() {
        assertEquals(listOf(Open, Move, AddToDock, Hide, Info), AppMenuCatalog.itemsFor(apps[5].key, Section.Apps, ui(dock = 3)))
    }

    @Test
    fun `a full dock does not offer add`() {
        assertEquals(listOf(Open, Move, Hide, Info), AppMenuCatalog.itemsFor(apps[5].key, Section.Apps, ui(dock = 4, dockSize = 4)))
    }

    @Test
    fun `grid app that is in the dock offers removal`() {
        assertEquals(listOf(Open, Move, RemoveFromDock, Hide, Info), AppMenuCatalog.itemsFor(apps[1].key, Section.Apps, ui(dock = 3)))
    }

    @Test
    fun `single grid app cannot be moved`() {
        assertEquals(listOf(Open, AddToDock, Hide, Info), AppMenuCatalog.itemsFor(apps[0].key, Section.Apps, ui(dock = 0, visible = 1)))
    }
}
