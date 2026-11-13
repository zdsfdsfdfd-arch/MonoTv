package tv.monotv.launcher.ui

import tv.monotv.launcher.core.AppEntry
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.ClockState
import tv.monotv.launcher.core.ContinueItem
import tv.monotv.launcher.core.DeviceInfo
import tv.monotv.launcher.core.EffectsLevel
import tv.monotv.launcher.core.HeroItem
import tv.monotv.launcher.core.HeroKind
import tv.monotv.launcher.core.InputItem
import tv.monotv.launcher.core.InputKind
import tv.monotv.launcher.core.LauncherSettings
import tv.monotv.launcher.core.LauncherUiState
import tv.monotv.launcher.core.NetworkKind
import tv.monotv.launcher.core.NetworkState
import tv.monotv.launcher.core.Wallpapers
import tv.monotv.launcher.ui.FakeArtworkLoader.Brand

/** A realistic Sber TV: the five dock apps of the brief plus Russian services and a few icon-only utilities. */
object SampleData {
    private data class Sample(val pkg: String, val brand: Brand, val leanback: Boolean = true)

    private val samples = listOf(
        Sample("com.google.android.youtube.tv", Brand("YouTube", 0xFFE8362B.toInt(), 0xFF8E1410.toInt())),
        Sample("com.netflix.ninja", Brand("NETFLIX", 0xFF16161A.toInt(), 0xFF050506.toInt(), text = 0xFFE50914.toInt())),
        Sample("com.wbd.stream", Brand("HBO Max", 0xFF5B2BD9.toInt(), 0xFF1B0B52.toInt())),
        Sample("com.apple.atve.androidtv.appletv", Brand("Apple TV", 0xFF2B2E33.toInt(), 0xFF0C0D0F.toInt())),
        Sample("com.spotify.tv.android", Brand("Spotify", 0xFF1ED760.toInt(), 0xFF0B5A29.toInt(), text = 0xFF06140B.toInt())),
        Sample("ru.kinopoisk.tv", Brand("Кинопоиск", 0xFFFF6A00.toInt(), 0xFF8A2B00.toInt())),
        Sample("ru.more.play", Brand("Okko", 0xFF7B2BFF.toInt(), 0xFF2A0B6B.toInt())),
        Sample("ru.rt.video.app.tv", Brand("Wink", 0xFFFF4F12.toInt(), 0xFF7A1F00.toInt())),
        Sample("ru.ivi.client", Brand("Иви", 0xFFEA003D.toInt(), 0xFF6B001C.toInt())),
        Sample("ru.rutube.app", Brand("RUTUBE", 0xFF14191F.toInt(), 0xFF05070A.toInt(), text = 0xFF00D1FF.toInt())),
        Sample("com.vk.tv", Brand("VK Видео", 0xFF0077FF.toInt(), 0xFF00357A.toInt())),
        Sample("com.zvooq.openplay", Brand("Звук", 0xFF00C2A8.toInt(), 0xFF004C44.toInt(), text = 0xFF02110F.toInt())),
        Sample("ru.sberdevices.starlivetv", Brand("ТВ-каналы", 0xFF21A038.toInt(), 0xFF0B4A1A.toInt())),
        Sample("ru.mts.mtstv", Brand("KION", 0xFFE30611.toInt(), 0xFF5A0206.toInt())),
        Sample("gpm.tnt_premier", Brand("PREMIER", 0xFFFFD400.toInt(), 0xFF8A6A00.toInt(), text = 0xFF14110A.toInt())),
        Sample("ru.start.androidmobile", Brand("START", 0xFFF2F2F2.toInt(), 0xFFA7A7A7.toInt(), text = 0xFF0B0B0B.toInt())),
        Sample("ru.sberdevices.starbrowser", Brand("Браузер", 0xFF3D7BFF.toInt(), 0xFF173A8A.toInt(), iconOnly = true)),
        Sample("org.videolan.vlc", Brand("VLC", 0xFFFF8800.toInt(), 0xFF9A4A00.toInt(), iconOnly = true)),
        Sample("com.android.tv.settings.files", Brand("Файлы", 0xFF7C8794.toInt(), 0xFF39424D.toInt(), iconOnly = true)),
        Sample("ru.sberdevices.starmarket", Brand("Магазин приложений", 0xFF19C2A0.toInt(), 0xFF0A5C4C.toInt(), iconOnly = true)),
        Sample("tv.twitch.android.app", Brand("Twitch", 0xFF9146FF.toInt(), 0xFF3A1A75.toInt())),
        Sample("org.xbmc.kodi", Brand("Kodi", 0xFF12B2E7.toInt(), 0xFF07506A.toInt())),
        Sample("com.plexapp.android", Brand("Plex", 0xFF1F1F1F.toInt(), 0xFF0A0A0A.toInt(), text = 0xFFE5A00D.toInt())),
    )

    fun install(loader: FakeArtworkLoader) = samples.forEach { loader.register(it.pkg, it.brand) }

