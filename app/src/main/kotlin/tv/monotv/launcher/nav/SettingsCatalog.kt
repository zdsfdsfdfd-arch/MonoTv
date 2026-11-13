package tv.monotv.launcher.nav

import tv.monotv.launcher.core.AccentColor
import tv.monotv.launcher.core.AnimationLevel
import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.DeviceInfo
import tv.monotv.launcher.core.EffectsLevel
import tv.monotv.launcher.core.LauncherSettings
import tv.monotv.launcher.core.LauncherUiState
import tv.monotv.launcher.core.ThemeMode
import tv.monotv.launcher.core.Wallpapers

/**
 * Builds the rows of every settings page from the current UI state.
 *
 * The settings screen has no state of its own: the same pure function feeds the reducer (how many rows are there,
 * what does OK do on row N) and the UI (what to draw), so the two can never disagree about what is on screen.
 *
 * Row ids are a public contract — the ViewModel switches on them when it receives [NavEffect.SettingsAction] /
 * [NavEffect.SettingsMove] — and must stay stable:
 *  - fixed rows use the plain ids listed in the page builders below ("theme", "dock_reset", "home_toggle", ...);
 *  - choice rows are `"<group>:<value>"` with the enum constant name, wallpaper id or dock size as value
 *    ("theme:Black", "wallpaper:aurora", "accent:Sky", "animation:Reduced", "size:5");
 *  - app rows are `"app:<AppKey.flatten()>"`; what activating one means depends on the page it is on.
 */
object SettingsCatalog {

