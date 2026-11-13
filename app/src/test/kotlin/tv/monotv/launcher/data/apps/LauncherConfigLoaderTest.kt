package tv.monotv.launcher.data.apps

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Loading from the real APK assets and from the override file in the external files dir. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LauncherConfigLoaderTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val loader = LauncherConfigLoader(context)
    private val overrideFile = File(checkNotNull(context.getExternalFilesDir(null)), LauncherConfigLoader.FILE_NAME)

    @After
    fun removeOverride() {
        overrideFile.deleteRecursively()
    }

    @Test
    fun `without an override the bundled config is used`() {
        val config = loader.load()
        assertNull(loader.lastError)
        assertEquals(listOf("youtube", "netflix", "hbomax", "appletv", "spotify"), config.dockPriority.take(5))
        assertEquals("youtube", config.knownIdFor("com.google.android.youtube.tv"))
        assertEquals(listOf("ru.sberdevices.category.STAR_LAUNCHER"), config.extraLauncherCategories)
        assertTrue(config.isBlocked("com.cvte.fac.menu"))
        assertFalse(config.isBlocked("com.spocky.projengmenu"))
    }

    @Test
    fun `a valid override replaces the bundled config as a whole`() {
        overrideFile.writeText(
            """
            {
              "dockPriority": ["plex"],
              "knownApps": [ { "id": "plex", "title": "Plex", "packages": ["com.plexapp.android"] } ],
              "blockedPackages": ["com.example.annoying"]
            }
            """.trimIndent(),
        )
        val config = loader.load()
        assertNull(loader.lastError)
        assertEquals(listOf("plex"), config.dockPriority)
        assertEquals(listOf("plex"), config.knownApps.map { it.id })
        assertNull("nothing is merged in from the bundled file", config.knownIdFor("com.google.android.youtube.tv"))
        assertTrue(config.extraLauncherCategories.isEmpty())
        assertTrue(config.isBlocked("com.example.annoying"))
    }

    @Test
    fun `an override cannot unblock factory menus`() {
        overrideFile.writeText("""{ "blockedPackages": [], "blockedKeywords": [] }""")
        val config = loader.load()
        assertNull(loader.lastError)
        assertTrue(config.isBlocked("com.cvte.fac.menu"))
        assertTrue(config.isBlocked("com.stb.factorymenu"))
        assertTrue(config.isBlocked("com.unknownvendor.engineermode"))
        assertFalse(config.isBlocked("com.spocky.projengmenu"))
    }

    @Test
    fun `a broken override is ignored and reported`() {
        overrideFile.writeText("""{ "dockPriority": ["plex" """)
        val config = loader.load()
        assertEquals("youtube", config.dockPriority.first())
        val error = checkNotNull(loader.lastError)
        assertTrue(error, error.contains(overrideFile.path))
        assertTrue(error, error.startsWith("Не удалось прочитать"))

        // Fixing the file clears the error on the next load.
        overrideFile.writeText("""{ "dockPriority": ["plex"] }""")
        assertEquals(listOf("plex"), loader.load().dockPriority)
        assertNull(loader.lastError)
    }

    @Test
    fun `an override that is not a json object is ignored and reported`() {
        overrideFile.writeText("""["youtube", "netflix"]""")
        assertEquals("youtube", loader.load().dockPriority.first())
        assertNotNull(loader.lastError)
    }

    @Test
    fun `an oversized override is ignored and reported`() {
        overrideFile.writeText("""{ "_note": "${"x".repeat(600 * 1024)}", "dockPriority": ["plex"] }""")
        assertEquals("youtube", loader.load().dockPriority.first())
        val error = checkNotNull(loader.lastError)
        assertTrue(error, error.contains("КБ"))
    }

    @Test
    fun `a directory with the config name is not an override`() {
        assertTrue(overrideFile.mkdirs())
        assertEquals("youtube", loader.load().dockPriority.first())
        assertNull(loader.lastError)
    }

    @Test
    fun `an override with a byte-order mark is accepted`() {
        overrideFile.writeText(0xFEFF.toChar() + """{ "dockPriority": ["kodi"] }""")
        assertEquals(listOf("kodi"), loader.load().dockPriority)
        assertNull(loader.lastError)
    }
}
