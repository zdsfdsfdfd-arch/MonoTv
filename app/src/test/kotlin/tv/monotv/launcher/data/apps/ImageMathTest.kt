package tv.monotv.launcher.data.apps

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

class ImageMathTest {

    private val black = 0xFF000000.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val grey = 0xFF808080.toInt()

    private fun hueOf(argb: Int) = ImageMath.toHsl(argb)[0]

    /** Distance between two hues on the colour wheel, in degrees. */
    private fun hueDistance(a: Float, b: Float): Float = abs(a - b).let { min(it, 360f - it) }

    private fun assertHue(expected: Float, argb: Int, tolerance: Float = 8f) {
        val distance = hueDistance(expected, hueOf(argb))
        assertTrue("hue ${hueOf(argb)} is not within $tolerance of $expected", distance <= tolerance)
    }

    private fun assertUiFriendly(argb: Int) {
        val (_, saturation, lightness) = ImageMath.toHsl(argb)
        assertEquals("accent must be opaque", 0xFF, argb ushr 24)
        assertTrue("saturation $saturation", saturation in 0.34f..0.86f)
        assertTrue("lightness $lightness", lightness in 0.44f..0.66f)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // boxBlur
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `blurring a flat image changes nothing`() {
        val pixels = IntArray(8 * 5) { 0xFF336699.toInt() }
        assertArrayEquals(pixels, ImageMath.boxBlur(pixels, 8, 5, radius = 3, passes = 3))
    }

    @Test
    fun `blur returns a new array and leaves the input alone`() {
        val pixels = IntArray(6 * 6) { if (it % 2 == 0) black else white }
        val before = pixels.copyOf()
        val blurred = ImageMath.boxBlur(pixels, 6, 6, radius = 1, passes = 1)
        assertNotSame(pixels, blurred)
        assertArrayEquals(before, pixels)
        assertNotEquals(pixels.toList(), blurred.toList())
    }

    @Test
    fun `degenerate blur arguments return a plain copy`() {
        val pixels = IntArray(4 * 4) { it * 0x01010101 }
        for (blurred in listOf(
            ImageMath.boxBlur(pixels, 4, 4, radius = 0, passes = 3),
            ImageMath.boxBlur(pixels, 4, 4, radius = 2, passes = 0),
            ImageMath.boxBlur(pixels, 0, 4, radius = 2, passes = 1),
            ImageMath.boxBlur(pixels, 4, -1, radius = 2, passes = 1),
            ImageMath.boxBlur(pixels, 40, 40, radius = 2, passes = 1),
        )) {
            assertNotSame(pixels, blurred)
            assertArrayEquals(pixels, blurred)
        }
    }

    @Test
    fun `a single bright pixel spreads evenly over the box`() {
        val size = 9
        val pixels = IntArray(size * size) { black }
        pixels[4 * size + 4] = white
        val blurred = ImageMath.boxBlur(pixels, size, size, radius = 1, passes = 1)

        // 255 / 3 = 85 after the horizontal pass, 85 / 3 = 28.33 -> 28 after the vertical one.
        val expected = 0xFF1C1C1C.toInt()
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inBox = x in 3..5 && y in 3..5
                assertEquals("pixel $x,$y", if (inBox) expected else black, blurred[y * size + x])
            }
        }
    }

    @Test
    fun `more passes spread further and keep the image symmetric`() {
        val size = 15
        val pixels = IntArray(size * size) { black }
        pixels[7 * size + 7] = white
        val blurred = ImageMath.boxBlur(pixels, size, size, radius = 2, passes = 3)

        fun at(x: Int, y: Int) = blurred[y * size + x] and 0xFF
        assertTrue(at(7, 7) > at(9, 7))
        assertTrue(at(9, 7) > at(12, 7))
        assertTrue("one pass reaches 2 pixels, three passes must reach further", at(12, 7) > 0)
        for (d in 1..7) {
            assertEquals(at(7 - d, 7), at(7 + d, 7))
            assertEquals(at(7, 7 - d), at(7, 7 + d))
        }
        assertTrue(blurred.all { it ushr 24 == 0xFF })
    }

    @Test
    fun `a hard edge becomes a monotonic ramp and edges are clamped`() {
        val width = 16
        val height = 4
        val pixels = IntArray(width * height) { if (it % width < 8) black else white }
        val blurred = ImageMath.boxBlur(pixels, width, height, radius = 3, passes = 2)

        for (y in 0 until height) {
            for (x in 1 until width) {
                assertTrue((blurred[y * width + x] and 0xFF) >= (blurred[y * width + x - 1] and 0xFF))
            }
            assertArrayEquals(blurred.copyOfRange(0, width), blurred.copyOfRange(y * width, (y + 1) * width))
        }
        // Clamped edges: the outermost columns stay pure instead of fading towards an imaginary border colour.
        assertEquals(black, blurred[0])
        assertEquals(white, blurred[width - 1])
    }

    @Test
    fun `an absurd radius is bounded and flattens the image`() {
        val pixels = IntArray(8 * 8) { if (it % 8 < 4) black else white }
        val blurred = ImageMath.boxBlur(pixels, 8, 8, radius = Int.MAX_VALUE, passes = 3)
        val values = blurred.map { it and 0xFF }
        assertTrue("spread ${values.max() - values.min()}", values.max() - values.min() < 60)
    }

    @Test
    fun `channels are blurred independently`() {
        val pixels = intArrayOf(red, blue, red, blue)
        val blurred = ImageMath.boxBlur(pixels, 4, 1, radius = 1, passes = 1)
        // Index 1 averages red, blue, red.
        assertEquals(0xFFAA0055.toInt(), blurred[1])
    }

    // -----------------------------------------------------------------------------------------------------------------
    // dominantColor
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a single colour keeps its hue and is made ui-friendly`() {
        val accent = ImageMath.dominantColor(IntArray(100) { red })
        assertHue(0f, accent)
        assertUiFriendly(accent)
        // Pure red is too saturated for a glow: it is pulled down to the allowed maximum.
        assertEquals(0.85f, ImageMath.toHsl(accent)[1], 0.02f)
    }

    @Test
    fun `no usable pixel means the neutral accent`() {
        assertEquals(ImageMath.NEUTRAL_ACCENT, ImageMath.dominantColor(IntArray(0)))
        assertEquals(ImageMath.NEUTRAL_ACCENT, ImageMath.dominantColor(IntArray(50) { black }))
        assertEquals(ImageMath.NEUTRAL_ACCENT, ImageMath.dominantColor(IntArray(50) { white }))
        assertEquals(ImageMath.NEUTRAL_ACCENT, ImageMath.dominantColor(IntArray(50) { grey }))
        assertEquals(ImageMath.NEUTRAL_ACCENT, ImageMath.dominantColor(IntArray(50) { 0x00FF0000 }))
        assertEquals(0xFF5B6B7A.toInt(), ImageMath.NEUTRAL_ACCENT)
    }

    @Test
    fun `a small saturated logo beats a large neutral background`() {
        val pixels = IntArray(1000) { index ->
            when {
                index < 450 -> black
                index < 900 -> white
                index < 950 -> grey
                else -> blue
            }
        }
        assertHue(240f, ImageMath.dominantColor(pixels))
    }

    @Test
    fun `transparent pixels do not vote`() {
        val pixels = IntArray(100) { if (it < 90) 0x10FF0000 else green }
        assertHue(120f, ImageMath.dominantColor(pixels))
    }

    @Test
    fun `votes are weighted by saturation`() {
        val dullRed = ImageMath.hslToArgb(0f, 0.2f, 0.5f)
        val vividBlue = ImageMath.hslToArgb(240f, 1f, 0.5f)
        val pixels = IntArray(100) { if (it < 70) dullRed else vividBlue }
        assertHue(240f, ImageMath.dominantColor(pixels))
    }

    @Test
    fun `hues are averaged across the red wrap-around`() {
        val pixels = IntArray(100) { if (it % 2 == 0) ImageMath.hslToArgb(352f, 0.9f, 0.5f) else ImageMath.hslToArgb(8f, 0.9f, 0.5f) }
        assertHue(0f, ImageMath.dominantColor(pixels), tolerance = 4f)
    }

    @Test
    fun `dark, pale and washed-out colours are normalised`() {
        val navy = ImageMath.dominantColor(IntArray(10) { ImageMath.hslToArgb(230f, 0.9f, 0.14f) })
        assertHue(230f, navy)
        assertEquals(0.45f, ImageMath.toHsl(navy)[2], 0.02f)

        val pastel = ImageMath.dominantColor(IntArray(10) { ImageMath.hslToArgb(140f, 0.9f, 0.88f) })
        assertHue(140f, pastel)
        assertEquals(0.65f, ImageMath.toHsl(pastel)[2], 0.02f)

        val washedOut = ImageMath.dominantColor(IntArray(10) { ImageMath.hslToArgb(30f, 0.2f, 0.5f) })
        assertHue(30f, washedOut)
        assertEquals(0.35f, ImageMath.toHsl(washedOut)[1], 0.02f)

        listOf(navy, pastel, washedOut).forEach(::assertUiFriendly)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // HSL helpers
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `known colours convert to the expected hsl`() {
        assertArrayEquals(floatArrayOf(0f, 1f, 0.5f), ImageMath.toHsl(red), 0.001f)
        assertArrayEquals(floatArrayOf(120f, 1f, 0.5f), ImageMath.toHsl(green), 0.001f)
        assertArrayEquals(floatArrayOf(240f, 1f, 0.5f), ImageMath.toHsl(blue), 0.001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 0f), ImageMath.toHsl(black), 0.001f)
        assertArrayEquals(floatArrayOf(0f, 0f, 1f), ImageMath.toHsl(white), 0.001f)
        assertArrayEquals(floatArrayOf(300f, 1f, 0.5f), ImageMath.toHsl(0xFFFF00FF.toInt()), 0.001f)
        assertEquals(0f, ImageMath.toHsl(grey)[1], 0.001f)
    }

    @Test
    fun `hsl converts back to the expected colours`() {
        assertEquals(red, ImageMath.hslToArgb(0f, 1f, 0.5f))
        assertEquals(green, ImageMath.hslToArgb(120f, 1f, 0.5f))
        assertEquals(blue, ImageMath.hslToArgb(240f, 1f, 0.5f))
        assertEquals(white, ImageMath.hslToArgb(77f, 0.4f, 1f))
        assertEquals(black, ImageMath.hslToArgb(77f, 0.4f, 0f))
        assertEquals(grey, ImageMath.hslToArgb(0f, 0f, 128 / 255f))
    }

    @Test
    fun `hue wraps and the other components are clamped`() {
        assertEquals(blue, ImageMath.hslToArgb(-120f, 1f, 0.5f))
        assertEquals(green, ImageMath.hslToArgb(480f, 1f, 0.5f))
        assertEquals(red, ImageMath.hslToArgb(360f, 7f, 0.5f))
        assertEquals(white, ImageMath.hslToArgb(10f, 1f, 3f))
        assertEquals(0x80FF0000.toInt(), ImageMath.hslToArgb(0f, 1f, 0.5f, alpha = 0x80))
        assertEquals(red, ImageMath.hslToArgb(0f, 1f, 0.5f, alpha = 999))
    }

    @Test
    fun `argb survives a round trip through hsl`() {
        val samples = intArrayOf(
            0xFF123456.toInt(), 0xFFFEDCBA.toInt(), 0xFF0A0B0C.toInt(), 0xFFF0E0D0.toInt(), 0xFF7F007F.toInt(),
            0xFF00FFFF.toInt(), 0xFFFFFF00.toInt(), 0xFF21D07A.toInt(), 0xFF3DA5FF.toInt(), 0xFFFF6B5E.toInt(),
        )
        for (argb in samples) {
            val (hue, saturation, lightness) = ImageMath.toHsl(argb)
            val back = ImageMath.hslToArgb(hue, saturation, lightness)
            for (shift in intArrayOf(16, 8, 0)) {
                val delta = abs(((argb shr shift) and 0xFF) - ((back shr shift) and 0xFF))
                assertTrue("${argb.toUInt().toString(16)} -> ${back.toUInt().toString(16)}", delta <= 1)
            }
        }
    }

    @Test
    fun `withLightness keeps hue, saturation and alpha`() {
        val source = 0xC03DA5FF.toInt()
        val dark = ImageMath.withLightness(source, 0.12f)
        assertEquals(0xC0, dark ushr 24)
        assertEquals(ImageMath.toHsl(source)[0], ImageMath.toHsl(dark)[0], 4f)
        assertEquals(0.12f, ImageMath.toHsl(dark)[2], 0.01f)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Sizing
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `cover scale shrinks to the requested box and never enlarges`() {
        assertEquals(0.5f, ImageMath.coverScale(3840, 2160, 1920, 1080), 0.0001f)
        assertEquals(1f, ImageMath.coverScale(640, 360, 1920, 1080), 0f)
        assertEquals(1f, ImageMath.coverScale(1920, 1080, 1920, 1080), 0f)
        // A portrait poster in a landscape slot must still cover the width.
        assertEquals(0.5f, ImageMath.coverScale(1000, 1500, 500, 400), 0.0001f)
    }

    @Test
    fun `cover scale caps images with an extreme aspect ratio`() {
        val scale = ImageMath.coverScale(20_000, 1_080, 1920, 1080)
        val pixels = 20_000.0 * 1_080 * scale * scale
        assertTrue(scale < 1f)
        assertEquals(4.0 * 1920 * 1080, pixels, 4.0 * 1920 * 1080 * 0.01)
    }

    @Test
    fun `cover scale of nonsense is one`() {
        assertEquals(1f, ImageMath.coverScale(0, 100, 10, 10), 0f)
        assertEquals(1f, ImageMath.coverScale(100, -5, 10, 10), 0f)
        assertEquals(1f, ImageMath.coverScale(100, 100, 0, 10), 0f)
        assertEquals(1f, ImageMath.coverScale(100, 100, 10, 0), 0f)
    }

    @Test
    fun `sample size is the largest power of two that keeps enough pixels`() {
        assertEquals(1, ImageMath.sampleSize(1f))
        assertEquals(1, ImageMath.sampleSize(0.75f))
        assertEquals(2, ImageMath.sampleSize(0.5f))
        assertEquals(2, ImageMath.sampleSize(0.3f))
        assertEquals(4, ImageMath.sampleSize(0.25f))
        assertEquals(8, ImageMath.sampleSize(0.1f))
        assertEquals(1, ImageMath.sampleSize(0f))
        assertEquals(1, ImageMath.sampleSize(-2f))
        assertEquals(1, ImageMath.sampleSize(Float.NaN))
        assertEquals(1, ImageMath.sampleSize(5f))
        assertEquals(4096, ImageMath.sampleSize(1e-9f))
    }

    @Test
    fun `banner width is the widest 16 to 9 box inside the request`() {
        assertEquals(320, ImageMath.bannerWidth(320, 180))
        assertEquals(300, ImageMath.bannerWidth(300, 200))
        assertEquals(160, ImageMath.bannerWidth(400, 90))
        assertEquals(1, ImageMath.bannerWidth(0, 0))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Procedural artwork
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `fallback gradient is opaque, dark and gets darker along the diagonal`() {
        val width = 64
        val height = 36
        val pixels = ImageMath.fallbackGradient(width, height, seed = "aurora".hashCode())
        assertEquals(width * height, pixels.size)
        assertTrue(pixels.all { it ushr 24 == 0xFF })

        fun brightest(argb: Int) = maxOf((argb shr 16) and 0xFF, (argb shr 8) and 0xFF, argb and 0xFF)
        assertTrue("wallpaper text must stay readable on it", pixels.all { brightest(it) < 100 })
        assertTrue(brightest(pixels.first()) > brightest(pixels.last()) + 20)
        assertTrue(brightest(pixels[(height / 2) * width + width / 2]) in brightest(pixels.last())..brightest(pixels.first()))
    }

    @Test
    fun `fallback gradient is deterministic and its hue depends on the seed`() {
        val first = ImageMath.fallbackGradient(32, 18, seed = 12345)
        assertArrayEquals(first, ImageMath.fallbackGradient(32, 18, seed = 12345))
        // 12345 % 360 = 105, 12345 + 180 -> the opposite side of the colour wheel.
        val other = ImageMath.fallbackGradient(32, 18, seed = 12345 + 180)
        assertHue(105f, first[0], tolerance = 6f)
        assertHue(285f, other[0], tolerance = 6f)
    }

    @Test
    fun `fallback gradient handles negative seeds and empty sizes`() {
        assertEquals(0, ImageMath.fallbackGradient(0, 10, seed = 1).size)
        assertEquals(0, ImageMath.fallbackGradient(10, -1, seed = 1).size)
        val pixels = ImageMath.fallbackGradient(1, 1, seed = Int.MIN_VALUE)
        assertEquals(1, pixels.size)
        assertEquals(0xFF, pixels[0] ushr 24)
    }
}
