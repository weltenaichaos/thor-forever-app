package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import thor.companion.Theme

/**
 * What the action games share: a world of [WORLD_W] x [WORLD_H] drawn scaled to fit, a frame
 * loop that runs only while the board is on screen and not paused, and a Paused veil (tap it
 * to go on). Leaving the page or the screen going off pauses the game.
 */
abstract class ActionView(context: Context) : GameView(context) {
    var paused = false
        set(v) { field = v; lastFrame = 0L; onChange(); invalidate() }
    private var lastFrame = 0L

    /** The world's scale and its top left on screen. */
    protected var sc = 1f
    protected var ox = 0f
    protected var oy = 0f

    /** Moves the world on by [dt] seconds. */
    protected abstract fun step(dt: Float)
    /** Draws the world in world units (the canvas is already scaled). */
    protected abstract fun drawWorld(canvas: Canvas)
    /** Whether the world moves on its own now (not waiting for a first tap, not over). */
    protected abstract fun moving(): Boolean
    /** A touch in world units, while not paused. */
    protected abstract fun touch(action: Int, x: Float, y: Float)

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE && moving()) paused = true
    }

    override fun onDetachedFromWindow() { if (moving()) paused = true; super.onDetachedFromWindow() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (paused) {
            if (e.actionMasked == MotionEvent.ACTION_UP) paused = false
            return true
        }
        touch(e.actionMasked, (e.x - ox) / sc, (e.y - oy) / sc)
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        sc = minOf(width / WORLD_W, height / WORLD_H)
        ox = (width - WORLD_W * sc) / 2f
        oy = (height - WORLD_H * sc) / 2f
        val now = SystemClock.uptimeMillis()
        if (!paused && moving()) {
            if (lastFrame != 0L) {
                // Small steps keep fast balls from passing through things; a long gap (a hiccup) counts as 50 ms.
                var dt = minOf((now - lastFrame) / 1000f, 0.05f)
                while (dt > 0f) { val s = minOf(dt, 1 / 240f); step(s); dt -= s }
            }
            lastFrame = now
            postInvalidateOnAnimation()
        } else lastFrame = 0L
        canvas.save()
        canvas.clipRect(ox, oy, ox + WORLD_W * sc, oy + WORLD_H * sc)
        canvas.translate(ox, oy)
        canvas.scale(sc, sc)
        drawWorld(canvas)
        canvas.restore()
        if (paused) veil(canvas, "Paused", "Tap to go on")
    }

    /** A line of help in the world's lower part (world units). */
    protected fun hint(canvas: Canvas, words: String, y: Float = WORLD_H - 22f) {
        text.textSize = 19f
        text.style = android.graphics.Paint.Style.STROKE; text.strokeWidth = 4f; text.color = Theme.SURFACE
        canvas.drawText(words, WORLD_W / 2, y, text)
        text.style = android.graphics.Paint.Style.FILL; text.color = Theme.GOLD_TEXT
        canvas.drawText(words, WORLD_W / 2, y, text)
    }

    /** Big words in the middle of the world. */
    protected fun banner(canvas: Canvas, words: String, detail: String? = null) {
        heading.textSize = 44f
        heading.style = android.graphics.Paint.Style.STROKE; heading.strokeWidth = 6f; heading.color = Theme.SURFACE
        canvas.drawText(words, WORLD_W / 2, WORLD_H / 2 - 8, heading)
        heading.style = android.graphics.Paint.Style.FILL; heading.color = Theme.GOLD
        canvas.drawText(words, WORLD_W / 2, WORLD_H / 2 - 8, heading)
        if (detail != null) hint(canvas, detail, WORLD_H / 2 + 34)
    }

    override fun save() = ""

    companion object {
        const val WORLD_W = 1000f
        const val WORLD_H = 620f
    }
}
