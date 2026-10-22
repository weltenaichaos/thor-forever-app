package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.SystemClock
import android.view.MotionEvent
import thor.companion.Theme
import kotlin.math.cos
import kotlin.math.sin

/**
 * Peg Bounce's board (Jesse, 2026-10-22: less basic): a night sky with a moon and a castle
 * outline, shiny pegs that glow when hit, points popping up, a gold launcher with our ∞,
 * the balls left in a tube on the left and a meter on the right that fills as orange goes
 * (x2, x3, x5 points). Drag to aim, let go to shoot.
 */
class PegsView(context: Context, var game: Pegs, private val best: () -> Int) : ActionView(context) {
    private var aiming = false
    private val trail = Trail()
    private val path = Path()
    private val sky = Paint().apply { shader = LinearGradient(0f, 0f, 0f, WORLD_H, intArrayOf(Color.rgb(10, 26, 44), Color.rgb(20, 48, 76), Color.rgb(29, 63, 94)), floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP) }
    private val gold = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stars = List(70) { k -> Triple((k * 137.5f + 31) % WORLD_W, (k * 89.3f + 17) % (WORLD_H * 0.8f), 0.6f + (k % 4) * 0.5f) }
    private var wasFlying = false

    override fun line() = "orange ${game.orangeLeft} left · ${number(game.score)}" +
        if (game.over) " · best ${number(maxOf(best(), game.score))}" else ""
    // The bucket keeps moving while the board is shown.
    override fun moving() = !game.over

    override fun step(dt: Float) {
        game.step(dt)
        if (game.flying) trail.add(game.ballX, game.ballY)
        if (wasFlying && !game.flying) {
            trail.clear()
            if (game.freeBall) popup("Free ball!", game.bucket, Pegs.BUCKET_Y - 20, Theme.GOOD)
            else if (game.lastShot > 0) popup("+${number(game.lastShot)}", WORLD_W / 2, 120f)
            onChange()
        }
        wasFlying = game.flying
    }

    override fun touch(action: Int, x: Float, y: Float) {
        if (game.over || game.flying) return
        when (action) {
            MotionEvent.ACTION_DOWN -> { aiming = true; game.aimAt(x, y) }
            MotionEvent.ACTION_MOVE -> game.aimAt(x, y)
            MotionEvent.ACTION_UP -> if (aiming) { aiming = false; game.aimAt(x, y); game.shoot(); wasFlying = true; onChange() }
            MotionEvent.ACTION_CANCEL -> aiming = false
        }
    }

