package tv.monotv.launcher.data.apps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.monotv.launcher.core.KnownApp
import tv.monotv.launcher.core.LauncherConfig
import java.io.File

/** The pure JSON parser and the bundled `launcher-config.json` itself (plain JVM, no Android runtime). */
class LauncherConfigParserTest {

    // -----------------------------------------------------------------------------------------------------------------
    // Parser
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a complete document is parsed field by field`() {
        val config = LauncherConfigLoader.parse(
            """
            {
              "version": 3,
              "dockPriority": ["youtube", "kinopoisk"],
              "knownApps": [
                { "id": "youtube", "title": "YouTube", "packages": ["com.google.android.youtube.tv", "org.smarttube.stable"], "tagline": "Видео" },
                { "id": "kinopoisk", "title": "Кинопоиск", "packages": ["ru.kinopoisk.tv"] }
              ],
              "blockedPackages": ["com.cvte.fac.*", "com.stb.factorymenu"],
              "blockedKeywords": ["factory"],
              "extraLauncherCategories": ["ru.sberdevices.category.STAR_LAUNCHER"]
            }
            """.trimIndent(),
        )
        assertEquals(
            LauncherConfig(
                version = 3,
                dockPriority = listOf("youtube", "kinopoisk"),
                knownApps = listOf(
                    KnownApp("youtube", "YouTube", listOf("com.google.android.youtube.tv", "org.smarttube.stable"), "Видео"),
                    KnownApp("kinopoisk", "Кинопоиск", listOf("ru.kinopoisk.tv"), null),
                ),
                blockedPackages = listOf("com.cvte.fac.*", "com.stb.factorymenu"),
                blockedKeywords = listOf("factory"),
                extraLauncherCategories = listOf("ru.sberdevices.category.STAR_LAUNCHER"),
            ),
            config,
        )
    }

    @Test
    fun `missing fields take their defaults`() {
        assertEquals(LauncherConfig(), LauncherConfigLoader.parse("{}"))
        assertEquals(LauncherConfig(dockPriority = listOf("a")), LauncherConfigLoader.parse("""{"dockPriority":["a"]}"""))
    }

    @Test
    fun `unknown fields are ignored everywhere`() {
        val config = LauncherConfigLoader.parse(
            """
            {
              "_note": "h = store page, m = device dump",
              "future": { "nested": [1, 2, 3] },
              "knownApps": [ { "id": "okko", "title": "Okko", "packages": ["ru.more.play"], "_unverified": ["ru.more.play"], "color": "#fff" } ]
            }
            """.trimIndent(),
        )
        assertEquals(listOf(KnownApp("okko", "Okko", listOf("ru.more.play"))), config.knownApps)
        assertEquals(1, config.version)
    }

    @Test
    fun `text that is not a json object yields an empty config and a failure to report`() {
        for (text in listOf("", "   ", "not json", "[1, 2]", "{ \"version\": ", "42", "null")) {
            assertEquals("input: $text", LauncherConfig(), LauncherConfigLoader.parse(text))
            assertTrue("input: $text", LauncherConfigLoader.tryParse(text).isFailure)
        }
        assertTrue(LauncherConfigLoader.tryParse("{}").isSuccess)
    }

    @Test
    fun `mistyped fields fall back to defaults instead of failing`() {
        val config = LauncherConfigLoader.parse(
            """
            {
              "version": "new",
              "dockPriority": "youtube",
              "knownApps": { "id": "youtube" },
              "blockedPackages": [1, true, null, "com.stb.factorymenu", {"a": 1}],
              "blockedKeywords": 7,
              "extraLauncherCategories": [["nested"]]
            }
            """.trimIndent(),
        )
        assertEquals(LauncherConfig(blockedPackages = listOf("com.stb.factorymenu")), config)
    }

    @Test
    fun `broken known apps are skipped and sloppy ones are tidied`() {
        val config = LauncherConfigLoader.parse(
            """
            {
              "knownApps": [
                "youtube",
                { "title": "No id" },
                { "id": "  ", "title": "Blank id" },
                { "id": 5, "title": "Numeric id" },
                { "id": " plex ", "packages": [" com.plexapp.android ", "", 3, "com.plexapp.android"], "tagline": null },
                { "id": "kodi", "title": "   ", "packages": "org.xbmc.kodi", "tagline": "  " },
                { "id": "plex", "title": "Duplicate id loses" }
              ]
            }
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                KnownApp("plex", "plex", listOf("com.plexapp.android"), null),
                KnownApp("kodi", "kodi", emptyList(), null),
            ),
            config.knownApps,
        )
    }

    @Test
    fun `string lists are trimmed and de-duplicated in order`() {
        val config = LauncherConfigLoader.parse("""{"dockPriority": [" b ", "a", "b", "", "c", "a"]}""")
        assertEquals(listOf("b", "a", "c"), config.dockPriority)
    }

    @Test
    fun `keywords are lower-cased because packages are matched in lower case`() {
        val config = LauncherConfigLoader.parse("""{"blockedKeywords": ["Factory", "FACTORY", "EngMode"]}""")
        assertEquals(listOf("factory", "engmode"), config.blockedKeywords)
        assertTrue(config.isBlocked("com.Vendor.FactoryTools"))
    }

    @Test
    fun `a byte-order mark in front of the document is tolerated`() {
        val bom = 0xFEFF.toChar().toString()
        val config = LauncherConfigLoader.parse(bom + """{"version": 2}""")
        assertEquals(2, config.version)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Safety floor
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the safety floor blocks factory menus even for an empty config`() {
        val bare = LauncherConfigLoader.parse("{}")
        assertFalse(bare.isBlocked("com.cvte.fac.menu"))

        val safe = LauncherConfigLoader.withSafetyFloor(bare)
        for (packageName in factoryPackages) assertTrue(packageName, safe.isBlocked(packageName))
        for (packageName in harmlessPackages) assertFalse(packageName, safe.isBlocked(packageName))
    }

