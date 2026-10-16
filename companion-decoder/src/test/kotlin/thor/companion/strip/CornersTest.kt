package thor.companion.strip

import kotlin.math.roundToInt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The small position square (top left) next to the colour bar (top right), each read in its own part of the picture. */
class CornersTest {

    private class ArrayPixels(override val width: Int, override val height: Int, val data: IntArray) : Pixels {
        override fun rgb(x: Int, y: Int) = data[y * width + x]
    }

    private class Part(val src: Pixels, val left: Int, override val width: Int, override val height: Int) : Pixels {
        override fun rgb(x: Int, y: Int) = src.rgb(left + x, y)
    }

    @Test
    fun decodesTheSpotAlone() {
        val payload = "TP2|1420|0.5312|0.4430|6.2"
        val px = render(spot = payload.toByteArray(), bar = null, seq = 9)
        val frame = assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(px)).frame
        assertTrue(frame.crcOk)
        assertEquals(StripDecoder.POSITION_VERSION, frame.version)
        assertEquals(payload, String(frame.payload))
        assertTrue(frame.x < 10, "the spot sits in the top-left corner")
    }

    @Test
    fun decodesTheBiggestSpot() {
        val payload = "TP2|" + "9".repeat(StripDecoder.cornerPayload(5) - 4)
        val frame = assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(render(spot = payload.toByteArray(), bar = null, seq = 1))).frame
        assertTrue(frame.crcOk)
        assertEquals(payload, String(frame.payload))
    }

    @Test
    fun readsBothWhenEachIsLookedForInItsOwnPart() {
        val spot = "TP2|1429|0.1000|0.9000|3.1"
        val bar = "TB1|3/98|" + (1..8).joinToString(",") { "${it * 1000}:$it:$it" }
        val px = render(spot = spot.toByteArray(), bar = bar.toByteArray(), seq = 4)
        val third = px.width / 3
        val left = assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(Part(px, 0, third, px.height))).frame
        val right = assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(Part(px, third, px.width - third, px.height))).frame
        assertEquals(spot, String(left.payload)); assertEquals(StripDecoder.POSITION_VERSION, left.version)
        assertEquals(bar, String(right.payload)); assertEquals(StripDecoder.BAR_VERSION, right.version)
    }

    @Test
    fun readsAllFourCornersInTheirAreas() {
        val sent = mapOf(
            StripDecoder.POSITION_VERSION to "TP2|1429|0.1000|0.9000|3.1",
            StripDecoder.DATA_VERSION to "TB1|3/98|" + (1..18).joinToString(",") { "${it * 1000}:$it:$it" },
            StripDecoder.CHAT_VERSION to "TE1|4|open|hello|5",
            StripDecoder.STATUS_VERSION to "TS1|Sanbica|12|5000|1429|0.1000|0.9000|3.1|17000|123|0.24.6|5:1,6:2",
        )
        val px = render(spot = null, bar = null, seq = 7, corners = sent)
        val read = CornerAreas.areas(px.width, px.height, 0.0).map { a ->
            val part = object : Pixels {
                override val width = a[2] - a[0]
                override val height = a[3] - a[1]
                override fun rgb(x: Int, y: Int) = px.rgb(a[0] + x, a[1] + y)
            }
            assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(part)).frame.also { assertTrue(it.crcOk) }
        }
        assertEquals(listOf(StripDecoder.POSITION_VERSION, StripDecoder.DATA_VERSION, StripDecoder.CHAT_VERSION, StripDecoder.STATUS_VERSION), read.map { it.version })
        for (f in read) assertEquals(sent[f.version], String(f.payload))
        // The data square found in the whole picture, where it is in it.
        val data = CornerAreas.dataFrame(px, read[0].cellPx)!!
        assertEquals(StripDecoder.DATA_VERSION, data.version)
        assertTrue(data.x > px.width - 200, "the data square is in the top-right corner (x ${data.x})")
    }

    /** A square cut off by the right edge of the part looked at (seen on the Thor: a crash) is just not found. */
    @Test
    fun aSquareCutOffAtTheEdgeIsNoCrash() {
        val px = render(spot = null, bar = null, seq = 5, corners = mapOf(StripDecoder.DATA_VERSION to "TB1|1/2|3:4"))
        for (cut in 1..120) {
            val part = object : Pixels {
                override val width = px.width - cut
                override val height = 200
                override fun rgb(x: Int, y: Int) = px.rgb(x, y)
            }
            StripDecoder.decode(part)
        }
    }

    /**
     * The cells Strip.lua's ns.ColourCells drew for four messages, normal and dense (8 levels),
     * each only as many rows as it needs (corner-cells.txt, written by the addon's code):
     * all read back from their corners through the Thor-like display.
     */
    @Test
    fun readsTheAddonsOwnCellsNormalAndDense() {
        val lines = javaClass.getResource("/corner-cells.txt")!!.readText().lines()
        var i = 0
        val blocks = ArrayList<Triple<Int, Boolean, List<Int>>>()
        while (i < lines.size && lines[i].isNotBlank()) {
            val (v, rows, dense) = lines[i].split(' ')
            val n = rows.toInt() * StripDecoder.COLS
            val cells = lines.subList(i + 1, i + 1 + n).map { l ->
                l.split(',').map { (it.toDouble() * 255).roundToInt() }.let { (r, g, b) -> (r shl 16) or (g shl 8) or b }
            }
            blocks += Triple(v.toInt(), dense == "true", cells)
            i += 1 + n
        }
        val expected = mapOf(6 to "TP2|", 9 to "TB1|", 7 to "TE1|", 8 to "TS1|")
        for (dense in listOf(false, true)) {
            val px = render(spot = null, bar = null, seq = 3, raw = blocks.filter { it.second == dense }.map { it.first to it.third })
            val read = CornerAreas.areas(px.width, px.height, 0.0).map { a ->
                val part = object : Pixels {
                    override val width = a[2] - a[0]
                    override val height = a[3] - a[1]
                    override fun rgb(x: Int, y: Int) = px.rgb(a[0] + x, a[1] + y)
                }
                assertIs<StripDecoder.Result.Ok>(StripDecoder.decode(part), "dense $dense").frame.also { assertTrue(it.crcOk, "dense $dense") }
            }
            for (f in read) {
                val kind = StripDecoder.cornerKind(f.version)
                assertEquals(if (dense) kind + StripDecoder.DENSE else kind, f.version)
                assertTrue(String(f.payload).startsWith(expected.getValue(kind)))
            }
        }
    }

    /** Draws the spot and the bar the way Strip.lua does, then through the Thor-like display (1.5x, colour mix). */
    private fun render(spot: ByteArray?, bar: ByteArray?, seq: Int, cell: Int = 3, corners: Map<Int, String> = emptyMap(),
        raw: List<Pair<Int, List<Int>>> = emptyList()): Pixels {
        val gw = 1280; val gh = 720
        val rnd = Random(seq)
        val game = IntArray(gw * gh) {
            val v = rnd.nextInt(10, 120)
            (v shl 16) or ((v + rnd.nextInt(0, 40)) shl 8) or (v / 2)
        }
        fun draw(body: ByteArray, version: Int, cols: Int, rows: Int, left: Int, top: Int = 0) {
            val head = byteArrayOf(version.toByte(), seq.toByte(), 0, body.size.toByte())
            val crc = StripDecoder.crc16(head + body)
            val bytes = head + body + byteArrayOf((crc shr 8).toByte(), crc.toByte())
            val level = intArrayOf(0, 51, 102, 153)
            fun grey(l: Int) = (level[l] shl 16) or (level[l] shl 8) or level[l]
            fun colour(v: Int) = (level[v shr 4 and 3] shl 16) or (level[v shr 2 and 3] shl 8) or level[v and 3]
            val cells = IntArray(cols * rows) { colour(0) }
            for (c in 0 until StripDecoder.COLS) cells[c] = grey(if (c % 2 == 0) 3 else 0)
            var i = StripDecoder.COLS
            for (c in 0..3) cells[i++] = grey(c)
            cells[i++] = colour(48); cells[i++] = colour(12); cells[i++] = colour(3); cells[i++] = grey(0)
            for (p in bytes.indices step 3) {
                val v = ((bytes[p].toInt() and 0xFF) shl 16) or ((bytes.getOrElse(p + 1) { 0 }.toInt() and 0xFF) shl 8) or
                    (bytes.getOrElse(p + 2) { 0 }.toInt() and 0xFF)
                for (s in 3 downTo 0) cells[i++] = colour((v shr (s * 6)) and 63)
            }
            for (c in cells.indices) {
                val x0 = left + (c % cols) * cell
                val y0 = top + (c / cols) * cell
                for (dy in 0 until cell) for (dx in 0 until cell) game[(y0 + dy) * gw + x0 + dx] = cells[c]
            }
        }
        if (spot != null) draw(spot, StripDecoder.POSITION_VERSION, StripDecoder.COLS, StripDecoder.CORNER_ROWS.getValue(StripDecoder.POSITION_VERSION), 0)
        if (bar != null) draw(bar, StripDecoder.BAR_VERSION, StripDecoder.BAR_COLS, StripDecoder.BAR_ROWS, gw - 160 - StripDecoder.BAR_COLS * cell)
        // The corner squares two pixels in from their corners, as Corners.lua and Strip.lua place them.
        for ((version, text) in corners) {
            val rows = StripDecoder.CORNER_ROWS.getValue(version)
            val right = version == StripDecoder.DATA_VERSION || version == StripDecoder.STATUS_VERSION
            val bottom = version == StripDecoder.CHAT_VERSION || version == StripDecoder.STATUS_VERSION
            draw(text.toByteArray(), version, StripDecoder.COLS, rows,
                if (right) gw - 2 - StripDecoder.COLS * cell else 2, if (bottom) gh - 2 - rows * cell else 2)
        }
        // Cells as the addon drew them, in their corners.
        for ((version, cells) in raw) {
            val rows = cells.size / StripDecoder.COLS
            val right = version == StripDecoder.DATA_VERSION || version == StripDecoder.STATUS_VERSION
            val bottom = version == StripDecoder.CHAT_VERSION || version == StripDecoder.STATUS_VERSION
            val left = if (right) gw - 2 - StripDecoder.COLS * cell else 2
            val top = if (bottom) gh - 2 - rows * cell else 2
            for (c in cells.indices) {
                val x0 = left + (c % StripDecoder.COLS) * cell
                val y0 = top + (c / StripDecoder.COLS) * cell
                for (dy in 0 until cell) for (dx in 0 until cell) game[(y0 + dy) * gw + x0 + dx] = cells[c]
            }
        }
        val sw = 1920; val sh = if (corners.isEmpty() && raw.isEmpty()) 400 else 1080
        return ArrayPixels(sw, sh, IntArray(sw * sh) { idx -> thorColour(bilinear(game, gw, gh, (idx % sw) / 1.5, (idx / sw) / 1.5)) })
    }

    private fun bilinear(img: IntArray, w: Int, h: Int, fx: Double, fy: Double): Int {
        val x = (fx - 0.25).coerceIn(0.0, w - 1.0); val y = (fy - 0.25).coerceIn(0.0, h - 1.0)
        val x0 = x.toInt(); val y0 = y.toInt(); val x1 = minOf(x0 + 1, w - 1); val y1 = minOf(y0 + 1, h - 1)
        val ax = x - x0; val ay = y - y0
        var out = 0
        for (sh in intArrayOf(16, 8, 0)) {
            fun ch(px: Int, py: Int) = (img[py * w + px] shr sh) and 0xFF
            val upper = ch(x0, y0) * (1 - ax) + ch(x1, y0) * ax
            val lower = ch(x0, y1) * (1 - ax) + ch(x1, y1) * ax
            out = out or ((upper * (1 - ay) + lower * ay).toInt() shl sh)
        }
        return out
    }

    private fun thorColour(c: Int): Int {
        fun lin(v: Int): Double { val f = v / 255.0; return if (f <= 0.04045) f / 12.92 else Math.pow((f + 0.055) / 1.055, 2.4) }
        fun enc(l: Double): Int { val v = l.coerceIn(0.0, 1.0); return (255 * (if (v <= 0.0031308) v * 12.92 else 1.055 * Math.pow(v, 1 / 2.4) - 0.055) + 0.5).toInt() }
        val r = lin(c shr 16 and 0xFF); val g = lin(c shr 8 and 0xFF); val b = lin(c and 0xFF)
        val nr = enc(0.823 * r + 0.177 * g)
        val ng = enc(0.032 * r + 0.968 * g)
        val nb = enc(0.018 * r + 0.073 * g + 0.909 * b)
        return (nr shl 16) or (ng shl 8) or nb
    }
}
