package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import thor.companion.Theme

/**
 * Memory Pairs' table: tap a card to turn it. Two different cards show for a moment, then
 * turn back (or at once, with the next tap). Pairs stay up, faded. The backs carry our ∞.
 */
class MemoryView(context: Context, var game: Memory) : GameView(context) {
    private val path = Path()
    /** The card turning up now, and when. */
    private var flipping = -1
    private var flippedAt = 0L
    private val hideLater = Runnable { if (game.missed) { game.hide(); invalidate() } }

    override fun line() = if (game.won) "All pairs · ${game.turns} turns · ${clock(game.millis)}" else "${game.found} of ${game.pairs} pairs · ${game.turns} turns"
    override fun save() = game.save()
    override fun clockRuns() = !game.won && (game.turns > 0 || game.open.isNotEmpty())
    override fun addTime(ms: Long) { game.millis += ms }

    private val gap get() = 8 * d
    /** Cards are a bit taller than wide. */
    private val cardW get() = minOf((width - gap * (game.cols - 1)) / game.cols.toFloat(), ((height - gap * (game.rows - 1)) / game.rows.toFloat()) / 1.2f)
    private val cardH get() = cardW * 1.2f
    private val x0 get() = (width - (cardW * game.cols + gap * (game.cols - 1))) / 2f
    private val y0 get() = (height - (cardH * game.rows + gap * (game.rows - 1))) / 2f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP || game.won) return true
        val col = ((e.x - x0) / (cardW + gap)).toInt(); val row = ((e.y - y0) / (cardH + gap)).toInt()
        if (e.x < x0 || e.y < y0 || col !in 0 until game.cols || row !in 0 until game.rows) return true
        val i = row * game.cols + col
        removeCallbacks(hideLater)
        val wasRunning = clockRuns()
        if (game.flip(i)) {
            flipping = i; flippedAt = SystemClock.uptimeMillis()
            if (!wasRunning) startClock()
            if (game.missed) postDelayed(hideLater, SHOW_MS)
            if (game.won) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onChange()
        }
        invalidate()
        return true
    }

    override fun onDetachedFromWindow() { removeCallbacks(hideLater); game.hide(); super.onDetachedFromWindow() }

    override fun onDraw(canvas: Canvas) {
        val w = cardW; val h = cardH
        val t = SystemClock.uptimeMillis() - flippedAt
        for (i in game.faces.indices) {
            val x = x0 + (i % game.cols) * (w + gap); val y = y0 + (i / game.cols) * (h + gap)
            val up = game.matched[i] || i in game.open
            // Turning over: the card narrows to an edge and opens again showing its face.
            var squeeze = 1f
            var showFace = up
            if (i == flipping && t < FLIP_MS) {
                val f = t / FLIP_MS.toFloat()
                squeeze = Math.abs(1 - 2 * f)
                showFace = up && f > 0.5f
                postInvalidateOnAnimation()
            }
            canvas.save()
            canvas.scale(maxOf(squeeze, 0.04f), 1f, x + w / 2, y + h / 2)
            r.set(x, y, x + w, y + h)
            if (showFace) {
                val done = game.matched[i]
                paint.color = if (done) Color.rgb(246, 236, 212) else Color.rgb(255, 231, 160)
                paint.alpha = if (done) 90 else 255
                canvas.drawRoundRect(r, w * 0.1f, w * 0.1f, paint)
                if (!done) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f * d; paint.color = Theme.GOLD
                    canvas.drawRoundRect(r, w * 0.1f, w * 0.1f, paint)
                    paint.style = Paint.Style.FILL
                }
                Symbols.draw(canvas, paint, path, game.faces[i], r.centerX(), r.centerY(), w * 0.78f, if (done) 115 else 255)
                paint.alpha = 255
            } else {
                paint.color = Color.rgb(31, 76, 120)
                canvas.drawRoundRect(r, w * 0.1f, w * 0.1f, paint)
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = Theme.ACCENT
                canvas.drawRoundRect(r, w * 0.1f, w * 0.1f, paint)
                paint.alpha = 150; paint.strokeWidth = 1 * d
                r.inset(w * 0.08f, w * 0.08f)
                canvas.drawRoundRect(r, w * 0.06f, w * 0.06f, paint)
                paint.alpha = 255; paint.style = Paint.Style.FILL
                text.color = Theme.ACCENT; text.textSize = w * 0.42f
                canvas.drawText("∞", x + w / 2, y + h / 2 - (text.descent() + text.ascent()) / 2, text)
            }
            canvas.restore()
        }
        if (game.won) veil(canvas, "All pairs found!", "${game.turns} turns · ${clock(game.millis)} · tap New game")
    }

    companion object {
        const val FLIP_MS = 180L
        const val SHOW_MS = 900L
    }
}
