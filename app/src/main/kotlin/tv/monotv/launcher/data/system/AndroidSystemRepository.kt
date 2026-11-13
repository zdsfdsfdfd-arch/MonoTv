package tv.monotv.launcher.data.system

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat
import android.util.DisplayMetrics
import android.view.WindowManager
import java.io.File
import java.io.IOException
import java.util.TimeZone
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import tv.monotv.launcher.BuildConfig
import tv.monotv.launcher.core.ClockState
import tv.monotv.launcher.core.DeviceInfo
import tv.monotv.launcher.core.EffectsLevel
import tv.monotv.launcher.core.LauncherConfig
import tv.monotv.launcher.core.NetworkKind
import tv.monotv.launcher.core.NetworkState
import tv.monotv.launcher.data.SystemRepository
import tv.monotv.launcher.data.apps.LauncherConfigLoader

/**
 * [SystemRepository] backed by the plain public Android APIs — nothing Sber- or Google-specific, so it behaves the
 * same on StarOS (Android 9 / 11 / 13), on a stock Android TV box and on a phone used for development.
 *
 * How it stays cheap and harmless:
 *  - the clock follows the system's own once-a-minute `TIME_TICK` broadcast and the network state follows a
 *    connectivity callback; there is no timer, handler loop or polling anywhere;
 *  - everything is registered in [start] and released in [stop], so a launcher sitting in the background behind a
 *    video app does not wake up at all;
 *  - it only reads system state. The single thing it ever changes is the enabled state of this app's own HOME
 *    alias, see [setHomeAliasEnabled].
 *
 * [start], [stop] and [refreshDevice] belong to the main thread; the flows may be collected anywhere.
 * Only the application context is kept.
 */
