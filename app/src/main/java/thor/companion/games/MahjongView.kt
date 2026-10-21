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
 * Mahjong's board: tiles in three layers. Tap a free tile, then another free one with the
 * same symbol, and both go. Blocked tiles are shaded. The symbols are our own: six shapes in
 * three colours (18 kinds, four of each).
 */
class MahjongView(context: Context, var game: Mahjong) : GameView(context) {
    private var selected = -1
    /** A pair to show (Hint, or the pair just taken, fading out) and when that began. */
    private var shown: Pair<Int, Int>? = null
    private var shownAt = 0L
    private var hinting = false
    private val path = Path()

    override fun line(): String {
        if (game.won) return "Cleared · ${clock(game.millis)}"
        val pairs = freePairs()
        return if (pairs == 0) "${game.left} left · no pairs, tap Shuffle" else "${game.left} left · $pairs pairs free"
    }
    override fun save() = game.save()
    override fun clockRuns() = !game.won && game.left < Mahjong.SPOTS.size
    override fun addTime(ms: Long) { game.millis += ms }

    private fun freePairs(): Int {
        val free = Mahjong.SPOTS.indices.filter { game.free(it) }.groupBy { game.faces[it] }
        return free.values.sumOf { it.size / 2 }
    }

    // Layout: 9 x 5 squares, each layer drawn a little up and to the left of the one below.
    private val tileW get() = minOf(width / 9.5f, height / (5 * RATIO + 0.5f))
    private val tileH get() = tileW * RATIO
    private val lift get() = tileW * 0.11f
    private val x0 get() = (width - tileW * 9) / 2f + lift
    private val y0 get() = (height - tileH * 5) / 2f + lift

    private fun tileLeft(i: Int) = Mahjong.SPOTS[i].let { x0 + it.col * tileW - it.layer * lift }
    private fun tileTop(i: Int) = Mahjong.SPOTS[i].let { y0 + it.row * tileH - it.layer * lift }

    fun hint() {
        val h = game.hint()
        if (h == null) { shuffle(); return }
        selected = -1; shown = h; shownAt = SystemClock.uptimeMillis(); hinting = true
        invalidate()
    }

    fun shuffle() {
        if (game.won) return
        game.shuffle()
        selected = -1; shown = null
        onChange(); invalidate()
    }

    fun undo() {
        if (game.undo()) { selected = -1; shown = null; onChange(); invalidate() }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked != MotionEvent.ACTION_UP || game.won) return true
        // The top tile under the finger (drawn last = on top).
        val i = Mahjong.SPOTS.indices.reversed().firstOrNull { k ->
            !game.gone[k] && e.x >= tileLeft(k) && e.x < tileLeft(k) + tileW && e.y >= tileTop(k) && e.y < tileTop(k) + tileH
        } ?: run { selected = -1; invalidate(); return true }
        when {
            !game.free(i) -> { selected = -1 }
            selected == i -> selected = -1
            selected >= 0 && game.match(selected, i) -> {
                val wasStart = game.left == Mahjong.SPOTS.size - 2
                shown = selected to i; shownAt = SystemClock.uptimeMillis(); hinting = false
                selected = -1
                if (wasStart) startClock()
                onChange()
            }
            else -> selected = i
        }
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val w = tileW; val h = tileH
        val now = SystemClock.uptimeMillis()
        val pair = shown
        val age = now - shownAt
        val fading = pair != null && !hinting && age < FADE_MS
        val hinted = pair != null && hinting && age < HINT_MS
        if (fading || hinted) postInvalidateOnAnimation()
        for (i in Mahjong.SPOTS.indices) {
            val goneNow = game.gone[i]
            val leaving = fading && (i == pair!!.first || i == pair.second)
            if (goneNow && !leaving) continue
            val alpha = if (leaving) (255 * (1 - age / FADE_MS.toFloat())).toInt().coerceIn(0, 255) else 255
            val x = tileLeft(i); val y = tileTop(i)
            val lit = i == selected || hinted && (i == pair!!.first || i == pair.second)
            // The tile's side (a darker block below and to the right), then its face.
            paint.color = Color.rgb(138, 118, 80); paint.alpha = alpha
            r.set(x + lift * 0.6f, y + lift * 0.6f, x + w + lift * 0.6f - 2 * d, y + h + lift * 0.6f - 2 * d)
            canvas.drawRoundRect(r, w * 0.11f, w * 0.11f, paint)
            paint.color = if (lit) Color.rgb(255, 231, 160) else Color.rgb(246, 236, 212); paint.alpha = alpha
            r.set(x, y, x + w - 2 * d, y + h - 2 * d)
            canvas.drawRoundRect(r, w * 0.11f, w * 0.11f, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = if (lit) 2.5f * d else 1 * d
            paint.color = if (lit) Theme.GOLD else Color.rgb(184, 162, 122); paint.alpha = alpha
            canvas.drawRoundRect(r, w * 0.11f, w * 0.11f, paint)
            paint.style = Paint.Style.FILL
            symbol(canvas, game.faces[i], r.centerX(), r.centerY(), w * 0.8f, alpha)
            if (!leaving && !game.free(i)) {
                paint.color = Theme.SURFACE; paint.alpha = 70
                canvas.drawRoundRect(r, w * 0.11f, w * 0.11f, paint)
            }
            paint.alpha = 255
        }
        if (game.won) veil(canvas, "Board cleared!", "${clock(game.millis)} · ${game.shuffles} shuffles · tap New game")
    }

