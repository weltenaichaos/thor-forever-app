package thor.companion.games

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Peg Bounce's rules in world units (1000 x 620): aim the launcher at the top and shoot a
 * ball; it falls and bounces off the pegs, lighting each it touches. When the ball leaves at
 * the bottom the lit pegs go away. Clear every orange peg before the balls run out. A ball
 * that lands in the moving bucket comes back free. Points grow as fewer oranges are left.
 */
class Pegs(private val random: Random = Random.Default) {
    class Peg(val x: Float, val y: Float, val orange: Boolean) { var lit = false }

    val pegs = mutableListOf<Peg>()
    var balls = 10
        private set
    var score = 0
        private set
    /** Aim, in radians from straight down (negative: left). */
    var aim = 0.5f
    var flying = false
        private set
    var ballX = LAUNCH_X
    var ballY = LAUNCH_Y
    private var vx = 0f
    private var vy = 0f
    var bucket = W / 2
        private set
    private var bucketV = 160f
    /** How long the ball has hardly moved (then lit pegs it rests on go away). */
    private var slow = 0f
    /** The last shot's points, shown for a moment. */
    var lastShot = 0
        private set
    var freeBall = false
        private set

    val orangeLeft get() = pegs.count { it.orange }
    val won get() = orangeLeft == 0
    val over get() = won || (balls == 0 && !flying)

    init {
        // Rows of pegs in a wave; a fifth of them orange.
        val spots = mutableListOf<Pair<Float, Float>>()
        for (row in 0 until 7) for (c in 0 until 13) {
            val x = 80f + c * 70f + (if (row % 2 == 1) 35f else 0f)
            val y = 170f + row * 52f + sin(c * 0.7f + row) * 12f
            if (x < W - 50) spots += x to y
        }
        val oranges = spots.indices.shuffled(random).take(22).toSet()
        for ((i, s) in spots.withIndex()) pegs += Peg(s.first, s.second, i in oranges)
    }

    /** Points the launcher at world point [x], [y] (below it). */
    fun aimAt(x: Float, y: Float) {
        if (flying) return
        aim = kotlin.math.atan2(x - LAUNCH_X, maxOf(10f, y - LAUNCH_Y)).coerceIn(-1.35f, 1.35f)
    }

    fun shoot() {
        if (flying || balls == 0 || won) return
        balls--
        flying = true; freeBall = false; lastShot = 0
        ballX = LAUNCH_X + sin(aim) * 40; ballY = LAUNCH_Y + cos(aim) * 40
        vx = sin(aim) * SHOT; vy = cos(aim) * SHOT
    }

    fun step(dt: Float) {
        bucket += bucketV * dt
        if (bucket < BUCKET_W / 2 + 10 || bucket > W - BUCKET_W / 2 - 10) { bucketV = -bucketV; bucket = bucket.coerceIn(BUCKET_W / 2 + 10, W - BUCKET_W / 2 - 10) }
        if (!flying) return
        vy += GRAVITY * dt
        ballX += vx * dt; ballY += vy * dt
        if (ballX < R) { ballX = R; vx = -vx * 0.9f }
        if (ballX > W - R) { ballX = W - R; vx = -vx * 0.9f }
        if (ballY < R) { ballY = R; vy = -vy * 0.9f }
        for (p in pegs) {
            val dx = ballX - p.x; val dy = ballY - p.y
            val dist = hypot(dx, dy)
            if (dist >= R + PEG_R || dist == 0f) continue
            // Push out and bounce (losing a quarter of the speed along the hit).
            val nx = dx / dist; val ny = dy / dist
            ballX = p.x + nx * (R + PEG_R); ballY = p.y + ny * (R + PEG_R)
            val along = vx * nx + vy * ny
            if (along < 0) { vx -= 1.75f * along * nx; vy -= 1.75f * along * ny }
            if (!p.lit) { p.lit = true; lastShot += points(p) }
        }
        slow = if (hypot(vx, vy) < 40f) slow + dt else 0f
        if (slow > 1.5f) { clearLit(); slow = 0f }
        // The bucket at the bottom.
        if (ballY > BUCKET_Y && ballY < BUCKET_Y + 20 && kotlin.math.abs(ballX - bucket) < BUCKET_W / 2 && vy > 0) {
            balls++; freeBall = true; land()
        } else if (ballY > H + R) land()
    }

    private fun points(p: Peg) = if (p.orange) 100 * (1 + (22 - orangeLeft) / 5) else 10

    private fun clearLit() {
        pegs.removeAll { it.lit }
    }

    private fun land() {
        flying = false
        clearLit()
        score += lastShot
        ballX = LAUNCH_X; ballY = LAUNCH_Y
    }

    companion object {
        const val W = 1000f
        const val H = 620f
        const val LAUNCH_X = 500f
        const val LAUNCH_Y = 26f
        const val R = 9f
        const val PEG_R = 11f
        const val SHOT = 560f
        const val GRAVITY = 520f
        const val BUCKET_W = 130f
        const val BUCKET_Y = 590f
    }
}
