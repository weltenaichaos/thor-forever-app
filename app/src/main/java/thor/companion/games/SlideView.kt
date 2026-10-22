package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import thor.companion.Theme

/**
 * The slide puzzle's board: tap a tile in the gap's row or column and it slides (with the
 * tiles between). Tiles in their own place turn gold. A short note beside the board says how.
 */
class SlideView(context: Context, var game: SlidePuzzle) : GameView(context) {
    /** Where each number was before the last slide, and when it slid. */
    private var before = IntArray(0)
    private var slidAt = 0L

    override fun line() = if (game.won) "Solved · ${game.moves} moves · ${clock(game.millis)}" else "Moves ${game.moves} · ${clock(game.millis)}"
    override fun save() = game.save()
    override fun clockRuns() = !game.won && game.moves > 0
    override fun addTime(ms: Long) { game.millis += ms }

    private val board get() = minOf(height.toFloat(), width * 0.62f)
    private val note get() = minOf(width - board - 24 * d, 220 * d)
    private val bx get() = (width - board - 24 * d - note) / 2f
    private val by get() = (height - board) / 2f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP || game.won) return true
        val pad = board * 0.015f
        val c = (board - 2 * pad) / game.size
        val x = ((e.x - bx - pad) / c).toInt(); val y = ((e.y - by - pad) / c).toInt()
        if (e.x < bx || e.y < by || x !in 0 until game.size || y !in 0 until game.size) return true
        val was = game.tiles.copyOf()
        val first = game.moves == 0
        if (game.tap(y * game.size + x)) {
            before = was; slidAt = SystemClock.uptimeMillis()
            if (first) startClock()
            if (game.won) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onChange(); invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val n = game.size; val ox = bx; val oy = by
        r.set(ox, oy, ox + board, oy + board)
        paint.color = Theme.SURFACE
        canvas.drawRoundRect(r, 12 * d, 12 * d, paint)
        val pad = board * 0.015f
        val c = (board - 2 * pad) / n
        val t = SystemClock.uptimeMillis() - slidAt
        val f = if (before.size == game.tiles.size && t < SLIDE_MS) { postInvalidateOnAnimation(); 1 - (1 - t / SLIDE_MS.toFloat()).let { it * it } } else 1f
        for (i in game.tiles.indices) {
            val v = game.tiles[i]
            if (v == 0) continue
            var cx = (i % n).toFloat(); var cy = (i / n).toFloat()
            if (f < 1f) {
                val from = before.indexOf(v)
                cx = from % n + (cx - from % n) * f; cy = from / n + (cy - from / n) * f
            }
            val x = ox + pad + cx * c; val y = oy + pad + cy * c
            r.set(x + c * 0.03f, y + c * 0.03f, x + c * 0.97f, y + c * 0.97f)
            paint.color = if (game.home(i)) Color.rgb(232, 210, 150) else Color.rgb(200, 205, 212)
            canvas.drawRoundRect(r, c * 0.1f, c * 0.1f, paint)
            paint.color = Color.WHITE; paint.alpha = 64
            canvas.drawRoundRect(r.left, r.top, r.right, r.top + c * 0.14f, c * 0.08f, c * 0.08f, paint)
            paint.alpha = 255
            heading.color = Color.rgb(30, 22, 12); heading.textSize = c * 0.42f
            canvas.drawText("$v", r.centerX(), r.centerY() - (heading.descent() + heading.ascent()) / 2, heading)
        }
        if (note > 80 * d) {
            text.textAlign = Paint.Align.LEFT; text.textSize = 13.5f * d; text.color = Theme.DIM
            val nx = ox + board + 24 * d
            var ny = oy + board / 2 - 40 * d
            for (l in listOf("Tap a tile next to the gap", "(or a row of them) to slide it.", "", "Put the numbers in order.", "Tiles in their place turn gold.")) { canvas.drawText(l, nx, ny, text); ny += 20 * d }
            text.textAlign = Paint.Align.CENTER
        }
        if (game.won) veil(canvas, "Solved!", "${game.moves} moves · ${clock(game.millis)} · tap New game")
    }

    companion object { const val SLIDE_MS = 110L }
}
