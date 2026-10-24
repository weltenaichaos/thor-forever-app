package thor.companion.games

import kotlin.random.Random

/**
 * Memory Pairs' rules: cards face down in a grid, each symbol twice. Turn two: a pair stays
 * up, two different ones turn back at the next card you touch. A turn is two cards.
 */
class Memory private constructor(val cols: Int, val rows: Int, val faces: IntArray) {
    val matched = BooleanArray(faces.size)
    /** The cards face up now and not matched (0, 1 or 2 of them). */
    val open = mutableListOf<Int>()
    var turns = 0
    var millis = 0L

    val pairs get() = faces.size / 2
    val found get() = matched.count { it } / 2
    val won get() = matched.all { it }
    /** Two different cards are up, waiting to turn back. */
    val missed get() = open.size == 2

    /** Turns card [i] up; false when it can't be (already up or matched). */
    fun flip(i: Int): Boolean {
        if (open.size == 2) open.clear()
        if (matched[i] || i in open) return false
        open += i
        if (open.size == 2) {
            turns++
            val (a, b) = open
            if (faces[a] == faces[b]) { matched[a] = true; matched[b] = true; open.clear() }
        }
        return true
    }

    /** Turns two different cards back down. */
    fun hide() { if (open.size == 2) open.clear() }

    fun save(): String = "$cols,$rows,$turns,$millis;" + faces.joinToString(",") + ";" +
        matched.joinToString("") { if (it) "1" else "0" }

    companion object {
        val SIZES = listOf(4 to 3, 6 to 4, 8 to 4)
        val SIZE_NAMES = listOf("4 x 3", "6 x 4", "8 x 4")

        fun new(cols: Int, rows: Int, random: Random = Random.Default): Memory {
            val kinds = (0 until Mahjong.KINDS).shuffled(random).take(cols * rows / 2)
            return Memory(cols, rows, (kinds + kinds).shuffled(random).toIntArray())
        }

        fun load(text: String): Memory? = runCatching {
            val p = text.split(';')
            val h = p[0].split(',')
            val cols = h[0].toInt(); val rows = h[1].toInt()
            val faces = p[1].split(',').map { it.toInt() }.toIntArray()
            require(faces.size == cols * rows && p[2].length == faces.size)
            Memory(cols, rows, faces).apply {
                turns = h[2].toInt(); millis = h[3].toLong()
                for (i in matched.indices) matched[i] = p[2][i] == '1'
            }
        }.getOrNull()
    }
}
