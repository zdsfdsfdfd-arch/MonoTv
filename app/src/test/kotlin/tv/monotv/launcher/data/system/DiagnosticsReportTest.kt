package tv.monotv.launcher.data.system

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import tv.monotv.launcher.BuildConfig

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w960dp-h540dp-land-xhdpi")
class DiagnosticsReportTest {

    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val packageManager: PackageManager get() = app.packageManager

    @Test
    fun `a bare device still yields a complete report`() {
        val report = DiagnosticsReport.build(app)

        assertEquals(
            listOf(
                "== Mono TV — отчёт об устройстве ==",
                "== Build ==",
                "== Display ==",
                "== Memory ==",
                "== Features ==",
                "== HOME candidates ==",
                "== Launchable activities: android.intent.category.LEANBACK_LAUNCHER ==",
                "== Launchable activities: ru.sberdevices.category.STAR_LAUNCHER ==",
                "== Launchable activities: android.intent.category.LAUNCHER ==",
                "== TV inputs ==",
                "== Known system packages ==",
            ),
            report.lines().filter { it.startsWith("== ") },
        )
        assertTrue(report.endsWith("\n"))
    }

    @Test
    fun `the header says what the report is and who wrote it`() {
        val header = section(DiagnosticsReport.build(app), "Mono TV — отчёт об устройстве")
        assertTrue(header[0].startsWith("Только чтение"))
        assertTrue(header.any { it.matches(Regex("""generated: \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} [+-]\d{4}""")) })
        assertTrue(header.contains("launcher: ${app.packageName} ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"))
        assertTrue(header.any { it.startsWith("package visibility: ") })
    }

    @Test
    fun `build and display facts are reported`() {
        ShadowBuild.setManufacturer("SberDevices")
        ShadowBuild.setModel("SBDV-00006")
        ShadowBuild.setFingerprint("SberDevices/sbdv_00006/sbdv_00006:11/RP1A/1234:user/release-keys")

        val report = DiagnosticsReport.build(app)

        val build = section(report, "Build")
        assertTrue(build.contains("manufacturer: SberDevices"))
        assertTrue(build.contains("model: SBDV-00006"))
        assertTrue(build.contains("fingerprint: SberDevices/sbdv_00006/sbdv_00006:11/RP1A/1234:user/release-keys"))
        assertTrue(build.contains("sdk: ${Build.VERSION.SDK_INT}"))
        assertTrue(build.any { it.startsWith("supported ABIs: ") })
        assertTrue(build.any { it.startsWith("security patch: ") })

        val display = section(report, "Display")
        assertTrue(display.contains("size: 1920x1080 px"))
        assertTrue(display.contains("density: 320 dpi"))
        assertTrue(display.contains("canvas: 960x540 dp (design canvas 960x540 dp: matches)"))
        assertTrue(display.any { it.startsWith("refresh rate: ") && it.endsWith(" Hz") })

        val memory = section(report, "Memory")
        assertTrue(memory.any { it.startsWith("total: ") })
        assertTrue(memory.contains("lowRam: no"))
        assertTrue(memory.contains("cpu cores: ${Runtime.getRuntime().availableProcessors()}"))
        assertTrue(memory.any { it.startsWith("recommended effects: ") })
    }

    @Test
    fun `features and google play services are reported as yes or no`() {
        // A Sber TV: leanback with live TV, wired and wireless network, no touch screen.
        val present = mapOf(
            PackageManager.FEATURE_LEANBACK to true,
            PackageManager.FEATURE_LEANBACK_ONLY to false,
            PackageManager.FEATURE_LIVE_TV to true,
            PackageManager.FEATURE_TOUCHSCREEN to false,
            PackageManager.FEATURE_WIFI to true,
            PackageManager.FEATURE_ETHERNET to true,
            PackageManager.FEATURE_BLUETOOTH to false,
        )
        for ((feature, supported) in present) shadowOf(packageManager).setSystemFeature(feature, supported)

        var features = section(DiagnosticsReport.build(app), "Features")
        assertEquals(
            listOf(
                "leanback: yes",
                "leanback_only: no",
                "live_tv: yes",
                "touchscreen: no",
                "wifi: yes",
                "ethernet: yes",
                "bluetooth: no",
                "google play services: no",
            ),
            features,
        )

        install("com.google.android.gms", versionName = "24.0", system = true)
        features = section(DiagnosticsReport.build(app), "Features")
        assertTrue(features.contains("google play services: yes"))
    }

