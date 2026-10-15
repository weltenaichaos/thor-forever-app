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
 * PixelCopy), and keeps only the top rows, where the addon's data square is. No
 * screenshot of the whole screen and no root.
 */
class TopScreen(@Suppress("UNUSED_PARAMETER") context: Context) {

    /** Kept so the companion's screen switch still compiles; the game's view is always the one read. */
    @Volatile var displayId: String? = null

    private val handler = Handler(HandlerThread("companion-copy").apply { start() }.looper)

    /** The top [rows] rows of the game's picture, or null when it isn't showing. */
    fun capture(rows: Int = StripDecoder.SCAN_ROWS): Pixels? {
        val view = CompanionBridge.view ?: return null
        val holder = view.holder ?: return null
        if (holder.surface?.isValid != true) return null
        val frame = holder.surfaceFrame
        val w = frame.width()
        val h = minOf(rows, frame.height())
        if (w <= 0 || h <= 0) return null
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var result = PixelCopy.ERROR_UNKNOWN
        try {
            PixelCopy.request(view, Rect(0, 0, w, h), bitmap, { r -> result = r; done.countDown() }, handler)
        } catch (e: IllegalArgumentException) {
            // The surface went away between the check and the copy.
            bitmap.recycle()
            return null
        }
        if (!done.await(1, TimeUnit.SECONDS) || result != PixelCopy.SUCCESS) {
            bitmap.recycle()
            return null
        }
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        bitmap.recycle()
        return ArgbPixels(w, h, pixels)
    }

    /** No display list is needed: the game's view is read directly. */
    fun listDisplays(): List<String> = emptyList()

    private class ArgbPixels(override val width: Int, override val height: Int, private val data: IntArray) : Pixels {
        override fun rgb(x: Int, y: Int): Int = data[y * width + x] and 0xFFFFFF
    }
}
