package thor.companion.strip

import kotlin.math.hypot

/**
 * The quest areas the addon draws for a few seconds (Areas.lua), from its
 * `TA1|mapID|left|top|width|height|stepX|stepY|cols|cell<newline>title<newline>...`
 * message: where the first copy of the zone map is in game pixels from the top-left
 * corner of the data square, each copy's size, the distance from one to the next
 * across and down, how many per row, the square's cell size in game pixels, and the
 * quest each copy shows, row by row (named like the q places of the zone map).
 */
data class AreaPicture(
    val mapId: Int, val left: Int, val top: Int, val width: Int, val height: Int,
    val stepX: Int, val stepY: Int, val cols: Int, val cell: Int, val titles: List<String>,
) {
    /** Left, top, right, bottom in screen pixels of copy [i], given the square as [frame] found it. */
    fun onScreen(frame: StripFrame, i: Int): IntArray {
        val scale = frame.cellPx / cell
        val x = frame.x + (left + (i % cols) * stepX) * scale
        val y = frame.row - frame.cellPx / 2 + (top + (i / cols) * stepY) * scale
        return intArrayOf(Math.round(x).toInt(), Math.round(y).toInt(), Math.round(x + width * scale).toInt(), Math.round(y + height * scale).toInt())
    }

    companion object {
        fun parse(payload: String): AreaPicture? {
            if (!payload.startsWith("TA1|")) return null
            val rows = payload.split('\n')
            val f = rows.first().split('|')
            if (f.size < 10) return null
            val n = f.subList(1, 10).map { it.toIntOrNull() ?: return null }
            if (n[3] <= 0 || n[4] <= 0 || n[5] < n[3] || n[6] < n[4] || n[7] <= 0 || n[8] <= 0) return null
            val titles = rows.drop(1)
            if (titles.isEmpty() || titles.any { it.isEmpty() }) return null
            return AreaPicture(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], n[8], titles)
        }
    }
}

/**
 * Where one quest's objectives are on a zone map: a grid of [w] by [h] cells over the
 * whole zone (x and y from 0 to 1 like the positions), each in the area or not.
 */
class AreaMask(val w: Int, val h: Int, private val cells: BooleanArray) {
    init { require(w > 0 && h > 0 && cells.size == w * h) }

    val empty: Boolean = cells.none { it }

    operator fun get(cx: Int, cy: Int) = cx in 0 until w && cy in 0 until h && cells[cy * w + cx]

    /** Whether the zone map point [x], [y] is in the area. */
    fun inside(x: Double, y: Double) = get((x * w).toInt(), (y * h).toInt())

    /**
     * The point of the area nearest to [x], [y] (the point itself when it is inside), or
     * null when there is no area. [aspect]: how much longer a unit across the map is than
     * one down (the zone maps are 3:2).
     */
    fun nearest(x: Double, y: Double, aspect: Double = 1.5): DoubleArray? {
        if (inside(x, y)) return doubleArrayOf(x, y)
        var best: DoubleArray? = null
        var bestD = Double.MAX_VALUE
        for (cy in 0 until h) for (cx in 0 until w) {
            if (!cells[cy * w + cx]) continue
            // The cell's nearest point to x, y: x, y kept inside the cell.
            val px = x.coerceIn(cx.toDouble() / w, (cx + 1.0) / w)
            val py = y.coerceIn(cy.toDouble() / h, (cy + 1.0) / h)
            val d = hypot((px - x) * aspect, py - y)
            if (d < bestD) { bestD = d; best = doubleArrayOf(px, py) }
        }
        return best
    }

