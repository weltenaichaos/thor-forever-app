package thor.companion.strip

import java.util.Locale

/**
 * The short forms the addon sends in its corner squares (Data.lua, ns.Short): numbers as
 * fixed-width digits of 64 (0-9 A-Z a-z + /). Written out again as the long messages the
 * rest of the app reads, so nothing else needs to know.
 *   P<map 3><x 3><y 3><facing 1>  ->  TP2|map|x|y|facing
 *   S<name>|<level>|<copper>|<place as in P>|<session>|<bagHash 3>|<version>|<sums, 3 each>  ->  TS1|...
 *   B<free 2><total 2><item>...  ->  TB1|free/total|itemID:count[:key],...  (each item: a width digit, then id, count, key)
 *   Z<packed>  ->  any message, packed (Pack.lua): byte 1 + 3 digits repeats 5 to 68 earlier bytes,
 *                  byte 2 to 6 + that many digits is a decimal number, byte 7 + 3 digits is 0.dddd,
 *                  byte 8 + 2 digits is 0.ddd, any other byte is itself
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

    /** [packed] (without its Z) written out again, or null when it is damaged. */
    private fun unpack(packed: String): String? {
        val z = packed.toByteArray(Charsets.UTF_8)
        var out = ByteArray(z.size * 3)
        var n = 0
        fun put(b: Byte) { if (n == out.size) out = out.copyOf(out.size * 2); out[n++] = b }
        fun digits(from: Int, count: Int): Long? {
            if (from + count > z.size) return null
            var v = 0L
            for (k in from until from + count) { val d = DIGITS.indexOf(z[k].toInt().toChar()); if (d < 0) return null; v = v * 64 + d }
            return v
        }
        var i = 0
        while (i < z.size) {
            when (val b = z[i].toInt()) {
                1 -> {
                    val dist = digits(i + 1, 2)?.toInt() ?: return null
                    val len = (digits(i + 3, 1)?.toInt() ?: return null) + 5
                    if (dist < 1 || dist > n) return null
                    repeat(len) { put(out[n - dist]) }
                    i += 4
                }
                in 2..6 -> {
                    val v = digits(i + 1, b) ?: return null
                    for (c in v.toString()) put(c.code.toByte())
                    i += 1 + b
                }
                7, 8 -> {
                    val w = if (b == 7) 3 else 2
                    val v = digits(i + 1, w) ?: return null
                    for (c in "0." + v.toString().padStart(w + 1, '0')) put(c.code.toByte())
                    i += 1 + w
                }
                else -> { put(b.toByte()); i++ }
            }
        }
        return String(out, 0, n, Charsets.UTF_8)
    }

    private fun bags(s: String): String? {
        if (s.length < 4) return null
        val free = number(s.substring(0, 2)) ?: return null
        val total = number(s.substring(2, 4)) ?: return null
        val items = ArrayList<String>()
        var i = 4
        while (i < s.length) {
            val h = DIGITS.indexOf(s[i]).takeIf { it >= 0 } ?: return null
            val iw = 3 + h % 2
            val cw = h / 2 % 3
            val kw = h / 6
            if (kw > 2) return null
            val end = i + 1 + iw + cw + kw
            if (end > s.length) return null
            var at = i + 1
            val id = number(s.substring(at, at + iw)) ?: return null; at += iw
            val count = if (cw == 0) 1L else number(s.substring(at, at + cw)) ?: return null; at += cw
            val key = if (kw == 0) null else number(s.substring(at, at + kw)) ?: return null
            items += "$id:$count" + (key?.let { ":$it" } ?: "")
            i = end
        }
        return "TB1|$free/$total|" + items.joinToString(",")
    }

    /**
     * The long forms of the messages in [message]: several for Y<message><byte 30><message>...
     * (small messages that went out together), else the one.
     */
    fun expandAll(message: String): List<String> {
        val whole = if (message.startsWith("Z")) unpack(message.substring(1)) ?: return listOf(message) else message
        if (whole.startsWith("Y")) return whole.substring(1).split('\u001e').filter { it.isNotEmpty() }.map { expand(it) }
        return listOf(expand(whole))
    }

    /** The long form of [message], or [message] as it is when it is no short form (or a damaged one). */
    fun expand(message: String): String {
        if (message.startsWith("Z")) {
            val whole = unpack(message.substring(1)) ?: return message
            return if (whole.startsWith("Z")) whole else expand(whole)
        }
        if (message.startsWith("B")) return bags(message.substring(1)) ?: message
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
