package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import thor.companion.Theme

/** A game's small picture on the games page, drawn here (no artwork from anywhere else). */
class GameIcon(context: Context, private val id: String) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Theme.heading(context); textAlign = Paint.Align.CENTER }
    private val r = RectF()
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val s = minOf(width, height).toFloat()
        canvas.save()
        canvas.translate((width - s) / 2f, (height - s) / 2f)
        val ink = Theme.GOLD_TEXT
        p.style = Paint.Style.FILL
        p.strokeCap = Paint.Cap.ROUND
        fun c(colour: Int) { p.color = colour; p.style = Paint.Style.FILL }
        fun line(colour: Int, w: Float) { p.color = colour; p.style = Paint.Style.STROKE; p.strokeWidth = s * w }
        fun word(text: String, size: Float, colour: Int = ink) { t.color = colour; t.textSize = s * size; canvas.drawText(text, s / 2, s / 2 - (t.descent() + t.ascent()) / 2, t) }
        when (id) {
            "gems" -> { c(Color.rgb(74, 163, 224)); path.reset(); path.moveTo(s * .5f, s * .1f); path.lineTo(s * .88f, s * .42f); path.lineTo(s * .5f, s * .92f); path.lineTo(s * .12f, s * .42f); path.close(); canvas.drawPath(path, p)
                c(Color.argb(110, 255, 255, 255)); path.reset(); path.moveTo(s * .5f, s * .1f); path.lineTo(s * .64f, s * .42f); path.lineTo(s * .36f, s * .42f); path.close(); canvas.drawPath(path, p) }
            "solitaire" -> { c(Color.rgb(246, 241, 228)); r.set(s * .22f, s * .08f, s * .78f, s * .92f); canvas.drawRoundRect(r, s * .07f, s * .07f, p)
                t.color = Color.rgb(27, 27, 27); t.textSize = s * .3f; canvas.drawText("A", s * .37f, s * .36f, t); t.textSize = s * .44f; canvas.drawText("♠", s * .52f, s * .76f, t) }
            "mines" -> { c(Color.rgb(200, 205, 212)); for (a in 0 until 4) { val ang = a * Math.PI / 4; val dx = (Math.cos(ang) * s * .38).toFloat(); val dy = (Math.sin(ang) * s * .38).toFloat(); line(Color.rgb(200, 205, 212), .07f); canvas.drawLine(s / 2 - dx, s / 2 - dy, s / 2 + dx, s / 2 + dy, p) }
                c(Color.rgb(200, 205, 212)); canvas.drawCircle(s / 2, s / 2, s * .24f, p); c(Color.WHITE); canvas.drawCircle(s * .43f, s * .43f, s * .06f, p) }
            "2048" -> { c(Color.rgb(255, 210, 90)); r.set(s * .08f, s * .2f, s * .92f, s * .8f); canvas.drawRoundRect(r, s * .1f, s * .1f, p); word("2048", .27f, Color.rgb(40, 30, 12)) }
            "sudoku" -> { line(ink, .03f); r.set(s * .12f, s * .12f, s * .88f, s * .88f); canvas.drawRect(r, p); for (i in 1..2) { val v = s * .12f + i * s * .76f / 3; canvas.drawLine(v, s * .12f, v, s * .88f, p); canvas.drawLine(s * .12f, v, s * .88f, v, p) }; word("9", .32f) }
            "nonogram" -> { c(ink); val cell = s * .76f / 5; for (i in 0 until 25) if ("1011001110111110111001010"[i] == '1') { r.set(s * .12f + (i % 5) * cell + 1, s * .12f + (i / 5) * cell + 1, s * .12f + (i % 5 + 1) * cell - 1, s * .12f + (i / 5 + 1) * cell - 1); canvas.drawRect(r, p) } }
            "mahjong" -> { c(Color.rgb(200, 190, 160)); r.set(s * .26f, s * .14f, s * .82f, s * .92f); canvas.drawRoundRect(r, s * .08f, s * .08f, p); c(Color.rgb(246, 241, 228)); r.offset(-s * .06f, -s * .06f); canvas.drawRoundRect(r, s * .08f, s * .08f, p)
                c(Color.rgb(192, 48, 48)); canvas.drawCircle(r.centerX(), r.centerY(), s * .14f, p); c(Color.rgb(246, 241, 228)); canvas.drawCircle(r.centerX(), r.centerY(), s * .07f, p) }
            "blocks" -> { val cell = s * .26f; for ((x, y, col) in listOf(Triple(0, 1, Theme.TEAL), Triple(1, 1, Theme.TEAL), Triple(2, 1, Theme.TEAL), Triple(1, 0, Theme.TEAL), Triple(0, 2, Theme.GOLD), Triple(1, 2, Theme.GOLD), Triple(2, 2, Theme.COPPER))) { c(col); r.set(s * .11f + x * cell + 2, s * .11f + y * cell + 2, s * .11f + (x + 1) * cell - 2, s * .11f + (y + 1) * cell - 2); canvas.drawRoundRect(r, 4f, 4f, p) } }
            "pipes" -> { line(Theme.TEAL, .16f); p.strokeCap = Paint.Cap.BUTT; canvas.drawLine(s * .1f, s * .5f, s * .9f, s * .5f, p); canvas.drawLine(s * .5f, s * .1f, s * .5f, s * .9f, p); line(Theme.SKY, .05f); canvas.drawLine(s * .1f, s * .5f, s * .9f, s * .5f, p) }
            "slide" -> { val cell = s * .26f; var n = 1; for (y in 0 until 3) for (x in 0 until 3) { if (x == 2 && y == 2) continue; c(Color.rgb(37, 70, 102)); r.set(s * .11f + x * cell + 2, s * .11f + y * cell + 2, s * .11f + (x + 1) * cell - 2, s * .11f + (y + 1) * cell - 2); canvas.drawRoundRect(r, 4f, 4f, p); t.color = ink; t.textSize = cell * .55f; canvas.drawText("${n++}", r.centerX(), r.centerY() + cell * .2f, t) } }
            "words" -> { t.color = Theme.DIM; t.textSize = s * .2f; for ((i, row) in listOf("QAZ", "WOR", "EDX").withIndex()) for ((j, ch) in row.withIndex()) canvas.drawText("$ch", s * (.25f + j * .25f), s * (.32f + i * .26f), t)
                line(Theme.GOLD, .1f); p.alpha = 120; canvas.drawLine(s * .25f, s * .51f, s * .75f, s * .51f, p) }
            "memory" -> { c(Color.rgb(30, 90, 124)); r.set(s * .08f, s * .18f, s * .48f, s * .82f); canvas.drawRoundRect(r, s * .06f, s * .06f, p); t.color = Theme.TEAL; t.textSize = s * .26f; canvas.drawText("∞", r.centerX(), r.centerY() + s * .08f, t)
                c(Color.rgb(246, 241, 228)); r.set(s * .52f, s * .18f, s * .92f, s * .82f); canvas.drawRoundRect(r, s * .06f, s * .06f, p); t.color = Color.rgb(192, 48, 48); t.textSize = s * .3f; canvas.drawText("♥", r.centerX(), r.centerY() + s * .1f, t) }
            "pegs" -> { c(Theme.TEAL); for ((x, y) in listOf(.2f to .3f, .5f to .3f, .8f to .3f, .35f to .55f, .65f to .55f, .5f to .8f)) canvas.drawCircle(s * x, s * y, s * .07f, p); c(Theme.COPPER); canvas.drawCircle(s * .2f, s * .3f, s * .07f, p); c(Color.rgb(240, 240, 240)); canvas.drawCircle(s * .5f, s * .1f, s * .08f, p) }
            "gryphon" -> { c(Theme.ACCENT); path.reset(); path.moveTo(s * .1f, s * .6f); path.quadTo(s * .45f, s * .1f, s * .55f, s * .5f); path.quadTo(s * .7f, s * .2f, s * .92f, s * .35f); path.quadTo(s * .7f, s * .45f, s * .6f, s * .7f); path.quadTo(s * .35f, s * .55f, s * .1f, s * .6f); canvas.drawPath(path, p) }
            "fishing" -> { line(Theme.SKY, .03f); canvas.drawLine(s * .5f, 0f, s * .5f, s * .35f, p); c(Theme.BAD); r.set(s * .36f, s * .32f, s * .64f, s * .6f); canvas.drawArc(r, 180f, 180f, true, p); c(Color.WHITE); canvas.drawArc(r, 0f, 180f, true, p)
                line(Theme.TEAL, .04f); canvas.drawArc(RectF(s * .15f, s * .55f, s * .85f, s * .85f), 200f, 140f, false, p) }
            "snake" -> { line(Color.rgb(90, 210, 120), .12f); path.reset(); path.moveTo(s * .12f, s * .7f); path.cubicTo(s * .3f, s * .2f, s * .5f, s * .9f, s * .72f, s * .38f); canvas.drawPath(path, p); c(Color.rgb(90, 210, 120)); canvas.drawCircle(s * .76f, s * .32f, s * .1f, p) }
            "bricks" -> { for (y in 0 until 3) for (x in 0 until 3) { c(listOf(Theme.BAD, Theme.GOLD, Theme.TEAL)[y]); r.set(s * (.08f + x * .29f), s * (.12f + y * .14f), s * (.34f + x * .29f), s * (.23f + y * .14f)); canvas.drawRect(r, p) }
                c(Color.rgb(240, 240, 240)); canvas.drawCircle(s * .55f, s * .66f, s * .06f, p); c(ink); r.set(s * .3f, s * .82f, s * .7f, s * .9f); canvas.drawRoundRect(r, s * .04f, s * .04f, p) }
        }
        canvas.restore()
    }
}
