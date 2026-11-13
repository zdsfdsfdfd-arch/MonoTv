package tv.monotv.launcher.data.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.hardware.display.DisplayManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.view.Display
import androidx.core.content.pm.PackageInfoCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import tv.monotv.launcher.BuildConfig
import tv.monotv.launcher.core.LauncherConfig
import tv.monotv.launcher.data.apps.LauncherConfigLoader

/**
 * A plain-text description of the device the launcher is running on.
 *
 * Sber TVs cannot be inspected the usual way — ADB is model dependent and often unavailable — so this report is how
 * a developer learns what a particular firmware looks like: which HOME candidates exist, which launcher categories
 * its apps use, how the HDMI inputs are exposed, which system packages are present. The user saves it from
 * Settings → Система and copies the file off the TV with a file manager.
 *
 * STRICTLY READ-ONLY. Everything here is a query: `Build` fields, PackageManager look-ups and the TV input list.
 * No activity or service is started, no component or setting is changed, nothing is sent anywhere (the app has no
 * network permission) and the factory / service menus are only ever *named*, never resolved into something
 * launchable. Each section is collected independently, so one API that a firmware has broken costs one section
 * and not the whole report.
 */
object DiagnosticsReport {

    /** Name of the file the report is saved as, in the app's external files directory. */
    const val FILE_NAME = "device-report.txt"

    /** Sber's private launcher category; some of their own apps (their YouTube client, for one) declare only this. */
    private const val CATEGORY_STAR_LAUNCHER = "ru.sberdevices.category.STAR_LAUNCHER"

    private const val GOOGLE_PLAY_SERVICES = "com.google.android.gms"

    private const val PERMISSION_QUERY_ALL_PACKAGES = "android.permission.QUERY_ALL_PACKAGES"

    /** Width of the canvas, in dp, every Sber TV maps to (1920 px at 320 dpi, 1280 px at 213 dpi). */
    private const val DESIGN_WIDTH_DP = 960
    private const val DESIGN_HEIGHT_DP = 540

    /** More entries than any TV has; only stops a pathological device from producing a multi-megabyte file. */
    private const val MAX_ACTIVITIES_PER_CATEGORY = 500

    private const val BYTES_PER_MB = 1024L * 1024L

    /** Marks packages the launcher refuses to list or start, so the report shows the guard at work. */
    private val forbidden: LauncherConfig = LauncherConfigLoader.withSafetyFloor(LauncherConfig())

    /**
     * System packages worth knowing about on a Sber device, with what each one is. They are only looked up by name.
     * Listing the factory menus here is deliberate: whether they exist is precisely what a developer needs to know
     * to trust the blocklist.
     */
    private val KNOWN_SYSTEM_PACKAGES: List<Pair<String, String>> = listOf(
        "ru.sberdevices.starlauncher" to "stock launcher",
        "com.android.tv.settings" to "TV settings, FallbackHome",
        "ru.sberdevices.settings" to "Sber settings",
        "com.sberbank.settings" to "Sber settings (older firmwares)",
        "ru.sberdevices.assistant" to "voice assistant",
        "ru.sberdevices.services" to "Sber services",
        "ru.sberdevices.services.core" to "Sber services core",
        "ru.sberdevices.SberDevicesServiceSystem" to "Sber platform service",
        "ru.sberdevices.starmarket" to "app store",
        "ru.sberdevices.updater" to "OTA updater",
        "ru.sberdevices.rcfupdater" to "remote control firmware updater",
        "ru.sberdevices.tvinputs" to "HDMI / AV input viewer",
        "ru.sberdevices.starlivetv" to "live TV",
        "ru.sberdevices.tv" to "Sber TV app",
        "ru.sberdevices.startube" to "Sber YouTube client",
        "ru.sberdevices.starbrowser" to "browser",
        "ru.sberdevices.app.starkeyboard" to "on-screen keyboard (the only IME)",
        "ru.sberdevices.webview" to "WebView",
        "ru.sberdevices.systemui" to "system UI",
        "ru.sberdevices.starprofile" to "Sber ID",
        "ru.sberdevices.starscreensaver" to "screensaver",
        "ru.sberdevices.gamepad.service" to "gamepad service",
        "com.droidlogic.tvinput" to "Amlogic TV inputs",
        "com.mediatek.tvinput" to "MediaTek TV inputs",
        "com.mediatek.tv.service" to "MediaTek TV service",
        "com.cvte.fac.menu" to "FACTORY MENU — never listed or started",
        "com.stb.factorymenu" to "factory menu shortcut — never listed or started",
    )

