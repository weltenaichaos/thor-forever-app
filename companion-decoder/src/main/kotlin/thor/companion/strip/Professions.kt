package thor.companion.strip

/**
 * What the game is making after a Make (`c\t<seq>\t<made>\t<wanted>\t<status>\t<reason>\t<name>`):
 * [seq] counts the Makes, [status] is making, done or stopped ([reason]: what the game said, as "Inventory is full.").
 */
data class CraftProgress(val seq: Int, val made: Int, val wanted: Int, val status: String, val reason: String, val name: String) {
    val running: Boolean get() = status == "making"
}

/** A profession and its level, as the game's skill list says. */
data class Profession(val name: String, val level: Int, val max: Int)

/** Something the game said about crafting ("You create: ...", a skill point), with when (time(), in seconds). */
data class CraftResult(val at: Long, val text: String)

/**
 * Your professions and what you made lately, from the addon's
 * `TF1|<now>\np\t<profession>\t<level>\t<max>\n...e\t<time>\t<text>\n...` (see addon/ThorCompanion/Professions.lua).
 * [now] is the game's clock when it was sent, the same clock as the results' and the cooldowns' times.
 * [open] is the profession window open in the game (`w\t<profession>`), null when none is.
 * [makeKeys]: the addon has its Make keys bound (`f\t1`), so Make needs no typing.
 * [openKeys]: the key (an index into [ActionKeys]) that opens each profession's window (`k\t<profession>\t<key>`).
 */
data class Professions(val now: Long, val list: List<Profession>, val results: List<CraftResult>, val open: String? = null,
    val openKeys: Map<String, Int> = emptyMap(), val makeKeys: Boolean = false, val progress: CraftProgress? = null) {
    companion object {
        fun parse(payload: String): Professions? {
            if (!payload.startsWith("TF1|")) return null
            val rows = payload.substring(4).split('\n')
            val now = rows[0].trim().toLongOrNull() ?: return null
            val list = mutableListOf<Profession>()
            val results = mutableListOf<CraftResult>()
            var open: String? = null
            val keys = mutableMapOf<String, Int>()
            var makeKeys = false
            var progress: CraftProgress? = null
            for (row in rows.drop(1)) {
                val f = row.split('\t')
                when {
                    f[0] == "p" && f.size >= 4 -> list += Profession(f[1], f[2].toIntOrNull() ?: 0, f[3].toIntOrNull() ?: 0)
                    f[0] == "e" && f.size >= 3 -> results += CraftResult(f[1].toLongOrNull() ?: 0, f.drop(2).joinToString("\t"))
                    f[0] == "w" && f.size >= 2 -> open = f[1]
                    f[0] == "k" && f.size >= 3 -> f[2].toIntOrNull()?.let { keys[f[1]] = it }
                    f[0] == "f" -> makeKeys = true
                    f[0] == "c" && f.size >= 7 -> progress = CraftProgress(f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: 0,
                        f[3].toIntOrNull() ?: 0, f[4], f[5], f.drop(6).joinToString("\t"))
                }
            }
            return Professions(now, list, results, open, keys, makeKeys, progress)
        }

        /**
         * The keys the app presses for Make without typing (see Professions.lua): the
         * profession's number [n] (its open key ALT-SHIFT-F<n>), the recipe's [row] in its
         * TR1 rows (1 first), how many, each number as ALT-CTRL-NUMPAD digits, a separator
         * ALT-CTRL-F1 between them and ALT-CTRL-F2, which crafts, at the end.
         */
        fun makeKeys(n: Int, row: Int, count: Int): List<String> {
            fun digits(v: Int) = v.toString().map { "ALT-CTRL-NUMPAD$it" }
            return digits(n) + "ALT-CTRL-F1" + digits(row) + "ALT-CTRL-F1" + digits(count) + "ALT-CTRL-F2"
        }

        /** The spell that opens a profession's window (for /cast): Mining's is Smelting. */
        fun windowSpell(profession: String): String = when (profession) {
            "Mining" -> "Smelting"
            else -> profession
        }
    }
}

