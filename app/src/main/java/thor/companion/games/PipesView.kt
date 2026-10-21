package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.SystemClock
import android.view.MotionEvent
import thor.companion.Theme

/**
 * Pipe Connect's board: tap a tile to turn it a quarter. Pipes the light reaches from the ∞
 * well glow blue, and lamps (the ends) light up green. A short note beside the board says how.
 */
class PipesView(context: Context, var game: Pipes) : GameView(context) {
    /** The tile turning now and when it began (drawn turning from its old angle). */
    private var turning = -1
    private var turnedAt = 0L

    override fun line(): String {
        val lit = game.lit().count { it }
        return if (game.won) "All lit · ${game.moves} turns · ${clock(game.millis)}"
            else "$lit of ${game.size * game.size} lit · ${clock(game.millis)}"
    }
    override fun save() = game.save()
    override fun clockRuns() = !game.won && game.moves > 0
    override fun addTime(ms: Long) { game.millis += ms }

    private val board get() = minOf(height.toFloat(), width * 0.66f)
    private val note get() = minOf(width - board - 24 * d, 220 * d)
    private val bx get() = (width - board - 24 * d - note) / 2f
    private val by get() = (height - board) / 2f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP || game.won) return true
        val c = board / game.size
        val x = ((e.x - bx) / c).toInt(); val y = ((e.y - by) / c).toInt()
        if (e.x < bx || e.y < by || x !in 0 until game.size || y !in 0 until game.size) return true
        val first = game.moves == 0
        game.turn(y * game.size + x)
        turning = y * game.size + x; turnedAt = SystemClock.uptimeMillis()
        if (first) startClock()
        if (game.won) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        onChange(); invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val n = game.size; val c = board / n; val ox = bx; val oy = by
        r.set(ox, oy, ox + board, oy + board)
        paint.color = Theme.SURFACE
        canvas.drawRoundRect(r, 10 * d, 10 * d, paint)
        val lit = game.lit()
        val t = SystemClock.uptimeMillis() - turnedAt
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until n * n) {
            val x = ox + (i % n) * c; val y = oy + (i / n) * c
            r.set(x + c * 0.04f, y + c * 0.04f, x + c * 0.96f, y + c * 0.96f)
            paint.color = Color.rgb(19, 47, 74)
            canvas.drawRoundRect(r, c * 0.12f, c * 0.12f, paint)
            val cx = x + c / 2; val cy = y + c / 2
            canvas.save()
            if (i == turning && t < TURN_MS) {
                canvas.rotate(-90f * (1 - t / TURN_MS.toFloat()), cx, cy)
                postInvalidateOnAnimation()
            }
            val on = lit[i]
            paint.color = if (on) Theme.TEAL else Color.rgb(93, 118, 144)
            paint.strokeWidth = c * 0.2f
            val m = game.pipes[i]
            for (side in 0 until 4) if (m and (1 shl side) != 0) {
                val ex = cx + when (side) { 1 -> c * 0.46f; 3 -> -c * 0.46f; else -> 0f }
                val ey = cy + when (side) { 0 -> -c * 0.46f; 2 -> c * 0.46f; else -> 0f }
                canvas.drawLine(cx, cy, ex, ey, paint)
            }
            canvas.drawCircle(cx, cy, c * 0.12f, paint)
            if (Integer.bitCount(m) == 1 && i != game.well) {
                paint.color = if (on) Theme.GOOD else Color.rgb(45, 80, 112)
                canvas.drawCircle(cx, cy, c * 0.23f, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = c * 0.04f
                paint.color = if (on) Theme.GOLD_TEXT else Theme.DIM
                canvas.drawCircle(cx, cy, c * 0.23f, paint)
                paint.style = Paint.Style.FILL
            }
            canvas.restore()
            if (i == game.well) {
                paint.color = Theme.ACCENT
                canvas.drawCircle(cx, cy, c * 0.33f, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = c * 0.05f; paint.color = Theme.GOLD_TEXT
                canvas.drawCircle(cx, cy, c * 0.33f, paint)
                paint.style = Paint.Style.FILL
                text.color = Theme.BG; text.textSize = c * 0.38f
                canvas.drawText("∞", cx, cy - (text.descent() + text.ascent()) / 2, text)
            }
        }
        paint.strokeCap = Paint.Cap.BUTT
        // How to play, beside the board.
        if (note > 80 * d) {
            text.textAlign = Paint.Align.LEFT; text.textSize = 13.5f * d; text.color = Theme.DIM
            val nx = ox + board + 24 * d
            var ny = oy + board / 2 - 30 * d
            for (l in listOf("Tap a tile to turn it.", "", "Lead the light from the", "∞ well to every lamp.")) { canvas.drawText(l, nx, ny, text); ny += 20 * d }
            text.textAlign = Paint.Align.CENTER
        }
        if (game.won) veil(canvas, "All lamps lit!", "${game.moves} turns · ${clock(game.millis)} · tap New game")
    }

    companion object { const val TURN_MS = 120L }
}