    @Test
    fun `the safety floor keeps the user's own entries and is idempotent`() {
        val custom = LauncherConfig(
            dockPriority = listOf("plex"),
            blockedPackages = listOf("com.example.annoying", "com.stb.factorymenu"),
            blockedKeywords = listOf("bloatware", "factory"),
        )
        val safe = LauncherConfigLoader.withSafetyFloor(custom)
        assertEquals(listOf("plex"), safe.dockPriority)
        assertEquals(listOf("com.example.annoying", "com.stb.factorymenu"), safe.blockedPackages.take(2))
        assertEquals(listOf("bloatware", "factory"), safe.blockedKeywords.take(2))
        assertTrue(safe.blockedPackages.containsAll(LauncherConfigLoader.MANDATORY_BLOCKED_PACKAGES))
        assertTrue(safe.blockedKeywords.containsAll(LauncherConfigLoader.MANDATORY_BLOCKED_KEYWORDS))
        assertEquals(safe.blockedPackages.size, safe.blockedPackages.toSet().size)
        assertEquals(safe.blockedKeywords.size, safe.blockedKeywords.toSet().size)
        assertTrue(safe.isBlocked("com.example.annoying"))
        assertTrue(safe.isBlocked("org.vendor.bloatware.store"))
        assertEquals(safe, LauncherConfigLoader.withSafetyFloor(safe))
    }

