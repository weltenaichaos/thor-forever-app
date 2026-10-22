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

    @Test fun pipes() {
        for ((k, size) in Pipes.SIZES.withIndex()) {
            val g = Pipes.new(size, Random(k))
            assertFalse(g.won)
            // Every tile has a turn that solves it: try them all, tile by tile, from the well out (BFS over turns).
            val start = g.save()
            assertEquals(start, Pipes.load(start)!!.save())
            // Four quarter turns bring a tile back.
            val before = g.pipes[0]
            repeat(4) { g.turn(0) }
            assertEquals(before, g.pipes[0])
            assertEquals(4, g.moves)
        }
        assertEquals(2, Pipes.rotate(1))
        assertEquals(1, Pipes.rotate(8))
    }

    @Test fun slidePuzzle() {
        for (size in SlidePuzzle.SIZES) {
            val p = SlidePuzzle.new(size, Random(size))
            assertFalse(p.won)
            assertEquals(p.save(), SlidePuzzle.load(p.save())!!.save())
        }
        val p = SlidePuzzle.new(3, Random(9))
        val g = p.gap
        val far = if (g % 3 == 0) g + 2 else if (g % 3 == 2) g - 2 else g - 1
        assertTrue(p.tap(far))
        assertEquals(far, p.gap)
        val diagonal = (0 until 9).first { it / 3 != p.gap / 3 && it % 3 != p.gap % 3 }
        assertFalse(p.tap(diagonal))
    }

    @Test fun wordSearch() {
        for (level in 0..2) {
            val w = WordSearch.new(level, Random(level))
            for (p in w.words) {
                val spelled = String(CharArray(p.word.length) { w.letters[p.cell(it, w.size)] })
                assertEquals(p.word, spelled)
                if (level < 2) assertTrue(p.dx >= 0)
            }
            // Backwards works too.
            val p = w.words.first()
            assertEquals(p, w.select(p.cell(p.word.length - 1, w.size), p.cell(0, w.size)))
            assertNull(w.select(p.cell(0, w.size), p.cell(p.word.length - 1, w.size)))
            for (q in w.words) w.select(q.cell(0, w.size), q.cell(q.word.length - 1, w.size))
            assertTrue(w.won)
            assertEquals(w.save(), WordSearch.load(w.save())!!.save())
        }
    }

    @Test fun memory() {
        val m = Memory.new(6, 4, Random(4))
        assertEquals(12, m.pairs)
        val a = 0
        val twin = (1 until 24).first { m.faces[it] == m.faces[a] }
        val other = (1 until 24).first { m.faces[it] != m.faces[a] }
        assertTrue(m.flip(a)); assertTrue(m.flip(other))
        assertTrue(m.missed)
        assertTrue(m.flip(a))
        assertTrue(m.flip(twin))
        assertEquals(1, m.found)
        assertFalse(m.flip(a))
        for (i in 0 until 24) for (j in i + 1 until 24) if (!m.matched[i] && !m.matched[j] && m.faces[i] == m.faces[j]) { m.flip(i); m.flip(j) }
        assertTrue(m.won)
        assertEquals(m.save(), Memory.load(m.save())!!.save())
    }

    @Test fun snake() {
        val s = Snake(Random(1))
        s.step(5f)
        assertEquals(4, s.length)  // waits for the first swipe
        s.turn(3)                  // straight back: ignored
        s.turn(0)
        s.move()
        assertEquals(0, s.dir)
        // Running up into the edge ends it.
        repeat(Snake.H) { if (s.alive) s.move() }
        assertFalse(s.alive)
        // Eating: put the gem right ahead.
        val t = Snake(Random(2))
        t.turn(2); t.turn(1)
        val head = t.body.first()
        val field = Snake::class.java.getDeclaredField("gem").apply { isAccessible = true }
        field.setInt(t, head + Snake.W)
        t.move()
        assertEquals(5, t.length)
    }

    @Test fun bricks() {
        val b = Bricks()
        b.launch()
        // A paddle that always follows the ball never drops it.
        var t = 0f
        while (t < 120f && b.level == 1) { b.movePaddle(b.ballX); b.step(1 / 240f); t += 1 / 240f }
        assertEquals(3, b.lives)
        assertTrue(b.score > 0)
        // A paddle that stays away loses all three.
        val c = Bricks()
        repeat(3) {
            c.launch()
            var k = 0
            while (!c.stuck && k < 240 * 60) { c.movePaddle(if (c.ballX < 500) 925f else 75f); c.step(1 / 240f); k++ }
        }
        assertTrue(c.over)
    }

    @Test fun glide() {
        val g = Glide(Random(3))
        g.step(1f)
        assertTrue(g.alive)          // waits for the first flap
        g.flap()
        while (g.alive) g.step(1 / 240f)
        assertFalse(g.alive)         // no more flaps: it falls
        // A simple pilot that flaps when below the next gap's middle gets well past the start.
        val h = Glide(Random(4))
        h.flap()
        var t = 0f
        while (h.alive && t < 60f) {
            val next = h.peaks.firstOrNull { it.x + Glide.PEAK_W / 2 > Glide.X - Glide.R }
            val target = next?.let { (it.top + it.bottom) / 2 + 25 } ?: (Glide.H / 2)
            if (h.y > target && h.vy > -60f) h.flap()
            h.step(1 / 240f); t += 1 / 240f
        }
        assertTrue("flew ${h.metres}", h.metres > 300f)
    }

    @Test fun fishing() {
        val f = Fishing(Random(5))
        f.step(10f)
        assertEquals(Fishing.State.READY, f.state)   // the round starts with the first cast
        f.tap()
        assertEquals(Fishing.State.WAITING, f.state)
        f.tap()
        assertEquals(Fishing.State.EARLY, f.state)
        var t = 0
        while (f.state != Fishing.State.READY && t < 1000) { f.step(0.01f); t++ }
        f.tap()
        while (f.state != Fishing.State.BITE && t < 2000) { f.step(0.01f); t++ }
        f.tap()
        assertEquals(Fishing.State.CAUGHT, f.state)
        assertEquals(1, f.catch.size)
        f.step(200f)
        assertEquals(Fishing.State.OVER, f.state)
    }

    @Test fun pegs() {
        val p = Pegs(Random(6))
        val all = p.pegs.size
        assertEquals(16, p.orangeLeft)
        var shots = 0
        while (!p.over && shots < 40) {
            p.aimAt(100f + (shots * 137) % 800, 400f)
            p.shoot(); shots++
            var k = 0
            while (p.flying && k < 240 * 60) { p.step(1 / 240f); k++ }
            assertFalse("ball stuck", p.flying)
        }
        assertTrue(p.over)
        assertTrue(p.pegs.size < all)
        assertTrue(p.score > 0)
    }
}
