package tv.monotv.launcher.data.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The layout of the report is plain text processing and is pinned down without Android. */
class DiagnosticsReportRenderTest {

    @Test
    fun `sections are titled and separated by a blank line`() {
        val text = DiagnosticsReport.render(
            listOf(
                "Build" to listOf("manufacturer: SberDevices", "model: SBDV-00006"),
                "Display" to listOf("size: 1920x1080 px"),
            ),
        )
        assertEquals(
            """
            == Build ==
            manufacturer: SberDevices
            model: SBDV-00006

            == Display ==
            size: 1920x1080 px

            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `an empty section says so instead of looking truncated`() {
        assertEquals("== TV inputs ==\n(нет данных)\n", DiagnosticsReport.render(listOf("TV inputs" to emptyList())))
    }

    @Test
    fun `no sections render as an empty text`() {
        assertEquals("", DiagnosticsReport.render(emptyList()))
    }

    @Test
    fun `one entry is always one line`() {
        val text = DiagnosticsReport.render(listOf("Apps\r\nInjected" to listOf("Line one\nline two\ttabbed\u0000", "second")))
        assertEquals("== Apps  Injected ==\nLine one line two tabbed \nsecond\n", text)
        assertEquals(3, text.trimEnd('\n').lines().size)
    }

    @Test
    fun `text is kept verbatim otherwise`() {
        val line = "Кинопоиск | ru.kinopoisk.tv/.MainActivity | user | banner=yes   "
        val text = DiagnosticsReport.render(listOf("Launchable activities: android.intent.category.LEANBACK_LAUNCHER" to listOf(line)))
        assertTrue(text.contains("$line\n"))
        assertTrue(text.startsWith("== Launchable activities: android.intent.category.LEANBACK_LAUNCHER ==\n"))
    }

    @Test
    fun `rendering is deterministic and keeps the given order`() {
        val sections = (1..20).map { index -> "Section $index" to (1..index).map { "entry $it" } }
        val text = DiagnosticsReport.render(sections)
        assertEquals(text, DiagnosticsReport.render(sections))
        assertEquals((1..20).map { "== Section $it ==" }, text.lines().filter { it.startsWith("== ") })
        assertEquals(20 + (1..20).sum() + 19, text.trimEnd('\n').lines().size)
    }
}
