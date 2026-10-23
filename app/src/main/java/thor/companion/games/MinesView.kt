package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.GestureDetector
import android.view.MotionEvent
import thor.companion.Theme

/** Minesweeper's field: tap opens a square, a long press (or tap in flag mode) puts a flag. */
class MinesView(context: Context, var field: MineField) : GameView(context) {
    /** Taps put flags instead of opening squares. */
    var flagMode = false

    override fun line() = if (field.lost) "Boom! · ${clock(field.millis)} · tap New game" else "💣 ${field.flagsLeft} left · ${clock(field.millis)}"
    override fun save() = field.save()
    override fun clockRuns() = field.laid && !field.over
    override fun addTime(ms: Long) { field.millis += ms }

    private val cell get() = minOf(width / field.width.toFloat(), height / field.height.toFloat())
    private val x0 get() = (width - cell * field.width) / 2f
    private val y0 get() = (height - cell * field.height) / 2f

    private fun at(x: Float, y: Float): Int {
        val cx = ((x - x0) / cell).toInt(); val cy = ((y - y0) / cell).toInt()
        if (x < x0 || y < y0 || cx !in 0 until field.width || cy !in 0 until field.height) return -1
        return cy * field.width + cx
    }

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val i = at(e.x, e.y)
            if (i < 0) return true
            val was = field.laid
            val changed = if (flagMode && !field.open[i]) field.toggleFlag(i) else field.reveal(i)
            if (changed) {
                if (!was && field.laid) startClock()
                onChange(); invalidate()
            }
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val i = at(e.x, e.y)
            if (i >= 0 && field.toggleFlag(i)) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                onChange(); invalidate()
            }
        }
    })

    override fun onTouchEvent(e: MotionEvent) = gestures.onTouchEvent(e)

    override fun onDraw(canvas: Canvas) {
        val c = cell; val ox = x0; val oy = y0
        val pad = maxOf(1f, c * 0.04f)
        for (i in 0 until field.width * field.height) {
            val x = ox + (i % field.width) * c; val y = oy + (i / field.width) * c
            r.set(x + pad, y + pad, x + c - pad, y + c - pad)
            val open = field.open[i] || (field.lost && field.mine[i])
            paint.color = when {
                i == field.boom -> Theme.BAD
                open -> Color.rgb(11, 29, 46)
                else -> Color.rgb(37, 70, 102)
            }
            canvas.drawRoundRect(r, c * 0.12f, c * 0.12f, paint)
            if (!open) {
                // A raised square: a lighter top edge.
                paint.color = Color.rgb(65, 110, 151)
                canvas.drawRect(r.left + c * 0.1f, r.top, r.right - c * 0.1f, r.top + pad * 1.5f, paint)
            }
            val cx = x + c / 2; val cy = y + c / 2
            when {
                field.flag[i] && !(field.lost && field.mine[i]) -> flag(canvas, cx, cy, c, wrong = field.lost && !field.mine[i])
                open && field.mine[i] -> mine(canvas, cx, cy, c)
                open -> {
                    val n = field.count(i)
                    if (n > 0) {
                        heading.color = NUMBER_COLOURS[n - 1]
                        heading.textSize = c * 0.58f
                        canvas.drawText("$n", cx, cy - (heading.descent() + heading.ascent()) / 2, heading)
                    }
                }
            }
        }
        if (field.won) veil(canvas, "Field cleared!", "${clock(field.millis)} · tap New game for another")
    }

    private fun flag(canvas: Canvas, cx: Float, cy: Float, c: Float, wrong: Boolean) {
        paint.color = Theme.GOLD_TEXT
        canvas.drawRect(cx - c * 0.05f, cy - c * 0.28f, cx + c * 0.01f, cy + c * 0.26f, paint)
        paint.color = if (wrong) Theme.DIM else Theme.BAD
        val p = android.graphics.Path().apply { moveTo(cx, cy - c * 0.3f); lineTo(cx + c * 0.28f, cy - c * 0.16f); lineTo(cx, cy - c * 0.02f); close() }
        canvas.drawPath(p, paint)
        paint.color = Theme.GOLD_TEXT
        canvas.drawRect(cx - c * 0.18f, cy + c * 0.22f, cx + c * 0.14f, cy + c * 0.28f, paint)
    }

    private fun mine(canvas: Canvas, cx: Float, cy: Float, c: Float) {
        paint.color = Color.rgb(15, 18, 22)
        paint.strokeWidth = c * 0.07f
        for (a in 0 until 4) {
            val ang = a * Math.PI / 4
            val dx = (Math.cos(ang) * c * 0.3).toFloat(); val dy = (Math.sin(ang) * c * 0.3).toFloat()
            canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, paint)
        }
        canvas.drawCircle(cx, cy, c * 0.2f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(cx - c * 0.07f, cy - c * 0.07f, c * 0.05f, paint)
    }

    companion object {
        val NUMBER_COLOURS = intArrayOf(Color.rgb(110, 180, 255), Color.rgb(90, 210, 120), Color.rgb(255, 110, 110),
            Color.rgb(176, 114, 232), Color.rgb(255, 170, 70), Color.rgb(52, 186, 215), Color.rgb(240, 240, 240), Color.rgb(160, 160, 160))
    }
}
