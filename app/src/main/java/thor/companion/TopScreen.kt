package thor.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.view.PixelCopy
import thor.companion.strip.Pixels
import thor.companion.strip.StripDecoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reads the game's picture straight from the view Thor Forever draws it into (Android's
 * PixelCopy), and keeps only the top rows, where the addon's data square is, or only the
 * corner where it was last seen. No screenshot of the whole screen and no root.
 */
class TopScreen(@Suppress("UNUSED_PARAMETER") context: Context) {

    private val thread = HandlerThread("companion-copy").apply { start() }
    private val handler = Handler(thread.looper)

    /** Ends the copier's thread (the app is closing). */
    fun close() {
        thread.quitSafely()
    }

    /** The top [rows] rows of the game's picture, or null when it isn't showing. */
    fun capture(rows: Int = StripDecoder.SCAN_ROWS): Pixels? = captureArea(Rect(0, 0, Int.MAX_VALUE, rows))?.pixels

    /** A part of the game's picture: where it starts in the picture, and its pixels. */
    class Shot(val left: Int, val top: Int, val pixels: Pixels)

    /** One bitmap kept for copies of the same size (the reading loop asks for the same corner each time). */
    private var kept: Bitmap? = null

    /**
     * Only [area] of the game's picture (cut to the picture's size), or null when it isn't
     * showing. The addon's bar is small: copying just its corner is much cheaper than the
     * whole top of the screen.
     */
    @Synchronized
    fun captureArea(area: Rect): Shot? {
        val view = CompanionBridge.view ?: return null
        val holder = view.holder ?: return null
        if (holder.surface?.isValid != true) return null
        val frame = holder.surfaceFrame
        val src = Rect(maxOf(0, area.left), maxOf(0, area.top), minOf(area.right, frame.width()), minOf(area.bottom, frame.height()))
        val w = src.width()
        val h = src.height()
        if (w <= 0 || h <= 0) return null
        val bitmap = kept?.takeIf { it.width == w && it.height == h }
            ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { kept?.recycle(); kept = it }
        val done = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        try {
            PixelCopy.request(view, src, bitmap, { r -> result = r; done.countDown() }, handler)
        } catch (e: IllegalArgumentException) {
            // The surface went away between the check and the copy.
            return null
        }
        if (!done.await(1, TimeUnit.SECONDS)) {
            // The copy may still write into it: let it go rather than use it again.
            kept = null
            return null
        }
        if (result != PixelCopy.SUCCESS) return null
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        return Shot(src.left, src.top, ArgbPixels(w, h, pixels))
    }

    /** The game picture's size, or null when it isn't showing. */
    fun size(): Rect? {
        val holder = CompanionBridge.view?.holder ?: return null
        if (holder.surface?.isValid != true) return null
        return Rect(holder.surfaceFrame)
    }

    private class ArgbPixels(override val width: Int, override val height: Int, private val data: IntArray) : Pixels {
        override fun rgb(x: Int, y: Int): Int = data[y * width + x] and 0xFFFFFF
    }
}
