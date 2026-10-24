package thor.companion.games

import kotlin.random.Random

/**
 * Minesweeper's rules. The mines are laid at the first tap, never on or next to it, so the
 * first tap always opens a patch. Opening a square shows how many mines touch it; a 0 opens
 * its neighbours by itself. Tapping an open number whose mines are all flagged opens the rest
 * around it. All safe squares open: won. A mine: lost.
 */
class MineField(val width: Int, val height: Int, val mines: Int, private val random: Random = Random.Default) {
    val mine = BooleanArray(width * height)
    val open = BooleanArray(width * height)
    val flag = BooleanArray(width * height)
    var laid = false
    var lost = false
    /** The mine that went off, or -1. */
    var boom = -1
    /** Play time so far, counted by the screen while it is shown. */
    var millis = 0L

    val won get() = laid && !lost && open.count { it } == width * height - mines
    val over get() = lost || won
    val flagsLeft get() = mines - flag.count { it }

    fun around(i: Int): List<Int> {
        val x = i % width; val y = i / width
        val out = ArrayList<Int>(8)
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = x + dx; val ny = y + dy
            if (nx in 0 until width && ny in 0 until height) out += ny * width + nx
        }
        return out
    }

    fun count(i: Int) = around(i).count { mine[it] }

    private fun lay(safe: Int) {
        val keepClear = around(safe).toSet() + safe
        val spots = (0 until width * height).filter { it !in keepClear }.shuffled(random)
        for (i in spots.take(mines)) mine[i] = true
        laid = true
    }

    /** Opens square [i] (or, on an open number, its unflagged neighbours). False when nothing happened. */
    fun reveal(i: Int): Boolean {
        if (over || i !in open.indices || flag[i]) return false
        if (!laid) lay(i)
        if (open[i]) {
            val n = around(i)
            if (count(i) == 0 || n.count { flag[it] } != count(i)) return false
            var any = false
            for (j in n) if (!open[j] && !flag[j]) { openOne(j); any = true }
            return any
        }
        openOne(i)
        return true
    }

    private fun openOne(i: Int) {
        if (mine[i]) { open[i] = true; lost = true; boom = i; return }
        val todo = ArrayDeque<Int>()
        todo += i
        while (todo.isNotEmpty()) {
            val j = todo.removeFirst()
            if (open[j] || flag[j]) continue
            open[j] = true
            if (count(j) == 0) for (k in around(j)) if (!open[k] && !mine[k]) todo += k
        }
    }

    fun toggleFlag(i: Int): Boolean {
        if (over || i !in open.indices || open[i]) return false
        flag[i] = !flag[i]
        return true
    }

    fun save(): String {
        val cells = (0 until width * height).joinToString("") { i ->
            ((if (mine[i]) 1 else 0) + (if (open[i]) 2 else 0) + (if (flag[i]) 4 else 0)).toString()
        }
        return "$width,$height,$mines,${if (laid) 1 else 0},${if (lost) 1 else 0},$boom,$millis;$cells"
    }

    companion object {
        /** The three sizes: columns, rows, mines. */
        val SIZES = listOf(Triple(10, 7, 10), Triple(16, 10, 28), Triple(20, 12, 45))
        val SIZE_NAMES = listOf("Small", "Medium", "Large")

        fun load(text: String, random: Random = Random.Default): MineField? = runCatching {
            val (head, body) = text.split(';')
            val h = head.split(',')
            MineField(h[0].toInt(), h[1].toInt(), h[2].toInt(), random).apply {
                require(body.length == width * height)
                laid = h[3] == "1"; lost = h[4] == "1"; boom = h[5].toInt(); millis = h[6].toLong()
                for (i in body.indices) {
                    val v = body[i].digitToInt()
                    mine[i] = v and 1 != 0; open[i] = v and 2 != 0; flag[i] = v and 4 != 0
                }
            }
        }.getOrNull()
    }
}
