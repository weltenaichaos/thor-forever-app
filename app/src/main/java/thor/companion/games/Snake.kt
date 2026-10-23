package thor.companion.games

import kotlin.random.Random

/**
 * Snake's rules: a snake on a 24 x 14 field moves one square per tick. Eating a gem makes it
 * one longer and a little faster. Running into the edge or itself ends the round. Turns are
 * queued (two quick swipes make a U-turn without biting itself).
 */
class Snake(private val random: Random = Random.Default) {
    /** The squares from head to tail. */
    val body = ArrayDeque<Int>()
    /** Direction: 0 up, 1 right, 2 down, 3 left. */
    var dir = 1
        private set
    private val queued = ArrayDeque<Int>()
    var gem = -1
        private set
    var alive = true
        private set
    var started = false
        private set
    private var clock = 0f
    val length get() = body.size
    /** Seconds per square: from 0.15 down to 0.07 as it grows. */
    val tick get() = maxOf(0.07f, 0.15f - (length - 4) * 0.002f)

    init {
        val y = H / 2
        for (x in 6 downTo 3) body += y * W + x
        placeGem()
    }

    private fun placeGem() {
        val free = (0 until W * H).filter { it !in body }
        gem = if (free.isEmpty()) -1 else free[random.nextInt(free.size)]
    }

    /** Asks to turn to [d]; a turn straight back is ignored. */
    fun turn(d: Int) {
        started = true
        val last = queued.lastOrNull() ?: dir
        if (d == last || d == (last + 2) % 4 || queued.size >= 2) return
        queued += d
    }

    /** Moves on by [dt] seconds (whole squares as they come). */
    fun step(dt: Float) {
        if (!alive || !started) return
        clock += dt
        while (clock >= tick && alive) { clock -= tick; move() }
    }

    fun move() {
        queued.removeFirstOrNull()?.let { dir = it }
        val head = body.first()
        val x = head % W; val y = head / W
        val nx = x + when (dir) { 1 -> 1; 3 -> -1; else -> 0 }
        val ny = y + when (dir) { 0 -> -1; 2 -> 1; else -> 0 }
        if (nx !in 0 until W || ny !in 0 until H) { alive = false; return }
        val next = ny * W + nx
        val eats = next == gem
        if (!eats) body.removeLast()
        if (next in body) { alive = false; if (!eats) body.addLast(head); return }
        body.addFirst(next)
        if (eats) placeGem()
    }

    companion object {
        const val W = 24
        const val H = 14
    }
}
