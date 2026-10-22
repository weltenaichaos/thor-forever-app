package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import thor.companion.Theme

/**
 * Nonogram's board: the numbers above and to the left, the squares below. Tap a square to
 * fill it, or drag along a row or column to fill several. In Mark mode the same touches put
 * an X (a square you know stays empty). Touching a filled square (or an X) empties it again.
 * A line whose numbers are met goes grey.
 */
class NonogramView(context: Context, var game: Nonogram) : GameView(context) {
    var markMode = false
    /** The drag: where it began, which way it is locked (0 none, 1 row, 2 column), and what it paints. */
    private var start = -1
    private var axis = 0
    private var paintWith = 0
    /** What the touched square held: an emptying drag empties only squares like it. */
    private var was = 0

    override fun line() = if (game.won) "Solved · ${clock(game.millis)}"
        else "${Nonogram.SIZE_NAMES[Nonogram.SIZES.indexOf(game.size).coerceAtLeast(0)]} · ${clock(game.millis)}"
    override fun save() = game.save()
    override fun clockRuns() = !game.won && game.marks.any { it != 0 }
    override fun addTime(ms: Long) { game.millis += ms }

    private val rowClues get() = game.rows.maxOf { it.size }.coerceAtLeast(1)
    private val colClues get() = game.cols.maxOf { it.size }.coerceAtLeast(1)
    private val cell get() = minOf(width / (game.size + rowClues * 0.62f), height / (game.size + colClues * 0.58f))
    private val left get() = (width - cell * (game.size + rowClues * 0.62f)) / 2f + cell * rowClues * 0.62f
    private val top get() = (height - cell * (game.size + colClues * 0.58f)) / 2f + cell * colClues * 0.58f

    private fun at(x: Float, y: Float): Int {
        val c = cell
        val cx = ((x - left) / c).toInt(); val cy = ((y - top) / c).toInt()
        if (x < left || y < top || cx !in 0 until game.size || cy !in 0 until game.size) return -1
        return cy * game.size + cx
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (game.won) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                start = at(e.x, e.y); axis = 0
                if (start >= 0) {
                    val want = if (markMode) 2 else 1
                    was = game.marks[start]
                    paintWith = if (was == want) 0 else want
                    put(start)
                }
            }
            MotionEvent.ACTION_MOVE -> if (start >= 0) {
                val n = game.size
                var i = at(e.x, e.y)
                if (i < 0) return true
                if (axis == 0 && i != start) axis = if (i / n == start / n) 1 else if (i % n == start % n) 2 else
                    (if (Math.abs(i % n - start % n) >= Math.abs(i / n - start / n)) 1 else 2)
                // Stay on the row or column the drag started along.
                i = if (axis == 1) (start / n) * n + i % n else if (axis == 2) (i / n) * n + start % n else i
                val a = minOf(i, start); val b = maxOf(i, start)
                val step = if (axis == 2) n else 1
                var k = a
                while (k <= b) { put(k); k += step }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> start = -1
        }
        return true
    }

    private fun put(i: Int) {
        val now = game.marks[i]
        if (now == paintWith) return
        // Filling or marking covers only empty squares; emptying clears only squares like the first one.
        if (i != start && now != (if (paintWith == 0) was else 0)) return
        val wasRunning = clockRuns()
        game.marks[i] = paintWith
        if (!wasRunning && clockRuns()) startClock()
        if (game.won) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onChange(); invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = game.size; val c = cell; val ox = left; val oy = top
        text.textSize = c * 0.5f
        val clueW = c * 0.62f; val clueH = c * 0.58f
        for (row in 0 until n) {
            text.color = if (game.rowDone(row)) Color.rgb(77, 106, 131) else Theme.GOLD_TEXT
            val clue = game.rows[row]
            for ((k, v) in clue.withIndex()) {
                val x = ox - clueW * (clue.size - k) + clueW * 0.45f
                canvas.drawText("$v", x, oy + row * c + c / 2 - (text.descent() + text.ascent()) / 2, text)
            }
        }
        for (col in 0 until n) {
            text.color = if (game.colDone(col)) Color.rgb(77, 106, 131) else Theme.GOLD_TEXT
            val clue = game.cols[col]
            for ((k, v) in clue.withIndex()) {
                val y = oy - clueH * (clue.size - k) + clueH * 0.5f
                canvas.drawText("$v", ox + col * c + c / 2, y - (text.descent() + text.ascent()) / 2, text)
            }
        }
        val pad = maxOf(1f, c * 0.04f)
        for (i in 0 until n * n) {
            val x = ox + (i % n) * c; val y = oy + (i / n) * c
            r.set(x + pad, y + pad, x + c - pad, y + c - pad)
            val m = game.marks[i]
            paint.color = if (m == 1 || game.won && game.picture[i]) Theme.TEAL else Color.rgb(19, 47, 74)
            canvas.drawRoundRect(r, c * 0.1f, c * 0.1f, paint)
            if (m == 2 && !game.won) {
                paint.color = Theme.DIM; paint.strokeWidth = c * 0.08f; paint.strokeCap = Paint.Cap.ROUND
                val k = c * 0.3f
                canvas.drawLine(x + k, y + k, x + c - k, y + c - k, paint)
                canvas.drawLine(x + c - k, y + k, x + k, y + c - k, paint)
            }
        }
        paint.color = Theme.ACCENT
        for (k in 0..n) if (k % 5 == 0 || k == n) {
            paint.strokeWidth = 1.5f * d
            canvas.drawLine(ox + k * c, oy, ox + k * c, oy + n * c, paint)
            canvas.drawLine(ox, oy + k * c, ox + n * c, oy + k * c, paint)
        }
        if (game.won) {
            heading.color = Theme.GOLD_TEXT; heading.textSize = 20 * d
            canvas.drawText("Solved!", ox - clueW * rowClues / 2, oy - clueH * colClues / 2 + 8 * d, heading)
        }
    }
}
