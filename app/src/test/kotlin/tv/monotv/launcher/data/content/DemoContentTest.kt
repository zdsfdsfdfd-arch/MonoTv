package tv.monotv.launcher.data.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.HeroKind
import tv.monotv.launcher.core.Wallpapers

class DemoContentTest {
    private val builtInIds = Wallpapers.builtIn.map { it.id }.toSet()

    @Test
    fun `four demo spotlights on four different backdrops`() {
        val items = DemoContent.heroItems
        assertEquals(4, items.size)
        assertEquals(
            setOf(Wallpapers.AURORA, Wallpapers.HORIZON, Wallpapers.NEBULA, Wallpapers.RIDGE),
            items.map { (it.art as ArtSource.Wallpaper).id }.toSet(),
        )
        assertEquals(
            listOf("Сериал · 2 сезон", "Документальный фильм", "Фильм · Фантастика", "Концерт"),
            items.map { it.eyebrow },
        )
    }

    @Test
    fun `demo spotlights are marked as demo and can not start anything`() {
        for (item in DemoContent.heroItems) {
            assertTrue(item.id, item.id.startsWith(DemoContent.ID_PREFIX))
            assertEquals(HeroKind.Demo, item.kind)
            assertEquals("Смотреть", item.actionLabel)
            assertNull(item.appKey)
            assertNull(item.intentUri)
            assertNull(item.progress)
            assertTrue(item.title.isNotBlank())
        }
    }

    @Test
    fun `demo descriptions fit the hero text block`() {
        for (item in DemoContent.heroItems) {
            val description = item.description.orEmpty()
            assertTrue("${item.id} has no description", description.isNotBlank())
            assertTrue("${item.id}: ${description.length} characters", description.length <= 140)
        }
    }

    @Test
    fun `five demo continue cards with plausible progress on built-in art`() {
        val items = DemoContent.continueItems
        assertEquals(5, items.size)
        assertEquals(listOf(0.82f, 0.35f, 0.6f, 0.12f, 0.47f), items.map { it.progress })
        for (item in items) {
            assertTrue(item.id, item.id.startsWith(DemoContent.ID_PREFIX))
            assertTrue(item.title.isNotBlank())
            assertTrue(item.subtitle.orEmpty().isNotBlank())
            assertTrue((item.art as ArtSource.Wallpaper).id in builtInIds)
            assertNull(item.appKey)
            assertNull(item.intentUri)
        }
        assertTrue("uses the backdrops the hero does not", items.any { it.art == ArtSource.Wallpaper(Wallpapers.DUNES) })
        assertTrue(items.any { it.art == ArtSource.Wallpaper(Wallpapers.TIDE) })
    }

    @Test
    fun `demo ids are unique`() {
        val heroIds = DemoContent.heroItems.map { it.id }
        val continueIds = DemoContent.continueItems.map { it.id }
        assertEquals(heroIds.size, heroIds.toSet().size)
        assertEquals(continueIds.size, continueIds.toSet().size)
        assertTrue("hero and continue ids never clash", heroIds.intersect(continueIds.toSet()).isEmpty())
    }
}
