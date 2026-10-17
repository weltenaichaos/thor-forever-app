package thor.companion.strip

/**
 * What an item's tooltip lines (see [ItemTips]) say it is worn on and what it gives, to
 * compare it with what you wear. English tooltips (the game's language here).
 */
data class ItemStats(val slots: List<Int>, val stats: Map<String, Double>) {
    companion object {
        /** Tooltip slot words and the inventory slots (as [Gear]) they go in. */
        private val SLOTS = linkedMapOf(
            "Head" to listOf(1), "Neck" to listOf(2), "Shoulder" to listOf(3), "Back" to listOf(15),
            "Chest" to listOf(5), "Shirt" to listOf(4), "Tabard" to listOf(19), "Wrist" to listOf(9),
            "Hands" to listOf(10), "Waist" to listOf(6), "Legs" to listOf(7), "Feet" to listOf(8),
            "Finger" to listOf(11, 12), "Trinket" to listOf(13, 14),
            "Main Hand" to listOf(16), "One-Hand" to listOf(16), "Two-Hand" to listOf(16),
            "Ranged" to listOf(16), "Off Hand" to listOf(17), "Held In Off-hand" to listOf(17),
        )
        private val PLUS = Regex("""^([+-]\d+(?:\.\d+)?)\s+(.+)$""")
        private val ARMOR = Regex("""^(\d+)\s+Armor\b""")
        private val DPS = Regex("""^\((\d+(?:\.\d+)?) damage per second\)""")

        fun parse(lines: List<String>): ItemStats {
            var slots = emptyList<Int>()
            val stats = linkedMapOf<String, Double>()
            for (raw in lines) {
                val line = raw.trim()
                val left = line.split("  ").first().trim()
                val slot = if (slots.isEmpty()) SLOTS[left] else null
                val armor = ARMOR.find(line)
                val dps = DPS.find(line)
                val plus = PLUS.find(left)
                when {
                    slot != null -> slots = slot
                    armor != null -> stats["Armor"] = (stats["Armor"] ?: 0.0) + armor.groupValues[1].toDouble()
                    dps != null -> stats["Damage per second"] = dps.groupValues[1].toDouble()
                    plus != null -> {
                        val name = plus.groupValues[2].trim()
                        if (name.length <= 24 && name.none { c -> c == ':' || c == '.' })
                            stats[name] = (stats[name] ?: 0.0) + plus.groupValues[1].toDouble()
                    }
                }
            }
            return ItemStats(slots, stats)
        }
    }
}

/** One stat's difference: what the new item gives less what the worn one does. */
data class StatChange(val stat: String, val change: Double)

object ItemCompare {
    /**
     * What wearing [item] instead of [worn] changes, biggest gain first (stats that stay the
     * same are left out). [worn] null: the slot is empty.
     */
    fun changes(item: ItemStats, worn: ItemStats?): List<StatChange> {
        val names = (item.stats.keys + worn?.stats?.keys.orEmpty()).distinct()
        return names.map { StatChange(it, (item.stats[it] ?: 0.0) - (worn?.stats?.get(it) ?: 0.0)) }
            .filter { kotlin.math.abs(it.change) >= 0.05 }
            .sortedByDescending { it.change }
    }

    /**
     * Of what you wear, the item to compare with: for a ring, trinket or one-hand item the
     * lower item level of the two slots (or an empty one). Returns the slot and what is in it.
     */
    fun wornFor(item: ItemStats, gear: List<GearItem>): Pair<Int, GearItem?>? {
        if (item.slots.isEmpty()) return null
        val bySlot = gear.associateBy { it.slot }
        return item.slots.map { it to bySlot[it] }.minByOrNull { it.second?.itemLevel ?: -1 }
    }
}