    @Test
    fun `HOME candidates are listed with the current default marked`() {
        val stock = resolveInfo("ru.sberdevices.starlauncher", "ru.sberdevices.starlauncher.ui.mainscreen.MainActivity", system = true)
        val fallback = resolveInfo("com.android.tv.settings", "com.android.tv.settings.system.FallbackHome", system = true).apply {
            priority = -1000
        }
        answer(homeIntent(), stock, fallback)

        val home = section(DiagnosticsReport.build(app), "HOME candidates")

        assertTrue(home[0].startsWith("current default: "))
        // Our own (disabled) HOME alias may be listed as well once the manifest declares it; it is not the subject here.
        val others = home.drop(1).filterNot { it.startsWith("${app.packageName}/") }
        assertEquals(2, others.size)
        assertTrue(others[0].startsWith("ru.sberdevices.starlauncher/.ui.mainscreen.MainActivity | priority=0 | isDefault="))
        assertTrue(others[0].contains(" | system | state=default"))
        assertTrue(others[1].startsWith("com.android.tv.settings/.system.FallbackHome | priority=-1000 | "))
        // Whichever of the two the platform resolves to is the one — and the only one — marked.
        assertEquals(1, home.count { it.endsWith(" | CURRENT") })
        val current = home[0].removePrefix("current default: ")
        assertTrue(home.single { it.endsWith(" | CURRENT") }.startsWith("$current | "))
    }

    @Test
    fun `no default home is said in so many words`() {
        val resolver = resolveInfo("android", "com.android.internal.app.ResolverActivity", system = true)
        answer(homeIntent(), resolver)

        val home = section(DiagnosticsReport.build(app), "HOME candidates")

        assertTrue(home[0].startsWith("current default: none"))
        assertEquals(0, home.count { it.endsWith(" | CURRENT") })
    }

    @Test
    fun `launchable activities are grouped by category`() {
        val leanback = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val star = Intent(Intent.ACTION_MAIN).addCategory("ru.sberdevices.category.STAR_LAUNCHER")
        val phone = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        answer(
            leanback,
            resolveInfo("ru.kinopoisk.tv", "ru.kinopoisk.tv.MainActivity", label = "Кинопоиск", banner = true),
            resolveInfo("com.android.tv.settings", "com.android.tv.settings.MainSettings", label = "Настройки", system = true),
        )
        answer(
            star,
            resolveInfo("ru.sberdevices.startube", "ru.sberdevices.startube.MainActivity", label = "Видео", system = true, banner = true),
        )
        answer(
            phone,
            resolveInfo("org.videolan.vlc", "org.videolan.vlc.StartActivity", label = "VLC\nplayer"),
            resolveInfo("com.cvte.fac.menu", "com.cvte.fac.menu.MainActivity", label = "Factory", system = true),
        )

        val report = DiagnosticsReport.build(app)

        val leanbackLines = section(report, "Launchable activities: android.intent.category.LEANBACK_LAUNCHER")
        assertTrue(leanbackLines.contains("Кинопоиск | ru.kinopoisk.tv/.MainActivity | user | banner=yes"))
        assertTrue(leanbackLines.contains("Настройки | com.android.tv.settings/.MainSettings | system | banner=no"))

        assertEquals(
            listOf("Видео | ru.sberdevices.startube/.MainActivity | system | banner=yes"),
            section(report, "Launchable activities: ru.sberdevices.category.STAR_LAUNCHER"),
        )

        val phoneLines = section(report, "Launchable activities: android.intent.category.LAUNCHER")
        assertTrue("a label is flattened to one line", phoneLines.contains("VLC player | org.videolan.vlc/.StartActivity | user | banner=no"))
        val factory = phoneLines.single { it.contains("com.cvte.fac.menu") }
        assertTrue("the factory menu is named but flagged as blocked", factory.contains(" | BLOCKED"))
        assertEquals(1, report.lines().count { it.contains(" | BLOCKED") })
    }

    @Test
    fun `known system packages are looked up by name`() {
        install("ru.sberdevices.starlauncher", versionName = "2.61.1", versionCode = 26101, system = true)
        install("ru.sberdevices.tvinputs", versionName = "1.4", versionCode = 14, system = true, enabled = false)

        val known = section(DiagnosticsReport.build(app), "Known system packages")

        assertTrue(known.contains("ru.sberdevices.starlauncher (stock launcher): installed=yes | enabled=yes | version=2.61.1 (26101) | system"))
        assertTrue(known.contains("ru.sberdevices.tvinputs (HDMI / AV input viewer): installed=yes | enabled=no | version=1.4 (14) | system"))
        assertTrue(known.contains("ru.sberdevices.assistant (voice assistant): installed=no"))
        assertTrue(known.single { it.startsWith("com.cvte.fac.menu ") }.endsWith("installed=no"))
        assertTrue(known.single { it.startsWith("com.stb.factorymenu ") }.endsWith("installed=no"))
        for (expected in EXPECTED_KNOWN_PACKAGES) {
            assertEquals("exactly one line for $expected", 1, known.count { it.startsWith("$expected (") })
        }
        assertEquals(EXPECTED_KNOWN_PACKAGES.size, known.size)
    }

