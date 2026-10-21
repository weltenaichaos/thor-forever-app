package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import thor.companion.Theme
import kotlin.math.cos
import kotlin.math.sin

/** Peg Bounce's board: drag to aim (a dotted path shows the start of the flight), let go to shoot. */
class PegsView(context: Context, var game: Pegs, private val best: () -> Int) : ActionView(context) {
    private var aiming = false

    override fun line() = "Balls ${game.balls} · orange ${game.orangeLeft} left · ${number(game.score)}" +
        if (game.over) " · best ${number(maxOf(best(), game.score))}" else ""
    // The bucket keeps moving while the board is shown.
    override fun moving() = !game.over

    override fun step(dt: Float) {
        val wasFlying = game.flying
        game.step(dt)
        if (wasFlying && !game.flying) onChange()
    }

    override fun touch(action: Int, x: Float, y: Float) {
        if (game.over || game.flying) return
        when (action) {
            MotionEvent.ACTION_DOWN -> { aiming = true; game.aimAt(x, y) }
            MotionEvent.ACTION_MOVE -> game.aimAt(x, y)
            MotionEvent.ACTION_UP -> if (aiming) { aiming = false; game.aimAt(x, y); game.shoot(); onChange() }
            MotionEvent.ACTION_CANCEL -> aiming = false
        }
    }

    override fun drawWorld(canvas: Canvas) {
        paint.color = Theme.SURFACE
        canvas.drawRect(0f, 0f, WORLD_W, WORLD_H, paint)
        paint.color = Theme.DIM; paint.alpha = 90
        for (k in 0 until 50) canvas.drawCircle((k * 137f) % WORLD_W, (k * 89f + 30) % WORLD_H, 1.2f + (k % 3), paint)
        paint.alpha = 255
        for (p in game.pegs) {
            paint.color = when {
                p.lit && p.orange -> Color.rgb(255, 196, 120)
                p.lit -> Color.rgb(150, 205, 255)
                p.orange -> Color.rgb(222, 140, 90)
                else -> Color.rgb(59, 127, 209)
            }
            canvas.drawCircle(p.x, p.y, Pegs.PEG_R, paint)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
            paint.color = if (p.orange) Color.rgb(255, 210, 160) else Color.rgb(156, 196, 240)
            if (p.lit) paint.color = Color.WHITE
            canvas.drawCircle(p.x, p.y, Pegs.PEG_R, paint); paint.style = Paint.Style.FILL
        }
        // The bucket.
        r.set(game.bucket - Pegs.BUCKET_W / 2, Pegs.BUCKET_Y, game.bucket + Pegs.BUCKET_W / 2, Pegs.BUCKET_Y + 24)
        paint.color = Theme.GOOD; canvas.drawRoundRect(r, 10f, 10f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.color = Theme.GOLD_TEXT
        canvas.drawRoundRect(r, 10f, 10f, paint); paint.style = Paint.Style.FILL
        text.textSize = 15f; text.color = Theme.SURFACE
        canvas.drawText("free ball", r.centerX(), r.centerY() + 5, text)
        // The launcher and, while aiming, the first part of the flight.
        canvas.save(); canvas.rotate(-Math.toDegrees(game.aim.toDouble()).toFloat(), Pegs.LAUNCH_X, Pegs.LAUNCH_Y)
        paint.color = Theme.ACCENT
        r.set(Pegs.LAUNCH_X - 12, Pegs.LAUNCH_Y - 4, Pegs.LAUNCH_X + 12, Pegs.LAUNCH_Y + 38); canvas.drawRoundRect(r, 6f, 6f, paint)
        canvas.restore()
        r.set(Pegs.LAUNCH_X - 44, 0f, Pegs.LAUNCH_X + 44, 26f); canvas.drawRoundRect(r, 8f, 8f, paint)
        if (!game.flying && !game.over) {
            paint.color = Theme.GOLD_TEXT; paint.alpha = 170
            var px = Pegs.LAUNCH_X + sin(game.aim) * 40; var py = Pegs.LAUNCH_Y + cos(game.aim) * 40
            var vx = sin(game.aim) * Pegs.SHOT; var vy = cos(game.aim) * Pegs.SHOT
            for (k in 0 until 28) {
                for (s in 0 until 4) { vy += Pegs.GRAVITY / 120f; px += vx / 120f; py += vy / 120f }
                if (px < 0 || px > WORLD_W) vx = -vx
                canvas.drawCircle(px, py, 2.6f, paint)
            }
            paint.alpha = 255
        }
        paint.color = Theme.GOLD_TEXT
        canvas.drawCircle(game.ballX, game.ballY, Pegs.R, paint)
        when {
            game.won -> banner(canvas, "All orange cleared!", "${number(game.score)} points · tap New game")
            game.over -> banner(canvas, "Out of balls", "${game.orangeLeft} orange left · tap New game")
            game.flying && game.freeBall -> {}
            !game.flying && game.score == 0 && game.balls == 10 -> hint(canvas, "Drag to aim · let go to shoot · hit every orange peg", WORLD_H - 50)
        }
    }
}
