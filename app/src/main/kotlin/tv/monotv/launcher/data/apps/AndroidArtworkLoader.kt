package tv.monotv.launcher.data.apps

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageItemInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.util.LruCache
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import tv.monotv.launcher.core.AppKey
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.Artwork
import tv.monotv.launcher.core.ArtworkLoader
import tv.monotv.launcher.core.Wallpapers
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns an [ArtSource] into pixels using nothing but what is already on the device: app banners and icons from the
 * package manager, built-in wallpapers from resources, a user wallpaper from the external files dir and local
 * content / file / resource uris. There is no network path at all — http(s) uris are refused before any I/O.
 *
 * Built for 1–2 GB TVs:
 *  - every decode runs on the `decode` dispatcher, is downsampled while decoding and never produces more pixels
 *    than were asked for (nor more than the source really has — the GPU scales up for free, RAM does not);
 *  - results live in a byte-bounded [LruCache], so scrolling back shows art on the first frame without the heap
 *    growing with the number of installed apps;
 *  - concurrent requests for the same picture share one decode;
 *  - blur is computed once per source on a 64x36 copy and cached — nothing is ever blurred per frame.
 *
 * Decodes run in a scope owned by the loader rather than in the caller's: a tile that scrolls away must not cancel
 * the decode that another tile is waiting for, and a finished decode is worth caching either way. The loader is
 * meant to live as long as the process and holds the application context only.
 *
 * Nothing here throws: missing apps, unreadable files, broken third-party resources and even [OutOfMemoryError]
 * end in `null` (after dropping the caches in the latter case).
 */
