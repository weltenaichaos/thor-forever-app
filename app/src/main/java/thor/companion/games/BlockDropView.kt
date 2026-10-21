package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import thor.companion.Theme

/**
 * Block Drop's board: the 8 x 8 board on the left, the three shapes on offer on the right.
 * Drag a shape onto the board (it rides a little above the finger so it stays in sight); a
 * lit outline shows where it would land. Full rows and columns flash and clear.
 */
class BlockDropView(context: Context, var game: BlockDrop, private val best: () -> Int) : GameView(context) {
    private var dragging = -1
    private var fx = 0f
    private var fy = 0f
    /** Where the dragged shape would land (its top left square), or -1. */
    private var landX = -1
    private var landY = -1
    /** The last clear: which lines (rows 0..7, columns 8..15) and when. */
    private var cleared: List<Int> = emptyList()
    private var clearedAt = 0L
    private val slot = Array(3) { RectF() }

    override fun line() = if (game.over) "Score ${number(game.score)} · no room left" else "Score ${number(game.score)} · best ${number(maxOf(best(), game.score))}"
    override fun save() = game.save()

    private val board get() = minOf(height.toFloat(), width * 0.64f)
    private val cell get() = board / BlockDrop.SIZE
    private val gap get() = 18 * d
    private val trayW get() = minOf(width - board - gap, board * 0.45f)
    private val bx get() = (width - board - gap - trayW) / 2f
    private val by get() = (height - board) / 2f

    private fun layoutSlots() {
        val x = bx + board + gap; val sp = 10 * d
        val h = (board - 2 * sp) / 3
        for (k in 0 until 3) slot[k].set(x, by + k * (h + sp), x + trayW, by + k * (h + sp) + h)
    }

    private fun size(shape: Int): Pair<Int, Int> = BlockDrop.SHAPES[shape].let { s -> (s.maxOf { it.first } + 1) to (s.maxOf { it.second } + 1) }