    /** Rows of [page] for the data in [ui], top to bottom. Empty when the page currently has nothing to show. */
    fun rows(page: SettingsPageId, ui: LauncherUiState): List<SettingsRow> = when (page) {
        SettingsPageId.Root -> root()
        SettingsPageId.Appearance -> appearance(ui)
        SettingsPageId.Theme -> theme(ui.settings)
        SettingsPageId.Wallpaper -> wallpaper(ui.settings)
        SettingsPageId.Accent -> accent(ui.settings)
        SettingsPageId.Animation -> animation(ui.settings)
        SettingsPageId.Dock -> dock(ui)
        SettingsPageId.DockSize -> dockSize(ui.settings)
        SettingsPageId.DockApps -> dockApps(ui)
        SettingsPageId.DockOrder -> ui.dock.map(::reorderable)
        SettingsPageId.Apps -> apps(ui)
        SettingsPageId.AppsVisibility -> appsVisibility(ui)
        SettingsPageId.AppsOrder -> ui.apps.map(::reorderable)
        SettingsPageId.System -> system(ui.device)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Pages
    // -----------------------------------------------------------------------------------------------------------------

    private fun root(): List<SettingsRow> = listOf(
        navigate("appearance", SettingsPageId.Appearance, description = "Тема, обои, акцент, анимации"),
        navigate("dock", SettingsPageId.Dock, description = "Состав и размер"),
        navigate("apps", SettingsPageId.Apps, description = "Показ и порядок"),
        navigate("system", SettingsPageId.System, description = "Устройство и версия"),
    )

    private fun appearance(ui: LauncherUiState): List<SettingsRow> {
        val settings = ui.settings
        return listOf(
            navigate("theme", SettingsPageId.Theme, value = settings.theme.title),
            navigate(
                "wallpaper",
                SettingsPageId.Wallpaper,
                value = Wallpapers.choices.firstOrNull { it.id == settings.wallpaper }?.title,
            ),
            navigate("accent", SettingsPageId.Accent, value = settings.accent.title),
            navigate("animation", SettingsPageId.Animation, value = animationValue(settings.animation, ui.effects)),
            action(
                "demo",
                "Демо-контент",
                value = onOff(settings.demoContent),
                description = "Примеры карточек для проверки интерфейса",
            ),
        )
    }

    private fun theme(settings: LauncherSettings): List<SettingsRow> = ThemeMode.entries.map { mode ->
        action(
            id = "theme:${mode.name}",
            title = mode.title,
            description = when (mode) {
                ThemeMode.Black -> "Истинный чёрный для OLED и тёмной комнаты"
                ThemeMode.Graphite -> "Мягкий тёмно-серый фон"
            },
            checked = mode == settings.theme,
        )
    }

    private fun wallpaper(settings: LauncherSettings): List<SettingsRow> = Wallpapers.choices.map { choice ->
        action(
            id = "wallpaper:${choice.id}",
            title = choice.title,
            description = when (choice.id) {
                Wallpapers.DYNAMIC -> "Фон следует за выбранной карточкой"
                Wallpapers.CUSTOM -> "Файл wallpaper.jpg в папке приложения (см. README)"
                else -> null
            },
            checked = choice.id == settings.wallpaper,
        )
    }

    private fun accent(settings: LauncherSettings): List<SettingsRow> = AccentColor.entries.map { color ->
        action(id = "accent:${color.name}", title = color.title, checked = color == settings.accent)
    }

    private fun animation(settings: LauncherSettings): List<SettingsRow> = AnimationLevel.entries.map { level ->
        action(
            id = "animation:${level.name}",
            title = level.title,
            description = when (level) {
                AnimationLevel.Auto -> "Подбирается под производительность устройства"
                AnimationLevel.Full -> "Пружины, свечение, параллакс"
                AnimationLevel.Reduced -> "Короткие переходы без свечения"
                AnimationLevel.Off -> "Мгновенная смена фокуса"
            },
            checked = level == settings.animation,
        )
    }

    private fun dock(ui: LauncherUiState): List<SettingsRow> {
        val size = ui.settings.dockSize
        return listOf(
            navigate("size", SettingsPageId.DockSize, value = size.toString()),
            navigate("dock_apps", SettingsPageId.DockApps, value = "${ui.dock.size} из $size"),
            // Reordering needs at least two items; a disabled row explains itself better than a missing one.
            navigate("dock_order", SettingsPageId.DockOrder, title = "Порядок", enabled = ui.dock.size > 1),
            action("dock_reset", "Сбросить Dock", description = "Вернуть автоматический набор"),
        )
    }

    private fun dockSize(settings: LauncherSettings): List<SettingsRow> =
        (LauncherSettings.MIN_DOCK_SIZE..LauncherSettings.MAX_DOCK_SIZE).map { size ->
            action(id = "size:$size", title = "$size ${appsNoun(size)}", checked = size == settings.dockSize)
        }

    /**
     * Candidates for the Dock: every app the user can currently see. Apps that already sit in the Dock are listed
     * even when they are hidden or filtered out as "non-TV" — otherwise a docked app could not be unchecked here.
     */
    private fun dockApps(ui: LauncherUiState): List<SettingsRow> {
        val settings = ui.settings
        val docked = ui.dock.mapTo(HashSet()) { it.key }
        val full = ui.dock.size >= settings.dockSize
        return ui.allApps
            .filter { it.key in docked || (it.key !in settings.hiddenApps && it.isListed(settings)) }
            .map { app ->
                val inDock = app.key in docked
                // A full Dock still lets the user uncheck; only adding is blocked, and the row says why.
                val blocked = !inDock && full
                action(
                    id = appRowId(app.key),
                    title = app.label,
                    description = if (blocked) "Dock заполнен" else null,
                    checked = inDock,
                    appKey = app.key,
                    enabled = !blocked,
                )
            }
    }

    private fun apps(ui: LauncherUiState): List<SettingsRow> {
        val settings = ui.settings
        val listed = ui.allApps.filter { it.isListed(settings) }
        val visible = listed.count { it.key !in settings.hiddenApps }
        return listOf(
            navigate("visibility", SettingsPageId.AppsVisibility, value = "$visible из ${listed.size}"),
            navigate("order", SettingsPageId.AppsOrder, enabled = ui.apps.size > 1),
            action(
                "non_tv",
                "Приложения без TV-режима",
                value = onOff(settings.showNonTvApps),
                description = "Показывать приложения, рассчитанные на телефон",
            ),
            action("apps_reset", "Сбросить порядок и скрытые"),
        )
    }

    private fun appsVisibility(ui: LauncherUiState): List<SettingsRow> {
        val settings = ui.settings
        return ui.allApps.filter { it.isListed(settings) }.map { app ->
            action(id = appRowId(app.key), title = app.label, checked = app.key !in settings.hiddenApps, appKey = app.key)
        }
    }

    private fun system(device: DeviceInfo): List<SettingsRow> = listOf(
        info("model", "Устройство", "${device.manufacturer} ${device.model}".trim()),
        info("android", "Android", "${device.androidRelease} (API ${device.sdkInt})"),
        info("display", "Экран", "${device.widthPx}×${device.heightPx} · ${device.densityDpi} dpi"),
        info("memory", "Память", gigabytes(device.totalRamMb)),
        info("version", "Версия Mono TV", device.launcherVersion),
        info("home", "Домашний экран", if (device.isDefaultHome) "Mono TV" else device.currentHomePackage ?: "Не определён"),
        action("system_settings", "Системные настройки", description = "Сеть, звук, обновления"),
        action(
            "home_toggle",
            if (device.homeAliasEnabled) "Вернуть стандартный домашний экран" else "Сделать домашним экраном",
            // Shown in both states: the promise that the stock home screen stays intact is the point of this row.
            description = "Стандартный экран Сбер ТВ не удаляется и не отключается",
        ),
        action("diagnostics", "Сохранить отчёт об устройстве", description = "Только чтение: модель, экран, приложения, входы"),
    )

    // -----------------------------------------------------------------------------------------------------------------
    // Row builders and formatting
    // -----------------------------------------------------------------------------------------------------------------

    private fun navigate(
        id: String,
        page: SettingsPageId,
        title: String = page.title,
        value: String? = null,
        description: String? = null,
        enabled: Boolean = true,
    ) = SettingsRow(id = id, title = title, value = value, kind = RowKind.Navigate(page), description = description, enabled = enabled)

    private fun action(
        id: String,
        title: String,
        value: String? = null,
        description: String? = null,
        checked: Boolean? = null,
        appKey: AppKey? = null,
        enabled: Boolean = true,
    ) = SettingsRow(
        id = id,
        title = title,
        value = value,
        kind = RowKind.Action,
        description = description,
        checked = checked,
        appKey = appKey,
        enabled = enabled,
    )

    private fun info(id: String, title: String, value: String) =
        SettingsRow(id = id, title = title, value = value, kind = RowKind.Info)

    private fun reorderable(app: AppEntry) =
        SettingsRow(id = appRowId(app.key), title = app.label, kind = RowKind.Reorderable, appKey = app.key)

    private fun appRowId(key: AppKey): String = "app:${key.flatten()}"

    /** Same rule the Apps section uses: phone-style apps are only offered when the user opted in. */
    private fun AppEntry.isListed(settings: LauncherSettings): Boolean = isLeanback || settings.showNonTvApps

    private fun onOff(on: Boolean): String = if (on) "Вкл" else "Выкл"

    /** "Авто" alone would hide what the device actually got, so the resolved level is appended to it. */
    private fun animationValue(level: AnimationLevel, effects: EffectsLevel): String {
        if (level != AnimationLevel.Auto) return level.title
        val resolved = when (effects) {
            EffectsLevel.Full -> AnimationLevel.Full
            EffectsLevel.Reduced -> AnimationLevel.Reduced
            EffectsLevel.Off -> AnimationLevel.Off
        }
        return "${level.title} · ${resolved.title.lowercase()}"
    }

    /** Russian plural of "приложение" after a number: 1 приложение, 4 приложения, 5 приложений, 21 приложение. */
    private fun appsNoun(count: Int): String = when {
        count % 100 in 11..14 -> "приложений"
        count % 10 == 1 -> "приложение"
        count % 10 in 2..4 -> "приложения"
        else -> "приложений"
    }

    /** Megabytes as "2,0 ГБ": one decimal, rounded half up, Russian decimal comma (no Locale involved). */
    private fun gigabytes(megabytes: Int): String {
        val tenths = (megabytes.coerceAtLeast(0) * 10L + 512) / 1024
        return "${tenths / 10},${tenths % 10} ГБ"
    }
}
