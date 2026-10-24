package thor.companion.games

import kotlin.random.Random

/**
 * Pipe Connect's rules: a square of pipe tiles around a well in the middle. Every tile can be
 * turned a quarter at a time. The pipes, turned right, make one tree from the well to every
 * lamp (the ends); it is solved when all of them carry the light. Sides are bits: up 1,
 * right 2, down 4, left 8.
 */
class Pipes private constructor(val size: Int, val pipes: IntArray) {
    var moves = 0
    var millis = 0L
    val well get() = size / 2 * size + size / 2

    /** Turns tile [i] a quarter clockwise. */
    fun turn(i: Int) {
        pipes[i] = rotate(pipes[i])
        moves++
    }

    /** The tiles the light reaches from the well. */
    fun lit(): BooleanArray {
        val on = BooleanArray(size * size)
        val todo = ArrayDeque<Int>()
        todo += well; on[well] = true
        while (todo.isNotEmpty()) {
            val a = todo.removeFirst()
            for (side in 0 until 4) {
                val bit = 1 shl side
                if (pipes[a] and bit == 0) continue
                val b = neighbour(a, side) ?: continue
                if (!on[b] && pipes[b] and (1 shl ((side + 2) % 4)) != 0) { on[b] = true; todo += b }
            }
        }
        return on
    }

    val won get() = lit().all { it }

    /** The tile next to [i] on [side] (0 up, 1 right, 2 down, 3 left), or null at the edge. */
    fun neighbour(i: Int, side: Int): Int? {
        val x = i % size; val y = i / size
        return when (side) {
            0 -> if (y > 0) i - size else null
            1 -> if (x < size - 1) i + 1 else null
            2 -> if (y < size - 1) i + size else null
            else -> if (x > 0) i - 1 else null
        }
    }

    fun save(): String = "$size,$moves,$millis;" + pipes.joinToString(",")

    companion object {
        val SIZES = listOf(5, 7, 9)
        val SIZE_NAMES = listOf("Small", "Medium", "Large")

        fun rotate(m: Int) = ((m shl 1) or (m shr 3)) and 15

        fun new(size: Int, random: Random = Random.Default): Pipes {
            while (true) {
                val p = IntArray(size * size)
                val g = Pipes(size, p)
                // A random tree from the well (grown from random tiles already in it).
                val inTree = BooleanArray(size * size)
                val grown = mutableListOf(g.well)
                inTree[g.well] = true
                while (grown.isNotEmpty()) {
                    val a = grown[random.nextInt(grown.size)]
                    // Few crossings (tiles with 4 pipes) make it calmer; keep branches to 3.
                    val open = (0 until 4).filter { s -> g.neighbour(a, s)?.let { !inTree[it] } ?: false }
                    if (open.isEmpty() || Integer.bitCount(p[a]) >= 3) { grown.remove(a); continue }
                    val side = open[random.nextInt(open.size)]
                    val b = g.neighbour(a, side)!!
                    p[a] = p[a] or (1 shl side)
                    p[b] = p[b] or (1 shl ((side + 2) % 4))
                    inTree[b] = true
                    grown += b
                }
                if (!inTree.all { it }) continue
                for (i in p.indices) repeat(random.nextInt(4)) { p[i] = rotate(p[i]) }
                if (!g.won) return g
            }
        }

        fun load(text: String): Pipes? = runCatching {
            val (h, body) = text.split(';')
            val v = h.split(',')
            val size = v[0].toInt()
            val p = body.split(',').map { it.toInt() }.toIntArray()
            require(p.size == size * size)
            Pipes(size, p).apply { moves = v[1].toInt(); millis = v[2].toLong() }
        }.getOrNull()
    }
}
