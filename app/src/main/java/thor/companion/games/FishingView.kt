package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import thor.companion.Theme

/** Fishing Bobber's lake at sunset: tap to cast, tap again when the bobber dips. */
class FishingView(context: Context, var game: Fishing, private val best: () -> Float) : ActionView(context) {
    private val path = Path()
    private val sky = Paint().apply { shader = LinearGradient(0f, 0f, 0f, 300f, Color.rgb(240, 179, 122), Color.rgb(122, 166, 200), Shader.TileMode.CLAMP) }
    private val water = Paint().apply { shader = LinearGradient(0f, 300f, 0f, WORLD_H, Color.rgb(42, 108, 149), Color.rgb(13, 44, 70), Shader.TileMode.CLAMP) }
    private var time = 0f

    private fun kg(v: Float) = "%.1f".format(v)
    override fun line() = "${game.catch.size} caught · ${kg(game.kg)} kg · " +
        if (game.state == Fishing.State.OVER) "best ${kg(maxOf(best(), game.kg))} kg" else "${GameView.clock((game.left * 1000).toLong())} left"
    override fun moving() = game.started && game.state != Fishing.State.OVER

    override fun step(dt: Float) {
        val s = game.state; val sec = game.left.toInt()
        game.step(dt)
        time += dt
        if (game.state != s || game.left.toInt() != sec) {
            if (game.state == Fishing.State.BITE) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            onChange()
        }
    }

    override fun touch(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) { game.tap(); onChange() }
    }

    override fun drawWorld(canvas: Canvas) {
        canvas.drawRect(0f, 0f, WORLD_W, 300f, sky)
        paint.color = Color.rgb(255, 226, 160); paint.alpha = 230
        canvas.drawCircle(760f, 160f, 50f, paint); paint.alpha = 255
        paint.color = Color.rgb(61, 90, 115)
        path.reset(); path.moveTo(0f, 300f); path.lineTo(0f, 250f); path.lineTo(140f, 150f); path.lineTo(280f, 240f); path.lineTo(440f, 125f)
        path.lineTo(610f, 250f); path.lineTo(WORLD_W, 200f); path.lineTo(WORLD_W, 300f); path.close(); canvas.drawPath(path, paint)
        canvas.drawRect(0f, 300f, WORLD_W, WORLD_H, water)
        paint.color = Color.rgb(156, 196, 240); paint.alpha = 80; paint.strokeWidth = 2f
        for (k in 0 until 12) {
            val y = 330f + (k * 37) % 270; val x = ((k * 173f + time * 12f) % 1100f) - 80f
            canvas.drawLine(x, y, x + 70f, y, paint)
        }
        paint.alpha = 255
        val state = game.state
        val bx = 520f; val by = 400f
        val inWater = state == Fishing.State.WAITING || state == Fishing.State.BITE
        if (inWater) {
            // The line from the shore, and the bobber: bobbing while waiting, pulled under on a bite.
            val dip = if (state == Fishing.State.BITE) 12f + kotlin.math.sin(time * 40f) * 4f else kotlin.math.sin(time * 3f) * 2f
            paint.style = Paint.Style.STROKE; paint.color = Theme.GOLD_TEXT; paint.strokeWidth = 1.5f; paint.alpha = 200
            path.reset(); path.moveTo(40f, WORLD_H); path.quadTo(260f, 120f, bx, by - 18 + dip); canvas.drawPath(path, paint)
            paint.color = Color.rgb(156, 196, 240); paint.alpha = if (state == Fishing.State.BITE) 220 else 150; paint.strokeWidth = 2.5f
            r.set(bx - 40, by - 9, bx + 40, by + 9); canvas.drawOval(r, paint)
            if (state == Fishing.State.BITE) { r.set(bx - 70, by - 16, bx + 70, by + 16); canvas.drawOval(r, paint) }
            paint.style = Paint.Style.FILL; paint.alpha = 255
            canvas.save(); canvas.clipRect(0f, 0f, WORLD_W, by + 2)
            paint.color = Color.rgb(212, 85, 61); r.set(bx - 14, by - 14 + dip, bx + 14, by + 14 + dip); canvas.drawArc(r, 180f, 180f, true, paint)
            paint.color = Color.rgb(246, 236, 212); canvas.drawArc(r, 0f, 180f, true, paint)
            paint.color = Theme.GOLD_TEXT; canvas.drawRect(bx - 2, by - 38 + dip, bx + 2, by - 14 + dip, paint)
            canvas.restore()
        }
        // The catch so far.
        r.set(WORLD_W - 250f, 316f, WORLD_W - 16f, WORLD_H - 16f)
        paint.color = Theme.SURFACE; paint.alpha = 190; canvas.drawRoundRect(r, 14f, 14f, paint); paint.alpha = 255
        heading.textSize = 20f; heading.color = Theme.ACCENT
        canvas.drawText("Your catch", r.centerX(), r.top + 32, heading)
        text.textSize = 18f
        for ((k, f) in game.catch.takeLast(6).withIndex()) {
            val y = r.top + 70 + k * 40
            text.textAlign = Paint.Align.LEFT; text.color = if (f.rare) Theme.GOLD else Theme.TEXT
            canvas.drawText(f.name, r.left + 16, y, text)
            text.textAlign = Paint.Align.RIGHT; text.color = Theme.DIM
            canvas.drawText(if (f.kg == 0f) "·" else "${kg(f.kg)} kg", r.right - 16, y, text)
        }
        text.textAlign = Paint.Align.CENTER
        when (state) {
            Fishing.State.READY -> hint(canvas, if (!game.started) "Tap to cast · when the bobber dips, tap quickly" else "Tap to cast again")
            Fishing.State.WAITING -> hint(canvas, "Wait for it…")
            Fishing.State.BITE -> banner(canvas, "Bite! Tap!")
            Fishing.State.CAUGHT -> game.last?.let { banner(canvas, it.name + if (it.kg > 0) "!" else "…", if (it.kg > 0) "${kg(it.kg)} kg" else "Well, it's something") }
            Fishing.State.LOST -> banner(canvas, "It got away", "Tap a little quicker next time")
            Fishing.State.EARLY -> banner(canvas, "Too early", "Wait until the bobber dips")
            Fishing.State.OVER -> banner(canvas, "Time's up!", "${game.catch.size} caught · ${kg(game.kg)} kg · tap New game")
        }
    }
}
