package tv.monotv.launcher.data.settings

import android.content.Context
import android.content.SharedPreferences
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import tv.monotv.launcher.core.AccentColor
import tv.monotv.launcher.core.AnimationLevel
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.LauncherSettings
import tv.monotv.launcher.core.RecentLaunch
import tv.monotv.launcher.core.ThemeMode
import tv.monotv.launcher.core.Wallpapers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PrefsSettingsRepositoryTest {

    private val context: Context get() = RuntimeEnvironment.getApplication()

    private val prefs: SharedPreferences
        get() = context.getSharedPreferences(PrefsSettingsRepository.FILE_NAME, Context.MODE_PRIVATE)

    private val youtube = AppKey("com.google.android.youtube.tv", "com.google.android.apps.youtube.tv.activity.ShellActivity")
    private val okko = AppKey("ru.more.play", "ru.more.play.MainActivity")
    private val kion = AppKey("ru.mts.mtstv", "ru.mts.mtstv.Splash")

    // -----------------------------------------------------------------------------------------------------------------
    // Settings
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a fresh install starts with defaults and no history`() {
        val repository = PrefsSettingsRepository(context)
        assertEquals(LauncherSettings(), repository.settings.value)
        assertEquals(emptyList<RecentLaunch>(), repository.recents.value)
    }

    @Test
    fun `an update is visible as soon as update returns`() {
        val repository = PrefsSettingsRepository(context)
        repository.update { it.copy(theme = ThemeMode.Graphite, dockSize = 7) }
        assertEquals(ThemeMode.Graphite, repository.settings.value.theme)
        assertEquals(7, repository.settings.value.dockSize)
    }

    @Test
    fun `updates build on each other`() {
        val repository = PrefsSettingsRepository(context)
        repository.update { it.copy(accent = AccentColor.Sky) }
        repository.update { it.copy(demoContent = true) }
        assertEquals(LauncherSettings(accent = AccentColor.Sky, demoContent = true), repository.settings.value)
    }

    @Test
    fun `settings survive a restart`() {
        val settings = LauncherSettings(
            dockSize = 6,
            dockApps = listOf(okko, youtube),
            appOrder = listOf(kion, okko, youtube),
            hiddenApps = setOf(kion),
            showNonTvApps = true,
            theme = ThemeMode.Graphite,
            wallpaper = Wallpapers.RIDGE,
            accent = AccentColor.Amber,
            animation = AnimationLevel.Reduced,
            demoContent = true,
        )
        PrefsSettingsRepository(context).update { settings }

        assertEquals(settings, PrefsSettingsRepository(context).settings.value)
    }

    @Test
    fun `the stored form is the codec's flat string map`() {
        PrefsSettingsRepository(context).update { it.copy(dockSize = 6, dockApps = listOf(okko), theme = ThemeMode.Graphite) }

        assertEquals("6", prefs.getString(SettingsCodec.KEY_DOCK_SIZE, null))
        assertEquals(okko.flatten(), prefs.getString(SettingsCodec.KEY_DOCK_APPS, null))
        assertEquals("Graphite", prefs.getString(SettingsCodec.KEY_THEME, null))
        assertEquals(SettingsCodec.decode(prefs.all.mapValues { it.value as? String }), PrefsSettingsRepository(context).settings.value)
    }

    @Test
    fun `an emptied manual dock and the automatic dock are told apart after a restart`() {
        val repository = PrefsSettingsRepository(context)

        repository.update { it.copy(dockApps = emptyList()) }
        assertTrue(prefs.contains(SettingsCodec.KEY_DOCK_APPS))
        assertEquals(emptyList<AppKey>(), PrefsSettingsRepository(context).settings.value.dockApps)

        repository.update { it.copy(dockApps = listOf(youtube)) }
        assertEquals(listOf(youtube), PrefsSettingsRepository(context).settings.value.dockApps)

        repository.update { it.copy(dockApps = null) }
        assertFalse("the key must be removed, not left behind", prefs.contains(SettingsCodec.KEY_DOCK_APPS))
        assertNull(PrefsSettingsRepository(context).settings.value.dockApps)
    }

    @Test
    fun `values are corrected when they are set, not after the next restart`() {
        val repository = PrefsSettingsRepository(context)
        repository.update { it.copy(dockSize = 99, wallpaper = "no-such-wallpaper", appOrder = listOf(okko, okko, youtube)) }

        val expected = LauncherSettings(dockSize = 7, appOrder = listOf(okko, youtube))
        assertEquals(expected, repository.settings.value)
        assertEquals(expected, PrefsSettingsRepository(context).settings.value)
    }

    @Test
    fun `an update that changes nothing keeps the very same value`() {
        val repository = PrefsSettingsRepository(context)
        repository.update { it.copy(theme = ThemeMode.Graphite) }
        val before = repository.settings.value

        repository.update { it }
        repository.update { it.copy(theme = ThemeMode.Graphite) }
        repository.update { it.copy(dockSize = it.dockSize) }

        assertSame(before, repository.settings.value)
    }

    @Test
    fun `a failing transform changes nothing and does not take the launcher down`() {
        val repository = PrefsSettingsRepository(context)
        repository.update { it.copy(accent = AccentColor.Lime) }
        val before = repository.settings.value

        repository.update { error("boom") }
        repository.update { throw IndexOutOfBoundsException("moved an app that is gone") }

        assertSame(before, repository.settings.value)
        assertEquals(LauncherSettings(accent = AccentColor.Lime), PrefsSettingsRepository(context).settings.value)
        // ...and the repository is still usable afterwards.
        repository.update { it.copy(demoContent = true) }
        assertEquals(LauncherSettings(accent = AccentColor.Lime, demoContent = true), PrefsSettingsRepository(context).settings.value)
    }

    @Test
    fun `an update made from inside a collector is persisted after the update that caused it`() {
        val repository = PrefsSettingsRepository(context)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        // An unconfined collector runs inside the assignment that publishes the new value, i.e. inside update().
        val job = scope.launch {
            repository.settings.collect { settings ->
                if (settings.theme == ThemeMode.Graphite && settings.accent != AccentColor.Snow) {
                    repository.update { it.copy(accent = AccentColor.Snow) }
                }
            }
        }

        repository.update { it.copy(theme = ThemeMode.Graphite) }
        job.cancel()

        val expected = LauncherSettings(theme = ThemeMode.Graphite, accent = AccentColor.Snow)
        assertEquals(expected, repository.settings.value)
        assertEquals("the file must end up with the newest state, not the outer update's", expected, PrefsSettingsRepository(context).settings.value)
    }

    @Test
    fun `a damaged store falls back field by field`() {
        prefs.edit()
            .putInt(SettingsCodec.KEY_DOCK_SIZE, 6) // wrong type: written by something that is not this codec
            .putString(SettingsCodec.KEY_THEME, "Graphite")
            .putString(SettingsCodec.KEY_ACCENT, "\u0000garbage")
            .putBoolean(SettingsCodec.KEY_DEMO_CONTENT, true)
            .putString(SettingsCodec.KEY_APP_ORDER, "${okko.flatten()}\nbroken line\n${youtube.flatten()}")
            .putStringSet("recents", setOf("not", "a", "string"))
            .commit()

        val repository = PrefsSettingsRepository(context)

        assertEquals(LauncherSettings(theme = ThemeMode.Graphite, appOrder = listOf(okko, youtube)), repository.settings.value)
        assertEquals(emptyList<RecentLaunch>(), repository.recents.value)

        // Writing over the damaged entries works and heals them.
        repository.update { it.copy(dockSize = 4) }
        repository.recordLaunch(okko, now = 10)
        val reopened = PrefsSettingsRepository(context)
        assertEquals(4, reopened.settings.value.dockSize)
        assertEquals(listOf(RecentLaunch(okko, 10, 1)), reopened.recents.value)
    }

    @Test
    fun `concurrent updates from many threads are all applied and all persisted`() {
        val repository = PrefsSettingsRepository(context)
        val threads = 8
        val perThread = 50
        val startTogether = CountDownLatch(1)
        val failures = java.util.concurrent.ConcurrentLinkedQueue<Throwable>()

        val workers = (0 until threads).map { worker ->
            thread {
                try {
                    startTogether.await()
                    repeat(perThread) { i ->
                        repository.update { it.copy(appOrder = it.appOrder + AppKey("pkg.w$worker", "Activity$i")) }
                        repository.recordLaunch(AppKey("pkg.w$worker", "Main"), now = i.toLong())
                    }
                } catch (failure: Throwable) {
                    failures += failure
                }
            }
        }
        startTogether.countDown()
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(30)) }

        assertEquals(emptyList<Throwable>(), failures.toList())
        val order = repository.settings.value.appOrder
        assertEquals(threads * perThread, order.size)
        assertEquals(threads * perThread, order.toSet().size)
        for (worker in 0 until threads) {
            // Each thread's own additions kept their order: no update was computed from a stale value.
            assertEquals(
                (0 until perThread).map { AppKey("pkg.w$worker", "Activity$it") },
                order.filter { it.packageName == "pkg.w$worker" },
            )
        }
        assertEquals(threads, repository.recents.value.size)
        assertTrue(repository.recents.value.all { it.launchCount == perThread })

        val reopened = PrefsSettingsRepository(context)
        assertEquals(repository.settings.value, reopened.settings.value)
        assertEquals(repository.recents.value, reopened.recents.value)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Recents
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a first launch is recorded at the front with count one`() {
        val repository = PrefsSettingsRepository(context)
        repository.recordLaunch(okko, now = 100)
        repository.recordLaunch(youtube, now = 200)

        assertEquals(listOf(RecentLaunch(youtube, 200, 1), RecentLaunch(okko, 100, 1)), repository.recents.value)
    }

    @Test
    fun `launching again moves the app to the front and counts up`() {
        val repository = PrefsSettingsRepository(context)
        repository.recordLaunch(okko, now = 100)
        repository.recordLaunch(youtube, now = 200)
        repository.recordLaunch(kion, now = 300)
        repository.recordLaunch(okko, now = 400)
        repository.recordLaunch(okko, now = 500)

        assertEquals(
            listOf(RecentLaunch(okko, 500, 3), RecentLaunch(kion, 300, 1), RecentLaunch(youtube, 200, 1)),
            repository.recents.value,
        )
    }

    @Test
    fun `the newest launch is first even when the clock runs backwards`() {
        val repository = PrefsSettingsRepository(context)
        repository.recordLaunch(okko, now = 1_794_000_000_000)
        // The TV lost power and came up with its factory date.
        repository.recordLaunch(youtube, now = 1_420_070_400_000)

        assertEquals(listOf(youtube, okko), repository.recents.value.map { it.key })
        assertEquals(listOf(youtube, okko), PrefsSettingsRepository(context).recents.value.map { it.key })
    }

    @Test
    fun `history is limited to thirty apps and drops the oldest`() {
        val repository = PrefsSettingsRepository(context)
        val apps = (0 until 40).map { AppKey("pkg$it", "Main") }
        apps.forEachIndexed { index, app -> repository.recordLaunch(app, now = index.toLong()) }

        val recents = repository.recents.value
        assertEquals(30, recents.size)
        assertEquals(apps.reversed().take(30), recents.map { it.key })

        // Re-launching the oldest survivor keeps the size and moves it to the front.
        repository.recordLaunch(apps[10], now = 1000)
        assertEquals(30, repository.recents.value.size)
        assertEquals(RecentLaunch(apps[10], 1000, 2), repository.recents.value.first())
        assertEquals(apps[11], repository.recents.value.last().key)
    }

    @Test
    fun `the launch count saturates instead of overflowing`() {
        prefs.edit().putString("recents", "5 ${Int.MAX_VALUE} ${okko.flatten()}").commit()
        val repository = PrefsSettingsRepository(context)

        repository.recordLaunch(okko, now = 6)

        assertEquals(listOf(RecentLaunch(okko, 6, Int.MAX_VALUE)), repository.recents.value)
    }

    @Test
    fun `history survives a restart`() {
        val first = PrefsSettingsRepository(context)
        first.recordLaunch(okko, now = 100)
        first.recordLaunch(youtube, now = 200)
        first.recordLaunch(okko, now = 300)

        assertEquals(listOf(RecentLaunch(okko, 300, 2), RecentLaunch(youtube, 200, 1)), PrefsSettingsRepository(context).recents.value)
    }

    @Test
    fun `pruning forgets uninstalled apps only`() {
        val repository = PrefsSettingsRepository(context)
        repository.recordLaunch(okko, now = 100)
        repository.recordLaunch(youtube, now = 200)
        repository.recordLaunch(kion, now = 300)

        repository.pruneRecents(setOf(okko, kion, AppKey("never.launched", "Main")))

        val expected = listOf(RecentLaunch(kion, 300, 1), RecentLaunch(okko, 100, 1))
        assertEquals(expected, repository.recents.value)
        assertEquals(expected, PrefsSettingsRepository(context).recents.value)
    }

    @Test
    fun `pruning with everything still installed keeps the very same list`() {
        val repository = PrefsSettingsRepository(context)
        repository.recordLaunch(okko, now = 100)
        val before = repository.recents.value

        repository.pruneRecents(setOf(okko, youtube))

        assertSame(before, repository.recents.value)
    }

    @Test
    fun `settings and history do not disturb each other`() {
        val repository = PrefsSettingsRepository(context)
        repository.update { it.copy(theme = ThemeMode.Graphite) }
        repository.recordLaunch(okko, now = 100)
        repository.update { it.copy(dockApps = listOf(okko)) }
        repository.pruneRecents(emptySet())

        val reopened = PrefsSettingsRepository(context)
        assertEquals(LauncherSettings(theme = ThemeMode.Graphite, dockApps = listOf(okko)), reopened.settings.value)
        assertEquals(emptyList<RecentLaunch>(), reopened.recents.value)
    }
}
