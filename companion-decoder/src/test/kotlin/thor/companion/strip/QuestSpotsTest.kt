package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class QuestSpotsTest {
    private val spots = QuestSpots(
        """
        # comment
        7|o 12 492 362,12 600 100,40 100 100|t 12 489 416
        9|o |t 12 300 300
        11|o 12 200 200|t 40 500 500
        """.trimIndent(),
    )

    private fun quest(id: Int, title: String, ready: Boolean = false) = Quest(id, ready, true, 0, title, emptyList())

    @Test
    fun readsTheList() {
        assertEquals(3, spots.size)
        assertEquals(QuestSpots.Spot(12, 0.492, 0.362), spots[7]!!.objectives[0])
        assertEquals(emptyList(), spots[9]!!.objectives)
    }

    @Test
    fun placesTheQuestsInThisZone() {
        val log = listOf(quest(7, "Kobold Camp Cleanup"), quest(9, "Talk to someone"), quest(11, "Ready one", ready = true), quest(99, "Unknown"))
        assertEquals(
            listOf(
                MapPlace('q', 0.492, 0.362, "Kobold Camp Cleanup"),
                MapPlace('q', 0.6, 0.1, "Kobold Camp Cleanup"),
                MapPlace('q', 0.3, 0.3, "Talk to someone"),
            ),
            spots.places(setOf(12), log),
        )
        assertEquals(listOf(MapPlace('Q', 0.5, 0.5, "Ready one"), MapPlace('q', 0.1, 0.1, "Kobold Camp Cleanup")).toSet(),
            spots.places(setOf(40), log).toSet())
    }

    @Test
    fun cutsLongTitlesLikeTheAddon() {
        val long = "A very long quest title that goes on and on"
        assertEquals("A very long quest title that go…", spots.places(setOf(12), listOf(quest(11, long))).first().label)
    }

    @Test
    fun addsCirclesOnlyWithTheSpotsField() {
        val log = QuestLog(0, 0, 0, listOf(quest(7, "Kobold Camp Cleanup")))
        val forever = ZoneMap.parse("TM1|1429|Elwynn Forest|Eastern Kingdoms|0|0|0\n")!!
        assertSame(forever, forever.withQuestSpots(log) { spots })
        val anniversary = ZoneMap.parse("TM1|1429|Elwynn Forest|Eastern Kingdoms|0|0|0|2:12\nf,0.400,0.500,Goldshire")!!
        assertEquals("2:12", anniversary.spots)
        val shown = anniversary.withQuestSpots(log) { game -> if (game == 2) spots else null }
        assertEquals(listOf('f', 'q', 'q'), shown.places.map { it.kind })
        // The game's own quest places win.
        val own = ZoneMap.parse("TM1|1429|Elwynn Forest|Eastern Kingdoms|0|0|0|2:12\nq,0.400,0.500,Kobold Camp Cleanup")!!
        assertSame(own, own.withQuestSpots(log) { spots })
    }
}
