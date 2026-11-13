package tv.monotv.launcher.data.content

import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.ContinueItem
import tv.monotv.launcher.core.HeroItem
import tv.monotv.launcher.core.HeroKind
import tv.monotv.launcher.core.Wallpapers

/**
 * Sample content for demos and screenshots, shown only while the "Демо-контент" setting is on.
 *
 * A launcher without network access and without the system-only permission to read other apps' rows often has
 * nothing real to put into the hero, which makes the home screen impossible to judge or to photograph. These
 * entries fill that gap honestly:
 *  - every title, plot and name below is invented for this app — no real film, series, studio or brand is used,
 *    so nothing here can be mistaken for an offer the TV can actually play;
 *  - every id starts with [ID_PREFIX] and hero items carry [HeroKind.Demo], which is how the rest of the app
 *    recognises them and answers "this is a demo card" instead of trying to start playback;
 *  - artwork is the built-in wallpaper set only, so the demo works offline and costs no extra APK size.
 */
object DemoContent {
    /** Prefix of every demo id (hero and continue items alike). */
    const val ID_PREFIX = "demo:"

    private const val ACTION_WATCH = "Смотреть"

    /** Four spotlights, one per content type, each on a different built-in backdrop. */
    val heroItems: List<HeroItem> = listOf(
        hero(
            slug = "lighthouse",
            eyebrow = "Сериал · 2 сезон",
            title = "Смотритель Белого мыса",
            description = "Смотритель северного маяка ловит сигнал, которого не должно существовать. " +
                "Новый сезон уже доступен целиком.",
            wallpaper = Wallpapers.AURORA,
        ),
        hero(
            slug = "dawns",
            eyebrow = "Документальный фильм",
            title = "Сорок рассветов",
            description = "Оператор-одиночка сорок дней подряд встречает солнце на разных широтах — " +
                "от тундры до экватора.",
            wallpaper = Wallpapers.HORIZON,
        ),
        hero(
            slug = "orbit",
            eyebrow = "Фильм · Фантастика",
            title = "Орбита Вейры",
            description = "Экипаж грузового корабля слышит голос из туманности, где никого не может быть. " +
                "Ответить — значит нарушить приказ.",
            wallpaper = Wallpapers.NEBULA,
        ),
        hero(
            slug = "orchestra",
            eyebrow = "Концерт",
            title = "Оркестр на перевале",
            description = "Камерный оркестр играет под открытым небом на высоте двух тысяч метров. " +
                "Один вечер, снятый без единого дубля.",
            wallpaper = Wallpapers.RIDGE,
        ),
    )

    /**
     * Five "continue watching" cards. The progress values match the remaining time in the subtitles (e.g. 24 minutes
     * left of a 45-minute episode is 0.47), so the row looks plausible next to real Watch Next entries.
     */
    val continueItems: List<ContinueItem> = listOf(
        resume("telegraph", "Песчаный телеграф", "2 сезон · 5 серия · осталось 9 мин", Wallpapers.DUNES, 0.82f),
        resume("tides", "Остров трёх приливов", "Фильм · осталось 1 ч 5 мин", Wallpapers.TIDE, 0.35f),
        resume("dawns", "Сорок рассветов", "Осталось 36 мин", Wallpapers.HORIZON, 0.6f),
        resume("orchestra", "Оркестр на перевале", "Концерт · осталось 1 ч 28 мин", Wallpapers.RIDGE, 0.12f),
        resume("lighthouse", "Смотритель Белого мыса", "3 серия · осталось 24 мин", Wallpapers.AURORA, 0.47f),
    )

    private fun hero(slug: String, eyebrow: String, title: String, description: String, wallpaper: String) = HeroItem(
        id = "${ID_PREFIX}hero:$slug",
        kind = HeroKind.Demo,
        eyebrow = eyebrow,
        title = title,
        description = description,
        actionLabel = ACTION_WATCH,
        art = ArtSource.Wallpaper(wallpaper),
        appKey = null,
        intentUri = null,
        progress = null,
    )

    private fun resume(slug: String, title: String, subtitle: String, wallpaper: String, progress: Float) = ContinueItem(
        id = "${ID_PREFIX}continue:$slug",
        title = title,
        subtitle = subtitle,
        art = ArtSource.Wallpaper(wallpaper),
        progress = progress,
        appKey = null,
        intentUri = null,
    )
}
