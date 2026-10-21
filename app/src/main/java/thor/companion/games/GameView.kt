package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import thor.companion.Theme

/**
 * What every game's board has in common: it keeps its drags for itself (the page around it
 * would scroll), counts play time only while it is on screen, and tells the page when
 * something changed ([onChange]: the header line and the save).
 */
abstract class GameView(context: Context) : View(context) {
    var onChange: () -> Unit = {}
    protected val d = context.resources.displayMetrics.density
    protected val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Theme.body(context); textAlign = Paint.Align.CENTER }
    protected val heading = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Theme.heading(context); textAlign = Paint.Align.CENTER }
    protected val r = RectF()

    /** The one-line summary for the header and the Continue card. */
    abstract fun line(): String
    /** The game as text, kept between flights. */
    abstract fun save(): String
    /** Whether a clock runs now (counted in [addTime] while shown). */
    protected open fun clockRuns() = false
    protected open fun addTime(ms: Long) {}

    private var tickedAt = 0L
    private val tick = object : Runnable {
        override fun run() {
            val now = SystemClock.uptimeMillis()
            if (clockRuns() && tickedAt != 0L) { addTime(now - tickedAt); onChange() }
            tickedAt = now
            if (isAttachedToWindow && windowVisibility == VISIBLE) postDelayed(this, 1000)
            else tickedAt = 0L
        }
    }

    /** Starts the clock again (after a tap that started the game, or coming back to it). */
    protected fun startClock() {
        removeCallbacks(tick)
        tickedAt = SystemClock.uptimeMillis()
        if (isAttachedToWindow && windowVisibility == VISIBLE) postDelayed(tick, 1000)
    }

    /** Starts the clock again after [pause] (a new game). */
    fun resume() = startClock()

    /** Stops the clock and counts the time since its last tick. */
    fun pause() {
        removeCallbacks(tick)
        if (tickedAt != 0L && clockRuns()) addTime(SystemClock.uptimeMillis() - tickedAt)
        tickedAt = 0L
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); startClock() }
    override fun onDetachedFromWindow() { pause(); onChange(); super.onDetachedFromWindow() }
    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) startClock() else { pause(); onChange() }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
        return super.dispatchTouchEvent(event)
    }

    /** A dark veil over the board with a title and a line, for the end of a round. */
    protected fun veil(canvas: Canvas, title: String, detail: String) {
        paint.color = Theme.withAlpha(Theme.SURFACE, 215)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        heading.color = Theme.GOLD_TEXT
        heading.textSize = 26 * d
        canvas.drawText(title, width / 2f, height / 2f - 6 * d, heading)
        text.color = Theme.TEXT
        text.textSize = 15 * d
        canvas.drawText(detail, width / 2f, height / 2f + 22 * d, text)
    }

    companion object {
        fun clock(ms: Long): String { val s = ms / 1000; return "%d:%02d".format(s / 60, s % 60) }
        fun number(n: Int): String = "%,d".format(n).replace(',', ' ')
    }
}
