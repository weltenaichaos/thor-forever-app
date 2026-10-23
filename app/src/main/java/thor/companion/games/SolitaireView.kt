package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewConfiguration
import thor.companion.Theme
import thor.companion.games.Klondike.Companion.FOUNDATION
import thor.companion.games.Klondike.Companion.STOCK
import thor.companion.games.Klondike.Companion.TABLEAU
import thor.companion.games.Klondike.Companion.WASTE

/**
 * The solitaire table: the stock, the waste and the four foundations on top, the seven
 * columns below. A tap on a card sends it where it fits (home first); a card or a run can
 * also be dragged. When everything is face up, the rest goes home by itself.
 */
class SolitaireView(context: Context, var game: Klondike) : GameView(context) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downPile = -1
    private var downIndex = -1
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var dragX = 0f
    private var dragY = 0f
    /** Where the grabbed card's top left was when it was grabbed, relative to the finger. */
    private var grabDx = 0f
    private var grabDy = 0f
    private var finishing = false

    override fun line() = "${clock(game.millis)} · ${game.moves} moves"
    override fun save() = game.save()
    override fun clockRuns() = !game.won && game.moves > 0
    override fun addTime(ms: Long) { game.millis += ms }

    private val colW get() = width / 7f
    private val cardW get() = minOf(colW * 0.88f, (height * 0.30f) / 1.4f)
    private val cardH get() = cardW * 1.4f
    private val gap get() = (colW - cardW) / 2f
    private val rowTop get() = 4 * d
    private val tableTop get() = rowTop + cardH + 12 * d

    private fun pileX(p: Int): Float = when (p) {
        STOCK -> 0f
        WASTE -> colW
        in FOUNDATION until FOUNDATION + 4 -> colW * (3 + p - FOUNDATION)
        else -> colW * (p - TABLEAU)
    } + gap

    /** How far apart the column's face-down and face-up cards are, squeezed so a long column still fits. */
    private fun steps(p: Int): Pair<Float, Float> {
        val cards = game.piles[p]
        val down = cards.count { !it.up }; val up = cards.size - down
        var sDown = cardH * 0.13f; var sUp = cardH * 0.26f
        val room = height - tableTop - cardH - 4 * d
        val need = down * sDown + maxOf(0, up - 1) * sUp
        if (need > room && need > 0) { val f = room / need; sDown *= f; sUp *= f }
        return sDown to sUp
    }

    private fun cardY(p: Int, index: Int): Float {
        if (p < TABLEAU) return rowTop
        val (sDown, sUp) = steps(p)
        var y = tableTop
        for (i in 0 until index) y += if (game.piles[p][i].up) sUp else sDown
        return y
    }

    /** The pile and card under a point (the card index is the pile's size for its empty spot). */
    private fun hit(x: Float, y: Float): Pair<Int, Int>? {
        val col = (x / colW).toInt().coerceIn(0, 6)
        if (y < tableTop - 6 * d) {
            val p = when (col) { 0 -> STOCK; 1 -> WASTE; 2 -> return null; else -> FOUNDATION + col - 3 }
            return p to maxOf(0, game.piles[p].size - 1)
        }
        val p = TABLEAU + col
        val cards = game.piles[p]
        for (i in cards.indices.reversed()) if (y >= cardY(p, i)) return p to i
        return p to 0
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (finishing) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; dragging = false
                val h = hit(e.x, e.y)
                downPile = h?.first ?: -1; downIndex = h?.second ?: -1
                if (downPile >= 0 && downPile != STOCK && game.movable(downPile, downIndex)) {
                    grabDx = pileX(downPile) - e.x; grabDy = cardY(downPile, downIndex) - e.y
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (!dragging && downPile >= 0 && downPile != STOCK && game.movable(downPile, downIndex) &&
                    Math.hypot((e.x - downX).toDouble(), (e.y - downY).toDouble()) > slop) dragging = true
                if (dragging) { dragX = e.x; dragY = e.y; invalidate() }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    dragging = false
                    val cx = e.x + grabDx + cardW / 2; val cy = e.y + grabDy + cardH / 3
                    val to = hit(cx, cy)?.first
                    if (to != null && to != downPile) game.move(downPile, downIndex, to)
                } else if (downPile == STOCK) game.draw()
                else if (downPile >= 0) game.tap(downPile, downIndex)
                if (game.moves == 1) startClock()
                downPile = -1
                after()
            }
            MotionEvent.ACTION_CANCEL -> { dragging = false; downPile = -1; invalidate() }
        }
        return true
    }

    /** After every change: the header, and the end of the game by itself when it can. */
    private fun after() {
        onChange()
        invalidate()
        if (game.canFinish && !finishing) {
            finishing = true
            val step = object : Runnable {
                override fun run() {
                    if (game.finishStep()) { onChange(); invalidate(); postDelayed(this, 90) }
                    else { finishing = false; onChange(); invalidate() }
                }
            }
            postDelayed(step, 200)
        }
    }

    fun undo() { if (!finishing && game.undo()) { onChange(); invalidate() } }

    override fun onDraw(canvas: Canvas) {
        // The top row's empty places.
        for (p in listOf(STOCK, WASTE) + (FOUNDATION until FOUNDATION + 4)) slot(canvas, pileX(p), rowTop, if (p >= FOUNDATION) "A" else if (p == STOCK && game.piles[WASTE].isNotEmpty()) "↻" else "")
        for (c in 0 until 7) slot(canvas, pileX(TABLEAU + c), tableTop, "K")
        val dragged = if (dragging) downPile to downIndex else null
        for (p in 0 until Klondike.PILES) {
            val cards = game.piles[p]
            val from = if (p < TABLEAU) maxOf(0, cards.size - 2) else 0
            for (i in from until cards.size) {
                if (dragged != null && dragged.first == p && i >= dragged.second) continue
                card(canvas, cards[i], pileX(p), cardY(p, i))
            }
        }
        if (dragged != null) {
            val cards = game.piles[dragged.first]
            var y = dragY + grabDy
            for (i in dragged.second until cards.size) { card(canvas, cards[i], dragX + grabDx, y); y += cardH * 0.26f }
        }
        if (game.won && !finishing) veil(canvas, "You won!", "${clock(game.millis)} · ${game.moves} moves · tap New game")
    }

    private fun slot(canvas: Canvas, x: Float, y: Float, mark: String) {
        r.set(x, y, x + cardW, y + cardH)
        paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 1.5f * d; paint.color = Theme.STROKE
        canvas.drawRoundRect(r, cardW * 0.09f, cardW * 0.09f, paint)
        paint.style = android.graphics.Paint.Style.FILL
        if (mark.isNotEmpty()) {
            heading.color = Theme.STROKE; heading.textSize = cardW * 0.4f
            canvas.drawText(mark, x + cardW / 2, y + cardH / 2 + cardW * 0.14f, heading)
        }
    }

    private fun card(canvas: Canvas, c: Klondike.Card, x: Float, y: Float) {
        r.set(x, y, x + cardW, y + cardH)
        val radius = cardW * 0.09f
        if (!c.up) {
            // Our own back: deep teal, a sky line, and the Forever ∞.
            paint.color = Color.rgb(30, 90, 124); canvas.drawRoundRect(r, radius, radius, paint)
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 1.2f * d
            paint.color = Theme.ACCENT; canvas.drawRoundRect(r, radius, radius, paint)
            r.inset(cardW * 0.09f, cardW * 0.09f); paint.color = Theme.TEAL
            canvas.drawRoundRect(r, radius * 0.6f, radius * 0.6f, paint)
            paint.style = android.graphics.Paint.Style.FILL
            heading.color = Theme.TEAL; heading.textSize = cardW * 0.36f
            canvas.drawText("∞", x + cardW / 2, y + cardH / 2 + cardW * 0.12f, heading)
            return
        }
        paint.color = Color.rgb(246, 241, 228); canvas.drawRoundRect(r, radius, radius, paint)
        paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 1.2f * d
        paint.color = Theme.ACCENT; canvas.drawRoundRect(r, radius, radius, paint)
        paint.style = android.graphics.Paint.Style.FILL
        val ink = if (c.red) Color.rgb(192, 48, 48) else Color.rgb(27, 27, 27)
        text.color = ink; text.textAlign = android.graphics.Paint.Align.LEFT
        text.typeface = android.graphics.Typeface.create(text.typeface, android.graphics.Typeface.BOLD)
        text.textSize = cardW * 0.26f
        canvas.drawText(Klondike.RANKS[c.rank] + Klondike.SUITS[c.suit], x + cardW * 0.08f, y + cardW * 0.28f, text)
        text.textAlign = android.graphics.Paint.Align.CENTER
        text.textSize = cardW * 0.5f
        canvas.drawText(Klondike.SUITS[c.suit], x + cardW / 2, y + cardH * 0.72f, text)
        text.typeface = Theme.body(context)
    }
}
