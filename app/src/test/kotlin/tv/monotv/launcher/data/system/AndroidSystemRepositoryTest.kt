package tv.monotv.launcher.data.system

import android.app.ActivityManager
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.provider.Settings
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowApplicationPackageManager
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSettings
import tv.monotv.launcher.BuildConfig
import tv.monotv.launcher.core.ClockState
import tv.monotv.launcher.core.EffectsLevel
import tv.monotv.launcher.core.NetworkKind
import tv.monotv.launcher.core.NetworkState

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class AndroidSystemRepositoryTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val packageManager: PackageManager get() = app.packageManager
    private val connectivity: ConnectivityManager
        get() = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val moscow = TimeZone.getTimeZone("Europe/Moscow")
    private lateinit var savedZone: TimeZone

    /** The fake wall clock handed to the repository. */
    private var nowMillis = millis(2026, 11, 13, 19, 42)

    @Before
    fun setUp() {
        savedZone = TimeZone.getDefault()
        TimeZone.setDefault(moscow)
        ShadowSettings.set24HourTimeFormat(true)
    }

    @After
    fun tearDown() {
        TimeZone.setDefault(savedZone)
    }

    private fun repository(context: Context = app) = AndroidSystemRepository(context) { nowMillis }

    // -----------------------------------------------------------------------------------------------------------------
    // Clock
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the clock is known before start`() {
        assertEquals(ClockState("19:42", "13 ноября, пятница", "Добрый вечер"), repository().clock.value)
    }

    @Test
    fun `the clock follows the minute tick while started`() {
        val repository = repository()
        repository.start()

        nowMillis += 60_000
        broadcast(Intent.ACTION_TIME_TICK)
        assertEquals("19:43", repository.clock.value.time)

        nowMillis = millis(2026, 11, 14, 0, 0)
        broadcast(Intent.ACTION_TIME_TICK)
        assertEquals(ClockState("00:00", "14 ноября, суббота", "Доброй ночи"), repository.clock.value)
    }

    @Test
    fun `start recomputes the clock at once and stop ends the updates`() {
        val repository = repository()
        nowMillis += 5 * 60_000
        assertEquals("19:42", repository.clock.value.time)

        repository.start()
        assertEquals("19:47", repository.clock.value.time)

        repository.stop()
        nowMillis += 60_000
        broadcast(Intent.ACTION_TIME_TICK)
        assertEquals("a stopped repository must not react to broadcasts", "19:47", repository.clock.value.time)

        repository.start()
        assertEquals("19:48", repository.clock.value.time)
    }

    @Test
    fun `a manual time change, a new time zone and a new hour format are picked up`() {
        val repository = repository()
        repository.start()

        nowMillis = millis(2026, 12, 31, 23, 59)
        broadcast(Intent.ACTION_TIME_CHANGED)
        assertEquals(ClockState("23:59", "31 декабря, четверг", "Доброй ночи"), repository.clock.value)

        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Vladivostok"))
        broadcast(Intent.ACTION_TIMEZONE_CHANGED)
        assertEquals(ClockState("06:59", "1 января, пятница", "Доброе утро"), repository.clock.value)

        ShadowSettings.set24HourTimeFormat(false)
        broadcast(Intent.ACTION_LOCALE_CHANGED)
        assertEquals("6:59", repository.clock.value.time)

        nowMillis += 24 * 60 * 60_000
        broadcast(Intent.ACTION_DATE_CHANGED)
        assertEquals("2 января, суббота", repository.clock.value.date)
    }

    @Test
    fun `start and stop are idempotent and leave nothing registered`() {
        val repository = repository()
        assertEquals(0, clockReceivers())

        repository.start()
        repository.start()
        assertEquals(1, clockReceivers())
        assertEquals(1, shadowOf(connectivity).networkCallbacks.size)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val registration = shadowOf(app).registeredReceivers.single { it.intentFilter.hasAction(Intent.ACTION_TIME_TICK) }
            assertTrue("only the system may deliver clock broadcasts", registration.flags and Context.RECEIVER_NOT_EXPORTED != 0)
        }

        repository.stop()
        repository.stop()
        assertEquals(0, clockReceivers())
        assertEquals(0, shadowOf(connectivity).networkCallbacks.size)

        repository.start()
        assertEquals(1, clockReceivers())
        assertEquals(1, shadowOf(connectivity).networkCallbacks.size)
        repository.stop()
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Network
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the active network is mapped by transport and validation`() {
        val network = connectivity.activeNetwork!!

        shadowOf(connectivity).setNetworkCapabilities(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI, validated = true))
        assertEquals(NetworkState(NetworkKind.Wifi, validated = true), repository().network.value)

        shadowOf(connectivity).setNetworkCapabilities(network, capabilities(NetworkCapabilities.TRANSPORT_ETHERNET, validated = false))
        assertEquals(NetworkState(NetworkKind.Ethernet, validated = false), repository().network.value)

        shadowOf(connectivity).setNetworkCapabilities(network, capabilities(NetworkCapabilities.TRANSPORT_CELLULAR, validated = true))
        assertEquals(NetworkState(NetworkKind.Other, validated = true), repository().network.value)
    }

    @Test
    fun `a cable wins when a network reports both transports`() {
        val both = capabilities(NetworkCapabilities.TRANSPORT_WIFI, validated = true)
        shadowOf(both).addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
        assertEquals(NetworkState(NetworkKind.Ethernet, validated = true), AndroidSystemRepository.networkStateOf(both))
        assertEquals(NetworkState(NetworkKind.Offline, validated = false), AndroidSystemRepository.networkStateOf(null))
    }

    @Test
    fun `no active network means offline`() {
        shadowOf(connectivity).setActiveNetworkInfo(null)
        val repository = repository()
        assertEquals(NetworkState(NetworkKind.Offline, validated = false), repository.network.value)
        repository.start()
        assertEquals(NetworkState(NetworkKind.Offline, validated = false), repository.network.value)
    }

    @Test
    fun `validation and loss of the active network are followed`() {
        // Drives the platform state and the callback together, so it holds both where the callback's arguments are
        // used (Android 8+) and where the active network is re-read on every event (before that).
        val network = connectivity.activeNetwork!!
        shadowOf(connectivity).setNetworkCapabilities(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI, validated = false))
        val repository = repository()
        repository.start()
        val callback = shadowOf(connectivity).networkCallbacks.single()
        assertEquals(NetworkState(NetworkKind.Wifi, validated = false), repository.network.value)

        val validated = capabilities(NetworkCapabilities.TRANSPORT_WIFI, validated = true)
        shadowOf(connectivity).setNetworkCapabilities(network, validated)
        callback.onAvailable(network)
        callback.onCapabilitiesChanged(network, validated)
        assertEquals(NetworkState(NetworkKind.Wifi, validated = true), repository.network.value)

        shadowOf(connectivity).setActiveNetworkInfo(null)
        callback.onLost(network)
        assertEquals(NetworkState(NetworkKind.Offline, validated = false), repository.network.value)
    }

    @Test
    fun `network callbacks move the state between wifi, ethernet and offline`() {
        assumeTrue("callback arguments alone are authoritative from Android 8 on", Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
        val wifi = connectivity.activeNetwork!!
        val ethernet = ShadowNetwork.newInstance(77)
        shadowOf(connectivity).setNetworkCapabilities(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI, validated = false))
        val repository = repository()
        repository.start()
        val callback = shadowOf(connectivity).networkCallbacks.single()

        assertEquals(NetworkState(NetworkKind.Wifi, validated = false), repository.network.value)

        // The captive-portal check passes.
        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI, validated = true))
        assertEquals(NetworkState(NetworkKind.Wifi, validated = true), repository.network.value)

        // A cable is plugged in and becomes the default network.
        callback.onAvailable(ethernet)
        callback.onCapabilitiesChanged(ethernet, capabilities(NetworkCapabilities.TRANSPORT_ETHERNET, validated = true))
        assertEquals(NetworkState(NetworkKind.Ethernet, validated = true), repository.network.value)

        // The late farewell of the replaced network must not blank the working one.
        callback.onLost(wifi)
        assertEquals(NetworkState(NetworkKind.Ethernet, validated = true), repository.network.value)

        callback.onLost(ethernet)
        assertEquals(NetworkState(NetworkKind.Offline, validated = false), repository.network.value)

        callback.onAvailable(wifi)
        callback.onCapabilitiesChanged(wifi, capabilities(NetworkCapabilities.TRANSPORT_WIFI, validated = true))
        assertEquals(NetworkState(NetworkKind.Wifi, validated = true), repository.network.value)
    }

    @Test
    fun `start re-reads the network that changed while stopped`() {
        val network = connectivity.activeNetwork!!
        shadowOf(connectivity).setNetworkCapabilities(network, capabilities(NetworkCapabilities.TRANSPORT_WIFI, validated = true))
        val repository = repository()
        repository.start()
        repository.stop()

        shadowOf(connectivity).setActiveNetworkInfo(null)
        repository.start()

        assertEquals(NetworkKind.Offline, repository.network.value.kind)
    }

    @Test
    fun `a platform that refuses network access reads as offline instead of crashing`() {
        val locked = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getSystemService(name: String): Any? =
                if (name == Context.CONNECTIVITY_SERVICE) throw SecurityException("no ACCESS_NETWORK_STATE") else super.getSystemService(name)
        }
        val repository = repository(locked)
        repository.start()
        assertEquals(NetworkState(NetworkKind.Offline, validated = false), repository.network.value)
        repository.stop()
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Device
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `device facts are read from the platform`() {
        ShadowBuild.setManufacturer("SberDevices")
        ShadowBuild.setBrand("SberDevices")
        ShadowBuild.setModel("SBDV-00006")
        ShadowBuild.setDevice("sbdv_00006")
        setTotalRam(megabytes = 1873)
        shadowOf(packageManager).setSystemFeature(PackageManager.FEATURE_LEANBACK, true)

        val device = repository().device.value

        assertEquals("SberDevices", device.manufacturer)
        assertEquals("SberDevices", device.brand)
        assertEquals("SBDV-00006", device.model)
        assertEquals("sbdv_00006", device.device)
        assertEquals(Build.VERSION.SDK_INT, device.sdkInt)
        assertTrue(device.androidRelease.isNotEmpty())
        assertEquals(1920, device.widthPx)
        assertEquals(1080, device.heightPx)
        assertEquals(320, device.densityDpi)
        assertEquals(1873, device.totalRamMb)
        assertFalse(device.lowRam)
        assertEquals(Runtime.getRuntime().availableProcessors(), device.cpuCores)
        assertTrue(device.hasLeanback)
        assertEquals(BuildConfig.VERSION_NAME, device.launcherVersion)
    }

    @Test
    fun `recommended effects follow the hardware`() {
        setTotalRam(megabytes = 930)
        assertEquals(EffectsLevel.Reduced, repository().recommendedEffects())

        setTotalRam(megabytes = 3800)
        shadowOf(activityManager()).setIsLowRamDevice(true)
        val lowRam = repository()
        assertTrue(lowRam.device.value.lowRam)
        assertEquals(EffectsLevel.Reduced, lowRam.recommendedEffects())

        shadowOf(activityManager()).setIsLowRamDevice(false)
        val capable = repository()
        val device = capable.device.value
        assertEquals(
            PerformanceTier.recommend(device.totalRamMb, device.lowRam, device.cpuCores, device.sdkInt),
            capable.recommendedEffects(),
        )
        assertNotEquals(EffectsLevel.Off, capable.recommendedEffects())
    }

    // -----------------------------------------------------------------------------------------------------------------
    // HOME
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `without any HOME answer the current home is unknown`() {
        val device = repository().device.value
        assertNull(device.currentHomePackage)
        assertFalse(device.isDefaultHome)
        assertFalse(device.homeAliasEnabled)
    }

    @Test
    fun `the stock launcher is reported as the current home`() {
        answer(homeIntent(), STOCK_LAUNCHER, "ru.sberdevices.starlauncher.ui.mainscreen.MainActivity")
        val device = repository().device.value
        assertEquals(STOCK_LAUNCHER, device.currentHomePackage)
        assertFalse(device.isDefaultHome)
    }

    @Test
    fun `the system chooser answering means no default home`() {
        answer(homeIntent(), "android", "com.android.internal.app.ResolverActivity")
        val device = repository().device.value
        assertNull(device.currentHomePackage)
        assertFalse(device.isDefaultHome)
    }

    @Test
    fun `refreshDevice notices that this app became the default home`() {
        answer(homeIntent(), STOCK_LAUNCHER, "ru.sberdevices.starlauncher.ui.mainscreen.MainActivity")
        val repository = repository()
        val before = repository.device.value

        @Suppress("DEPRECATION") // Same shortcut family as answer(), see there.
        shadowOf(packageManager).removeResolveInfosForIntent(homeIntent(), STOCK_LAUNCHER)
        answer(homeIntent(), app.packageName, AndroidSystemRepository.HOME_ALIAS_CLASS)
        assertEquals("nothing is re-read behind the caller's back", before, repository.device.value)

        repository.refreshDevice()

        assertEquals(app.packageName, repository.device.value.currentHomePackage)
        assertTrue(repository.device.value.isDefaultHome)
        // Only the HOME fields changed.
        assertEquals(before, repository.device.value.copy(currentHomePackage = STOCK_LAUNCHER, isDefaultHome = false))
    }

    @Test
    fun `start refreshes the HOME state`() {
        val repository = repository()
        answer(homeIntent(), app.packageName, AndroidSystemRepository.HOME_ALIAS_CLASS)
        repository.start()
        assertTrue(repository.device.value.isDefaultHome)
        repository.stop()
    }

    @Test
    fun `the home alias is switched on and back to its manifest default`() {
        val alias = ComponentName(app.packageName, "tv.monotv.launcher.HomeAlias")
        val repository = repository()

        assertTrue(repository.setHomeAliasEnabled(true))
        assertTrue(repository.device.value.homeAliasEnabled)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, packageManager.getComponentEnabledSetting(alias))

        assertTrue(repository.setHomeAliasEnabled(false))
        assertFalse(repository.device.value.homeAliasEnabled)
        assertEquals(
            "disabling means back to the manifest default, never an explicit DISABLED",
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            packageManager.getComponentEnabledSetting(alias),
        )
    }

    @Test
    @Config(sdk = [34], shadows = [UnknownComponentPackageManager::class])
    fun `a rejected alias change is reported as failure`() {
        val repository = repository()
        assertFalse(repository.setHomeAliasEnabled(true))
        assertFalse(repository.device.value.homeAliasEnabled)
        assertFalse(repository.setHomeAliasEnabled(false))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Opening system screens
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `nothing is started when nothing resolves`() {
        val repository = repository()
        assertFalse(repository.openSystemSettings())
        assertFalse(repository.openNetworkSettings())
        assertFalse(repository.openHomeChooser())
        assertNull(shadowOf(app).peekNextStartedActivity())
    }

    @Test
    fun `system settings are opened in a new task`() {
        answer(Intent(Settings.ACTION_SETTINGS), TV_SETTINGS, "com.android.tv.settings.MainSettings")

        assertTrue(repository().openSystemSettings())

        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_SETTINGS, started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull(shadowOf(app).peekNextStartedActivity())
    }

    @Test
    fun `network settings prefer the wifi screen and fall back step by step`() {
        answer(Intent(Settings.ACTION_SETTINGS), TV_SETTINGS, "com.android.tv.settings.MainSettings")
        assertTrue(repository().openNetworkSettings())
        assertEquals(Settings.ACTION_SETTINGS, shadowOf(app).nextStartedActivity.action)

        answer(Intent(Settings.ACTION_WIRELESS_SETTINGS), TV_SETTINGS, "com.android.tv.settings.connectivity.NetworkActivity")
        assertTrue(repository().openNetworkSettings())
        assertEquals(Settings.ACTION_WIRELESS_SETTINGS, shadowOf(app).nextStartedActivity.action)

        answer(Intent(Settings.ACTION_WIFI_SETTINGS), TV_SETTINGS, "com.android.tv.settings.connectivity.WifiActivity")
        assertTrue(repository().openNetworkSettings())
        val started = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_WIFI_SETTINGS, started.action)
        assertTrue(started.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertNull("exactly one screen is opened", shadowOf(app).peekNextStartedActivity())
    }

    @Test
    fun `a factory menu answering a settings intent is never started`() {
        answer(Intent(Settings.ACTION_SETTINGS), "com.cvte.fac.menu", "com.cvte.fac.menu.MainActivity")
        answer(Intent(Settings.ACTION_WIFI_SETTINGS), "com.stb.factorymenu", "com.stb.factorymenu.Shortcut")
        answer(Intent(Settings.ACTION_HOME_SETTINGS), "com.vendor.engineermode", "com.vendor.engineermode.Main")

        val repository = repository()
        assertFalse(repository.openSystemSettings())
        assertFalse(repository.openNetworkSettings())
        assertFalse(repository.openHomeChooser())
        assertNull(shadowOf(app).peekNextStartedActivity())
    }

    @Test
    fun `a start that the system rejects is reported as false`() {
        answer(Intent(Settings.ACTION_SETTINGS), TV_SETTINGS, "com.android.tv.settings.MainSettings")
        answer(Intent(Settings.ACTION_WIFI_SETTINGS), TV_SETTINGS, "com.android.tv.settings.connectivity.WifiActivity")
        val rejecting = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun startActivity(intent: Intent?) {
                throw SecurityException("not exported")
            }

            override fun startActivity(intent: Intent?, options: Bundle?) {
                throw SecurityException("not exported")
            }
        }
        val repository = repository(rejecting)
        assertFalse(repository.openSystemSettings())
        assertFalse(repository.openNetworkSettings())
        assertFalse(repository.openHomeChooser())
    }

    @Test
    fun `the home chooser prefers the system screen and falls back to the HOME intent`() {
        answer(homeIntent(), STOCK_LAUNCHER, "ru.sberdevices.starlauncher.ui.mainscreen.MainActivity")
        assertTrue(repository().openHomeChooser())
        val fallback = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_MAIN, fallback.action)
        assertTrue(fallback.hasCategory(Intent.CATEGORY_HOME))
        assertTrue(fallback.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)

        answer(Intent(Settings.ACTION_HOME_SETTINGS), TV_SETTINGS, "com.android.tv.settings.HomeSettings")
        assertTrue(repository().openHomeChooser())
        assertEquals(Settings.ACTION_HOME_SETTINGS, shadowOf(app).nextStartedActivity.action)
        assertNull(shadowOf(app).peekNextStartedActivity())
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the diagnostics report is written to the external files dir and starts nothing`() {
        val path = repository().writeDiagnosticsReport()

        assertNotNull(path)
        val file = File(path!!)
        assertEquals(File(app.getExternalFilesDir(null), "device-report.txt"), file)
        val text = file.readText(Charsets.UTF_8)
        assertEquals(DiagnosticsReport.FILE_NAME, file.name)
        assertTrue(text.startsWith("== Mono TV — отчёт об устройстве =="))
        assertTrue(text.contains("== Build =="))
        assertTrue(text.contains("== Known system packages =="))
        assertNull(shadowOf(app).peekNextStartedActivity())
        assertNull(shadowOf(app).peekNextStartedService())
    }

    @Test
    fun `writing the report again replaces the old file`() {
        val repository = repository()
        val file = File(repository.writeDiagnosticsReport()!!)
        file.writeText("stale")
        assertEquals(file.path, repository.writeDiagnosticsReport())
        assertTrue(file.readText().contains("== Display =="))
    }

    @Test
    fun `no external storage means no report`() {
        val noStorage = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            override fun getExternalFilesDir(type: String?): File? = null
        }
        assertNull(repository(noStorage).writeDiagnosticsReport())
    }

    @Test
    fun `an unwritable report location yields null`() {
        val blocked = object : ContextWrapper(app) {
            override fun getApplicationContext(): Context = this
            // A regular file where the directory should be: creating the report inside it must fail.
            override fun getExternalFilesDir(type: String?): File = File(app.cacheDir, "not-a-directory").apply { writeText("x") }
        }
        assertNull(repository(blocked).writeDiagnosticsReport())
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------------------------------

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime.of(year, month, day, hour, minute).atZone(ZoneId.of("Europe/Moscow")).toInstant().toEpochMilli()

    private fun broadcast(action: String) {
        app.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun clockReceivers(): Int =
        shadowOf(app).registeredReceivers.count { it.intentFilter.hasAction(Intent.ACTION_TIME_TICK) }

    private fun capabilities(transport: Int, validated: Boolean): NetworkCapabilities {
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(transport)
        if (validated) shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return capabilities
    }

    private fun activityManager(): ActivityManager = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private fun setTotalRam(megabytes: Int) {
        shadowOf(activityManager()).setMemoryInfo(ActivityManager.MemoryInfo().apply { totalMem = megabytes * 1024L * 1024L })
    }

    private fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /**
     * Makes [packageName]/[className] the activity that resolves [intent]. Robolectric deprecates this shortcut in
     * favour of installing whole packages with intent filters; for "this intent resolves to that component" it is
     * still the most direct way to say it.
     */
    @Suppress("DEPRECATION")
    private fun answer(intent: Intent, packageName: String, className: String) {
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                this.packageName = packageName
                name = className
                applicationInfo = ApplicationInfo().apply { this.packageName = packageName }
            }
        }
        shadowOf(packageManager).addResolveInfoForIntent(intent, info)
    }

    /**
     * A PackageManager that does not know our HOME alias — what the real one answers (with an
     * IllegalArgumentException) on a build whose manifest lacks it. Every other component behaves normally.
     */
    @Implements(className = "android.app.ApplicationPackageManager", isInAndroidSdk = false)
    class UnknownComponentPackageManager : ShadowApplicationPackageManager() {
        @Implementation
        override fun setComponentEnabledSetting(componentName: ComponentName, newState: Int, flags: Int) {
            if (componentName.className == AndroidSystemRepository.HOME_ALIAS_CLASS) {
                throw IllegalArgumentException("Unknown component: $componentName")
            }
            super.setComponentEnabledSetting(componentName, newState, flags)
        }

        @Implementation
        override fun getComponentEnabledSetting(componentName: ComponentName): Int {
            if (componentName.className == AndroidSystemRepository.HOME_ALIAS_CLASS) {
                throw IllegalArgumentException("Unknown component: $componentName")
            }
            return super.getComponentEnabledSetting(componentName)
        }
    }

    private companion object {
        const val STOCK_LAUNCHER = "ru.sberdevices.starlauncher"
        const val TV_SETTINGS = "com.android.tv.settings"
    }
}
