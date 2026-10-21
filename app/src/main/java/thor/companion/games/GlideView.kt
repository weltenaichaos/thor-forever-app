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

/** Gryphon Glide's sky: tap anywhere to flap. Our own drawn gryphon, peaks and coins. */
class GlideView(context: Context, var game: Glide, private val best: () -> Int) : ActionView(context) {
    private val path = Path()
    private val sky = Paint().apply { shader = LinearGradient(0f, 0f, 0f, WORLD_H, Color.rgb(79, 134, 189), Color.rgb(169, 207, 231), Shader.TileMode.CLAMP) }
    private var drift = 0f

    override fun line() = "● ${game.coinsTaken} · ${number(game.metres.toInt())} m · best ${number(maxOf(best(), game.metres.toInt()))} m"
    override fun moving() = game.alive && game.started

    override fun step(dt: Float) {
        val m = game.metres.toInt() / 10; val c = game.coinsTaken; val alive = game.alive
        game.step(dt)
        drift += dt * 20f
        if (game.metres.toInt() / 10 != m || game.coinsTaken != c || game.alive != alive) onChange()
    }

    override fun touch(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN && game.alive) { game.flap(); onChange() }
    }

    override fun drawWorld(canvas: Canvas) {
        canvas.drawRect(0f, 0f, WORLD_W, WORLD_H, sky)
        // Clouds and far hills, slowly drifting.
        paint.color = Color.WHITE; paint.alpha = 110
        for (k in 0 until 3) {
            val x = ((k * 420f - drift * (1 + k * 0.3f)) % 1300f + 1300f) % 1300f - 150f
            r.set(x, 70f + k * 60, x + 230f, 120f + k * 60); canvas.drawOval(r, paint)
        }
        paint.alpha = 255
        paint.color = Color.rgb(125, 147, 168)
        path.reset(); path.moveTo(0f, WORLD_H)
        for (k in 0..6) {
            val x = k * 200f - (drift * 2f) % 200f
            path.lineTo(x, WORLD_H - 110f - ((k + (drift * 2f / 200f).toInt()) % 3) * 50f)
        }
        path.lineTo(WORLD_W + 200, WORLD_H); path.close(); canvas.drawPath(path, paint)
        for (p in game.peaks) {
            val half = Glide.PEAK_W / 2
            path.reset(); path.moveTo(p.x - half, 0f); path.lineTo(p.x + half, 0f); path.lineTo(p.x, p.top); path.close()
            paint.color = Color.rgb(91, 111, 136); canvas.drawPath(path, paint)
            outline()
            canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            path.reset(); path.moveTo(p.x - half, WORLD_H); path.lineTo(p.x + half, WORLD_H); path.lineTo(p.x, p.bottom); path.close()
            paint.color = Color.rgb(74, 95, 120); canvas.drawPath(path, paint)
            outline()
            canvas.drawPath(path, paint); paint.style = Paint.Style.FILL
            // Snow on the rising peak's tip.
            path.reset(); path.moveTo(p.x, p.bottom); path.lineTo(p.x + 14, p.bottom + 46); path.lineTo(p.x - 14, p.bottom + 46); path.close()
            paint.color = Color.rgb(232, 240, 248); canvas.drawPath(path, paint)
        }
        for (c in game.coins) if (!c.taken) {
            paint.color = Theme.GOLD; canvas.drawCircle(c.x, c.y, 15f, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3.5f; paint.color = Color.rgb(176, 122, 20)
            canvas.drawCircle(c.x, c.y, 15f, paint); paint.style = Paint.Style.FILL
        }
        gryphon(canvas, Glide.X, game.y, game.vy)
        when {
            !game.alive -> banner(canvas, "Crash!", "${number(game.metres.toInt())} m · ${game.coinsTaken} coins · tap New game")
            !game.started -> hint(canvas, "Tap anywhere to flap · fly through the gaps, grab the coins")
        }
    }

    private fun outline() {
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Theme.ACCENT
    }

    /** A simple bird-lion: body, tail, a wing that beats with the climb, a pale head and beak. */
    private fun gryphon(canvas: Canvas, x: Float, y: Float, vy: Float) {
        canvas.save()
        canvas.rotate((vy / 620f * 30f).coerceIn(-25f, 35f), x, y)
        paint.color = Color.rgb(138, 106, 58)
        path.reset(); path.moveTo(x - 38, y + 2); path.lineTo(x - 62, y - 10); path.lineTo(x - 58, y + 16); path.close(); canvas.drawPath(path, paint)
        paint.color = Theme.ACCENT
        r.set(x - 44, y - 18, x + 28, y + 20); canvas.drawOval(r, paint)
        paint.color = Color.rgb(138, 106, 58)
        val up = vy < 0
        path.reset(); path.moveTo(x - 18, y - 6)
        if (up) path.quadTo(x - 10, y - 56, x + 18, y - 44) else path.quadTo(x - 6, y + 40, x + 18, y + 30)
        path.quadTo(x + 4, y - 4, x + 6, y - 2); path.close(); canvas.drawPath(path, paint)
        paint.color = Theme.GOLD_TEXT
        canvas.drawCircle(x + 32, y - 10, 15f, paint)
        paint.color = Color.BLACK
        canvas.drawCircle(x + 37, y - 13, 2.6f, paint)
        paint.color = Color.rgb(255, 160, 64)
        path.reset(); path.moveTo(x + 45, y - 12); path.lineTo(x + 62, y - 8); path.lineTo(x + 45, y - 4); path.close(); canvas.drawPath(path, paint)
        canvas.restore()
    }
}
