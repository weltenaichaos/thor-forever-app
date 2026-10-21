package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import thor.companion.Theme

/** Brick Breaker's field: drag anywhere to move the paddle, tap to launch the ball. */
class BricksView(context: Context, var game: Bricks, private val best: () -> Int) : ActionView(context) {
    private var downX = 0f
    private var dragged = false

    override fun line() = "Level ${game.level} · ${"♥".repeat(game.lives)}${"♡".repeat(3 - game.lives)} · ${number(game.score)}" +
        if (game.over) " · best ${number(maxOf(best(), game.score))}" else ""
    override fun moving() = !game.over && !game.stuck

    override fun step(dt: Float) {
        val s = game.score; val l = game.lives; val lv = game.level
        game.step(dt)
        if (game.score != s || game.lives != l || game.level != lv) onChange()
    }

    override fun touch(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> { downX = x; dragged = false }
            MotionEvent.ACTION_MOVE -> { if (Math.abs(x - downX) > 8f) dragged = true; game.movePaddle(x); if (game.stuck) game.step(0f) }
            MotionEvent.ACTION_UP -> if (!dragged && game.stuck) { game.launch(); invalidate() }
        }
    }

    override fun drawWorld(canvas: Canvas) {
        paint.color = Theme.SURFACE
        canvas.drawRect(0f, 0f, WORLD_W, WORLD_H, paint)
        if (game.stuck) game.step(0f)
        for (b in game.bricks) {
            r.set(b.x + 3, b.y + 3, b.x + Bricks.BW - 3, b.y + Bricks.BH - 3)
            paint.color = ROWS[b.colour % ROWS.size]
            canvas.drawRoundRect(r, 6f, 6f, paint)
            paint.color = Color.WHITE; paint.alpha = 56
            canvas.drawRoundRect(r.left, r.top, r.right, r.top + 7, 4f, 4f, paint)
            paint.alpha = 255
            if (b.hits > 1) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.color = Theme.GOLD_TEXT
                canvas.drawRoundRect(r, 6f, 6f, paint); paint.style = Paint.Style.FILL
            }
        }
        r.set(game.paddle - Bricks.PW / 2, Bricks.PADDLE_Y, game.paddle + Bricks.PW / 2, Bricks.PADDLE_Y + 16)
        paint.color = Theme.ACCENT
        canvas.drawRoundRect(r, 8f, 8f, paint)
        paint.color = Theme.GOLD_TEXT
        canvas.drawCircle(game.ballX, game.ballY, Bricks.R, paint)
        when {
            game.over -> banner(canvas, "Game over", "Level ${game.level} · ${number(game.score)} · tap New game")
            game.stuck -> hint(canvas, "Drag left and right to move · tap to launch", Bricks.PADDLE_Y - 40)
        }
    }

    companion object {
        val ROWS = intArrayOf(Color.rgb(212, 85, 61), Color.rgb(222, 140, 90), Color.rgb(232, 194, 90), Color.rgb(58, 163, 99),
            Color.rgb(52, 186, 215), Color.rgb(59, 127, 209))
    }
}