class AndroidSystemRepository internal constructor(
    context: Context,
    /** Source of "now" in epoch milliseconds; replaced in tests so clock updates can be observed. */
    private val now: () -> Long,
) : SystemRepository {

    constructor(context: Context) : this(context, System::currentTimeMillis)

    private val appContext: Context = context.applicationContext ?: context
    private val packageManager: PackageManager = appContext.packageManager
    private val connectivity: ConnectivityManager? = try {
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    } catch (e: RuntimeException) {
        null
    }

    /** Our optional HOME entry point; the name is fixed by the manifest's `<activity-alias>`. */
    private val homeAlias = ComponentName(appContext.packageName, HOME_ALIAS_CLASS)

    private val clockState = MutableStateFlow(currentClock())
    private val networkState = MutableStateFlow(readActiveNetwork())
    private val deviceState = MutableStateFlow(readDevice())

    override val clock: StateFlow<ClockState> = clockState.asStateFlow()
    override val network: StateFlow<NetworkState> = networkState.asStateFlow()
    override val device: StateFlow<DeviceInfo> = deviceState.asStateFlow()

    private var started = false
    private var clockReceiverRegistered = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            clockState.value = currentClock()
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Starts following the clock and the network. All three states are re-read right away: while the launcher was
     * stopped no broadcast reached it, and coming back from the system's HOME chooser is exactly the moment the
     * HOME fields may have changed.
     */
    override fun start() {
        if (started) return
        started = true

        clockState.value = currentClock()
        registerClockReceiver()

        // Read first, register second: the callback then only ever delivers news that is newer than this snapshot.
        networkState.value = readActiveNetwork()
        registerNetworkCallback()

        refreshDevice()
    }

    override fun stop() {
        if (!started) return
        started = false

        if (clockReceiverRegistered) {
            clockReceiverRegistered = false
            try {
                appContext.unregisterReceiver(clockReceiver)
            } catch (e: RuntimeException) {
                // Already gone (the system may drop receivers of a process it is tearing down).
            }
        }

        networkCallback?.let { callback ->
            networkCallback = null
            try {
                connectivity?.unregisterNetworkCallback(callback)
            } catch (e: RuntimeException) {
                // Not registered any more; nothing left to release.
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Clock
    // -----------------------------------------------------------------------------------------------------------------

    private fun registerClockReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_DATE_CHANGED)
            // The 12 / 24-hour default follows the locale when the user never chose one explicitly.
            addAction(Intent.ACTION_LOCALE_CHANGED)
        }
        clockReceiverRegistered = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Only the system sends these; no other app has any business delivering them to us.
                appContext.registerReceiver(clockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                appContext.registerReceiver(clockReceiver, filter)
            }
            true
        } catch (e: RuntimeException) {
            false
        }
    }

    private fun currentClock(): ClockState = ClockFormatter.format(now(), TimeZone.getDefault(), is24HourFormat())

    /** Reads the system setting (never writes it). Falls back to the 24-hour clock that is the norm in Russia. */
    private fun is24HourFormat(): Boolean = try {
        DateFormat.is24HourFormat(appContext)
    } catch (e: RuntimeException) {
        true
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Network
    // -----------------------------------------------------------------------------------------------------------------

    private fun registerNetworkCallback() {
        val manager = connectivity ?: return
        val callback = DefaultNetworkCallback()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                manager.registerDefaultNetworkCallback(callback)
            } else {
                // API 23 cannot follow "the default network" directly, so watch every internet-capable network and
                // let the callback re-read which one is active.
                val request = NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build()
                manager.registerNetworkCallback(request, callback)
            }
            networkCallback = callback
        } catch (e: RuntimeException) {
            // SecurityException without ACCESS_NETWORK_STATE, or the per-app callback limit: the state stays at
            // whatever start() has just read.
        }
    }

    /** Synchronous snapshot of the default network; [NetworkKind.Offline] when there is none or it cannot be read. */
    private fun readActiveNetwork(): NetworkState {
        val manager = connectivity ?: return OFFLINE
        return try {
            val network = manager.activeNetwork ?: return OFFLINE
            networkStateOf(manager.getNetworkCapabilities(network))
        } catch (e: RuntimeException) {
            OFFLINE
        }
    }

    /**
     * Delivered on the connectivity thread, in order. From Android 8 on every `onAvailable` is guaranteed to be
     * followed by `onCapabilitiesChanged`, so the callback arguments alone describe the default network; earlier
     * versions make no such promise and get a fresh read of the active network on every event instead.
     */
    private inner class DefaultNetworkCallback : ConnectivityManager.NetworkCallback() {

        private val readsActiveNetwork = Build.VERSION.SDK_INT < Build.VERSION_CODES.O

        /** The network the last capabilities belonged to. Touched on the connectivity thread only. */
        private var current: Network? = null

        override fun onAvailable(network: Network) {
            if (readsActiveNetwork) networkState.value = readActiveNetwork() else current = network
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (readsActiveNetwork) {
                networkState.value = readActiveNetwork()
            } else {
                current = network
                networkState.value = networkStateOf(networkCapabilities)
            }
        }

        override fun onLost(network: Network) {
            if (readsActiveNetwork) {
                networkState.value = readActiveNetwork()
            } else if (current == null || current == network) {
                // A late "lost" for a network that has already been replaced must not blank a working connection.
                current = null
                networkState.value = OFFLINE
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Device
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Re-reads which app answers the HOME button and whether our alias is enabled. Two quick PackageManager calls;
     * the hardware facts never change while the process lives and are not read again.
     */
    override fun refreshDevice() {
        val homePackage = currentHomePackage()
        val aliasEnabled = isHomeAliasEnabled()
        deviceState.update { device ->
            device.copy(
                currentHomePackage = homePackage,
                isDefaultHome = homePackage == appContext.packageName,
                homeAliasEnabled = aliasEnabled,
            )
        }
    }

    override fun recommendedEffects(): EffectsLevel {
        val device = deviceState.value
        return PerformanceTier.recommend(device.totalRamMb, device.lowRam, device.cpuCores, device.sdkInt)
    }

    private fun readDevice(): DeviceInfo {
        val display = PlatformProbe.displaySize(appContext)
        val memory = PlatformProbe.memoryInfo(appContext)
        val homePackage = currentHomePackage()
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            model = Build.MODEL.orEmpty(),
            device = Build.DEVICE.orEmpty(),
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            widthPx = display.widthPx,
            heightPx = display.heightPx,
            densityDpi = display.densityDpi,
            totalRamMb = memory?.let { (it.totalMem / BYTES_PER_MB).toInt() } ?: 0,
            lowRam = PlatformProbe.isLowRamDevice(appContext),
            cpuCores = Runtime.getRuntime().availableProcessors(),
            hasLeanback = hasFeature(PackageManager.FEATURE_LEANBACK),
            launcherVersion = BuildConfig.VERSION_NAME,
            currentHomePackage = homePackage,
            isDefaultHome = homePackage == appContext.packageName,
            homeAliasEnabled = isHomeAliasEnabled(),
        )
    }

    private fun hasFeature(name: String): Boolean = try {
        packageManager.hasSystemFeature(name)
    } catch (e: RuntimeException) {
        false
    }

    // -----------------------------------------------------------------------------------------------------------------
    // HOME role
    // -----------------------------------------------------------------------------------------------------------------

    private fun currentHomePackage(): String? = PlatformProbe.currentHome(packageManager)?.packageName

    private fun isHomeAliasEnabled(): Boolean = try {
        packageManager.getComponentEnabledSetting(homeAlias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    } catch (e: RuntimeException) {
        // An unknown component (a build without the alias) is simply "not enabled".
        false
    }

    /**
     * Switches this app's own `HomeAlias` activity-alias on or off.
     *
     * THIS IS THE ONLY COMPONENT-STATE CHANGE IN THE WHOLE APP, and it can only ever name a component of our own
     * package — Android does not let an ordinary app touch anybody else's. Enabling the alias merely adds Mono TV
     * to the list of HOME candidates; which one runs is then chosen by the user in the system's own dialog. The
     * stock launcher is never disabled, hidden or modified in any way: on some Sber firmwares a missing stock
     * launcher ends in a reboot loop, so "undo" must always be nothing more than flipping this one switch back
     * (the alias returns to its manifest default, disabled, and HOME falls back to the stock launcher by itself).
     *
     * `DONT_KILL_APP` keeps the launcher running across the change.
     *
     * @return true when the alias is now in the requested state
     */
    override fun setHomeAliasEnabled(enabled: Boolean): Boolean {
        val newState = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
        }
        val applied = try {
            packageManager.setComponentEnabledSetting(homeAlias, newState, PackageManager.DONT_KILL_APP)
            true
        } catch (e: RuntimeException) {
            false
        }
        refreshDevice()
        return applied && deviceState.value.homeAliasEnabled == enabled
    }

    /**
     * Opens the system screen where the default home app is chosen. Where the firmware has no such screen, the
     * HOME intent itself is started: with several candidates and no default yet the system then asks which one
     * to use (with a default already set it simply shows that home screen).
     */
    override fun openHomeChooser(): Boolean =
        appContext.startActivitySafely(Intent(Settings.ACTION_HOME_SETTINGS)) ||
            appContext.startActivitySafely(PlatformProbe.homeIntent())

    // -----------------------------------------------------------------------------------------------------------------
    // System screens
    // -----------------------------------------------------------------------------------------------------------------

    /** Wi-Fi settings where the firmware has them, the broader network page next, the settings root as last resort. */
    override fun openNetworkSettings(): Boolean =
        NETWORK_SETTINGS_ACTIONS.any { action -> appContext.startActivitySafely(Intent(action)) }

    override fun openSystemSettings(): Boolean = appContext.startActivitySafely(Intent(Settings.ACTION_SETTINGS))

    // -----------------------------------------------------------------------------------------------------------------
    // Diagnostics
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Writes [DiagnosticsReport] to `device-report.txt` in the app's external files directory — the one place a
     * user can reach with a file manager or a USB stick, without ADB. Collecting the report walks every installed
     * launcher activity, so call this off the main thread.
     *
     * @return the absolute path, or null when external storage is unavailable or the file could not be written
     */
    override fun writeDiagnosticsReport(): String? = try {
        val directory = appContext.getExternalFilesDir(null)
        if (directory == null) {
            null
        } else {
            val file = File(directory, DiagnosticsReport.FILE_NAME)
            file.writeText(DiagnosticsReport.build(appContext), Charsets.UTF_8)
            file.absolutePath
        }
    } catch (e: IOException) {
        null
    } catch (e: RuntimeException) {
        null
    }

    companion object {
        /**
         * Class name of the optional HOME `<activity-alias>` declared (disabled by default) in the manifest.
         * Fully qualified on purpose: it must not change if the applicationId ever gets a suffix.
         */
        const val HOME_ALIAS_CLASS = "tv.monotv.launcher.HomeAlias"

        private const val BYTES_PER_MB = 1024L * 1024L

        private val OFFLINE = NetworkState(NetworkKind.Offline, validated = false)

        private val NETWORK_SETTINGS_ACTIONS = listOf(
            Settings.ACTION_WIFI_SETTINGS,
            Settings.ACTION_WIRELESS_SETTINGS,
            Settings.ACTION_SETTINGS,
        )

        /**
         * Wired wins when a network reports both transports (a VPN running over several links does): a cable is the
         * more telling fact about a TV. Cellular, Bluetooth tethering and the like are all just "other".
         * A null [capabilities] means the network vanished between two calls.
         */
        internal fun networkStateOf(capabilities: NetworkCapabilities?): NetworkState {
            if (capabilities == null) return OFFLINE
            val kind = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Ethernet
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
                else -> NetworkKind.Other
            }
            return NetworkState(kind, capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }
    }
}

/** Display size in physical pixels of the UI resolution (not of the panel) and the density the UI is laid out for. */
internal class DisplaySize(val widthPx: Int, val heightPx: Int, val densityDpi: Int)

/**
 * Read-only questions to the platform that both [AndroidSystemRepository] and [DiagnosticsReport] ask.
 * Every function swallows the platform's runtime exceptions and answers with "unknown" instead.
 */
internal object PlatformProbe {

    /** `packageName` of the framework's "choose an app" dialog, which answers when no default is set. */
    private const val RESOLVER_PACKAGE = "android"

    fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /**
     * The activity the HOME button currently leads to, or null when that is undecided — the system resolver
     * answering means there are several candidates and the user has not picked a default yet.
     */
    fun currentHome(packageManager: PackageManager): ComponentName? = try {
        // Intent.resolveActivity asks with MATCH_DEFAULT_ONLY, exactly like the system does for a HOME press.
        homeIntent().resolveActivity(packageManager)?.takeUnless { it.packageName == RESOLVER_PACKAGE }
    } catch (e: RuntimeException) {
        null
    }

    /**
     * The real size of the default display: the full 1920×1080 (or 1280×720) the UI is rendered at, including any
     * area the system keeps for itself.
     *
     * The window manager is asked through the application context on purpose — the repository must not hold an
     * activity. This is a pure read of the display configuration, which does not depend on a window.
     */
    fun displaySize(context: Context): DisplaySize {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            if (windowManager != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val bounds = windowManager.maximumWindowMetrics.bounds
                    return DisplaySize(bounds.width(), bounds.height(), context.resources.configuration.densityDpi)
                }
                val metrics = DisplayMetrics()
                @Suppress("DEPRECATION") // getRealMetrics is the only source of the real size before API 30.
                windowManager.defaultDisplay.getRealMetrics(metrics)
                return DisplaySize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
            }
        } catch (e: RuntimeException) {
            // Fall through to the resources' view of the display.
        }
        val metrics = context.resources.displayMetrics
        return DisplaySize(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
    }

    fun activityManager(context: Context): ActivityManager? = try {
        context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    } catch (e: RuntimeException) {
        null
    }

    fun memoryInfo(context: Context): ActivityManager.MemoryInfo? = try {
        activityManager(context)?.let { manager -> ActivityManager.MemoryInfo().also(manager::getMemoryInfo) }
    } catch (e: RuntimeException) {
        null
    }

    fun isLowRamDevice(context: Context): Boolean = try {
        activityManager(context)?.isLowRamDevice ?: false
    } catch (e: RuntimeException) {
        false
    }
}

