package thor.companion.games

import kotlin.random.Random

/**
 * Mahjong solitaire's rules: 72 tiles in three layers, 18 faces four times each. Two free
 * tiles with the same face go away together. A tile is free when nothing lies on it and its
 * left or its right side is open. Every deal can be cleared (it is dealt by playing it
 * backwards); Shuffle deals the rest again the same way when you are stuck.
 */
class Mahjong private constructor(val faces: IntArray) {
    /** Removed tiles, in pairs, for Undo. */
    private val removed = ArrayDeque<Pair<Int, Int>>()
    val gone = BooleanArray(SPOTS.size)
    var millis = 0L
    var shuffles = 0

    val left get() = gone.count { !it }
    val won get() = left == 0

    fun free(i: Int): Boolean = !gone[i] && Companion.free(i) { !gone[it] }

    /** Takes [a] and [b] away when they are a free pair with the same face. */
    fun match(a: Int, b: Int): Boolean {
        if (a == b || !free(a) || !free(b) || faces[a] != faces[b]) return false
        gone[a] = true; gone[b] = true
        removed += a to b
        return true
    }

    fun undo(): Boolean {
        val (a, b) = removed.removeLastOrNull() ?: return false
        gone[a] = false; gone[b] = false
        return true
    }

    /** A free pair to take, or null when there is none (then Shuffle). */
    fun hint(): Pair<Int, Int>? {
        val free = SPOTS.indices.filter { free(it) }
        for (i in free.indices) for (j in i + 1 until free.size) if (faces[free[i]] == faces[free[j]]) return free[i] to free[j]
        return null
    }

    /**
     * Deals the faces still on the table anew so the rest can be cleared. When the tiles left
     * lie so that no deal can clear them (say a stack with only its top tile free), it first
     * puts back the last pairs taken, as few as needed. Returns how many pairs came back.
     */
    fun shuffle(random: Random = Random.Default): Int {
        var back = 0
        while (true) {
            val spots = SPOTS.indices.filter { !gone[it] }
            val dealt = deal(spots, spots.map { faces[it] }, random)
            if (dealt != null) {
                for ((k, s) in spots.withIndex()) faces[s] = dealt[k]
                shuffles++
                return back
            }
            if (!undo()) return back
            back++
        }
    }

    fun save(): String = "$millis,$shuffles;" + faces.joinToString(",") + ";" + gone.joinToString("") { if (it) "1" else "0" }

    /** A place for a tile: its layer and its square in the layer grid. */
    class Spot(val layer: Int, val col: Int, val row: Int)

    companion object {
        const val KINDS = 18
        /** Layer 0: 9 x 5, layer 1: 7 x 3 on its middle, layer 2: 3 x 2 on top (72 tiles). */
        val SPOTS: List<Spot> = buildList {
            for (r in 0 until 5) for (c in 0 until 9) add(Spot(0, c, r))
            for (r in 1 until 4) for (c in 1 until 8) add(Spot(1, c, r))
            for (r in 1 until 3) for (c in 3 until 6) add(Spot(2, c, r))
        }
        private val AT = SPOTS.withIndex().associate { (i, s) -> Triple(s.layer, s.col, s.row) to i }

        fun free(i: Int, present: (Int) -> Boolean): Boolean {
            val s = SPOTS[i]
            for (l in s.layer + 1..2) AT[Triple(l, s.col, s.row)]?.let { if (present(it)) return false }
            val leftTaken = AT[Triple(s.layer, s.col - 1, s.row)]?.let(present) ?: false
            val rightTaken = AT[Triple(s.layer, s.col + 1, s.row)]?.let(present) ?: false
            return !leftTaken || !rightTaken
        }

        fun new(random: Random = Random.Default): Mahjong {
            val all = SPOTS.indices.toList()
            val faces = (0 until KINDS).flatMap { k -> List(4) { k } }
            while (true) deal(all, faces, random)?.let { return Mahjong(it.toIntArray()) }
        }

        /**
         * Gives [spots] the [faces] so that they can all be cleared: free pairs are taken away
         * at random (as if playing backwards) and each gets one face pair. Null if it got stuck.
         */
        private fun deal(spots: List<Int>, faces: List<Int>, random: Random): List<Int>? {
            repeat(200) {
                val present = HashSet(spots)
                val pairs = faces.groupBy { it }.flatMap { (k, v) -> List(v.size / 2) { k } }.shuffled(random).toMutableList()
                val out = HashMap<Int, Int>()
                var ok = true
                while (present.isNotEmpty()) {
                    val free = present.filter { free(it) { j -> j in present } }
                    if (free.size < 2) { ok = false; break }
                    val a = free[random.nextInt(free.size)]
                    val rest = free - a
                    val b = rest[random.nextInt(rest.size)]
                    val k = pairs.removeAt(pairs.size - 1)
                    out[a] = k; out[b] = k
                    present -= a; present -= b
                }
                if (ok) return spots.map { out.getValue(it) }
            }
            return null
        }

        fun load(text: String): Mahjong? = runCatching {
            val p = text.split(';')
            val h = p[0].split(',')
            val faces = p[1].split(',').map { it.toInt() }.toIntArray()
            require(faces.size == SPOTS.size && p[2].length == SPOTS.size)
            Mahjong(faces).apply {
                millis = h[0].toLong(); shuffles = h[1].toInt()
                for (i in gone.indices) gone[i] = p[2][i] == '1'
            }
        }.getOrNull()
    }
}
