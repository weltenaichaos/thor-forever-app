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
    /** A peg just hit, for the board to show its points. */
    class Hit(val x: Float, val y: Float, val points: Int, val orange: Boolean)

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
    /** Hits since the board last looked (it empties this). */
    val hits = mutableListOf<Hit>()
    private var oranges = 0

    val orangeLeft get() = pegs.count { it.orange }
    val won get() = orangeLeft == 0
    val over get() = won || (balls == 0 && !flying)

    init {
        val spots = mutableListOf<Pair<Float, Float>>()
        if (random.nextBoolean()) {
            // An arc over two rings, a row between them and a wave below.
            for (k in 0 until 15) {
                val a = Math.PI * (0.12 + 0.76 * k / 14)
                spots += (W / 2 - cos(a).toFloat() * 300f) to (250f - sin(a).toFloat() * 110f)
            }
            for (k in 0 until 12) {
                val a = k / 12.0 * Math.PI * 2
                spots += (290f + cos(a).toFloat() * 68f) to (380f + sin(a).toFloat() * 68f)
                spots += (W - 290f + cos(a).toFloat() * 68f) to (380f + sin(a).toFloat() * 68f)
            }
            for (k in 0 until 6) spots += (W / 2 - 125f + k * 50f) to 360f
            for (k in 0 until 9) spots += (W / 2 - 200f + k * 50f) to (500f + sin(k.toFloat()) * 10f)
        } else {
            // Rows in a gentle wave, every other one shifted.
            for (row in 0 until 6) for (c in 0 until 11) {
                val x = 150f + c * 70f + (if (row % 2 == 1) 35f else 0f)
                val y = 170f + row * 62f + sin(c * 0.7f + row) * 14f
                if (x < W - 140) spots += x to y
            }
        }
        val pick = spots.indices.shuffled(random).take(16).toSet()
        for ((i, s) in spots.withIndex()) pegs += Peg(s.first, s.second, i in pick)
        oranges = pegs.count { it.orange }
    }

    /** How much of the orange is cleared (0..1), and the points multiplier it brings. */
    val fever get() = if (oranges == 0) 1f else 1f - orangeLeft.toFloat() / oranges
    val multiplier get() = when { fever >= 0.75f -> 5; fever >= 0.5f -> 3; fever >= 0.25f -> 2; else -> 1 }

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
            if (!p.lit) {
                p.lit = true
                val pts = points(p)
                lastShot += pts
                hits += Hit(p.x, p.y, pts, p.orange)
                if (hits.size > 50) hits.removeAt(0)
            }
        }
        slow = if (hypot(vx, vy) < 40f) slow + dt else 0f
        if (slow > 1.5f) { clearLit(); slow = 0f }
        // The bucket at the bottom.
        if (ballY > BUCKET_Y && ballY < BUCKET_Y + 20 && kotlin.math.abs(ballX - bucket) < BUCKET_W / 2 && vy > 0) {
            balls++; freeBall = true; land()
        } else if (ballY > H + R) land()
    }

    private fun points(p: Peg) = (if (p.orange) 100 else 10) * multiplier

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