    /** The dragged shape's top left on screen. */
    private fun dragLeft(shape: Int) = fx - size(shape).first * cell / 2
    private fun dragTop(shape: Int) = fy - size(shape).second * cell - cell * 0.9f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (game.over) return true
        fx = e.x; fy = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                layoutSlots()
                dragging = (0 until 3).firstOrNull { slot[it].contains(e.x, e.y) && game.offer[it] != null } ?: -1
                findLanding()
            }
            MotionEvent.ACTION_MOVE -> if (dragging >= 0) findLanding()
            MotionEvent.ACTION_UP -> if (dragging >= 0) {
                if (landX >= 0 && game.place(dragging, landX, landY)) {
                    if (game.lastCleared.isNotEmpty()) { cleared = game.lastCleared; clearedAt = SystemClock.uptimeMillis() }
                    onChange()
                }
                dragging = -1; landX = -1
            }
            MotionEvent.ACTION_CANCEL -> { dragging = -1; landX = -1 }
        }
        invalidate()
        return true
    }

    private fun findLanding() {
        landX = -1; landY = -1
        val shape = game.offer.getOrNull(dragging) ?: return
        val x = Math.round((dragLeft(shape) - bx) / cell); val y = Math.round((dragTop(shape) - by) / cell)
        if (game.fits(shape, x, y)) { landX = x; landY = y }
    }

    override fun onDraw(canvas: Canvas) {
        val c = cell; val ox = bx; val oy = by
        r.set(ox, oy, ox + board, oy + board)
        paint.color = Theme.SURFACE
        canvas.drawRoundRect(r, 10 * d, 10 * d, paint)
        for (i in 0 until BlockDrop.SIZE * BlockDrop.SIZE) {
            val x = ox + (i % BlockDrop.SIZE) * c; val y = oy + (i / BlockDrop.SIZE) * c
            val v = game.cells[i]
            if (v == 0) {
                paint.color = Color.rgb(19, 47, 74)
                r.set(x + c * 0.04f, y + c * 0.04f, x + c * 0.96f, y + c * 0.96f)
                canvas.drawRoundRect(r, c * 0.12f, c * 0.12f, paint)
            } else block(canvas, x, y, c, COLOURS[v - 1], 255)
        }
        // Lines just cleared: a bright flash fading away.
        val t = SystemClock.uptimeMillis() - clearedAt
        if (cleared.isNotEmpty() && t < FLASH_MS) {
            paint.color = Theme.GOLD_TEXT; paint.alpha = (200 * (1 - t / FLASH_MS.toFloat())).toInt()
            for (line in cleared) {
                if (line < BlockDrop.SIZE) canvas.drawRect(ox, oy + line * c, ox + board, oy + (line + 1) * c, paint)
                else canvas.drawRect(ox + (line - BlockDrop.SIZE) * c, oy, ox + (line - BlockDrop.SIZE + 1) * c, oy + board, paint)
            }
            paint.alpha = 255
            postInvalidateOnAnimation()
        }
        // Where the dragged shape would land.
        val dragged = game.offer.getOrNull(dragging)
        if (dragged != null && landX >= 0) {
            for ((dx, dy) in BlockDrop.SHAPES[dragged]) {
                val x = ox + (landX + dx) * c; val y = oy + (landY + dy) * c
                r.set(x + c * 0.04f, y + c * 0.04f, x + c * 0.96f, y + c * 0.96f)
                paint.color = Theme.TEAL; paint.alpha = 95
                canvas.drawRoundRect(r, c * 0.12f, c * 0.12f, paint)
                paint.alpha = 255; paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = Theme.GOLD
                canvas.drawRoundRect(r, c * 0.12f, c * 0.12f, paint)
                paint.style = Paint.Style.FILL
            }
        }
        // The tray.
        layoutSlots()
        for (k in 0 until 3) {
            val s = slot[k]
            paint.color = Theme.SURFACE
            canvas.drawRoundRect(s, 10 * d, 10 * d, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 1 * d; paint.color = Theme.STROKE
            canvas.drawRoundRect(s, 10 * d, 10 * d, paint)
            paint.style = Paint.Style.FILL
            val shape = game.offer[k] ?: continue
            if (k == dragging) continue
            val (sw, sh) = size(shape)
            val sc = minOf(c * 0.6f, s.width() * 0.8f / sw, s.height() * 0.8f / sh)
            val fits = game.fitsAnywhere(shape)
            val x = s.centerX() - sw * sc / 2; val y = s.centerY() - sh * sc / 2
            for ((dx, dy) in BlockDrop.SHAPES[shape]) block(canvas, x + dx * sc, y + dy * sc, sc, COLOURS[game.colours[k] - 1], if (fits) 255 else 80)
        }
        // The shape under the finger, full size.
        if (dragged != null) {
            val x = dragLeft(dragged); val y = dragTop(dragged)
            for ((dx, dy) in BlockDrop.SHAPES[dragged]) block(canvas, x + dx * c, y + dy * c, c, COLOURS[game.colours[dragging] - 1], 230)
        }
        if (game.over) veil(canvas, "No room left", "Score ${number(game.score)} · tap New game")
    }

    private fun block(canvas: Canvas, x: Float, y: Float, c: Float, colour: Int, alpha: Int) {
        r.set(x + c * 0.04f, y + c * 0.04f, x + c * 0.96f, y + c * 0.96f)
        paint.color = colour; paint.alpha = alpha
        canvas.drawRoundRect(r, c * 0.14f, c * 0.14f, paint)
        // A lighter top edge, so blocks look raised.
        paint.color = Color.WHITE; paint.alpha = alpha * 55 / 255
        r.bottom = r.top + c * 0.22f
        canvas.drawRoundRect(r, c * 0.12f, c * 0.12f, paint)
        paint.alpha = 255
    }

    companion object {
        const val FLASH_MS = 320L
        val COLOURS = intArrayOf(Color.rgb(212, 85, 61), Color.rgb(222, 140, 90), Color.rgb(232, 194, 90), Color.rgb(58, 163, 99),
            Color.rgb(52, 186, 215), Color.rgb(59, 127, 209), Color.rgb(176, 114, 232))
    }
}
