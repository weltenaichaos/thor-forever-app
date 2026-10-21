package thor.companion.games

import kotlin.random.Random

/**
 * 2048's rules: a 4 x 4 board of numbers. A swipe slides every tile that way; two equal
 * tiles that meet become one with their sum (worth that many points). After every swipe
 * that moved something, a new 2 (sometimes a 4) appears on a free square.
 */
class Tiles2048(private val random: Random = Random.Default) {
    val cells = IntArray(SIZE * SIZE)
    var score = 0
    /** Set once a 2048 tile was made; you can play on after it. */
    var won = false

    enum class Dir { LEFT, RIGHT, UP, DOWN }

    init { start() }

    fun start() {
        cells.fill(0); score = 0; won = false
        spawn(); spawn()
    }

    val over: Boolean get() = Dir.entries.none { canMove(it) }

    fun canMove(dir: Dir): Boolean {
        val copy = cells.copyOf()
        return slide(copy, dir).first
    }

    /** Swipes [dir]; false when nothing could move (then nothing new appears). */
    fun move(dir: Dir): Boolean {
        val (moved, points) = slide(cells, dir)
        if (!moved) return false
        score += points
        if (cells.any { it >= 2048 }) won = true
        spawn()
        return true
    }

    private fun spawn() {
        val free = cells.indices.filter { cells[it] == 0 }
        if (free.isEmpty()) return
        cells[free[random.nextInt(free.size)]] = if (random.nextInt(10) == 0) 4 else 2
    }

    fun save(): String = "$score,${if (won) 1 else 0};" + cells.joinToString(",")

    companion object {
        const val SIZE = 4

        /** Slides [b] in place; whether anything moved, and the points of the merges. */
        fun slide(b: IntArray, dir: Dir): Pair<Boolean, Int> {
            var moved = false; var points = 0
            for (line in 0 until SIZE) {
                val idx = IntArray(SIZE) { pos ->
                    when (dir) {
                        Dir.LEFT -> line * SIZE + pos
                        Dir.RIGHT -> line * SIZE + (SIZE - 1 - pos)
                        Dir.UP -> pos * SIZE + line
                        Dir.DOWN -> (SIZE - 1 - pos) * SIZE + line
                    }
                }
                val values = idx.map { b[it] }.filter { it != 0 }
                val out = mutableListOf<Int>()
                var i = 0
                while (i < values.size) {
                    if (i + 1 < values.size && values[i] == values[i + 1]) {
                        out += values[i] * 2; points += values[i] * 2; i += 2
                    } else { out += values[i]; i++ }
                }
                for (pos in 0 until SIZE) {
                    val v = out.getOrElse(pos) { 0 }
                    if (b[idx[pos]] != v) moved = true
                    b[idx[pos]] = v
                }
            }
            return moved to points
        }

        fun load(text: String, random: Random = Random.Default): Tiles2048? = runCatching {
            val (head, body) = text.split(';')
            val h = head.split(',')
            val v = body.split(',').map { it.toInt() }
            require(v.size == SIZE * SIZE)
            Tiles2048(random).apply {
                score = h[0].toInt(); won = h[1] == "1"
                for (i in cells.indices) cells[i] = v[i]
            }
        }.getOrNull()
    }
}
