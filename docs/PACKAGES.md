# Список package names

Этот файл — справочник, по которому собран `app/src/main/assets/launcher-config.json`.
Источник — веб-исследование (ноябрь 2026): страницы магазинов, манифесты open-source приложений и
реальные `pm list packages` с телевизоров Sber, опубликованные сообществом. **На вашем телевизоре список
ещё не проверялся** — после запуска `scripts/analyze-device.sh` (или «Настройки → Система → Сохранить отчёт
об устройстве» в самом лаунчере) его нужно сверить с фактическим отчётом.

Обозначения достоверности:

| Метка | Что значит |
|-------|------------|
| **В** | высокая: id виден на странице магазина / в манифесте / в нескольких независимых дампах |
| **С** | средняя: id встречается в дампах устройств, роль пакета описана сообществом |
| **Н** | низкая: единичное упоминание, использовать только как подсказку |

Лаунчер **не хардкодит** этот список: приложения находятся автоматически через
`CATEGORY_LEANBACK_LAUNCHER`, приватную категорию Сбера `ru.sberdevices.category.STAR_LAUNCHER` и
(по желанию) `CATEGORY_LAUNCHER`. Таблица ниже нужна только для двух вещей: порядка автоматического Dock и
коротких подписей в Hero. Неустановленные приложения просто пропускаются — пустых карточек не бывает.

## 1. Приложения для Dock и главного экрана

Порядок строк = приоритет автоматического Dock (`dockPriority`).

| id в конфиге | Приложение | Пакеты (сначала TV-сборка) | Дост. |
|---|---|---|---|
| `youtube` | YouTube | `com.google.android.youtube.tv` · `ru.sberdevices.startube` (клиент Сбера, только категория STAR_LAUNCHER) · `org.smarttube.stable` · `org.smarttube.beta` · `app.smarttube.fdroid` · `com.teamsmart.videomanager.tv` · `com.liskovsoft.smarttubetv.beta` · `com.amazon.firetv.youtube` | В / С |
| `netflix` | Netflix | `com.netflix.ninja` · `com.netflix.mediaclient` (телефонная) | В |
| `hbomax` | HBO Max / Max | `com.wbd.stream` · `com.hbo.hbonow` (старый id) | В |
| `appletv` | Apple TV | `com.apple.atve.androidtv.appletv` · `com.apple.atve.sony.appletv` · `com.apple.atve.amazon.appletv` | В |
| `spotify` | Spotify | `com.spotify.tv.android` · `com.spotify.music` (телефонная) | В / С |
| `kinopoisk` | Кинопоиск | `ru.kinopoisk.tv` · `ru.kinopoisk` (телефонная) | В |
| `okko` | Okko | `tv.okko.androidtv` · `ru.more.play` (она же предустановлена на Сбер ТВ) | В |
| `wink` | Wink | `ru.rt.video.app.tv` · `ru.rt.video.app.mobile` | В |
| `ivi` | Иви | `ru.ivi.client` | В |
| `rutube` | RUTUBE | `ru.rutube.app.tv` · `ru.rutube.app` | В |
| `vkvideo` | VK Видео | `com.vk.tv` · `com.vk.vkvideo` | В |
| `kion` | KION | `ru.mts.mtstv` | В |
| `premier` | PREMIER | `one.premier.rustoretv` · `gpm.tnt_premier` | В |
| `start` | START | `ru.start.androidtv` · `ru.start.androidmobile` | В |
| `zvuk` | Звук | `ru.sberdevices.music` (предустановка Сбера) · `com.zvooq.openplay` | С / В |
| `sbertv` | ТВ-каналы Сбера | `ru.sberdevices.starlivetv` | С |
| `sbervideo` | Видео (каталог Сбера) | `ru.sberdevices.videos` | С |
| `sberplay` | SberPlay | `ru.sberdevices.sberplay` | С |
| `amediateka` | Amediateka | `ru.amediateka` · `com.amdteka` | В |
| `tricolor` | Триколор Кино и ТВ | `com.gsgroup.tricoloronline` | В |
| `smotreshka` | Смотрёшка | `tv.lfstrm.smotreshka_stb` · `tv.lfstrm.smotreshka` | С / В |
| `limehd` | Лайм HD | `tv.limehd.stb` · `limehd.ru.ctv.tv` · `com.infolink.limeiptv` | В |
| `peerstv` | Peers.TV | `ru.cn.tv` | В |
| `yandexmusic` | Яндекс Музыка | `ru.yandex.music` (отдельной TV-сборки не найдено) | В |
| `twitch` | Twitch | `tv.twitch.android.app` | В |
| `primevideo` | Prime Video | `com.amazon.amazonvideo.livingroom` | В |
| `disneyplus` | Disney+ | `com.disney.disneyplus` | В |
| `plex` | Plex | `com.plexapp.android` | В |
| `kodi` | Kodi | `org.xbmc.kodi` | В |
| `vlc` | VLC | `org.videolan.vlc` | В |
| `jellyfin` | Jellyfin | `org.jellyfin.androidtv` | В |
| `stremio` | Stremio | `com.stremio.one` | В |
| `mxplayer` | MX Player TV | `com.mxtech.videoplayer.television` | В |
| `steamlink` | Steam Link | `com.valvesoftware.steamlink` | В |
| `geforcenow` | GeForce NOW | `com.nvidia.geforcenow` | В |
| `rustore` | RuStore для ТВ | `ru.vk.store.tv` | С |

