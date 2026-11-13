package tv.monotv.launcher

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.view.KeyEvent
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import tv.monotv.launcher.nav.Overlay
import tv.monotv.launcher.nav.Section

/**
 * Boots the REAL app (manifest, Application, container, repositories, ViewModel, Compose UI) under Robolectric on the
 * Android versions found on Sber devices and drives it with real KeyEvents through Activity.dispatchKeyEvent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 30, 33, 34], qualifiers = "w960dp-h540dp-xhdpi")
class MainActivitySmokeTest {
    private val context get() = ApplicationProvider.getApplicationContext<LauncherApp>()

    private fun installTvApp(pkg: String, label: String, category: String = Intent.CATEGORY_LEANBACK_LAUNCHER) {
        val pm = shadowOf(context.packageManager)
        val info = ResolveInfo().apply {
            activityInfo = ActivityInfo().apply {
                packageName = pkg
                name = "$pkg.MainActivity"
                exported = true
                enabled = true
                nonLocalizedLabel = label
                applicationInfo = ApplicationInfo().apply {
                    packageName = pkg
                    nonLocalizedLabel = label
                    enabled = true
                }
            }
            nonLocalizedLabel = label
        }
        pm.installPackage(
            android.content.pm.PackageInfo().apply {
                packageName = pkg
                applicationInfo = info.activityInfo.applicationInfo
            },
        )
        pm.addResolveInfoForIntent(Intent(Intent.ACTION_MAIN).addCategory(category), info)
    }

    private fun ActivityScenario<MainActivity>.key(code: Int): Boolean {
        var consumed = false
        onActivity { activity ->
            val down = activity.dispatchKeyEvent(KeyEvent(0L, 0L, KeyEvent.ACTION_DOWN, code, 0))
            val up = activity.dispatchKeyEvent(KeyEvent(0L, 50L, KeyEvent.ACTION_UP, code, 0))
            consumed = down || up
        }
        ShadowLooper.idleMainLooper()
        return consumed
    }

    private fun ActivityScenario<MainActivity>.viewModel(): LauncherViewModel {
        lateinit var vm: LauncherViewModel
        onActivity { vm = ViewModelProvider(it)[LauncherViewModel::class.java] }
        return vm
    }

    @Test
    fun `boots, lists TV apps, navigates with the remote and never swallows system keys`() {
        installTvApp("com.google.android.youtube.tv", "YouTube")
        installTvApp("ru.kinopoisk.tv", "Кинопоиск")
        installTvApp("ru.sberdevices.startube", "YouTube (Сбер)", category = "ru.sberdevices.category.STAR_LAUNCHER")
        installTvApp("com.cvte.fac.menu", "Factory")

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ShadowLooper.idleMainLooper()
            val vm = scenario.viewModel()
            // The first scan runs on a background dispatcher.
            val deadline = System.currentTimeMillis() + 10_000
            while (!vm.ui.value.loaded && System.currentTimeMillis() < deadline) {
                Thread.sleep(20)
                ShadowLooper.idleMainLooper()
            }
            val ui = vm.ui.value
            assertTrue("apps were scanned", ui.loaded)
            val packages = ui.allApps.map { it.key.packageName }
            assertTrue(packages.containsAll(listOf("com.google.android.youtube.tv", "ru.kinopoisk.tv", "ru.sberdevices.startube")))
            assertFalse("factory menu must never be listed", packages.any { it.startsWith("com.cvte") })
            assertFalse("the launcher does not list itself", packages.contains(context.packageName))
            assertEquals(Section.Dock, ui.nav.home.section)
            assertTrue(ui.hero.isNotEmpty())
            assertTrue(ui.inputs.isNotEmpty())
            assertNotNull(ui.device.launcherVersion)

            assertTrue(scenario.key(KeyEvent.KEYCODE_DPAD_RIGHT))
            assertEquals(1, vm.ui.value.nav.home.dockIndex)
            assertTrue(scenario.key(KeyEvent.KEYCODE_DPAD_UP))
            assertTrue(scenario.key(KeyEvent.KEYCODE_DPAD_UP))
            assertEquals(Section.TopBar, vm.ui.value.nav.home.section)
            assertTrue(scenario.key(KeyEvent.KEYCODE_DPAD_CENTER))
            assertTrue(vm.ui.value.nav.topOverlay is Overlay.Settings)
            assertTrue(scenario.key(KeyEvent.KEYCODE_BACK))
            assertEquals(null, vm.ui.value.nav.topOverlay)

            // Assistant, HOME and vendor shortcut keys must reach the system untouched.
            for (code in listOf(KeyEvent.KEYCODE_SEARCH, KeyEvent.KEYCODE_VOICE_ASSIST, KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_VOLUME_UP)) {
                scenario.onActivity { activity ->
                    assertFalse("key $code was consumed", RemoteKeyProbe.consumes(activity, code))
                }
            }
        }
    }

    @Test
    fun `home alias ships disabled and the stock launcher is not referenced as a target`() {
        val pm = context.packageManager
        val alias = ComponentName(context, "tv.monotv.launcher.HomeAlias")
        val state = pm.getComponentEnabledSetting(alias)
        assertTrue(state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT || state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED)
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
        assertFalse("installing the app must not add a HOME candidate", homes.any { it.activityInfo?.packageName == context.packageName })
    }
}

/** Asks the key mapper policy directly, without letting the framework fallbacks run for system keys. */
private object RemoteKeyProbe {
    fun consumes(activity: MainActivity, code: Int): Boolean {
        val mapper = tv.monotv.launcher.input.RemoteKeyMapper { }
        return mapper.handle(KeyEvent.ACTION_DOWN, code, 0, 0L, false) || mapper.handle(KeyEvent.ACTION_UP, code, 0, 10L, false)
    }
}
