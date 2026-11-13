package tv.monotv.launcher.data.apps

import android.app.ActivityManager
import android.app.Application
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.Artwork
import tv.monotv.launcher.core.Wallpapers
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import kotlin.math.abs
import kotlin.math.min

/** Real pixels through Robolectric's native graphics: decoding, cropping, colour, blur and the caches. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class AndroidArtworkLoaderTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val packages = shadowOf(app.packageManager)
    private val filesDir: File = checkNotNull(app.getExternalFilesDir(null))
    private var nextResourceId = 0x7F080000

    /** Unconfined: every load has finished by the time the suspending call returns. */
    private val loader = AndroidArtworkLoader(app, Dispatchers.Unconfined)

    @After
    fun tearDown() {
        filesDir.listFiles()?.forEach { it.deleteRecursively() }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Fixtures
    // -----------------------------------------------------------------------------------------------------------------

    private fun load(source: ArtSource, width: Int, height: Int): Artwork? = runBlocking { loader.load(source, width, height) }

    private fun loadBlurred(source: ArtSource): Artwork? = runBlocking { loader.loadBlurred(source) }

    private fun Artwork.bitmap(): Bitmap = image.asAndroidBitmap()

    private fun Artwork.pixel(x: Int, y: Int): Int = bitmap().getPixel(x, y)

    private fun Artwork.size(): Pair<Int, Int> = image.width to image.height

    private fun hueOf(argb: Int): Float = ImageMath.toHsl(argb)[0]

    private fun assertHue(expected: Float, argb: Int, tolerance: Float = 12f) {
        val distance = abs(expected - hueOf(argb)).let { min(it, 360f - it) }
        assertTrue("hue ${hueOf(argb)} is not within $tolerance of $expected", distance <= tolerance)
    }

    private fun assertColor(expected: Int, actual: Int, tolerance: Int = 6) {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val delta = abs(((expected ushr shift) and 0xFF) - ((actual ushr shift) and 0xFF))
            assertTrue("expected ${expected.toUInt().toString(16)} but was ${actual.toUInt().toString(16)}", delta <= tolerance)
        }
    }

    private fun bitmapOf(width: Int, height: Int, color: (x: Int, y: Int) -> Int): Bitmap {
        val pixels = IntArray(width * height) { color(it % width, it / width) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun bitmapDrawable(width: Int, height: Int, color: (x: Int, y: Int) -> Int): Drawable =
        BitmapDrawable(app.resources, bitmapOf(width, height, color))

    private fun register(packageName: String, drawable: Drawable?): Int {
        if (drawable == null) return 0
        return (nextResourceId++).also { packages.addDrawableResolution(packageName, it, drawable) }
    }

    private fun installApp(
        packageName: String,
        banner: Drawable? = null,
        icon: Drawable? = null,
        appBanner: Drawable? = null,
        appIcon: Drawable? = null,
    ): AppKey {
        val activityName = "$packageName.MainActivity"
        packages.installPackage(
            PackageInfo().apply {
                this.packageName = packageName
                applicationInfo = ApplicationInfo().apply {
                    this.packageName = packageName
                    this.banner = register(packageName, appBanner)
                    this.icon = register(packageName, appIcon)
                }
            },
        )
        packages.addOrUpdateActivity(
            ActivityInfo().apply {
                this.packageName = packageName
                name = activityName
                exported = true
                this.banner = register(packageName, banner)
                this.icon = register(packageName, icon)
            },
        )
        return AppKey(packageName, activityName)
    }

    private fun writeImage(file: File, bitmap: Bitmap, format: Bitmap.CompressFormat = Bitmap.CompressFormat.PNG): File {
        file.outputStream().use { assertTrue(bitmap.compress(format, 95, it)) }
        return file
    }

    /** A vector-like drawable (no pixels of its own) that counts how often it is rendered. */
    private open class SolidDrawable(private val color: Int, private val width: Int, private val height: Int) : Drawable() {
        var draws = 0
            private set
        private val paint = Paint().apply { color = this@SolidDrawable.color }

        override fun draw(canvas: Canvas) {
            draws++
            canvas.drawRect(bounds, paint)
        }

        override fun getIntrinsicWidth(): Int = width
        override fun getIntrinsicHeight(): Int = height
        override fun setAlpha(alpha: Int) = Unit
        override fun setColorFilter(colorFilter: ColorFilter?) = Unit

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    private class FailingDrawable(private val failure: Throwable) : SolidDrawable(Color.RED, 320, 180) {
        override fun draw(canvas: Canvas) {
            throw failure
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // App banners and icons
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a banner is centre-cropped into a 16 to 9 bitmap of the requested size`() {
        // A square source with green bands above and below: a correct centre crop shows none of the green.
        val key = installApp(
            "com.example.banner",
            banner = bitmapDrawable(400, 400) { _, y -> if (y < 80 || y >= 320) Color.GREEN else Color.RED },
        )
        val artwork = checkNotNull(load(ArtSource.AppBanner(key), 320, 180))

        assertTrue(artwork.isBanner)
        assertEquals(320 to 180, artwork.size())
        for ((x, y) in listOf(0 to 0, 319 to 0, 0 to 179, 319 to 179, 160 to 90)) {
            assertColor(Color.RED, artwork.pixel(x, y))
        }
        assertHue(0f, artwork.accent)
    }

    @Test
    fun `a wide banner is cropped at the sides`() {
        val key = installApp(
            "com.example.wide",
            banner = bitmapDrawable(640, 180) { x, _ -> if (x < 160 || x >= 480) Color.GREEN else Color.BLUE },
        )
        val artwork = checkNotNull(load(ArtSource.AppBanner(key), 320, 180))
        assertEquals(320 to 180, artwork.size())
        assertColor(Color.BLUE, artwork.pixel(0, 90))
        assertColor(Color.BLUE, artwork.pixel(319, 90))
        assertHue(240f, artwork.accent)
    }

    @Test
    fun `a request that is not 16 to 9 gets the widest 16 to 9 picture inside it`() {
        val key = installApp("com.example.banner", banner = SolidDrawable(Color.RED, 320, 180))
        assertEquals(300 to 168, checkNotNull(load(ArtSource.AppBanner(key), 300, 200)).size())
        assertEquals(160 to 90, checkNotNull(load(ArtSource.AppBanner(key), 400, 90)).size())
    }

    @Test
    fun `a bitmap banner is never enlarged beyond its own pixels`() {
        val key = installApp("com.example.small", banner = bitmapDrawable(160, 90) { _, _ -> Color.BLUE })
        val artwork = checkNotNull(load(ArtSource.AppBanner(key), 1920, 1080))
        assertEquals(160 to 90, artwork.size())
        assertTrue(artwork.isBanner)
    }

    @Test
    fun `a drawable without pixels of its own is rendered at the requested size`() {
        val key = installApp("com.example.vector", banner = SolidDrawable(Color.MAGENTA, 32, 18))
        val artwork = checkNotNull(load(ArtSource.AppBanner(key), 640, 360))
        assertEquals(640 to 360, artwork.size())
        assertColor(Color.MAGENTA, artwork.pixel(639, 359))
    }

    @Test
    fun `the application banner is used when the activity has none`() {
        val key = installApp("com.example.appbanner", appBanner = SolidDrawable(Color.BLUE, 320, 180), icon = SolidDrawable(Color.RED, 96, 96))
        val artwork = checkNotNull(load(ArtSource.AppBanner(key), 320, 180))
        assertTrue(artwork.isBanner)
        assertColor(Color.BLUE, artwork.pixel(160, 90))
    }

    @Test
    fun `an app without a banner gets its icon as a square of the requested height`() {
        val key = installApp("com.example.icononly", icon = SolidDrawable(Color.GREEN, 108, 108))
        val artwork = checkNotNull(load(ArtSource.AppBanner(key), 320, 180))
        assertFalse(artwork.isBanner)
        assertEquals(180 to 180, artwork.size())
        assertColor(Color.GREEN, artwork.pixel(90, 90))
        assertHue(120f, artwork.accent)
    }

    @Test
    fun `a bitmap icon keeps its own resolution and a non-square one is fitted, not cropped`() {
        val small = installApp("com.example.smallicon", icon = bitmapDrawable(96, 96) { _, _ -> Color.RED })
        assertEquals(96 to 96, checkNotNull(load(ArtSource.AppBanner(small), 320, 180)).size())

        val wide = installApp("com.example.wideicon", icon = SolidDrawable(Color.RED, 200, 100))
        val artwork = checkNotNull(load(ArtSource.AppBanner(wide), 320, 100))
        assertEquals(100 to 100, artwork.size())
        assertColor(Color.RED, artwork.pixel(2, 50))
        assertColor(Color.RED, artwork.pixel(97, 50))
        assertEquals("letterboxed above the icon", 0, artwork.pixel(50, 10) ushr 24)
        assertEquals("letterboxed below the icon", 0, artwork.pixel(50, 90) ushr 24)
    }

    @Test
    fun `an adaptive icon is drawn through its own mask`() {
        val adaptive = AdaptiveIconDrawable(ColorDrawable(Color.BLUE), ColorDrawable(Color.RED))
        val key = installApp("com.example.adaptive", icon = adaptive)
        val artwork = checkNotNull(load(ArtSource.AppBanner(key), 256, 144))
        assertFalse(artwork.isBanner)
        assertEquals(144 to 144, artwork.size())
        assertColor(Color.RED, artwork.pixel(72, 72))
    }

    @Test
    fun `a stale activity name still gets the application's artwork and a missing package gets none`() {
        val key = installApp("com.example.app", appBanner = SolidDrawable(Color.BLUE, 320, 180))
        val stale = ArtSource.AppBanner(AppKey(key.packageName, "com.example.app.RenamedActivity"))
        val artwork = checkNotNull(load(stale, 320, 180))
        assertTrue(artwork.isBanner)
        assertColor(Color.BLUE, artwork.pixel(10, 10))

        assertNull(load(ArtSource.AppBanner(AppKey("com.example.gone", "com.example.gone.Main")), 320, 180))
        assertNull(loadBlurred(ArtSource.AppBanner(AppKey("com.example.gone", "com.example.gone.Main"))))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Wallpapers
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the dynamic wallpaper is not a picture`() {
        val dynamic = ArtSource.Wallpaper(Wallpapers.DYNAMIC)
        assertNull(load(dynamic, 1920, 1080))
        assertNull(loadBlurred(dynamic))
        assertNull(loader.cached(dynamic, 1920, 1080))
        assertNull(loader.cachedBlurred(dynamic))
    }

    @Test
    fun `a missing wallpaper becomes a dark generated gradient instead of a blank screen`() {
        val artwork = checkNotNull(load(ArtSource.Wallpaper("no-such-wallpaper"), 1920, 1080))
        assertTrue(artwork.isBanner)
        // Generated small on purpose: a gradient loses nothing when the GPU stretches it.
        assertEquals(640 to 360, artwork.size())
        assertEquals(0xFF, artwork.pixel(0, 0) ushr 24)
        assertNotEquals(artwork.pixel(0, 0), artwork.pixel(639, 359))
        assertTrue(ImageMath.toHsl(artwork.pixel(320, 180))[2] < 0.3f)
        assertNotEquals(ImageMath.NEUTRAL_ACCENT, artwork.accent)

        val again = AndroidArtworkLoader(app, Dispatchers.Unconfined)
        val second = checkNotNull(runBlocking { again.load(ArtSource.Wallpaper("no-such-wallpaper"), 1920, 1080) })
        assertTrue("same id, same picture", artwork.bitmap().sameAs(second.bitmap()))
        val other = checkNotNull(load(ArtSource.Wallpaper("another-missing-one"), 1920, 1080))
        assertFalse("the hue follows the id", artwork.bitmap().sameAs(other.bitmap()))
    }

    @Test
    fun `a small request for a missing wallpaper is generated at that size`() {
        assertEquals(64 to 36, checkNotNull(load(ArtSource.Wallpaper("no-such-wallpaper"), 64, 36)).size())
    }

    @Test
    fun `built-in wallpapers decode from resources when they are bundled`() {
        val id = Wallpapers.builtIn.first().id
        val resourceId = app.resources.getIdentifier("wp_$id", "drawable", app.packageName)
        assumeTrue("the wallpaper files are added by another module", resourceId != 0)

        val artwork = checkNotNull(load(ArtSource.Wallpaper(id), 960, 540))
        assertTrue(artwork.isBanner)
        val pixels = artwork.image.width.toLong() * artwork.image.height
        assertTrue("downsampled to roughly the request, whatever the file's size", pixels <= 4L * 960 * 540)
    }

    @Test
    fun `the custom wallpaper is read from the external files dir and downsampled`() {
        val quadrants = bitmapOf(800, 450) { x, y ->
            when {
                x < 400 && y < 225 -> Color.RED
                x >= 400 && y < 225 -> Color.GREEN
                x < 400 -> Color.BLUE
                else -> Color.YELLOW
            }
        }
        writeImage(File(filesDir, "wallpaper.png"), quadrants)

        val artwork = checkNotNull(load(ArtSource.Wallpaper(Wallpapers.CUSTOM), 400, 225))
        assertTrue(artwork.isBanner)
        assertEquals(400 to 225, artwork.size())
        assertColor(Color.RED, artwork.pixel(50, 50))
        assertColor(Color.GREEN, artwork.pixel(350, 50))
        assertColor(Color.BLUE, artwork.pixel(50, 175))
        assertColor(Color.YELLOW, artwork.pixel(350, 175))
    }

    @Test
    fun `jpg is preferred over png and webp for the custom wallpaper`() {
        writeImage(File(filesDir, "wallpaper.webp"), bitmapOf(64, 36) { _, _ -> Color.GREEN }, Bitmap.CompressFormat.WEBP_LOSSLESS)
        writeImage(File(filesDir, "wallpaper.png"), bitmapOf(64, 36) { _, _ -> Color.BLUE })
        val source = ArtSource.Wallpaper(Wallpapers.CUSTOM)
        assertHue(240f, checkNotNull(load(source, 64, 36)).pixel(32, 18))

        writeImage(File(filesDir, "wallpaper.jpg"), bitmapOf(64, 36) { _, _ -> Color.RED }, Bitmap.CompressFormat.JPEG)
        // Still the cached picture: a file has no "changed" broadcast, whoever replaces it asks the loader to forget.
        assertHue(240f, checkNotNull(load(source, 64, 36)).pixel(32, 18))
        loader.clear()
        assertHue(0f, checkNotNull(load(source, 64, 36)).pixel(32, 18))

        File(filesDir, "wallpaper.jpg").delete()
        File(filesDir, "wallpaper.png").delete()
        loader.clear()
        assertHue(120f, checkNotNull(load(source, 64, 36)).pixel(32, 18))
    }

    @Test
    fun `a missing or unreadable custom wallpaper falls back to the gradient`() {
        val source = ArtSource.Wallpaper(Wallpapers.CUSTOM)
        val missing = checkNotNull(load(source, 320, 180))
        assertEquals(320 to 180, missing.size())

        File(filesDir, "wallpaper.jpg").writeText("this is not a jpeg")
        loader.clear()
        val broken = checkNotNull(load(source, 320, 180))
        assertTrue(missing.bitmap().sameAs(broken.bitmap()))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Local uris
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a file uri is decoded with power-of-two sampling`() {
        val file = writeImage(File(filesDir, "poster.png"), bitmapOf(1024, 512) { x, _ -> if (x < 512) Color.RED else Color.BLUE })
        val artwork = checkNotNull(load(ArtSource.LocalUri(Uri.fromFile(file).toString()), 256, 128))
        assertTrue(artwork.isBanner)
        assertEquals(256 to 128, artwork.size())
        assertColor(Color.RED, artwork.pixel(20, 64))
        assertColor(Color.BLUE, artwork.pixel(236, 64))
    }

    @Test
    fun `a source that sampling alone cannot fit is scaled down to the request`() {
        val file = writeImage(File(filesDir, "poster.png"), bitmapOf(900, 600) { _, _ -> Color.CYAN })
        val artwork = checkNotNull(load(ArtSource.LocalUri(Uri.fromFile(file).toString()), 300, 200))
        assertEquals(300 to 200, artwork.size())
        assertEquals(Bitmap.Config.ARGB_8888, artwork.bitmap().config)
    }

    @Test
    fun `a small source is not enlarged`() {
        val file = writeImage(File(filesDir, "thumb.png"), bitmapOf(40, 30) { _, _ -> Color.CYAN })
        assertEquals(40 to 30, checkNotNull(load(ArtSource.LocalUri(Uri.fromFile(file).toString()), 1920, 1080)).size())
    }

    @Test
    fun `a content uri is opened twice and both streams are closed`() {
        val bytes = writeImage(File(filesDir, "provider.png"), bitmapOf(200, 100) { _, _ -> Color.RED }).readBytes()
        val uri = Uri.parse("content://com.example.provider/posters/1")
        var opened = 0
        var closed = 0
        shadowOf(app.contentResolver).registerInputStreamSupplier(uri) {
            opened++
            object : FilterInputStream(bytes.inputStream()) {
                override fun close() {
                    closed++
                    super.close()
                }
            } as InputStream
        }

        val artwork = checkNotNull(load(ArtSource.LocalUri(uri.toString()), 100, 50))
        assertEquals(100 to 50, artwork.size())
        assertHue(0f, artwork.accent)
        assertEquals("one pass for the bounds, one for the pixels", 2, opened)
        assertEquals(2, closed)
    }

    @Test
    fun `network and unknown schemes are refused`() {
        val refused = listOf(
            "http://example.com/poster.png",
            "https://example.com/poster.png",
            "HTTPS://EXAMPLE.COM/poster.png",
            "ftp://example.com/poster.png",
            "data:image/png;base64,iVBORw0KGgo=",
            "/sdcard/poster.png",
            "poster.png",
            "",
        )
        for (uri in refused) {
            assertNull(uri, load(ArtSource.LocalUri(uri), 320, 180))
            assertNull(uri, loadBlurred(ArtSource.LocalUri(uri)))
        }
    }

    @Test
    fun `missing files and files that are not images give no artwork`() {
        assertNull(load(ArtSource.LocalUri(Uri.fromFile(File(filesDir, "nope.png")).toString()), 320, 180))
        val text = File(filesDir, "notes.png").apply { writeText("not an image") }
        assertNull(load(ArtSource.LocalUri(Uri.fromFile(text).toString()), 320, 180))
        assertNull(load(ArtSource.LocalUri("content://com.example.nobody/1"), 320, 180))
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Caching
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `a loaded picture is served from memory afterwards`() {
        val banner = SolidDrawable(Color.RED, 320, 180)
        val source = ArtSource.AppBanner(installApp("com.example.cached", banner = banner))

        assertNull(loader.cached(source, 320, 180))
        val first = checkNotNull(load(source, 320, 180))
        assertSame(first, loader.cached(source, 320, 180))
        assertSame(first, load(source, 320, 180))
        assertEquals("decoded once", 1, banner.draws)

        assertNull("another size is another entry", loader.cached(source, 160, 90))
        assertNotSame(first, load(source, 160, 90))
        assertEquals(2, banner.draws)
    }

    @Test
    fun `empty requests give nothing`() {
        val source = ArtSource.AppBanner(installApp("com.example.app", banner = SolidDrawable(Color.RED, 320, 180)))
        assertNull(load(source, 0, 180))
        assertNull(load(source, 320, 0))
        assertNull(load(source, -1, -1))
    }

    @Test
    fun `concurrent requests for the same picture share one decode`() {
        val scheduler = TestCoroutineScheduler()
        val queued = AndroidArtworkLoader(app, StandardTestDispatcher(scheduler))
        val banner = SolidDrawable(Color.RED, 320, 180)
        val source = ArtSource.AppBanner(installApp("com.example.shared", banner = banner))
        // Unconfined callers run up to the point where they wait for the decode, which stays queued on the scheduler.
        val callers = CoroutineScope(Job() + Dispatchers.Unconfined)

        val requests = List(5) { callers.async { queued.load(source, 320, 180) } }
        val blurRequests = List(3) { callers.async { queued.loadBlurred(source) } }
        assertTrue("nothing is decoded on the caller's thread", requests.none { it.isCompleted } && banner.draws == 0)
        scheduler.advanceUntilIdle()

        val results = requests.map { it.getCompleted() }
        assertNotNull(results.first())
        assertTrue(results.all { it === results.first() })
        assertTrue(blurRequests.map { it.getCompleted() }.let { blurs -> blurs.first() != null && blurs.all { it === blurs.first() } })
        assertEquals("one decode for the picture, one for its blurred copy", 2, banner.draws)
        callers.cancel()
    }

    @Test
    fun `a decode survives the cancellation of the caller that started it`() {
        val scheduler = TestCoroutineScheduler()
        val queued = AndroidArtworkLoader(app, StandardTestDispatcher(scheduler))
        val source = ArtSource.AppBanner(installApp("com.example.scrolled", banner = SolidDrawable(Color.RED, 320, 180)))
        val callers = CoroutineScope(Job() + Dispatchers.Unconfined)

        val impatient = callers.async { queued.load(source, 320, 180) }
        impatient.cancel()
        assertNull(queued.cached(source, 320, 180))
        scheduler.advanceUntilIdle()

        assertTrue(impatient.isCancelled)
        assertNotNull("the picture is ready when the tile scrolls back", queued.cached(source, 320, 180))
        callers.cancel()
    }

    @Test
    fun `invalidate forgets one package only`() {
        val changedBanner = SolidDrawable(Color.RED, 320, 180)
        val changed = ArtSource.AppBanner(installApp("com.example.changed", banner = changedBanner))
        val other = ArtSource.AppBanner(installApp("com.example.other", banner = SolidDrawable(Color.BLUE, 320, 180)))
        val wallpaper = ArtSource.Wallpaper("no-such-wallpaper")
        for (source in listOf(changed, other, wallpaper)) {
            assertNotNull(load(source, 320, 180))
            assertNotNull(loadBlurred(source))
        }
        val before = changedBanner.draws

        loader.invalidate("com.example.changed")

        assertNull(loader.cached(changed, 320, 180))
        assertNull(loader.cachedBlurred(changed))
        for (source in listOf(other, wallpaper)) {
            assertNotNull(loader.cached(source, 320, 180))
            assertNotNull(loader.cachedBlurred(source))
        }
        assertNotNull(load(changed, 320, 180))
        assertEquals("decoded afresh", before + 1, changedBanner.draws)
    }

    @Test
    fun `clear forgets everything`() {
        val source = ArtSource.AppBanner(installApp("com.example.app", banner = SolidDrawable(Color.RED, 320, 180)))
        assertNotNull(load(source, 320, 180))
        assertNotNull(loadBlurred(source))
        loader.clear()
        assertNull(loader.cached(source, 320, 180))
        assertNull(loader.cachedBlurred(source))
    }

    @Test
    fun `pictures too big for the tile cache live in the small backdrop cache`() {
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryClass(16)
        val tight = AndroidArtworkLoader(app, Dispatchers.Unconfined)
        // 8 MB tile cache: a 1200x1000 picture is 4.8 MB (more than half of it), a 640x360 one is 0.9 MB.
        val sources = List(3) { index ->
            val file = writeImage(File(filesDir, "backdrop$index.png"), bitmapOf(1200, 1000) { _, _ -> Color.RED })
            ArtSource.LocalUri(Uri.fromFile(file).toString())
        }

        val huge = checkNotNull(runBlocking { tight.load(sources[0], 1200, 1000) })
        assertEquals(1200 to 1000, huge.size())
        assertSame("a backdrop is decoded once and then remembered", huge, tight.cached(sources[0], 1200, 1000))

        // A small heap keeps two backdrops: loading a third forgets the least recently used one.
        assertNotNull(runBlocking { tight.load(sources[1], 1200, 1000) })
        assertNotNull(runBlocking { tight.load(sources[2], 1200, 1000) })
        assertNull(tight.cached(sources[0], 1200, 1000))
        assertNotNull(tight.cached(sources[1], 1200, 1000))
        assertNotNull(tight.cached(sources[2], 1200, 1000))

        // Small pictures still go to the byte-bounded tile cache, and clear() empties both.
        assertNotNull(runBlocking { tight.load(sources[0], 640, 360) })
        assertNotNull(tight.cached(sources[0], 640, 360))
        tight.clear()
        assertNull(tight.cached(sources[0], 640, 360))
        assertNull(tight.cached(sources[2], 1200, 1000))
    }

    @Test
    fun `the cache is bounded in bytes and evicts the least recently used picture`() {
        shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryClass(16)
        val tight = AndroidArtworkLoader(app, Dispatchers.Unconfined)
        // Each 800x600 picture is 1.92 MB; five of them cannot all stay in 8 MB.
        val sources = List(5) { index ->
            val file = writeImage(File(filesDir, "poster$index.png"), bitmapOf(800, 600) { _, _ -> Color.RED })
            ArtSource.LocalUri(Uri.fromFile(file).toString())
        }
        for (source in sources) assertNotNull(runBlocking { tight.load(source, 800, 600) })

        assertNull("the oldest entry made room", tight.cached(sources.first(), 800, 600))
        assertNotNull(tight.cached(sources.last(), 800, 600))
        assertTrue(sources.count { tight.cached(it, 800, 600) != null } in 3..4)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Blur
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `the blurred copy is tiny, opaque, smooth and cached`() {
        val source = ArtSource.AppBanner(
            installApp("com.example.split", banner = bitmapDrawable(640, 360) { x, _ -> if (x < 320) Color.RED else Color.BLUE }),
        )
        assertNull(loader.cachedBlurred(source))
        val blurred = checkNotNull(loadBlurred(source))

        assertTrue(blurred.isBanner)
        assertEquals(64 to 36, blurred.size())
        val pixels = IntArray(64 * 36).also { blurred.bitmap().getPixels(it, 0, 64, 0, 0, 64, 36) }
        assertTrue(pixels.all { it ushr 24 == 0xFF })
        assertHue(0f, blurred.pixel(1, 18))
        assertHue(240f, blurred.pixel(62, 18))
        val middle = blurred.pixel(32, 18)
        assertTrue("the hard edge is gone", Color.red(middle) in 60..200 && Color.blue(middle) in 60..200)
        for (x in 1 until 64) {
            assertTrue("red fades out monotonically", Color.red(blurred.pixel(x, 18)) <= Color.red(blurred.pixel(x - 1, 18)))
        }

        assertSame(blurred, loader.cachedBlurred(source))
        assertSame(blurred, loadBlurred(source))
    }

    @Test
    fun `the blurred copy of an icon is opaque and takes the icon's colour`() {
        // An opaque disc on a transparent square, like most launcher icons.
        val icon = bitmapDrawable(96, 96) { x, y ->
            val dx = x - 48
            val dy = y - 48
            if (dx * dx + dy * dy < 40 * 40) Color.GREEN else Color.TRANSPARENT
        }
        val source = ArtSource.AppBanner(installApp("com.example.icon", icon = icon))
        val blurred = checkNotNull(loadBlurred(source))

        assertTrue(blurred.isBanner)
        assertEquals(64 to 36, blurred.size())
        val pixels = IntArray(64 * 36).also { blurred.bitmap().getPixels(it, 0, 64, 0, 0, 64, 36) }
        assertTrue("no see-through pixels in a backdrop", pixels.all { it ushr 24 == 0xFF })
        assertHue(120f, blurred.pixel(32, 18))
        assertHue(120f, blurred.accent)
    }

    @Test
    fun `a missing wallpaper can be blurred too`() {
        val blurred = checkNotNull(loadBlurred(ArtSource.Wallpaper("no-such-wallpaper")))
        assertEquals(64 to 36, blurred.size())
        assertTrue(blurred.isBanner)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Robustness
    // -----------------------------------------------------------------------------------------------------------------

    @Test
    fun `artwork that throws while drawing gives null and does not poison later loads`() {
        val broken = ArtSource.AppBanner(installApp("com.example.broken", banner = FailingDrawable(IllegalStateException("bad resource"))))
        val fine = ArtSource.AppBanner(installApp("com.example.fine", banner = SolidDrawable(Color.RED, 320, 180)))

        assertNull(load(broken, 320, 180))
        assertNull(loadBlurred(broken))
        assertNull(loader.cached(broken, 320, 180))
        assertNotNull(load(fine, 320, 180))
        assertNull("a failed load is retried, not remembered", load(broken, 320, 180))
    }

    @Test
    fun `running out of memory drops the caches and gives null`() {
        val fine = ArtSource.AppBanner(installApp("com.example.fine", banner = SolidDrawable(Color.RED, 320, 180)))
        val greedy = ArtSource.AppBanner(installApp("com.example.greedy", banner = FailingDrawable(OutOfMemoryError("test"))))
        assertNotNull(load(fine, 320, 180))
        assertNotNull(loadBlurred(fine))

        assertNull(load(greedy, 320, 180))

        assertNull("memory was given back", loader.cached(fine, 320, 180))
        assertNull(loader.cachedBlurred(fine))
        assertNotNull("and the loader keeps working", load(fine, 320, 180))
    }
}
