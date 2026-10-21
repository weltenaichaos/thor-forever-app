package thor.companion.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The mini games' rules (no screen needed): moves, scores, and saving a game as text. */
class GamesRulesTest {

    @Test fun gemMatchPlaysARoundAndKeepsItsBoard() {
        val g = GemBoard(Random(1))
        assertTrue(g.findRows().isEmpty())
        repeat(GemBoard.MOVES) {
            val (a, b) = g.hint()!!
            val steps = g.swap(a, b)
            assertNotNull(steps)
            assertTrue(steps!!.isNotEmpty())
            assertTrue(g.cells.all { it in 0 until GemBoard.KINDS })
            assertTrue(g.findRows().isEmpty())
        }
        assertTrue(g.roundOver)
        assertNull(g.swap(0, 1))
        assertEquals(g.save(), GemBoard.load(g.save())!!.save())
        g.nextRound()
        assertEquals(GemBoard.MOVES, g.movesLeft)
    }

    @Test fun gemMatchRefusesASwapWithoutARow() {
        val g = GemBoard(Random(5))
        assertNull(g.swap(0, 9))  // not neighbours
        for (i in 0 until 63) {
            val before = g.cells.copyOf()
            if (i % 8 == 7) continue
            if (g.swap(i, i + 1) == null) assertTrue(before.contentEquals(g.cells)) else break
        }
    }

    @Test fun tilesMergeOncePerSwipe() {
        val b = intArrayOf(2, 2, 4, 4, 0, 0, 0, 2, 2, 0, 2, 0, 4, 4, 4, 4)
        val (moved, points) = Tiles2048.slide(b, Tiles2048.Dir.LEFT)
        assertTrue(moved)
        assertEquals(32, points)
        assertEquals(listOf(4, 8, 0, 0, 2, 0, 0, 0, 4, 0, 0, 0, 8, 8, 0, 0), b.toList())
        val t = Tiles2048(Random(2))
        var n = 0
        while (!t.over && n < 100_000) { Tiles2048.Dir.entries.any { t.move(it) }; n++ }
        assertTrue(t.over)
        assertEquals(t.save(), Tiles2048.load(t.save())!!.save())
    }

    @Test fun minesTheFirstTapIsSafeAndOpensAPatch() {
        val m = MineField(16, 10, 28, Random(3))
        assertTrue(m.reveal(55))
        assertEquals(28, m.mine.count { it })
        assertFalse(m.lost)
        assertEquals(0, m.count(55))
        for (i in 0 until 160) if (m.mine[i]) m.toggleFlag(i)
        for (i in 0 until 160) if (!m.mine[i]) m.reveal(i)
        assertTrue(m.won)
        assertEquals(m.save(), MineField.load(m.save())!!.save())
        val boom = MineField(10, 7, 10, Random(4))
        boom.reveal(0)
        val mine = boom.mine.indexOfFirst { it }
        boom.reveal(mine)
        assertTrue(boom.lost)
        assertEquals(mine, boom.boom)
    }

    @Test fun solitaireDealsMovesAndUndoes() {
        val k = Klondike(Random(1))
        assertEquals(24, k.piles[Klondike.STOCK].size)
        assertEquals(52, k.piles.sumOf { it.size })
        (0 until 7).forEach { c -> assertEquals(c + 1, k.piles[Klondike.TABLEAU + c].size); assertTrue(k.piles[Klondike.TABLEAU + c].last().up) }
        val start = k.save()
        assertTrue(k.draw())
        assertTrue(k.undo())
        assertEquals(start, k.save())
        // Many games by a simple player: every move keeps all 52 cards, and some games are won.
        var wins = 0
        for (seed in 0 until 60) {
            val g = Klondike(Random(seed))
            var idle = 0
            var steps = 0
            while (!g.won && idle < 60 && steps < 3000) {
                steps++
                if (g.canFinish) { while (g.finishStep()) {}; continue }
                var did = false
                loop@ for (from in listOf(Klondike.WASTE) + (Klondike.TABLEAU until Klondike.TABLEAU + 7)) {
                    val p = g.piles[from]
                    for (i in p.indices) {
                        val to = g.target(from, i) ?: continue
                        val home = to in Klondike.FOUNDATION until Klondike.FOUNDATION + 4
                        val reveals = from >= Klondike.TABLEAU && i > 0 && !p[i - 1].up
                        if (home || reveals || from == Klondike.WASTE) { assertTrue(g.tap(from, i)); did = true; break@loop }
                    }
                }
                if (did) idle = 0 else { g.draw(); idle++ }
                assertEquals(52, g.piles.sumOf { it.size })
            }
            if (g.won) wins++
            assertEquals(g.save(), Klondike.load(g.save())!!.save())
        }
        assertTrue("won $wins", wins > 0)
    }

