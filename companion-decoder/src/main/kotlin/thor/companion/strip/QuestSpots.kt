package thor.companion.strip

/**
 * Where quests are done and handed in, for Classic Era and Anniversary: those games have
 * no quest areas on their own map, so the addon sends no q places there. The app ships
 * this list instead (assets thor/companion/quest-spots-<game>.txt, built by
 * tools/quest-spots/build.py from pfQuest), one quest per line:
 * `questID|o area x y,area x y,...|t area x y,...` with o the middles of the objectives'
 * groups, t where the quest is handed in, area the zone's AreaTable ID and x, y 0 to 1000.
 */
class QuestSpots(text: String) {
    data class Spot(val area: Int, val x: Double, val y: Double)
    class Entry(val objectives: List<Spot>, val turnIn: List<Spot>)

    private val quests: Map<Int, Entry> = HashMap<Int, Entry>().also { map ->
        for (line in text.lineSequence()) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val f = line.split('|')
            val id = f[0].toIntOrNull() ?: continue
            map[id] = Entry(spots(f.getOrNull(1), "o "), spots(f.getOrNull(2), "t "))
        }
    }

    val size get() = quests.size

    operator fun get(questId: Int): Entry? = quests[questId]

    /**
     * The q (to do) and Q (to hand in) places of the quests in [log] in the zone [areas],
     * at most [max]; labels like the addon's (the quest's title, cut at 32 characters).
     */
    fun places(areas: Set<Int>, log: List<Quest>, max: Int = 20): List<MapPlace> {
        val out = ArrayList<MapPlace>()
        for (q in log) {
            val e = quests[q.id] ?: continue
            val (kind, at) = when {
                q.ready -> 'Q' to e.turnIn
                // A quest with nothing to kill or collect (talk to someone): where it is handed in.
                e.objectives.isEmpty() -> 'q' to e.turnIn
                else -> 'q' to e.objectives
            }
            for (s in at) {
                if (s.area !in areas) continue
                if (out.size >= max) return out
                out += MapPlace(kind, s.x, s.y, label(q.title))
            }
        }
        return out
    }

    companion object {
        private const val MAX_LABEL = 32

        private fun spots(field: String?, prefix: String): List<Spot> =
            field?.removePrefix(prefix)?.split(',')?.mapNotNull { s ->
                val p = s.trim().split(' ')
                if (p.size < 3) return@mapNotNull null
                val area = p[0].toIntOrNull() ?: return@mapNotNull null
                val x = p[1].toDoubleOrNull() ?: return@mapNotNull null
                val y = p[2].toDoubleOrNull() ?: return@mapNotNull null
                if (x <= 0 || y <= 0 || x >= 1000 || y >= 1000) null else Spot(area, x / 1000, y / 1000)
            }.orEmpty()

        private fun label(title: String) = if (title.length > MAX_LABEL) title.take(MAX_LABEL - 1) + "…" else title

        /** The game (1 Classic Era, 2 Anniversary) and the zone's area IDs from [ZoneMap.spots]; null without them. */
        fun field(spots: String): Pair<Int, Set<Int>>? {
            val game = spots.substringBefore(':').toIntOrNull() ?: return null
            val areas = spots.substringAfter(':', "").split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
            return if (areas.isEmpty()) null else game to areas
        }
    }
}

/**
 * This map with the quest circles from [spots] added, in Classic Era and Anniversary only
 * (when the addon sent the [ZoneMap.spots] field) and only while the game gave no quest
 * places itself. Every other map comes back as it is.
 */
fun ZoneMap.withQuestSpots(log: QuestLog?, spots: (game: Int) -> QuestSpots?): ZoneMap {
    if (log == null || places.any { it.kind == 'q' || it.kind == 'Q' }) return this
    val (game, areas) = QuestSpots.field(this.spots) ?: return this
    val added = spots(game)?.places(areas, log.quests).orEmpty()
    return if (added.isEmpty()) this else copy(places = places + added)
}
