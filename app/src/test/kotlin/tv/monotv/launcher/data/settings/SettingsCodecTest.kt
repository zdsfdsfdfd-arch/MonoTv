package tv.monotv.launcher.data.settings

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.monotv.launcher.core.AccentColor
import tv.monotv.launcher.core.AnimationLevel
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.LauncherSettings
import tv.monotv.launcher.core.RecentLaunch
import tv.monotv.launcher.core.ThemeMode
import tv.monotv.launcher.core.Wallpapers

class SettingsCodecTest {

    private val youtube = AppKey("com.google.android.youtube.tv", "com.google.android.apps.youtube.tv.activity.ShellActivity")
    private val okko = AppKey("ru.more.play", "ru.more.play.MainActivity")
    private val kion = AppKey("ru.mts.mtstv", ".ui.Splash\$Alias")

    // -----------------------------------------------------------------------------------------------------------------
    // Settings
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `defaults survive a round trip`() {
        assertEquals(LauncherSettings(), SettingsCodec.decode(SettingsCodec.encode(LauncherSettings())))
    }

    @Test
    fun `an empty store decodes to the defaults`() {
        assertEquals(LauncherSettings(), SettingsCodec.decode(emptyMap()))
    }

    @Test
    fun `fully customised settings survive a round trip`() {
        val settings = LauncherSettings(
            dockSize = 7,
            dockApps = listOf(okko, youtube),
            appOrder = listOf(kion, youtube, okko),
            hiddenApps = setOf(kion),
            showNonTvApps = true,
            theme = ThemeMode.Graphite,
            wallpaper = Wallpapers.NEBULA,
            accent = AccentColor.Coral,
            animation = AnimationLevel.Off,
            demoContent = true,
        )
        assertEquals(settings, SettingsCodec.decode(SettingsCodec.encode(settings)))
    }

    @Test
    fun `values are stored as plain readable text`() {
        val encoded = SettingsCodec.encode(
            LauncherSettings(dockSize = 6, dockApps = listOf(okko, youtube), theme = ThemeMode.Graphite, demoContent = true),
        )
        assertEquals("6", encoded[SettingsCodec.KEY_DOCK_SIZE])
        assertEquals("${okko.flatten()}\n${youtube.flatten()}", encoded[SettingsCodec.KEY_DOCK_APPS])
        assertEquals("Graphite", encoded[SettingsCodec.KEY_THEME])
        assertEquals("true", encoded[SettingsCodec.KEY_DEMO_CONTENT])
        assertEquals("false", encoded[SettingsCodec.KEY_SHOW_NON_TV_APPS])
        assertEquals("", encoded[SettingsCodec.KEY_APP_ORDER])
    }

    @Test
    fun `automatic dock is an absent key and an emptied manual dock is an empty value`() {
        val automatic = SettingsCodec.encode(LauncherSettings(dockApps = null))
        assertFalse(SettingsCodec.KEY_DOCK_APPS in automatic)
        assertNull(SettingsCodec.decode(automatic).dockApps)

        val emptied = SettingsCodec.encode(LauncherSettings(dockApps = emptyList()))
        assertEquals("", emptied[SettingsCodec.KEY_DOCK_APPS])
        assertEquals(emptyList<AppKey>(), SettingsCodec.decode(emptied).dockApps)
    }

    @Test
    fun `a null stored value counts as absent`() {
        val decoded = SettingsCodec.decode(mapOf(SettingsCodec.KEY_DOCK_APPS to null, SettingsCodec.KEY_THEME to null))
        assertNull(decoded.dockApps)
        assertEquals(ThemeMode.Black, decoded.theme)
    }

