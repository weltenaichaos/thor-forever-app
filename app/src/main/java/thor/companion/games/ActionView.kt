package thor.companion.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
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
        drawEffects(canvas, now)
        // A soft dark edge, then the gold frame.
        if (vignette == null || vignetteFor != sc) {
            vignetteFor = sc
            vignette = RadialGradient(WORLD_W / 2, WORLD_H * 0.45f, WORLD_W * 0.62f, intArrayOf(0, 0, Color.argb(130, 0, 0, 0)),
                floatArrayOf(0f, 0.62f, 1f), Shader.TileMode.CLAMP)
        }
        fx.shader = vignette
        canvas.drawRect(0f, 0f, WORLD_W, WORLD_H, fx)
        fx.shader = null
        canvas.restore()
        frame.style = Paint.Style.STROKE
        frame.color = Theme.ACCENT; frame.strokeWidth = 2.5f * d
        r.set(ox + 1.5f * d, oy + 1.5f * d, ox + WORLD_W * sc - 1.5f * d, oy + WORLD_H * sc - 1.5f * d)
        canvas.drawRoundRect(r, 10 * d, 10 * d, frame)
        frame.alpha = 110; frame.strokeWidth = 1 * d
        r.inset(4 * d, 4 * d)
        canvas.drawRoundRect(r, 7 * d, 7 * d, frame)
        frame.alpha = 255
        if (paused) veil(canvas, "Paused", "Tap to go on")
    }

    // ---- Effects: points popping up, bits flying, glowing orbs (all in world units) ----

    private class Popup(val words: String, val x: Float, val y: Float, val colour: Int, val at: Long)
    private class Bit(var x: Float, var y: Float, val vx: Float, val vy: Float, val colour: Int, val at: Long, val spin: Float)
    private val popups = mutableListOf<Popup>()
    private val bits = mutableListOf<Bit>()
    private val fx = Paint(Paint.ANTI_ALIAS_FLAG)
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG)
    private var vignette: Shader? = null
    private var vignetteFor = 0f
    private val dst = RectF()
    private val sprites = HashMap<String, Bitmap>()
    private var spritesFor = 0f

    /** "+20" rising from [x], [y] and fading. */
    protected fun popup(words: String, x: Float, y: Float, colour: Int = Theme.GOLD) {
        popups += Popup(words, x, y, colour, SystemClock.uptimeMillis())
        if (popups.size > 12) popups.removeAt(0)
    }

    /** [count] little pieces flying out from [x], [y]. */
    protected fun burst(x: Float, y: Float, colour: Int, count: Int = 10) {
        val now = SystemClock.uptimeMillis()
        for (k in 0 until count) {
            val a = k * (2 * Math.PI / count) + Math.random() * 0.5
            val v = 90 + Math.random() * 140
            bits += Bit(x, y, (Math.cos(a) * v).toFloat(), (Math.sin(a) * v - 60).toFloat(), colour, now, (Math.random() * 360).toFloat())
        }
        if (bits.size > 120) bits.subList(0, bits.size - 120).clear()
    }

    private fun drawEffects(canvas: Canvas, now: Long) {
        var any = false
        val pi = popups.iterator()
        while (pi.hasNext()) {
            val p = pi.next(); val t = (now - p.at) / 700f
            if (t >= 1f) { pi.remove(); continue }
            any = true
            heading.textSize = 24f
            heading.style = Paint.Style.STROKE; heading.strokeWidth = 4f; heading.color = Theme.SURFACE; heading.alpha = (255 * (1 - t)).toInt()
            canvas.drawText(p.words, p.x, p.y - 30 * t, heading)
            heading.style = Paint.Style.FILL; heading.color = p.colour; heading.alpha = (255 * (1 - t)).toInt()
            canvas.drawText(p.words, p.x, p.y - 30 * t, heading)
            heading.alpha = 255
        }
        val bi = bits.iterator()
        while (bi.hasNext()) {
            val b = bi.next(); val t = (now - b.at) / 550f
            if (t >= 1f) { bi.remove(); continue }
            any = true
            val sec = t * 0.55f
            val x = b.x + b.vx * sec; val y = b.y + b.vy * sec + 400 * sec * sec
            fx.color = b.colour; fx.alpha = (230 * (1 - t)).toInt()
            canvas.save(); canvas.rotate(b.spin + t * 300, x, y)
            canvas.drawRect(x - 4, y - 4, x + 4, y + 4, fx)
            canvas.restore()
        }
        fx.alpha = 255
        if (any) postInvalidateOnAnimation()
    }

    /**
     * A shiny ball of [radius] world units centred on [x], [y]: light top left, [colour] in the
     * middle, [dark] at the edge. Drawn from a small picture made once per size and colour.
     */
    protected fun orb(canvas: Canvas, x: Float, y: Float, radius: Float, light: Int, colour: Int, dark: Int) {
        val bmp = sprite("orb$radius/$light/$colour/$dark", radius) { c, px ->
            fx.shader = RadialGradient(px * 0.7f, px * 0.6f, px, intArrayOf(light, colour, dark), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
            c.drawCircle(px, px, px, fx); fx.shader = null
            fx.color = Color.WHITE; fx.alpha = 200
            c.drawOval(px * 0.5f, px * 0.45f, px * 0.9f, px * 0.72f, fx); fx.alpha = 255
        }
        dst.set(x - radius, y - radius, x + radius, y + radius)
        canvas.drawBitmap(bmp, null, dst, null)
    }

    /** A soft round glow of [radius] in [colour], fading out to its edge. */
    protected fun glow(canvas: Canvas, x: Float, y: Float, radius: Float, colour: Int, alpha: Int = 255) {
        val bmp = sprite("glow$radius/$colour", radius) { c, px ->
            fx.shader = RadialGradient(px, px, px, intArrayOf(Color.argb(200, Color.red(colour), Color.green(colour), Color.blue(colour)), 0),
                null, Shader.TileMode.CLAMP)
            c.drawCircle(px, px, px, fx); fx.shader = null
        }
        fx.alpha = alpha
        dst.set(x - radius, y - radius, x + radius, y + radius)
        canvas.drawBitmap(bmp, null, dst, fx)
        fx.alpha = 255
    }

    private fun sprite(key: String, radius: Float, paintIt: (Canvas, Float) -> Unit): Bitmap {
        if (spritesFor != sc) { sprites.values.forEach { it.recycle() }; sprites.clear(); spritesFor = sc }
        return sprites.getOrPut(key) {
            val px = maxOf(2f, radius * sc)
            val size = (px * 2).toInt() + 2
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { paintIt(Canvas(it), size / 2f) }
        }
    }

    /** A trail behind a moving ball: its last places, oldest first. */
    protected class Trail(private val length: Int = 7) {
        val xs = FloatArray(length); val ys = FloatArray(length)
        var count = 0
        fun add(x: Float, y: Float) {
            for (k in 0 until length - 1) { xs[k] = xs[k + 1]; ys[k] = ys[k + 1] }
            xs[length - 1] = x; ys[length - 1] = y
            count = minOf(count + 1, length)
        }
        fun clear() { count = 0 }
    }

    protected fun drawTrail(canvas: Canvas, t: Trail, radius: Float, colour: Int) {
        val n = t.xs.size
        for (k in n - t.count until n - 1) {
            val f = (k - (n - t.count) + 1f) / t.count
            fx.color = colour; fx.alpha = (110 * f).toInt()
            canvas.drawCircle(t.xs[k], t.ys[k], radius * (0.4f + 0.5f * f), fx)
        }
        fx.alpha = 255
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
