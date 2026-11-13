package tv.monotv.launcher.safety

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CrashGuardTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val alias get() = ComponentName(context.packageName, CrashGuard.HOME_ALIAS)

    private fun prefs() = context.getSharedPreferences("monotv_crash_guard", Context.MODE_PRIVATE)

    private fun enableAlias() = context.packageManager.setComponentEnabledSetting(
        alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP,
    )

    private fun aliasEnabled() =
        context.packageManager.getComponentEnabledSetting(alias) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    @Test
    fun `three crashes inside the window are a loop`() {
        val now = 1_000_000L
        assertTrue(CrashGuard.isCrashLoop(listOf(now - 90_000, now - 40_000, now - 1_000), now))
        assertFalse(CrashGuard.isCrashLoop(listOf(now - 40_000, now - 1_000), now))
        assertFalse("old crashes do not count", CrashGuard.isCrashLoop(listOf(now - 500_000, now - 400_000, now - 1_000), now))
        assertFalse("timestamps from the future are ignored", CrashGuard.isCrashLoop(listOf(now + 5, now + 6, now + 7), now))
    }

    @Test
    fun `stored crash list tolerates garbage`() {
        assertEquals(listOf(1L, 22L, 333L), CrashGuard.decode("1, 22,x,,333"))
        assertTrue(CrashGuard.decode(null).isEmpty())
    }

    @Test
    fun `crash loop while holding home gives the role back and resets the streak`() {
        enableAlias()
        val now = 10_000_000L
        prefs().edit().putString("crashes", "${now - 50_000},${now - 20_000},${now - 2_000}").commit()
        val guard = CrashGuard(context)
        assertTrue(guard.releaseHomeIfCrashLooping(now))
        assertFalse(aliasEnabled())
        assertEquals(now, guard.lastReleaseAt())
        assertFalse("streak was cleared", guard.releaseHomeIfCrashLooping(now + 1))
    }

    @Test
    fun `isolated crashes leave the home role alone`() {
        enableAlias()
        val now = 10_000_000L
        prefs().edit().putString("crashes", "${now - 900_000},${now - 2_000}").commit()
        assertFalse(CrashGuard(context).releaseHomeIfCrashLooping(now))
        assertTrue(aliasEnabled())
    }

    @Test
    fun `crash loop as an ordinary app changes nothing`() {
        val now = 10_000_000L
        prefs().edit().putString("crashes", "${now - 3_000},${now - 2_000},${now - 1_000}").commit()
        assertFalse(CrashGuard(context).releaseHomeIfCrashLooping(now))
        assertFalse(aliasEnabled())
    }

    @Test
    fun `installed handler records the crash and still delegates`() {
        var delegated = false
        val original = Thread.getDefaultUncaughtExceptionHandler()
        try {
            Thread.setDefaultUncaughtExceptionHandler { _, _ -> delegated = true }
            CrashGuard(context).install()
            Thread.getDefaultUncaughtExceptionHandler()!!.uncaughtException(Thread.currentThread(), IllegalStateException("boom"))
            assertTrue(delegated)
            assertEquals(1, CrashGuard.decode(prefs().getString("crashes", null)).size)
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(original)
        }
    }
}
