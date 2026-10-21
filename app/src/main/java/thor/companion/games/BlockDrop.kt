package thor.companion.games

import kotlin.random.Random

/**
 * Block Drop's rules: an 8 x 8 board and three shapes to place, anywhere they fit. A full
 * row or column clears (several at once, and in a row of turns, count more). When all three
 * are placed, three new ones come. No room for any of them: the game is over.
 */
class BlockDrop(private val random: Random = Random.Default) {
    /** 0 empty, else the colour (1..7) of the shape that filled it. */
    val cells = IntArray(SIZE * SIZE)
    /** The three shapes on offer (null once placed), each an index into [SHAPES]. */
    val offer = arrayOfNulls<Int>(3)
    val colours = IntArray(3)
    var score = 0
    /** Turns in a row that cleared something (more points each time). */
    var streak = 0
    /** The rows and columns the last placement cleared, for drawing them going. */
    var lastCleared: List<Int> = emptyList()
        private set

    init { refill() }

    private fun refill() {
        for (k in 0 until 3) { offer[k] = random.nextInt(SHAPES.size); colours[k] = 1 + random.nextInt(7) }
    }

    fun fits(shape: Int, x: Int, y: Int): Boolean = SHAPES[shape].all { (dx, dy) ->
        val cx = x + dx; val cy = y + dy
        cx in 0 until SIZE && cy in 0 until SIZE && cells[cy * SIZE + cx] == 0
    }

    fun fitsAnywhere(shape: Int) = (0 until SIZE).any { y -> (0 until SIZE).any { x -> fits(shape, x, y) } }

    val over get() = offer.filterNotNull().none { fitsAnywhere(it) }

    /** Puts offered shape [k] with its top left at [x], [y]; false when it doesn't fit. */
    fun place(k: Int, x: Int, y: Int): Boolean {
        val shape = offer[k] ?: return false
        if (!fits(shape, x, y)) return false
        for ((dx, dy) in SHAPES[shape]) cells[(y + dy) * SIZE + x + dx] = colours[k]
        score += SHAPES[shape].size
        offer[k] = null
        val rows = (0 until SIZE).filter { r -> (0 until SIZE).all { cells[r * SIZE + it] != 0 } }
        val cols = (0 until SIZE).filter { c -> (0 until SIZE).all { cells[it * SIZE + c] != 0 } }
        lastCleared = rows.map { it } + cols.map { SIZE + it }
        for (r in rows) for (c in 0 until SIZE) cells[r * SIZE + c] = 0
        for (c in cols) for (r in 0 until SIZE) cells[r * SIZE + c] = 0
        val lines = rows.size + cols.size
        if (lines > 0) { streak++; score += 10 * lines * lines * streak } else streak = 0
        if (offer.all { it == null }) refill()
        return true
    }

    fun save(): String = "$score,$streak;" + cells.joinToString("") + ";" +
        (0 until 3).joinToString(",") { "${offer[it] ?: -1}.${colours[it]}" }

    companion object {
        const val SIZE = 8
        /** The shapes, as squares from their top left. */
        val SHAPES: List<List<Pair<Int, Int>>> = listOf(
            "X", "XX", "XXX", "XXXX", "XXXXX",
            "X|X", "X|X|X", "X|X|X|X", "X|X|X|X|X",
            "XX|XX", "XXX|XXX|XXX", "XXX|XXX",
            "X.|XX", ".X|XX", "XX|X.", "XX|.X",
            "X..|X..|XXX", "XXX|..X|..X", "XXX|X..|X..", "..X|..X|XXX",
            "XXX|.X.", ".X.|XXX", "XX.|.XX", ".XX|XX.", "X.|XX|.X", ".X|XX|X.",
            "X.|X.|XX", "XX|.X|.X", "XXX|X..", "..X|XXX",
        ).map { s -> s.split('|').flatMapIndexed { y, row -> row.mapIndexedNotNull { x, ch -> if (ch == 'X') x to y else null } } }

        fun load(text: String, random: Random = Random.Default): BlockDrop? = runCatching {
            val p = text.split(';')
            val h = p[0].split(',')
            require(p[1].length == SIZE * SIZE)
            BlockDrop(random).apply {
                score = h[0].toInt(); streak = h[1].toInt()
                for (i in cells.indices) cells[i] = p[1][i].digitToInt()
                p[2].split(',').forEachIndexed { k, o ->
                    val (s, c) = o.split('.').map { it.toInt() }
                    offer[k] = s.takeIf { it in SHAPES.indices }; colours[k] = c
                }
            }
        }.getOrNull()
    }
}
