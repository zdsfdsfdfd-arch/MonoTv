package tv.monotv.launcher.data.apps

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pixel maths of the artwork pipeline, kept free of Android types so it is verified by plain JVM tests.
 *
 * Pixels are non-premultiplied ARGB ints in row-major order, exactly what `Bitmap.getPixels` produces.
 */
object ImageMath {

    /** Accent used when an image has no usable colour at all (greyscale, black, transparent): a calm blue-grey. */
    const val NEUTRAL_ACCENT: Int = 0xFF5B6B7A.toInt()

    private const val HUE_BINS = 24
    private const val MIN_ALPHA = 128
    private const val MIN_LIGHTNESS = 0.10f
    private const val MAX_LIGHTNESS = 0.92f
    private const val MIN_SATURATION = 0.12f

    /** An image may come out this many times larger (in pixels) than requested before it is scaled down further. */
    private const val COVER_PIXEL_BUDGET = 4L

    // -----------------------------------------------------------------------------------------------------------------
    // Blur
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Box blur with clamped edges; [passes] = 3 is visually a Gaussian. Runs in O(pixels) per pass regardless of
     * [radius] (sliding window), which is what makes a strong blur affordable on a Cortex-A53.
     *
     * Channels are averaged independently, so the result is exact for opaque images — the loader always blurs an
     * opaque image. Returns a new array; the input is left untouched. Degenerate arguments return a plain copy.
     */
    fun boxBlur(pixels: IntArray, width: Int, height: Int, radius: Int, passes: Int): IntArray {
        val result = pixels.copyOf()
        if (width <= 0 || height <= 0 || radius <= 0 || passes <= 0) return result
        if (pixels.size.toLong() < width.toLong() * height) return result

        val scratch = IntArray(result.size)
        repeat(passes) {
            blurLines(result, scratch, lines = height, length = width, lineStride = width, pixelStride = 1, radius)
            blurLines(scratch, result, lines = width, length = height, lineStride = 1, pixelStride = width, radius)
        }
        return result
    }

    private fun blurLines(
        src: IntArray,
        dst: IntArray,
        lines: Int,
        length: Int,
        lineStride: Int,
        pixelStride: Int,
        radius: Int,
    ) {
        val window = 2 * radius + 1
        val half = window / 2
        val last = length - 1
        for (line in 0 until lines) {
            val base = line * lineStride
            var a = 0
            var r = 0
            var g = 0
            var b = 0
            for (i in -radius..radius) {
                val p = src[base + i.coerceIn(0, last) * pixelStride]
                a += p ushr 24
                r += (p shr 16) and 0xFF
                g += (p shr 8) and 0xFF
                b += p and 0xFF
            }
            for (i in 0 until length) {
                dst[base + i * pixelStride] =
                    (((a + half) / window) shl 24) or
                    (((r + half) / window) shl 16) or
                    (((g + half) / window) shl 8) or
                    ((b + half) / window)
                val leaving = src[base + max(i - radius, 0) * pixelStride]
                val entering = src[base + min(i + radius + 1, last) * pixelStride]
                a += (entering ushr 24) - (leaving ushr 24)
                r += ((entering shr 16) and 0xFF) - ((leaving shr 16) and 0xFF)
                g += ((entering shr 8) and 0xFF) - ((leaving shr 8) and 0xFF)
                b += (entering and 0xFF) - (leaving and 0xFF)
            }
        }
    }

