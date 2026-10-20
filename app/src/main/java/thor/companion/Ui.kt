package thor.companion

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import com.winlator.cmod.R

/**
 * The app's look: the Thor Forever start screen's (ThorStartActivity). Deep blue, lighter
 * at the top, thin gold frames, round buttons in a blue gradient (teal for the main one and
 * for what is chosen) with Cinzel capitals, and Tahoma for the rest.
 */
object Theme {
    val BG = Color.rgb(16, 38, 59)
    /** The top of the screen's gradient, as behind the start screen's logo. */
    val HEADER_TOP = Color.rgb(65, 110, 151)
    /** A panel: a little darker than the screen, like a frame set into it. */
    val SURFACE = Color.rgb(11, 29, 46)
    val RAISED = Color.rgb(29, 57, 85)
    /** A panel's thin edge. */
    val STROKE = Color.rgb(45, 80, 112)
    /** A button's edge, and the dark line just inside it. */
    val EDGE = Color.rgb(65, 110, 151)
    val INNER = Color.rgb(9, 24, 38)
    val TEXT = Color.rgb(211, 230, 243)
    val DIM = Color.rgb(134, 166, 193)
    val SKY = Color.rgb(169, 207, 231)
    val SKY_DARK = Color.rgb(131, 176, 206)
    /** Gold: the frame of what matters now, and small marks. */
    val ACCENT = Color.rgb(201, 168, 106)
    /** Pale gold: titles and the tabs' names. */
    val GOLD_TEXT = Color.rgb(241, 228, 194)
    val BRONZE = Color.rgb(74, 52, 24)
    val TEAL = Color.rgb(52, 186, 215)
    val TEAL_DARK = Color.rgb(32, 129, 165)
    val BUTTON_TOP = Color.rgb(37, 70, 102)
    val BUTTON_BOTTOM = Color.rgb(22, 48, 73)
    val PRESSED = Color.rgb(22, 48, 73)
    val OFF_INK = Color.rgb(128, 146, 162)
    val GOOD = Color.rgb(90, 210, 120)
    val WARN = Color.rgb(255, 200, 80)
    val BAD = Color.rgb(255, 90, 90)
    val GOLD = Color.rgb(255, 210, 90)
    val SILVER = Color.rgb(200, 205, 212)
    val COPPER = Color.rgb(222, 140, 90)
    val XP = Color.rgb(160, 80, 220)
    val RESTED = Color.rgb(70, 110, 200)
    val XP_TEXT = Color.rgb(200, 150, 255)
    val RESTED_TEXT = Color.rgb(150, 185, 255)

    private var heading: Typeface? = null
    private var body: Typeface? = null
    private var bodyBold: Typeface? = null

    /** Cinzel, the start screen's capitals: tabs, buttons and titles. */
    fun heading(context: Context): Typeface = heading ?: font(context, R.font.cinzel_bold, Typeface.SERIF).also { heading = it }

    /** Tahoma, as in the game's own frames and on the start screen. */
    fun body(context: Context, bold: Boolean = false): Typeface =
        if (bold) bodyBold ?: Typeface.create(body(context), Typeface.BOLD).also { bodyBold = it }
        else body ?: font(context, R.font.wine_tahoma, Typeface.SANS_SERIF).also { body = it }

    /** For the screenshot test, where the app's own fonts may not load: the font files instead. */
    internal fun useFonts(heading: Typeface, body: Typeface) {
        this.heading = heading
        this.body = body
        bodyBold = null
    }

    private fun font(context: Context, id: Int, fallback: Typeface) =
        runCatching { context.resources.getFont(id) }.getOrNull() ?: fallback

    /** One screen pixel, for the thin frames of the start screen. */
    fun px(context: Context) = maxOf(1, Math.round(context.resources.displayMetrics.density / 2.6f))

    /** A rounded box; [stroke] draws a thin edge in that colour (the small round things: dots, badges). */
    fun box(context: Context, fill: Int, radiusDp: Int = 12, stroke: Int? = null, strokeDp: Float = 1f) = GradientDrawable().apply {
        val d = context.resources.displayMetrics.density
        cornerRadius = radiusDp * d
        setColor(fill)
        if (stroke != null) setStroke((strokeDp * d).toInt().coerceAtLeast(1), stroke)
    }

    /** A button's round corners, as in the companion's first look (a pill for a one-line button). */
    const val BUTTON_RADIUS_DP = 18f
    /** The tabs' corners: a little rounder than a square, so the row of tabs still reads as one bar. */
    const val TAB_RADIUS_DP = 12f

