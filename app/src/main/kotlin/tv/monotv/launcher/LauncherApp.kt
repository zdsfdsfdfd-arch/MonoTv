package tv.monotv.launcher

import android.app.Application
import android.content.ComponentCallbacks2
import tv.monotv.launcher.safety.CrashGuard

class LauncherApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }

    val crashGuard: CrashGuard by lazy { CrashGuard(this) }

    override fun onCreate() {
        super.onCreate()
        // Before anything else can fail: if we are HOME and stuck in a crash loop, give HOME back to the stock launcher.
        crashGuard.releaseHomeIfCrashLooping()
        crashGuard.install()
    }

    /** The launcher lives for days; give decoded artwork back as soon as the system asks for memory. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            container.artwork.clear()
        }
    }
}
