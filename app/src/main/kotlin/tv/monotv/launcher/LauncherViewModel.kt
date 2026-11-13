package tv.monotv.launcher

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tv.monotv.launcher.core.AccentColor
import tv.monotv.launcher.core.AnimationLevel
import tv.monotv.launcher.core.AppArrangement
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.BumpEvent
import tv.monotv.launcher.core.ContinueItem
import tv.monotv.launcher.core.EffectsLevel
import tv.monotv.launcher.core.HeroItem
import tv.monotv.launcher.core.HeroKind
import tv.monotv.launcher.core.LauncherConfig
import tv.monotv.launcher.core.LauncherSettings
import tv.monotv.launcher.core.LauncherUiState
import tv.monotv.launcher.core.RecentLaunch
import tv.monotv.launcher.core.ThemeMode
import tv.monotv.launcher.core.Wallpapers
import tv.monotv.launcher.data.AppsRepository
import tv.monotv.launcher.data.ContentRepository
import tv.monotv.launcher.data.InputsRepository
import tv.monotv.launcher.data.SettingsRepository
import tv.monotv.launcher.data.SystemRepository
import tv.monotv.launcher.nav.AppMenuCatalog
import tv.monotv.launcher.nav.AppMenuItem
import tv.monotv.launcher.nav.ConfirmId
import tv.monotv.launcher.nav.NavContext
import tv.monotv.launcher.nav.NavEffect
import tv.monotv.launcher.nav.NavKey
import tv.monotv.launcher.nav.NavReducer
import tv.monotv.launcher.nav.NavState
import tv.monotv.launcher.nav.Overlay
import tv.monotv.launcher.nav.Section
import tv.monotv.launcher.nav.SettingsCatalog
import tv.monotv.launcher.nav.SettingsPageId
import tv.monotv.launcher.ui.components.PointerTarget

/** Pure rules that turn raw sources into the Hero and "Продолжить просмотр" rows (ContentComposer in production). */
interface ContentPlanner {
    fun continueItems(watchNext: List<ContinueItem>, recents: List<RecentLaunch>, apps: List<AppEntry>, demo: Boolean): List<ContinueItem>

    fun heroItems(continueItems: List<ContinueItem>, dock: List<AppEntry>, config: LauncherConfig, demo: Boolean): List<HeroItem>
}

/**
 * The launcher's brain. It owns the navigation state, turns every remote key into a [NavReducer] step and executes
 * the resulting effects against the repositories. The UI is a pure function of [ui].
 *
 * Consistency rule: a key press is always reduced against a snapshot built synchronously from the current values of
 * all sources ([snapshot]) — never against a possibly one-frame-old collected state — so fast key repeats can not act
 * on stale lists (e.g. moving a dock item twice in a row).
 */