    /**
     * Collects the report. Makes a few dozen binder calls and loads the label of every launchable activity, so it
     * belongs on a background thread. Never throws and never starts anything.
     */
    fun build(context: Context): String {
        val appContext = context.applicationContext ?: context
        val packageManager = appContext.packageManager
        return render(
            listOf(
                section("Mono TV — отчёт об устройстве") { header(appContext) },
                section("Build") { buildFields() },
                section("Display") { display(appContext) },
                section("Memory") { memory(appContext) },
                section("Features") { features(packageManager) },
                section("HOME candidates") { homeCandidates(packageManager) },
                section("Launchable activities: ${Intent.CATEGORY_LEANBACK_LAUNCHER}") {
                    launchables(packageManager, Intent.CATEGORY_LEANBACK_LAUNCHER)
                },
                section("Launchable activities: $CATEGORY_STAR_LAUNCHER") {
                    launchables(packageManager, CATEGORY_STAR_LAUNCHER)
                },
                section("Launchable activities: ${Intent.CATEGORY_LAUNCHER}") {
                    launchables(packageManager, Intent.CATEGORY_LAUNCHER)
                },
                section("TV inputs") { tvInputs(appContext) },
                section("Known system packages") { knownPackages(packageManager) },
            ),
        )
    }

    /**
     * Lays out titled sections as text: `== Title ==`, one line per entry, a blank line between sections.
     * Line breaks and control characters inside an entry are flattened to spaces (app labels are arbitrary text),
     * so "one entry, one line" holds and the file stays easy to search.
     */
    fun render(sections: List<Pair<String, List<String>>>): String = buildString {
        sections.forEachIndexed { index, (title, lines) ->
            if (index > 0) append('\n')
            append("== ").append(singleLine(title)).append(" ==\n")
            if (lines.isEmpty()) {
                append("(нет данных)\n")
            } else {
                for (line in lines) append(singleLine(line)).append('\n')
            }
        }
    }

    private fun singleLine(text: String): String =
        if (text.none { it.isISOControl() }) text else text.map { if (it.isISOControl()) ' ' else it }.joinToString("")

    private inline fun section(title: String, collect: () -> List<String>): Pair<String, List<String>> =
        title to try {
            collect()
        } catch (e: RuntimeException) {
            listOf("недоступно: ${e.javaClass.simpleName}: ${e.message}")
        }

    // -----------------------------------------------------------------------------------------------------------------
    // Sections
    // -----------------------------------------------------------------------------------------------------------------

