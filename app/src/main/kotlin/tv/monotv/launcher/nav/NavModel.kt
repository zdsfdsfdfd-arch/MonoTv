package tv.monotv.launcher.nav

import androidx.compose.runtime.Immutable
import tv.monotv.launcher.core.AppKey

/**
 * Deterministic D-pad navigation model.
 *
 * The launcher does NOT use the platform / Compose focus system for moving around. One root view owns key input,
 * every key press is turned into a [NavKey] and run through the pure function [NavReducer.reduce]. The resulting
 * [NavState] is the single source of truth for "what is focused"; the UI only renders it. Consequences:
 *  - focus can never get lost or jump somewhere surprising;
 *  - every section remembers its last selected item;
 *  - the whole navigation behaviour is unit-testable on the JVM.
 */

enum class NavKey { Up, Down, Left, Right, Ok, OkLong, Back, Menu }

/** Home sections in vertical order. UP/DOWN moves between them (empty sections are skipped). */
enum class Section { TopBar, Hero, Dock, Continue, Apps }

/** Pills of the top bar, in on-screen order (left → right). */
enum class TopBarItem(val title: String) { Network("Wi-Fi"), Inputs("Входы"), Settings("Настройки") }

@Immutable
data class HomeNav(
    val section: Section = Section.Dock,
    val topBarIndex: Int = TopBarItem.Settings.ordinal,
    val heroIndex: Int = 0,
    val dockIndex: Int = 0,
    val continueIndex: Int = 0,
    /** Index into the Apps grid (row = index / columns, column = index % columns). */
    val appsIndex: Int = 0,
)

/** "Move mode": the focused item of [section] (Dock or Apps) is picked up and follows LEFT/RIGHT/UP/DOWN. */
@Immutable
data class ReorderState(val section: Section)

enum class SettingsPageId(val title: String) {
    Root("Настройки"),
    Appearance("Оформление"),
    Theme("Тёмная тема"),
    Wallpaper("Обои"),
    Accent("Акцентный цвет"),
    Animation("Анимации"),
    Dock("Dock"),
    DockSize("Количество элементов"),
    DockApps("Приложения в Dock"),
    DockOrder("Порядок в Dock"),
    Apps("Приложения"),
    AppsVisibility("Показывать и скрывать"),
    AppsOrder("Порядок приложений"),
    System("Система"),
}

/** One page of the settings stack. [grabbed] = the focused row of a reorderable list is picked up. */
@Immutable
data class SettingsFrame(val page: SettingsPageId, val index: Int = 0, val grabbed: Boolean = false)

enum class AppMenuItem(val title: String) {
    Open("Открыть"),
    Move("Переместить"),
    AddToDock("Добавить в Dock"),
    RemoveFromDock("Убрать из Dock"),
    Hide("Скрыть"),
    Info("О приложении"),
}

enum class ConfirmId(val title: String, val message: String, val confirmLabel: String) {
    ResetDock("Сбросить Dock?", "Dock вернётся к автоматическому набору приложений.", "Сбросить"),
    ResetApps("Сбросить приложения?", "Порядок и скрытые приложения вернутся к исходному состоянию.", "Сбросить"),
    EnableHome(
        "Использовать как домашний экран?",
        "После этого кнопка HOME предложит выбрать Mono TV. Стандартный экран Сбер ТВ не удаляется и не отключается — вернуть его можно здесь же.",
        "Включить",
    ),
    DisableHome("Вернуть стандартный домашний экран?", "Кнопка HOME снова будет открывать стандартный экран.", "Вернуть"),
}

@Immutable
sealed interface Overlay {
    /** Compact inputs menu under the "Входы" pill. */
    data class Inputs(val index: Int = 0) : Overlay

    /** Launcher settings; [stack] is never empty, the last frame is the visible page. */
    data class Settings(val stack: List<SettingsFrame> = listOf(SettingsFrame(SettingsPageId.Root))) : Overlay

    /** Context menu of an app (long OK / MENU on an app). [items] is fixed when the menu opens. */
    data class AppMenu(val app: AppKey, val items: List<AppMenuItem>, val index: Int = 0) : Overlay