    /**
     * The area's outline as line pieces (x1, y1, x2, y2, ... in cells, 0..[w] and 0..[h]),
     * corners cut off so it isn't a staircase (marching squares).
     */
    fun outline(): FloatArray {
        val out = ArrayList<Float>()
        fun seg(x1: Float, y1: Float, x2: Float, y2: Float) { out += x1; out += y1; out += x2; out += y2 }
        // Corners of the squares between cell centres: cell (cx, cy) has its centre at cx + 0.5.
        for (gy in -1 until h) for (gx in -1 until w) {
            val a = get(gx, gy); val b = get(gx + 1, gy); val c = get(gx + 1, gy + 1); val d = get(gx, gy + 1)
            val code = (if (a) 8 else 0) or (if (b) 4 else 0) or (if (c) 2 else 0) or (if (d) 1 else 0)
            if (code == 0 || code == 15) continue
            val x0 = gx + 0.5f; val y0 = gy + 0.5f
            val top = floatArrayOf(x0 + 0.5f, y0); val right = floatArrayOf(x0 + 1, y0 + 0.5f)
            val bottom = floatArrayOf(x0 + 0.5f, y0 + 1); val left = floatArrayOf(x0, y0 + 0.5f)
            fun line(p: FloatArray, q: FloatArray) = seg(p[0], p[1], q[0], q[1])
            when (code) {
                1, 14 -> line(left, bottom)
                2, 13 -> line(bottom, right)
                3, 12 -> line(left, right)
                4, 11 -> line(top, right)
                6, 9 -> line(top, bottom)
                7, 8 -> line(left, top)
                5 -> { line(left, top); line(bottom, right) }
                10 -> { line(top, right); line(left, bottom) }
            }
        }
        return out.toFloatArray()
    }

    /** One line of text: width, height and the lengths of the runs, out and in by turns. */
    fun save(): String {
        val runs = ArrayList<Int>()
        var inArea = false
        var n = 0
        for (c in cells) {
            if (c == inArea) n++ else { runs += n; inArea = c; n = 1 }
        }
        runs += n
        return "$w $h " + runs.joinToString(",")
    }

    companion object {
        /** Null when [line] is not what [save] wrote. */
        fun load(line: String): AreaMask? {
            val f = line.trim().split(' ')
            if (f.size != 3) return null
            val w = f[0].toIntOrNull() ?: return null
            val h = f[1].toIntOrNull() ?: return null
            if (w <= 0 || h <= 0 || w * h > 1_000_000) return null
            val cells = BooleanArray(w * h)
            var at = 0
            var inArea = false
            for (r in f[2].split(',')) {
                val n = r.toIntOrNull() ?: return null
                if (n < 0 || at + n > cells.size) return null
                if (inArea) cells.fill(true, at, at + n)
                at += n
                inArea = !inArea
            }
            return if (at == cells.size) AreaMask(w, h, cells) else null
        }

        /**
         * The area from a screenshot of the addon's copy: [rgb] pixels, [w] by [h], white
         * (and the game's yellow edge) on black. Holes inside are filled in, so only the
         * edge being drawn is enough.
         */
        fun fromPixels(rgb: IntArray, w: Int, h: Int, threshold: Int = 40): AreaMask {
            val lit = BooleanArray(w * h) { i ->
                val p = rgb[i]
                maxOf((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF) >= threshold
            }
            // What the black around the copy reaches without crossing the area is outside;
            // the rest is the area.
            val outside = BooleanArray(w * h)
            val queue = IntArray(w * h)
            var head = 0; var tail = 0
            fun visit(i: Int) { if (!lit[i] && !outside[i]) { outside[i] = true; queue[tail++] = i } }
            for (x in 0 until w) { visit(x); visit((h - 1) * w + x) }
            for (y in 0 until h) { visit(y * w); visit(y * w + w - 1) }
            while (head < tail) {
                val i = queue[head++]
                val x = i % w; val y = i / w
                if (x > 0) visit(i - 1)
                if (x < w - 1) visit(i + 1)
                if (y > 0) visit(i - w)
                if (y < h - 1) visit(i + w)
            }
            return AreaMask(w, h, BooleanArray(w * h) { !outside[it] })
        }
    }
}
