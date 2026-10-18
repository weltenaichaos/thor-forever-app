package thor.companion.strip

/**
 * One chat line as the addon sends it (see addon/ThorCompanion/Chat.lua). [sender] is
 * Name-Realm as the game gives it; [channel] the public channel ("Trade"), or empty;
 * [classFile] the sender's class as the game names it ("MAGE"), when the addon knew it.
 */
data class ChatLine(val kind: String, val sender: String, val text: String, val channel: String = "", val classFile: String? = null) {
    /** The sender without the realm, as the chat frame shows it. */
    val name: String get() = sender.substringBefore('-')
}

/**
 * The chat lines the app has received, oldest first, from the addon's
 * `TH1|<session>\t<names>\t<channels>\n<id>\t<kind>\t<sender>\t<channel>\t<text>[\t<class>]\n...`
 * messages. <class> is the game's class number (8 is a mage, see [CLASSES]); addons before 0.30.3 leave it out.
 * Lines sent again (after a refresh) are recognised by session and number and kept once.
 * The addon may send a group line before an older Trade line; lines are kept in the
 * order of their numbers, so the chat reads as it was written.
 */
class ChatLog(private val keep: Int = 200) {
    private val seen = HashSet<String>()
    private val order = ArrayDeque<String>()
    /** Per line, its place in writing order: the session's arrival rank, then its number. */
    private val rank = ArrayDeque<Long>()
    private val sessions = HashMap<String, Long>()
    private val _lines = ArrayDeque<ChatLine>()
    val lines: List<ChatLine> get() = _lines

    /** How many lines were ever added: line i of [lines] is number total - lines.size + i + 1. */
    var total = 0
        private set

    /** Who has which whisper key right now: slot n (1-based) is ALT-SHIFT-F<n>. */
    var whisperNames: List<String> = emptyList()
        private set

    /** Each sender's class as the last line with one said it, for lines that come without (the chat log file). */
    private val classes = HashMap<String, String>()

    /** [sender]'s class ("MAGE"), as the chat last said it; null when no line said it. */
    fun classOf(sender: String): String? = classes[sender]

    /** The public channels you are in, by number: 1 to "General", 2 to "Trade", ... */
    var channels: List<Pair<Int, String>> = emptyList()
        private set

    /** The whisper slot (1-based) for [sender], or null when the addon has no key for them now. */
    fun whisperSlot(sender: String): Int? =
        if (sender.isEmpty()) null else whisperNames.indexOf(sender).takeIf { it >= 0 }?.plus(1)

    /** Adds one line read from the game's chat log file ([ChatFile]), after all the others. */
    fun add(line: ChatLine) {
        val key = "file:" + total
        seen.add(key)
        order.add(key)
        // After what is here now: lines from the square that come later still go after it.
        rank.add(rank.lastOrNull() ?: 0L)
        _lines.add(line)
        total++
        if (_lines.size > keep) {
            _lines.removeFirst()
            rank.removeFirst()
            seen.remove(order.removeFirst())
        }
    }