    @Test fun sudoku() {
        for (level in 0..2) {
            val g = Sudoku.new(level, Random(level))
            assertEquals(1, Sudoku.solutions(IntArray(81) { if (g.given[it]) g.values[it] else 0 }, 2))
            val i = g.given.indexOfFirst { !it }
            val wrong = (1..9).first { it != g.solution[i] }
            assertTrue(g.set(i, wrong)); assertTrue(g.wrong(i)); assertEquals(1, g.mistakes)
            assertFalse(g.set(g.given.indexOfFirst { it }, 1))
            for (k in 0 until 81) if (!g.given[k]) g.set(k, g.solution[k])
            assertTrue(g.won)
            assertEquals(g.save(), Sudoku.load(g.save())!!.save())
        }
    }

    @Test fun nonogram() {
        for (size in Nonogram.SIZES) {
            val g = Nonogram.new(size, Random(size))
            assertTrue(Nonogram.lineSolvable(g))
            assertFalse(g.won)
            for (i in g.marks.indices) g.marks[i] = if (g.picture[i]) 1 else 2
            assertTrue(g.won)
            assertEquals(g.save(), Nonogram.load(g.save())!!.save())
        }
        assertEquals(listOf(2, 1), Nonogram.runs(listOf(true, true, false, true)))
    }

    @Test fun mahjong() {
        for (seed in 0 until 30) {
            val m = Mahjong.new(Random(seed))
            for (k in 0 until Mahjong.KINDS) assertEquals(4, m.faces.count { it == k })
            // A simple player: any free pair, Shuffle when there is none. Every game gets cleared.
            var guard = 0
            while (!m.won && guard < 2000) {
                guard++
                val h = m.hint()
                if (h != null) assertTrue(m.match(h.first, h.second)) else m.shuffle(Random(seed + guard))
            }
            assertTrue("seed $seed", m.won)
            assertEquals(m.save(), Mahjong.load(m.save())!!.save())
        }
        val m = Mahjong.new(Random(1))
        val h = m.hint()!!
        assertTrue(m.match(h.first, h.second))
        assertEquals(70, m.left)
        assertTrue(m.undo())
        assertEquals(72, m.left)
    }

    @Test fun blockDrop() {
        val b = BlockDrop(Random(2))
        var turns = 0
        while (!b.over && turns < 10000) {
            val k = (0 until 3).first { b.offer[it] != null && b.fitsAnywhere(b.offer[it]!!) }
            val shape = b.offer[k]!!
            val spot = (0 until 64).first { b.fits(shape, it % 8, it / 8) }
            assertTrue(b.place(k, spot % 8, spot / 8))
            turns++
        }
        assertTrue(b.over)
        assertEquals(b.save(), BlockDrop.load(b.save())!!.save())
        // A full row clears.
        val c = BlockDrop(Random(3))
        for (x in 0 until 7) c.cells[x] = 1
        c.offer[0] = 0
        assertTrue(c.place(0, 7, 0))
        assertEquals(listOf(0), c.lastCleared)
        assertTrue(c.cells.take(8).all { it == 0 })
    }
}
