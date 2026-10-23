package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import thor.companion.Theme

/**
 * Word Search's board: the letters on the left, the words to find on the right. Drag from a
 * word's first letter to its last; the band follows the finger along straight lines only.
 * Found words keep a coloured band and are crossed off the list.
 */
class WordSearchView(context: Context, var game: WordSearch) : GameView(context) {
    private var from = -1
    private var to = -1
    /** Touched once: the clock runs from the first drag. */
    private var touched = false

    override fun line() = if (game.won) "All found · ${clock(game.millis)}" else "${game.foundCount} of ${game.words.size} found · ${clock(game.millis)}"
    override fun save() = game.save()
    override fun clockRuns() = !game.won && (touched || game.foundCount > 0 || game.millis > 0)
    override fun addTime(ms: Long) { game.millis += ms }

    private val board get() = minOf(height.toFloat(), width * 0.68f)
    private val cell get() = board / game.size
    private val bx get() = maxOf(0f, (width - board - 16 * d - listWidth) / 2f)
    private val by get() = (height - board) / 2f
    private val listWidth get() = minOf(width - board - 16 * d, 190 * d)

    private fun at(x: Float, y: Float): Int {
        val c = cell
        val cx = ((x - bx) / c).toInt().coerceIn(0, game.size - 1); val cy = ((y - by) / c).toInt().coerceIn(0, game.size - 1)
        return cy * game.size + cx
    }

    /** The square the drag reaches, kept on a straight line (across, down or diagonal) from [from]. */
    private fun snap(x: Float, y: Float): Int {
        val n = game.size
        val a = from; val b = at(x, y)
        val dx = b % n - a % n; val dy = b / n - a / n
        if (dx == 0 || dy == 0 || Math.abs(dx) == Math.abs(dy)) return b
        // Off a line: take the nearest of across, down and diagonal.
        return if (Math.abs(dx) > 2 * Math.abs(dy)) a + dx
            else if (Math.abs(dy) > 2 * Math.abs(dx)) a + dy * n
            else {
                val k = minOf(Math.abs(dx), Math.abs(dy))
                val ex = a % n + Integer.signum(dx) * k; val ey = a / n + Integer.signum(dy) * k
                ey * n + ex
            }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (game.won) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.x < bx || e.x > bx + board || e.y < by || e.y > by + board) return true
                from = at(e.x, e.y); to = from
                if (!touched) { touched = true; startClock() }
            }
            MotionEvent.ACTION_MOVE -> if (from >= 0) to = snap(e.x, e.y)
            MotionEvent.ACTION_UP -> {
                if (from >= 0 && to != from && game.select(from, to) != null) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onChange()
                }
                from = -1; to = -1
            }
            MotionEvent.ACTION_CANCEL -> { from = -1; to = -1 }
        }
        invalidate()
        return true
    }

    private fun band(canvas: Canvas, a: Int, b: Int, colour: Int, alpha: Int) {
        val n = game.size; val c = cell
        paint.color = colour; paint.alpha = alpha
        paint.strokeWidth = c * 0.78f; paint.strokeCap = Paint.Cap.ROUND
        canvas.drawLine(bx + (a % n + 0.5f) * c, by + (a / n + 0.5f) * c, bx + (b % n + 0.5f) * c, by + (b / n + 0.5f) * c, paint)
        paint.alpha = 255; paint.strokeCap = Paint.Cap.BUTT
    }

    override fun onDraw(canvas: Canvas) {
        val n = game.size; val c = cell
        r.set(bx, by, bx + board, by + board)
        paint.color = Theme.SURFACE
        canvas.drawRoundRect(r, 10 * d, 10 * d, paint)
        for ((k, w) in game.words.withIndex()) if (w.found) band(canvas, w.cell(0, n), w.cell(w.word.length - 1, n), BANDS[k % BANDS.size], 115)
        if (from >= 0) band(canvas, from, to, Theme.GOLD, 95)
        text.color = Theme.TEXT; text.textSize = c * 0.52f
        text.isFakeBoldText = true
        for (i in 0 until n * n) {
            canvas.drawText(game.letters[i].toString(), bx + (i % n + 0.5f) * c, by + (i / n + 0.5f) * c - (text.descent() + text.ascent()) / 2, text)
        }
        text.isFakeBoldText = false
        // The list.
        val lx = bx + board + 16 * d
        heading.textAlign = Paint.Align.LEFT
        heading.color = Theme.ACCENT; heading.textSize = 11.5f * d
        canvas.drawText("FIND · DRAG ACROSS", lx, by + 12 * d, heading)
        val rowH = minOf(24 * d, (board - 24 * d) / game.words.size)
        heading.textSize = minOf(15 * d, rowH * 0.7f)
        for ((k, w) in game.words.withIndex()) {
            val y = by + 24 * d + rowH * (k + 0.75f)
            heading.color = if (w.found) Color.rgb(77, 106, 131) else Theme.GOLD_TEXT
            canvas.drawText(w.word, lx, y, heading)
            if (w.found) {
                paint.color = Color.rgb(77, 106, 131); paint.strokeWidth = 1.5f * d
                canvas.drawLine(lx, y - heading.textSize * 0.33f, lx + heading.measureText(w.word), y - heading.textSize * 0.33f, paint)
            }
        }
        heading.textAlign = Paint.Align.CENTER
        if (game.won) veil(canvas, "All words found!", "${clock(game.millis)} · tap New game")
    }

    companion object {
        val BANDS = intArrayOf(Theme.TEAL, Theme.GOOD, Theme.ACCENT, Color.rgb(176, 114, 232), Theme.COPPER, Color.rgb(59, 127, 209))
    }
}
