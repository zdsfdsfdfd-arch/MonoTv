package tv.monotv.launcher.nav

import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.LauncherUiState

/**
 * Decides which entries the context menu of an app offers (long OK / MENU on an app tile).
 *
 * The menu only ever lists actions that would actually do something right now — no greyed-out entries. A short menu
 * is faster to use with a D-pad, and [NavReducer] can then treat every entry as activatable.
 */
object AppMenuCatalog {

    /**
     * Menu for [app] opened from section [from], in display order. Never empty: "Открыть" is always first so a
     * stray long press followed by OK still does the obvious thing, and "О приложении" is always last.
     */
    fun itemsFor(app: AppKey, from: Section, ui: LauncherUiState): List<AppMenuItem> = buildList {
        add(AppMenuItem.Open)

        val siblings = when (from) {
            Section.Dock -> ui.dock.size
            Section.Apps -> ui.apps.size
            Section.TopBar, Section.Hero, Section.Continue -> 0
        }
        // A single item has nowhere to go.
        if (siblings > 1) add(AppMenuItem.Move)

        if (ui.dock.any { it.key == app }) {
            add(AppMenuItem.RemoveFromDock)
        } else if (ui.dock.size < ui.settings.dockSize) {
            add(AppMenuItem.AddToDock)
        }

        // Hiding is about the Apps grid; from the Dock the matching action is "Убрать из Dock".
        if (from == Section.Apps) add(AppMenuItem.Hide)

        add(AppMenuItem.Info)
    }
}
