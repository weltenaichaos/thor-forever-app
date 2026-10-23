package thor.companion.games

import kotlin.random.Random

/**
 * The slide puzzle's rules: numbered tiles in a square with one gap. A tile in the gap's row or
 * column slides towards it (with the tiles between). Solved when 1, 2, 3... read in order and
 * the gap is last. New puzzles are mixed by random slides, so each one can be solved.
 */
class SlidePuzzle private constructor(val size: Int, val tiles: IntArray) {
    var moves = 0
    var millis = 0L
    val gap get() = tiles.indexOf(0)
    val won get() = tiles.indices.all { tiles[it] == if (it == tiles.size - 1) 0 else it + 1 }
    /** Whether tile [i] sits in its own place. */
    fun home(i: Int) = tiles[i] == i + 1

    /** Slides tile [i] (and those between it and the gap) into the gap; false when not in line with it. */
    fun tap(i: Int): Boolean {
        val g = gap
        if (i == g) return false
        val step = when {
            i / size == g / size -> if (i < g) -1 else 1
            i % size == g % size -> if (i < g) -size else size
            else -> return false
        }
        var at = g
        while (at != i) { tiles[at] = tiles[at + step]; at += step }
        tiles[i] = 0
        moves++
        return true
    }

    fun save(): String = "$size,$moves,$millis;" + tiles.joinToString(",")

    companion object {
        val SIZES = listOf(3, 4, 5)
        val SIZE_NAMES = listOf("3 x 3", "4 x 4", "5 x 5")

        fun new(size: Int, random: Random = Random.Default): SlidePuzzle {
            val p = SlidePuzzle(size, IntArray(size * size) { if (it == size * size - 1) 0 else it + 1 })
            var last = -1
            repeat(size * size * 30) {
                val g = p.gap
                val options = listOf(g - size, g + size, g - 1, g + 1).filter { n ->
                    n in p.tiles.indices && n != last && (n / size == g / size || n % size == g % size)
                }
                val n = options[random.nextInt(options.size)]
                p.tap(n); last = g
            }
            if (p.won) p.tap(if (p.gap % size > 0) p.gap - 1 else p.gap + 1)
            p.moves = 0
            return p
        }

        fun load(text: String): SlidePuzzle? = runCatching {
            val (h, body) = text.split(';')
            val v = h.split(',')
            val size = v[0].toInt()
            val t = body.split(',').map { it.toInt() }.toIntArray()
            require(t.size == size * size && t.sorted() == (0 until size * size).toList())
            SlidePuzzle(size, t).apply { moves = v[1].toInt(); millis = v[2].toLong() }
        }.getOrNull()
    }
}
