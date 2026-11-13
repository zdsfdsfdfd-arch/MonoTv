package tv.monotv.launcher.data.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.monotv.launcher.ContentPlanner
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.ContinueItem
import tv.monotv.launcher.core.HeroItem
import tv.monotv.launcher.core.HeroKind
import tv.monotv.launcher.core.KnownApp
import tv.monotv.launcher.core.LauncherConfig
import tv.monotv.launcher.core.RecentLaunch
import tv.monotv.launcher.core.Wallpapers

class ContentComposerTest {
    private val youtube = app("com.google.android.youtube.tv", "YouTube", knownId = "youtube")
    private val netflix = app("com.netflix.ninja", "Netflix")
    private val hbo = app("com.wbd.stream", "HBO Max")
    private val appleTv = app("com.apple.atve.androidtv.appletv", "Apple TV")
    private val spotify = app("com.spotify.tv.android", "Spotify")
    private val kinopoisk = app("ru.kinopoisk.tv", "Кинопоиск", knownId = "kinopoisk")
    private val okko = app("ru.more.play", "Okko")
    private val wink = app("ru.rt.video.app.tv", "Wink")
    private val briefDock = listOf(youtube, netflix, hbo, appleTv, spotify)
    private val allApps = briefDock + listOf(kinopoisk, okko, wink)

    private val config = LauncherConfig(
        knownApps = listOf(
            KnownApp("youtube", "YouTube", listOf(youtube.key.packageName), tagline = "Видео, музыка и прямые эфиры."),
            KnownApp("kinopoisk", "Кинопоиск", listOf(kinopoisk.key.packageName)),
        ),
    )

    private fun app(pkg: String, label: String, knownId: String? = null, activity: String = "$pkg.Main") = AppEntry(
        key = AppKey(pkg, activity), label = label, isLeanback = true, hasBanner = true, isSystem = false,
        knownId = knownId, versionCode = 1, installedAt = 0,
    )

    private fun watchNext(id: Long, app: AppEntry, progress: Float? = 0.5f) = ContinueItem(
        id = "wn:$id", title = "Фильм $id", subtitle = "Серия $id", art = ArtSource.LocalUri("content://posters/$id"),
        progress = progress, appKey = app.key, intentUri = "intent:#Intent;S.id=$id;end",
    )

    private fun recent(app: AppEntry, at: Long) = RecentLaunch(app.key, lastUsedAt = at, launchCount = 1)

    private fun wallpaperOf(item: HeroItem): String = (item.art as ArtSource.Wallpaper).id

    // ---- continue watching -----------------------------------------------------------------------------------------

    @Test
    fun `watch next items lead in the order they were given`() {
        val watchNext = listOf(watchNext(7, kinopoisk), watchNext(3, okko, progress = null))
        val result = ContentComposer.continueItems(watchNext, listOf(recent(youtube, 100)), allApps, demo = false)
        assertEquals(watchNext, result.take(2))
        assertEquals(listOf("wn:7", "wn:3", "recent:${youtube.key.flatten()}"), result.map { it.id })
    }

    @Test
    fun `a recently opened app becomes an honest card without progress`() {
        val result = ContentComposer.continueItems(emptyList(), listOf(recent(youtube, 100)), allApps, demo = false)
        assertEquals(
            listOf(
                ContinueItem(
                    id = "recent:com.google.android.youtube.tv/com.google.android.youtube.tv.Main",
                    title = "YouTube",
                    subtitle = "Недавно открывали",
                    art = ArtSource.AppBanner(youtube.key),
                    progress = null,
                    appKey = youtube.key,
                    intentUri = null,
                ),
            ),
            result,
        )
    }

    @Test
    fun `fallback skips apps that are gone or already have a watch next entry`() {
        val uninstalled = app("com.example.gone", "Gone")
        val kinopoiskOtherActivity = RecentLaunch(AppKey(kinopoisk.key.packageName, "ru.kinopoisk.tv.Other"), 400, 1)
        val recents = listOf(
            recent(kinopoisk, 500), // represented by wn:1
            kinopoiskOtherActivity, // not a listed activity at all
            recent(uninstalled, 300),
            recent(netflix, 200),
        )
        val result = ContentComposer.continueItems(listOf(watchNext(1, kinopoisk)), recents, allApps, demo = false)
        assertEquals(listOf("wn:1", "recent:${netflix.key.flatten()}"), result.map { it.id })
    }