Важное следствие для Сбер ТВ: на прошивках без сервисов Google официальный YouTube не работает, поэтому
слот «YouTube» в Dock займёт то, что реально установлено — клиент Сбера `ru.sberdevices.startube` или
SmartTube. Netflix, HBO Max, Apple TV и Spotify появятся в Dock, только если вы их установили; иначе их
места автоматически займут следующие по приоритету сервисы (Кинопоиск, Okko, Wink …).

## 2. Системные пакеты Сбер ТВ — не трогать

Лаунчер ничего из этого не отключает, не удаляет и не изменяет. Список нужен, чтобы при анализе устройства
понимать, что есть что. Роли описаны сообществом (достоверность **С**), имена пакетов встречаются в
нескольких независимых дампах.

| Пакет | Назначение | Что будет, если отключить |
|---|---|---|
| `ru.sberdevices.starlauncher` | штатный домашний экран (`.ui.mainscreen.MainActivity`) | сообщения о циклической перезагрузке / recovery на части прошивок |
| `ru.sberdevices.assistant` | голосовой ассистент «Салют» | перестаёт работать кнопка ассистента на пульте |
| `ru.sberdevices.services`, `ru.sberdevices.services.core`, `ru.sberdevices.SberDevicesServiceSystem` | платформенный сервис: бэкенд ассистента, Сбер ID, установка приложений | сторожевой процесс включает обратно в течение секунды |
| `ru.sberdevices.starmarket` | каталог приложений | не сможете ставить приложения из каталога |
| `ru.sberdevices.settings`, `com.sberbank.settings`, `com.android.tv.settings` | настройки (в последнем — запасной `FallbackHome`) | потеря доступа к настройкам |
| `ru.sberdevices.updater` | обновления прошивки (OTA) | не придут обновления |
| `ru.sberdevices.tvinputs` | проигрывание HDMI / AV-входов | не работают входы |
| `ru.sberdevices.starlivetv` | эфирное / онлайн-ТВ | не работают каналы |
| `ru.sberdevices.app.starkeyboard` | единственная экранная клавиатура | нечем вводить текст |
| `ru.sberdevices.webview` | системный WebView | ломаются приложения на WebView |
| `ru.sberdevices.systemui`, `com.android.systemui` | системный интерфейс | — |
| `ru.sberdevices.starprofile` | профиль / вход по Сбер ID | — |
| `ru.sberdevices.starscreensaver` | заставка | прошивка включает обратно при загрузке |
| `ru.sberdevices.rcfupdater` | обновление прошивки пульта | — |
| `ru.sberdevices.gamepad.service` | пульт в приложении «Салют» на телефоне | — |
| `ru.sberdevices.startube`, `ru.sberdevices.starbrowser`, `ru.sberdevices.music`, `ru.sberdevices.videos`, `ru.sberdevices.sberplay`, `ru.sberdevices.starkidsroom.prod`, `com.salute.star` | пользовательские приложения Сбера (YouTube-клиент, браузер, Звук, Видео, игры, детская, умный дом) | прошивка может переустановить |
| `com.droidlogic.tvinput`, `com.droidlogic.dtvkit.inputsource` (Amlogic) · `com.mediatek.tvinput`, `com.mediatek.tv.service` (MediaTek) | сервисы TV Input Framework: тюнер, HDMI, картинка | не работают входы и тюнер |
| `com.cvte.*`, `com.cvte.sber.onekey`, `ru.sberdevices.onekey` | сервисы платы CVTE, кнопки-ярлыки пульта | — |
| `com.android.providers.tv` | TV-провайдер (каналы, Watch Next) | — |