    private fun goldShader(top: Float, bottom: Float) = LinearGradient(0f, top, 0f, bottom,
        intArrayOf(Color.rgb(255, 240, 200), Color.rgb(217, 180, 106), Color.rgb(138, 106, 50)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)

    override fun drawWorld(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        canvas.drawRect(0f, 0f, WORLD_W, WORLD_H, sky)
        // The moon (a crescent) and twinkling stars.
        glow(canvas, 830f, 95f, 120f, Color.rgb(255, 231, 160), 70)
        paint.color = Color.rgb(246, 236, 212); paint.alpha = 220
        canvas.drawCircle(830f, 95f, 36f, paint)
        paint.color = Color.rgb(16, 38, 62); paint.alpha = 240
        canvas.drawCircle(843f, 87f, 32f, paint)
        paint.color = Color.WHITE
        for ((k, s) in stars.withIndex()) {
            paint.alpha = (90 + 110 * (0.5f + 0.5f * sin(now / 700f + k))).toInt()
            canvas.drawCircle(s.first, s.second, s.third, paint)
        }
        paint.alpha = 255
        // A far castle along the bottom.
        paint.color = Color.rgb(8, 20, 34)
        path.reset(); path.moveTo(0f, WORLD_H); path.lineTo(0f, WORLD_H - 70)
        for ((x, y) in listOf(50f to -70f, 50f to -120f, 66f to -138f, 82f to -120f, 82f to -90f, 160f to -90f, 160f to -64f, 270f to -76f,
                350f to -56f, 470f to -80f, 570f to -60f, 690f to -84f, 760f to -84f, 760f to -128f, 776f to -148f, 792f to -128f, 792f to -76f, 890f to -64f, 1000f to -80f)) path.lineTo(x, WORLD_H + y)
        path.lineTo(WORLD_W, WORLD_H); path.close()
        paint.alpha = 220; canvas.drawPath(path, paint); paint.alpha = 255

        // Balls left, in a tube on the left.
        tube(canvas, 32f)
        for (k in 0 until minOf(game.balls, 11)) orb(canvas, 32f, 545f - k * 40f, 12f, Color.WHITE, Color.rgb(246, 236, 212), Color.rgb(201, 168, 106))
        heading.textSize = 17f; heading.color = Theme.ACCENT
        canvas.drawText("${game.balls}", 32f, 92f, heading)
        // The orange meter on the right: it fills as orange goes, and the points grow.
        tube(canvas, 968f)
        val top = 108f; val h = 456f
        val f = game.fever
        if (f > 0f) {
            r.set(962f, top + h * (1 - f) + 4, 974f, top + h - 4)
            paint.color = Color.rgb(240, 138, 72); canvas.drawRoundRect(r, 6f, 6f, paint)
            paint.color = Color.WHITE; paint.alpha = 90; r.right = r.left + 5; canvas.drawRoundRect(r, 3f, 3f, paint); paint.alpha = 255
        }
        heading.textSize = 15f
        heading.textAlign = Paint.Align.RIGHT
        for ((label, at) in listOf("x2" to 0.25f, "x3" to 0.5f, "x5" to 0.75f)) {
            val y = top + h * (1 - at)
            paint.color = Theme.ACCENT; paint.strokeWidth = 2f
            canvas.drawLine(952f, y, 984f, y, paint)
            heading.color = if (f >= at) Theme.GOLD else Theme.GOLD_TEXT
            canvas.drawText(label, 946f, y + 5, heading)
        }
        heading.textAlign = Paint.Align.CENTER

        // The pegs: shiny, and glowing once hit.
        for (p in game.pegs) {
            if (p.lit) glow(canvas, p.x, p.y, 30f, if (p.orange) Color.rgb(255, 196, 120) else Color.rgb(140, 220, 255))
            paint.color = Color.BLACK; paint.alpha = 80
            canvas.drawCircle(p.x + 2, p.y + 3, Pegs.PEG_R, paint); paint.alpha = 255
            when {
                p.lit && p.orange -> orb(canvas, p.x, p.y, Pegs.PEG_R, Color.WHITE, Color.rgb(255, 226, 176), Color.rgb(255, 173, 90))
                p.lit -> orb(canvas, p.x, p.y, Pegs.PEG_R, Color.WHITE, Color.rgb(191, 233, 255), Color.rgb(95, 180, 232))
                p.orange -> orb(canvas, p.x, p.y, Pegs.PEG_R, Color.rgb(255, 232, 204), Color.rgb(240, 138, 72), Color.rgb(162, 74, 24))
                else -> orb(canvas, p.x, p.y, Pegs.PEG_R, Color.rgb(207, 230, 255), Color.rgb(74, 143, 224), Color.rgb(29, 78, 143))
            }
        }
        for (hit in game.hits) popup("+${hit.points}", hit.x, hit.y - 18, if (hit.orange) Theme.GOLD else Color.rgb(170, 220, 255))
        game.hits.clear()

        // The bucket: a gold-rimmed basket, glowing.
        val bx = game.bucket; val by = Pegs.BUCKET_Y
        glow(canvas, bx, by + 14, 90f, Theme.TEAL, 110)
        path.reset(); path.moveTo(bx - Pegs.BUCKET_W / 2, by); path.lineTo(bx + Pegs.BUCKET_W / 2, by)
        path.lineTo(bx + Pegs.BUCKET_W / 2 - 12, by + 28); path.lineTo(bx - Pegs.BUCKET_W / 2 + 12, by + 28); path.close()
        paint.color = Color.rgb(31, 107, 74); canvas.drawPath(path, paint)
        gold.shader = goldShader(by - 4, by + 4)
        r.set(bx - Pegs.BUCKET_W / 2 - 5, by - 4, bx + Pegs.BUCKET_W / 2 + 5, by + 4); canvas.drawRoundRect(r, 4f, 4f, gold)
        heading.textSize = 13f; heading.color = Theme.GOLD_TEXT
        canvas.drawText("FREE BALL", bx, by + 20, heading)

        // While aiming: dots along the start of the flight, fading.
        if (!game.flying && !game.over) {
            var px = Pegs.LAUNCH_X + sin(game.aim) * 40; var py = Pegs.LAUNCH_Y + cos(game.aim) * 40
            var vx = sin(game.aim) * Pegs.SHOT; var vy = cos(game.aim) * Pegs.SHOT
            paint.color = Theme.GOLD_TEXT
            for (k in 0 until 26) {
                for (s in 0 until 4) { vy += Pegs.GRAVITY / 120f; px += vx / 120f; py += vy / 120f }
                if (px < 0 || px > WORLD_W) vx = -vx
                paint.alpha = 200 - k * 7
                canvas.drawCircle(px, py, 3f - k * 0.06f, paint)
            }
            paint.alpha = 255
        }
        // The ball: a glow, its trail, and a pearl.
        if (game.flying) {
            drawTrail(canvas, trail, Pegs.R, Color.rgb(255, 231, 160))
            glow(canvas, game.ballX, game.ballY, 34f, Color.rgb(255, 231, 160), 150)
            orb(canvas, game.ballX, game.ballY, Pegs.R, Color.WHITE, Color.rgb(246, 236, 212), Color.rgb(201, 168, 106))
        }
        // The launcher: a gold mount with our ∞ and a barrel that follows the aim.
        gold.shader = goldShader(0f, 70f)
        canvas.save(); canvas.rotate(-Math.toDegrees(game.aim.toDouble()).toFloat(), Pegs.LAUNCH_X, Pegs.LAUNCH_Y)
        r.set(Pegs.LAUNCH_X - 12, Pegs.LAUNCH_Y, Pegs.LAUNCH_X + 12, Pegs.LAUNCH_Y + 50); canvas.drawRoundRect(r, 6f, 6f, gold)
        r.set(Pegs.LAUNCH_X - 15, Pegs.LAUNCH_Y + 42, Pegs.LAUNCH_X + 15, Pegs.LAUNCH_Y + 52); canvas.drawRoundRect(r, 4f, 4f, gold)
        if (!game.flying && !game.over) orb(canvas, Pegs.LAUNCH_X, Pegs.LAUNCH_Y + 40, Pegs.R, Color.WHITE, Color.rgb(246, 236, 212), Color.rgb(201, 168, 106))
        canvas.restore()
        path.reset(); path.moveTo(Pegs.LAUNCH_X - 75, 0f); path.quadTo(Pegs.LAUNCH_X, 58f, Pegs.LAUNCH_X + 75, 0f); path.close()
        canvas.drawPath(path, gold)
        canvas.drawCircle(Pegs.LAUNCH_X, Pegs.LAUNCH_Y, 24f, gold)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; paint.color = Color.rgb(90, 69, 32)
        canvas.drawCircle(Pegs.LAUNCH_X, Pegs.LAUNCH_Y, 24f, paint); paint.style = Paint.Style.FILL
        text.textSize = 22f; text.color = Color.rgb(90, 69, 32)
        canvas.drawText("∞", Pegs.LAUNCH_X, Pegs.LAUNCH_Y + 8, text)

        when {
            game.won -> banner(canvas, "All orange cleared!", "${number(game.score)} points · tap New game")
            game.over -> banner(canvas, "Out of balls", "${game.orangeLeft} orange left · tap New game")
            !game.flying && game.score == 0 && game.balls == 10 -> hint(canvas, "Drag to aim · let go to shoot · hit every orange peg", WORLD_H - 70)
        }
    }

    /** A dark glass tube with a gold rim, centred on [x]. */
    private fun tube(canvas: Canvas, x: Float) {
        r.set(x - 15, 100f, x + 15, 572f)
        paint.color = Color.rgb(8, 20, 34); canvas.drawRoundRect(r, 15f, 15f, paint)
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.color = Theme.ACCENT
        canvas.drawRoundRect(r, 15f, 15f, paint); paint.style = Paint.Style.FILL
    }
}
