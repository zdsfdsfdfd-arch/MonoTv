package tv.monotv.launcher.safety

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Last line of defence for the "never break the TV" rule.
 *
 * A HOME app that crashes on start is restarted by the system immediately — a crash loop on the home screen would
 * leave the TV unusable without ADB. So the launcher counts its own crashes, and when it has crashed
 * [CRASH_LIMIT] times within [WINDOW_MS] it gives the HOME role back on the next start by returning its own
 * optional `HomeAlias` to the disabled state. With that alias gone the system resolves HOME to the stock launcher
 * again, which this app never disables.
 *
 * As everywhere else, the only component state touched is that of our own alias.
 */
class CrashGuard(private val context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Wraps the default uncaught-exception handler so every crash leaves a timestamp behind. */
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                recordCrash(System.currentTimeMillis())
            } catch (_: Throwable) {
                // Recording must never mask the original crash.
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Call once at process start. Returns true when the HOME role was released because of a crash loop.
     */
    fun releaseHomeIfCrashLooping(now: Long = System.currentTimeMillis()): Boolean {
        val crashes = decode(prefs.getString(KEY_CRASHES, null))
        if (!isCrashLoop(crashes, now)) return false
        // Forget the streak either way: after releasing HOME the app is an ordinary, harmless app again.
        prefs.edit().remove(KEY_CRASHES).putLong(KEY_RELEASED_AT, now).apply()
        return try {
            val alias = ComponentName(context.packageName, HOME_ALIAS)
            val pm = context.packageManager
            if (pm.getComponentEnabledSetting(alias) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return false
            pm.setComponentEnabledSetting(alias, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP)
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    /** Timestamp of the last automatic release, or 0. Lets the UI explain what happened. */
    fun lastReleaseAt(): Long = prefs.getLong(KEY_RELEASED_AT, 0L)

    fun clearReleaseNotice() {
        prefs.edit().remove(KEY_RELEASED_AT).apply()
    }

    private fun recordCrash(now: Long) {
        val recent = (decode(prefs.getString(KEY_CRASHES, null)) + now).filter { now - it in 0..WINDOW_MS }.takeLast(CRASH_LIMIT * 2)
        // commit(): the process is about to die, apply() might never reach the disk.
        prefs.edit().putString(KEY_CRASHES, recent.joinToString(",")).commit()
    }

    companion object {
        const val CRASH_LIMIT = 3
        const val WINDOW_MS = 120_000L
        const val HOME_ALIAS = "tv.monotv.launcher.HomeAlias"
        private const val PREFS = "monotv_crash_guard"
        private const val KEY_CRASHES = "crashes"
        private const val KEY_RELEASED_AT = "released_at"

        /** True when at least [CRASH_LIMIT] crashes happened within the last [WINDOW_MS]. Pure, unit-tested. */
        fun isCrashLoop(crashTimes: List<Long>, now: Long): Boolean =
            crashTimes.count { now - it in 0..WINDOW_MS } >= CRASH_LIMIT

        fun decode(stored: String?): List<Long> =
            stored.orEmpty().split(',').mapNotNull { it.trim().toLongOrNull() }
    }
}