    /**
     * A face from [top] to [bottom] in a one-pixel [edge], with an [inner] line just inside it,
     * as the start screen draws its frames; [radiusDp] rounds the corners.
     */
    fun framed(context: Context, top: Int, bottom: Int, edge: Int, inner: Int? = INNER, radiusDp: Float = 0f): Drawable {
        val px = px(context)
        val radius = radiusDp * context.resources.displayMetrics.density
        val face = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            setStroke(px, edge)
            cornerRadius = radius
        }
        if (inner == null) return face
        val line = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(px, inner)
            cornerRadius = maxOf(0f, radius - px)
        }
        return LayerDrawable(arrayOf(face, line)).apply { setLayerInset(1, px, px, px, px) }
    }

    /**
     * A round button: deep blue, or teal in a gold edge for the main one ([primary]) and for
     * what is chosen, grey when it can't be tapped; while pressed its gradient turns over.
     */
    fun button(context: Context, primary: Boolean = false, enabled: Boolean = true, radiusDp: Float = BUTTON_RADIUS_DP): Drawable {
        val top = if (!enabled) Color.rgb(44, 58, 72) else if (primary) TEAL else BUTTON_TOP
        val bottom = if (!enabled) Color.rgb(34, 46, 58) else if (primary) TEAL_DARK else BUTTON_BOTTOM
        val edge = if (!enabled) Color.rgb(70, 88, 106) else if (primary) ACCENT else EDGE
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), framed(context, bottom, top, edge, radiusDp = radiusDp))
            addState(intArrayOf(), framed(context, top, bottom, edge, radiusDp = radiusDp))
        }
    }

    /** A round deep blue button in a gold edge: one that stands out without being the main one. */
    fun goldButton(context: Context): Drawable = framed(context, BUTTON_TOP, BUTTON_BOTTOM, ACCENT, radiusDp = BUTTON_RADIUS_DP)

    /** A panel to group things in, in a thin [edge]; [gold] frames it as the start screen does (gold, then bronze). */
    fun panel(context: Context, fill: Int = SURFACE, edge: Int = STROKE, gold: Boolean = false): Drawable =
        if (gold) framed(context, fill, fill, ACCENT, BRONZE) else framed(context, fill, fill, edge, null)

    fun withAlpha(colour: Int, alpha: Int) = Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))
}

/**
 * The whole screen's background, as the start screen's: deep blue, lighter at the top
 * ([headerPx] high), in a thin gold and bronze frame with a sky blue line a little inside.
 */
class ScreenFrame(private val headerPx: Int, private val px: Int) : Drawable() {
    private val paint = Paint()
    private val line = Paint().apply { style = Paint.Style.STROKE; strokeWidth = px.toFloat() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        paint.shader = null
        paint.color = Theme.BG
        canvas.drawRect(b, paint)
        paint.shader = LinearGradient(0f, b.top.toFloat(), 0f, (b.top + headerPx).toFloat(), Theme.withAlpha(Theme.HEADER_TOP, 200), Theme.BG, Shader.TileMode.CLAMP)
        canvas.drawRect(b.left.toFloat(), b.top.toFloat(), b.right.toFloat(), (b.top + headerPx).toFloat(), paint)
        fun frame(inset: Int, colour: Int) {
            line.color = colour
            val h = px / 2f
            canvas.drawRect(b.left + inset + h, b.top + inset + h, b.right - inset - h, b.bottom - inset - h, line)
        }
        frame(0, Theme.ACCENT)
        frame(px, Theme.BRONZE)
        frame(px * 4, Theme.withAlpha(Theme.SKY_DARK, 140))
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE
}

/** A thin progress bar in a dark edge, like the game's experience bar, with an optional second value behind the first (rested experience). */
class Bar(context: Context) : View(context) {
    var value = 0f
    var second = 0f
    var colour = Theme.XP
    var secondColour = Theme.RESTED
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()

    override fun onDraw(canvas: Canvas) {
        fun part(to: Float, c: Int) {
            if (to <= 0f) return
            paint.color = c
            r.set(0f, 0f, width * to.coerceIn(0f, 1f), height.toFloat())
            canvas.drawRect(r, paint)
        }
        part(1f, Theme.INNER)
        part(second, secondColour)
        part(value, colour)
        paint.color = Theme.STROKE
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        canvas.drawRect(0.5f, 0.5f, width - 0.5f, height - 0.5f, paint)
        paint.style = Paint.Style.FILL
    }
}

/** Lays its children out left to right, starting a new line when one doesn't fit (the quick replies). */
class Flow(context: Context, private val gap: Int) : android.view.ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0; var y = 0; var line = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.AT_MOST), MeasureSpec.UNSPECIFIED)
            if (x > 0 && x + c.measuredWidth > width) { x = 0; y += line + gap; line = 0 }
            x += c.measuredWidth + gap
            line = maxOf(line, c.measuredHeight)
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), y + line + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val width = r - l - paddingLeft - paddingRight
        var x = 0; var y = 0; var line = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (x > 0 && x + c.measuredWidth > width) { x = 0; y += line + gap; line = 0 }
            c.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gap
            line = maxOf(line, c.measuredHeight)
        }
    }
}

/** The usual "off" sign (a ring open at the top with a stroke through the gap), for Exit. */
class PowerIcon(context: Context) : View(context) {
    var colour = Theme.DIM
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ring = RectF()

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        val cx = width / 2f; val cy = height / 2f + s * 0.05f
        val r = s * 0.34f
        paint.color = colour
        paint.strokeWidth = s * 0.1f
        ring.set(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(ring, -55f, 290f, false, paint)
        canvas.drawLine(cx, cy - r - s * 0.1f, cx, cy - s * 0.04f, paint)
    }
}
