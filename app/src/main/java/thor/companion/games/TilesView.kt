package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import thor.companion.Theme

/** 2048's board: swipe anywhere on it. The tiles go from copper through silver to gold. */
class TilesView(context: Context, var game: Tiles2048) : GameView(context) {
    private var downX = 0f
    private var downY = 0f
    private var swiped = false

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
                    if (game.move(dir)) { onChange(); invalidate() }
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val x0 = (width - size) / 2f; val y0 = (height - size) / 2f
        r.set(x0, y0, x0 + size, y0 + size)
        paint.color = Theme.SURFACE
        canvas.drawRoundRect(r, 12 * d, 12 * d, paint)
        val gap = size * 0.025f
        val cell = (size - gap * 5) / 4
        for (i in game.cells.indices) {
            val x = x0 + gap + (i % 4) * (cell + gap); val y = y0 + gap + (i / 4) * (cell + gap)
            r.set(x, y, x + cell, y + cell)
            val v = game.cells[i]
            paint.color = if (v == 0) Color.rgb(19, 47, 74) else colour(v)
            canvas.drawRoundRect(r, 10 * d, 10 * d, paint)
            if (v == 0) continue
            heading.color = if (v >= 2048 || v in 8..64) Color.rgb(20, 24, 30) else Color.rgb(30, 22, 12)
            heading.textSize = cell * when { v < 100 -> 0.42f; v < 1000 -> 0.34f; else -> 0.27f }
            canvas.drawText("$v", x + cell / 2, y + cell / 2 - (heading.descent() + heading.ascent()) / 2, heading)
        }
        if (game.over) veil(canvas, "No more moves", "Score ${number(game.score)} · tap New game")
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
}