    // -----------------------------------------------------------------------------------------------------------------
    // Colour
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * The colour an image "is", tuned for UI use (glows, tile backgrounds, ambient tint).
     *
     * Transparent, near-black, near-white and grey pixels say nothing about the brand colour and are ignored. The
     * rest vote for a hue weighted by their saturation, the strongest hue wins, and the winner is normalised
     * (saturation 0.35..0.85, lightness 0.45..0.65) so the accent is never muddy, neon, too dark to see on black or
     * so bright that white text on it becomes unreadable. Without a usable pixel the result is [NEUTRAL_ACCENT].
     */
    fun dominantColor(pixels: IntArray): Int {
        val weight = FloatArray(HUE_BINS)
        val sumCos = FloatArray(HUE_BINS)
        val sumSin = FloatArray(HUE_BINS)
        val sumSaturation = FloatArray(HUE_BINS)
        val sumLightness = FloatArray(HUE_BINS)
        val hsl = FloatArray(3)

        for (pixel in pixels) {
            if ((pixel ushr 24) < MIN_ALPHA) continue
            argbToHsl(pixel, hsl)
            val saturation = hsl[1]
            val lightness = hsl[2]
            if (lightness < MIN_LIGHTNESS || lightness > MAX_LIGHTNESS || saturation < MIN_SATURATION) continue
            val bin = (hsl[0] / 360f * HUE_BINS).toInt().coerceIn(0, HUE_BINS - 1)
            val radians = hsl[0] * (PI.toFloat() / 180f)
            weight[bin] += saturation
            sumCos[bin] += saturation * cos(radians)
            sumSin[bin] += saturation * sin(radians)
            sumSaturation[bin] += saturation * saturation
            sumLightness[bin] += saturation * lightness
        }

        // A colour rarely falls into a single 15° bin, so neighbours support the candidate with half their weight.
        var best = -1
        var bestScore = 0f
        for (bin in 0 until HUE_BINS) {
            val score = weight[bin] + 0.5f * (weight[wrap(bin - 1)] + weight[wrap(bin + 1)])
            if (score > bestScore) {
                bestScore = score
                best = bin
            }
        }
        if (best < 0) return NEUTRAL_ACCENT

        var total = 0f
        var x = 0f
        var y = 0f
        var saturation = 0f
        var lightness = 0f
        for (offset in -1..1) {
            val bin = wrap(best + offset)
            total += weight[bin]
            x += sumCos[bin]
            y += sumSin[bin]
            saturation += sumSaturation[bin]
            lightness += sumLightness[bin]
        }
        val hue = ((atan2(y, x) * (180f / PI.toFloat())) + 360f) % 360f
        return hslToArgb(
            hue,
            (saturation / total).coerceIn(0.35f, 0.85f),
            (lightness / total).coerceIn(0.45f, 0.65f),
        )
    }

    private fun wrap(bin: Int): Int = (bin + HUE_BINS) % HUE_BINS

