package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.os.SystemClock
import android.view.MotionEvent
import thor.companion.Theme

/** Gem Match's board: tap a gem and then its neighbour, or drag it onto the neighbour. */
class GemView(context: Context, var board: GemBoard, private val best: () -> Int) : GameView(context) {
    private var selected = -1
    private var downAt = -1
    private var downX = 0f
    private var downY = 0f
    /** The round was already over when this touch began: its tap starts the next round. */
    private var overAtDown = false
    /** The chain being shown after a swap: its steps, and when the first one started. */
    private var steps: List<GemBoard.Step> = emptyList()
    private var stepsAt = 0L
    private val path = Path()

    override fun line() = "Level ${board.level} · ${number(board.roundScore)} of ${number(board.goal)}"
    override fun save() = board.save()

    private val boardSize get() = minOf(height.toFloat(), width * 0.62f)
    private val cell get() = boardSize / GemBoard.SIZE
    private val left get() = 0f
    private val top get() = (height - boardSize) / 2f

    private fun cellAt(x: Float, y: Float): Int {
        val cx = ((x - left) / cell).toInt(); val cy = ((y - top) / cell).toInt()
        if (x < left || y < top || cx !in 0 until GemBoard.SIZE || cy !in 0 until GemBoard.SIZE) return -1
        return cy * GemBoard.SIZE + cx
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (steps.isNotEmpty()) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { overAtDown = board.roundOver; downAt = if (overAtDown) -1 else cellAt(e.x, e.y); downX = e.x; downY = e.y }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.x - downX; val dy = e.y - downY
                if (downAt >= 0 && maxOf(Math.abs(dx), Math.abs(dy)) > cell * 0.4f) {
                    val x = downAt % GemBoard.SIZE; val y = downAt / GemBoard.SIZE
                    val to = if (Math.abs(dx) > Math.abs(dy)) (if (dx > 0) x + 1 else x - 1).let { if (it in 0 until GemBoard.SIZE) y * GemBoard.SIZE + it else -1 }
                    else (if (dy > 0) y + 1 else y - 1).let { if (it in 0 until GemBoard.SIZE) it * GemBoard.SIZE + x else -1 }
                    if (to >= 0) trySwap(downAt, to)
                    downAt = -1
                }
            }
            MotionEvent.ACTION_UP -> {
                if (overAtDown && board.roundOver) { board.nextRound(); selected = -1; onChange(); invalidate(); return true }
                val c = cellAt(e.x, e.y)
                if (downAt >= 0 && c == downAt) {
                    when {
                        selected == c -> selected = -1
                        selected >= 0 && board.neighbours(selected, c) -> trySwap(selected, c)
                        else -> selected = c
                    }
                    invalidate()
                }
                downAt = -1
            }
        }
        return true
    }

    private fun trySwap(a: Int, b: Int) {
        selected = -1
        val result = board.swap(a, b)
        if (result == null) { selected = b; invalidate(); return }
        steps = result
        stepsAt = SystemClock.uptimeMillis()
        onChange()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // Which picture of the chain to show now: each step's board with its cleared gems fading.
        var shown = board.cells
        var fading: Set<Int> = emptySet()
        var fade = 0f
        if (steps.isNotEmpty()) {
            val t = SystemClock.uptimeMillis() - stepsAt
            val i = (t / STEP_MS).toInt()
            if (i >= steps.size) steps = emptyList()
            else {
                shown = steps[i].board; fading = steps[i].cleared; fade = (t % STEP_MS) / STEP_MS.toFloat()
                postInvalidateOnAnimation()
            }
        }
        val c = cell; val top = top
        for (i in shown.indices) {
            val x = left + (i % GemBoard.SIZE) * c; val y = top + (i / GemBoard.SIZE) * c
            r.set(x + c * 0.04f, y + c * 0.04f, x + c * 0.96f, y + c * 0.96f)
            paint.style = android.graphics.Paint.Style.FILL
            paint.color = if ((i % GemBoard.SIZE + i / GemBoard.SIZE) % 2 == 0) Color.rgb(15, 39, 64) else Color.rgb(19, 47, 74)
            canvas.drawRoundRect(r, c * 0.12f, c * 0.12f, paint)
            if (i == selected) {
                paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 3 * d; paint.color = Theme.GOLD
                canvas.drawRoundRect(r, c * 0.12f, c * 0.12f, paint)
                paint.style = android.graphics.Paint.Style.FILL
            }
            val k = shown[i]
            if (k < 0) continue
            val alpha = if (i in fading) (255 * (1f - fade)).toInt() else 255
            val grow = if (i in fading) 1f + fade * 0.3f else 1f
            gem(canvas, k, x + c / 2, y + c / 2, c * 0.34f * grow, alpha)
        }
        side(canvas)
        if (board.roundOver && steps.isEmpty()) {
            val won = board.roundScore >= board.goal
            veil(canvas, if (won) "Level ${board.level} done!" else "Out of moves",
                if (won) "${number(board.roundScore)} points · tap for level ${board.level + 1}" else "${number(board.roundScore)} of ${number(board.goal)} · tap to try again")
        }
    }

    /** Moves left, the goal and the best score, right of the board. */
    private fun side(canvas: Canvas) {
        val x0 = boardSize + 16 * d
        val w = width - x0
        if (w < 80 * d) return
        heading.textAlign = android.graphics.Paint.Align.LEFT; text.textAlign = android.graphics.Paint.Align.LEFT
        var y = top + 8 * d
        fun box(title: String, value: String, h: Float, bar: Float? = null) {
            r.set(x0, y, width.toFloat() - 2 * d, y + h)
            paint.color = Theme.SURFACE; canvas.drawRoundRect(r, 8 * d, 8 * d, paint)
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = d; paint.color = Theme.STROKE
            canvas.drawRoundRect(r, 8 * d, 8 * d, paint); paint.style = android.graphics.Paint.Style.FILL
            heading.color = Theme.GOLD_TEXT; heading.textSize = 13 * d
            canvas.drawText(title, x0 + 12 * d, y + 22 * d, heading)
            text.color = Theme.TEXT; text.textSize = (if (bar == null) 28 else 17) * d
            canvas.drawText(value, x0 + 12 * d, y + (if (bar == null) 56 else 46) * d, text)
            if (bar != null) {
                r.set(x0 + 12 * d, y + h - 18 * d, width - 14 * d, y + h - 10 * d)
                paint.color = Theme.INNER; canvas.drawRect(r, paint)
                r.right = r.left + (r.width() * bar.coerceIn(0f, 1f)); paint.color = Theme.GOLD; canvas.drawRect(r, paint)
            }
            y += h + 10 * d
        }
        box("Moves left", "${board.movesLeft}", 68 * d)
        box("Goal · level ${board.level}", "${number(board.roundScore)} of ${number(board.goal)}", 70 * d, board.roundScore / board.goal.toFloat())
        box("Best", number(maxOf(best(), board.score)), 68 * d)
        text.color = Theme.DIM; text.textSize = 12.5f * d
        for (l in listOf("Drag a gem onto its neighbour,", "or tap one and then the other.", "Three in a row disappear.")) {
            canvas.drawText(l, x0 + 2 * d, y + 12 * d, text); y += 17 * d
        }
        heading.textAlign = android.graphics.Paint.Align.CENTER; text.textAlign = android.graphics.Paint.Align.CENTER
    }

    private fun gem(canvas: Canvas, kind: Int, cx: Float, cy: Float, s: Float, alpha: Int) {
        paint.color = COLOURS[kind]; paint.alpha = alpha
        path.reset()
        fun poly(n: Int, start: Double) {
            for (i in 0 until n) {
                val a = start + i * 2 * Math.PI / n
                val px = cx + s * Math.cos(a).toFloat(); val py = cy + s * Math.sin(a).toFloat()
                if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
            }
            path.close(); canvas.drawPath(path, paint)
        }
        when (kind) {
            0 -> poly(4, -Math.PI / 2)
            1 -> canvas.drawCircle(cx, cy, s * 0.92f, paint)
            2 -> { path.reset(); path.moveTo(cx, cy - s); path.lineTo(cx + s, cy + s * 0.75f); path.lineTo(cx - s, cy + s * 0.75f); path.close(); canvas.drawPath(path, paint) }
            3 -> { r.set(cx - s * 0.8f, cy - s * 0.8f, cx + s * 0.8f, cy + s * 0.8f); canvas.drawRoundRect(r, s * 0.2f, s * 0.2f, paint) }
            4 -> poly(5, -Math.PI / 2)
            else -> poly(6, 0.0)
        }
        // A small shine, top left.
        paint.color = Color.WHITE; paint.alpha = alpha * 90 / 255
        r.set(cx - s * 0.45f, cy - s * 0.55f, cx - s * 0.05f, cy - s * 0.3f)
        canvas.drawOval(r, paint)
        paint.alpha = 255
    }

    companion object {
        const val STEP_MS = 260L
        val COLOURS = intArrayOf(Color.rgb(224, 72, 72), Color.rgb(74, 163, 224), Color.rgb(90, 210, 120),
            Color.rgb(255, 210, 90), Color.rgb(176, 114, 232), Color.rgb(240, 240, 240))
    }
}
