package thor.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import thor.companion.strip.AreaMask
import thor.companion.strip.MapPlace
import thor.companion.strip.ZoneMap
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The zone drawn from what the addon sends: the places on it, the path you walked
 * and an arrow for you, over the zone's picture once the app has taken one (a grid
 * until then). The map fills the view at every zoom, with no empty bars: the whole
 * zone as wide as the view (or as tall, in a tall view; the bit cut off at the edges
 * is a drag away), or with [close] the area around you, [span] of the zone wide
 * across the view; pinch to change it, like the minimap's zoom, and drag to look
 * elsewhere ([panned]). With
 * [turns], the area around you turns with you like the minimap (where you face is up).
 * Names make room for each other: one that would cover another name or a pin is left
 * out, and a tap there says what it is. Quest areas have their real shape once the app
 * took it from the game ([areas]); until then they are circles round the game's one point.
 */
class MapView(context: Context) : View(context) {
    var zone: ZoneMap? = null
    /** The zone's picture, taken from the game's map art; null draws a grid. */
    var picture: android.graphics.Bitmap? = null
    var trail: List<List<DoubleArray>> = emptyList()
    var x: Double? = null
    var y: Double? = null
    var facing: Double? = null
    var close = false
    /** How much of the zone's width the close view shows (0..1). */
    var span = 0.4
    /** Whether quest objective areas are drawn. */
    var questAreas = true
    /** The real shapes of quest areas the app took (by the name in their q places); the others are circles. */
    var areas: Map<String, AreaMask> = emptyMap()
    /** A quest title whose places are marked more strongly (tapped in the Quests tab). */
    var highlight: String? = null
    /** The selected quest's nearest place, ringed, and an optional note written under the ring. */
    var targetNote: Pair<MapPlace, String>? = null
    /** Called with what a tap hit: the pin there, or every quest area under the finger (nearest first). */
    var onPlaces: (List<MapPlace>) -> Unit = {}
    /** The map turns with you, like the minimap (around you only, and not after a drag). */
    var turns = false
    /** Called after a pinch changed [close] or [span]. */
    var onZoom: () -> Unit = {}
    /** Where the view is centred after you dragged it (map units); null: on you, or the zone's middle. */
    var panned: DoubleArray? = null
    /** Called when a drag starts and when it ends (with [panned] where it left the view). */
    var onPan: () -> Unit = {}

