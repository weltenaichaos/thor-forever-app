package thor.companion.games

import kotlin.random.Random

/**
 * Nonogram (Picross) rules: the numbers beside each row and above each column say how long
 * the runs of filled squares are, in order. A new puzzle can be solved by logic alone, one
 * line at a time (checked when it is made). It is solved when every line matches its numbers.
 */
class Nonogram private constructor(val size: Int, val picture: BooleanArray) {
    /** 0 empty, 1 filled, 2 marked as empty (an X). */
    val marks = IntArray(size * size)
    var millis = 0L

    val rows: List<List<Int>> = List(size) { r -> runs(List(size) { picture[r * size + it] }) }
    val cols: List<List<Int>> = List(size) { c -> runs(List(size) { picture[it * size + c] }) }

    fun rowDone(r: Int) = runs(List(size) { marks[r * size + it] == 1 }) == rows[r]
    fun colDone(c: Int) = runs(List(size) { marks[it * size + c] == 1 }) == cols[c]
    val won get() = (0 until size).all { rowDone(it) && colDone(it) }

    fun save(): String = "$size,$millis;" + picture.joinToString("") { if (it) "1" else "0" } + ";" + marks.joinToString("")

    companion object {
        val SIZES = listOf(8, 10, 12)
        val SIZE_NAMES = listOf("Small", "Medium", "Large")

        fun runs(line: List<Boolean>): List<Int> {
            val out = mutableListOf<Int>(); var n = 0
            for (b in line) { if (b) n++ else if (n > 0) { out += n; n = 0 } }
            if (n > 0) out += n
            return out
        }

        fun new(size: Int, random: Random = Random.Default): Nonogram {
            while (true) {
                // Clumpy pictures (neighbours tend to match) read better than noise.
                val p = BooleanArray(size * size)
                for (i in p.indices) {
                    val up = if (i >= size) p[i - size] else random.nextBoolean()
                    val left = if (i % size > 0) p[i - 1] else random.nextBoolean()
                    p[i] = when {
                        up == left -> random.nextInt(100) < (if (up) 80 else 22)
                        else -> random.nextBoolean()
                    }
                }
                if (p.count { it } < size * size / 3) continue
                val g = Nonogram(size, p)
                if (g.rows.any { it.isEmpty() } || g.cols.any { it.isEmpty() }) continue
                if (lineSolvable(g)) return g
            }
        }

        /** Whether line-by-line logic alone fills in the whole picture. */
        fun lineSolvable(g: Nonogram): Boolean {
            val n = g.size
            val known = IntArray(n * n) { -1 }  // -1 unknown, 0 empty, 1 filled
            var changed = true
            while (changed) {
                changed = false
                for (horizontal in listOf(true, false)) for (k in 0 until n) {
                    val idx = IntArray(n) { if (horizontal) k * n + it else it * n + k }
                    val clue = if (horizontal) g.rows[k] else g.cols[k]
                    val line = IntArray(n) { known[idx[it]] }
                    val result = solveLine(clue, line) ?: return false
                    for (j in 0 until n) if (line[j] == -1 && result[j] != -1) { known[idx[j]] = result[j]; changed = true }
                }
            }
            return known.none { it == -1 }
        }

        /** What every placement of [clue] that fits [line] agrees on (-1 where they differ); null when none fits. */
        fun solveLine(clue: List<Int>, line: IntArray): IntArray? {
            val n = line.size
            var agree: IntArray? = null
            val cur = IntArray(n)
            fun place(run: Int, from: Int) {
                if (run == clue.size) {
                    for (j in from until n) { if (line[j] == 1) return }
                    for (j in from until n) cur[j] = 0
                    val a = agree
                    if (a == null) agree = cur.copyOf() else for (j in 0 until n) if (a[j] != cur[j]) a[j] = -1
                    return
                }
                val len = clue[run]
                val rest = clue.drop(run + 1).sum() + (clue.size - run - 1)
                for (start in from..(n - len - rest)) {
                    // Squares from 'from' up to start are empty, then the run, then one empty (unless at the end).
                    var ok = true
                    for (j in from until start) if (line[j] == 1) { ok = false; break }
                    if (!ok) break
                    for (j in start until start + len) if (line[j] == 0) { ok = false; break }
                    if (ok && start + len < n && line[start + len] == 1) ok = false
                    if (!ok) continue
                    for (j in from until start) cur[j] = 0
                    for (j in start until start + len) cur[j] = 1
                    if (start + len < n) cur[start + len] = 0
                    place(run + 1, minOf(n, start + len + 1))
                }
            }
            place(0, 0)
            return agree
        }

        fun load(text: String): Nonogram? = runCatching {
            val p = text.split(';')
            val h = p[0].split(',')
            val size = h[0].toInt()
            require(p[1].length == size * size && p[2].length == size * size)
            Nonogram(size, p[1].map { it == '1' }.toBooleanArray()).apply {
                millis = h[1].toLong()
                for (i in marks.indices) marks[i] = p[2][i].digitToInt()
            }
        }.getOrNull()
    }
}
