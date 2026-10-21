package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import thor.companion.Theme

/**
 * Sudoku's board: the grid on the left, a number pad on the right. Tap a square, then a
 * number. With Notes on, the number goes in as a small pencil mark instead. Wrong numbers
 * show red. The row, column and box of the chosen square are lit, and so is every square
 * with the same number.
 */
class SudokuView(context: Context, var game: Sudoku) : GameView(context) {
    var notesMode = false
    private var selected = -1
    private val pad = Array(11) { RectF() }  // 0..8 the numbers, 9 Notes, 10 Erase

    override fun line() = if (game.won) "Solved · ${clock(game.millis)}"
        else "${Sudoku.LEVELS[game.level]} · mistakes ${game.mistakes} · ${clock(game.millis)}"
    override fun save() = game.save()
    override fun clockRuns() = !game.won
    override fun addTime(ms: Long) { game.millis += ms }

    private val gap get() = 14 * d
    private val grid get() = minOf(height.toFloat(), width * 0.6f)
    private val gx get() = maxOf(0f, (width - grid - gap - padWidth) / 2f)
    private val gy get() = (height - grid) / 2f
    private val padWidth get() = minOf(width - grid - gap, grid * 0.66f)

    private fun layoutPad() {
        val left = gx + grid + gap
        val sp = 7 * d
        val b = minOf((padWidth - 2 * sp) / 3, (grid - 3 * sp) / 3.55f)
        val top = gy
        for (n in 0 until 9) {
            val x = left + (n % 3) * (b + sp); val y = top + (n / 3) * (b + sp)
            pad[n].set(x, y, x + b, y + b)
        }
        val y = top + 3 * (b + sp)
        val w = (3 * b + 2 * sp - sp) / 2
        pad[9].set(left, y, left + w, y + b * 0.55f)
        pad[10].set(left + w + sp, y, left + 2 * w + sp, y + b * 0.55f)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP || game.won) return true
        val c = grid / 9
        val cx = ((e.x - gx) / c).toInt(); val cy = ((e.y - gy) / c).toInt()
        if (e.x >= gx && e.y >= gy && cx in 0 until 9 && cy in 0 until 9) {
            selected = cy * 9 + cx
            invalidate(); return true
        }
        layoutPad()
        val hit = pad.indexOfFirst { it.contains(e.x, e.y) }
        when {
            hit == 9 -> notesMode = !notesMode
            hit == 10 -> if (selected >= 0 && game.set(selected, 0)) onChange() else if (selected >= 0 && game.notes[selected] != 0 && !game.given[selected]) { game.notes[selected] = 0; onChange() }
            hit in 0..8 && selected >= 0 -> {
                val n = hit + 1
                val changed = if (notesMode) game.toggleNote(selected, n) else game.set(selected, n)
                if (changed) {
                    if (!notesMode && game.wrong(selected)) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                    onChange()
                }
            }
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val c = grid / 9; val ox = gx; val oy = gy
        r.set(ox, oy, ox + grid, oy + grid)
        paint.color = Theme.SURFACE
        canvas.drawRect(r, paint)
        val sel = selected
        val selValue = if (sel >= 0) game.values[sel] else 0
        val peers = if (sel >= 0) Sudoku.peers(sel) else emptySet()
        for (i in 0 until 81) {
            val x = ox + (i % 9) * c; val y = oy + (i / 9) * c
            val v = game.values[i]
            val fill = when {
                i == sel -> Theme.TEAL_DARK
                selValue != 0 && v == selValue -> Color.rgb(29, 74, 110)
                i in peers -> Color.rgb(19, 47, 74)
                else -> 0
            }
            if (fill != 0) { paint.color = fill; canvas.drawRect(x, y, x + c, y + c, paint) }
            if (v != 0) {
                val p = if (game.given[i]) heading else text
                p.color = when { game.wrong(i) -> Theme.BAD; game.given[i] -> Theme.GOLD_TEXT; else -> Color.rgb(110, 205, 240) }
                p.textSize = c * 0.62f
                canvas.drawText("$v", x + c / 2, y + c / 2 - (p.descent() + p.ascent()) / 2, p)
            } else if (game.notes[i] != 0) {
                text.color = Theme.DIM; text.textSize = c * 0.27f
                for (n in 1..9) if (game.notes[i] and (1 shl n) != 0) {
                    val nx = x + c * (0.2f + 0.3f * ((n - 1) % 3)); val ny = y + c * (0.2f + 0.3f * ((n - 1) / 3))
                    canvas.drawText("$n", nx, ny - (text.descent() + text.ascent()) / 2, text)
                }
            }
        }
        if (sel >= 0) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2 * d; paint.color = Theme.ACCENT
            val x = ox + (sel % 9) * c; val y = oy + (sel / 9) * c
            canvas.drawRect(x + d, y + d, x + c - d, y + c - d, paint)
            paint.style = Paint.Style.FILL
        }
        for (k in 0..9) {
            val thick = k % 3 == 0
            paint.color = if (thick) Theme.ACCENT else Theme.STROKE
            paint.strokeWidth = if (thick) 2.5f * d else 1 * d
            canvas.drawLine(ox + k * c, oy, ox + k * c, oy + grid, paint)
            canvas.drawLine(ox, oy + k * c, ox + grid, oy + k * c, paint)
        }

        layoutPad()
        for (n in 0 until 9) {
            val left = 9 - game.placed(n + 1)
            button(canvas, pad[n], on = false, dim = left == 0)
            val b = pad[n]
            heading.color = Theme.GOLD_TEXT; heading.textSize = b.height() * 0.42f
            if (left == 0) heading.alpha = 90
            canvas.drawText("${n + 1}", b.centerX(), b.centerY() + b.height() * 0.08f, heading)
            heading.alpha = 255
            text.color = Theme.DIM; text.textSize = b.height() * 0.14f
            canvas.drawText(if (left == 0) "done" else "$left left", b.centerX(), b.bottom - b.height() * 0.12f, text)
        }
        button(canvas, pad[9], on = notesMode, dim = false)
        button(canvas, pad[10], on = false, dim = false)
        heading.textSize = pad[9].height() * 0.36f
        for ((k, words) in listOf(if (notesMode) "✎ Notes on" else "✎ Notes", "Erase").withIndex()) {
            val b = pad[9 + k]
            heading.color = if (k == 0 && notesMode) Color.rgb(11, 34, 54) else Theme.GOLD_TEXT
            canvas.drawText(words, b.centerX(), b.centerY() - (heading.descent() + heading.ascent()) / 2, heading)
        }
        if (game.won) veil(canvas, "Solved!", "${clock(game.millis)} · ${game.mistakes} mistakes · tap New game")
    }

    private fun button(canvas: Canvas, b: RectF, on: Boolean, dim: Boolean) {
        val rad = 9 * d
        paint.color = if (on) Theme.TEAL else Theme.BUTTON_TOP
        if (dim) paint.alpha = 90
        canvas.drawRoundRect(b, rad, rad, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d
        paint.color = if (on) Theme.ACCENT else Theme.EDGE
        if (dim) paint.alpha = 90
        canvas.drawRoundRect(b, rad, rad, paint)
        paint.style = Paint.Style.FILL; paint.alpha = 255
    }
}
