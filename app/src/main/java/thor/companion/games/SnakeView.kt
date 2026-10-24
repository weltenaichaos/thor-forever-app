package thor.companion.games

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.view.MotionEvent
import thor.companion.Theme

/** Snake's field: swipe (anywhere) to turn. The first swipe starts it. */
class SnakeView(context: Context, var game: Snake, private val best: () -> Int) : ActionView(context) {
    private var downX = 0f
    private var downY = 0f
    private var swiped = false
    private val path = Path()

    override fun line() = if (!game.alive) "Length ${game.length} · best ${maxOf(best(), game.length)} · tap New game" else "Length ${game.length} · best ${maxOf(best(), game.length)}"
    override fun moving() = game.alive && game.started

    override fun step(dt: Float) {
        val was = game.length; val alive = game.alive
        game.step(dt)
        if (game.length != was) { val h = game.body.first(); popup("+1", (h % Snake.W + 0.5f) * WORLD_W / Snake.W, (h / Snake.W) * WORLD_H / Snake.H, Theme.GOOD) }
        if (!game.alive && alive) { val h = game.body.first(); burst((h % Snake.W + 0.5f) * WORLD_W / Snake.W, (h / Snake.W + 0.5f) * WORLD_H / Snake.H, Theme.TEAL, 14) }
        if (game.length != was || game.alive != alive) onChange()
    }

    override fun touch(action: Int, x: Float, y: Float) {
        when (action) {
            MotionEvent.ACTION_DOWN -> { downX = x; downY = y; swiped = false }
            MotionEvent.ACTION_MOVE -> if (!swiped) {
                val dx = x - downX; val dy = y - downY
                if (maxOf(Math.abs(dx), Math.abs(dy)) > 24f) {
                    swiped = true
                    game.turn(if (Math.abs(dx) > Math.abs(dy)) (if (dx > 0) 1 else 3) else (if (dy > 0) 2 else 0))
                    downX = x; downY = y; swiped = false
                    onChange()
                }
            }
        }
    }

    override fun drawWorld(canvas: Canvas) {
        val cw = WORLD_W / Snake.W; val ch = WORLD_H / Snake.H
        paint.color = Theme.SURFACE
        canvas.drawRect(0f, 0f, WORLD_W, WORLD_H, paint)
        paint.color = Theme.BG
        for (y in 0 until Snake.H) for (x in 0 until Snake.W) if ((x + y) % 2 == 1) canvas.drawRect(x * cw, y * ch, (x + 1) * cw, (y + 1) * ch, paint)
        if (game.gem >= 0) {
            val gx = (game.gem % Snake.W) * cw + cw / 2; val gy = (game.gem / Snake.W) * ch + ch / 2
            glow(canvas, gx, gy, cw * 1.1f, Color.rgb(255, 120, 90), (160 + 60 * Math.sin(android.os.SystemClock.uptimeMillis() / 250.0)).toInt())
            if (moving()) postInvalidateOnAnimation()
            path.reset(); path.moveTo(gx, gy - ch * 0.38f); path.lineTo(gx + cw * 0.36f, gy); path.lineTo(gx, gy + ch * 0.38f); path.lineTo(gx - cw * 0.36f, gy); path.close()
            paint.color = Color.rgb(212, 85, 61); canvas.drawPath(path, paint)
            paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 2.5f; paint.color = Color.rgb(255, 210, 160)
            canvas.drawPath(path, paint); paint.style = android.graphics.Paint.Style.FILL
        }
        for ((k, cell) in game.body.withIndex().reversed()) {
            val x = (cell % Snake.W) * cw; val y = (cell / Snake.W) * ch
            r.set(x + 2, y + 2, x + cw - 2, y + ch - 2)
            paint.color = if (k == 0) Theme.ACCENT else Theme.TEAL
            paint.alpha = if (k == 0) 255 else maxOf(130, 255 - k * 4)
            canvas.drawRoundRect(r, 8f, 8f, paint)
            paint.alpha = 255
        }
        // Eyes, looking the way it goes.
        val head = game.body.first()
        val hx = (head % Snake.W) * cw + cw / 2; val hy = (head / Snake.W) * ch + ch / 2
        paint.color = Theme.SURFACE
        val (ex, ey) = when (game.dir) { 0 -> 0f to -1f; 1 -> 1f to 0f; 2 -> 0f to 1f; else -> -1f to 0f }
        canvas.drawCircle(hx + ex * cw * 0.2f - ey * cw * 0.18f, hy + ey * ch * 0.2f + ex * ch * 0.18f, 4f, paint)
        canvas.drawCircle(hx + ex * cw * 0.2f + ey * cw * 0.18f, hy + ey * ch * 0.2f - ex * ch * 0.18f, 4f, paint)
        when {
            !game.alive -> banner(canvas, "Ouch!", "Length ${game.length} · tap New game")
            !game.started -> hint(canvas, "Swipe to start and to turn · eat the gems · don't bite yourself")
        }
    }
}
