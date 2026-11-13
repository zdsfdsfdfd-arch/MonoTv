package tv.monotv.launcher.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.res.ResourcesCompat
import tv.monotv.launcher.R
import tv.monotv.launcher.core.ArtSource
import tv.monotv.launcher.core.Artwork
import tv.monotv.launcher.core.ArtworkLoader

/**
 * Deterministic artwork for screenshot tests: generated app banners (a colour field + the app name — no real logos),
 * a few icon-only apps, and the real built-in wallpapers when the resources exist. Everything is synchronous so a
 * screenshot never races a decode.
 */
class FakeArtworkLoader(private val context: Context) : ArtworkLoader {
    data class Brand(val label: String, val top: Int, val bottom: Int, val text: Int = 0xFFFFFFFF.toInt(), val iconOnly: Boolean = false)

    private val brands = HashMap<String, Brand>()
    private val cache = HashMap<String, Artwork?>()
    private val bold: Typeface = ResourcesCompat.getFont(context, R.font.inter_display_bold) ?: Typeface.DEFAULT_BOLD

    fun register(packageName: String, brand: Brand) {
        brands[packageName] = brand
    }

    override fun cached(source: ArtSource, widthPx: Int, heightPx: Int): Artwork? =
        cache.getOrPut("$source@$widthPx x $heightPx") { render(source, widthPx, heightPx) }

    override suspend fun load(source: ArtSource, widthPx: Int, heightPx: Int): Artwork? = cached(source, widthPx, heightPx)

    override fun cachedBlurred(source: ArtSource): Artwork? = cache.getOrPut("$source@blur") {
        val base = render(source, 96, 54) ?: return@getOrPut null
        Artwork(blur(base.image.asAndroidBitmapCopy(64, 36)).asImageBitmap(), base.accent, isBanner = true)
    }

    override suspend fun loadBlurred(source: ArtSource): Artwork? = cachedBlurred(source)

    private fun render(source: ArtSource, w: Int, h: Int): Artwork? = when (source) {
        is ArtSource.AppBanner -> {
            val brand = brands[source.key.packageName] ?: Brand(source.key.packageName.substringAfterLast('.'), 0xFF3A4654.toInt(), 0xFF171C22.toInt())
            if (brand.iconOnly) icon(brand, h) else banner(brand, w, h)
        }
        is ArtSource.Wallpaper -> wallpaper(source.id, w, h)
        is ArtSource.LocalUri -> null
    }

    private fun banner(brand: Brand, w: Int, h: Int): Artwork {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), brand.top, brand.bottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = RadialGradient(w * 0.82f, h * 0.1f, w * 0.7f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = null
        paint.color = brand.text
        paint.typeface = bold
        paint.textAlign = Paint.Align.CENTER
        var size = h * 0.30f
        paint.textSize = size
        while (paint.measureText(brand.label) > w * 0.84f && size > 6f) {
            size *= 0.92f
            paint.textSize = size
        }
        paint.letterSpacing = -0.02f
        canvas.drawText(brand.label, w / 2f, h / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return Artwork(bitmap.asImageBitmap(), accentOf(brand), isBanner = true)
    }

    private fun icon(brand: Brand, side: Int): Artwork {
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, 0f, side.toFloat(), side.toFloat(), brand.top, brand.bottom, Shader.TileMode.CLAMP)
        val r = side * 0.24f
        canvas.drawRoundRect(RectF(0f, 0f, side.toFloat(), side.toFloat()), r, r, paint)
        paint.shader = null
        paint.color = brand.text
        paint.typeface = bold
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = side * 0.5f
        canvas.drawText(brand.label.take(1), side / 2f, side / 2f - (paint.descent() + paint.ascent()) / 2f, paint)
        return Artwork(bitmap.asImageBitmap(), accentOf(brand), isBanner = false)
    }

    /** Mirrors the production rule: a dark picture still yields a usable, saturated accent. */
    private fun accentOf(brand: Brand): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(brand.top, hsv)
        if (hsv[2] < 0.3f || hsv[1] < 0.15f) android.graphics.Color.colorToHSV(brand.text, hsv)
        if (hsv[1] < 0.15f) return 0xFF7C8794.toInt()
        return android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceIn(0.45f, 0.85f), hsv[2].coerceIn(0.6f, 0.9f)))
    }

    private fun wallpaper(id: String, w: Int, h: Int): Artwork? {
        val resId = context.resources.getIdentifier("wp_$id", "drawable", context.packageName)
        if (resId != 0) {
            val decoded = BitmapFactory.decodeResource(context.resources, resId, BitmapFactory.Options().apply { inScaled = false })
            if (decoded != null) {
                val scaled = Bitmap.createScaledBitmap(decoded, w, h, true)
                return Artwork(scaled.asImageBitmap(), averageColor(scaled), isBanner = true)
            }
        }
        // Stand-in art until the real wallpapers exist: night gradient with one light source top-right.
        val hue = (id.hashCode() and 0x7fffffff) % 360
        val a = android.graphics.Color.HSVToColor(floatArrayOf(hue.toFloat(), 0.62f, 0.20f))
        val b = android.graphics.Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.70f, 0.52f))
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(0f, h.toFloat(), w.toFloat(), 0f, 0xFF020304.toInt(), a, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        paint.shader = RadialGradient(w * 0.74f, h * 0.30f, w * 0.42f, b, 0x00000000, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return Artwork(bitmap.asImageBitmap(), b, isBanner = true)
    }

    private fun averageColor(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        var bestSat = -1f
        var best = 0xFF5B6B7A.toInt()
        val hsv = FloatArray(3)
        for (y in 0 until 8) for (x in 0 until 8) {
            val c = small.getPixel(x, y)
            android.graphics.Color.colorToHSV(c, hsv)
            val score = hsv[1] * hsv[2]
            if (score > bestSat) {
                bestSat = score
                best = android.graphics.Color.HSVToColor(floatArrayOf(hsv[0], hsv[1].coerceIn(0.35f, 0.85f), hsv[2].coerceIn(0.55f, 0.8f)))
            }
        }
        return best
    }

    private fun androidx.compose.ui.graphics.ImageBitmap.asAndroidBitmapCopy(w: Int, h: Int): Bitmap {
        val src = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)
        readPixels(pixels)
        src.setPixels(pixels, 0, width, 0, 0, width, height)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    /** Small box blur, 3 passes. */
    private fun blur(bitmap: Bitmap): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        var px = IntArray(w * h)
        bitmap.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) {
            val out = IntArray(w * h)
            val r = 4
            for (y in 0 until h) for (x in 0 until w) {
                var sr = 0; var sg = 0; var sb = 0; var n = 0
                for (dy in -r..r) for (dx in -r..r) {
                    val xx = (x + dx).coerceIn(0, w - 1)
                    val yy = (y + dy).coerceIn(0, h - 1)
                    val c = px[yy * w + xx]
                    sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; n++
                }
                out[y * w + x] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
            }
            px = out
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }
}