    @Test
    fun `building the report starts nothing and changes nothing`() {
        val alias = ComponentName(app.packageName, AndroidSystemRepository.HOME_ALIAS_CLASS)
        val stock = resolveInfo("ru.sberdevices.starlauncher", "ru.sberdevices.starlauncher.ui.mainscreen.MainActivity", system = true)
        answer(homeIntent(), stock)
        install("ru.sberdevices.starlauncher", versionName = "2.61.1", system = true)
        val receiversBefore = shadowOf(app).registeredReceivers.size

        repeat(2) { DiagnosticsReport.build(app) }

        assertNull(shadowOf(app).peekNextStartedActivity())
        assertNull(shadowOf(app).peekNextStartedService())
        assertTrue(shadowOf(app).broadcastIntents.isEmpty())
        assertEquals(receiversBefore, shadowOf(app).registeredReceivers.size)
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, packageManager.getComponentEnabledSetting(alias))
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            packageManager.getComponentEnabledSetting(ComponentName(stock.activityInfo.packageName, stock.activityInfo.name)),
        )
        assertEquals(
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            packageManager.getApplicationEnabledSetting("ru.sberdevices.starlauncher"),
        )
        assertFalse(app.getExternalFilesDir(null)!!.resolve(DiagnosticsReport.FILE_NAME).exists())
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------------------------------------------------

    /** The lines of the section titled [title]. */
    private fun section(report: String, title: String): List<String> {
        val lines = report.lines()
        val start = lines.indexOf("== $title ==")
        assertTrue("section '$title' is missing", start >= 0)
        return lines.drop(start + 1).takeWhile { it.isNotEmpty() }
    }

    private fun homeIntent(): Intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

    /**
     * Makes [activities] the answer to [intent]. Robolectric deprecates this shortcut in favour of installing whole
     * packages with intent filters; for "this intent resolves to these components" it is still the most direct way.
     */
    @Suppress("DEPRECATION")
    private fun answer(intent: Intent, vararg activities: ResolveInfo) {
        shadowOf(packageManager).addResolveInfoForIntent(intent, activities.toList())
    }

    private fun resolveInfo(
        packageName: String,
        className: String,
        label: String? = null,
        system: Boolean = false,
        banner: Boolean = false,
    ): ResolveInfo = ResolveInfo().apply {
        nonLocalizedLabel = label
        activityInfo = ActivityInfo().apply {
            this.packageName = packageName
            name = className
            if (banner) this.banner = 0x7f080001
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                if (system) flags = flags or ApplicationInfo.FLAG_SYSTEM
            }
        }
    }

    private fun install(
        packageName: String,
        versionName: String,
        versionCode: Int = 1,
        system: Boolean = false,
        enabled: Boolean = true,
    ) {
        val info = PackageInfo().apply {
            this.packageName = packageName
            this.versionName = versionName
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                longVersionCode = versionCode.toLong()
            } else {
                @Suppress("DEPRECATION")
                this.versionCode = versionCode
            }
            applicationInfo = ApplicationInfo().apply {
                this.packageName = packageName
                this.enabled = enabled
                if (system) flags = flags or ApplicationInfo.FLAG_SYSTEM
            }
        }
        shadowOf(packageManager).installPackage(info)
    }

    private companion object {
        /** Every package the platform facts name, spelled out again so a typo in the report's own table is caught. */
        val EXPECTED_KNOWN_PACKAGES = listOf(
            "ru.sberdevices.starlauncher",
            "com.android.tv.settings",
            "ru.sberdevices.settings",
            "com.sberbank.settings",
            "ru.sberdevices.assistant",
            "ru.sberdevices.services",
            "ru.sberdevices.services.core",
            "ru.sberdevices.SberDevicesServiceSystem",
            "ru.sberdevices.starmarket",
            "ru.sberdevices.updater",
            "ru.sberdevices.rcfupdater",
            "ru.sberdevices.tvinputs",
            "ru.sberdevices.starlivetv",
            "ru.sberdevices.tv",
            "ru.sberdevices.startube",
            "ru.sberdevices.starbrowser",
            "ru.sberdevices.app.starkeyboard",
            "ru.sberdevices.webview",
            "ru.sberdevices.systemui",
            "ru.sberdevices.starprofile",
            "ru.sberdevices.starscreensaver",
            "ru.sberdevices.gamepad.service",
            "com.droidlogic.tvinput",
            "com.mediatek.tvinput",
            "com.mediatek.tv.service",
            "com.cvte.fac.menu",
            "com.stb.factorymenu",
        )
    }
}
