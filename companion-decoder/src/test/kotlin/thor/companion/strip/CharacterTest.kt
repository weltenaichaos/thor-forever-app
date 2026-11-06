package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CharacterTest {
    @Test
    fun parsesTheSheet() {
        val c = CharacterInfo.parse("TP1|Warlock|WARLOCK|Undead|1234|4000|0|11.8||11,12,13,14|55")!!
        assertEquals("Warlock", c.className)
        assertEquals("WARLOCK", c.classFile)
        assertEquals(1234L, c.xp)
        assertEquals(4000L, c.xpMax)
        assertEquals(11.8, c.itemLevel)
        assertNull(c.guild)
        assertEquals(listOf(11, 12, 13, 14), c.stats)
        assertEquals(55, c.armor)
    }

    @Test
    fun theCharactersOwnIdFromAddon034() {
        assertEquals("Player-4904-0ABC1234", CharacterInfo.parse("TP1|Mage|MAGE|Human|1|2|0|5.0||1,2,3,4|5|Player-4904-0ABC1234")!!.guid)
        assertNull(CharacterInfo.parse("TP1|Mage|MAGE|Human|1|2|0|5.0||1,2,3,4|5")!!.guid)
        assertNull(CharacterInfo.parse("TP1|Mage|MAGE|Human|1|2|0|5.0||1,2,3,4|5|")!!.guid)
        assertNull(CharacterInfo.parse("TP1|Mage|MAGE|Human|1|2|0|5.0||1,2,3,4|5|?")!!.guid)
    }

    @Test
    fun secretStatsAreNull() {
        val c = CharacterInfo.parse("TP1|Mage|MAGE|Human|?|?|0|?|Guild|?,?,?,?|?")!!
        assertNull(c.xp)
        assertEquals(listOf(null, null, null, null), c.stats)
        assertEquals("Guild", c.guild)
        assertNull(CharacterInfo.parse("TS1|x"))
    }

    @Test
    fun parsesGear() {
        assertEquals(
            listOf(GearItem(1, 5001, 14, null), GearItem(16, 5016, 14, 75)),
            Gear.parse("TQ1|1:5001:14,16:5016:14:75"),
        )
        assertEquals(emptyList(), Gear.parse("TQ1|"))
        assertEquals("Main hand", Gear.slotName(16))
    }
}
