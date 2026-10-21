package thor.companion.games

import kotlin.random.Random

/**
 * Sudoku's rules: a 9 x 9 grid where every row, column and 3 x 3 box holds 1 to 9 once.
 * A new puzzle has exactly one solution (checked while numbers are taken away). Notes are
 * small pencil marks per square (a bit for each number).
 */
class Sudoku private constructor(
    val solution: IntArray,
    val given: BooleanArray,
    val values: IntArray,
    val notes: IntArray,
    val level: Int,
) {
    var millis = 0L
    var mistakes = 0

    val won get() = values.indices.all { values[it] == solution[it] }

    /** Puts [n] (1..9) in square [i], or clears it with 0; false for a given square. Clears the square's notes. */
    fun set(i: Int, n: Int): Boolean {
        if (given[i] || values[i] == n) return false
        values[i] = n
        notes[i] = 0
        if (n != 0 && n != solution[i]) mistakes++
        // The number goes from the notes in its row, column and box.
        if (n != 0 && n == solution[i]) for (j in peers(i)) notes[j] = notes[j] and (1 shl n).inv()
        return true
    }

    fun toggleNote(i: Int, n: Int): Boolean {
        if (given[i] || values[i] != 0) return false
        notes[i] = notes[i] xor (1 shl n)
        return true
    }

    /** Whether square [i]'s number is wrong (it is not the solution's). */
    fun wrong(i: Int) = values[i] != 0 && values[i] != solution[i]

    /** How many of [n] are placed right (9: that number is done). */
    fun placed(n: Int) = values.indices.count { values[it] == n && solution[it] == n }

    fun save(): String = listOf(level, millis, mistakes).joinToString(",") + ";" +
        solution.joinToString("") + ";" + given.joinToString("") { if (it) "1" else "0" } + ";" +
        values.joinToString("") + ";" + notes.joinToString(",")

    companion object {
        val LEVELS = listOf("Easy", "Medium", "Hard")
        /** How many squares a new puzzle shows at each level. */
        private val GIVENS = intArrayOf(40, 32, 27)

        fun peers(i: Int): Set<Int> {
            val r = i / 9; val c = i % 9; val br = r / 3 * 3; val bc = c / 3 * 3
            val out = HashSet<Int>()
            for (k in 0 until 9) { out += r * 9 + k; out += k * 9 + c; out += (br + k / 3) * 9 + bc + k % 3 }
            out -= i
            return out
        }
        private val PEERS = Array(81) { peers(it).toIntArray() }

        fun new(level: Int, random: Random = Random.Default): Sudoku {
            val full = IntArray(81)
            fill(full, 0, random)
            val grid = full.copyOf()
            val order = (0 until 81).shuffled(random)
            var shown = 81
            val target = GIVENS[level.coerceIn(0, 2)]
            for (i in order) {
                if (shown <= target) break
                val keep = grid[i]
                grid[i] = 0
                if (solutions(grid.copyOf(), 2) != 1) grid[i] = keep else shown--
            }
            return Sudoku(full, BooleanArray(81) { grid[it] != 0 }, grid, IntArray(81), level)
        }

        private fun allowed(g: IntArray, i: Int, n: Int) = PEERS[i].none { g[it] == n }

        private fun fill(g: IntArray, at: Int, random: Random): Boolean {
            if (at == 81) return true
            for (n in (1..9).shuffled(random)) {
                if (allowed(g, at, n)) { g[at] = n; if (fill(g, at + 1, random)) return true }
            }
            g[at] = 0
            return false
        }

        /** Counts the solutions of [g] up to [limit]. */
        fun solutions(g: IntArray, limit: Int): Int {
            // The empty square with the fewest choices first.
            var best = -1; var bestCount = 10; var bestMask = 0
            for (i in 0 until 81) {
                if (g[i] != 0) continue
                var mask = 0
                for (j in PEERS[i]) if (g[j] != 0) mask = mask or (1 shl g[j])
                val count = 9 - Integer.bitCount(mask and 0x3FE)
                if (count == 0) return 0
                if (count < bestCount) { best = i; bestCount = count; bestMask = mask }
            }
            if (best < 0) return 1
            var found = 0
            for (n in 1..9) {
                if (bestMask and (1 shl n) != 0) continue
                g[best] = n
                found += solutions(g, limit - found)
                if (found >= limit) break
            }
            g[best] = 0
            return found
        }

        fun load(text: String): Sudoku? = runCatching {
            val p = text.split(';')
            val h = p[0].split(',')
            val sol = p[1].map { it.digitToInt() }.toIntArray()
            val given = p[2].map { it == '1' }.toBooleanArray()
            val vals = p[3].map { it.digitToInt() }.toIntArray()
            val notes = p[4].split(',').map { it.toInt() }.toIntArray()
            require(sol.size == 81 && given.size == 81 && vals.size == 81 && notes.size == 81)
            Sudoku(sol, given, vals, notes, h[0].toInt()).apply { millis = h[1].toLong(); mistakes = h[2].toInt() }
        }.getOrNull()
    }
}
