package thor.companion.strip

/**
 * Where the addon's four corner squares are looked for (see Corners.lua): only these
 * four small parts of the picture are copied and read, never the whole of it.
 * In order: top left (position), top right (the data), bottom left (chat), bottom right (status).
 */
object CornerAreas {
    const val TOP_LEFT = 0
    const val TOP_RIGHT = 1
    const val BOTTOM_LEFT = 2
    const val BOTTOM_RIGHT = 3

    /**
     * The four areas (left, top, right, bottom) of a picture [width] x [height], for
     * squares with cells of [cell] pixels (0 before one was read: then a guess, three
     * pixels on a 1080-high picture). An eighth of the picture each way, more for big cells.
     */
    fun areas(width: Int, height: Int, cell: Double): List<IntArray> {
        val c = if (cell > 0) cell else maxOf(3.0, 3.0 * height / 1080)
        val tallest = StripDecoder.CORNER_ROWS.values.max()
        val w = maxOf(width / 8, (StripDecoder.COLS * c + 4 * c + 16).toInt()).coerceAtMost(width / 2)
        val h = maxOf(height / 8, (tallest * c + 4 * c + 16).toInt()).coerceAtMost(height / 2)
        return listOf(
            intArrayOf(0, 0, w, h),
            intArrayOf(width - w, 0, width, h),
            intArrayOf(0, height - h, w, height),
            intArrayOf(width - w, height - h, width, height),
        )
    }

    /** The data square (top right) in a whole picture [px], placed in the whole picture, or null. */
    fun dataFrame(px: Pixels, cell: Double): StripFrame? {
        val a = areas(px.width, px.height, cell)[TOP_RIGHT]
        val part = Crop(px, a[0], a[1], a[2] - a[0], a[3] - a[1])
        val frame = (StripDecoder.decode(part) as? StripDecoder.Result.Ok)?.frame?.takeIf { it.crcOk } ?: return null
        return moved(frame, a[0], a[1])
    }

    /** [frame], read in a part that starts at [left], [top], placed in the whole picture. */
    fun moved(frame: StripFrame, left: Int, top: Int) = StripFrame(
        frame.version, frame.seq, frame.part, frame.parts, frame.crcOk, frame.payload,
        frame.shadeMinDistance, frame.cellPx, frame.row + top, frame.x + left,
    )

    private class Crop(val src: Pixels, val left: Int, val top: Int, override val width: Int, override val height: Int) : Pixels {
        override fun rgb(x: Int, y: Int) = src.rgb(left + x, top + y)
    }
}