    /** Modal confirmation. index 0 = cancel (default), 1 = confirm. */
    data class Confirm(val id: ConfirmId, val index: Int = 0) : Overlay
}

@Immutable
data class NavState(
    val home: HomeNav = HomeNav(),
    val reorder: ReorderState? = null,
    /** Overlay stack, last = top-most. Keys go to the top overlay only. */
    val overlays: List<Overlay> = emptyList(),
) {
    val topOverlay: Overlay? get() = overlays.lastOrNull()
}

// ---------------------------------------------------------------------------------------------------------------------
// Settings rows (built by SettingsCatalog from the current data; consumed by the reducer and the settings UI)
// ---------------------------------------------------------------------------------------------------------------------

@Immutable
sealed interface RowKind {
    /** OK pushes [page]. */
    data class Navigate(val page: SettingsPageId) : RowKind

    /** OK emits NavEffect.SettingsAction (toggle, choose, run). */
    data object Action : RowKind

    /** Read-only row; focusable so long pages can be scrolled, OK does nothing. */
    data object Info : RowKind

    /** OK picks the row up / drops it; while picked up UP/DOWN emits NavEffect.SettingsMove. */
    data object Reorderable : RowKind
}

@Immutable
data class SettingsRow(
    /** Stable id, unique within its page (e.g. "theme", "wallpaper:aurora", "app:com.foo/.Main"). */
    val id: String,
    val title: String,
    /** Right-aligned current value ("5", "Изумруд", "Вкл"). */
    val value: String? = null,
    val kind: RowKind,
    /** Second line under the title. */
    val description: String? = null,
    /** null = no check mark column; true/false = radio / check state. */
    val checked: Boolean? = null,
    /** When set the row shows this app's icon. */
    val appKey: AppKey? = null,
    /** Disabled rows are still focusable but OK only bumps. */
    val enabled: Boolean = true,
)

// ---------------------------------------------------------------------------------------------------------------------
// Reducer I/O
// ---------------------------------------------------------------------------------------------------------------------

/** Read-only facts about the current data that the reducer needs. Cheap to build on every key press. */
class NavContext(
    val heroCount: Int,
    val dockCount: Int,
    val continueCount: Int,
    val appsCount: Int,
    val appsColumns: Int,
    val inputsCount: Int,
    val topBarCount: Int = TopBarItem.entries.size,
    /** App shown at [index] of [section] (Dock or Apps), null when out of range. */
    val appAt: (section: Section, index: Int) -> AppKey?,
    /** Menu items to offer for an app, in order. Never empty. */
    val appMenuFor: (app: AppKey, from: Section) -> List<AppMenuItem>,
    val settingsRows: (page: SettingsPageId) -> List<SettingsRow>,
)

/** Side effects requested by the reducer; executed by the ViewModel after the state is applied. */
sealed interface NavEffect {
    data class LaunchApp(val app: AppKey) : NavEffect
    data class OpenHero(val index: Int) : NavEffect
    data class OpenContinue(val index: Int) : NavEffect
    data object OpenNetworkSettings : NavEffect
    data class SwitchInput(val index: Int) : NavEffect

    /** A settings row of kind Action was activated. */
    data class SettingsAction(val page: SettingsPageId, val rowId: String) : NavEffect

    /** A grabbed row of a Reorderable settings page moved from [from] to [to]. */
    data class SettingsMove(val page: SettingsPageId, val from: Int, val to: Int) : NavEffect

    /** Home move-mode: item of [section] moved from index [from] to [to]. */
    data class MoveItem(val section: Section, val from: Int, val to: Int) : NavEffect

    data class AppMenuAction(val app: AppKey, val item: AppMenuItem) : NavEffect
    data class Confirmed(val id: ConfirmId) : NavEffect

    /** Navigation hit an edge; the UI plays a tiny rubber-band in [direction]. */
    data class Bump(val direction: NavKey) : NavEffect

    /** BACK at the very root of Home. The ViewModel ignores it when we are the default HOME app. */
    data object ExitApp : NavEffect
}

data class NavResult(val state: NavState, val effects: List<NavEffect> = emptyList())
