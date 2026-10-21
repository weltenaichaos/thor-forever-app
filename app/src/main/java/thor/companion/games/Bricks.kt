package thor.companion.games

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Brick Breaker's rules in world units (1000 x 620): rows of bricks, a paddle along the
 * bottom, one ball. The ball waits on the paddle until launched. Where it hits the paddle
 * sets its angle. Bricks with [Brick.hits] 2 need two hits. Losing the ball costs a life
 * (three); clearing the bricks starts the next level, a little faster.
 */
class Bricks {
    class Brick(val x: Float, val y: Float, val colour: Int, var hits: Int)

    val bricks = mutableListOf<Brick>()
    var paddle = W / 2
    var ballX = 0f
    var ballY = 0f
    var vx = 0f
    var vy = 0f
    var stuck = true
        private set
    var lives = 3
        private set
    var level = 1
        private set
    var score = 0
        private set
    val over get() = lives == 0
    private val speed get() = 520f + (level - 1) * 45f

    init { build() }

    private fun build() {
        bricks.clear()
        for (row in 0 until 6) for (c in 0 until COLS) {
            // Each level leaves some gaps in its own pattern.
            if (row >= 2 && (c * 7 + row * 3 + level * 5) % 11 == 0) continue
            bricks += Brick(c * BW, TOP + row * BH, row, if (row == 0 && c % 4 == 1) 2 else 1)
        }
        stuck = true
    }

    fun movePaddle(x: Float) {
        paddle = x.coerceIn(PW / 2, W - PW / 2)
    }

    fun launch() {
        if (!stuck || over) return
        stuck = false
        vx = speed * 0.45f; vy = -speed * 0.89f
    }

    fun step(dt: Float) {
        if (over) return
        if (stuck) { ballX = paddle; ballY = PADDLE_Y - R - 1; return }
        ballX += vx * dt; ballY += vy * dt
        if (ballX < R) { ballX = R; vx = abs(vx) }
        if (ballX > W - R) { ballX = W - R; vx = -abs(vx) }
        if (ballY < R) { ballY = R; vy = abs(vy) }
        // The paddle: the further from its middle, the wider the angle.
        if (vy > 0 && ballY + R >= PADDLE_Y && ballY + R <= PADDLE_Y + 18 && abs(ballX - paddle) <= PW / 2 + R) {
            val off = ((ballX - paddle) / (PW / 2)).coerceIn(-1f, 1f)
            val s = hypot(vx, vy)
            vx = s * off * 0.8f
            vy = -kotlin.math.sqrt(maxOf(s * s - vx * vx, s * s * 0.2f))
            ballY = PADDLE_Y - R
        }
        if (ballY > H + R) {
            lives--
            stuck = true
            return
        }
        // Bricks: bounce off the side it came in through.
        for (b in bricks) {
            val nx = ballX.coerceIn(b.x + 2, b.x + BW - 2)
            val ny = ballY.coerceIn(b.y + 2, b.y + BH - 2)
            if (hypot(ballX - nx, ballY - ny) > R) continue
            val fromSide = abs(ballX - nx) > abs(ballY - ny)
            if (fromSide) vx = if (ballX < nx) -abs(vx) else abs(vx) else vy = if (ballY < ny) -abs(vy) else abs(vy)
            b.hits--
            score += 10 * level
            if (b.hits <= 0) bricks.remove(b)
            break
        }
        if (bricks.isEmpty()) { level++; score += 100 * level; build() }
    }

    companion object {
        const val W = 1000f
        const val H = 620f
        const val COLS = 12
        const val BW = W / COLS
        const val BH = 26f
        const val TOP = 46f
        const val PW = 150f
        const val PADDLE_Y = 580f
        const val R = 9f
    }
}