    @Test
    fun `an app with a watch next entry is represented whichever of its activities was launched`() {
        val second = app(kinopoisk.key.packageName, "Кинопоиск Детям", activity = "ru.kinopoisk.tv.Kids")
        val result = ContentComposer.continueItems(
            listOf(watchNext(1, kinopoisk)), listOf(recent(second, 10)), allApps + second, demo = false,
        )
        assertEquals(listOf("wn:1"), result.map { it.id })
    }

    @Test
    fun `fallback is most recent first and capped at six`() {
        val many = (1..9).map { app("com.example.app$it", "App $it") }
        // Deliberately not sorted: the composer must not depend on the caller's order.
        val recents = many.mapIndexed { index, app -> recent(app, at = if (index % 2 == 0) 1000L + index else 2000L + index) }
        val result = ContentComposer.continueItems(emptyList(), recents, many, demo = false)
        assertEquals(ContentComposer.MAX_RECENT_FALLBACK, result.size)
        val expected = recents.sortedByDescending { it.lastUsedAt }.take(6).map { "recent:${it.key.flatten()}" }
        assertEquals(expected, result.map { it.id })
        assertTrue(result.all { it.progress == null })
    }

    @Test
    fun `demo cards are appended after real ones and only on request`() {
        val watchNext = listOf(watchNext(1, kinopoisk))
        val recents = listOf(recent(youtube, 100))
        val without = ContentComposer.continueItems(watchNext, recents, allApps, demo = false)
        assertTrue(without.none { it.id.startsWith(DemoContent.ID_PREFIX) })

        val with = ContentComposer.continueItems(watchNext, recents, allApps, demo = true)
        assertEquals(without + DemoContent.continueItems, with)
    }

    @Test
    fun `continue row has unique ids and never exceeds max`() {
        val duplicated = listOf(watchNext(1, kinopoisk), watchNext(1, kinopoisk), watchNext(2, okko))
        val recents = listOf(recent(youtube, 3), recent(youtube, 2), recent(netflix, 1))
        val result = ContentComposer.continueItems(duplicated, recents, allApps, demo = true, max = 100)
        assertEquals(result.map { it.id }.toSet().size, result.size)
        assertEquals(2 + 2 + DemoContent.continueItems.size, result.size)

        val cut = ContentComposer.continueItems(duplicated, recents, allApps, demo = true, max = 3)
        assertEquals(listOf("wn:1", "wn:2", "recent:${youtube.key.flatten()}"), cut.map { it.id })
        assertTrue(ContentComposer.continueItems(duplicated, recents, allApps, demo = true, max = 0).isEmpty())
    }

    @Test
    fun `default continue row holds ten cards`() {
        val watchNext = (1L..14L).map { watchNext(it, kinopoisk) }
        val result = ContentComposer.continueItems(watchNext, emptyList(), allApps, demo = false)
        assertEquals(watchNext.take(10), result)
    }

    // ---- hero ------------------------------------------------------------------------------------------------------

