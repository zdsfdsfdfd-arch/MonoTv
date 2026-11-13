package tv.monotv.launcher

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import tv.monotv.launcher.data.AppsRepository
import tv.monotv.launcher.data.ContentRepository
import tv.monotv.launcher.data.InputsRepository
import tv.monotv.launcher.data.SettingsRepository
import tv.monotv.launcher.data.SystemRepository
import tv.monotv.launcher.data.apps.AndroidAppsRepository
import tv.monotv.launcher.data.apps.AndroidArtworkLoader
import tv.monotv.launcher.data.apps.LauncherConfigLoader
import tv.monotv.launcher.data.content.AndroidContentRepository
import tv.monotv.launcher.data.content.ContentComposer
import tv.monotv.launcher.data.inputs.AndroidInputsRepository
import tv.monotv.launcher.data.settings.PrefsSettingsRepository
import tv.monotv.launcher.data.system.AndroidSystemRepository

/**
 * Hand-written dependency container (the app is far too small for a DI framework). Everything is created lazily on
 * first use so a cold start only pays for what the first frame needs.
 */
class AppContainer(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** One loader for the whole process: its caches are the launcher's entire bitmap memory. */
    val artwork: AndroidArtworkLoader by lazy { AndroidArtworkLoader(app) }

    val apps: AppsRepository by lazy {
        // An updated / removed package may have new artwork: drop its cached banners when the package changes.
        AndroidAppsRepository(app, scope, LauncherConfigLoader(app), onPackageChanged = { artwork.invalidate(it) })
    }

    val settings: SettingsRepository by lazy { PrefsSettingsRepository(app) }

    val system: SystemRepository by lazy { AndroidSystemRepository(app) }

    val inputs: InputsRepository by lazy { AndroidInputsRepository(app) }

    private val contentRepository: AndroidContentRepository by lazy {
        // Called on a background thread: a plain read of the current app list. Apps the launcher does not list
        // (blocked, uninstalled) resolve to null, which keeps them out of "Продолжить просмотр" as well.
        AndroidContentRepository(app, appForPackage = { pkg -> apps.apps.value?.firstOrNull { it.key.packageName == pkg } })
    }

    val content: ContentRepository get() = contentRepository

    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer {
            LauncherViewModel(
                apps, settings, system, inputs, content, ContentComposer,
                hasWatchNextAccess = { contentRepository.hasTvListingsPermission() },
                refreshArtwork = { artwork.clear() },
            )
        }
    }
}