class AndroidArtworkLoader(
    context: Context,
    decode: CoroutineDispatcher = Dispatchers.IO,
) : ArtworkLoader {

    private val appContext: Context = context.applicationContext ?: context

    /**
     * Two decodes at a time: enough to fill a row of tiles quickly, few enough to leave cores of a quad A53 to the
     * UI and render threads and to keep the transient memory of full-screen decodes in check.
     */
    private val scope = CoroutineScope(SupervisorJob() + limited(decode))

    private val cache = object : LruCache<SizedKey, Artwork>(cacheBytes(appContext)) {
        override fun sizeOf(key: SizedKey, value: Artwork): Int = value.image.asAndroidBitmap().allocationByteCount
    }

    /** Blurred copies are ~9 KB each, so this cache is bounded by count. */
    private val blurredCache = LruCache<ArtSource, Artwork>(BLUR_CACHE_ENTRIES)

    /** Decodes in flight, by [SizedKey] or [BlurKey]. Guarded by itself. */
    private val flights = HashMap<Any, Deferred<Artwork?>>()

    /** Bumped by [invalidate] / [clear] so that a decode which started before them does not re-fill the cache. */
    private val generation = AtomicInteger()

    override fun cached(source: ArtSource, widthPx: Int, heightPx: Int): Artwork? =
        cache.get(SizedKey(source, widthPx, heightPx))

    override suspend fun load(source: ArtSource, widthPx: Int, heightPx: Int): Artwork? {
        if (widthPx <= 0 || heightPx <= 0 || !isPicture(source)) return null
        val key = SizedKey(source, widthPx, heightPx)
        cache.get(key)?.let { return it }
        return shared(key) {
            val startedAt = generation.get()
            render(source, min(widthPx, MAX_EDGE_PX), min(heightPx, MAX_EDGE_PX))?.also { artwork ->
                val bitmap = artwork.image.asAndroidBitmap()
                bitmap.prepareToDraw()
                // One picture may take half of the cache at most: a full-screen backdrop must not push every app
                // banner out. An oversized result is still returned, it is just not remembered.
                val fits = bitmap.allocationByteCount <= cache.maxSize() / 2
                if (fits && startedAt == generation.get()) cache.put(key, artwork)
            }
        }
    }

    override fun cachedBlurred(source: ArtSource): Artwork? = blurredCache.get(source)

    override suspend fun loadBlurred(source: ArtSource): Artwork? {
        if (!isPicture(source)) return null
        blurredCache.get(source)?.let { return it }
        return shared(BlurKey(source)) {
            val startedAt = generation.get()
            renderBlurred(source)?.also { artwork ->
                if (startedAt == generation.get()) blurredCache.put(source, artwork)
            }
        }
    }

    /**
     * Forgets the banner / icon of [packageName]. Call it when the package was updated, removed or changed — its
     * artwork may be different now and a cached copy would otherwise live until it is evicted.
     */
    fun invalidate(packageName: String) {
        generation.incrementAndGet()
        for (key in cache.snapshot().keys) {
            if (key.source.isBannerOf(packageName)) cache.remove(key)
        }
        for (source in blurredCache.snapshot().keys) {
            if (source.isBannerOf(packageName)) blurredCache.remove(source)
        }
    }

    /**
     * Drops every cached bitmap. Meant for `onTrimMemory` and for the moment the user replaced the custom wallpaper
     * file (a file has no "changed" broadcast to listen to).
     */
    fun clear() {
        generation.incrementAndGet()
        cache.evictAll()
        blurredCache.evictAll()
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Request plumbing
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Runs [block] once per [key] no matter how many callers ask at the same time. The entry is created LAZY and
     * started only by `await`, i.e. after it is in the map — an eager start on an unconfined dispatcher could finish
     * (and try to remove itself) before it was ever registered and would then stay in the map forever.
     */
    private suspend fun shared(key: Any, block: () -> Artwork?): Artwork? {
        val flight = synchronized(flights) {
            flights[key] ?: scope.async(start = CoroutineStart.LAZY) {
                try {
                    guarded(block)
                } finally {
                    synchronized(flights) { flights.remove(key) }
                }
            }.also { flights[key] = it }
        }
        return flight.await()
    }

    /**
     * Third-party resources and content providers are foreign code: whatever they throw must not take the launcher
     * down, hence the broad catch ([block] does not suspend, so no cancellation can be swallowed here).
     */
    private fun guarded(block: () -> Artwork?): Artwork? = try {
        block()
    } catch (e: Exception) {
        null
    } catch (e: StackOverflowError) {
        // A pathologically nested drawable; the stack has unwound by now and nothing is left half-done.
        null
    } catch (e: OutOfMemoryError) {
        // Give the heap back first; the request that hit the wall is simply answered with "no artwork".
        cache.evictAll()
        blurredCache.evictAll()
        null
    }

    private fun render(source: ArtSource, widthPx: Int, heightPx: Int): Artwork? = when (source) {
        is ArtSource.AppBanner -> renderApp(source.key, widthPx, heightPx)
        is ArtSource.Wallpaper -> renderWallpaper(source.id, widthPx, heightPx)
        is ArtSource.LocalUri -> renderLocalUri(source.uri, widthPx, heightPx)
    }

    private fun artwork(bitmap: Bitmap, isBanner: Boolean): Artwork =
        Artwork(bitmap.asImageBitmap(), accentOf(bitmap), isBanner)

    // -----------------------------------------------------------------------------------------------------------------
    // App banners and icons
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * The activity's own banner, else the application banner (`loadBanner` already falls back to it), else the icon.
     * A key whose activity no longer exists still gets the application's artwork instead of an empty tile.
     */
    private fun renderApp(key: AppKey, widthPx: Int, heightPx: Int): Artwork? {
        val packageManager = appContext.packageManager
        val item = activityInfo(packageManager, key) ?: applicationInfo(packageManager, key.packageName) ?: return null

        val banner = item.loadBanner(packageManager)
        if (banner != null) {
            val width = ImageMath.bannerWidth(widthPx, heightPx)
            return artwork(drawCentered(banner, width, max(1, width * 9 / 16), crop = true), isBanner = true)
        }
        val icon = item.loadIcon(packageManager) ?: return null
        return artwork(drawCentered(icon, heightPx, heightPx, crop = false), isBanner = false)
    }

    /**
     * Both lookups use the classic int-flags overloads on every API level: they are the only ones that exist before
     * API 33 and behave identically after it, so a second branch for the flags-object twins would buy nothing.
     */
    @Suppress("DEPRECATION")
    private fun activityInfo(packageManager: PackageManager, key: AppKey): PackageItemInfo? = try {
        packageManager.getActivityInfo(ComponentName(key.packageName, key.activityName), 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    @Suppress("DEPRECATION")
    private fun applicationInfo(packageManager: PackageManager, packageName: String): PackageItemInfo? = try {
        packageManager.getApplicationInfo(packageName, 0)
    } catch (e: PackageManager.NameNotFoundException) {
        null
    }

    /**
     * Draws any drawable — bitmap, vector, adaptive icon, layer list — into a new bitmap of (at most) the given
     * size, centred and either cropped to fill it ([crop], banners) or fitted inside it (icons). Going through
     * `Drawable.draw` is what makes [android.graphics.drawable.AdaptiveIconDrawable] and friends come out right.
     *
     * A bitmap-backed drawable is never enlarged: when the source has fewer pixels than requested the result
     * shrinks to what the source can fill, keeping the requested aspect ratio.
     */
    private fun drawCentered(drawable: Drawable, width: Int, height: Int, crop: Boolean): Bitmap {
        var outWidth = width
        var outHeight = height
        (drawable as? BitmapDrawable)?.bitmap?.let { source ->
            val widthRatio = width / source.width.toFloat()
            val heightRatio = height / source.height.toFloat()
            val enlargement = if (crop) max(widthRatio, heightRatio) else min(widthRatio, heightRatio)
            if (enlargement > 1f) {
                outWidth = max(1, (width / enlargement).roundToInt())
                outHeight = max(1, (height / enlargement).roundToInt())
            }
        }

        val intrinsicWidth = drawable.intrinsicWidth
        val intrinsicHeight = drawable.intrinsicHeight
        if (intrinsicWidth <= 0 || intrinsicHeight <= 0) {
            // No size of its own (a colour, a shape): it simply fills the picture.
            drawable.setBounds(0, 0, outWidth, outHeight)
        } else {
            val widthScale = outWidth / intrinsicWidth.toFloat()
            val heightScale = outHeight / intrinsicHeight.toFloat()
            val scale = if (crop) max(widthScale, heightScale) else min(widthScale, heightScale)
            val drawnWidth = (intrinsicWidth * scale).roundToInt()
            val drawnHeight = (intrinsicHeight * scale).roundToInt()
            val left = (outWidth - drawnWidth) / 2
            val top = (outHeight - drawnHeight) / 2
            drawable.setBounds(left, top, left + drawnWidth, top + drawnHeight)
        }
        val bitmap = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Wallpapers
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Built-in wallpapers are `wp_<id>` drawables, the custom one is a file the user dropped next to the config.
     * When neither exists the result is a generated gradient: a missing file must never leave the screen blank.
     */
    private fun renderWallpaper(id: String, widthPx: Int, heightPx: Int): Artwork {
        val decoded = try {
            if (id == Wallpapers.CUSTOM) {
                customWallpaperFile()?.let { file -> decodeSampled(widthPx, heightPx) { file.inputStream() } }
            } else {
                val resources = appContext.resources
                val resourceId =
                    resources.getIdentifier(WALLPAPER_RESOURCE_PREFIX + id, "drawable", appContext.packageName)
                if (resourceId == 0) null else decodeSampled(widthPx, heightPx) { resources.openRawResource(resourceId) }
            }
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
        return artwork(decoded ?: fallbackWallpaper(id, widthPx, heightPx), isBanner = true)
    }

    private fun customWallpaperFile(): File? {
        val directory = appContext.getExternalFilesDir(null) ?: return null
        return CUSTOM_WALLPAPER_FILES.map { File(directory, it) }.firstOrNull { it.isFile }
    }

    /** A soft gradient has no detail to lose, so it is generated small and left to the GPU to stretch. */
    private fun fallbackWallpaper(id: String, widthPx: Int, heightPx: Int): Bitmap {
        val shrink = min(1f, sqrt(FALLBACK_MAX_PIXELS / (widthPx.toFloat() * heightPx)))
        val width = max(1, (widthPx * shrink).roundToInt())
        val height = max(1, (heightPx * shrink).roundToInt())
        val pixels = ImageMath.fallbackGradient(width, height, id.hashCode())
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Local uris and bounded decoding
    // -----------------------------------------------------------------------------------------------------------------

    private fun renderLocalUri(uri: String, widthPx: Int, heightPx: Int): Artwork? {
        val parsed = Uri.parse(uri)
        // The scheme is checked before anything is opened: this launcher has no network path, by design.
        if (parsed.scheme?.lowercase(Locale.ROOT) !in LOCAL_SCHEMES) return null
        val resolver = appContext.contentResolver
        val bitmap = decodeSampled(widthPx, heightPx) { resolver.openInputStream(parsed) } ?: return null
        return artwork(bitmap, isBanner = true)
    }

    /**
     * Two-pass decode: read the dimensions first, then let the decoder itself skip pixels (`inSampleSize`), so a
     * 4K source never exists in memory at full size. [open] is called once per pass and every stream is closed.
     */
    private fun decodeSampled(widthPx: Int, heightPx: Int, open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageMath.sampleSize(
                ImageMath.coverScale(bounds.outWidth, bounds.outHeight, widthPx, heightPx),
            )
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = (open() ?: return null).use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        return trimmed(decoded, widthPx, heightPx)
    }

    /**
     * `inSampleSize` only knows powers of two, which can leave up to 4x the wanted pixels; this brings the bitmap
     * down to the size that just covers the request and makes sure it is plain 32-bit (a 16-bit PNG would
     * otherwise arrive as 64-bit float pixels).
     */
    private fun trimmed(decoded: Bitmap, widthPx: Int, heightPx: Int): Bitmap {
        var bitmap = decoded
        val scale = ImageMath.coverScale(bitmap.width, bitmap.height, widthPx, heightPx)
        if (scale < WORTH_SCALING_BELOW) {
            val scaled = Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).roundToInt()),
                max(1, (bitmap.height * scale).roundToInt()),
                true,
            )
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        if (bitmap.config != Bitmap.Config.ARGB_8888) {
            val converted: Bitmap? = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            if (converted != null) {
                bitmap.recycle()
                bitmap = converted
            }
        }
        return bitmap
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Colour and blur
    // -----------------------------------------------------------------------------------------------------------------

    /** Dominant colour from a thumbnail: a few thousand pixels say as much about the colour as two million do. */
    private fun accentOf(bitmap: Bitmap): Int {
        val longest = max(bitmap.width, bitmap.height)
        val sample = if (longest <= ACCENT_SAMPLE_EDGE_PX) {
            bitmap
        } else {
            val scale = ACCENT_SAMPLE_EDGE_PX / longest.toFloat()
            Bitmap.createScaledBitmap(
                bitmap,
                max(1, (bitmap.width * scale).roundToInt()),
                max(1, (bitmap.height * scale).roundToInt()),
                true,
            )
        }
        val pixels = IntArray(sample.width * sample.height)
        sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
        if (sample !== bitmap) sample.recycle()
        return ImageMath.dominantColor(pixels)
    }

    /**
     * The picture at thumbnail size, centre-cropped onto an opaque dark base of its own accent colour and box
     * blurred three times (which is visually a Gaussian). The base matters for icons and transparent banners:
     * blurring see-through pixels would smear black fringes into the result.
     */
    private fun renderBlurred(source: ArtSource): Artwork? {
        val base = render(source, BLUR_WIDTH_PX, BLUR_HEIGHT_PX) ?: return null
        val small = base.image.asAndroidBitmap()

        val bitmap = Bitmap.createBitmap(BLUR_WIDTH_PX, BLUR_HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(ImageMath.withLightness(base.accent, BLUR_BASE_LIGHTNESS))
        val scale = max(BLUR_WIDTH_PX / small.width.toFloat(), BLUR_HEIGHT_PX / small.height.toFloat())
        val drawnWidth = small.width * scale
        val drawnHeight = small.height * scale
        val left = (BLUR_WIDTH_PX - drawnWidth) / 2f
        val top = (BLUR_HEIGHT_PX - drawnHeight) / 2f
        canvas.drawBitmap(small, null, RectF(left, top, left + drawnWidth, top + drawnHeight), Paint(Paint.FILTER_BITMAP_FLAG))

        val pixels = IntArray(BLUR_WIDTH_PX * BLUR_HEIGHT_PX)
        bitmap.getPixels(pixels, 0, BLUR_WIDTH_PX, 0, 0, BLUR_WIDTH_PX, BLUR_HEIGHT_PX)
        val blurred = ImageMath.boxBlur(pixels, BLUR_WIDTH_PX, BLUR_HEIGHT_PX, BLUR_RADIUS_PX, BLUR_PASSES)
        bitmap.setPixels(blurred, 0, BLUR_WIDTH_PX, 0, 0, BLUR_WIDTH_PX, BLUR_HEIGHT_PX)
        return Artwork(bitmap.asImageBitmap(), base.accent, isBanner = true)
    }

    private data class SizedKey(val source: ArtSource, val widthPx: Int, val heightPx: Int)

    private data class BlurKey(val source: ArtSource)

    private companion object {
        const val MIN_CACHE_BYTES = 8 * 1024 * 1024
        const val BYTES_PER_MB = 1024 * 1024
        const val DECODE_PARALLELISM = 2
        const val BLUR_CACHE_ENTRIES = 32

        /** No request is honoured beyond this edge length; the UI never asks for more than a 1080p backdrop. */
        const val MAX_EDGE_PX = 4096

        const val BLUR_WIDTH_PX = 64
        const val BLUR_HEIGHT_PX = 36
        const val BLUR_RADIUS_PX = 6
        const val BLUR_PASSES = 3
        const val BLUR_BASE_LIGHTNESS = 0.12f

        const val ACCENT_SAMPLE_EDGE_PX = 48

        /** A decoded bitmap is scaled once more only when that saves at least a fifth of its edge length. */
        const val WORTH_SCALING_BELOW = 0.8f

        const val FALLBACK_MAX_PIXELS = 640f * 360f

        const val WALLPAPER_RESOURCE_PREFIX = "wp_"
        val CUSTOM_WALLPAPER_FILES = listOf("wallpaper.jpg", "wallpaper.png", "wallpaper.webp")
        val LOCAL_SCHEMES = setOf("content", "file", "android.resource")

        /** An eighth of the app's heap class, but never less than 8 MB — a 720p backdrop takes just under half. */
        fun cacheBytes(context: Context): Int {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val heapClassMb = activityManager?.memoryClass ?: 0
            return max(MIN_CACHE_BYTES, heapClassMb * (BYTES_PER_MB / 8))
        }

        /** Dispatchers.Unconfined has no notion of parallelism and refuses to be limited; it is used as it is. */
        fun limited(dispatcher: CoroutineDispatcher): CoroutineDispatcher = try {
            dispatcher.limitedParallelism(DECODE_PARALLELISM)
        } catch (e: UnsupportedOperationException) {
            dispatcher
        }

        fun isPicture(source: ArtSource): Boolean =
            !(source is ArtSource.Wallpaper && source.id == Wallpapers.DYNAMIC)

        fun ArtSource.isBannerOf(packageName: String): Boolean =
            this is ArtSource.AppBanner && key.packageName == packageName
    }
}