    /** Writes hue (0..360), saturation (0..1) and lightness (0..1) of [argb] into [out]; alpha is ignored. */
    fun argbToHsl(argb: Int, out: FloatArray) {
        val r = ((argb shr 16) and 0xFF) / 255f
        val g = ((argb shr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val high = max(r, max(g, b))
        val low = min(r, min(g, b))
        val delta = high - low
        val lightness = (high + low) / 2f

        var hue = 0f
        var saturation = 0f
        if (delta > 0f) {
            saturation = delta / (1f - abs(2f * lightness - 1f))
            hue = when (high) {
                r -> 60f * (((g - b) / delta) % 6f)
                g -> 60f * (((b - r) / delta) + 2f)
                else -> 60f * (((r - g) / delta) + 4f)
            }
            if (hue < 0f) hue += 360f
        }
        out[0] = hue
        out[1] = saturation.coerceIn(0f, 1f)
        out[2] = lightness
    }

    /** Allocating convenience for [argbToHsl]: `[hue, saturation, lightness]`. */
    fun toHsl(argb: Int): FloatArray = FloatArray(3).also { argbToHsl(argb, it) }

    /** Inverse of [argbToHsl]. Hue wraps around, saturation and lightness are clamped to 0..1. */
    fun hslToArgb(hue: Float, saturation: Float, lightness: Float, alpha: Int = 0xFF): Int {
        val h = ((hue % 360f) + 360f) % 360f
        val s = saturation.coerceIn(0f, 1f)
        val l = lightness.coerceIn(0f, 1f)
        val chroma = (1f - abs(2f * l - 1f)) * s
        val x = chroma * (1f - abs((h / 60f) % 2f - 1f))
        val m = l - chroma / 2f
        val r: Float
        val g: Float
        val b: Float
        when ((h / 60f).toInt()) {
            0 -> { r = chroma; g = x; b = 0f }
            1 -> { r = x; g = chroma; b = 0f }
            2 -> { r = 0f; g = chroma; b = x }
            3 -> { r = 0f; g = x; b = chroma }
            4 -> { r = x; g = 0f; b = chroma }
            else -> { r = chroma; g = 0f; b = x }
        }
        return (alpha.coerceIn(0, 0xFF) shl 24) or
            (channel(r + m) shl 16) or
            (channel(g + m) shl 8) or
            channel(b + m)
    }

    /** Same hue and saturation at a fixed [lightness]; used for the dark base under blurred, translucent art. */
    fun withLightness(argb: Int, lightness: Float): Int {
        val hsl = toHsl(argb)
        return hslToArgb(hsl[0], hsl[1], lightness, argb ushr 24)
    }

    private fun channel(value: Float): Int = (value * 255f).roundToInt().coerceIn(0, 0xFF)

    // -----------------------------------------------------------------------------------------------------------------
    // Sizing
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Factor (0 < f <= 1) that shrinks a source image until it just covers the requested box. Images are never
     * enlarged — upscaling is free on the GPU and costly in RAM.
     *
     * A source whose aspect ratio is wildly different from the request (a panorama for a 16:9 slot) would still be
     * huge after "cover" scaling, so the result is additionally capped at 4× the requested pixel count.
     */
    fun coverScale(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Float {
        if (srcWidth <= 0 || srcHeight <= 0 || reqWidth <= 0 || reqHeight <= 0) return 1f
        var scale = min(1f, max(reqWidth / srcWidth.toFloat(), reqHeight / srcHeight.toFloat()))
        val budget = COVER_PIXEL_BUDGET * reqWidth * reqHeight
        val pixels = srcWidth.toDouble() * srcHeight * scale * scale
        if (pixels > budget) scale = sqrt(budget / (srcWidth.toDouble() * srcHeight)).toFloat()
        return scale
    }

    /**
     * `BitmapFactory.Options.inSampleSize` for a wanted [scale]: the largest power of two that does not shrink the
     * image below the target, so the decoder throws away as many pixels as possible before they reach the heap.
     */
    fun sampleSize(scale: Float): Int {
        if (!(scale > 0f) || scale >= 1f) return 1
        var sample = 1
        while (sample < (1 shl 12) && sample * 2 <= 1f / scale) sample *= 2
        return sample
    }

    /** Width of the largest 16:9 rectangle inside the requested box; the height is `width * 9 / 16`. */
    fun bannerWidth(reqWidth: Int, reqHeight: Int): Int = max(1, min(reqWidth, reqHeight * 16 / 9))

    // -----------------------------------------------------------------------------------------------------------------
    // Procedural artwork
    // -----------------------------------------------------------------------------------------------------------------

    /**
     * Dark diagonal gradient used when a wallpaper is missing, so the backdrop is never a blank rectangle. The hue
     * is derived from [seed] (same id → same picture). A ±½-level ordered noise breaks up the banding that a smooth
     * dark gradient would otherwise show on a TV panel.
     */
    fun fallbackGradient(width: Int, height: Int, seed: Int): IntArray {
        if (width <= 0 || height <= 0) return IntArray(0)
        val hue = (((seed % 360) + 360) % 360).toFloat()
        val from = hslToArgb(hue, 0.42f, 0.20f)
        val to = hslToArgb(hue + 38f, 0.55f, 0.045f)
        val pixels = IntArray(width * height)
        val maxX = max(1, width - 1).toFloat()
        val maxY = max(1, height - 1).toFloat()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val t = (x / maxX + y / maxY) / 2f
                val noise = ((((x * 73856093) xor (y * 19349663)) ushr 4) and 0xFF) / 255f - 0.5f
                pixels[y * width + x] = (0xFF shl 24) or
                    (mix(from shr 16, to shr 16, t, noise) shl 16) or
                    (mix(from shr 8, to shr 8, t, noise) shl 8) or
                    mix(from, to, t, noise)
            }
        }
        return pixels
    }

    private fun mix(from: Int, to: Int, t: Float, noise: Float): Int {
        val a = from and 0xFF
        val b = to and 0xFF
        return (a + (b - a) * t + noise).roundToInt().coerceIn(0, 0xFF)
    }
}
