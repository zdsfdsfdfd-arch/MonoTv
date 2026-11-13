package tv.monotv.launcher

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.ContinueItem
import tv.monotv.launcher.core.HeroItem
import tv.monotv.launcher.core.HeroKind
import tv.monotv.launcher.core.LauncherConfig
import tv.monotv.launcher.core.RecentLaunch
import tv.monotv.launcher.core.Wallpapers
import tv.monotv.launcher.data.AppsRepository
import tv.monotv.launcher.data.ContentRepository
import tv.monotv.launcher.data.InputsRepository
import tv.monotv.launcher.data.SettingsRepository
import tv.monotv.launcher.data.SystemRepository
import tv.monotv.launcher.data.apps.AndroidAppsRepository
import tv.monotv.launcher.data.apps.AndroidArtworkLoader
import tv.monotv.launcher.data.apps.LauncherConfigLoader
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

    // TEMPORARY until the content module lands: no Watch Next source, hero built from the dock.
    val content: ContentRepository by lazy {
        object : ContentRepository {
            override suspend fun loadWatchNext(limit: Int): List<ContinueItem> = emptyList()
            override val watchNextInvalidations: StateFlow<Int> = MutableStateFlow(0)
            override fun start() = Unit
            override fun stop() = Unit
        }
    }

    private val planner = object : ContentPlanner {
        override fun continueItems(
            watchNext: List<ContinueItem>, recents: List<RecentLaunch>, apps: List<AppEntry>, demo: Boolean,
        ): List<ContinueItem> = watchNext

        override fun heroItems(
            continueItems: List<ContinueItem>, dock: List<AppEntry>, config: LauncherConfig, demo: Boolean,
        ): List<HeroItem> = dock.map {
            HeroItem("app:${it.key.flatten()}", HeroKind.App, "Приложение", it.label, null, "Открыть", ArtSource.Wallpaper(Wallpapers.AURORA), it.key, null, null)
        }.ifEmpty {
            listOf(HeroItem("welcome", HeroKind.App, "Mono TV", "Добро пожаловать", null, "Настройки", ArtSource.Wallpaper(Wallpapers.AURORA), null, null, null))
        }
    }

    val viewModelFactory: ViewModelProvider.Factory = viewModelFactory {
        initializer { LauncherViewModel(apps, settings, system, inputs, content, planner) }
    }
}
