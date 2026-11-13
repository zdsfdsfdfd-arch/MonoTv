package tv.monotv.launcher.core

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decoded artwork ready to draw.
 *
 * @param image   bitmap already downsampled to roughly the requested size
 * @param accent  dominant, UI-friendly colour of the image as ARGB (used for glows / ambient tint)
 * @param isBanner true when [image] is a full-bleed 16:9 picture; false when it is a square icon that the UI
 *                 should centre on a generated tile.
 */
@Immutable
class Artwork(val image: ImageBitmap, val accent: Int, val isBanner: Boolean)

/**
 * The only way UI gets pixels. Implementations must:
 *  - decode off the main thread, downsample to the requested size, and keep a bounded in-memory cache;
 *  - never touch the network;
 *  - return null (not throw) when artwork is missing.
 *
 * `cached` is a synchronous memory-cache peek so already-loaded art shows on the first frame (no flicker when
 * scrolling back). Sizes are in pixels.
 */
interface ArtworkLoader {
    fun cached(source: ArtSource, widthPx: Int, heightPx: Int): Artwork?

    suspend fun load(source: ArtSource, widthPx: Int, heightPx: Int): Artwork?

    /**
     * A heavily blurred, very small version of the art (for frosted-glass panels and ambient light). Computed once
     * per source and cached; drawing it stretched is free at runtime — no per-frame blur anywhere in the UI.
     */
    suspend fun loadBlurred(source: ArtSource): Artwork?

    fun cachedBlurred(source: ArtSource): Artwork?
}

val LocalArtworkLoader = staticCompositionLocalOf<ArtworkLoader> {
    error("ArtworkLoader not provided")
}