    @Test
    fun `dock size is clamped to the supported range`() {
        fun decoded(value: String) = SettingsCodec.decode(mapOf(SettingsCodec.KEY_DOCK_SIZE to value)).dockSize
        assertEquals(4, decoded("0"))
        assertEquals(4, decoded("-12"))
        assertEquals(4, decoded("3"))
        assertEquals(4, decoded("4"))
        assertEquals(6, decoded(" 6 "))
        assertEquals(7, decoded("7"))
        assertEquals(7, decoded("8"))
        assertEquals(7, decoded("2147483647"))
        assertEquals(LauncherSettings.DEFAULT_DOCK_SIZE, decoded("six"))
        assertEquals(LauncherSettings.DEFAULT_DOCK_SIZE, decoded(""))
        assertEquals(LauncherSettings.DEFAULT_DOCK_SIZE, decoded("99999999999999999999"))
    }

    @Test
    fun `a broken field falls back alone and leaves the others intact`() {
        val stored = SettingsCodec.encode(
            LauncherSettings(
                dockSize = 6,
                theme = ThemeMode.Graphite,
                accent = AccentColor.Sky,
                animation = AnimationLevel.Reduced,
                wallpaper = Wallpapers.TIDE,
                showNonTvApps = true,
                demoContent = true,
            ),
        ).toMutableMap()
        stored[SettingsCodec.KEY_ACCENT] = "Magenta"
        stored[SettingsCodec.KEY_SHOW_NON_TV_APPS] = "yes"

        val decoded = SettingsCodec.decode(stored)

        assertEquals(AccentColor.Emerald, decoded.accent)
        assertFalse(decoded.showNonTvApps)
        assertEquals(6, decoded.dockSize)
        assertEquals(ThemeMode.Graphite, decoded.theme)
        assertEquals(AnimationLevel.Reduced, decoded.animation)
        assertEquals(Wallpapers.TIDE, decoded.wallpaper)
        assertTrue(decoded.demoContent)
    }

    @Test
    fun `unknown enum names and wallpaper ids become defaults`() {
        val decoded = SettingsCodec.decode(
            mapOf(
                SettingsCodec.KEY_THEME to "graphite", // enum names are case-sensitive
                SettingsCodec.KEY_ANIMATION to "Turbo",
                SettingsCodec.KEY_WALLPAPER to "wallpaper-from-the-future",
                SettingsCodec.KEY_DEMO_CONTENT to "1",
            ),
        )
        assertEquals(LauncherSettings(), decoded)
    }

    @Test
    fun `every wallpaper choice is accepted`() {
        for (choice in Wallpapers.choices) {
            assertEquals(choice.id, SettingsCodec.decode(mapOf(SettingsCodec.KEY_WALLPAPER to choice.id)).wallpaper)
        }
    }

    @Test
    fun `unknown keys are ignored`() {
        assertEquals(LauncherSettings(), SettingsCodec.decode(mapOf("recents" to "x", "futureSetting" to "42")))
    }

    @Test
    fun `app lists drop unparsable lines and duplicates but keep their order`() {
        val text = listOf(okko.flatten(), "", "no-slash", "/missing.package", "missing.activity/", youtube.flatten(), okko.flatten(), "  ${kion.flatten()}\r")
            .joinToString("\n")
        val decoded = SettingsCodec.decode(
            mapOf(
                SettingsCodec.KEY_DOCK_APPS to text,
                SettingsCodec.KEY_APP_ORDER to text,
                SettingsCodec.KEY_HIDDEN_APPS to text,
            ),
        )
        assertEquals(listOf(okko, youtube, kion), decoded.dockApps)
        assertEquals(listOf(okko, youtube, kion), decoded.appOrder)
        assertEquals(setOf(okko, youtube, kion), decoded.hiddenApps)
    }

    @Test
    fun `a dock value without a single readable key means the automatic dock`() {
        assertNull(SettingsCodec.decode(mapOf(SettingsCodec.KEY_DOCK_APPS to "garbage\n\u0000\u0001")).dockApps)
        assertNull(SettingsCodec.decode(mapOf(SettingsCodec.KEY_DOCK_APPS to "\n")).dockApps)
    }