## 3. Заводские, инженерные и сервисные пакеты — никогда не показывать и не запускать

Эти пакеты внесены в `blockedPackages` / `blockedKeywords`. Даже если прошивка объявит для них
launcher-activity, Mono TV не покажет карточку и откажется запускать их по прямой ссылке.

| Пакет | Где встречается |
|---|---|
| `com.cvte.fac.*` (в т.ч. `com.cvte.fac.menu`), `com.cvte.tv.hkcfac` | заводское меню плат CVTE (в т.ч. Сбер ТВ) |
| `com.stb.factorymenu` | ярлык заводского меню |
| `ru.sberdevices.TvFactoryHelper` | заводская утилита Сбер ТВ |
| `mediatek.factorymenu.ui`, `com.mediatek.tv.factory`, `com.mediatek.testpq`, `com.mediatek.factorymode*`, `com.mediatek.engineermode*` | MediaTek |
| `com.droidlogic.readlog`, `com.droidlogic.factory*` | Amlogic |
| `com.realtek.factorytools`, `com.rtk.engmenu`, `com.realtek.servicemenu`, `com.apps.factory.ui`, `com.toptech.tvfactory` | Realtek |
| `com.at.factory.factorymanager`, `com.tcl.factory.view`, `com.homwee.factory`, `com.smartdevice.factory`, `com.duokan.factorytest`, `com.hisense.autotest`, `com.sdt.producttest`, `com.sdmc.facTest`, `com.utsmta.app`, `com.fengmi.testsuite`, `com.android.factorytest` | прочие производители |

Ключевые слова (подстрока в имени пакета): `factory`, `factorytest`, `engineermode`, `engmode`,
`servicemenu`, `hwtest`, `burnin`, `agingtest`, `producttest`.

Слово `engmenu` намеренно **не** в списке: популярный сторонний лаунчер Projectivy называется
`com.spocky.projengmenu` и является обычным пользовательским приложением.

## 4. Пакет самого лаунчера

| | |
|---|---|
| applicationId | `tv.monotv.launcher` |
| Основная activity | `tv.monotv.launcher/.MainActivity` (категории `LEANBACK_LAUNCHER`, `LAUNCHER`) |
| HOME-псевдоним | `tv.monotv.launcher/.HomeAlias` — в APK **выключен**; включается только явным действием пользователя |

## 5. Как добавить своё приложение в справочник

Обычно ничего делать не нужно: установленное TV-приложение появится в разделе «Приложения» само, а в Dock
его можно добавить долгим нажатием OK → «Добавить в Dock». Справочник нужен, только если вы хотите, чтобы
приложение попадало в *автоматический* Dock. Тогда добавьте запись в `launcher-config.json`:

```json
{ "id": "myapp", "title": "My App", "packages": ["com.example.myapp.tv"], "tagline": "Короткая подпись для Hero" }
```

и впишите `"myapp"` в нужное место массива `dockPriority`. Подробности — в README, раздел «Как добавить
приложение».