/** One material of a recipe: the item ([itemId] 0 when the game hadn't loaded it, then [name] says what) and how many. */
data class Material(val itemId: Int, val count: Int, val name: String = "")

/**
 * One line of a profession window: a heading, or a recipe that makes [itemId] (0 for an
 * enchantment), [made]..[madeMax] at a time. [colour] is the game's o, m, e or t (orange,
 * yellow, green, grey: how sure a skill point is). [readyAt] is when a recipe with a cooldown
 * can be made again (game time, 0: now).
 */
data class Recipe(
    val colour: Char, val itemId: Int, val made: Int, val madeMax: Int, val readyAt: Long,
    val materials: List<Material>, val name: String,
) {
    val heading: Boolean get() = colour == 'h'

    /** How many times you can make it with what [have] says you have of each item (null when a material isn't known yet). */
    fun canMake(have: (Int) -> Int): Int? {
        if (heading || materials.isEmpty()) return null
        var n = Int.MAX_VALUE
        for (m in materials) {
            if (m.itemId == 0 || m.count <= 0) return null
            n = minOf(n, have(m.itemId) / m.count)
        }
        return n
    }
}

/** A page of a profession's recipes, from `TR1|<profession>|<level>|<max>|<page>|<pages>|<read at>\n<rows>`. */
data class RecipePage(
    val profession: String, val level: Int, val max: Int, val page: Int, val pages: Int, val readAt: Long,
    val recipes: List<Recipe>,
) {
    companion object {
        fun parse(payload: String): RecipePage? {
            if (!payload.startsWith("TR1|")) return null
            val rows = payload.substring(4).split('\n')
            val h = rows[0].split('|')
            if (h.size < 6) return null
            val recipes = rows.drop(1).mapNotNull { row ->
                val f = row.split('\t')
                if (f.size < 7 || f[0].isEmpty()) return@mapNotNull null
                val materials = f[5].split(';').filter { it.isNotEmpty() }.mapNotNull { m ->
                    val p = m.split(':', limit = 3)
                    val id = p[0].toIntOrNull() ?: return@mapNotNull null
                    Material(id, p.getOrNull(1)?.toIntOrNull() ?: 1, p.getOrNull(2).orEmpty())
                }
                Recipe(f[0][0], f[1].toIntOrNull() ?: 0, f[2].toIntOrNull() ?: 1, f[3].toIntOrNull() ?: 1,
                    f[4].toLongOrNull() ?: 0, materials, f[6])
            }
            return RecipePage(h[0], h[1].toIntOrNull() ?: 0, h[2].toIntOrNull() ?: 0, h[3].toIntOrNull() ?: 1,
                h[4].toIntOrNull() ?: 1, h[5].toLongOrNull() ?: 0, recipes)
        }

        /** A profession's recipes from its pages, in order; null until every page is in. */
        fun join(pages: Collection<RecipePage>): List<Recipe>? {
            val first = pages.maxByOrNull { it.readAt } ?: return null
            val byPage = pages.filter { it.readAt == first.readAt }.associateBy { it.page }
            if ((1..first.pages).any { it !in byPage }) return null
            return (1..first.pages).flatMap { byPage.getValue(it).recipes }
        }
    }
}

/** What of the recipes' materials is in your bank, from `TK1|<read at>|itemID:count,...` (read while the bank was open). */
data class BankCounts(val readAt: Long, val counts: Map<Int, Int>) {
    companion object {
        fun parse(payload: String): BankCounts? {
            if (!payload.startsWith("TK1|")) return null
            val f = payload.substring(4).split('|')
            val counts = f.getOrNull(1).orEmpty().split(',').mapNotNull { e ->
                val p = e.split(':')
                if (p.size < 2) return@mapNotNull null
                (p[0].toIntOrNull() ?: return@mapNotNull null) to (p[1].toIntOrNull() ?: return@mapNotNull null)
            }.toMap()
            return BankCounts(f[0].toLongOrNull() ?: 0, counts)
        }
    }
}
