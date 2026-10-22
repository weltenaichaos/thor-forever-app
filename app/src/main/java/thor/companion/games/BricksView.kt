package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.MotionEvent
import thor.companion.Theme

/**
 * Brick Breaker's field (Jesse, 2026-10-22: less basic): glossy bricks with light and shadow,
 * strong bricks in a silver frame that crack on the first hit, bricks bursting into pieces
 * with their points, a gold paddle with glowing ends, and a glowing ball with a trail.
 * Drag anywhere to move the paddle, tap to launch.
 */
class BricksView(context: Context, var game: Bricks, private val best: () -> Int) : ActionView(context) {
    private var downX = 0f
    private var dragged = false
    private val trail = Trail()
    private val bg = Paint().apply { shader = LinearGradient(0f, 0f, 0f, WORLD_H, intArrayOf(Color.rgb(10, 26, 44), Color.rgb(20, 48, 76), Color.rgb(29, 63, 94)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP) }
    private val brick = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gold = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader = LinearGradient(0f, Bricks.PADDLE_Y, 0f, Bricks.PADDLE_Y + 16, intArrayOf(Color.rgb(255, 240, 200), Color.rgb(217, 180, 106), Color.rgb(138, 106, 50)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    }
    /** One shader per row (rows sit at fixed heights): light top, colour, dark bottom. */
    private val rowShaders = Array(6) { row ->
        val (light, mid, dark) = ROWS[row]
        val top = Bricks.TOP + row * Bricks.BH
        LinearGradient(0f, top + 3, 0f, top + Bricks.BH - 3, intArrayOf(light, mid, dark), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    }
    private val metal = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3.5f }

    override fun line() = "${number(game.score)} · best ${number(maxOf(best(), game.score))}"
    override fun moving() = !game.over && !game.stuck

    override fun step(dt: Float) {
        val s = game.score; val l = game.lives; val lv = game.level
        game.step(dt)
        if (!game.stuck) trail.add(game.ballX, game.ballY)
        if (game.lives != l) trail.clear()
        if (game.level != lv) popup("Level ${game.level}!", WORLD_W / 2, WORLD_H / 2)
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
        canvas.drawRect(0f, 0f, WORLD_W, WORLD_H, bg)
        // A faint ∞ pattern behind everything.
        text.textSize = 20f; text.color = Theme.ACCENT; text.alpha = 14
        var y = 40f
        while (y < WORLD_H) { var x = 28f; while (x < WORLD_W) { canvas.drawText("∞", x, y, text); x += 56f }; y += 56f }
        text.alpha = 255
        if (game.stuck) game.step(0f)
        for (b in game.bricks) {
            val row = b.colour % ROWS.size
            r.set(b.x + 4, b.y + 6, b.x + Bricks.BW - 4, b.y + Bricks.BH)
            paint.color = Color.BLACK; paint.alpha = 90; canvas.drawRoundRect(r, 6f, 6f, paint); paint.alpha = 255
            r.set(b.x + 4, b.y + 3, b.x + Bricks.BW - 4, b.y + Bricks.BH - 3)
            brick.shader = rowShaders[row]
            canvas.drawRoundRect(r, 6f, 6f, brick)
            paint.color = Color.WHITE; paint.alpha = 110
            canvas.drawRoundRect(r.left + 5, r.top + 3, r.right - 5, r.top + 9, 3f, 3f, paint); paint.alpha = 255
            if (b.strong) {
                metal.color = Color.rgb(214, 220, 228)
                canvas.drawRoundRect(r, 6f, 6f, metal)
                paint.color = Color.rgb(232, 236, 240)
                canvas.drawCircle(r.left + 7, r.centerY(), 2.5f, paint); canvas.drawCircle(r.right - 7, r.centerY(), 2.5f, paint)
                if (b.hits == 1) {
                    paint.color = Color.rgb(58, 14, 6); paint.strokeWidth = 2.2f
                    val cx = r.centerX()
                    canvas.drawLine(cx - 4, r.top + 3, cx + 4, r.top + 11, paint)
                    canvas.drawLine(cx + 4, r.top + 11, cx - 2, r.top + 17, paint)
                    canvas.drawLine(cx - 2, r.top + 17, cx + 6, r.bottom - 3, paint)
                }
            }
        }
        for (hit in game.hits) {
            val colour = ROWS[hit.colour % ROWS.size].second
            if (hit.broke) { burst(hit.x, hit.y, colour, 12); popup("+${hit.points}", hit.x, hit.y - 6) }
            else burst(hit.x, hit.y, Color.rgb(214, 220, 228), 5)
        }
        game.hits.clear()

        // The paddle: gold, with glowing blue ends and our ∞.
        val px = game.paddle; val py = Bricks.PADDLE_Y
        glow(canvas, px, py + 14, Bricks.PW * 0.7f, Theme.TEAL, 90)
        r.set(px - Bricks.PW / 2, py, px + Bricks.PW / 2, py + 16)
        canvas.drawRoundRect(r, 8f, 8f, gold)
        paint.color = Color.WHITE; paint.alpha = 130
        canvas.drawRoundRect(r.left + 12, py + 3, r.right - 12, py + 6, 2f, 2f, paint); paint.alpha = 255
        for (end in listOf(r.left + 6, r.right - 6)) {
            glow(canvas, end, py + 8, 20f, Theme.TEAL)
            orb(canvas, end, py + 8, 9f, Color.rgb(201, 242, 255), Theme.TEAL, Color.rgb(21, 106, 133))
        }
        text.textSize = 15f; text.color = Color.rgb(90, 69, 32)
        canvas.drawText("∞", px, py + 13, text)
        // The ball.
        drawTrail(canvas, trail, Bricks.R, Color.rgb(255, 231, 160))
        glow(canvas, game.ballX, game.ballY, 36f, Color.rgb(255, 231, 160), 150)
        orb(canvas, game.ballX, game.ballY, Bricks.R, Color.WHITE, Color.rgb(246, 236, 212), Color.rgb(201, 168, 106))
        // Lives as small pearls, the level on the right.
        for (k in 0 until game.lives) orb(canvas, 26f + k * 24f, 30f, 8f, Color.WHITE, Color.rgb(246, 236, 212), Color.rgb(201, 168, 106))
        heading.textAlign = Paint.Align.RIGHT; heading.textSize = 20f; heading.color = Theme.ACCENT
        canvas.drawText("Level ${game.level}", WORLD_W - 22, 37f, heading)
        heading.textAlign = Paint.Align.CENTER
        when {
            game.over -> banner(canvas, "Game over", "Level ${game.level} · ${number(game.score)} · tap New game")
            game.stuck -> hint(canvas, "Drag left and right to move · tap to launch", Bricks.PADDLE_Y - 40)
        }
    }

    companion object {
        /** Each row's light, colour and dark. */
        val ROWS = listOf(
            Triple(Color.rgb(255, 154, 128), Color.rgb(212, 85, 61), Color.rgb(138, 42, 24)),
            Triple(Color.rgb(255, 197, 154), Color.rgb(224, 134, 78), Color.rgb(148, 71, 28)),
            Triple(Color.rgb(255, 240, 168), Color.rgb(232, 194, 90), Color.rgb(154, 122, 32)),
            Triple(Color.rgb(159, 232, 180), Color.rgb(58, 163, 99), Color.rgb(29, 94, 54)),
            Triple(Color.rgb(180, 240, 255), Color.rgb(52, 186, 215), Color.rgb(21, 106, 133)),
            Triple(Color.rgb(168, 200, 255), Color.rgb(59, 127, 209), Color.rgb(27, 69, 131)),
        )
    }
}