class LauncherViewModel(
    private val apps: AppsRepository,
    private val settings: SettingsRepository,
    private val system: SystemRepository,
    private val inputs: InputsRepository,
    private val content: ContentRepository,
    private val planner: ContentPlanner,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /** Whether the optional READ_TV_LISTINGS permission is currently granted. */
    private val hasWatchNextAccess: () -> Boolean = { false },
    /** Drops decoded artwork that may be stale (the user's wallpaper file has no "changed" event to listen to). */
    private val refreshArtwork: () -> Unit = {},
) : ViewModel() {

    private val nav = MutableStateFlow(NavState())
    private val toast = MutableStateFlow<String?>(null)
    private val bump = MutableStateFlow<BumpEvent?>(null)
    private val watchNext = MutableStateFlow<List<ContinueItem>>(emptyList())
    private var toastJob: Job? = null
    private var started = false

    private val watchNextAccess = MutableStateFlow(hasWatchNextAccess())
    private val permissionRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /** Runtime permissions the activity should ask the user for (only ever READ_TV_LISTINGS). */
    val permissions: SharedFlow<String> = permissionRequests

    private val exitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when BACK was pressed at the very root while this app is NOT the default HOME (activity should finish). */
    val exit: SharedFlow<Unit> = exitRequests

    private val recommendedEffects: EffectsLevel by lazy { system.recommendedEffects() }

    // ---- derived data, memoised on its inputs so a snapshot per key press costs nothing ---------------------------
    private class Arrangement(
        val all: List<AppEntry>?,
        val settings: LauncherSettings,
        val recents: List<RecentLaunch>,
        val watchNext: List<ContinueItem>,
        val dock: List<AppEntry>,
        val visible: List<AppEntry>,
        val continueItems: List<ContinueItem>,
        val hero: List<HeroItem>,
    )

    private var lastArrangement: Arrangement? = null

    private fun arrangement(): Arrangement {
        val all = apps.apps.value
        val s = settings.settings.value
        val recents = settings.recents.value
        val wn = watchNext.value
        lastArrangement?.let { last ->
            if (last.all === all && last.settings == s && last.recents === recents && last.watchNext === wn) return last
        }
        val list = all.orEmpty()
        val dock = AppArrangement.dock(list, s, apps.config, recents)
        val visible = AppArrangement.visibleApps(list, s, dock, recents)
        val continueItems = planner.continueItems(wn, recents, visible, s.demoContent)
        val hero = planner.heroItems(continueItems, dock, apps.config, s.demoContent)
        return Arrangement(all, s, recents, wn, dock, visible, continueItems, hero).also { lastArrangement = it }
    }

    private fun snapshot(): LauncherUiState {
        val a = arrangement()
        val state = LauncherUiState(
            clock = system.clock.value,
            network = system.network.value,
            hero = a.hero,
            dock = a.dock,
            continueWatching = a.continueItems,
            apps = a.visible,
            allApps = a.all.orEmpty(),
            inputs = inputs.inputs.value,
            settings = a.settings,
            device = system.device.value,
            effects = when (a.settings.animation) {
                AnimationLevel.Auto -> recommendedEffects
                AnimationLevel.Full -> EffectsLevel.Full
                AnimationLevel.Reduced -> EffectsLevel.Reduced
                AnimationLevel.Off -> EffectsLevel.Off
            },
            nav = nav.value,
            toast = toast.value,
            bump = bump.value,
            loaded = a.all != null,
            watchNextAccess = watchNextAccess.value,
        )
        // Data can change under the cursor (an app is uninstalled, a row empties): never show an invalid focus.
        val normalized = NavReducer.normalize(state.nav, contextFor(state))
        return if (normalized == state.nav) state else state.copy(nav = normalized)
    }

    val ui: StateFlow<LauncherUiState> =
        merge(
            apps.apps, settings.settings, settings.recents, system.clock, system.network, system.device,
            inputs.inputs, watchNext, nav, toast, bump, watchNextAccess,
        ).map { snapshot() }.stateIn(viewModelScope, SharingStarted.Eagerly, snapshot())

    init {
        viewModelScope.launch {
            // StateFlow: emits the current value first, so this also performs the initial load.
            content.watchNextInvalidations.collect { reloadWatchNext() }
        }
    }

    private suspend fun reloadWatchNext() {
        watchNext.value = withContext(io) { content.loadWatchNext() }
    }

    // ---- lifecycle ------------------------------------------------------------------------------------------------

    /** Activity became visible: (re)attach every listener. Nothing runs while the launcher is in the background. */
    fun start() {
        if (started) return
        started = true
        apps.start()
        system.start()
        inputs.start()
        content.start()
        system.refreshDevice()
        // The permission can also be granted from outside (adb), so re-check whenever we come to the front.
        watchNextAccess.value = hasWatchNextAccess()
        viewModelScope.launch { reloadWatchNext() }
    }

    /** The activity reports the outcome of a permission prompt started through [permissions]. */
    fun onPermissionResult(granted: Boolean) {
        watchNextAccess.value = hasWatchNextAccess()
        if (watchNextAccess.value) {
            showToast("Доступ разрешён")
            viewModelScope.launch { reloadWatchNext() }
        } else if (!granted) {
            showToast("Доступ не выдан. На части прошивок его можно дать только через adb — см. README")
        }
    }

    fun stop() {
        if (!started) return
        started = false
        apps.stop()
        system.stop()
        inputs.stop()
        content.stop()
    }

    /** HOME was pressed while the launcher is already in front: return to the pristine home position. */
    fun goHome() {
        nav.value = NavState()
    }

    override fun onCleared() {
        stop()
    }

    // ---- input ----------------------------------------------------------------------------------------------------

    fun onKey(key: NavKey) {
        val state = snapshot()
        val result = NavReducer.reduce(nav.value, key, contextFor(state))
        nav.value = result.state
        for (effect in result.effects) perform(effect, state)
    }

    /** Air-mouse / touchpad taps: focus the item, then behave exactly like OK (or BACK for a tap outside). */
    fun onPointer(target: PointerTarget) {
        val current = nav.value
        when (target) {
            PointerTarget.Outside -> onKey(NavKey.Back)
            is PointerTarget.HomeItem -> {
                if (current.overlays.isNotEmpty() || current.reorder != null) return
                val home = current.home
                nav.value = current.copy(
                    home = when (target.section) {
                        Section.TopBar -> home.copy(section = Section.TopBar, topBarIndex = target.index)
                        Section.Hero -> home.copy(section = Section.Hero, heroIndex = target.index)
                        Section.Dock -> home.copy(section = Section.Dock, dockIndex = target.index)
                        Section.Continue -> home.copy(section = Section.Continue, continueIndex = target.index)
                        Section.Apps -> home.copy(section = Section.Apps, appsIndex = target.index)
                    },
                )
                onKey(NavKey.Ok)
            }
            is PointerTarget.OverlayRow -> {
                val top = current.topOverlay ?: return
                val moved = when (top) {
                    is Overlay.Inputs -> top.copy(index = target.index)
                    is Overlay.AppMenu -> top.copy(index = target.index)
                    is Overlay.Confirm -> top.copy(index = target.index)
                    is Overlay.Settings -> {
                        val frame = top.stack.lastOrNull() ?: return
                        if (frame.grabbed) return
                        top.copy(stack = top.stack.dropLast(1) + frame.copy(index = target.index))
                    }
                }
                nav.value = current.copy(overlays = current.overlays.dropLast(1) + moved)
                onKey(NavKey.Ok)
            }
        }
    }

    private fun contextFor(state: LauncherUiState) = NavContext(
        heroCount = state.hero.size,
        dockCount = state.dock.size,
        continueCount = state.continueWatching.size,
        appsCount = state.apps.size,
        appsColumns = LauncherUiState.APPS_COLUMNS,
        inputsCount = state.inputs.size,
        appAt = { section, index ->
            when (section) {
                Section.Dock -> state.dock.getOrNull(index)?.key
                Section.Apps -> state.apps.getOrNull(index)?.key
                else -> null
            }
        },
        appMenuFor = { app, from -> AppMenuCatalog.itemsFor(app, from, state) },
        settingsRows = { page -> SettingsCatalog.rows(page, state) },
    )

    // ---- effects --------------------------------------------------------------------------------------------------

    private fun perform(effect: NavEffect, state: LauncherUiState) {
        when (effect) {
            is NavEffect.LaunchApp -> launchApp(effect.app)
            is NavEffect.OpenHero -> state.hero.getOrNull(effect.index)?.let { openHero(it) }
            is NavEffect.OpenContinue -> state.continueWatching.getOrNull(effect.index)?.let { openContinue(it) }
            NavEffect.OpenNetworkSettings ->
                if (!system.openNetworkSettings()) showToast("Системные настройки сети недоступны")
            is NavEffect.SwitchInput -> {
                val input = state.inputs.getOrNull(effect.index) ?: return
                if (!inputs.switchTo(input)) showToast("Переключение на «${input.label}» недоступно на этом устройстве")
            }
            is NavEffect.SettingsAction -> settingsAction(effect.page, effect.rowId, state)
            is NavEffect.SettingsMove -> when (effect.page) {
                SettingsPageId.DockOrder ->
                    settings.update { AppArrangement.moveDockItem(it, state.dock, effect.from, effect.to) }
                SettingsPageId.AppsOrder ->
                    settings.update { AppArrangement.moveApp(it, state.apps, effect.from, effect.to) }
                else -> Unit
            }
            is NavEffect.MoveItem -> when (effect.section) {
                Section.Dock -> settings.update { AppArrangement.moveDockItem(it, state.dock, effect.from, effect.to) }
                Section.Apps -> settings.update { AppArrangement.moveApp(it, state.apps, effect.from, effect.to) }
                else -> Unit
            }
            is NavEffect.AppMenuAction -> appMenuAction(effect.app, effect.item, state)
            is NavEffect.Confirmed -> confirmed(effect.id)
            is NavEffect.Bump -> bump.value = BumpEvent((bump.value?.seq ?: 0) + 1, effect.direction)
            NavEffect.ExitApp -> if (!state.device.isDefaultHome) exitRequests.tryEmit(Unit)
        }
    }

    private fun launchApp(key: AppKey) {
        if (apps.launch(key)) settings.recordLaunch(key) else showToast("Не удалось открыть приложение")
    }

    private fun openHero(item: HeroItem) {
        when {
            item.kind == HeroKind.Demo -> showToast("Это демо-карточка: воспроизведение недоступно")
            item.appKey == null && item.intentUri == null -> openOverlay(Overlay.Settings())
            apps.launchUri(item.intentUri, item.appKey) -> item.appKey?.let { settings.recordLaunch(it) }
            else -> showToast("Не удалось открыть «${item.title}»")
        }
    }

    private fun openContinue(item: ContinueItem) {
        when {
            item.id.startsWith(DEMO_PREFIX) -> showToast("Это демо-карточка: воспроизведение недоступно")
            apps.launchUri(item.intentUri, item.appKey) -> item.appKey?.let { settings.recordLaunch(it) }
            else -> showToast("Не удалось открыть «${item.title}»")
        }
    }

    private fun settingsAction(page: SettingsPageId, rowId: String, state: LauncherUiState) {
        val arg = rowId.substringAfter(':', "")
        when {
            rowId == "demo" -> settings.update { it.copy(demoContent = !it.demoContent) }
            rowId == "non_tv" -> settings.update { it.copy(showNonTvApps = !it.showNonTvApps) }
            rowId == "dock_reset" -> openOverlay(Overlay.Confirm(ConfirmId.ResetDock))
            rowId == "apps_reset" -> openOverlay(Overlay.Confirm(ConfirmId.ResetApps))
            rowId == "home_toggle" ->
                openOverlay(Overlay.Confirm(if (state.device.homeAliasEnabled) ConfirmId.DisableHome else ConfirmId.EnableHome))
            rowId == "watch_next" ->
                if (hasWatchNextAccess()) {
                    watchNextAccess.value = true
                    showToast("Доступ уже разрешён")
                } else {
                    permissionRequests.tryEmit(READ_TV_LISTINGS)
                }
            rowId == "system_settings" ->
                if (!system.openSystemSettings()) showToast("Системные настройки недоступны")
            rowId == "diagnostics" -> viewModelScope.launch {
                val path = withContext(io) { system.writeDiagnosticsReport() }
                showToast(if (path != null) "Отчёт сохранён: $path" else "Не удалось сохранить отчёт")
            }
            rowId.startsWith("theme:") ->
                ThemeMode.entries.firstOrNull { it.name == arg }?.let { value -> settings.update { it.copy(theme = value) } }
            rowId.startsWith("accent:") ->
                AccentColor.entries.firstOrNull { it.name == arg }?.let { value -> settings.update { it.copy(accent = value) } }
            rowId.startsWith("animation:") ->
                AnimationLevel.entries.firstOrNull { it.name == arg }?.let { value -> settings.update { it.copy(animation = value) } }
            rowId.startsWith("wallpaper:") ->
                if (Wallpapers.choices.any { it.id == arg }) {
                    // Choosing "Свой файл" (again) is the user's way to say "I replaced wallpaper.jpg": re-read it.
                    if (arg == Wallpapers.CUSTOM) refreshArtwork()
                    settings.update { it.copy(wallpaper = arg) }
                }
            rowId.startsWith("size:") ->
                arg.toIntOrNull()?.let { size -> settings.update { AppArrangement.setDockSize(it, size) } }
            rowId.startsWith("app:") -> {
                val key = AppKey.parse(arg) ?: return
                when (page) {
                    SettingsPageId.DockApps -> {
                        val inDock = state.dock.any { it.key == key }
                        settings.update { AppArrangement.setDockMembership(it, state.dock, key, inDock = !inDock) }
                    }
                    SettingsPageId.AppsVisibility ->
                        settings.update { AppArrangement.setHidden(it, state.dock, key, hidden = key !in it.hiddenApps) }
                    else -> Unit
                }
            }
        }
    }

    private fun appMenuAction(app: AppKey, item: AppMenuItem, state: LauncherUiState) {
        when (item) {
            AppMenuItem.Open -> launchApp(app)
            AppMenuItem.Move -> Unit // handled inside the reducer (enters move mode)
            AppMenuItem.AddToDock -> settings.update { AppArrangement.addToDock(it, state.dock, app) }
            AppMenuItem.RemoveFromDock -> settings.update { AppArrangement.removeFromDock(it, state.dock, app) }
            AppMenuItem.Hide -> {
                settings.update { AppArrangement.setHidden(it, state.dock, app, hidden = true) }
                showToast("Приложение скрыто. Вернуть: Настройки → Приложения")
            }
            AppMenuItem.Info -> if (!apps.openAppInfo(app)) showToast("Сведения о приложении недоступны")
        }
    }

    private fun confirmed(id: ConfirmId) {
        when (id) {
            ConfirmId.ResetDock -> settings.update { AppArrangement.resetDock(it) }
            ConfirmId.ResetApps -> settings.update { AppArrangement.resetApps(it) }
            ConfirmId.EnableHome ->
                if (system.setHomeAliasEnabled(true)) {
                    // Some firmwares have a chooser screen, most show their chooser on the next HOME press.
                    system.openHomeChooser()
                    showToast("Готово. Нажмите HOME и выберите Mono TV")
                } else {
                    showToast("Не удалось включить режим домашнего экрана")
                }
            ConfirmId.DisableHome -> {
                system.setHomeAliasEnabled(false)
                showToast("Кнопка HOME снова открывает стандартный экран")
            }
        }
    }

    private fun openOverlay(overlay: Overlay) {
        nav.value = NavReducer.openOverlay(nav.value, overlay)
    }

    private fun showToast(text: String) {
        toast.value = text
        toastJob?.cancel()
        // A single one-shot delay to hide the message; nothing in the launcher ticks periodically.
        toastJob = viewModelScope.launch {
            delay(TOAST_MS)
            toast.value = null
        }
    }

    companion object {
        private const val TOAST_MS = 3600L
        private const val DEMO_PREFIX = "demo:"

        /** Declared by the platform's TV provider app; lets a non-system app read other apps' Watch Next rows. */
        const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
    }
}
