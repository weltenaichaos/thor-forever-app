package thor.companion.games

import kotlin.random.Random

/**
 * Word Search's rules: words hidden in a square of letters, in a straight line. Easy: across
 * and down only. Medium: diagonals too. Hard: also backwards. Drag from a word's first letter
 * to its last (either end) to find it.
 */
class WordSearch private constructor(val size: Int, val level: Int, val letters: CharArray, val words: List<Placed>) {
    /** A hidden word: its first letter's square and the step to the next letter. */
    class Placed(val word: String, val x: Int, val y: Int, val dx: Int, val dy: Int) {
        var found = false
        fun cell(k: Int, size: Int) = (y + dy * k) * size + x + dx * k
    }

    var millis = 0L
    val foundCount get() = words.count { it.found }
    val won get() = words.all { it.found }

    /** Marks the word that runs from square [a] to square [b] (either way) as found; it, or null. */
    fun select(a: Int, b: Int): Placed? {
        for (w in words) {
            if (w.found) continue
            val first = w.cell(0, size); val last = w.cell(w.word.length - 1, size)
            if ((a == first && b == last) || (a == last && b == first)) { w.found = true; return w }
        }
        return null
    }

    fun save(): String = "$size,$level,$millis;" + String(letters) + ";" +
        words.joinToString(",") { "${it.word}.${it.x}.${it.y}.${it.dx}.${it.dy}.${if (it.found) 1 else 0}" }

    companion object {
        val LEVELS = listOf("Easy", "Medium", "Hard")
        private val SIZES = intArrayOf(9, 11, 12)
        private val COUNTS = intArrayOf(7, 10, 12)
        /** Our own word list: plain adventure words. */
        val WORDS = listOf(
            "ANVIL", "ARROW", "AXE", "BANNER", "BARREL", "BOOTS", "BRIDGE", "CANDLE", "CASTLE", "CAVE", "CLOAK",
            "COMPASS", "CROWN", "DAGGER", "DRAGON", "EMBER", "FEATHER", "FOREST", "FORGE", "GEM", "GOBLET",
            "GRYPHON", "HAMMER", "HARBOR", "HELM", "HERB", "HORSE", "INN", "ISLAND", "KEY", "LANTERN", "MAP",
            "MEADOW", "MOON", "MOUNTAIN", "OAK", "ORCHARD", "PICKAXE", "POTION", "QUEST", "RAVEN", "RIVER",
            "RUNE", "SADDLE", "SCROLL", "SHIELD", "SHIP", "SPEAR", "SPELL", "STAFF", "STAR", "STORM", "SWORD",
            "TAVERN", "TORCH", "TOWER", "TREASURE", "VALLEY", "WAGON", "WIZARD", "WOLF",
        )

        fun new(level: Int, random: Random = Random.Default): WordSearch {
            val lv = level.coerceIn(0, 2)
            val size = SIZES[lv]
            val dirs = buildList {
                add(1 to 0); add(0 to 1)
                if (lv >= 1) { add(1 to 1); add(1 to -1) }
                if (lv >= 2) { add(-1 to 0); add(0 to -1); add(-1 to -1); add(-1 to 1) }
            }
            while (true) {
                val grid = CharArray(size * size) { ' ' }
                val placed = mutableListOf<Placed>()
                for (word in WORDS.filter { it.length <= size }.shuffled(random)) {
                    if (placed.size == COUNTS[lv]) break
                    for (attempt in 0 until 60) {
                        val (dx, dy) = dirs[random.nextInt(dirs.size)]
                        val x = random.nextInt(size); val y = random.nextInt(size)
                        val ex = x + dx * (word.length - 1); val ey = y + dy * (word.length - 1)
                        if (ex !in 0 until size || ey !in 0 until size) continue
                        val fits = word.indices.all { k -> grid[(y + dy * k) * size + x + dx * k].let { it == ' ' || it == word[k] } }
                        if (!fits) continue
                        for (k in word.indices) grid[(y + dy * k) * size + x + dx * k] = word[k]
                        placed += Placed(word, x, y, dx, dy)
                        break
                    }
                }
                if (placed.size < COUNTS[lv]) continue
                val fill = "ABCDEFGHIKLMNOPRSTUVWY"
                for (i in grid.indices) if (grid[i] == ' ') grid[i] = fill[random.nextInt(fill.length)]
                return WordSearch(size, lv, grid, placed.sortedBy { it.word })
            }
        }

        fun load(text: String): WordSearch? = runCatching {
            val p = text.split(';')
            val h = p[0].split(',')
            val size = h[0].toInt()
            require(p[1].length == size * size)
            val words = p[2].split(',').map { w ->
                val f = w.split('.')
                Placed(f[0], f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt()).apply { found = f[5] == "1" }
            }
            WordSearch(size, h[1].toInt(), p[1].toCharArray(), words).apply { millis = h[2].toLong() }
        }.getOrNull()
    }
}
