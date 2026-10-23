package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.view.MotionEvent
import thor.companion.Theme

/**
 * 2048's board: swipe anywhere on it. The tiles glide to their new squares, two that became
 * one pop, and the new tile fades in (Jesse, build 206: jumping tiles looked like mixing).
 * A swipe that moves nothing shakes the board. The tiles go from copper through silver to gold.
 */
class TilesView(context: Context, var game: Tiles2048) : GameView(context) {
    private var downX = 0f
    private var downY = 0f
    private var swiped = false
    /** When the last swipe's tiles started gliding, and when a swipe that moved nothing shook the board (0: none). */
    private var movedAt = 0L
    private var shookAt = 0L
    /** The board before the last swipe, drawn under the gliding tiles. */
    private var before = IntArray(16)

    override fun line() = "Score ${number(game.score)}"
    override fun save() = game.save()

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = e.x; downY = e.y; swiped = false }
            MotionEvent.ACTION_MOVE -> if (!swiped) {
                val dx = e.x - downX; val dy = e.y - downY
                if (maxOf(Math.abs(dx), Math.abs(dy)) > 28 * d) {
                    swiped = true
                    val dir = if (Math.abs(dx) > Math.abs(dy)) (if (dx > 0) Tiles2048.Dir.RIGHT else Tiles2048.Dir.LEFT)
                        else (if (dy > 0) Tiles2048.Dir.DOWN else Tiles2048.Dir.UP)
                    val was = game.cells.copyOf()
                    if (game.move(dir)) { before = was; movedAt = SystemClock.uptimeMillis(); shookAt = 0L; onChange() }
                    else if (!game.over) shookAt = SystemClock.uptimeMillis()
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> if (!swiped && game.score == 0) { shookAt = SystemClock.uptimeMillis(); invalidate() }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val size = minOf(width, height).toFloat()
        var x0 = (width - size) / 2f
        val y0 = (height - size) / 2f
        // A swipe that moved nothing: a short shake.
        val shake = now - shookAt
        if (shookAt != 0L && shake < SHAKE_MS) { x0 += (Math.sin(shake / 22.0) * 7 * d * (1 - shake / SHAKE_MS.toFloat())).toFloat(); postInvalidateOnAnimation() }
        r.set(x0, y0, x0 + size, y0 + size)
        paint.color = Theme.SURFACE
        canvas.drawRoundRect(r, 12 * d, 12 * d, paint)
        val gap = size * 0.025f
        val cell = (size - gap * 5) / 4
        fun cx(i: Int) = x0 + gap + (i % 4) * (cell + gap)
        fun cy(i: Int) = y0 + gap + (i / 4) * (cell + gap)
        for (i in 0 until 16) tile(canvas, 0, cx(i), cy(i), cell)

        val t = now - movedAt
        if (movedAt != 0L && t < SLIDE_MS) {
            // Gliding: every tile from its old square towards its new one.
            val f = ease(t / SLIDE_MS.toFloat())
            for (m in game.lastMoves) tile(canvas, m.value, cx(m.from) + (cx(m.to) - cx(m.from)) * f, cy(m.from) + (cy(m.to) - cy(m.from)) * f, cell)
            postInvalidateOnAnimation()
        } else {
            val after = t - SLIDE_MS
            val settling = movedAt != 0L && after < POP_MS
            for (i in game.cells.indices) {
                val v = game.cells[i]
                if (v == 0) continue
                var scale = 1f; var alpha = 255
                if (settling && i in game.lastMerged) scale = 1f + 0.14f * Math.sin(Math.PI * after / POP_MS).toFloat()
                if (settling && i == game.lastSpawn) { scale = 0.5f + 0.5f * after / POP_MS; alpha = (255 * after / POP_MS).toInt() }
                val grow = cell * (scale - 1) / 2
                tile(canvas, v, cx(i) - grow, cy(i) - grow, cell * scale, alpha)
            }
            if (settling) postInvalidateOnAnimation()
        }
        // Until the first swipe: how to play, in a box across the board's middle.
        if (game.score == 0 && !game.over) {
            text.textSize = 15 * d
            val words = "all tiles slide, two equal tiles become one"
            val half = maxOf(text.measureText(words) / 2 + 16 * d, size * 0.4f)
            r.set(width / 2f - half, y0 + size / 2 - 40 * d, width / 2f + half, y0 + size / 2 + 40 * d)
            paint.color = Theme.withAlpha(Theme.SURFACE, 230)
            canvas.drawRoundRect(r, 11 * d, 11 * d, paint)
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = Theme.ACCENT
            canvas.drawRoundRect(r, 11 * d, 11 * d, paint)
            paint.style = android.graphics.Paint.Style.FILL
            text.color = Theme.GOLD_TEXT
            canvas.drawText("Swipe up, down, left or right:", width / 2f, r.centerY() - 5 * d, text)
            text.color = Theme.TEXT
            canvas.drawText(words, width / 2f, r.centerY() + 19 * d, text)
        }
        if (game.over) veil(canvas, "No more moves", "Score ${number(game.score)} · tap New game")
    }

    private fun ease(f: Float) = 1 - (1 - f) * (1 - f)

    private fun tile(canvas: Canvas, v: Int, x: Float, y: Float, cell: Float, alpha: Int = 255) {
        r.set(x, y, x + cell, y + cell)
        paint.color = if (v == 0) Color.rgb(19, 47, 74) else colour(v)
        paint.alpha = alpha
        canvas.drawRoundRect(r, 10 * d, 10 * d, paint)
        paint.alpha = 255
        if (v == 0) return
        heading.color = Color.rgb(28, 22, 12)
        heading.alpha = alpha
        heading.textSize = cell * when { v < 100 -> 0.42f; v < 1000 -> 0.34f; else -> 0.27f }
        canvas.drawText("$v", x + cell / 2, y + cell / 2 - (heading.descent() + heading.ascent()) / 2, heading)
        heading.alpha = 255
    }

    /** Copper for the small tiles, then silver, then gold, and a bright gold from 2048. */
    private fun colour(v: Int): Int = when (v) {
        2 -> Color.rgb(222, 170, 130)
        4 -> Color.rgb(222, 140, 90)
        8 -> Color.rgb(200, 205, 212)
        16 -> Color.rgb(170, 182, 196)
        32 -> Color.rgb(140, 200, 226)
        64 -> Color.rgb(52, 186, 215)
        128 -> Color.rgb(241, 228, 194)
        256 -> Color.rgb(232, 210, 150)
        512 -> Color.rgb(222, 190, 110)
        1024 -> Color.rgb(212, 172, 80)
        else -> Color.rgb(255, 210, 90)
    }

    companion object {
        const val SLIDE_MS = 110L
        const val POP_MS = 140L
        const val SHAKE_MS = 260L
    }
}
