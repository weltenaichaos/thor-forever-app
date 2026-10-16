package thor.companion.strip

import java.util.Locale

/**
 * The short forms the addon sends in its corner squares (Data.lua, ns.Short): numbers as
 * fixed-width digits of 64 (0-9 A-Z a-z + /). Written out again as the long messages the
 * rest of the app reads, so nothing else needs to know.
 *   P<map 3><x 3><y 3><facing 1>  ->  TP2|map|x|y|facing
 *   S<name>|<level>|<copper>|<place as in P>|<session>|<bagHash 3>|<version>|<sums, 3 each>  ->  TS1|...
 */
object Compact {
    private const val DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz+/"
    private const val FULL = 262143.0
    private val SUM_KINDS = listOf(5, 6, 7, 8, 10, 11)

    private fun number(s: String): Long? {
        var n = 0L
        for (c in s) { val d = DIGITS.indexOf(c); if (d < 0) return null; n = n * 64 + d }
        return n
    }

    /** map, x, y, facing (empty when not known) from the 9 or 10 digits of a place. */
    private fun place(s: String): List<String>? {
        if (s.length != 9 && s.length != 10) return null
        val map = number(s.substring(0, 3)) ?: return null
        val x = number(s.substring(3, 6)) ?: return null
        val y = number(s.substring(6, 9)) ?: return null
        val facing = if (s.length == 10) number(s.substring(9)) ?: return null else null
        return listOf(
            map.toString(),
            String.format(Locale.ROOT, "%.4f", x / FULL),
            String.format(Locale.ROOT, "%.4f", y / FULL),
            facing?.let { String.format(Locale.ROOT, "%.1f", it * 2 * Math.PI / 64) } ?: "",
        )
    }

    /** The long form of [message], or [message] as it is when it is no short form (or a damaged one). */
    fun expand(message: String): String {
        if (message.startsWith("P")) {
            val p = place(message.substring(1)) ?: return message
            return "TP2|" + p.joinToString("|")
        }
        if (message.startsWith("S")) {
            val f = message.substring(1).split('|')
            if (f.size != 8) return message
            val p = place(f[3]) ?: return message
            val sums = f[7].chunked(3)
            if (sums.size != SUM_KINDS.size || sums.any { it.length != 3 }) return message
            val hashes = sums.map { number(it) ?: return message }
            val list = SUM_KINDS.zip(hashes).joinToString(",") { (k, h) -> "$k:$h" }
            return listOf(
                "TS1", f[0], number(f[1]) ?: return message, number(f[2]) ?: return message, p[0], p[1], p[2], p[3],
                number(f[4]) ?: return message, number(f[5]) ?: return message, f[6], list,
            ).joinToString("|")
        }
        return message
    }
}
