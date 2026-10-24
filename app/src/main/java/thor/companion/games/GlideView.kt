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
import java.util.IdentityHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Gryphon Glide's sky (Jesse, 2026-10-23: less bare-bones). Tap anywhere to flap. All our own
 * drawing: a gryphon whose wings beat with each flap, rocky spires with snow and frost, spinning
 * coins, far mountains, hills with pines and clouds moving at their own speeds, and a day that
 * turns to sunset, night and dawn the further it flies. A gold flag marks the best flight.
 */
class GlideView(context: Context, var game: Glide, private val best: () -> Int) : ActionView(context) {
    private val path = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }

    /** When the last flap came (for the wing beat); 0 before the first. */
    private var flapAt = 0L
    private var lastMilestone = 0

    // ---- The day going round: colours keyed to how far the gryphon has flown ----

    private class Look(
        val skyTop: Int, val skyMid: Int, val skyLow: Int, val far: Int, val hill: Int, val forest: Int,
        val rockLit: Int, val rockShade: Int, val cloudTop: Int, val cloudLow: Int, val night: Float,
    )

    private val look = Mixed()

    /** The colours of now, mixed from the two nearest times of day. */
    private class Mixed {
        var skyTop = 0; var skyMid = 0; var skyLow = 0; var far = 0; var hill = 0; var forest = 0
        var rockLit = 0; var rockShade = 0; var cloudTop = 0; var cloudLow = 0; var night = 0f
        /** Where the sun stands (world y; below the horizon at night) and how red it is. */
        var sunY = 0f; var sunset = 0f
    }

    private fun mix(metres: Float) {
        val p = (metres % CYCLE) / CYCLE
        var k = 0
        while (k < STOPS.size - 2 && STOPS[k + 1].first <= p) k++
        val (p0, a) = STOPS[k]; val (p1, b) = STOPS[k + 1]
        val t = if (p1 > p0) ((p - p0) / (p1 - p0)).coerceIn(0f, 1f) else 0f
        val m = look
        m.skyTop = blend(a.skyTop, b.skyTop, t); m.skyMid = blend(a.skyMid, b.skyMid, t); m.skyLow = blend(a.skyLow, b.skyLow, t)
        m.far = blend(a.far, b.far, t); m.hill = blend(a.hill, b.hill, t); m.forest = blend(a.forest, b.forest, t)
        m.rockLit = blend(a.rockLit, b.rockLit, t); m.rockShade = blend(a.rockShade, b.rockShade, t)
        m.cloudTop = blend(a.cloudTop, b.cloudTop, t); m.cloudLow = blend(a.cloudLow, b.cloudLow, t)
        m.night = a.night + (b.night - a.night) * t
        // The sun: high in the day, down behind the mountains by sunset's end, up again at dawn.
        m.sunY = when {
            p < 0.18f -> 120f
            p < 0.44f -> 120f + (p - 0.18f) / 0.26f * 400f
            p < 0.86f -> 700f
            else -> 520f - (p - 0.86f) / 0.14f * 400f
        }
        m.sunset = ((m.sunY - 120f) / 330f).coerceIn(0f, 1f)
    }

    private val sky = Paint()
    private val mist = Paint()
    private var skyKey = -1

    /** Builds the sky's and the mist's colours again when the time of day moved on a little. */
    private fun skyShaders() {
        val key = (game.metres / 3f).toInt()
        if (key == skyKey) return
        skyKey = key
        sky.shader = LinearGradient(0f, 0f, 0f, WORLD_H, intArrayOf(look.skyTop, look.skyMid, look.skyLow), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        mist.shader = LinearGradient(0f, WORLD_H - 90f, 0f, WORLD_H, intArrayOf(Theme.withAlpha(look.skyLow, 0), Theme.withAlpha(look.skyLow, 120)), null, Shader.TileMode.CLAMP)
    }

    // ---- The land: tiles made once, drawn twice side by side while they scroll ----

    private val farLand = Path()
    private val hills = Path()
    private val pines = Path()
    private val woods = Path()
    private val ceiling = Path()
    private val stars = List(60) { k -> Triple((k * 137.5f + 31) % WORLD_W, (k * 71.3f + 13) % (WORLD_H * 0.6f), 0.7f + (k % 4) * 0.45f) }

    init {
        // Far mountains: a tile 1400 wide with sharp tops.
        val far = floatArrayOf(380f, 300f, 340f, 250f, 310f, 360f, 280f, 230f, 300f, 350f, 270f, 320f, 380f, 290f, 380f)
        farLand.moveTo(0f, WORLD_H)
        // Each tile reaches a little past its width (the same shape as the next tile's start), so no seam shows.
        for ((k, y) in far.withIndex()) farLand.lineTo(k * 100f, y + 40f)
        farLand.lineTo(FAR_W + 100f, far[1] + 40f)
        farLand.lineTo(FAR_W + 100f, WORLD_H); farLand.close()
        // Rolling hills (a tile 1000 wide) and pines standing on them.
        hills.moveTo(0f, WORLD_H)
        val hillY = { x: Float -> WORLD_H - 120f - 34f * sin(x / 1000f * 2 * Math.PI.toFloat() * 2) - 16f * sin(x / 1000f * 2 * Math.PI.toFloat() * 5 + 1f) }
        var x = 0f
        while (x <= WORLD_W + 20f) { hills.lineTo(x, hillY(x)); x += 20f }
        hills.lineTo(WORLD_W + 20f, WORLD_H); hills.close()
        for (k in 0 until 26) {
            val px = (k * 389f) % WORLD_W; val h = 22f + (k * 7 % 5) * 6f
            val base = hillY(px) + 6f
            pines.moveTo(px, base - h); pines.lineTo(px + h * 0.32f, base); pines.lineTo(px - h * 0.32f, base); pines.close()
        }
        // The near woods along the bottom (a tile 1000 wide, moving with the peaks).
        woods.moveTo(0f, WORLD_H)
        x = 0f
        var k = 0
        while (x < WORLD_W + 20f) {
            val h = 14f + (k % 50 * 5 % 7) * 2.5f
            woods.lineTo(x, WORLD_H - 4f); woods.lineTo(x + 10f, WORLD_H - 4f - h); woods.lineTo(x + 20f, WORLD_H - 4f)
            x += 20f; k++
        }
        woods.lineTo(x, WORLD_H); woods.close()
        // The cloud bank the upper spires hang from (a tile 1000 wide).
        for (c in 0 until 20) ceiling.addCircle(c * 50f + 25f, -6f + (c * 7 % 3) * 4f, 28f + (c * 11 % 4) * 5f, Path.Direction.CW)
        ceiling.addRect(-30f, -40f, WORLD_W + 30f, 6f, Path.Direction.CW)
    }

    // ---- Flaps, coins and the score line ----

    private class Puff(var x: Float, val y: Float, val at: Long)
    private val puffs = mutableListOf<Puff>()

    override fun line() = "● ${game.coinsTaken} · ${number(game.metres.toInt())} m · best ${number(maxOf(best(), game.metres.toInt()))} m"
    override fun moving() = game.alive && game.started

    override fun step(dt: Float) {
        val m = game.metres.toInt() / 10; val c = game.coinsTaken; val alive = game.alive
        val before = game.metres
        game.step(dt)
        val moved = (game.metres - before) * 25f
        for (p in puffs) p.x -= moved
        if (game.coinsTaken != c) { popup("+1", Glide.X + 30, game.y - 30); burst(Glide.X + 20, game.y, Theme.GOLD, 6) }
        if (!game.alive && alive) {
            burst(Glide.X, game.y, Color.rgb(246, 238, 220), 14)
            burst(Glide.X - 10, game.y, Color.rgb(196, 150, 86), 12)
        }
        val milestone = game.metres.toInt() / 500
        if (milestone > lastMilestone) { lastMilestone = milestone; popup("${number(milestone * 500)} m!", WORLD_W / 2, 90f) }
        if (game.metres.toInt() / 10 != m || game.coinsTaken != c || game.alive != alive) onChange()
    }

    override fun touch(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN && game.alive) {
            if (!game.started) lastMilestone = 0
            game.flap(); onChange()
            flapAt = SystemClock.uptimeMillis()
            puffs += Puff(Glide.X - 26, game.y + 14, flapAt)
            if (puffs.size > 6) puffs.removeAt(0)
        }
    }

    override fun drawWorld(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        mix(game.metres)
        skyShaders()
        val scroll = game.metres * 25f
        canvas.drawRect(0f, 0f, WORLD_W, WORLD_H, sky)
        // Stars and the moon come out at night.
        if (look.night > 0.05f) {
            fill.color = Color.WHITE
            for ((k, s) in stars.withIndex()) {
                fill.alpha = (look.night * (110 + 110 * (0.5f + 0.5f * sin(now / 700f + k)))).toInt()
                canvas.drawCircle(s.first, s.second, s.third, fill)
            }
            fill.alpha = 255
        }
        if (look.night > 0.5f) {
            // A crescent: the moon, then the sky's own colour over most of it.
            val a = ((look.night - 0.5f) * 2f * 255).toInt()
            glow(canvas, 820f, 100f, 110f, Color.rgb(255, 231, 160), (a * 0.3f).toInt())
            fill.color = Color.rgb(246, 236, 212); fill.alpha = (a * 0.9f).toInt()
            canvas.drawCircle(820f, 100f, 32f, fill)
            fill.color = blend(look.skyTop, look.skyMid, 92f / (WORLD_H * 0.55f)); fill.alpha = a
            canvas.drawCircle(832f, 92f, 28f, fill)
            fill.alpha = 255
        }
        // The sun, going down behind the far mountains.
        if (look.sunY < 640f) {
            // The glow's colour goes in 8 steps: each step is a small picture made once.
            val glowing = blend(Color.rgb(255, 247, 222), Color.rgb(255, 146, 70), (look.sunset * 8).toInt() / 8f)
            glow(canvas, 790f, look.sunY, 200f, glowing, 150)
            fill.color = blend(Color.rgb(255, 247, 222), Color.rgb(255, 146, 70), look.sunset)
            canvas.drawCircle(790f, look.sunY, 34f + 6f * look.sunset, fill)
        }
        // Far mountains (slow), clouds, hills with pines (faster).
        fill.color = look.far
        tile(canvas, farLand, scroll * 0.12f + drift(now) * 0.2f, FAR_W, fill)
        clouds(canvas, scroll * 0.3f + drift(now))
        fill.color = look.hill
        tile(canvas, hills, scroll * 0.5f, WORLD_W, fill)
        fill.color = blend(look.hill, look.forest, 0.55f)
        tile(canvas, pines, scroll * 0.5f, WORLD_W, fill)
        bestFlag(canvas)
        // The spires, the cloud bank above them and the woods below.
        val half = Glide.PEAK_W / 2
        if (shapes.size > game.peaks.size) shapes.keys.retainAll(game.peaks.toSet())
        for (p in game.peaks) {
            val s = shapes.getOrPut(p) { Spires(p) }
            if (p.x < -half - 10 || p.x > WORLD_W + half + 10) continue
            canvas.save(); canvas.translate(p.x, 0f)
            spire(canvas, s.upper, s.upperLit, s.upperCracks, s.frost, Color.rgb(222, 238, 250))
            spire(canvas, s.lower, s.lowerLit, s.lowerCracks, s.snow, Color.WHITE)
            canvas.restore()
        }
        // Two rows of cloud: a darker one behind, a lighter one in front.
        fill.color = blend(look.cloudLow, look.rockShade, 0.5f)
        canvas.save(); canvas.translate(0f, 10f); tile(canvas, ceiling, scroll + 25f, WORLD_W, fill); canvas.restore()
        fill.color = blend(look.cloudTop, look.cloudLow, 0.6f)
        tile(canvas, ceiling, scroll, WORLD_W, fill)
        fill.color = look.forest
        tile(canvas, woods, scroll, WORLD_W, fill)
        canvas.drawRect(0f, WORLD_H - 90f, WORLD_W, WORLD_H, mist)
        // Wind and flap puffs, coins, the gryphon.
        if (moving()) {
            line.strokeWidth = 2.5f; line.color = Color.WHITE
            for (k in 0 until 5) {
                val wx = WORLD_W - ((scroll * 1.6f + k * 233f) % (WORLD_W + 200f)) + 100f
                line.alpha = 46
                canvas.drawLine(wx, 90f + k * 103f, wx + 70f, 90f + k * 103f, line)
            }
            line.alpha = 255
        }
        val pi = puffs.iterator()
        while (pi.hasNext()) {
            val p = pi.next(); val t = (now - p.at) / 450f
            if (t >= 1f) { pi.remove(); continue }
            fill.color = Color.WHITE; fill.alpha = (120 * (1 - t)).toInt()
            canvas.drawCircle(p.x - 20 * t, p.y + 6 * t, 7f + 16f * t, fill)
            canvas.drawCircle(p.x - 6 - 30 * t, p.y - 8 + 4 * t, 5f + 12f * t, fill)
        }
        fill.alpha = 255
        for (c in game.coins) if (!c.taken) coin(canvas, c.x, c.y, now)
        // Before the first flap it hovers, bobbing a little.
        gryphon(canvas, Glide.X, if (game.started) game.y else game.y + sin(now / 400f) * 6f, game.vy, now)
        when {
            !game.alive -> banner(canvas, "Crash!", "${number(game.metres.toInt())} m · ${game.coinsTaken} coins · tap New game")
            !game.started -> hint(canvas, "Tap anywhere to flap · fly through the gaps, grab the coins")
        }
        if (puffs.isNotEmpty() || (game.alive && !game.started)) postInvalidateOnAnimation()
    }

    /** Clouds drift a little while waiting for the first flap, then stay where they got to. */
    private var drifted = 0f
    private fun drift(now: Long): Float {
        if (!game.started && game.alive) drifted = (now % 600_000L) / 1000f * 8f
        return drifted
    }

    /** Draws [shape] (a tile [w] wide) scrolled left by [by], twice so the screen stays covered. */
    private fun tile(canvas: Canvas, shape: Path, by: Float, w: Float, p: Paint) {
        val off = -(by % w)
        canvas.save(); canvas.translate(off, 0f); canvas.drawPath(shape, p)
        canvas.translate(w, 0f); canvas.drawPath(shape, p); canvas.restore()
    }

    private fun clouds(canvas: Canvas, by: Float) {
        for (k in 0 until 5) {
            val span = WORLD_W + 400f
            val x = ((k * 290f - by * (1 + k * 0.12f)) % span + span) % span - 200f
            val y = 70f + (k * 83 % 5) * 46f
            val s = 0.7f + (k % 3) * 0.25f
            // A light top, a shaded flat bottom.
            // A shaded underside first, then the light puffs a little higher.
            fill.color = look.cloudLow; fill.alpha = 230
            canvas.drawCircle(x - 34 * s, y + 8 * s, 24 * s, fill)
            canvas.drawCircle(x + 4 * s, y + 2 * s, 32 * s, fill)
            canvas.drawCircle(x + 44 * s, y + 6 * s, 26 * s, fill)
            r.set(x - 58 * s, y + 4 * s, x + 84 * s, y + 24 * s); canvas.drawRoundRect(r, 10 * s, 10 * s, fill)
            fill.color = look.cloudTop; fill.alpha = 240
            canvas.drawCircle(x - 36 * s, y + 2 * s, 23 * s, fill)
            canvas.drawCircle(x + 2 * s, y - 12 * s, 33 * s, fill)
            canvas.drawCircle(x + 42 * s, y - 2 * s, 25 * s, fill)
            canvas.drawCircle(x + 70 * s, y + 8 * s, 14 * s, fill)
        }
        fill.alpha = 255
    }

    /** A gold flag on a pole where the best flight ended, coming closer as the gryphon nears it. */
    private fun bestFlag(canvas: Canvas) {
        val b = best()
        if (!game.started || b <= 0 || b <= game.metres) return
        val x = Glide.X + (b - game.metres) * 25f
        if (x > WORLD_W + 40) return
        line.strokeWidth = 3f; line.color = Theme.ACCENT; line.alpha = 170
        canvas.drawLine(x, 34f, x, WORLD_H - 10f, line)
        line.alpha = 255
        path.reset(); path.moveTo(x, 40f); path.lineTo(x + 58f, 52f); path.lineTo(x, 66f); path.close()
        fill.color = Theme.GOLD; canvas.drawPath(path, fill)
        text.textSize = 13f; text.color = Theme.BRONZE
        canvas.drawText("Best", x + 22f, 57f, text)
    }

    // ---- Spires: made once per pair, inside the triangles the rules use ----

    private class Spires(p: Glide.Peaks) {
        val upper = Path(); val upperLit = Path(); val upperCracks = Path(); val frost = Path()
        val lower = Path(); val lowerLit = Path(); val lowerCracks = Path(); val snow = Path()
        init {
            var seed = (p.top * 31 + p.bottom * 17).toInt() or 1
            fun rnd(): Float { seed = seed * 1103515245 + 12345; return ((seed ushr 8) and 0xFFFF) / 65535f }
            make(rnd = ::rnd, edgeY = 0f, tipY = p.top, outline = upper, lit = upperLit, cracks = upperCracks, cap = frost)
            make(rnd = ::rnd, edgeY = ActionView.WORLD_H, tipY = p.bottom, outline = lower, lit = lowerLit, cracks = lowerCracks, cap = snow)
        }

        /** A rocky spire from the edge at [edgeY] to its point at [tipY], x around 0. */
        private fun make(rnd: () -> Float, edgeY: Float, tipY: Float, outline: Path, lit: Path, cracks: Path, cap: Path) {
            val half = Glide.PEAK_W / 2
            val n = 6
            val left = FloatArray((n + 1) * 2); val right = FloatArray((n + 1) * 2)
            for (k in 0..n) {
                val t = k / n.toFloat()
                val y = edgeY + (tipY - edgeY) * t
                val w = half * (1 - t)
                val jag = if (k in 1 until n) 3f + rnd() * 7f else 0f
                left[k * 2] = -w + jag; left[k * 2 + 1] = y
                right[k * 2] = w - (if (k in 1 until n) 3f + rnd() * 7f else 0f); right[k * 2 + 1] = y
            }
            outline.moveTo(left[0], left[1])
            for (k in 1..n) outline.lineTo(left[k * 2], left[k * 2 + 1])
            for (k in n - 1 downTo 0) outline.lineTo(right[k * 2], right[k * 2 + 1])
            outline.close()
            // The lit face: the left side up to a ridge a little right of the middle.
            lit.moveTo(left[0], left[1])
            for (k in 1..n) lit.lineTo(left[k * 2], left[k * 2 + 1])
            for (k in n - 1 downTo 0) {
                val t = k / n.toFloat()
                lit.lineTo(half * 0.18f * (1 - t) + (rnd() - 0.5f) * 6f * (1 - t), right[k * 2 + 1])
            }
            lit.close()
            // A few cracks in the shaded side.
            for (k in 0 until 3) {
                val t = 0.2f + 0.2f * k + rnd() * 0.1f
                val y = edgeY + (tipY - edgeY) * t
                val x = half * (1 - t) * (0.35f + rnd() * 0.3f)
                cracks.moveTo(x, y); cracks.lineTo(x + 6f, y + (tipY - edgeY) * 0.06f); cracks.lineTo(x + 2f, y + (tipY - edgeY) * 0.1f)
            }
            // Snow (or frost) on the point, with a ragged edge.
            val capLen = minOf(70f, abs(tipY - edgeY) * 0.4f)
            val dir = if (tipY > edgeY) 1f else -1f
            val capY = tipY - dir * capLen
            val capW = half * capLen / abs(tipY - edgeY)
            cap.moveTo(0f, tipY); cap.lineTo(capW, capY)
            val teeth = 4
            for (k in 1..teeth) {
                val x = capW - 2 * capW * k / teeth
                cap.lineTo(x + capW / teeth, capY + dir * (if (k % 2 == 1) 9f else 2f) * (0.6f + rnd() * 0.8f))
                cap.lineTo(x, capY)
            }
            cap.close()
        }
    }
    private val shapes = IdentityHashMap<Glide.Peaks, Spires>()

    private fun spire(canvas: Canvas, outline: Path, lit: Path, cracks: Path, cap: Path, capColour: Int) {
        fill.color = look.rockShade; canvas.drawPath(outline, fill)
        fill.color = look.rockLit; canvas.drawPath(lit, fill)
        line.strokeWidth = 2f; line.color = blend(look.rockShade, Color.BLACK, 0.35f)
        canvas.drawPath(cracks, line)
        fill.color = capColour; fill.alpha = 235; canvas.drawPath(cap, fill); fill.alpha = 255
        line.strokeWidth = 2.5f; line.color = blend(look.rockShade, Color.BLACK, 0.45f)
        canvas.drawPath(outline, line)
    }

    /** A gold coin turning round, with a glint now and then. */
    private fun coin(canvas: Canvas, x: Float, y: Float, now: Long) {
        val turn = abs(cos(now / 320f + x * 0.013f))
        val w = 3f + 13f * turn
        glow(canvas, x, y, 34f, Theme.GOLD, 120)
        fill.color = Color.rgb(176, 122, 20)
        r.set(x - w, y - 16f, x + w, y + 16f); canvas.drawOval(r, fill)
        fill.color = Theme.GOLD
        r.set(x - w * 0.8f, y - 13f, x + w * 0.8f, y + 13f); canvas.drawOval(r, fill)
        fill.color = Color.rgb(255, 238, 170)
        r.set(x - w * 0.45f, y - 9f, x - w * 0.05f, y + 2f); canvas.drawOval(r, fill)
        val glint = sin(now / 450f + x * 0.05f)
        if (glint > 0.85f) {
            val g = (glint - 0.85f) / 0.15f * 9f
            path.reset()
            path.moveTo(x + 8, y - 10 - g); path.lineTo(x + 9.5f, y - 11.5f); path.lineTo(x + 8 + g, y - 10)
            path.lineTo(x + 9.5f, y - 8.5f); path.lineTo(x + 8, y - 10 + g); path.lineTo(x + 6.5f, y - 8.5f)
            path.lineTo(x + 8 - g, y - 10); path.lineTo(x + 6.5f, y - 11.5f); path.close()
            fill.color = Color.WHITE; canvas.drawPath(path, fill)
        }
    }

    // ---- The gryphon: an eagle's white head and wings on a lion's golden body ----

    private val body = LinearGradient(0f, -16f, 0f, 20f, Color.rgb(236, 198, 128), Color.rgb(168, 116, 58), Shader.TileMode.CLAMP)
    private val wingFill = LinearGradient(0f, -70f, 0f, 0f, Color.rgb(222, 178, 108), Color.rgb(160, 110, 56), Shader.TileMode.CLAMP)

    /** How far the wings are raised: 1 up, 0 straight out, -1 down. A flap beats them down and back. */
    private fun wingLift(now: Long): Float {
        if (!game.alive) return 0.2f
        if (!game.started || flapAt == 0L) return sin(now / 260f) * 0.8f
        val t = (now - flapAt) / 1000f
        return when {
            t < 0.12f -> 1f - t / 0.12f * 1.9f          // the down stroke
            t < 0.38f -> -0.9f + (t - 0.12f) / 0.26f * 1.5f  // back up
            else -> 0.6f + sin(t * 3f) * 0.08f           // gliding, wings up and out
        }
    }

    private fun gryphon(canvas: Canvas, x: Float, y: Float, vy: Float, now: Long) {
        val lift = wingLift(now)
        canvas.save()
        canvas.translate(x, y)
        canvas.rotate(if (game.alive) (vy / 620f * 30f).coerceIn(-22f, 32f) else 50f)
        canvas.scale(1.1f, 1.1f)
        // The far wing, darker, behind the body.
        wing(canvas, -6f, -8f, lift * 0.85f, far = true)
        // The tail with its tuft.
        line.strokeWidth = 6f; line.color = Color.rgb(176, 124, 62)
        path.reset(); path.moveTo(-30f, 2f); path.cubicTo(-48f, -2f, -56f, 12f, -70f, 4f)
        canvas.drawPath(path, line)
        fill.color = Color.rgb(92, 62, 34)
        canvas.save(); canvas.rotate(-20f, -74f, 2f); r.set(-84f, -4f, -66f, 8f); canvas.drawOval(r, fill); canvas.restore()
        // Hind leg, tucked back.
        fill.color = Color.rgb(170, 118, 60)
        r.set(-34f, -2f, -10f, 18f); canvas.drawOval(r, fill)
        line.strokeWidth = 7f; line.color = Color.rgb(160, 110, 56)
        canvas.drawLine(-22f, 12f, -36f, 22f, line)
        fill.color = Color.rgb(120, 82, 40); canvas.drawCircle(-38f, 23f, 4.5f, fill)
        // The body.
        fill.shader = body; r.set(-38f, -16f, 22f, 18f); canvas.drawOval(r, fill); fill.shader = null
        // A saddle cloth in the Forever colours: blue with a gold edge.
        path.reset(); path.moveTo(-20f, -14f); path.lineTo(6f, -15f); path.lineTo(4f, 2f); path.quadTo(-8f, 6f, -20f, 2f); path.close()
        fill.color = Color.rgb(37, 70, 112); canvas.drawPath(path, fill)
        line.strokeWidth = 2f; line.color = Theme.ACCENT; canvas.drawPath(path, line)
        // Front leg: an eagle's, with talons.
        line.strokeWidth = 5f; line.color = Color.rgb(232, 176, 62)
        canvas.drawLine(12f, 10f, 20f, 22f, line)
        line.strokeWidth = 2f; line.color = Color.rgb(60, 44, 30)
        canvas.drawLine(20f, 22f, 26f, 25f, line); canvas.drawLine(20f, 22f, 22f, 28f, line); canvas.drawLine(20f, 22f, 16f, 27f, line)
        // White chest feathers and the head.
        fill.color = Color.rgb(240, 234, 220)
        r.set(6f, -16f, 30f, 12f); canvas.drawOval(r, fill)
        for (k in 0 until 4) canvas.drawCircle(10f + k * 5f, 10f - k * 1f, 4f, fill)
        // Crest feathers sweeping back from the head.
        fill.color = Color.rgb(226, 220, 206)
        path.reset(); path.moveTo(20f, -24f); path.lineTo(4f, -30f); path.lineTo(18f, -18f); path.close(); canvas.drawPath(path, fill)
        path.reset(); path.moveTo(20f, -20f); path.lineTo(2f, -20f); path.lineTo(18f, -14f); path.close(); canvas.drawPath(path, fill)
        fill.color = Color.rgb(250, 247, 238)
        canvas.drawCircle(30f, -16f, 14f, fill)
        fill.color = Color.rgb(222, 214, 198)
        r.set(18f, -10f, 40f, -1f); canvas.drawOval(r, fill)
        // The hooked beak.
        path.reset(); path.moveTo(40f, -21f); path.quadTo(52f, -21f, 56f, -12f); path.quadTo(56f, -6f, 52f, -6f)
        path.quadTo(52f, -10f, 48f, -11f); path.lineTo(40f, -9f); path.close()
        fill.color = Color.rgb(248, 190, 58); canvas.drawPath(path, fill)
        fill.color = Color.rgb(206, 140, 36)
        path.reset(); path.moveTo(40f, -11f); path.lineTo(48f, -11f); path.quadTo(46f, -6f, 41f, -6f); path.close(); canvas.drawPath(path, fill)
        // Eye and brow.
        fill.color = Color.rgb(30, 22, 16); canvas.drawCircle(35f, -19f, 3.2f, fill)
        fill.color = Color.WHITE; canvas.drawCircle(36f, -20f, 1.1f, fill)
        line.strokeWidth = 2.2f; line.color = Color.rgb(120, 104, 84)
        canvas.drawLine(30f, -24f, 40f, -23f, line)
        // The near wing in front.
        wing(canvas, -2f, -10f, lift, far = false)
        canvas.restore()
    }

    /**
     * One wing from its shoulder at [sx], [sy]: drawn raised, then folded over by [lift]
     * (1 up, -1 down, mirrored about the shoulder), so the beat reads from the side.
     */
    private fun wing(canvas: Canvas, sx: Float, sy: Float, lift: Float, far: Boolean) {
        canvas.save()
        canvas.translate(sx, sy)
        val s = if (abs(lift) < 0.12f) (if (lift < 0) -0.12f else 0.12f) else lift
        canvas.rotate(-8f * (1 - lift))
        canvas.scale(if (far) 0.86f else 1f, s)
        val feather = if (far) Color.rgb(96, 68, 40) else Color.rgb(124, 88, 50)
        val tip = if (far) Color.rgb(196, 182, 156) else Color.rgb(246, 236, 214)
        // Long flight feathers fanning from the wrist up to the tip, then shorter ones to the body.
        for (k in 0 until 8) {
            val t = k / 7f
            // Root along the arm (wrist at t=0 high and forward, body at t=1).
            val rx = -4f - 26f * t; val ry = -46f + 40f * t
            val len = 44f - 18f * t
            val ang = -112f - 60f * t
            canvas.save(); canvas.translate(rx, ry); canvas.rotate(ang)
            fill.color = tip; r.set(0f, -6.5f, len, 6.5f); canvas.drawOval(r, fill)
            fill.color = feather; r.set(-2f, -6f, len - 7f, 6f); canvas.drawOval(r, fill)
            canvas.restore()
        }
        // The arm and its small feathers on top (lit from above).
        path.reset(); path.moveTo(4f, 2f); path.quadTo(8f, -36f, -6f, -52f); path.quadTo(-20f, -40f, -34f, -4f); path.quadTo(-14f, 6f, 4f, 2f); path.close()
        if (far) { fill.color = Color.rgb(150, 104, 54); canvas.drawPath(path, fill) }
        else { fill.shader = wingFill; canvas.drawPath(path, fill); fill.shader = null }
        line.strokeWidth = 1.6f; line.color = if (far) Color.rgb(110, 76, 40) else Color.rgb(150, 104, 54)
        for (k in 0 until 3) {
            path.reset(); path.moveTo(-24f + k * 6f, -6f - k * 12f); path.quadTo(-14f + k * 6f, -2f - k * 12f, -6f + k * 4f, -10f - k * 12f)
            canvas.drawPath(path, line)
        }
        canvas.restore()
    }

    private companion object {
        /** Metres for one round of day, sunset, night and dawn. */
        const val CYCLE = 1600f
        const val FAR_W = 1400f

        fun blend(a: Int, b: Int, t: Float): Int {
            val u = 1 - t
            return Color.rgb((Color.red(a) * u + Color.red(b) * t).toInt(), (Color.green(a) * u + Color.green(b) * t).toInt(), (Color.blue(a) * u + Color.blue(b) * t).toInt())
        }

        val DAY = Look(Color.rgb(66, 120, 184), Color.rgb(122, 172, 214), Color.rgb(210, 230, 241), Color.rgb(150, 172, 200), Color.rgb(88, 126, 136), Color.rgb(40, 70, 80),
            Color.rgb(146, 154, 168), Color.rgb(88, 98, 118), Color.WHITE, Color.rgb(206, 220, 236), 0f)
        val SUNSET = Look(Color.rgb(50, 58, 118), Color.rgb(198, 112, 118), Color.rgb(255, 192, 112), Color.rgb(146, 104, 136), Color.rgb(92, 70, 98), Color.rgb(46, 36, 60),
            Color.rgb(184, 132, 118), Color.rgb(94, 72, 92), Color.rgb(255, 214, 168), Color.rgb(204, 124, 132), 0.1f)
        val NIGHT = Look(Color.rgb(8, 18, 36), Color.rgb(18, 40, 70), Color.rgb(38, 68, 100), Color.rgb(40, 60, 92), Color.rgb(26, 44, 66), Color.rgb(12, 24, 38),
            Color.rgb(92, 108, 136), Color.rgb(46, 58, 84), Color.rgb(100, 120, 150), Color.rgb(56, 72, 100), 1f)
        val DAWN = Look(Color.rgb(62, 88, 154), Color.rgb(172, 142, 182), Color.rgb(255, 204, 172), Color.rgb(140, 130, 172), Color.rgb(80, 90, 122), Color.rgb(40, 50, 72),
            Color.rgb(166, 144, 154), Color.rgb(86, 86, 110), Color.rgb(255, 228, 222), Color.rgb(192, 160, 192), 0.15f)
        /** When each look is reached, as a share of [CYCLE]. */
        val STOPS = listOf(0f to DAY, 0.2f to DAY, 0.32f to SUNSET, 0.42f to SUNSET, 0.55f to NIGHT, 0.82f to NIGHT, 0.92f to DAWN, 1f to DAY)
    }
}