    @Test
    fun `hero promotes at most two watch next entries that have real progress`() {
        val recentCard = ContentComposer.continueItems(emptyList(), listOf(recent(youtube, 1)), allApps, demo = false).single()
        val continueItems = listOf(
            watchNext(1, kinopoisk, progress = null), // unknown position: nothing to continue
            recentCard,
            watchNext(2, okko, progress = 0.4f),
            ContinueItem("demo:continue:x", "Демо", null, ArtSource.Wallpaper(Wallpapers.TIDE), 0.9f, null, null),
            watchNext(3, wink, progress = 0f),
            watchNext(4, kinopoisk, progress = 0.7f),
        )
        val hero = ContentComposer.heroItems(continueItems, dock = emptyList(), config = config, demo = false)
        assertEquals(
            listOf(
                HeroItem(
                    id = "wn:2", kind = HeroKind.Content, eyebrow = "Продолжить просмотр", title = "Фильм 2",
                    description = "Серия 2", actionLabel = "Продолжить", art = ArtSource.LocalUri("content://posters/2"),
                    appKey = okko.key, intentUri = "intent:#Intent;S.id=2;end", progress = 0.4f,
                ),
                HeroItem(
                    id = "wn:3", kind = HeroKind.Content, eyebrow = "Продолжить просмотр", title = "Фильм 3",
                    description = "Серия 3", actionLabel = "Продолжить", art = ArtSource.LocalUri("content://posters/3"),
                    appKey = wink.key, intentUri = "intent:#Intent;S.id=3;end", progress = 0f,
                ),
            ),
            hero,
        )
    }

    @Test
    fun `every dock app gets a spotlight after the content`() {
        val hero = ContentComposer.heroItems(listOf(watchNext(1, kinopoisk)), listOf(youtube, netflix), config, demo = false)
        assertEquals(listOf("wn:1", "app:${youtube.key.flatten()}", "app:${netflix.key.flatten()}"), hero.map { it.id })
        assertEquals(
            HeroItem(
                id = "app:com.google.android.youtube.tv/com.google.android.youtube.tv.Main",
                kind = HeroKind.App,
                eyebrow = "Приложение",
                title = "YouTube",
                description = "Видео, музыка и прямые эфиры.",
                actionLabel = "Открыть",
                art = ArtSource.Wallpaper(Wallpapers.AURORA),
                appKey = youtube.key,
                intentUri = null,
                progress = null,
            ),
            hero[1],
        )
        assertNull("unknown apps have no tagline", hero[2].description)
    }

    @Test
    fun `a known app without a tagline has no description`() {
        val hero = ContentComposer.heroItems(emptyList(), listOf(kinopoisk), config, demo = false)
        assertNull(hero.single().description)
    }

    @Test
    fun `spotlight backdrop is derived from the package name alone`() {
        // Pinned values: the hash is part of the app's look and must not drift between builds or devices.
        val expected = mapOf(
            youtube to Wallpapers.AURORA,
            netflix to Wallpapers.DUNES,
            hbo to Wallpapers.TIDE,
            kinopoisk to Wallpapers.NEBULA,
            okko to Wallpapers.RIDGE,
            app("com.vk.tv", "VK Видео") to Wallpapers.HORIZON,
        )
        for ((app, wallpaper) in expected) {
            val hero = ContentComposer.heroItems(emptyList(), listOf(app), config, demo = false)
            assertEquals(app.label, wallpaper, wallpaperOf(hero.single()))
        }
        val otherActivity = app(youtube.key.packageName, "YouTube", activity = "com.example.Other")
        val hero = ContentComposer.heroItems(emptyList(), listOf(otherActivity), config, demo = false)
        assertEquals(Wallpapers.AURORA, wallpaperOf(hero.single()))
    }

    @Test
    fun `dock apps that prefer the same backdrop still get different ones`() {
        // YouTube and Apple TV both hash to "aurora", Netflix and Spotify both to "dunes".
        val hero = ContentComposer.heroItems(emptyList(), briefDock, config, demo = false)
        val backdrops = hero.associate { it.title to wallpaperOf(it) }
        assertEquals(
            mapOf(
                "Apple TV" to Wallpapers.AURORA,
                "YouTube" to Wallpapers.DUNES,
                "Netflix" to Wallpapers.HORIZON,
                "Spotify" to Wallpapers.NEBULA,
                "HBO Max" to Wallpapers.TIDE,
            ),
            backdrops,
        )
        val builtIn = Wallpapers.builtIn.map { it.id }.toSet()
        assertTrue(backdrops.values.all { it in builtIn })
    }