    /** Adds the lines of [payload]; true when anything changed. Null when it isn't a TH1 message. */
    fun add(payload: String): Boolean? {
        if (!payload.startsWith("TH1|")) return null
        val rows = payload.substring(4).split('\n')
        val head = rows.first().split('\t', limit = 3)
        val session = head[0]
        val names = head.getOrNull(1)?.split(',') ?: emptyList()
        val chans = head.getOrNull(2)?.split(',')?.mapNotNull { c ->
            val (id, name) = c.split(' ', limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            id.toIntOrNull()?.let { it to name }
        } ?: channels
        var added = names != whisperNames || chans != channels
        whisperNames = names
        channels = chans
        for (row in rows.drop(1)) {
            // The text has no tabs (the addon takes them out), so a sixth field is the class.
            val f = row.split('\t', limit = 6)
            if (f.size < 5) continue
            val key = session + ":" + f[0]
            val id = f[0].toLongOrNull() ?: continue
            if (!seen.add(key)) continue
            val r = sessions.getOrPut(session) { sessions.size.toLong() } * 1_000_000_000L + id
            var at = rank.size
            while (at > 0 && rank[at - 1] > r) at--
            order.add(at, key)
            rank.add(at, r)
            val classFile = f.getOrNull(5)?.trim()?.toIntOrNull()?.let { CLASSES[it] }
            if (classFile != null && f[2].isNotEmpty()) classes[f[2]] = classFile
            _lines.add(at, ChatLine(f[1], f[2], f[4], f[3], classFile))
            total++
            added = true
            if (_lines.size > keep) {
                _lines.removeFirst()
                rank.removeFirst()
                seen.remove(order.removeFirst())
            }
        }
        return added
    }

    companion object {
        /** The game's class numbers, as the addon sends them, and the names the game gives the classes. */
        val CLASSES = mapOf(
            1 to "WARRIOR", 2 to "PALADIN", 3 to "HUNTER", 4 to "ROGUE", 5 to "PRIEST", 6 to "DEATHKNIGHT",
            7 to "SHAMAN", 8 to "MAGE", 9 to "WARLOCK", 10 to "MONK", 11 to "DRUID", 12 to "DEMONHUNTER", 13 to "EVOKER",
        )
    }
}

/**
 * A line of the game's chat log (Logs/WoWChatLog.txt, written while chat logging is on):
 *   10/7 19:51:34.843  [2. Trade] Azlyn Lee: Olympus?
 *   10/7 19:51:35.202  Zaria Stormstrike creates Light Armor Kit.
 * as a [ChatLine] of the kinds the addon uses. Names can have spaces; what isn't
 * someone talking (crafting, loot, emotes, the game's messages) is "system".
 */
object ChatFile {
    private val TIME = Regex("""^\d{1,2}/\d{1,2}(?:/\d{2,4})? \d{1,2}:\d{2}:\d{2}(?:\.\d+)?\s+""")
    private val CHANNEL = Regex("""^\[(\d+)\. ([^\]]+)] (.+?): (.*)$""")
    private val BRACKET = Regex("""^\[([^\]]+)] (.+?): (.*)$""")
    private val LINK = Regex("""\|H[^|]*\|h(.*?)\|h""")
    private val COLOUR = Regex("""\|c[0-9a-fA-F]{8}""")
    private val LOG_SWITCH = Regex("""^(?:Left|Joined|Changed) Channel: \[\(null\)]$""")
    private val SPOKEN = Regex("""^(.+?) (says|yells|whispers): (.*)$""")
    private val TO = Regex("""^To (.+?): (.*)$""")
    private val GROUPS = mapOf(
        "Party" to "party", "Party Leader" to "party",
        "Raid" to "raid", "Raid Leader" to "raid", "Raid Warning" to "raid",
        "Instance" to "instance", "Instance Leader" to "instance",
        "Guild" to "guild", "Officer" to "guild",
    )

    /** When a log line was said (this device's clock, today or yesterday), from its time; null without one. */
    fun saidAt(raw: String, now: java.util.Calendar = java.util.Calendar.getInstance()): Long? {
        val m = SAID.find(raw) ?: return null
        val (h, min, sec, frac) = m.destructured
        val c = now.clone() as java.util.Calendar
        c.set(java.util.Calendar.HOUR_OF_DAY, h.toInt()); c.set(java.util.Calendar.MINUTE, min.toInt())
        c.set(java.util.Calendar.SECOND, sec.toInt())
        c.set(java.util.Calendar.MILLISECOND, (("0." + frac.ifEmpty { "0" }).toDouble() * 1000).toInt())
        if (c.timeInMillis > now.timeInMillis + 60_000) c.add(java.util.Calendar.DAY_OF_MONTH, -1)
        return c.timeInMillis
    }
    private val SAID = Regex("""^\d{1,2}/\d{1,2}(?:/\d{2,4})? (\d{1,2}):(\d{2}):(\d{2})(?:\.(\d+))?""")

    fun parse(raw: String): ChatLine? {
        // Links and colours as the chat frame would show them: "|Hitem:...|h[Linen Cloth]|h" is "[Linen Cloth]".
        val line = raw.replace(TIME, "").replace(LINK, "$1").replace(COLOUR, "").replace("|r", "").trim()
        if (line.isEmpty()) return null
        CHANNEL.find(line)?.let { m ->
            val (_, channel, sender, text) = m.destructured
            return ChatLine("channel", sender, text, channel.substringBefore(" - "))
        }
        BRACKET.find(line)?.let { m ->
            val (group, sender, text) = m.destructured
            GROUPS[group]?.let { return ChatLine(it, sender, text) }
        }
        SPOKEN.find(line)?.let { m ->
            val (sender, verb, text) = m.destructured
            val kind = when (verb) { "says" -> "say"; "yells" -> "yell"; else -> "whisper" }
            // Battle.net friends come with a protected name /w can't use.
            if (kind == "whisper" && sender.contains("|K")) return ChatLine("bnwhisper", "", text)
            return ChatLine(kind, sender, text)
        }
        TO.find(line)?.let { m ->
            val (sender, text) = m.destructured
            if (sender.contains("|K")) return ChatLine("bnwhisper_to", "", text)
            return ChatLine("whisper_to", sender, text)
        }
        if (NOISE.any { it.containsMatchIn(line) }) return null
        // The addon switching the log off and on (to have it written out) leaves these.
        if (LOG_SWITCH.matches(line)) return null
        return ChatLine("system", "", line)
    }

    /** Other players' crafting and loot: in a city it buries the chat. Your own stays ("You create ..."). */
    private val NOISE = listOf(
        Regex("""^(?!You )\S.*? creates .+\.$"""),
        Regex("""^(?!You )\S.*? receives? (?:loot|item|bonus loot): """),
    )
}