    /** Kind [k]: shape k / 3 (ring, diamond, star, moon, leaf, cross) in colour k % 3 (red, green, blue). */
    private fun symbol(canvas: Canvas, k: Int, x: Float, y: Float, s: Float, alpha: Int) {
        paint.color = COLOURS[k % 3]; paint.alpha = alpha
        path.reset()
        when (k / 3) {
            0 -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.12f
                canvas.drawCircle(x, y, s * 0.32f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, s * 0.1f, paint)
                return
            }
            1 -> { path.moveTo(x, y - s * 0.42f); path.lineTo(x + s * 0.3f, y); path.lineTo(x, y + s * 0.42f); path.lineTo(x - s * 0.3f, y); path.close() }
            2 -> {
                for (p in 0 until 10) {
                    val a = p * Math.PI / 5 - Math.PI / 2; val rr = if (p % 2 == 1) s * 0.17f else s * 0.4f
                    val px = x + (Math.cos(a) * rr).toFloat(); val py = y + (Math.sin(a) * rr).toFloat()
                    if (p == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
            }
            3 -> {
                // A crescent: a big circle with a smaller one cut away.
                path.addCircle(x - s * 0.04f, y, s * 0.38f, Path.Direction.CW)
                val cut = Path().apply { addCircle(x + s * 0.12f, y - s * 0.06f, s * 0.3f, Path.Direction.CW) }
                path.op(cut, Path.Op.DIFFERENCE)
            }
            4 -> {
                path.moveTo(x, y + s * 0.42f)
                path.cubicTo(x - s * 0.42f, y, x - s * 0.25f, y - s * 0.4f, x, y - s * 0.42f)
                path.cubicTo(x + s * 0.25f, y - s * 0.4f, x + s * 0.42f, y, x, y + s * 0.42f)
                path.close()
            }
            else -> {
                path.addRoundRect(x - s * 0.1f, y - s * 0.4f, x + s * 0.1f, y + s * 0.4f, s * 0.05f, s * 0.05f, Path.Direction.CW)
                val bar = Path().apply { addRoundRect(x - s * 0.35f, y - s * 0.1f, x + s * 0.35f, y + s * 0.1f, s * 0.05f, s * 0.05f, Path.Direction.CW) }
                path.op(bar, Path.Op.UNION)
            }
        }
        canvas.drawPath(path, paint)
        if (k / 3 == 4) {
            paint.color = Color.rgb(246, 236, 212); paint.alpha = alpha; paint.strokeWidth = s * 0.05f
            canvas.drawLine(x, y - s * 0.3f, x, y + s * 0.38f, paint)
        }
    }

    companion object {
        const val RATIO = 1.3f
        const val FADE_MS = 180L
        const val HINT_MS = 1600L
        val COLOURS = intArrayOf(Color.rgb(212, 85, 61), Color.rgb(58, 163, 99), Color.rgb(59, 127, 209))
    }
}
