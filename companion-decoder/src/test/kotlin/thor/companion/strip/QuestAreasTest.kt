package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuestAreasTest {
    @Test
    fun readsTheMessage() {
        val p = assertNotNull(AreaPicture.parse("TA1|1420|-398|109|192|128|200|136|5|3\nThe Chill of Death\nRattling the Rattlecages"))
        assertEquals(1420, p.mapId)
        assertEquals(listOf("The Chill of Death", "Rattling the Rattlecages"), p.titles)
        assertEquals(192, p.width)
        assertEquals(136, p.stepY)
        assertNull(AreaPicture.parse("TA1|1420|-398|109|192|128|200|136|5|3"))
        assertNull(AreaPicture.parse("TA1|1420|-398|109|192|128|100|136|5|3\nA"))
        assertNull(AreaPicture.parse("TW1|1|2|3|4|5|6"))
    }

    /** A copy w x h: black, with [lit] pixels white. */
    private fun shot(w: Int, h: Int, lit: (Int, Int) -> Boolean) =
        IntArray(w * h) { i -> if (lit(i % w, i / w)) 0xFFFFFF else 0x000000 }

    @Test
    fun aFilledShape() {
        val m = AreaMask.fromPixels(shot(30, 20) { x, y -> x in 5..14 && y in 4..9 }, 30, 20)
        assertFalse(m.empty)
        assertTrue(m.inside(10.0 / 30, 6.0 / 20))
        assertFalse(m.inside(20.0 / 30, 6.0 / 20))
    }

    @Test
    fun onlyTheEdgeIsEnough() {
        // A ring: its inside is filled in; outside it stays out.
        val ring = shot(30, 20) { x, y -> (x in 5..14 && (y == 4 || y == 12)) || (y in 4..12 && (x == 5 || x == 14)) }
        val m = AreaMask.fromPixels(ring, 30, 20)
        assertTrue(m.inside(10.0 / 30, 8.0 / 20))
        assertFalse(m.inside(20.0 / 30, 8.0 / 20))
        assertFalse(m.inside(1.0 / 30, 1.0 / 20))
    }

    @Test
    fun nothingDrawnIsEmpty() {
        val m = AreaMask.fromPixels(shot(30, 20) { _, _ -> false }, 30, 20)
        assertTrue(m.empty)
        assertNull(m.nearest(0.5, 0.5))
        assertEquals(0, m.outline().size)
    }

    @Test
    fun nearestPointOfTheArea() {
        val m = AreaMask.fromPixels(shot(100, 100) { x, y -> x in 60..79 && y in 40..59 }, 100, 100)
        // Inside: where you are.
        assertEquals(listOf(0.7, 0.5), m.nearest(0.7, 0.5)!!.toList())
        // To the left of it: its left edge, at the same height.
        val p = m.nearest(0.2, 0.5)!!
        assertEquals(0.6, p[0], 1e-9)
        assertEquals(0.5, p[1], 1e-9)
    }

    @Test
    fun keptAndLoadedAgain() {
        val m = AreaMask.fromPixels(shot(30, 20) { x, y -> (x - 15) * (x - 15) + (y - 10) * (y - 10) < 30 }, 30, 20)
        val again = assertNotNull(AreaMask.load(m.save()))
        assertEquals(m.save(), again.save())
        for (y in 0 until 20) for (x in 0 until 30) assertEquals(m[x, y], again[x, y])
        assertNull(AreaMask.load("30 20 5,5"))
        assertNull(AreaMask.load("nonsense"))
    }

    @Test
    fun outlineGoesRoundTheArea() {
        // One cell: four corner-cutting pieces round it, a little diamond.
        val m = AreaMask.fromPixels(shot(5, 5) { x, y -> x == 2 && y == 2 }, 5, 5)
        val o = m.outline()
        assertEquals(4 * 4, o.size)
        // All on the cell's sides' middles: x and y from 2 to 3.
        for (v in o.toList()) assertTrue(v in 2f..3f, "$v")
    }
}
