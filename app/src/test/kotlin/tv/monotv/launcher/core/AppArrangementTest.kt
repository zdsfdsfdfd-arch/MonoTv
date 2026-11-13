package tv.monotv.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AppArrangementTest {

    private fun app(packageName: String, label: String = packageName, leanback: Boolean = true, knownId: String? = null) =
        AppEntry(
            key = AppKey(packageName, "$packageName.Main"),
            label = label,
            isLeanback = leanback,
            hasBanner = leanback,
            isSystem = false,
            knownId = knownId,
            versionCode = 1,
            installedAt = 0,
        )

    private fun used(app: AppEntry, at: Long) = RecentLaunch(app.key, lastUsedAt = at, launchCount = 1)

    private fun List<AppEntry>.packages() = map { it.key.packageName }

    private val youtube = app("com.google.android.youtube.tv", "YouTube")
    private val starTube = app("ru.sberdevices.startube", "Ютуб")
    private val netflix = app("com.netflix.ninja", "Netflix")
    private val netflixPhone = app("com.netflix.mediaclient", "Netflix", leanback = false)
    private val kinopoisk = app("ru.kinopoisk.tv", "Кинопоиск")
    private val okko = app("ru.more.play", "Okko")
    private val browser = app("org.example.browser", "Browser")
    private val files = app("org.example.files", "Files")
    private val gallery = app("org.example.gallery", "Gallery")
    private val settingsApp = app("org.example.settings", "Settings")
    private val phoneGame = app("org.example.game", "Game", leanback = false)

    private val config = LauncherConfig(
        dockPriority = listOf("youtube", "netflix", "ghost", "kinopoisk", "okko"),
        knownApps = listOf(
            KnownApp("youtube", "YouTube", listOf("com.google.android.youtube.tv", "ru.sberdevices.startube")),
            KnownApp("netflix", "Netflix", listOf("com.netflix.ninja", "com.netflix.mediaclient")),
            KnownApp("kinopoisk", "Кинопоиск", listOf("ru.kinopoisk.tv")),
            KnownApp("okko", "Okko", listOf("tv.okko.androidtv", "ru.more.play")),
        ),
    )

    private val auto = LauncherSettings(dockSize = 5)

    // -----------------------------------------------------------------------------------------------------------------
    // dock — automatic
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `automatic dock follows the priority list and skips apps that are not installed`() {
        val all = listOf(okko, browser, kinopoisk, youtube)
        val dock = AppArrangement.dock(all, auto, config, emptyList())
        assertEquals(listOf(youtube, kinopoisk, okko, browser), dock)
    }

    @Test
    fun `automatic dock takes one app per known id, preferring the first listed package`() {
        val dock = AppArrangement.dock(listOf(starTube, youtube, netflix), auto.copy(dockSize = 4), config, emptyList())
        // The second YouTube client is no longer "YouTube" for the priority list; it only fills a free slot at the end.
        assertEquals(listOf(youtube, netflix, starTube), dock)
    }

    @Test
    fun `automatic dock falls back to the next package of a known app`() {
        val dock = AppArrangement.dock(listOf(starTube, okko), auto, config, emptyList())
        assertEquals(listOf(starTube, okko), dock)
    }

    @Test
    fun `automatic dock fills free slots with tv apps by recency and then by label`() {
        val all = listOf(settingsApp, gallery, files, browser, youtube)
        val recents = listOf(used(gallery, at = 50), used(settingsApp, at = 90))
        val dock = AppArrangement.dock(all, auto, config, recents)
        assertEquals(listOf(youtube, settingsApp, gallery, browser, files), dock)
    }

    @Test
    fun `automatic dock sorts unsorted recents itself`() {
        val recents = listOf(used(browser, at = 1), used(files, at = 3), used(gallery, at = 2))
        val dock = AppArrangement.dock(listOf(browser, files, gallery), auto, LauncherConfig(), recents)
        assertEquals(listOf(files, gallery, browser), dock)
    }

    @Test
    fun `automatic dock stops at the dock size`() {
        val all = listOf(youtube, netflix, kinopoisk, okko, browser, files, gallery)
        assertEquals(4, AppArrangement.dock(all, auto.copy(dockSize = 4), config, emptyList()).size)
        assertEquals(7, AppArrangement.dock(all, auto.copy(dockSize = 7), config, emptyList()).size)
        assertEquals(listOf(youtube, netflix, kinopoisk, okko), AppArrangement.dock(all, auto.copy(dockSize = 4), config, emptyList()))
    }

    @Test
    fun `a stored dock size outside the supported range is clamped`() {
        val all = listOf(youtube, netflix, kinopoisk, okko, browser, files, gallery, settingsApp)
        assertEquals(LauncherSettings.MAX_DOCK_SIZE, AppArrangement.dock(all, auto.copy(dockSize = 99), config, emptyList()).size)
        assertEquals(LauncherSettings.MIN_DOCK_SIZE, AppArrangement.dock(all, auto.copy(dockSize = 0), config, emptyList()).size)
    }

    @Test
    fun `automatic dock never shows hidden apps`() {
        val settings = auto.copy(hiddenApps = setOf(youtube.key, browser.key))
        val dock = AppArrangement.dock(listOf(youtube, netflix, browser, files), settings, config, emptyList())
        assertEquals(listOf(netflix, files), dock)
    }

    @Test
    fun `phone apps enter the automatic dock only through the priority list and only when enabled`() {
        val all = listOf(netflixPhone, phoneGame, browser)
        assertEquals(listOf(browser), AppArrangement.dock(all, auto, config, emptyList()))

        val withPhoneApps = auto.copy(showNonTvApps = true)
        // The phone build of a prioritised service is welcome; an arbitrary phone app never fills a free slot.
        assertEquals(listOf(netflixPhone, browser), AppArrangement.dock(all, withPhoneApps, config, emptyList()))
    }

    @Test
    fun `tv build of a known app wins over its phone build`() {
        val settings = auto.copy(showNonTvApps = true)
        val dock = AppArrangement.dock(listOf(netflixPhone, netflix), settings, config, emptyList())
        assertEquals(listOf(netflix), dock)
    }

    @Test
    fun `automatic dock has no duplicates even with a sloppy config`() {
        val sloppy = config.copy(
            dockPriority = listOf("youtube", "youtube", "mirror", "netflix"),
            knownApps = config.knownApps + KnownApp("mirror", "Mirror", listOf("com.google.android.youtube.tv")),
        )
        val all = listOf(youtube, starTube, netflix)
        val dock = AppArrangement.dock(all, auto, sloppy, listOf(used(youtube, at = 5)))
        assertEquals(listOf(youtube, netflix, starTube), dock)
        assertEquals(dock.size, dock.map { it.key }.toSet().size)
    }

    @Test
    fun `dock of nothing is empty`() {
        assertTrue(AppArrangement.dock(emptyList(), auto, config, emptyList()).isEmpty())
        assertTrue(AppArrangement.dock(emptyList(), auto.copy(dockApps = listOf(youtube.key)), config, emptyList()).isEmpty())
    }

    // -----------------------------------------------------------------------------------------------------------------
    // dock — manual
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `manual dock shows exactly the chosen apps in the chosen order without auto-fill`() {
        val settings = auto.copy(dockApps = listOf(files.key, youtube.key))
        val dock = AppArrangement.dock(listOf(youtube, netflix, browser, files), settings, config, listOf(used(netflix, at = 9)))
        assertEquals(listOf(files, youtube), dock)
    }

    @Test
    fun `an empty manual dock stays empty`() {
        val dock = AppArrangement.dock(listOf(youtube, netflix), auto.copy(dockApps = emptyList()), config, emptyList())
        assertTrue(dock.isEmpty())
    }

    @Test
    fun `manual dock drops missing, hidden, duplicate and ineligible apps and is cut to the dock size`() {
        val settings = auto.copy(
            dockSize = 4,
            dockApps = listOf(
                AppKey("gone.app", "gone.app.Main"), phoneGame.key, browser.key, browser.key, netflix.key,
                files.key, gallery.key, youtube.key, settingsApp.key,
            ),
            hiddenApps = setOf(netflix.key),
        )
        val all = listOf(youtube, netflix, browser, files, gallery, settingsApp, phoneGame)
        assertEquals(listOf(browser, files, gallery, youtube), AppArrangement.dock(all, settings, config, emptyList()))

        // Switching phone apps on brings the stored entry back in its place.
        val withPhoneApps = settings.copy(showNonTvApps = true)
        assertEquals(listOf(phoneGame, browser, files, gallery), AppArrangement.dock(all, withPhoneApps, config, emptyList()))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // visibleApps
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `default order is dock, then recently used, then the rest by label`() {
        val all = listOf(settingsApp, gallery, files, browser, netflix, youtube)
        val dock = listOf(netflix, youtube)
        val recents = listOf(used(settingsApp, at = 30), used(files, at = 20), used(netflix, at = 10))
        val visible = AppArrangement.visibleApps(all, auto, dock, recents)
        assertEquals(listOf(netflix, youtube, settingsApp, files, browser, gallery), visible)
    }

    @Test
    fun `labels are sorted case-insensitively with russian collation and a stable tie-break`() {
        val apple = app("z.apple", "apple")
        val banana = app("a.banana", "Banana")
        val cherry = app("m.cherry", "cherry")
        assertEquals(listOf(apple, banana, cherry), AppArrangement.visibleApps(listOf(cherry, banana, apple), auto, emptyList(), emptyList()))

        val ivi = app("ru.ivi.client", "иви")
        val yandex = app("ru.yandex.music", "Яндекс Музыка")
        val kino = app("ru.kinopoisk.tv", "Кинопоиск")
        assertEquals(listOf(ivi, kino, yandex), AppArrangement.visibleApps(listOf(yandex, kino, ivi), auto, emptyList(), emptyList()))

        val twinB = app("b.twin", "Twin")
        val twinA = app("a.twin", "Twin")
        assertEquals(listOf(twinA, twinB), AppArrangement.visibleApps(listOf(twinB, twinA), auto, emptyList(), emptyList()))
        assertEquals(listOf(twinA, twinB), AppArrangement.visibleApps(listOf(twinA, twinB), auto, emptyList(), emptyList()))
    }

    @Test
    fun `visible apps exclude hidden apps and phone apps unless enabled`() {
        val all = listOf(browser, files, phoneGame)
        val settings = auto.copy(hiddenApps = setOf(files.key))
        assertEquals(listOf(browser), AppArrangement.visibleApps(all, settings, emptyList(), emptyList()))
        assertEquals(
            listOf(browser, phoneGame),
            AppArrangement.visibleApps(all, settings.copy(showNonTvApps = true), emptyList(), emptyList()),
        )
    }

    @Test
    fun `a custom order comes first and unlisted apps follow in the default order`() {
        val all = listOf(browser, files, gallery, settingsApp, youtube)
        val settings = auto.copy(
            appOrder = listOf(settingsApp.key, AppKey("gone.app", "gone.app.Main"), browser.key, settingsApp.key),
        )
        val visible = AppArrangement.visibleApps(all, settings, dock = listOf(youtube), recents = listOf(used(gallery, at = 1)))
        assertEquals(listOf(settingsApp, browser, youtube, gallery, files), visible)
    }

    @Test
    fun `a dock app that is not eligible does not leak into the visible apps`() {
        val settings = auto.copy(hiddenApps = setOf(youtube.key))
        val visible = AppArrangement.visibleApps(listOf(youtube, browser), settings, dock = listOf(youtube, browser), recents = emptyList())
        assertEquals(listOf(browser), visible)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Mutations
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `moving a dock item freezes the automatic dock and moves the item`() {
        val dock = listOf(youtube, netflix, kinopoisk)
        val moved = AppArrangement.moveDockItem(auto, dock, from = 0, to = 2)
        assertEquals(listOf(netflix.key, kinopoisk.key, youtube.key), moved.dockApps)
        assertEquals(listOf(kinopoisk.key, youtube.key, netflix.key), AppArrangement.moveDockItem(auto, dock, 2, 0).dockApps)
        assertEquals(listOf(netflix, kinopoisk, youtube), AppArrangement.dock(dock, moved, config, emptyList()))
    }

    @Test
    fun `moving a dock item to the same or an invalid position changes nothing`() {
        val dock = listOf(youtube, netflix)
        assertSame(auto, AppArrangement.moveDockItem(auto, dock, 1, 1))
        assertSame(auto, AppArrangement.moveDockItem(auto, dock, -1, 0))
        assertSame(auto, AppArrangement.moveDockItem(auto, dock, 0, 2))
        assertSame(auto, AppArrangement.moveDockItem(auto, emptyList(), 0, 0))
        assertNull(AppArrangement.moveDockItem(auto, dock, 0, 5).dockApps)
    }

    @Test
    fun `moving an app freezes the visible order and moves the app`() {
        val visible = listOf(browser, files, gallery, settingsApp)
        val moved = AppArrangement.moveApp(auto, visible, from = 3, to = 1)
        assertEquals(listOf(browser.key, settingsApp.key, files.key, gallery.key), moved.appOrder)
        assertEquals(
            listOf(browser, settingsApp, files, gallery),
            AppArrangement.visibleApps(visible, moved, emptyList(), emptyList()),
        )
        assertNull(moved.dockApps)
    }

    @Test
    fun `moving an app to the same or an invalid position changes nothing`() {
        val visible = listOf(browser, files)
        assertSame(auto, AppArrangement.moveApp(auto, visible, 0, 0))
        assertSame(auto, AppArrangement.moveApp(auto, visible, 2, 0))
        assertSame(auto, AppArrangement.moveApp(auto, visible, 0, -1))
        assertSame(auto, AppArrangement.moveApp(auto, emptyList(), 0, 1))
    }

    @Test
    fun `adding to the dock appends and turns the dock manual`() {
        val added = AppArrangement.addToDock(auto, listOf(youtube, netflix), browser.key)
        assertEquals(listOf(youtube.key, netflix.key, browser.key), added.dockApps)
    }

    @Test
    fun `adding to a full dock or adding a member changes nothing`() {
        val full = listOf(youtube, netflix, kinopoisk, okko, browser)
        assertSame(auto, AppArrangement.addToDock(auto, full, files.key))
        assertSame(auto, AppArrangement.addToDock(auto, listOf(youtube), youtube.key))

        val small = auto.copy(dockSize = 4)
        assertSame(small, AppArrangement.addToDock(small, full.take(4), files.key))
    }

    @Test
    fun `removing from the dock keeps the others in order`() {
        val removed = AppArrangement.removeFromDock(auto, listOf(youtube, netflix, browser), netflix.key)
        assertEquals(listOf(youtube.key, browser.key), removed.dockApps)
        assertEquals(emptyList<AppKey>(), AppArrangement.removeFromDock(auto, listOf(youtube), youtube.key).dockApps)
    }

    @Test
    fun `removing an app that is not in the dock changes nothing`() {
        assertSame(auto, AppArrangement.removeFromDock(auto, listOf(youtube), browser.key))
        assertSame(auto, AppArrangement.removeFromDock(auto, emptyList(), browser.key))
    }

    @Test
    fun `dock membership is a checkbox over add and remove`() {
        val dock = listOf(youtube, netflix)
        assertEquals(
            listOf(youtube.key, netflix.key, files.key),
            AppArrangement.setDockMembership(auto, dock, files.key, inDock = true).dockApps,
        )
        assertEquals(listOf(netflix.key), AppArrangement.setDockMembership(auto, dock, youtube.key, inDock = false).dockApps)
        assertSame(auto, AppArrangement.setDockMembership(auto, dock, youtube.key, inDock = true))
        assertSame(auto, AppArrangement.setDockMembership(auto, dock, files.key, inDock = false))
    }

    @Test
    fun `hiding an app removes it from a manual dock`() {
        val manual = auto.copy(dockApps = listOf(youtube.key, netflix.key))
        val hidden = AppArrangement.setHidden(manual, listOf(youtube, netflix), netflix.key, hidden = true)
        assertEquals(setOf(netflix.key), hidden.hiddenApps)
        assertEquals(listOf(youtube.key), hidden.dockApps)

        // Un-hiding does not put it back: the Dock is the user's own decision.
        val shown = AppArrangement.setHidden(hidden, listOf(youtube), netflix.key, hidden = false)
        assertTrue(shown.hiddenApps.isEmpty())
        assertEquals(listOf(youtube.key), shown.dockApps)
    }

    @Test
    fun `hiding an app leaves an automatic dock automatic`() {
        val hidden = AppArrangement.setHidden(auto, listOf(youtube, netflix), youtube.key, hidden = true)
        assertNull(hidden.dockApps)
        assertEquals(setOf(youtube.key), hidden.hiddenApps)
        assertEquals(listOf(netflix), AppArrangement.dock(listOf(youtube, netflix), hidden, config, emptyList()))
    }

    @Test
    fun `hiding a hidden app or showing a visible app changes nothing`() {
        val settings = auto.copy(hiddenApps = setOf(files.key))
        assertSame(settings, AppArrangement.setHidden(settings, emptyList(), files.key, hidden = true))
        assertSame(settings, AppArrangement.setHidden(settings, emptyList(), browser.key, hidden = false))
    }

    @Test
    fun `dock size is clamped and a longer manual dock is truncated`() {
        assertEquals(LauncherSettings.MAX_DOCK_SIZE, AppArrangement.setDockSize(auto, 42).dockSize)
        assertEquals(LauncherSettings.MIN_DOCK_SIZE, AppArrangement.setDockSize(auto, -3).dockSize)
        assertEquals(6, AppArrangement.setDockSize(auto, 6).dockSize)
        assertNull(AppArrangement.setDockSize(auto, 6).dockApps)

        val manual = auto.copy(dockSize = 7, dockApps = listOf(youtube, netflix, kinopoisk, okko, browser, files).map { it.key })
        val smaller = AppArrangement.setDockSize(manual, 4)
        assertEquals(4, smaller.dockSize)
        assertEquals(listOf(youtube, netflix, kinopoisk, okko).map { it.key }, smaller.dockApps)

        val bigger = AppArrangement.setDockSize(smaller, 7)
        assertEquals(smaller.dockApps, bigger.dockApps)
    }

    @Test
    fun `setting the current dock size changes nothing`() {
        assertSame(auto, AppArrangement.setDockSize(auto, auto.dockSize))
        val atMax = auto.copy(dockSize = LauncherSettings.MAX_DOCK_SIZE)
        assertSame(atMax, AppArrangement.setDockSize(atMax, 100))
    }

    @Test
    fun `resetting the dock makes it automatic again`() {
        val manual = auto.copy(dockSize = 6, dockApps = listOf(browser.key))
        val reset = AppArrangement.resetDock(manual)
        assertNull(reset.dockApps)
        assertEquals(6, reset.dockSize)
        assertSame(auto, AppArrangement.resetDock(auto))
    }

    @Test
    fun `resetting apps clears the order and the hidden set only`() {
        val settings = auto.copy(appOrder = listOf(browser.key), hiddenApps = setOf(files.key), dockApps = listOf(youtube.key))
        val reset = AppArrangement.resetApps(settings)
        assertTrue(reset.appOrder.isEmpty())
        assertTrue(reset.hiddenApps.isEmpty())
        assertEquals(listOf(youtube.key), reset.dockApps)
        assertSame(auto, AppArrangement.resetApps(auto))
    }

    @Test
    fun `pruning drops keys of uninstalled apps everywhere`() {
        val gone = AppKey("gone.app", "gone.app.Main")
        val settings = auto.copy(
            dockApps = listOf(youtube.key, gone),
            appOrder = listOf(gone, browser.key, files.key),
            hiddenApps = setOf(gone, files.key),
        )
        val pruned = AppArrangement.pruneMissing(settings, setOf(youtube.key, browser.key, files.key))
        assertEquals(listOf(youtube.key), pruned.dockApps)
        assertEquals(listOf(browser.key, files.key), pruned.appOrder)
        assertEquals(setOf(files.key), pruned.hiddenApps)
    }

    @Test
    fun `pruning keeps an automatic dock automatic and an emptied manual dock manual`() {
        val installed = setOf(browser.key)
        assertNull(AppArrangement.pruneMissing(auto.copy(appOrder = listOf(youtube.key)), installed).dockApps)
        assertEquals(
            emptyList<AppKey>(),
            AppArrangement.pruneMissing(auto.copy(dockApps = listOf(youtube.key)), installed).dockApps,
        )
    }

    @Test
    fun `pruning with nothing to drop or with an empty scan changes nothing`() {
        val settings = auto.copy(dockApps = listOf(youtube.key), appOrder = listOf(browser.key), hiddenApps = setOf(files.key))
        assertSame(settings, AppArrangement.pruneMissing(settings, setOf(youtube.key, browser.key, files.key, gallery.key)))
        // An empty scan result is a failed scan, not a TV without apps.
        assertSame(settings, AppArrangement.pruneMissing(settings, emptySet()))
        assertSame(auto, AppArrangement.pruneMissing(auto, setOf(youtube.key)))
    }

    @Test
    fun `eligibility follows the hidden set and the phone-apps switch`() {
        assertTrue(AppArrangement.isEligible(browser, auto))
        assertFalse(AppArrangement.isEligible(phoneGame, auto))
        assertTrue(AppArrangement.isEligible(phoneGame, auto.copy(showNonTvApps = true)))
        assertFalse(AppArrangement.isEligible(browser, auto.copy(hiddenApps = setOf(browser.key))))
    }
}
