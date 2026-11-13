package tv.monotv.launcher.data.apps

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Looper
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.AppKey

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAppsRepositoryTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val packages = shadowOf(app.packageManager)
    private val scope = CoroutineScope(SupervisorJob())
    private val changedPackages = ArrayList<String>()

    @After
    fun tearDown() {
        scope.cancel()
    }

    /** With the unconfined dispatcher a requested scan has finished by the time the requesting call returns. */
    private fun repository(io: CoroutineDispatcher = Dispatchers.Unconfined) =
        AndroidAppsRepository(app, scope, LauncherConfigLoader(app), io) { changedPackages += it }

    private fun install(
        packageName: String,
        system: Boolean = false,
        appBanner: Int = 0,
        versionCode: Long = 1L,
        installedAt: Long = 0L,
    ) {
        packages.installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                longVersionCode = versionCode
                firstInstallTime = installedAt
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = packageName
                    flags = if (system) ApplicationInfo.FLAG_SYSTEM else 0
                    banner = appBanner
                }
            },
        )
    }

    private fun activity(
        packageName: String,
        category: String,
        name: String = "$packageName.MainActivity",
        label: String? = packageName.substringAfterLast('.'),
        banner: Int = 0,
        exported: Boolean = true,
        enabled: Boolean = true,
        filter: IntentFilter = IntentFilter(Intent.ACTION_MAIN).apply { addCategory(category) },
    ): AppKey {
        packages.addOrUpdateActivity(
            ActivityInfo().apply {
                this.packageName = packageName
                this.name = name
                this.exported = exported
                this.enabled = enabled
                this.banner = banner
                nonLocalizedLabel = label
            },
        )
        packages.addIntentFilterForActivity(ComponentName(packageName, name), filter)
        return AppKey(packageName, name)
    }

    /** Installs a package with a single entry point of [category]. */
    private fun installApp(packageName: String, category: String = LEANBACK, label: String? = packageName.substringAfterLast('.')): AppKey {
        install(packageName)
        return activity(packageName, category, label = label)
    }

    private fun broadcast(action: String, packageName: String, replacing: Boolean = false) {
        val intent = Intent(action, Uri.fromParts("package", packageName, null))
        if (replacing) intent.putExtra(Intent.EXTRA_REPLACING, true)
        app.sendBroadcast(intent)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun AndroidAppsRepository.listed(): List<AppEntry> = checkNotNull(apps.value) { "no scan finished yet" }

    private fun AndroidAppsRepository.listedPackages(): List<String> = listed().map { it.key.packageName }

    private fun startedActivity(): Intent? = shadowOf(app).nextStartedActivity

    // -----------------------------------------------------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `discovers leanback, star launcher and launcher-only apps`() {
        val tv = installApp("com.example.tvapp", LEANBACK)
        val sber = installApp("ru.sberdevices.startube", STAR)
        val phone = installApp("com.example.phoneapp", LAUNCHER)

        val repository = repository()
        assertNull("nothing is published before the first scan", repository.apps.value)
        repository.start()

        val byKey = repository.listed().associateBy { it.key }
        assertEquals(setOf(tv, sber, phone), byKey.keys)
        assertTrue(byKey.getValue(tv).isLeanback)
        assertTrue("Sber's private launcher category counts as a TV app", byKey.getValue(sber).isLeanback)
        assertFalse(byKey.getValue(phone).isLeanback)
        assertEquals("youtube", byKey.getValue(sber).knownId)
    }

    @Test
    fun `a package gets one tile and its best entry point wins`() {
        install("com.example.both")
        activity("com.example.both", LAUNCHER, name = "com.example.both.PhoneActivity")
        val bothTv = activity("com.example.both", LEANBACK, name = "com.example.both.TvActivity")

        install("ru.sberdevices.example")
        activity("ru.sberdevices.example", LAUNCHER, name = "ru.sberdevices.example.PhoneActivity")
        val sberStar = activity("ru.sberdevices.example", STAR, name = "ru.sberdevices.example.StarActivity")

        install("com.example.all")
        activity("com.example.all", STAR, name = "com.example.all.StarActivity")
        activity("com.example.all", LAUNCHER, name = "com.example.all.PhoneActivity")
        val allTv = activity("com.example.all", LEANBACK, name = "com.example.all.TvActivity")

        install("com.example.twins")
        activity("com.example.twins", LEANBACK, name = "com.example.twins.One")
        activity("com.example.twins", LEANBACK, name = "com.example.twins.Two")

        val repository = repository().apply { start() }
        val listed = repository.listed()
        assertEquals(4, listed.size)
        assertEquals(listed.size, repository.listedPackages().toSet().size)
        assertTrue(listed.map { it.key }.containsAll(listOf(bothTv, sberStar, allTv)))
        assertTrue(listed.all { it.isLeanback })
    }

    @Test
    fun `factory and engineering menus are never listed`() {
        installApp("com.cvte.fac.menu")
        installApp("com.stb.factorymenu", LAUNCHER)
        installApp("com.mediatek.factorymode.ui", STAR)
        installApp("com.unknownvendor.EngineerMode", LAUNCHER)
        installApp("org.board.hwtest.suite")
        val projectivy = installApp("com.spocky.projengmenu")

        val repository = repository().apply { start() }
        assertEquals(listOf(projectivy), repository.listed().map { it.key })
    }

    @Test
    fun `the launcher does not list itself`() {
        val query = Intent(Intent.ACTION_MAIN).addCategory(LEANBACK)
        val ownIsVisibleToTheQuery = app.packageManager.queryIntentActivities(query, 0)
            .any { it.activityInfo.packageName == app.packageName }
        assertTrue("the test would prove nothing if the package manager did not return our own activity", ownIsVisibleToTheQuery)
        installApp("com.example.other")

        val repository = repository().apply { start() }
        assertEquals(listOf("com.example.other"), repository.listedPackages())
    }

    @Test
    fun `entry points that cannot be started from outside are skipped`() {
        install("com.example.private")
        activity("com.example.private", LEANBACK, exported = false)

        install("com.example.disabled")
        activity("com.example.disabled", LEANBACK, enabled = false)

        install("com.example.halfprivate")
        activity("com.example.halfprivate", LEANBACK, name = "com.example.halfprivate.TvActivity", exported = false)
        val fallback = activity("com.example.halfprivate", LAUNCHER, name = "com.example.halfprivate.PhoneActivity")

        val repository = repository().apply { start() }
        assertEquals(listOf(fallback), repository.listed().map { it.key })
        assertFalse(repository.listed().single().isLeanback)
    }

    @Test
    fun `entries carry label, banner, system flag, known id, version and install time`() {
        install("com.netflix.ninja", system = true, versionCode = 4_000_000_123L, installedAt = 1_700_000_000_000L)
        val netflix = activity("com.netflix.ninja", LEANBACK, label = "Netflix", banner = 0x7F080001)

        install("com.example.appbanner", appBanner = 0x7F080002)
        val appBanner = activity("com.example.appbanner", LEANBACK, label = "App banner")

        install("com.example.plain", versionCode = 7L, installedAt = 42L)
        val plain = activity("com.example.plain", LAUNCHER, label = "Plain")

        val repository = repository().apply { start() }
        val byKey = repository.listed().associateBy { it.key }
        assertEquals(
            AppEntry(
                key = netflix, label = "Netflix", isLeanback = true, hasBanner = true, isSystem = true,
                knownId = "netflix", versionCode = 4_000_000_123L, installedAt = 1_700_000_000_000L,
            ),
            byKey[netflix],
        )
        assertTrue(byKey.getValue(appBanner).hasBanner)
        assertEquals(
            AppEntry(
                key = plain, label = "Plain", isLeanback = false, hasBanner = false, isSystem = false,
                knownId = null, versionCode = 7L, installedAt = 42L,
            ),
            byKey[plain],
        )
    }

    @Test
    fun `unusable labels fall back to the known title or the package name and labels are tidied`() {
        val known = installApp("com.netflix.ninja", label = null)
        val unknown = installApp("com.example.nolabel", label = "   ")
        val messy = installApp("com.example.messy", label = "  Кино\n\t Поиск  ")

        val repository = repository().apply { start() }
        val labels = repository.listed().associate { it.key to it.label }
        assertEquals("Netflix", labels[known])
        assertEquals("com.example.nolabel", labels[unknown])
        assertEquals("Кино Поиск", labels[messy])
    }

    @Test
    fun `apps are sorted by label ignoring case`() {
        installApp("com.example.c", label = "cherry")
        installApp("com.example.b", label = "Banana")
        installApp("com.example.a2", label = "apple")
        installApp("com.example.a1", label = "apple")

        val repository = repository().apply { start() }
        assertEquals(listOf("apple", "apple", "Banana", "cherry"), repository.listed().map { it.label })
        assertEquals(listOf("com.example.a1", "com.example.a2"), repository.listedPackages().take(2))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Keeping the list fresh
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `rescans after a package broadcast`() {
        installApp("com.example.first")
        val repository = repository().apply { start() }
        assertEquals(listOf("com.example.first"), repository.listedPackages())

        installApp("com.example.second")
        assertEquals("no polling: nothing changes without a broadcast", 1, repository.listed().size)
        broadcast(Intent.ACTION_PACKAGE_ADDED, "com.example.second")
        assertEquals(listOf("com.example.first", "com.example.second"), repository.listedPackages())

        packages.deletePackage("com.example.first")
        broadcast(Intent.ACTION_PACKAGE_REMOVED, "com.example.first")
        assertEquals(listOf("com.example.second"), repository.listedPackages())

        assertEquals(listOf("com.example.second", "com.example.first"), changedPackages)
    }

    @Test
    fun `changed and replaced packages trigger a rescan too`() {
        install("com.example.app", versionCode = 1L)
        val key = activity("com.example.app", LEANBACK)
        val repository = repository().apply { start() }
        assertEquals(1L, repository.listed().single().versionCode)

        install("com.example.app", versionCode = 2L)
        activity("com.example.app", LEANBACK)
        broadcast(Intent.ACTION_PACKAGE_REPLACED, "com.example.app")
        assertEquals(2L, repository.listed().single { it.key == key }.versionCode)

        val scans = repository.completedScans
        broadcast(Intent.ACTION_PACKAGE_CHANGED, "com.example.app")
        assertEquals(scans + 1, repository.completedScans)
    }

    @Test
    fun `the removal half of an update is ignored so the app never looks uninstalled`() {
        installApp("com.example.app")
        val repository = repository().apply { start() }
        val scans = repository.completedScans

        broadcast(Intent.ACTION_PACKAGE_REMOVED, "com.example.app", replacing = true)
        assertEquals(scans, repository.completedScans)
        assertTrue(changedPackages.isEmpty())

        broadcast(Intent.ACTION_PACKAGE_ADDED, "com.example.app", replacing = true)
        assertEquals(scans + 1, repository.completedScans)
        assertEquals(listOf("com.example.app"), changedPackages)
    }

    @Test
    fun `a burst of package broadcasts is coalesced into one rescan`() {
        val scheduler = TestCoroutineScheduler()
        val repository = repository(io = StandardTestDispatcher(scheduler))
        installApp("com.example.first")

        repository.start()
        assertNull("the scan runs on the io dispatcher, not inside start()", repository.apps.value)
        scheduler.advanceUntilIdle()
        assertEquals(1, repository.completedScans)
        assertEquals(listOf("com.example.first"), repository.listedPackages())

        repeat(20) { index ->
            installApp("com.example.batch$index")
            broadcast(Intent.ACTION_PACKAGE_ADDED, "com.example.batch$index")
        }
        assertEquals("nothing runs on the caller's thread", 1, repository.completedScans)
        scheduler.advanceUntilIdle()
        assertEquals(2, repository.completedScans)
        assertEquals(21, repository.listed().size)
        assertEquals(20, changedPackages.size)
    }

    @Test
    fun `start registers the receiver once and stop unregisters it`() {
        installApp("com.example.first")
        val repository = repository()
        repository.start()
        repository.start()
        assertEquals(2, repository.completedScans)

        broadcast(Intent.ACTION_PACKAGE_CHANGED, "com.example.first")
        assertEquals("one receiver, one callback", listOf("com.example.first"), changedPackages)
        assertEquals(3, repository.completedScans)

        repository.stop()
        repository.stop()
        installApp("com.example.second")
        broadcast(Intent.ACTION_PACKAGE_ADDED, "com.example.second")
        assertEquals(3, repository.completedScans)
        assertEquals(listOf("com.example.first"), repository.listedPackages())

        // Whatever happened while stopped is picked up by the scan that start() always runs.
        repository.start()
        assertEquals(listOf("com.example.first", "com.example.second"), repository.listedPackages())
        broadcast(Intent.ACTION_PACKAGE_CHANGED, "com.example.second")
        assertEquals(5, repository.completedScans)
    }

    @Test
    fun `refresh rescans without any broadcast`() {
        val repository = repository()
        repository.refresh()
        assertEquals(emptyList<AppEntry>(), repository.listed())

        installApp("com.example.late")
        repository.refresh()
        assertEquals(listOf("com.example.late"), repository.listedPackages())
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Launching
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `launch starts the exact activity the way a home screen does`() {
        val tv = installApp("com.example.tvapp", LEANBACK)
        val sber = installApp("ru.sberdevices.example", STAR)
        val phone = installApp("com.example.phoneapp", LAUNCHER)
        val repository = repository().apply { start() }

        for ((key, category) in listOf(tv to LEANBACK, sber to STAR, phone to LAUNCHER)) {
            assertTrue(repository.launch(key))
            val started = checkNotNull(startedActivity())
            assertEquals(Intent.ACTION_MAIN, started.action)
            assertEquals(ComponentName(key.packageName, key.activityName), started.component)
            assertEquals(setOf(category), started.categories)
            assertEquals(HOME_LAUNCH_FLAGS, started.flags and HOME_LAUNCH_FLAGS)
        }
    }

    @Test
    fun `launch works for a key that was never scanned`() {
        val key = installApp("com.example.tvapp")
        val repository = repository()
        assertTrue(repository.launch(key))
        val started = checkNotNull(startedActivity())
        assertEquals(ComponentName(key.packageName, key.activityName), started.component)
        assertNull(started.categories)
    }

    @Test
    fun `launch refuses blocked packages even when asked explicitly`() {
        val factory = installApp("com.cvte.fac.menu")
        val engineer = installApp("com.unknownvendor.engineermode", LAUNCHER)
        val repository = repository().apply { start() }

        assertFalse(repository.launch(factory))
        assertFalse(repository.launch(engineer))
        assertFalse(repository.launch(AppKey("com.stb.factorymenu", "com.stb.factorymenu.Whatever")))
        assertFalse(repository.openAppInfo(factory))
        assertNull(startedActivity())
    }

    @Test
    fun `a stale activity name falls back to the package's current entry point`() {
        shadowOf(app).checkActivities(true)
        install("com.example.renamed")
        val current = activity("com.example.renamed", LEANBACK, name = "com.example.renamed.NewMain")
        val phoneOnly = installApp("com.example.phoneapp", LAUNCHER)
        val repository = repository().apply { start() }

        assertTrue(repository.launch(AppKey("com.example.renamed", "com.example.renamed.OldMain")))
        assertEquals(ComponentName(current.packageName, current.activityName), checkNotNull(startedActivity()).component)

        assertTrue(repository.launch(AppKey(phoneOnly.packageName, "com.example.phoneapp.Gone")))
        assertEquals(ComponentName(phoneOnly.packageName, phoneOnly.activityName), checkNotNull(startedActivity()).component)
    }

    @Test
    fun `launching something that is not installed fails quietly`() {
        shadowOf(app).checkActivities(true)
        val repository = repository().apply { start() }
        assertFalse(repository.launch(AppKey("com.example.gone", "com.example.gone.Main")))
        assertFalse(repository.launch(AppKey("", "")))
        assertFalse(repository.launchUri("intent:#Intent;component=com.example.gone/.Main;end", null))
        assertNull(startedActivity())
    }

    @Test
    fun `a deep link is started hardened`() {
        shadowOf(app).checkActivities(true)
        install("com.example.video")
        activity(
            "com.example.video", LEANBACK, name = "com.example.video.PlayerActivity",
            filter = IntentFilter(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("examplevideo")
            },
        )
        val link = Intent(Intent.ACTION_VIEW, Uri.parse("examplevideo://watch/42"))
            .putExtra("position", 1234L)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        link.selector = Intent(Intent.ACTION_MAIN).setClassName(app.packageName, "tv.monotv.launcher.Internal")
        val repository = repository().apply { start() }

        assertTrue(repository.launchUri(link.toUri(Intent.URI_INTENT_SCHEME), fallback = null))

        val started = checkNotNull(startedActivity())
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(Uri.parse("examplevideo://watch/42"), started.data)
        assertEquals(1234L, started.getLongExtra("position", -1L))
        assertNull("the selector could redirect the launch", started.selector)
        assertNull(started.clipData)
        assertEquals(0, started.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertTrue((started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun `a deep link into the launcher itself is refused and the fallback app opens instead`() {
        val fallback = installApp("com.example.video")
        val repository = repository().apply { start() }
        val own = ComponentName(app.packageName, "tv.monotv.launcher.MainActivity")

        val explicit = Intent(Intent.ACTION_VIEW).setComponent(own).toUri(Intent.URI_INTENT_SCHEME)
        assertTrue(repository.launchUri(explicit, fallback))
        assertEquals("com.example.video", checkNotNull(startedActivity()).component?.packageName)

        assertFalse(repository.launchUri(explicit, fallback = null))
        assertNull(startedActivity())
    }

    @Test
    fun `a deep link into a blocked package is refused`() {
        val factory = installApp("com.cvte.fac.menu")
        val fallback = installApp("com.example.video")
        val repository = repository().apply { start() }
        val link = Intent(Intent.ACTION_MAIN)
            .setClassName(factory.packageName, factory.activityName)
            .toUri(Intent.URI_INTENT_SCHEME)

        assertFalse(repository.launchUri(link, fallback = null))
        assertFalse(repository.launchUri(link, fallback = factory))
        assertNull(startedActivity())

        assertTrue(repository.launchUri(link, fallback))
        assertEquals("com.example.video", checkNotNull(startedActivity()).component?.packageName)
    }

    @Test
    fun `package management deep links are refused`() {
        install("com.example.installer")
        activity(
            "com.example.installer", LEANBACK, name = "com.example.installer.UninstallActivity",
            filter = IntentFilter(Intent.ACTION_DELETE).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                addDataScheme("package")
            },
        )
        val repository = repository().apply { start() }
        val uninstall = Intent(Intent.ACTION_DELETE, Uri.parse("package:com.example.video")).toUri(Intent.URI_INTENT_SCHEME)

        assertFalse(repository.launchUri(uninstall, fallback = null))
        assertNull(startedActivity())
    }

    @Test
    fun `missing, garbled and unresolvable deep links fall back to the app`() {
        shadowOf(app).checkActivities(true)
        val fallback = installApp("com.example.video")
        val repository = repository().apply { start() }

        for (uri in listOf(null, "", "   ", "intent:#Intent;S.broken", "nobody-handles-this://x", "intent:#Intent;action=com.example.NOBODY;end")) {
            assertTrue("uri: $uri", repository.launchUri(uri, fallback))
            assertEquals("uri: $uri", "com.example.video", checkNotNull(startedActivity()).component?.packageName)
            assertFalse("uri: $uri", repository.launchUri(uri, fallback = null))
            assertNull(startedActivity())
        }
    }

    @Test
    fun `app info opens the system details screen of the package`() {
        val key = installApp("com.example.tvapp")
        val repository = repository()

        assertTrue(repository.openAppInfo(key))
        val started = checkNotNull(startedActivity())
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, started.action)
        assertEquals(Uri.parse("package:com.example.tvapp"), started.data)
        assertTrue((started.flags and Intent.FLAG_ACTIVITY_NEW_TASK) != 0)
    }

    @Test
    fun `the config is the bundled one`() {
        val repository = repository()
        assertNotNull(repository.config.known("youtube"))
        assertTrue(repository.config.isBlocked("com.cvte.fac.menu"))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Failure modes
    // -----------------------------------------------------------------------------------------------------------------

    /** A context whose package manager and activity starts can be made to fail like a dying system server. */
    private class FlakyContext(base: Context) : ContextWrapper(base) {
        var packageManagerDead = false
        var startsForbidden = false

        override fun getApplicationContext(): Context = this

        override fun getPackageManager(): PackageManager {
            if (packageManagerDead) throw IllegalStateException("package manager has died")
            return super.getPackageManager()
        }

        override fun startActivity(intent: Intent?) {
            if (startsForbidden) throw SecurityException("not allowed to start $intent")
            super.startActivity(intent)
        }
    }

    @Test
    fun `a failed scan keeps the last good list`() {
        val flaky = FlakyContext(app)
        installApp("com.example.first")
        val repository = AndroidAppsRepository(flaky, scope, LauncherConfigLoader(app), Dispatchers.Unconfined)
        repository.start()
        assertEquals(listOf("com.example.first"), repository.listedPackages())

        flaky.packageManagerDead = true
        installApp("com.example.second")
        repository.refresh()
        assertEquals("a half-read list could drop apps from the Dock", listOf("com.example.first"), repository.listedPackages())

        flaky.packageManagerDead = false
        repository.refresh()
        assertEquals(listOf("com.example.first", "com.example.second"), repository.listedPackages())
    }

    @Test
    fun `a failed first scan still ends the loading state`() {
        val flaky = FlakyContext(app).apply { packageManagerDead = true }
        installApp("com.example.first")
        val repository = AndroidAppsRepository(flaky, scope, LauncherConfigLoader(app), Dispatchers.Unconfined)

        repository.start()
        assertEquals(emptyList<AppEntry>(), repository.apps.value)

        flaky.packageManagerDead = false
        repository.refresh()
        assertEquals(listOf("com.example.first"), repository.listedPackages())
    }

    @Test
    fun `nothing is thrown when the system refuses to start an activity or to answer`() {
        val flaky = FlakyContext(app)
        val key = installApp("com.example.tvapp")
        val repository = AndroidAppsRepository(flaky, scope, LauncherConfigLoader(app), Dispatchers.Unconfined)
        repository.start()
        val link = Intent(Intent.ACTION_MAIN).setClassName(key.packageName, key.activityName).toUri(Intent.URI_INTENT_SCHEME)

        flaky.startsForbidden = true
        assertFalse(repository.launch(key))
        assertFalse(repository.launchUri(link, key))
        assertFalse(repository.openAppInfo(key))

        flaky.packageManagerDead = true
        assertFalse(repository.launch(key))
        assertFalse(repository.launchUri(link, key))
        assertFalse(repository.openAppInfo(key))
        assertNull(startedActivity())
    }

    private companion object {
        const val LEANBACK = Intent.CATEGORY_LEANBACK_LAUNCHER
        const val LAUNCHER = Intent.CATEGORY_LAUNCHER
        const val STAR = "ru.sberdevices.category.STAR_LAUNCHER"
        const val HOME_LAUNCH_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
    }
}
