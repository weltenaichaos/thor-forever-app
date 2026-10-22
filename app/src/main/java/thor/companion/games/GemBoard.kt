package thor.companion.games

import kotlin.random.Random

/**
 * Gem Match's rules, without anything to draw: an 8 x 8 board of six kinds of gem.
 * Swapping two neighbours that makes a row of three or more clears them; the gems above
 * fall down and new ones drop in from the top, which can clear more (a chain, worth more).
 * A round has [MOVES] swaps; reaching the [goal] by then opens the next level.
 */
class GemBoard(private val random: Random = Random.Default) {
    val cells = IntArray(SIZE * SIZE)
    var score = 0
    var movesLeft = MOVES
    var level = 1
    /** Score at the start of this level's round (the round's own points are [score] minus this). */
    var levelStart = 0

    val goal get() = goalFor(level)
    val roundScore get() = score - levelStart
    val roundOver get() = movesLeft <= 0

    /** One step of a swap's chain, for drawing: the board with the cleared gems still in it, and which ones go. */
    class Step(val board: IntArray, val cleared: Set<Int>, val points: Int)

    init { fill() }

    /** A fresh board with no row of three already in it, and at least one swap that works. */
    fun fill() {
        do {
            for (i in cells.indices) {
                var k: Int
                do { k = random.nextInt(KINDS) } while (makesRow(i, k))
                cells[i] = k
            }
        } while (!hasMove())
    }

    private fun makesRow(i: Int, k: Int): Boolean {
        val x = i % SIZE; val y = i / SIZE
        if (x >= 2 && cells[i - 1] == k && cells[i - 2] == k) return true
        if (y >= 2 && cells[i - SIZE] == k && cells[i - 2 * SIZE] == k) return true
        return false
    }

    fun neighbours(a: Int, b: Int): Boolean {
        if (a !in cells.indices || b !in cells.indices) return false
        val ax = a % SIZE; val ay = a / SIZE; val bx = b % SIZE; val by = b / SIZE
        return Math.abs(ax - bx) + Math.abs(ay - by) == 1
    }

    /**
     * Swaps [a] and [b]. Null when it is not allowed (not neighbours, no moves left, or no
     * row comes of it: then the board stays as it was). Otherwise the chain's steps, in order;
     * the board then holds the end result (with a new shuffle if no swap was left).
     */
    fun swap(a: Int, b: Int): List<Step>? {
        if (roundOver || !neighbours(a, b)) return null
        swapCells(a, b)
        if (findRows().isEmpty()) { swapCells(a, b); return null }
        movesLeft--
        val steps = mutableListOf<Step>()
        var chain = 1
        while (true) {
            val rows = findRows()
            if (rows.isEmpty()) break
            var points = 0
            val gone = HashSet<Int>()
            for (r in rows) { points += r.size * 20 * chain; gone += r }
            score += points
            steps += Step(cells.copyOf(), gone, points)
            for (i in gone) cells[i] = EMPTY
            fall()
            chain++
        }
        if (!hasMove()) shuffle()
        return steps
    }

    /** After the round: on to the next level when the goal was reached, else the same level again. */
    fun nextRound(): Boolean {
        val won = roundScore >= goal
        if (won) level++
        if (!won) score = levelStart
        levelStart = score
        movesLeft = MOVES
        fill()
        return won
    }

    private fun swapCells(a: Int, b: Int) { val t = cells[a]; cells[a] = cells[b]; cells[b] = t }

    /** Every row or column of three or more of a kind, each as its cells. */
    fun findRows(): List<Set<Int>> {
        val out = mutableListOf<Set<Int>>()
        for (horizontal in listOf(true, false)) for (line in 0 until SIZE) {
            var start = 0
            while (start < SIZE) {
                val k = cells[at(horizontal, line, start)]
                var end = start + 1
                while (end < SIZE && k != EMPTY && cells[at(horizontal, line, end)] == k) end++
                if (k != EMPTY && end - start >= 3) out += (start until end).map { at(horizontal, line, it) }.toSet()
                start = end
            }
        }
        return out
    }

    private fun at(horizontal: Boolean, line: Int, pos: Int) = if (horizontal) line * SIZE + pos else pos * SIZE + line

    /** Gems drop into the holes below them, and new ones fill the top. */
    private fun fall() {
        for (x in 0 until SIZE) {
            var write = SIZE - 1
            for (y in SIZE - 1 downTo 0) {
                val k = cells[y * SIZE + x]
                if (k != EMPTY) { cells[write * SIZE + x] = k; write-- }
            }
            for (y in write downTo 0) cells[y * SIZE + x] = random.nextInt(KINDS)
        }
    }

    /** Whether any one swap would make a row. */
    fun hasMove(): Boolean = hint() != null

    /** A swap that makes a row (two cells), or null. */
    fun hint(): Pair<Int, Int>? {
        for (i in cells.indices) for (j in listOf(i + 1, i + SIZE)) {
            if (!neighbours(i, j)) continue
            swapCells(i, j)
            val ok = findRows().isNotEmpty()
            swapCells(i, j)
            if (ok) return i to j
        }
        return null
    }

    private fun shuffle() {
        do {
            for (i in cells.indices.reversed()) { val j = random.nextInt(i + 1); swapCells(i, j) }
        } while (findRows().isNotEmpty() || !hasMove())
    }

    fun save(): String = listOf(score, movesLeft, level, levelStart).joinToString(",") + ";" + cells.joinToString("")

    companion object {
        const val SIZE = 8
        const val KINDS = 6
        const val MOVES = 25
        const val EMPTY = -1
        fun goalFor(level: Int) = 1000 + level * 500

        fun load(text: String, random: Random = Random.Default): GemBoard? = runCatching {
            val (head, body) = text.split(';')
            val n = head.split(',').map { it.toInt() }
            require(body.length == SIZE * SIZE)
            GemBoard(random).apply {
                score = n[0]; movesLeft = n[1]; level = n[2]; levelStart = n[3]
                for (i in cells.indices) cells[i] = body[i].digitToInt().also { require(it in 0 until KINDS) }
            }
        }.getOrNull()
    }
}