    private fun header(context: Context): List<String> {
        val seesAllPackages = Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            context.checkSelfPermission(PERMISSION_QUERY_ALL_PACKAGES) == PackageManager.PERMISSION_GRANTED
        return listOf(
            "Только чтение: отчёт ничего не меняет в системе, ничего не запускает и никуда не отправляется.",
            "generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())}",
            "launcher: ${context.packageName} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            if (seesAllPackages) {
                "package visibility: full"
            } else {
                "package visibility: FILTERED — списки приложений и пакетов ниже могут быть неполными"
            },
        )
    }

    private fun buildFields(): List<String> = listOf(
        "manufacturer: ${Build.MANUFACTURER}",
        "brand: ${Build.BRAND}",
        "model: ${Build.MODEL}",
        "device: ${Build.DEVICE}",
        "product: ${Build.PRODUCT}",
        "hardware: ${Build.HARDWARE}",
        "board: ${Build.BOARD}",
        "fingerprint: ${Build.FINGERPRINT}",
        "build id: ${Build.DISPLAY}",
        "release: ${Build.VERSION.RELEASE}",
        "sdk: ${Build.VERSION.SDK_INT}",
        "security patch: ${Build.VERSION.SECURITY_PATCH}",
        "supported ABIs: ${Build.SUPPORTED_ABIS?.joinToString(", ").orEmpty()}",
    )

    private fun display(context: Context): List<String> {
        val size = PlatformProbe.displaySize(context)
        val lines = ArrayList<String>()
        lines += "size: ${size.widthPx}x${size.heightPx} px"
        lines += "density: ${size.densityDpi} dpi"
        if (size.densityDpi > 0) {
            val widthDp = size.widthPx * 160f / size.densityDpi
            val heightDp = size.heightPx * 160f / size.densityDpi
            val matches = Math.round(widthDp) == DESIGN_WIDTH_DP && Math.round(heightDp) == DESIGN_HEIGHT_DP
            lines += "canvas: ${decimal(widthDp)}x${decimal(heightDp)} dp " +
                "(design canvas ${DESIGN_WIDTH_DP}x$DESIGN_HEIGHT_DP dp: ${if (matches) "matches" else "DIFFERS"})"
        }
        val defaultDisplay = (context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
        if (defaultDisplay != null) {
            lines += "refresh rate: ${decimal(defaultDisplay.refreshRate)} Hz"
            // The panel mode can be larger than the UI: 4K sets still render the interface at 1080p.
            val mode = defaultDisplay.mode
            lines += "panel mode: ${mode.physicalWidth}x${mode.physicalHeight} @ ${decimal(mode.refreshRate)} Hz"
        }
        return lines
    }

    private fun memory(context: Context): List<String> {
        val lines = ArrayList<String>()
        val info = PlatformProbe.memoryInfo(context)
        val totalMb = info?.let { (it.totalMem / BYTES_PER_MB).toInt() } ?: 0
        val lowRam = PlatformProbe.isLowRamDevice(context)
        val cores = Runtime.getRuntime().availableProcessors()
        if (info != null) {
            lines += "total: $totalMb MB"
            lines += "available now: ${info.availMem / BYTES_PER_MB} MB"
            lines += "low-memory threshold: ${info.threshold / BYTES_PER_MB} MB"
        } else {
            lines += "total: unknown"
        }
        lines += "lowRam: ${yesNo(lowRam)}"
        PlatformProbe.activityManager(context)?.let { manager ->
            lines += "memoryClass: ${manager.memoryClass} MB"
            lines += "largeMemoryClass: ${manager.largeMemoryClass} MB"
        }
        lines += "cpu cores: $cores"
        lines += "recommended effects: ${PerformanceTier.recommend(totalMb, lowRam, cores, Build.VERSION.SDK_INT)}"
        return lines
    }

    private fun features(packageManager: PackageManager): List<String> {
        val features = listOf(
            "leanback" to PackageManager.FEATURE_LEANBACK,
            "leanback_only" to PackageManager.FEATURE_LEANBACK_ONLY,
            "live_tv" to PackageManager.FEATURE_LIVE_TV,
            "touchscreen" to PackageManager.FEATURE_TOUCHSCREEN,
            "wifi" to PackageManager.FEATURE_WIFI,
            "ethernet" to PackageManager.FEATURE_ETHERNET,
            "bluetooth" to PackageManager.FEATURE_BLUETOOTH,
        )
        return features.map { (name, feature) -> "$name: ${yesNo(packageManager.hasSystemFeature(feature))}" } +
            "google play services: ${yesNo(packageInfo(packageManager, GOOGLE_PLAY_SERVICES) != null)}"
    }

    private fun homeCandidates(packageManager: PackageManager): List<String> {
        val current = PlatformProbe.currentHome(packageManager)
        val lines = ArrayList<String>()
        lines += if (current != null) {
            "current default: ${current.flattenToShortString()}"
        } else {
            "current default: none — отвечает системный диалог выбора (несколько кандидатов, ни один не выбран)"
        }
        // Disabled candidates are listed too: a disabled stock launcher is exactly the kind of thing worth seeing.
        val candidates = queryActivities(packageManager, PlatformProbe.homeIntent(), PackageManager.MATCH_DISABLED_COMPONENTS)
            .sortedWith(compareByDescending<ResolveInfo> { it.priority }.thenBy { componentOf(it).flattenToShortString() })
        for (candidate in candidates) {
            val component = componentOf(candidate)
            lines += listOf(
                component.flattenToShortString(),
                "priority=${candidate.priority}",
                "isDefault=${candidate.isDefault}",
                origin(candidate.activityInfo.applicationInfo),
                "state=${componentState(packageManager, component)}",
            ).joinToString(" | ") + if (component == current) " | CURRENT" else ""
        }
        return lines
    }

    /** Activities a launcher would offer for [category], the way PackageManager reports them (enabled ones only). */
    private fun launchables(packageManager: PackageManager, category: String): List<String> {
        val activities = queryActivities(packageManager, Intent(Intent.ACTION_MAIN).addCategory(category), 0)
        val lines = activities.take(MAX_ACTIVITIES_PER_CATEGORY).map { info ->
            val activity = info.activityInfo
            val label = try {
                info.loadLabel(packageManager).toString()
            } catch (e: RuntimeException) {
                activity.name
            }
            val hasBanner = activity.banner != 0 || activity.applicationInfo.banner != 0
            listOf(
                label,
                componentOf(info).flattenToShortString(),
                origin(activity.applicationInfo),
                "banner=${yesNo(hasBanner)}",
            ).joinToString(" | ") + if (forbidden.isBlocked(activity.packageName)) " | BLOCKED: не показывается и не запускается" else ""
        }.sorted()
        return if (activities.size > MAX_ACTIVITIES_PER_CATEGORY) {
            lines + "… ещё ${activities.size - MAX_ACTIVITIES_PER_CATEGORY}"
        } else {
            lines
        }
    }

    private fun tvInputs(context: Context): List<String> {
        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
            ?: return listOf("TvInputManager: недоступен")
        val inputs: List<TvInputInfo> = manager.tvInputList ?: emptyList()
        if (inputs.isEmpty()) return listOf("TvInputManager: доступен, входов нет")
        return inputs.sortedBy { it.id }.map { input ->
            val label = try {
                input.loadLabel(context)?.toString()
            } catch (e: RuntimeException) {
                null
            }
            val state = try {
                inputStateName(manager.getInputState(input.id))
            } catch (e: RuntimeException) {
                "unknown"
            }
            listOf(
                "id=${input.id}",
                "type=${inputTypeName(input.type)}",
                "passthrough=${input.isPassthroughInput}",
                "label=${label ?: "?"}",
                "state=$state",
                "parent=${input.parentId ?: "-"}",
                "viewer=${viewerOf(context, input)}",
            ).joinToString(" | ")
        }
    }

    /**
     * Which activity would show this input if the launcher asked — resolved, never started. Passthrough inputs are
     * viewed through their passthrough channel uri, everything else through the channel list.
     */
    private fun viewerOf(context: Context, input: TvInputInfo): String {
        val uri = if (input.isPassthroughInput) {
            TvContract.buildChannelUriForPassthroughInput(input.id)
        } else {
            TvContract.Channels.CONTENT_URI
        }
        return context.resolveSafeActivity(Intent(Intent.ACTION_VIEW, uri))?.flattenToShortString() ?: "-"
    }

    private fun knownPackages(packageManager: PackageManager): List<String> =
        KNOWN_SYSTEM_PACKAGES.map { (packageName, role) ->
            val info = packageInfo(packageManager, packageName)
            val application = info?.applicationInfo
            if (info == null) {
                "$packageName ($role): installed=no"
            } else {
                listOf(
                    "$packageName ($role): installed=yes",
                    "enabled=${application?.let { yesNo(it.enabled) } ?: "?"}",
                    "version=${info.versionName ?: "?"} (${PackageInfoCompat.getLongVersionCode(info)})",
                    application?.let(::origin) ?: "?",
                ).joinToString(" | ")
            }
        }

    // -----------------------------------------------------------------------------------------------------------------
    // Read-only PackageManager helpers
    // -----------------------------------------------------------------------------------------------------------------

    private fun queryActivities(packageManager: PackageManager, intent: Intent, flags: Int): List<ResolveInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION") // The flags-as-int overload is the only one before API 33.
            packageManager.queryIntentActivities(intent, flags)
        }

    /** Null when the package is not installed (or not visible to this app). */
    private fun packageInfo(packageManager: PackageManager, packageName: String): PackageInfo? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION") // The flags-as-int overload is the only one before API 33.
            packageManager.getPackageInfo(packageName, 0)
        }
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    private fun componentOf(info: ResolveInfo): ComponentName =
        ComponentName(info.activityInfo.packageName, info.activityInfo.name)

    private fun componentState(packageManager: PackageManager, component: ComponentName): String = try {
        when (packageManager.getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> "default"
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> "enabled"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> "disabled"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER -> "disabled-by-user"
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> "disabled-until-used"
            else -> "unknown"
        }
    } catch (e: RuntimeException) {
        "unknown"
    }

    private fun origin(application: ApplicationInfo): String = when {
        application.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0 -> "system (updated)"
        application.flags and ApplicationInfo.FLAG_SYSTEM != 0 -> "system"
        else -> "user"
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Formatting
    // -----------------------------------------------------------------------------------------------------------------

    private fun inputTypeName(type: Int): String = when (type) {
        TvInputInfo.TYPE_TUNER -> "TUNER"
        TvInputInfo.TYPE_OTHER -> "OTHER"
        TvInputInfo.TYPE_COMPOSITE -> "COMPOSITE"
        TvInputInfo.TYPE_SVIDEO -> "SVIDEO"
        TvInputInfo.TYPE_SCART -> "SCART"
        TvInputInfo.TYPE_COMPONENT -> "COMPONENT"
        TvInputInfo.TYPE_VGA -> "VGA"
        TvInputInfo.TYPE_DVI -> "DVI"
        TvInputInfo.TYPE_HDMI -> "HDMI"
        TvInputInfo.TYPE_DISPLAY_PORT -> "DISPLAY_PORT"
        else -> "UNKNOWN($type)"
    }

    private fun inputStateName(state: Int): String = when (state) {
        TvInputManager.INPUT_STATE_CONNECTED -> "connected"
        TvInputManager.INPUT_STATE_CONNECTED_STANDBY -> "connected-standby"
        TvInputManager.INPUT_STATE_DISCONNECTED -> "disconnected"
        else -> "unknown($state)"
    }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    /** One decimal place, with a dot whatever the device locale is; whole numbers lose the ".0". */
    private fun decimal(value: Float): String {
        val tenths = Math.round(value * 10f)
        return if (tenths % 10 == 0) (tenths / 10).toString() else "${tenths / 10}.${Math.abs(tenths % 10)}"
    }
}