    private val d = resources.displayMetrics.density
    private val grid = Paint().apply { color = Color.rgb(27, 53, 79); strokeWidth = d }
    private val edge = Paint().apply { color = Theme.EDGE; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val path = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 196, 64); style = Paint.Style.STROKE; strokeWidth = 2.5f * d
        strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = 10 * d; textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(226, 229, 234); textSize = 11 * d; setShadowLayer(2.5f * d, 0f, 0f, Color.BLACK) }
    private val photo = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = Color.argb(35, 0, 0, 0) }
    // Quest areas stay faint, so the zone picture under them can still be read.
    private val area = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(26, 255, 210, 0) }
    private val areaEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 210, 0); style = Paint.Style.STROKE; strokeWidth = 1.2f * d
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(5 * d, 4 * d), 0f)
    }
    private val areaText = Paint(text).apply { color = Color.rgb(255, 226, 130); textSize = 10 * d }
    // A real shape: filled faintly, with a thin line round it (white for the followed quest).
    private val shapeFill = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.PorterDuffColorFilter(Color.argb(46, 255, 210, 0), android.graphics.PorterDuff.Mode.SRC_IN)
    }
    private val shapeFillMarked = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        colorFilter = android.graphics.PorterDuffColorFilter(Color.argb(70, 255, 255, 255), android.graphics.PorterDuff.Mode.SRC_IN)
    }
    private val shapeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 210, 0); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val shapeEdgeMarked = Paint(shapeEdge).apply { color = Color.WHITE }
    /** Each shape as a picture to stretch over the zone, and its outline (made once). */
    private val shapes = java.util.WeakHashMap<AreaMask, Pair<android.graphics.Bitmap, FloatArray>>()
    private val marked = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 3 * d }
    private val distanceText = Paint(text).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE; textSize = 13 * d; isFakeBoldText = true }
    private val bounds = android.graphics.RectF()
    private val arrow = Path()
    private val me = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    // The part of the map shown: its top-left corner (map units) and the screen pixels per
    // map unit across and down (WoW's zone maps are 3:2, so a unit down is 2/3 as tall).
    private var left = 0.0
    private var top = 0.0
    private var ux = 1f
    private var uy = 1f
    /** How much of the zone's width the view shows now. */
    private var shown = 1.0
    /** Degrees the map is turned clockwise about [pivotX], [pivotY] (0 unless [turns] is at work). */
    private var turn = 0f
    private var pivotX = 0f
    private var pivotY = 0f

    private fun sx(mx: Double) = ((mx - left) * ux).toFloat()
    private fun sy(my: Double) = ((my - top) * uy).toFloat()

    /** Where a map point lands on screen, the turning included. */
    private fun screen(mx: Double, my: Double): android.graphics.PointF {
        val x0 = sx(mx); val y0 = sy(my)
        if (turn == 0f) return android.graphics.PointF(x0, y0)
        val a = Math.toRadians(turn.toDouble())
        val dx = x0 - pivotX; val dy = y0 - pivotY
        return android.graphics.PointF((pivotX + dx * cos(a) - dy * sin(a)).toFloat(), (pivotY + dx * sin(a) + dy * cos(a)).toFloat())
    }

    /** A point on screen back on the unturned map (for taps). */
    private fun unturn(x0: Float, y0: Float): android.graphics.PointF {
        if (turn == 0f) return android.graphics.PointF(x0, y0)
        val a = Math.toRadians(-turn.toDouble())
        val dx = x0 - pivotX; val dy = y0 - pivotY
        return android.graphics.PointF((pivotX + dx * cos(a) - dy * sin(a)).toFloat(), (pivotY + dx * sin(a) + dy * cos(a)).toFloat())
    }

    /** Where the view shows the zone, for the frame of [width] by [height] pixels. */
    private fun place() {
        val vw = width.toFloat(); val vh = height.toFloat()
        // The zone covers the whole view, whatever its shape (no empty bars at the sides):
        // the whole zone is as wide as the view, or as tall when the view is the taller,
        // and a drag shows the rest; closer, [span] of it across the view.
        val cover = maxOf(vw, vh * 1.5f)
        ux = if (close) maxOf(cover, (vw / span.coerceIn(MIN_SPAN, 1.0)).toFloat()) else cover
        uy = ux / 1.5f
        shown = (vw / ux).toDouble()
        val down = (vh / uy).toDouble()
        val px = x; val py = y; val f = facing
        if (turns && close && panned == null && px != null && py != null && f != null) {
            // Turning with you: exactly on you, edge or not, with where you face up.
            left = px - shown / 2
            top = py - down / 2
            pivotX = vw / 2; pivotY = vh / 2
            turn = Math.toDegrees(f).toFloat()
            return
        }
        turn = 0f
        // On you (the whole zone too, so the bit cut off is never where you are), else the
        // middle (0, 0: the game gave no position, in a dungeon say).
        val centre = panned ?: if (px != null && py != null && (px > 0 || py > 0)) doubleArrayOf(px, py) else doubleArrayOf(0.5, 0.5)
        // Centred there, but not past the zone's edge, where there is nothing to see.
        left = if (shown >= 1) 0.5 - shown / 2 else (centre[0] - shown / 2).coerceIn(0.0, 1 - shown)
        top = if (down >= 1) 0.5 - down / 2 else (centre[1] - down / 2).coerceIn(0.0, 1 - down)
    }

    /** Quest areas close together (many quests share one), drawn and named as one. */
    private class Cluster(val x: Double, val y: Double, val places: MutableList<MapPlace>)

    private fun clusters(): List<Cluster> {
        val out = ArrayList<Cluster>()
        for (p in zone?.places.orEmpty()) {
            if (p.kind != 'q') continue
            // A unit down the map is 2/3 of one across.
            val c = out.firstOrNull { hypot(it.x - p.x, (it.y - p.y) / 1.5) < 0.02 }
            if (c != null) c.places += p else out += Cluster(p.x, p.y, mutableListOf(p))
        }
        return out
    }

    /** The real shape of a quest area, when the app took one. */
    private fun shape(p: MapPlace): AreaMask? = if (p.kind == 'q') areas[p.label]?.takeIf { !it.empty } else null

    /** Draws [m] over the zone, which lies at [zl], [zt] to [zr], [zb] on screen (before turning). */
    private fun drawShape(canvas: Canvas, m: AreaMask, marked: Boolean, zl: Float, zt: Float, zr: Float, zb: Float) {
        val (img, outline) = shapes.getOrPut(m) {
            val px = IntArray(m.w * m.h) { i -> if (m[i % m.w, i / m.w]) Color.WHITE else Color.TRANSPARENT }
            android.graphics.Bitmap.createBitmap(px, m.w, m.h, android.graphics.Bitmap.Config.ARGB_8888) to m.outline()
        }
        bounds.set(zl, zt, zr, zb)
        canvas.drawBitmap(img, null, bounds, if (marked) shapeFillMarked else shapeFill)
        // The outline is in the shape's cells: scaled onto the zone, the line kept thin.
        val k = (zr - zl) / m.w
        val edgePaint = if (marked) shapeEdgeMarked else shapeEdge
        edgePaint.strokeWidth = (if (marked) 2.5f else 1.3f) * d / k
        canvas.save()
        canvas.translate(zl, zt)
        canvas.scale(k, (zb - zt) / m.h)
        canvas.drawLines(outline, edgePaint)
        canvas.restore()
    }

    private fun pinRadius(kind: Char) = if (kind == 'a' || kind == 'Q') 9 * d else 7 * d

    /**
     * Writes [s] by a pin (or, [centred], on an area) at [cx], [cy], in the first spot that
     * covers no other name or pin ([taken]); left out when there is none (a tap shows it).
     */
    private fun label(canvas: Canvas, s: String, cx: Float, cy: Float, r: Float, paint: Paint, taken: MutableList<RectF>, centred: Boolean) {
        if (s.isEmpty()) return
        val tw = paint.measureText(s); val ts = paint.textSize
        val below = cx - tw / 2 to cy + r + ts + d
        val above = cx - tw / 2 to cy - r - 4 * d
        val spots = if (centred) listOf(cx - tw / 2 to cy + ts * 0.35f, above, below)
            else listOf(cx + r + 3 * d to cy + ts * 0.35f, cx - r - 3 * d - tw to cy + ts * 0.35f, below, above)
        for ((bx, by) in spots) {
            val box = RectF(bx - 2 * d, by - ts, bx + tw + 2 * d, by + ts * 0.3f)
            if (box.left < 0 || box.right > width || box.top < 0 || box.bottom > height) continue
            if (taken.any { RectF.intersects(it, box) }) continue
            taken += box
            canvas.drawText(s, bx, by, paint)
            return
        }
    }

    override fun onDraw(canvas: Canvas) {
        place()
        val px = x; val py = y
        val zoomed = shown <= 0.45
        val places = zone?.places.orEmpty()
        val title = highlight
        canvas.drawColor(Color.rgb(8, 22, 35))

        // The map itself, turned with you when [turns] is on: picture, areas, path and rings.
        canvas.save()
        canvas.rotate(turn, pivotX, pivotY)
        val zl = sx(0.0); val zt = sy(0.0); val zr = sx(1.0); val zb = sy(1.0)
        canvas.save()
        canvas.clipRect(zl, zt, zr, zb)
        canvas.drawColor(Theme.SURFACE)
        val pic = picture
        if (pic != null) {
            bounds.set(zl, zt, zr, zb)
            canvas.drawBitmap(pic, null, bounds, photo)
            canvas.drawRect(zl, zt, zr, zb, shade)
        } else {
            val stepGrid = if (shown < 0.5) 0.02 else 0.1
            var g = 0.0
            while (g <= 1.0) { canvas.drawLine(sx(g), zt, sx(g), zb, grid); canvas.drawLine(zl, sy(g), zr, sy(g), grid); g += stepGrid }
        }
        // Quest objectives are areas, not spots: their real shape once the app took it from
        // the game, until then a soft circle where to do them.
        val radius = 0.03f * ux
        if (questAreas) for (place in places) {
            if (place.kind != 'q') continue
            val m = shape(place)
            if (m != null && title != null && matches(place.label, title)) continue  // drawn marked below
            if (m != null) { drawShape(canvas, m, false, zl, zt, zr, zb); continue }
            canvas.drawCircle(sx(place.x), sy(place.y), radius, area)
            canvas.drawCircle(sx(place.x), sy(place.y), radius, areaEdge)
        }
        for (piece in trail) {
            if (piece.size < 2) continue
            val p = Path()
            p.moveTo(sx(piece[0][0]), sy(piece[0][1]))
            for (i in 1 until piece.size) p.lineTo(sx(piece[i][0]), sy(piece[i][1]))
            canvas.drawPath(p, path)
        }
        // The quest tapped in the Quests tab: a white ring round each of its places, whatever the toggles.
        if (title != null) for (place in places) {
            if (place.kind !in "qQa" || !matches(place.label, title)) continue
            val m = shape(place)
            if (m != null) drawShape(canvas, m, true, zl, zt, zr, zb)
            else canvas.drawCircle(sx(place.x), sy(place.y), if (place.kind == 'q') radius else 13 * d, marked)
        }
        // Where you are led: a white ring (on a real shape, a small one at its nearest point to you).
        targetNote?.let { (place, _) ->
            val r = when { place.kind != 'q' -> 13 * d; shape(place) != null -> 9 * d; else -> radius }
            canvas.drawCircle(sx(place.x), sy(place.y), r, marked)
        }
        canvas.restore()
        canvas.drawRect(zl, zt, zr, zb, edge)
        canvas.restore()

        // Pins and names stay upright. Names keep off the pins, you and each other.
        val taken = ArrayList<RectF>()
        val pins = places.filter { it.kind != 'q' }
        for (place in pins) {
            val at = screen(place.x, place.y); val r = pinRadius(place.kind)
            taken += RectF(at.x - r, at.y - r, at.x + r, at.y + r)
        }
        if (px != null && py != null) {
            val at = screen(px, py)
            taken += RectF(at.x - 11 * d, at.y - 11 * d, at.x + 11 * d, at.y + 11 * d)
        }
        for (place in pins) {
            val at = screen(place.x, place.y); val r = pinRadius(place.kind)
            dot.color = colour(place.kind)
            canvas.drawCircle(at.x, at.y, r, dot)
            canvas.drawCircle(at.x, at.y, r, ring)
            canvas.drawText(symbol(place.kind), at.x, at.y + 3.5f * d, glyph)
        }
        val groups = clusters()
        fun name(c: Cluster): String {
            val first = c.places.firstOrNull { title != null && matches(it.label, title) } ?: c.places.first()
            return short(first.label) + if (c.places.size > 1) "  +${c.places.size - 1}" else ""
        }
        // The followed quest's name first, then the pins that matter most, then the rest.
        if (title != null) for (c in groups) {
            if (c.places.none { matches(it.label, title) }) continue
            val at = screen(c.x, c.y)
            label(canvas, name(c), at.x, at.y, 8 * d, areaText, taken, centred = true)
        }
        for (kinds in listOf("wcQafd", "gvp")) {
            if (kinds == "gvp" && !zoomed) continue
            for (place in pins.filter { it.kind in kinds }.sortedBy { kinds.indexOf(it.kind) }) {
                val at = screen(place.x, place.y)
                label(canvas, short(place.label), at.x, at.y, pinRadius(place.kind), text, taken, centred = false)
            }
        }
        // Many quests share an area; their names only fit when zoomed in (tap one otherwise).
        if (questAreas && zoomed) for (c in groups) {
            if (title != null && c.places.any { matches(it.label, title) }) continue
            val at = screen(c.x, c.y)
            label(canvas, name(c), at.x, at.y, 8 * d, areaText, taken, centred = true)
        }
        targetNote?.let { (place, note) ->
            if (note.isEmpty()) return@let
            val at = screen(place.x, place.y)
            canvas.drawText(note, at.x, at.y + 27 * d, distanceText)
        }

        if (px != null && py != null) {
            val at = screen(px, py)
            canvas.save()
            canvas.translate(at.x, at.y)
            // Facing: 0 is north, counter-clockwise; the canvas turns clockwise. Turning with
            // you, the map took the turn and you point up.
            canvas.rotate(-Math.toDegrees(facing ?: 0.0).toFloat() + turn)
            arrow.rewind()
            arrow.moveTo(0f, -11 * d); arrow.lineTo(7 * d, 8 * d); arrow.lineTo(0f, 4 * d); arrow.lineTo(-7 * d, 8 * d); arrow.close()
            if (facing == null) {
                canvas.drawCircle(0f, 0f, 6 * d, me)
                canvas.drawCircle(0f, 0f, 6 * d, ring)
            } else {
                canvas.drawPath(arrow, me)
                canvas.drawPath(arrow, ring)
            }
            canvas.restore()
        }
    }

    /**
     * Moves you to [nx], [ny] facing [nf]. The addon sends your position a few times
     * a second while you walk; in between the arrow glides there instead of jumping,
     * so the map moves smoothly without the game sending (or the app reading) more.
     * A jump (new zone, a portal) is shown at once.
     */
    fun glideTo(nx: Double?, ny: Double?, nf: Double?) {
        val fx = x; val fy = y; val ff = facing
        glide?.cancel()
        if (nx == null || ny == null || fx == null || fy == null || hypot(nx - fx, ny - fy) > 0.03) {
            x = nx; y = ny; facing = nf
            invalidate()
            return
        }
        // Turn the short way round.
        var turn = if (nf != null && ff != null) nf - ff else 0.0
        while (turn > Math.PI) turn -= 2 * Math.PI
        while (turn < -Math.PI) turn += 2 * Math.PI
        glide = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = GLIDE_MS
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener {
                val t = it.animatedFraction.toDouble()
                x = fx + (nx - fx) * t
                y = fy + (ny - fy) * t
                facing = if (nf == null || ff == null) nf else ff + turn * t
                invalidate()
            }
            start()
        }
    }

    private var glide: android.animation.ValueAnimator? = null

    override fun onDetachedFromWindow() {
        glide?.cancel()
        super.onDetachedFromWindow()
    }

    /** Pinch to zoom: closer than the whole zone switches to the area around you. */
    private var pinched = false
    /** A drag: where the finger went down, and the view's centre then (map units). */
    private var downX = 0f
    private var downY = 0f
    private var dragFrom: DoubleArray? = null
    private var dragging = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop
    private val pinch = android.view.ScaleGestureDetector(context, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(g: android.view.ScaleGestureDetector): Boolean {
            pinched = true
            val now = if (close) span else 1.0
            val next = (now / g.scaleFactor).coerceIn(MIN_SPAN, 1.0)
            close = next < 0.95
            if (close) span = next
            invalidate()
            return true
        }

        override fun onScaleEnd(g: android.view.ScaleGestureDetector) = onZoom()
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        pinch.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pinched = false
                dragging = false
                downX = e.x; downY = e.y
                place()
                dragFrom = doubleArrayOf(left + width / ux / 2.0, top + height / uy / 2.0)
                // The tab scrolls; a pinch or drag on the map must not turn into scrolling.
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val from = dragFrom
                if (pinched || e.pointerCount > 1 || from == null) return true
                if (!dragging && hypot(e.x - downX, e.y - downY) < slop) return true
                // Only when there is more to see than the view shows.
                if (shown >= 1 && height / uy >= 1) return true
                dragging = true
                val was = panned
                panned = doubleArrayOf(from[0] - (e.x - downX) / ux, from[1] - (e.y - downY) / uy)
                // Kept where the view can go, so dragging back past the edge moves it at once.
                place()
                panned = doubleArrayOf(left + shown / 2, top + height / uy / 2.0)
                if (was == null) onPan()
                invalidate()
                return true
            }
        }
        if (dragging && (e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL)) {
            dragging = false
            onPan()
            return true
        }
        if (e.actionMasked != MotionEvent.ACTION_UP || pinched || dragging) return true
        // A pin within a finger's reach, else the quest areas under the finger.
        val at = unturn(e.x, e.y)
        val places = zone?.places.orEmpty()
        fun far(p: MapPlace) = hypot(sx(p.x) - at.x, sy(p.y) - at.y)
        val pin = places.filter { it.kind != 'q' }.minByOrNull(::far)?.takeIf { far(it) < 28 * d }
        if (pin != null) { onPlaces(listOf(pin)); return true }
        val reach = maxOf(0.03f * ux, 22 * d)
        val title = highlight
        // The tap on the map, and whether it is on a real shape (or a finger's width from one).
        val mx = left + at.x / ux; val my = top + at.y / uy
        fun onShape(m: AreaMask): Boolean {
            if (m.inside(mx, my)) return true
            val p = m.nearest(mx, my) ?: return false
            return hypot((p[0] - mx) * ux, (p[1] - my) * uy) < 14 * d
        }
        val hit = places.filter {
            it.kind == 'q' && (questAreas || (title != null && matches(it.label, title))) &&
                (shape(it)?.let(::onShape) ?: (far(it) < reach))
        }
        if (hit.isNotEmpty()) onPlaces(hit.sortedBy(::far)) else performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    /** The addon cuts long labels with "…", so a label matches a title it starts. */
    private fun matches(label: String, title: String) = label.isNotEmpty() && title.startsWith(label.removeSuffix("…"))

    private fun short(s: String) = if (s.length > 18) s.take(17) + "…" else s

    companion object {
        /** About the time between two positions from the addon while walking. */
        const val GLIDE_MS = 500L

        const val MIN_SPAN = 0.08

        fun symbol(kind: Char) = when (kind) {
            'a' -> "!"; 'q' -> ""; 'Q' -> "?"; 'w' -> "•"; 'c' -> "✝"; 'f' -> "F"; 'd' -> "D"; 'p' -> "◆"; 'v' -> "★"; 'g' -> "●"
            else -> ""
        }

        fun colour(kind: Char) = when (kind) {
            'a' -> Color.rgb(255, 210, 0)
            'q' -> Color.rgb(255, 210, 0)
            'Q' -> Color.rgb(255, 240, 120)
            'w' -> Color.rgb(90, 200, 255)
            'c' -> Color.rgb(200, 200, 200)
            'f' -> Color.rgb(120, 220, 120)
            'd' -> Color.rgb(200, 140, 255)
            'p' -> Color.rgb(170, 180, 200)
            'v' -> Color.rgb(255, 140, 60)
            'g' -> Color.rgb(100, 160, 255)
            else -> Color.GRAY
        }

        fun kindName(kind: Char) = when (kind) {
            'a' -> "Pick up a quest"; 'q' -> "Do this quest here"; 'Q' -> "Turn in a quest"; 'w' -> "Map pin"; 'c' -> "Corpse"
            'f' -> "Flight master"; 'd' -> "Dungeon"; 'p' -> "Place"; 'v' -> "Rare or treasure"; 'g' -> "Group member"
            else -> "Place"
        }
    }
}
