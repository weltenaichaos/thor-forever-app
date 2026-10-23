package thor.companion.games

import kotlin.random.Random

/**
 * Klondike solitaire's rules (one card at a time from the stock, as often through as you
 * like). Piles: the stock, the waste, four foundations (ace up to king in one suit) and
 * seven columns (down in alternating colours; only a king on an empty column).
 */
class Klondike(private val random: Random = Random.Default) {
    class Card(val rank: Int, val suit: Int, var up: Boolean) {
        /** Hearts (1) and diamonds (2) are red. */
        val red get() = suit == 1 || suit == 2
        fun copy() = Card(rank, suit, up)
    }

    val piles = List(PILES) { mutableListOf<Card>() }
    var moves = 0
    /** Play time so far, counted by the screen while it is shown. */
    var millis = 0L
    private val undo = ArrayDeque<String>()

    init { deal() }

    fun deal() {
        piles.forEach { it.clear() }
        val deck = (0 until 52).map { Card(it % 13 + 1, it / 13, false) }.shuffled(random).toMutableList()
        for (c in 0 until 7) for (r in 0..c) piles[TABLEAU + c] += deck.removeAt(deck.size - 1).also { it.up = r == c }
        piles[STOCK] += deck
        moves = 0; millis = 0; undo.clear()
    }

    val won get() = (0 until 4).all { piles[FOUNDATION + it].size == 13 }

    /** Everything is face up and the stock is used up: the rest can go home by itself. */
    val canFinish get() = !won && piles[STOCK].isEmpty() && piles[WASTE].isEmpty() &&
        (0 until 7).all { c -> piles[TABLEAU + c].all { it.up } }

    val canUndo get() = undo.isNotEmpty()

    /** A tap on the stock: the next card onto the waste, or the waste back when the stock is empty. */
    fun draw(): Boolean {
        val stock = piles[STOCK]; val waste = piles[WASTE]
        if (stock.isEmpty() && waste.isEmpty()) return false
        remember()
        if (stock.isNotEmpty()) waste += stock.removeAt(stock.size - 1).also { it.up = true }
        else { while (waste.isNotEmpty()) stock += waste.removeAt(waste.size - 1).also { it.up = false } }
        moves++
        return true
    }

    /** Whether the cards from [index] down in pile [from] can be picked up together. */
    fun movable(from: Int, index: Int): Boolean {
        val p = piles.getOrNull(from) ?: return false
        if (index !in p.indices || !p[index].up) return false
        return when {
            from == WASTE || from in FOUNDATION until FOUNDATION + 4 -> index == p.size - 1
            from >= TABLEAU -> (index until p.size - 1).all { fits(p[it + 1], p[it]) }
            else -> false
        }
    }

    /** Whether [card] can go on a column card [on] (one lower, the other colour). */
    private fun fits(card: Card, on: Card) = on.up && card.rank == on.rank - 1 && card.red != on.red

    fun canPlace(from: Int, index: Int, to: Int): Boolean {
        if (to == from || !movable(from, index)) return false
        val moving = piles[from].subList(index, piles[from].size)
        val target = piles.getOrNull(to) ?: return false
        val first = moving.first()
        return when (to) {
            in FOUNDATION until FOUNDATION + 4 -> moving.size == 1 &&
                (if (target.isEmpty()) first.rank == 1 else target.last().suit == first.suit && first.rank == target.last().rank + 1)
            in TABLEAU until TABLEAU + 7 -> if (target.isEmpty()) first.rank == 13 else fits(first, target.last())
            else -> false
        }
    }

    /** Moves the cards from [index] down in [from] onto [to]; false when that is not allowed. */
    fun move(from: Int, index: Int, to: Int): Boolean {
        if (!canPlace(from, index, to)) return false
        remember()
        val src = piles[from]
        val moving = src.subList(index, src.size).toList()
        repeat(moving.size) { src.removeAt(src.size - 1) }
        piles[to] += moving
        if (from >= TABLEAU && src.isNotEmpty()) src.last().up = true
        moves++
        return true
    }

    /**
     * Where a tap on that card sends it: home to its foundation when it is a single card that
     * fits there, else onto a column where it fits (a king only moves to an empty column
     * when that frees something). Null when it fits nowhere.
     */
    fun target(from: Int, index: Int): Int? {
        if (!movable(from, index)) return null
        val single = index == piles[from].size - 1
        if (single && from !in FOUNDATION until FOUNDATION + 4)
            (FOUNDATION until FOUNDATION + 4).firstOrNull { canPlace(from, index, it) }?.let { return it }
        val columns = (TABLEAU until TABLEAU + 7).filter { canPlace(from, index, it) }
        columns.firstOrNull { piles[it].isNotEmpty() }?.let { return it }
        // To an empty column: pointless for a king already at the top of its column.
        if (from >= TABLEAU && index == 0) return null
        return columns.firstOrNull()
    }

    fun tap(from: Int, index: Int): Boolean = target(from, index)?.let { move(from, index, it) } ?: false

    /** One card home, for the end of the game; false when none fits. */
    fun finishStep(): Boolean {
        for (from in listOf(WASTE) + (TABLEAU until TABLEAU + 7)) {
            val p = piles[from]
            if (p.isEmpty()) continue
            val to = (FOUNDATION until FOUNDATION + 4).firstOrNull { canPlace(from, p.size - 1, it) } ?: continue
            return move(from, p.size - 1, to)
        }
        return false
    }

    fun undo(): Boolean {
        val last = undo.removeLastOrNull() ?: return false
        val keep = millis
        restore(last)
        millis = keep
        return true
    }

    private fun remember() {
        undo += save()
        while (undo.size > 100) undo.removeFirst()
    }

    fun save(): String = "$moves,$millis;" + piles.joinToString("|") { p ->
        p.joinToString(",") { "${it.rank}.${it.suit}.${if (it.up) 1 else 0}" }
    }

    private fun restore(text: String) {
        val (head, body) = text.split(';', limit = 2)
        val h = head.split(',')
        val parts = body.split('|')
        require(parts.size == PILES)
        val read = parts.map { part ->
            if (part.isEmpty()) emptyList() else part.split(',').map {
                val (r, s, u) = it.split('.').map(String::toInt)
                require(r in 1..13 && s in 0..3)
                Card(r, s, u == 1)
            }
        }
        require(read.sumOf { it.size } == 52)
        for (i in 0 until PILES) { piles[i].clear(); piles[i] += read[i] }
        moves = h[0].toInt(); millis = h[1].toLong()
    }

    companion object {
        const val STOCK = 0
        const val WASTE = 1
        const val FOUNDATION = 2
        const val TABLEAU = 6
        const val PILES = 13
        val SUITS = listOf("♠", "♥", "♦", "♣")
        val RANKS = listOf("", "A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")

        fun load(text: String, random: Random = Random.Default): Klondike? = runCatching {
            Klondike(random).apply { restore(text); undo.clear() }
        }.getOrNull()
    }
}