    @Test
    fun `rearranging the dock never changes a backdrop`() {
        val reference = ContentComposer.heroItems(emptyList(), briefDock, config, demo = false).associate { it.id to it.art }
        for (order in listOf(briefDock.reversed(), briefDock.sortedBy { it.label }, briefDock.drop(2) + briefDock.take(2))) {
            val hero = ContentComposer.heroItems(emptyList(), order, config, demo = false)
            assertEquals(order.map { "app:${it.key.flatten()}" }, hero.map { it.id })
            assertEquals(reference, hero.associate { it.id to it.art })
        }
    }

    @Test
    fun `more dock apps than wallpapers reuse them evenly`() {
        val many = (1..13).map { "com.example.app$it" }
        val backdrops = ContentComposer.backdrops(many)
        assertEquals(many.toSet(), backdrops.keys)
        val perWallpaper = backdrops.values.groupingBy { it }.eachCount()
        assertEquals(Wallpapers.builtIn.map { it.id }.toSet(), perWallpaper.keys)
        assertTrue("$perWallpaper", perWallpaper.values.all { it in 2..3 })
        assertEquals(backdrops, ContentComposer.backdrops(many.shuffled(java.util.Random(7))))
    }

    @Test
    fun `demo spotlights lead when enabled`() {
        val continueItems = listOf(watchNext(1, kinopoisk), watchNext(2, okko), watchNext(3, wink))
        val hero = ContentComposer.heroItems(continueItems, briefDock, config, demo = true)
        assertEquals(DemoContent.heroItems.map { it.id } + listOf("wn:1", "wn:2"), hero.map { it.id })
        assertEquals(DemoContent.heroItems, hero.take(4))

        val plain = ContentComposer.heroItems(continueItems, briefDock, config, demo = false)
        assertTrue(plain.none { it.kind == HeroKind.Demo })
    }

    @Test
    fun `welcome page stands in when there is nothing to show`() {
        val expected = HeroItem(
            id = "welcome",
            kind = HeroKind.App,
            eyebrow = "Mono TV",
            title = "Добро пожаловать",
            description = "Откройте настройки, чтобы собрать Dock и выбрать обои.",
            actionLabel = "Настройки",
            art = ArtSource.Wallpaper(Wallpapers.AURORA),
            appKey = null,
            intentUri = null,
            progress = null,
        )
        assertEquals(listOf(expected), ContentComposer.heroItems(emptyList(), emptyList(), config, demo = false))
        // Cards without real progress do not count as hero content either.
        val recentOnly = ContentComposer.continueItems(emptyList(), listOf(recent(youtube, 1)), allApps, demo = false)
        assertEquals(listOf(expected), ContentComposer.heroItems(recentOnly, emptyList(), LauncherConfig(), demo = false))
    }

    @Test
    fun `hero has unique ids, respects max and is never empty`() {
        val dock = (1..9).map { app("com.example.app$it", "App $it") }
        val hero = ContentComposer.heroItems(listOf(watchNext(1, kinopoisk)), dock + dock.first(), config, demo = false)
        assertEquals(ContentComposer.DEFAULT_MAX_HERO, hero.size)
        assertEquals(hero.size, hero.map { it.id }.toSet().size)

        val all = ContentComposer.heroItems(listOf(watchNext(1, kinopoisk)), dock + dock.first(), config, demo = false, max = 50)
        assertEquals(1 + dock.size, all.size)

        val one = ContentComposer.heroItems(listOf(watchNext(1, kinopoisk)), dock, config, demo = false, max = 0)
        assertEquals(listOf("wn:1"), one.map { it.id })
    }

    // ---- ViewModel port --------------------------------------------------------------------------------------------

    @Test
    fun `composer is the planner the view model asks for`() {
        val planner: ContentPlanner = ContentComposer
        val watchNext = (1L..12L).map { watchNext(it, kinopoisk) }
        val recents = listOf(recent(youtube, 5))

        val continueItems = planner.continueItems(watchNext, recents, allApps, true)
        assertEquals(ContentComposer.continueItems(watchNext, recents, allApps, demo = true, max = 10), continueItems)

        val hero = planner.heroItems(continueItems, briefDock, config, false)
        assertEquals(ContentComposer.heroItems(continueItems, briefDock, config, demo = false, max = 6), hero)
        assertEquals(6, hero.size)
    }
}