/** The blocklist no configuration file can lift, see [LauncherConfigLoader.withSafetyFloor]. */
private val safetyFloor: LauncherConfig = LauncherConfigLoader.withSafetyFloor(LauncherConfig())

/**
 * True for factory, engineering and service menus. Opening one by accident can leave a TV unusable, so nothing in
 * this module may ever start such a package — whatever intent happened to resolve to it.
 */
internal fun isForbiddenPackage(packageName: String): Boolean = safetyFloor.isBlocked(packageName)

/**
 * The activity that would handle [intent], or null when nothing handles it or the answer is a
 * [forbidden][isForbiddenPackage] package.
 */
internal fun Context.resolveSafeActivity(intent: Intent): ComponentName? = try {
    intent.resolveActivity(packageManager)?.takeUnless { isForbiddenPackage(it.packageName) }
} catch (e: RuntimeException) {
    null
}

/**
 * The one way this module starts an activity of another app: resolve first, refuse forbidden targets, start in a
 * new task (the caller is the application context), and report failure as `false` instead of throwing — the
 * target may be missing, not exported or protected by a permission we do not hold.
 */
internal fun Context.startActivitySafely(intent: Intent): Boolean {
    if (resolveSafeActivity(intent) == null) return false
    return try {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: RuntimeException) {
        false
    }
}
