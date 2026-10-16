package thor.companion.strip

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProfessionsTest {
    @Test
    fun readsLevelsAndResults() {
        val p = Professions.parse("TF1|1000\np\tMining\t75\t150\np\tBlacksmithing\t60\t150\ne\t990\tYou create: [Copper Chain Belt].")!!
        assertEquals(1000, p.now)
        assertEquals(listOf("Mining", "Blacksmithing"), p.list.map { it.name })
        assertEquals(75, p.list[0].level)
        assertEquals("You create: [Copper Chain Belt].", p.results.single().text)
        assertEquals(0, Professions.parse("TF1|5")!!.list.size)
        assertNull(Professions.parse("TV1|5"))
    }

    @Test
    fun readsRecipesAndWhatYouCanMake() {
        val page = RecipePage.parse("TR1|Blacksmithing|60|150|1|1|1000\n" +
            "h\t0\t0\t0\t0\t\tMaterials\n" +
            "t\t2862\t1\t1\t0\t2835:1\tRough Sharpening Stone\n" +
            "e\t2851\t1\t1\t0\t2840:6;2880:1\tCopper Chain Belt\n" +
            "o\t0\t1\t1\t1300\t0:2:Strange Dust\tEnchant Something")!!
        assertEquals("Blacksmithing", page.profession)
        assertEquals(4, page.recipes.size)
        assertEquals(true, page.recipes[0].heading)
        val belt = page.recipes[2]
        assertEquals(listOf(Material(2840, 6), Material(2880, 1)), belt.materials)
        val have = mapOf(2840 to 13, 2880 to 5)
        assertEquals(2, belt.canMake { have[it] ?: 0 })
        assertEquals(0, page.recipes[1].canMake { have[it] ?: 0 })
        assertEquals(Material(0, 2, "Strange Dust"), page.recipes[3].materials.single())
        assertNull(page.recipes[3].canMake { 99 })
        assertEquals(1300, page.recipes[3].readyAt)
    }

    @Test
    fun joinsPages() {
        val a = RecipePage.parse("TR1|Mining|75|150|1|2|7\no\t2840\t1\t1\t0\t2770:1\tSmelt Copper")!!
        val b = RecipePage.parse("TR1|Mining|75|150|2|2|7\ne\t3576\t1\t1\t0\t2771:1\tSmelt Tin")!!
        assertNull(RecipePage.join(listOf(a)))
        assertEquals(listOf("Smelt Copper", "Smelt Tin"), RecipePage.join(listOf(b, a))!!.map { it.name })
    }

    @Test
    fun readsBank() {
        val b = BankCounts.parse("TK1|1000|2835:6,2840:12")!!
        assertEquals(mapOf(2835 to 6, 2840 to 12), b.counts)
        assertEquals(0, BankCounts.parse("TK1|0|")!!.counts.size)
    }

    @Test
    fun comparesWithWhatYouWear() {
        val belt = ItemStats.parse(listOf("Binds when equipped", "Waist  Mail", "52 Armor", "+3 Stamina", "+2 Strength",
            "Durability 30 / 30", "Requires Level 12", "Equip: Increases your chance to hit by 1%."))
        assertEquals(listOf(6), belt.slots)
        assertEquals(mapOf("Armor" to 52.0, "Stamina" to 3.0, "Strength" to 2.0), belt.stats)
        val old = ItemStats.parse(listOf("Waist  Leather", "20 Armor", "+4 Stamina"))
        val c = ItemCompare.changes(belt, old)
        assertEquals(listOf(StatChange("Armor", 32.0), StatChange("Strength", 2.0), StatChange("Stamina", -1.0)), c)
        val sword = ItemStats.parse(listOf("One-Hand  Sword", "7 - 15 Damage  Speed 2.60", "(4.2 damage per second)"))
        assertEquals(4.2, sword.stats["Damage per second"])
        val ring = ItemStats.parse(listOf("Finger", "+2 Agility"))
        val gear = listOf(GearItem(11, 100, 20, null), GearItem(12, 101, 12, null))
        assertEquals(12, ItemCompare.wornFor(ring, gear)!!.first)
        assertEquals(11 to null, ItemCompare.wornFor(ring, emptyList()))
        assertNull(ItemCompare.wornFor(ItemStats.parse(listOf("Trade Goods")), gear))
    }
}