    @Test
    fun `engmenu is not a blocked keyword because Projectivy is a normal app`() {
        assertFalse("engmenu" in LauncherConfigLoader.MANDATORY_BLOCKED_KEYWORDS)
        assertFalse(LauncherConfigLoader.withSafetyFloor(LauncherConfig()).isBlocked("com.spocky.projengmenu"))
        // The one real "engmenu" on the list is blocked by its exact name instead.
        assertTrue(LauncherConfigLoader.withSafetyFloor(LauncherConfig()).isBlocked("com.rtk.engmenu"))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Bundled assets/launcher-config.json
    // -----------------------------------------------------------------------------------------------------------------

    private val bundledText: String by lazy { bundledFile().readText(Charsets.UTF_8) }
    private val bundled: LauncherConfig by lazy { LauncherConfigLoader.tryParse(bundledText).getOrThrow() }

    /** Unit tests run with the module directory as working directory; the fallback covers a run from the repo root. */
    private fun bundledFile(): File =
        listOf("src/main/assets/launcher-config.json", "app/src/main/assets/launcher-config.json")
            .map(::File)
            .first { it.isFile }

    @Test
    fun `bundled config parses and declares version 1`() {
        assertEquals(1, bundled.version)
        assertTrue(bundled.knownApps.isNotEmpty())
        assertEquals(listOf("ru.sberdevices.category.STAR_LAUNCHER"), bundled.extraLauncherCategories)
    }

    @Test
    fun `bundled dock priority starts with the five required services, then the russian ones`() {
        assertEquals(listOf("youtube", "netflix", "hbomax", "appletv", "spotify"), bundled.dockPriority.take(5))
        assertEquals(
            listOf("kinopoisk", "okko", "wink", "ivi", "rutube", "vkvideo", "kion", "premier", "start", "zvuk"),
            bundled.dockPriority.subList(5, 15),
        )
    }

    @Test
    fun `bundled dock priority and known apps describe the same ids exactly once`() {
        val ids = bundled.knownApps.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(bundled.dockPriority.size, bundled.dockPriority.toSet().size)
        assertEquals(ids.toSet(), bundled.dockPriority.toSet())
        assertTrue(ids.all { id -> id.isNotEmpty() && id.all { it in 'a'..'z' || it in '0'..'9' } })
    }

    @Test
    fun `bundled known apps are exactly the researched package ids, tv builds first`() {
        val expected = linkedMapOf(
            "youtube" to listOf(
                "com.google.android.youtube.tv", "ru.sberdevices.startube", "org.smarttube.stable", "org.smarttube.beta",
                "app.smarttube.fdroid", "com.teamsmart.videomanager.tv", "com.liskovsoft.smarttubetv.beta",
                "com.amazon.firetv.youtube",
            ),
            "netflix" to listOf("com.netflix.ninja", "com.netflix.mediaclient"),
            "hbomax" to listOf("com.wbd.stream", "com.hbo.hbonow"),
            "appletv" to listOf(
                "com.apple.atve.androidtv.appletv", "com.apple.atve.sony.appletv", "com.apple.atve.amazon.appletv",
            ),
            "spotify" to listOf("com.spotify.tv.android", "com.spotify.music"),
            "kinopoisk" to listOf("ru.kinopoisk.tv", "ru.kinopoisk"),
            "okko" to listOf("tv.okko.androidtv", "ru.more.play"),
            "wink" to listOf("ru.rt.video.app.tv", "ru.rt.video.app.mobile"),
            "ivi" to listOf("ru.ivi.client"),
            "rutube" to listOf("ru.rutube.app.tv", "ru.rutube.app"),
            "vkvideo" to listOf("com.vk.tv", "com.vk.vkvideo"),
            "kion" to listOf("ru.mts.mtstv"),
            "premier" to listOf("one.premier.rustoretv", "gpm.tnt_premier"),
            "start" to listOf("ru.start.androidtv", "ru.start.androidmobile"),
            "amediateka" to listOf("ru.amediateka", "com.amdteka"),
            "tricolor" to listOf("com.gsgroup.tricoloronline"),
            "smotreshka" to listOf("tv.lfstrm.smotreshka_stb", "tv.lfstrm.smotreshka"),
            "limehd" to listOf("tv.limehd.stb", "limehd.ru.ctv.tv", "com.infolink.limeiptv"),
            "peerstv" to listOf("ru.cn.tv"),
            "zvuk" to listOf("ru.sberdevices.music", "com.zvooq.openplay"),
            "yandexmusic" to listOf("ru.yandex.music"),
            "sbertv" to listOf("ru.sberdevices.starlivetv"),
            "sbervideo" to listOf("ru.sberdevices.videos"),
            "sberplay" to listOf("ru.sberdevices.sberplay"),
            "twitch" to listOf("tv.twitch.android.app"),
            "primevideo" to listOf("com.amazon.amazonvideo.livingroom"),
            "disneyplus" to listOf("com.disney.disneyplus"),
            "plex" to listOf("com.plexapp.android"),
            "kodi" to listOf("org.xbmc.kodi"),
            "vlc" to listOf("org.videolan.vlc"),
            "jellyfin" to listOf("org.jellyfin.androidtv"),
            "stremio" to listOf("com.stremio.one"),
            "mxplayer" to listOf("com.mxtech.videoplayer.television"),
            "steamlink" to listOf("com.valvesoftware.steamlink"),
            "geforcenow" to listOf("com.nvidia.geforcenow"),
            "rustore" to listOf("ru.vk.store.tv"),
        )
        assertEquals(expected, bundled.knownApps.associate { it.id to it.packages })

        val allPackages = bundled.knownApps.flatMap { it.packages }
        assertEquals("a package may belong to one known app only", allPackages.size, allPackages.toSet().size)
        assertEquals("youtube", bundled.knownIdFor("ru.sberdevices.startube"))
        assertEquals("okko", bundled.knownIdFor("ru.more.play"))
        assertNull(bundled.knownIdFor("com.spocky.projengmenu"))
    }

    @Test
    fun `bundled known apps have usable titles and short taglines`() {
        for (app in bundled.knownApps) {
            assertTrue("title of ${app.id}", app.title.isNotBlank() && app.title != app.id)
            val tagline = app.tagline ?: continue
            assertTrue("tagline of ${app.id}: $tagline", tagline.length in 8..48)
            assertTrue("tagline of ${app.id} must be Russian: $tagline", tagline.any { it in 'а'..'я' || it in 'А'..'Я' })
            assertFalse("tagline of ${app.id} is not a slogan: $tagline", tagline.endsWith("!"))
        }
        assertTrue(bundled.knownApps.count { it.tagline != null } >= bundled.knownApps.size - 2)
    }

    @Test
    fun `bundled blocklist is the mandatory blocklist, verbatim`() {
        assertEquals(LauncherConfigLoader.MANDATORY_BLOCKED_PACKAGES, bundled.blockedPackages)
        assertEquals(LauncherConfigLoader.MANDATORY_BLOCKED_KEYWORDS, bundled.blockedKeywords)
        assertEquals(27, bundled.blockedPackages.size)
        assertEquals(
            listOf("factory", "factorytest", "engineermode", "engmode", "servicemenu", "hwtest", "burnin", "agingtest", "producttest"),
            bundled.blockedKeywords,
        )
    }

    @Test
    fun `bundled config blocks factory and engineering tools`() {
        for (packageName in factoryPackages) assertTrue(packageName, bundled.isBlocked(packageName))
    }

    @Test
    fun `bundled config blocks no ordinary app, known app or sber system package`() {
        for (packageName in harmlessPackages + bundled.knownApps.flatMap { it.packages }) {
            assertFalse(packageName, bundled.isBlocked(packageName))
        }
    }

    @Test
    fun `bundled document carries a note and no known app the parser had to drop`() {
        assertTrue(bundledText.contains("\"_note\""))
        // A typo in an "id" or "packages" field would make the lenient parser skip the entry without a word.
        assertEquals(36, bundled.knownApps.size)
        assertTrue(bundled.knownApps.all { it.packages.isNotEmpty() })
    }

    private val factoryPackages = listOf(
        "com.cvte.fac.menu",
        "com.cvte.fac.anything.else",
        "com.cvte.tv.hkcfac",
        "com.stb.factorymenu",
        "ru.sberdevices.TvFactoryHelper",
        "mediatek.factorymenu.ui",
        "com.mediatek.tv.factory",
        "com.mediatek.testpq",
        "com.mediatek.factorymode",
        "com.mediatek.factorymode.extra",
        "com.mediatek.engineermode",
        "com.mediatek.engineermode2",
        "com.at.factory.factorymanager",
        "com.tcl.factory.view",
        "com.homwee.factory",
        "com.smartdevice.factory",
        "com.duokan.factorytest",
        "com.hisense.autotest",
        "com.realtek.factorytools",
        "com.rtk.engmenu",
        "com.apps.factory.ui",
        "com.realtek.servicemenu",
        "com.toptech.tvfactory",
        "com.sdt.producttest",
        "com.sdmc.facTest",
        "com.utsmta.app",
        "com.droidlogic.readlog",
        "com.droidlogic.factory",
        "com.droidlogic.factorytool",
        "com.fengmi.testsuite",
        "com.android.factorytest",
        // Never seen, caught by keyword — in any letter case.
        "com.unknownvendor.EngineerMode",
        "com.unknownvendor.tv.EngMode.ui",
        "xyz.board.HwTest",
        "com.vendor.burnin",
        "com.vendor.agingtest.app",
        "com.vendor.ServiceMenu",
        "com.vendor.producttest2",
    )

    private val harmlessPackages = listOf(
        "com.spocky.projengmenu",
        "tv.monotv.launcher",
        "ru.sberdevices.assistant",
        "ru.sberdevices.services",
        "ru.sberdevices.services.core",
        "ru.sberdevices.SberDevicesServiceSystem",
        "ru.sberdevices.starmarket",
        "ru.sberdevices.settings",
        "com.sberbank.settings",
        "com.android.tv.settings",
        "ru.sberdevices.updater",
        "ru.sberdevices.tvinputs",
        "ru.sberdevices.starlivetv",
        "ru.sberdevices.app.starkeyboard",
        "ru.sberdevices.webview",
        "ru.sberdevices.systemui",
        "ru.sberdevices.starprofile",
        "ru.sberdevices.starscreensaver",
        "ru.sberdevices.rcfupdater",
        "ru.sberdevices.gamepad.service",
        "ru.sberdevices.startube",
        "ru.sberdevices.starbrowser",
        "ru.sberdevices.tv",
        "ru.sberdevices.starlauncher",
        "com.droidlogic.tvinput",
        "com.mediatek.tvinput",
        "com.mediatek.tv.service",
        "com.cvte.tv.something",
        "org.videolan.vlc",
    )
}