    val allApps: List<AppEntry> = samples.map {
        AppEntry(
            key = AppKey(it.pkg, "${it.pkg}.Main"),
            label = it.brand.label.let { l -> if (l == "NETFLIX") "Netflix" else l },
            isLeanback = it.leanback,
            hasBanner = !it.brand.iconOnly,
            isSystem = it.pkg.startsWith("ru.sberdevices") || it.pkg.startsWith("com.android"),
            knownId = null,
            versionCode = 1,
            installedAt = 0,
        )
    }

    fun dock(size: Int = 5): List<AppEntry> = allApps.take(size)

    val hero: List<HeroItem> = listOf(
        HeroItem(
            id = "demo:polar", kind = HeroKind.Demo, eyebrow = "Сериал · 2 сезон", title = "Полярная станция",
            description = "Экипаж зимовки находит во льдах сигнал, которого не должно существовать. Новый сезон уже доступен.",
            actionLabel = "Смотреть", art = ArtSource.Wallpaper(Wallpapers.AURORA), appKey = null, intentUri = null, progress = null,
        ),
        HeroItem(
            id = "wn:1", kind = HeroKind.Content, eyebrow = "Продолжить просмотр", title = "Сигнал с орбиты",
            description = "4 серия · осталось 18 минут", actionLabel = "Продолжить",
            art = ArtSource.Wallpaper(Wallpapers.HORIZON), appKey = allApps[5].key, intentUri = null, progress = 0.8f,
        ),
        HeroItem(
            id = "app:youtube", kind = HeroKind.App, eyebrow = "Приложение", title = "YouTube",
            description = "Видео, музыка и прямые эфиры на большом экране.", actionLabel = "Открыть",
            art = ArtSource.Wallpaper(Wallpapers.NEBULA), appKey = allApps[0].key, intentUri = null, progress = null,
        ),
        HeroItem(
            id = "demo:dunes", kind = HeroKind.Demo, eyebrow = "Документальный фильм", title = "Дюны и ветер",
            description = "Путешествие по самым тихим местам планеты, снятое за один год.",
            actionLabel = "Смотреть", art = ArtSource.Wallpaper(Wallpapers.DUNES), appKey = null, intentUri = null, progress = null,
        ),
    )

    val continueWatching: List<ContinueItem> = listOf(
        ContinueItem("wn:1", "Сигнал с орбиты", "4 серия · осталось 18 мин", ArtSource.Wallpaper(Wallpapers.HORIZON), 0.8f, allApps[5].key, null),
        ContinueItem("wn:2", "Полярная станция", "2 сезон · 1 серия", ArtSource.Wallpaper(Wallpapers.AURORA), 0.35f, allApps[6].key, null),
        ContinueItem("wn:3", "Тихий океан", "Осталось 52 мин", ArtSource.Wallpaper(Wallpapers.TIDE), 0.6f, allApps[7].key, null),
        ContinueItem("recent:yt", "YouTube", "Недавно открывали", ArtSource.AppBanner(allApps[0].key), null, allApps[0].key, null),
        ContinueItem("wn:4", "Хребет", "Фильм · осталось 1 ч 12 мин", ArtSource.Wallpaper(Wallpapers.RIDGE), 0.12f, allApps[8].key, null),
        ContinueItem("wn:5", "Туманность", "Концерт", ArtSource.Wallpaper(Wallpapers.NEBULA), 0.47f, allApps[4].key, null),
    )

    val inputs: List<InputItem> = listOf(
        InputItem("hdmi1", "HDMI 1", InputKind.Hdmi, connected = true, switchable = true),
        InputItem("hdmi2", "HDMI 2", InputKind.Hdmi, connected = false, switchable = true),
        InputItem("hdmi3", "HDMI 3", InputKind.Hdmi, connected = false, switchable = true),
        InputItem("av", "AV", InputKind.Av, connected = false, switchable = true),
        InputItem("tuner", "Эфирное ТВ", InputKind.Tuner, connected = null, switchable = true),
    )

    val device = DeviceInfo(
        manufacturer = "SberDevices", brand = "SaluteTV", model = "SaluteTV", device = "at30a6", androidRelease = "13", sdkInt = 33,
        widthPx = 1920, heightPx = 1080, densityDpi = 320, totalRamMb = 2048, lowRam = false, cpuCores = 4, hasLeanback = true,
        launcherVersion = "0.1.0", currentHomePackage = "ru.sberdevices.starlauncher",
    )

    fun state(dockSize: Int = 5, settings: LauncherSettings = LauncherSettings(dockSize = dockSize)): LauncherUiState = LauncherUiState(
        clock = ClockState("19:42", "13 ноября, пятница", "Добрый вечер"),
        network = NetworkState(NetworkKind.Wifi, validated = true),
        hero = hero,
        dock = dock(dockSize),
        continueWatching = continueWatching,
        apps = allApps,
        allApps = allApps,
        inputs = inputs,
        settings = settings,
        device = device,
        effects = EffectsLevel.Full,
        loaded = true,
    )
}
