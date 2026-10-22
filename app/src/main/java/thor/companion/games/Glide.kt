package thor.companion.games

import kotlin.random.Random

/**
 * Gryphon Glide's rules in world units (1000 x 620): the gryphon stays at [X] and falls; a
 * tap gives it a flap upwards. Peaks hang from above and rise from below with a gap between;
 * they and the ground end the flight. Coins in the gaps count extra. It gets a little faster
 * the further it flies.
 */
class Glide(private val random: Random = Random.Default) {
    /** A pair of peaks at [x]: the gap runs from [top] to [bottom]. */
    class Peaks(var x: Float, val top: Float, val bottom: Float)
    class Coin(var x: Float, val y: Float) { var taken = false }

    var y = H * 0.45f
        private set
    var vy = 0f
        private set
    val peaks = mutableListOf<Peaks>()
    val coins = mutableListOf<Coin>()
    var started = false
        private set
    var alive = true
        private set
    /** Metres flown (a world unit is 1/40 m... kept simple: distance / 25). */
    var metres = 0f
        private set
    var coinsTaken = 0
        private set
    private val speed get() = 230f + minOf(metres, 4000f) * 0.03f

    fun flap() {
        if (!alive) return
        started = true
        vy = FLAP
    }

    fun step(dt: Float) {
        if (!alive || !started) return
        vy = minOf(vy + GRAVITY * dt, 620f)
        y += vy * dt
        if (y < R) { y = R; vy = 0f }
        val move = speed * dt
        metres += move / 25f
        for (p in peaks) p.x -= move
        for (c in coins) c.x -= move
        peaks.removeAll { it.x < -PEAK_W }
        coins.removeAll { it.x < -40 || it.taken }
        // New peaks come in at the right, the gap wandering up and down.
        val lastX = peaks.lastOrNull()?.x ?: (W * 0.6f)
        if (lastX < W) {
            val gap = maxOf(190f, 260f - metres * 0.01f)
            val prevMid = peaks.lastOrNull()?.let { (it.top + it.bottom) / 2 } ?: (H / 2)
            val mid = (prevMid + random.nextFloat() * 220f - 110f).coerceIn(gap / 2 + 40, H - GROUND - gap / 2 - 20)
            val x = lastX + 280f + random.nextFloat() * 60f
            peaks += Peaks(x, mid - gap / 2, mid + gap / 2)
            if (random.nextInt(3) > 0) coins += Coin(x + 140f, mid + random.nextFloat() * 80f - 40f)
        }
        for (c in coins) if (!c.taken && kotlin.math.hypot(c.x - X, c.y - y) < R + 16) { c.taken = true; coinsTaken++ }
        if (y + R > H - GROUND || peaks.any { hits(it) }) alive = false
    }

    /** Whether the gryphon (a circle at X, y) touches the peaks: each a triangle with its point at the gap. */
    private fun hits(p: Peaks): Boolean {
        val dx = kotlin.math.abs(X - p.x)
        if (dx > PEAK_W / 2 + R) return false
        // How far the triangles reach at the gryphon's x (they narrow towards their points).
        val f = (1 - maxOf(0f, dx - R * 0.6f) / (PEAK_W / 2)).coerceIn(0f, 1f)
        val topReach = p.top * f
        val bottomReach = H - (H - p.bottom) * f
        return y - R * 0.75f < topReach || y + R * 0.75f > bottomReach
    }

    companion object {
        const val W = 1000f
        const val H = 620f
        const val X = 160f
        const val R = 22f
        const val GRAVITY = 1250f
        const val FLAP = -400f
        const val PEAK_W = 120f
        const val GROUND = 0f
    }
}