    @Test
    fun `keys that could not be read back are not written`() {
        val broken = AppKey("com.example\nevil/line", "Main")
        val padded = AppKey(" com.example", "Main ")
        val encoded = SettingsCodec.encode(LauncherSettings(dockApps = listOf(broken, okko, padded), hiddenApps = setOf(broken)))
        assertEquals(okko.flatten(), encoded[SettingsCodec.KEY_DOCK_APPS])
        assertEquals("", encoded[SettingsCodec.KEY_HIDDEN_APPS])
    }

    @Test
    fun `random settings survive a round trip`() {
        for (seed in 0 until 500) {
            val settings = randomSettings(Random(seed))
            val encoded = SettingsCodec.encode(settings)
            assertEquals("seed $seed", settings, SettingsCodec.decode(encoded))
            assertEquals("seed $seed: encoding must be stable", encoded, SettingsCodec.encode(SettingsCodec.decode(encoded)))
        }
    }

    @Test
    fun `random garbage never throws and always yields usable settings`() {
        val keys = SettingsCodec.encode(LauncherSettings(dockApps = emptyList())).keys.toList()
        for (seed in 0 until 500) {
            val random = Random(seed)
            val stored = HashMap<String, String?>()
            for (key in keys) {
                when (random.nextInt(4)) {
                    0 -> Unit
                    1 -> stored[key] = null
                    else -> stored[key] = randomGarbage(random)
                }
            }
            val decoded = SettingsCodec.decode(stored)
            assertTrue("seed $seed", decoded.dockSize in LauncherSettings.MIN_DOCK_SIZE..LauncherSettings.MAX_DOCK_SIZE)
            assertTrue("seed $seed", Wallpapers.choices.any { it.id == decoded.wallpaper })
            assertEquals("seed $seed", decoded.appOrder.distinct(), decoded.appOrder)
            assertEquals("seed $seed", decoded.dockApps?.distinct(), decoded.dockApps)
            // Whatever was salvaged is itself a fixed point of the codec.
            assertEquals("seed $seed", decoded, SettingsCodec.decode(SettingsCodec.encode(decoded)))
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Recents
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `recents survive a round trip in list order`() {
        val recents = listOf(
            // Deliberately not sorted by time: a TV without a clock battery records launches "in 2015" until NTP syncs.
            RecentLaunch(okko, lastUsedAt = 1_420_070_400_000, launchCount = 3),
            RecentLaunch(youtube, lastUsedAt = 1_794_000_000_000, launchCount = 41),
            RecentLaunch(kion, lastUsedAt = 0, launchCount = 1),
        )
        assertEquals(recents, SettingsCodec.decodeRecents(SettingsCodec.encodeRecents(recents)))
    }

    @Test
    fun `recents text format is one line per launch`() {
        val text = SettingsCodec.encodeRecents(listOf(RecentLaunch(okko, 1_794_000_000_000, 2), RecentLaunch(youtube, 5, 1)))
        assertEquals("1794000000000 2 ${okko.flatten()}\n5 1 ${youtube.flatten()}", text)
    }

    @Test
    fun `missing or empty recents decode to an empty list`() {
        assertEquals(emptyList<RecentLaunch>(), SettingsCodec.decodeRecents(null))
        assertEquals(emptyList<RecentLaunch>(), SettingsCodec.decodeRecents(""))
        assertEquals(emptyList<RecentLaunch>(), SettingsCodec.decodeRecents("\n\n"))
        assertEquals("", SettingsCodec.encodeRecents(emptyList()))
    }

    @Test
    fun `damaged recent lines are skipped`() {
        val text = listOf(
            "100 2 ${okko.flatten()}",
            "not-a-number 2 ${youtube.flatten()}",
            "100 many ${youtube.flatten()}",
            "100 2",
            "100 2 no-slash",
            "",
            "   200 0 ${youtube.flatten()}  ",
            "300 -5 ${kion.flatten()}",
        ).joinToString("\n")

        assertEquals(
            listOf(RecentLaunch(okko, 100, 2), RecentLaunch(youtube, 200, 1), RecentLaunch(kion, 300, 1)),
            SettingsCodec.decodeRecents(text),
        )
    }

    @Test
    fun `a repeated app keeps its first, most recent, entry`() {
        val text = "300 7 ${okko.flatten()}\n200 1 ${youtube.flatten()}\n100 3 ${okko.flatten()}"
        assertEquals(listOf(RecentLaunch(okko, 300, 7), RecentLaunch(youtube, 200, 1)), SettingsCodec.decodeRecents(text))
    }

    @Test
    fun `recents are capped when decoding`() {
        val many = (0 until 100).map { RecentLaunch(AppKey("pkg$it", "Main"), 1_000L - it, 1) }
        val decoded = SettingsCodec.decodeRecents(SettingsCodec.encodeRecents(many))
        assertEquals(SettingsCodec.MAX_RECENTS, decoded.size)
        assertEquals(many.take(SettingsCodec.MAX_RECENTS), decoded)
    }

    @Test
    fun `random recents survive a round trip`() {
        for (seed in 0 until 300) {
            val random = Random(seed)
            val recents = randomKeys(random, random.nextInt(0, SettingsCodec.MAX_RECENTS + 1)).map { key ->
                RecentLaunch(key, random.nextLong(-1_000_000L, 4_102_444_800_000L), random.nextInt(1, Int.MAX_VALUE))
            }
            assertEquals("seed $seed", recents, SettingsCodec.decodeRecents(SettingsCodec.encodeRecents(recents)))
        }
    }

    @Test
    fun `random garbage recents never throw`() {
        for (seed in 0 until 300) {
            val decoded = SettingsCodec.decodeRecents(randomGarbage(Random(seed)))
            assertTrue("seed $seed", decoded.size <= SettingsCodec.MAX_RECENTS)
            assertEquals("seed $seed", decoded, SettingsCodec.decodeRecents(SettingsCodec.encodeRecents(decoded)))
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Generators
    // -----------------------------------------------------------------------------------------------------------------

    private fun randomSettings(random: Random): LauncherSettings = LauncherSettings(
        dockSize = random.nextInt(LauncherSettings.MIN_DOCK_SIZE, LauncherSettings.MAX_DOCK_SIZE + 1),
        dockApps = if (random.nextInt(3) == 0) null else randomKeys(random, random.nextInt(0, 8)),
        appOrder = randomKeys(random, random.nextInt(0, 40)),
        hiddenApps = randomKeys(random, random.nextInt(0, 10)).toSet(),
        showNonTvApps = random.nextBoolean(),
        theme = ThemeMode.entries.random(random),
        wallpaper = Wallpapers.choices.random(random).id,
        accent = AccentColor.entries.random(random),
        animation = AnimationLevel.entries.random(random),
        demoContent = random.nextBoolean(),
    )

    /** Distinct keys shaped like real component names, including the odd-but-legal characters. */
    private fun randomKeys(random: Random, count: Int): List<AppKey> {
        val keys = LinkedHashSet<AppKey>()
        while (keys.size < count) keys += AppKey(randomName(random, PACKAGE_ALPHABET), randomName(random, CLASS_ALPHABET))
        return keys.toList()
    }

    private fun randomName(random: Random, alphabet: String): String =
        buildString { repeat(random.nextInt(1, 40)) { append(alphabet[random.nextInt(alphabet.length)]) } }

    private fun randomGarbage(random: Random): String = buildString {
        repeat(random.nextInt(0, 60)) {
            when (random.nextInt(6)) {
                0 -> append('\n')
                1 -> append('/')
                2 -> append(' ')
                3 -> append(random.nextInt(-5, 12))
                4 -> append(GARBAGE_WORDS.random(random))
                else -> append(random.nextInt(0, 0x3000).toChar())
            }
        }
    }

    private companion object {
        const val PACKAGE_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._"
        const val CLASS_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._$/"
        val GARBAGE_WORDS = listOf("true", "false", "Black", "Graphite", "Emerald", "dynamic", "aurora", "null", "com.app/Main", "Auto")
    }
}
