package thor.companion.games

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import thor.companion.Theme
import thor.companion.games.LastLight.Boost
import thor.companion.games.LastLight.Kind
import thor.companion.games.LastLight.Upgrade
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Last Light's field (pictures: mockups/survivors-v2, Jesse 2026-10-27). The hero stays in the
 * middle with their lantern; a moonlit meadow scrolls under them. Put a thumb anywhere and drag
 * to walk. Before each night a small shop spends the gold from earlier nights ([prefs]
 * "light_gold" and "light_shop"); a full gem bar shows three cards to pick from.
 *
 * Everything that has shading (the hero, foes, gems, the meadow) is painted once into small
 * pictures at the screen's scale and then only copied each frame, so 200 foes stay smooth.
 */
class LastLightView(context: Context, private val prefs: SharedPreferences) : ActionView(context) {
    var game = LastLight(shopLevels())
        private set
    /** This night's gold was added already. */
    private var paid = false
    private var lastSecond = -1
    private var bosses = 0
    private var swarms = 0
    private var level = 1

    // The thumb stick: where the thumb came down, and where it is.
    private var stick = false
    private var stickX = 0f; private var stickY = 0f
    private var thumbX = 0f; private var thumbY = 0f
    /** The card or shop row under the thumb (-1 none). */
    private var pressed = -1

    /** Seconds of play, for everything that moves on its own (stops while paused). */
    private var played = 0f

    private class Number(val x: Float, val y: Float, val words: String, val big: Boolean, val at: Float)
    private class Wisp(val x: Float, val y: Float, val kind: Kind, val at: Float)
    private class Wave(val x: Float, val y: Float, val radius: Float, val frost: Boolean, val at: Float)
    private val numbers = ArrayList<Number>()
    private val wisps = ArrayList<Wisp>()
    private val waves = ArrayList<Wave>()
    private var news: String? = null
    private var newsAt = -10f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG)
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Theme.body(context) }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Theme.heading(context) }
    private val path = Path()
    private val box = RectF()

    /** Light and dark over the meadow (fixed: the hero is always in the middle). */
    private val warm = RadialGradient(HX, HY, 300f, intArrayOf(Color.argb(78, 255, 207, 112), Color.argb(24, 255, 176, 80), 0), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private val dark = RadialGradient(HX, HY, 520f, intArrayOf(0, 0, Color.argb(200, 2, 7, 14)), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
    private val hurtVeil = RadialGradient(HX, HY, 560f, intArrayOf(0, Color.argb(150, 200, 30, 30)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
    private val dawn = LinearGradient(0f, 0f, 0f, LastLight.H, intArrayOf(Color.argb(110, 255, 190, 120), Color.argb(50, 255, 150, 90)), null, Shader.TileMode.CLAMP)
    private val xpBar = LinearGradient(0f, 2f, 0f, 16f, intArrayOf(Color.rgb(191, 243, 255), Theme.TEAL, Color.rgb(21, 106, 133)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private fun plaque(t: Float, b: Float) = LinearGradient(0f, t, 0f, b, intArrayOf(Color.argb(235, 31, 74, 112), Color.argb(235, 11, 29, 46)), null, Shader.TileMode.CLAMP)

    // ---- Gold and the shop ----

    private fun shopLevels(): IntArray {
        val kept = (prefs.getString("light_shop", "") ?: "").split(',').map { it.toIntOrNull() ?: 0 }
        return IntArray(Boost.entries.size) { kept.getOrElse(it) { 0 }.coerceIn(0, LastLight.MAX_LEVEL) }
    }
    private var gold: Int
        get() = prefs.getInt("light_gold", 0)
        set(v) { prefs.edit().putInt("light_gold", v).apply() }
    private val best get() = prefs.getInt("best_light", 0)

    /** A fresh night, back at the shop. */
    fun newNight() {
        game = LastLight(shopLevels())
        paid = false; lastSecond = -1; bosses = 0; swarms = 0; level = 1
        stick = false; pressed = -1
        numbers.clear(); wisps.clear(); waves.clear(); news = null
        onChange(); invalidate()
    }

    /**
     * For the screenshot test: a night played by a simple helper for [seconds] (walking away from
     * crowds, taking the first card), stopping at a level up when [atCards].
     */
    internal fun preview(seconds: Float, atCards: Boolean) {
        game = LastLight(shopLevels(), kotlin.random.Random(7))
        game.start()
        while (game.time < seconds && !game.over) {
            var sx = 0f; var sy = 0f
            for (f in game.foes) { val dx = game.x - f.x; val dy = game.y - f.y; val d2 = dx * dx + dy * dy + 1; if (d2 < 250f * 250f) { sx += dx / d2; sy += dy / d2 } }
            val l = hypot(sx, sy)
            if (l > 0) { game.steerX = sx / l; game.steerY = sy / l } else { game.steerX = 0.6f; game.steerY = 0.2f }
            if (game.choices != null) { if (atCards && game.time > seconds - 30f) break; game.choose(0) }
            game.step(LastLight.STEP)
            played += LastLight.STEP
            if (game.time < seconds - 0.4f) { game.hits.clear(); game.deaths.clear(); game.rings.clear() }
        }
        paid = true; bosses = game.bossesSent; swarms = game.swarmsSent
    }

    private fun buy(b: Boost) {
        val lv = game.shop[b.ordinal]
        if (game.started || lv >= LastLight.MAX_LEVEL || gold < b.cost(lv)) return
        gold -= b.cost(lv)
        val now = shopLevels().also { it[b.ordinal] = lv + 1 }
        prefs.edit().putString("light_shop", now.joinToString(",")).apply()
        game = LastLight(now)
        onChange()
    }

    // ---- ActionView ----

    override fun line(): String = when {
        !game.started -> "$gold gold" + if (best > 0) " · best ${clock(best * 1000L)}" else ""
        game.over -> if (game.won) "Dawn! · ${number(game.kills)} foes" else "${clock((game.time * 1000).toLong())} · best ${clock(maxOf(best, game.time.toInt()) * 1000L)}"
        else -> "${clock(((LastLight.NIGHT - game.time) * 1000).toLong())} left" + if (best > 0) " · best ${clock(best * 1000L)}" else ""
    }

    override fun moving() = game.started && !game.over && game.choices == null

    override fun step(dt: Float) {
        game.step(dt)
        played += dt
        if (game.choices != null) { stick = false; game.steerX = 0f; game.steerY = 0f }
        if (game.bossesSent != bosses) { bosses = game.bossesSent; announce("The Necromancer rises!") }
        if (game.swarmsSent != swarms) { swarms = game.swarmsSent; announce("They come from all sides!") }
        if (game.level != level) level = game.level
        if (game.over && !paid) { paid = true; gold += game.gold; onChange() }
        val s = game.time.toInt()
        if (s != lastSecond) { lastSecond = s; onChange() }
    }

    private fun announce(w: String) { news = w; newsAt = played }

    override fun touch(action: Int, x: Float, y: Float) {
        when {
            !game.started -> shopTouch(action, x, y)
            game.over -> if (action == MotionEvent.ACTION_UP) newNight()
            game.choices != null -> {
                val at = cardAt(x, y)
                when (action) {
                    MotionEvent.ACTION_DOWN -> pressed = at
                    MotionEvent.ACTION_MOVE -> if (at != pressed) pressed = -1
                    MotionEvent.ACTION_UP -> { if (at >= 0 && at == pressed) { game.choose(at); onChange() }; pressed = -1 }
                    MotionEvent.ACTION_CANCEL -> pressed = -1
                }
            }
            else -> when (action) {
                MotionEvent.ACTION_DOWN -> { stick = true; stickX = x; stickY = y; thumbX = x; thumbY = y }
                MotionEvent.ACTION_MOVE -> if (stick) {
                    var dx = x - stickX; var dy = y - stickY
                    val d = hypot(dx, dy)
                    // The ring follows a thumb that goes past its edge.
                    if (d > STICK) { stickX += dx / d * (d - STICK); stickY += dy / d * (d - STICK); dx = x - stickX; dy = y - stickY }
                    thumbX = x; thumbY = y
                    val dd = hypot(dx, dy)
                    if (dd < 6f) { game.steerX = 0f; game.steerY = 0f } else { game.steerX = dx / STICK; game.steerY = dy / STICK }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { stick = false; game.steerX = 0f; game.steerY = 0f }
            }
        }
    }

    private fun shopTouch(action: Int, x: Float, y: Float) {
        val at = when {
            x in 380f..620f && y in START_Y..START_Y + 58f -> 99
            x in 790f..950f -> Boost.entries.indices.firstOrNull { y in rowY(it)..rowY(it) + 74f } ?: -1
            else -> -1
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> pressed = at
            MotionEvent.ACTION_MOVE -> if (at != pressed) pressed = -1
            MotionEvent.ACTION_UP -> {
                if (at == pressed && at == 99) { game.start(); onChange() }
                else if (at == pressed && at >= 0) buy(Boost.entries[at])
                pressed = -1
            }
            MotionEvent.ACTION_CANCEL -> pressed = -1
        }
    }

    // ---- Drawing ----

    override fun drawWorld(canvas: Canvas) {
        if (spritesFor != sc) { sprites.values.forEach { it.recycle() }; sprites.clear(); spritesFor = sc }
        val camX = game.x - HX; val camY = game.y - HY
        meadow(canvas, camX, camY)
        fill.shader = warm; canvas.drawRect(0f, 0f, W, H, fill); fill.shader = null
        takeEvents()

        // Gems lying around.
        for (g in game.gems) {
            val gx = g.x - camX; val gy = g.y - camY
            if (gx < -20 || gx > W + 20 || gy < -20 || gy > H + 20) continue
            val k = when { g.value >= 25 -> 2; g.value >= 3 -> 1; else -> 0 }
            glow(canvas, gx, gy, 15f, GEM_GLOW[k], 120)
            sprite(canvas, "gem$k", gx, gy + sin(played * 3 + g.x) * 1.5f, 20f, 24f) { gem(it, k) }
        }
        // The holy ring lies on the ground.
        if (game.ringRadius > 0f) holyRing(canvas, game.ringRadius)

        // Foes and the hero, back to front.
        val order = game.foes.filter { it.x - camX in -60f..W + 60f && it.y - camY in -70f..H + 70f }.sortedBy { it.y }
        var heroDrawn = false
        for (f in order) {
            if (!heroDrawn && f.y > game.y) { hero(canvas); heroDrawn = true }
            foe(canvas, f, f.x - camX, f.y - camY)
        }
        if (!heroDrawn) hero(canvas)
        for (f in order) if (f.kind == Kind.NECROMANCER) bossBar(canvas, f, f.x - camX, f.y - camY)

        // Weapons.
        for (m in game.bombs) bomb(canvas, m, camX, camY)
        blades(canvas)
        for (b in game.bolts) bolt(canvas, b.x - camX, b.y - camY, b.vx, b.vy)
        effects(canvas, camX, camY)
        fireflies(canvas, camX, camY)

        // The dark beyond the lantern, red when hurt, warm at dawn.
        fill.shader = dark; canvas.drawRect(0f, 0f, W, H, fill)
        if (game.hurt > 0 && !game.over) { fill.shader = hurtVeil; fill.alpha = (255 * (game.hurt / 0.25f)).toInt().coerceIn(0, 255); canvas.drawRect(0f, 0f, W, H, fill); fill.alpha = 255 }
        if (game.won) { fill.shader = dawn; canvas.drawRect(0f, 0f, W, H, fill) }
        fill.shader = null
        if (stick && moving()) thumbStick(canvas)
        if (game.started) hud(canvas)
        val n = news
        if (n != null && played - newsAt < 2.6f && moving()) {
            val a = ((2.6f - (played - newsAt)) / 0.6f).coerceIn(0f, 1f)
            outlined(canvas, n, W / 2, 170f, 30f, Theme.GOLD, title, (255 * a).toInt())
        }
        when {
            !game.started -> shop(canvas)
            game.over -> ending(canvas)
            game.choices != null -> cards(canvas)
            game.time < 4f -> hint(canvas, "Put your thumb anywhere and drag to walk. You fight on your own.")
        }
        if (moving() && (numbers.isNotEmpty() || wisps.isNotEmpty() || waves.isNotEmpty())) postInvalidateOnAnimation()
    }

    private fun takeEvents() {
        for (h in game.hits) {
            numbers += Number(h.x + (Math.random() * 10 - 5).toFloat(), h.y, h.damage.toString(), h.big, played)
        }
        game.hits.clear()
        while (numbers.size > 28) numbers.removeAt(0)
        for (d in game.deaths) wisps += Wisp(d.x, d.y, d.kind, played)
        game.deaths.clear()
        while (wisps.size > 40) wisps.removeAt(0)
        for (r in game.rings) waves += Wave(r.x, r.y, r.radius, r.frost, played)
        game.rings.clear()
    }

    // ---- The meadow: one big seamless picture, tiled under the hero ----

    private fun meadow(canvas: Canvas, camX: Float, camY: Float) {
        val tile = sprites.getOrPut("meadow") { paintMeadow() }
        val x0 = floor(camX / TILE) * TILE; val y0 = floor(camY / TILE) * TILE
        var ty = y0
        while (ty < camY + H) {
            var tx = x0
            while (tx < camX + W) {
                box.set(tx - camX, ty - camY, tx - camX + TILE, ty - camY + TILE)
                canvas.drawBitmap(tile, null, box, bmp)
                tx += TILE
            }
            ty += TILE
        }
    }

    private fun paintMeadow(): Bitmap {
        val px = (TILE * sc).toInt().coerceAtLeast(8)
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(px / TILE, px / TILE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(Color.rgb(27, 51, 38))
        val rnd = java.util.Random(11)
        fun r() = rnd.nextFloat()
        /** Draws [what] at x, y and again across each edge it touches, so the tile has no seams. */
        fun wrap(x: Float, y: Float, reach: Float, what: (Float, Float) -> Unit) {
            for (dx in -1..1) for (dy in -1..1) {
                val ax = x + dx * TILE; val ay = y + dy * TILE
                if (ax + reach < 0 || ax - reach > TILE || ay + reach < 0 || ay - reach > TILE) continue
                what(ax, ay)
            }
        }
        // Soft lighter and darker patches.
        p.maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
        repeat(26) {
            val x = r() * TILE; val y = r() * TILE; val w = 40 + r() * 70; val h = 18 + r() * 30
            p.color = if (it % 3 == 0) Color.argb(60, 10, 22, 22) else Color.argb(56, 70, 120, 70)
            wrap(x, y, w + 30) { ax, ay -> c.drawOval(ax - w, ay - h, ax + w, ay + h, p) }
        }
        // A winding dirt path (it repeats with the tile).
        path.reset()
        for (i in -2..52) {
            val x = i * TILE / 50
            val y = TILE * 0.62f + 70 * sin(x / TILE * 6.2832f) + 30 * sin(x / TILE * 12.566f + 1f)
            if (i == -2) path.moveTo(x, y) else path.lineTo(x, y)
        }
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND
        p.maskFilter = BlurMaskFilter(8f, BlurMaskFilter.Blur.NORMAL)
        p.color = Color.argb(150, 42, 34, 22); p.strokeWidth = 88f; c.drawPath(path, p)
        p.maskFilter = null
        p.color = Color.argb(200, 86, 72, 50); p.strokeWidth = 62f; c.drawPath(path, p)
        p.color = Color.argb(90, 110, 94, 66); p.strokeWidth = 30f; c.drawPath(path, p)
        p.style = Paint.Style.FILL
        repeat(70) {
            val x = r() * TILE
            val y = TILE * 0.62f + 70 * sin(x / TILE * 6.2832f) + 30 * sin(x / TILE * 12.566f + 1f) + (r() - 0.5f) * 44
            p.color = Color.rgb(122, 106, 80); p.alpha = 170
            val w = 2 + r() * 3; val h = 1.5f + r() * 2
            wrap(x, y, 8f) { ax, ay -> c.drawOval(ax - w, ay - h, ax + w, ay + h, p) }
        }
        p.alpha = 255
        // Stones with a light top.
        repeat(16) {
            val x = r() * TILE; val y = r() * TILE; val rx = 8 + r() * 12; val ry = 5 + r() * 6
            wrap(x, y, rx + 4) { ax, ay ->
                p.color = Color.argb(100, 0, 0, 0); c.drawOval(ax - rx, ay + 3 - ry * 0.6f, ax + rx, ay + 3 + ry * 0.6f, p)
                p.color = Color.rgb(86, 100, 108); c.drawOval(ax - rx, ay - ry, ax + rx, ay + ry, p)
                p.color = Color.argb(170, 138, 154, 162); c.drawOval(ax - rx * 0.75f, ay - ry * 0.7f, ax + rx * 0.15f, ay - ry * 0.05f, p)
            }
        }
        // Grass tufts with light tips.
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f
        repeat(260) {
            val x = r() * TILE; val y = r() * TILE
            val col = if (r() < 0.5f) Color.rgb(63, 112, 72) else Color.rgb(47, 90, 58)
            wrap(x, y, 20f) { ax, ay ->
                p.color = col
                path.reset()
                path.moveTo(ax, ay); path.quadTo(ax - 3, ay - 10, ax - 6, ay - 13)
                path.moveTo(ax, ay); path.quadTo(ax, ay - 12, ax + 1, ay - 16)
                path.moveTo(ax, ay); path.quadTo(ax + 3, ay - 9, ax + 7, ay - 12)
                c.drawPath(path, p)
            }
        }
        p.style = Paint.Style.FILL
        // Little flowers.
        val petals = intArrayOf(Color.rgb(200, 176, 255), Color.rgb(255, 243, 196), Color.rgb(159, 216, 255))
        repeat(60) { i ->
            val x = r() * TILE; val y = r() * TILE
            wrap(x, y, 8f) { ax, ay ->
                p.color = petals[i % 3]; p.alpha = 220
                for (k in 0 until 5) { val a = k * 1.2566f; c.drawCircle(ax + cos(a) * 3f, ay + sin(a) * 3f, 2f, p) }
                p.alpha = 255; p.color = Theme.GOLD; c.drawCircle(ax, ay, 1.6f, p)
            }
        }
        // Old graves, dead trees and broken fences.
        repeat(7) {
            val x = r() * TILE; val y = r() * TILE; val tilt = (r() - 0.5f) * 14
            wrap(x, y, 30f) { ax, ay -> grave(c, p, ax, ay, tilt); if (r() < 0.5f) grave(c, p, ax + 42, ay + 12, -tilt) }
        }
        repeat(4) {
            val x = r() * TILE; val y = r() * TILE
            wrap(x, y, 70f) { ax, ay -> deadTree(c, p, ax, ay) }
        }
        repeat(3) {
            val x = r() * TILE; val y = r() * TILE
            wrap(x, y, 100f) { ax, ay -> fence(c, p, ax, ay) }
        }
        // Mist.
        p.maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
        repeat(5) {
            val x = r() * TILE; val y = r() * TILE; val w = 200 + r() * 160
            p.color = Color.argb(20, 207, 230, 255)
            wrap(x, y, w + 40) { ax, ay -> c.drawOval(ax - w, ay - 26, ax + w, ay + 26, p) }
        }
        p.maskFilter = null
        return b
    }

    private fun grave(c: Canvas, p: Paint, x: Float, y: Float, tilt: Float) {
        c.save(); c.rotate(tilt, x, y)
        p.style = Paint.Style.FILL
        p.color = Color.argb(110, 0, 0, 0); c.drawOval(x - 20, y + 17, x + 20, y + 27, p)
        path.reset(); path.moveTo(x - 15, y + 21); path.lineTo(x - 15, y - 5); path.quadTo(x, y - 26, x + 15, y - 5); path.lineTo(x + 15, y + 21); path.close()
        p.color = Color.rgb(92, 108, 120); c.drawPath(path, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; p.color = Color.rgb(29, 42, 48); c.drawPath(path, p)
        p.style = Paint.Style.FILL
        path.reset(); path.moveTo(x - 15, y - 5); path.quadTo(x, y - 26, x + 8, y - 18); path.quadTo(x - 6, y - 18, x - 12, y + 4); path.close()
        p.color = Color.argb(110, 160, 176, 188); c.drawPath(path, p)
        p.color = Color.rgb(42, 54, 64); c.drawRect(x - 6, y - 1, x + 6, y + 1.5f, p); c.drawRect(x - 1.2f, y - 6, x + 1.2f, y + 10, p)
        p.color = Color.rgb(74, 138, 74)
        path.reset(); path.moveTo(x - 15, y + 21); path.quadTo(x - 10, y + 12, x - 4, y + 21); path.close(); c.drawPath(path, p)
        path.reset(); path.moveTo(x + 6, y + 21); path.quadTo(x + 12, y + 14, x + 15, y + 21); path.close(); c.drawPath(path, p)
        c.restore()
    }

    private fun deadTree(c: Canvas, p: Paint, x: Float, y: Float) {
        p.style = Paint.Style.FILL; p.color = Color.argb(110, 0, 0, 0); c.drawOval(x - 30, y + 54, x + 50, y + 70, p)
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.ROUND; p.color = Color.rgb(42, 30, 20)
        path.reset()
        path.moveTo(x, y + 60); path.quadTo(x + 4, y + 20, x - 6, y - 10)
        path.moveTo(x + 2, y + 30); path.quadTo(x + 20, y + 10, x + 36, y + 6)
        path.moveTo(x - 2, y + 6); path.quadTo(x - 20, y - 6, x - 30, y - 24)
        path.moveTo(x + 28, y + 8); path.quadTo(x + 34, y - 6, x + 46, y - 10)
        p.strokeWidth = 7f; c.drawPath(path, p)
        p.strokeWidth = 2f; p.color = Color.rgb(90, 68, 48)
        path.reset(); path.moveTo(x - 1, y + 58); path.quadTo(x + 3, y + 20, x - 6, y - 8); c.drawPath(path, p)
        p.style = Paint.Style.FILL
    }

    private fun fence(c: Canvas, p: Paint, x: Float, y: Float) {
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT; p.color = Color.rgb(58, 44, 28)
        p.strokeWidth = 6f; c.drawLine(x, y, x + 166, y - 20, p)
        p.strokeWidth = 5f; c.drawLine(x, y + 16, x + 166, y - 4, p)
        p.style = Paint.Style.FILL
        for ((i, px) in listOf(6f, 46f, 86f, 126f, 162f).withIndex()) {
            val top = y - 10 - px / 8.3f
            p.color = Color.rgb(107, 90, 62); c.drawRoundRect(px + x, top, px + x + 8, top + if (i == 2) 28 else 38, 2f, 2f, p)
        }
    }

    // ---- Small painted pictures, made once per size ----

    private val sprites = HashMap<String, Bitmap>()
    private var spritesFor = 0f

    /** Copies the picture [key] ([w] x [h] world units, centred on [x], [y]); [paint] draws it the first time, centred on 0, 0. */
    private fun sprite(canvas: Canvas, key: String, x: Float, y: Float, w: Float, h: Float, flip: Boolean = false, alpha: Int = 255, paint: (Canvas) -> Unit) {
        val b = sprites.getOrPut(key) {
            val pw = (w * sc).toInt().coerceAtLeast(2); val ph = (h * sc).toInt().coerceAtLeast(2)
            Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888).also { val c = Canvas(it); c.scale(pw / w, ph / h); c.translate(w / 2, h / 2); paint(c) }
        }
        box.set(x - w / 2, y - h / 2, x + w / 2, y + h / 2)
        bmp.alpha = alpha
        if (flip) { canvas.save(); canvas.scale(-1f, 1f, x, y); canvas.drawBitmap(b, null, box, bmp); canvas.restore() }
        else canvas.drawBitmap(b, null, box, bmp)
        bmp.alpha = 255
    }

    private val sp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sPath = Path()

    private fun radial(cx: Float, cy: Float, r: Float, vararg colours: Int) = RadialGradient(cx, cy, r, colours, null, Shader.TileMode.CLAMP)

    private fun outline(c: Canvas, p: Path, colour: Int, width: Float) {
        sp.shader = null; sp.style = Paint.Style.STROKE; sp.strokeWidth = width; sp.color = colour; sp.strokeJoin = Paint.Join.ROUND
        c.drawPath(p, sp); sp.style = Paint.Style.FILL
    }


    private fun hero(canvas: Canvas) {
        val walking = moving() && hypot(game.steerX, game.steerY) > 0.05f
        val bob = if (walking) sin(played * 14f) * 1.6f else sin(played * 2f) * 0.6f
        val flip = game.facing < 0
        val lx = HX + 21 * HERO_K * game.facing; val ly = HY - 34 * HERO_K + bob
        val flicker = 150 + (sin(played * 9f) * 25 + sin(played * 23f) * 15).toInt()
        glow(canvas, lx, ly, 56f, Theme.GOLD, flicker)
        if (game.hurt > 0) glow(canvas, HX, HY, 40f, Theme.BAD, 160)
        sprite(canvas, "hero", HX, HY + bob, 60f * HERO_K, 100f * HERO_K, flip) { c -> c.scale(HERO_K, HERO_K); LastLightArt.hero(c) }
        // Health under the hero.
        val f = (game.hp / game.maxHp).coerceIn(0f, 1f)
        fill.color = Color.rgb(6, 18, 30); canvas.drawRoundRect(HX - 32, HY + 38, HX + 32, HY + 47, 4.5f, 4.5f, fill)
        fill.color = if (f > 0.35f) Theme.GOOD else Theme.BAD
        canvas.drawRoundRect(HX - 31, HY + 39, HX - 31 + 62 * f, HY + 46, 3.5f, 3.5f, fill)
        fill.color = Color.argb(100, 255, 255, 255); canvas.drawRoundRect(HX - 31, HY + 39, HX - 31 + 62 * f, HY + 41.5f, 1f, 1f, fill)
    }

    private fun foe(canvas: Canvas, f: LastLight.Foe, x: Float, y: Float) {
        val flash = f.flash > 0
        if (f.slow > 0) glow(canvas, x, y, f.kind.radius * 2f, Theme.TEAL, 110)
        val bob = sin(played * 4 + f.phase) * 1.5f
        when (f.kind) {
            Kind.GHOUL -> sprite(canvas, "ghoul$flash", x, y + bob * 0.5f, 44f, 52f, flip = game.x < f.x) { LastLightArt.ghoul(it, flash) }
            Kind.BANSHEE -> sprite(canvas, "banshee$flash", x, y + bob * 2f, 62f, 80f, flip = game.x < f.x) { LastLightArt.banshee(it, flash) }
            Kind.SKELETON -> sprite(canvas, "skeleton$flash", x, y, 56f, 56f, flip = game.x < f.x) { LastLightArt.skeleton(it, flash) }
            Kind.BAT -> {
                val up = sin(played * 16 + f.phase) > 0
                sprite(canvas, "bat$up$flash", x, y, 70f, 64f) { LastLightArt.bat(it, up, flash) }
            }
            Kind.NECROMANCER -> sprite(canvas, "necromancer$flash", x, y + bob * 2f, 160f, 180f, flip = game.x < f.x) { LastLightArt.necromancer(it, flash) }
        }
    }

    private fun bossBar(canvas: Canvas, f: LastLight.Foe, x: Float, y: Float) {
        val k = (f.hp / f.maxHp).coerceIn(0f, 1f)
        fill.color = Color.rgb(6, 18, 30); canvas.drawRoundRect(x - 42, y - 92, x + 42, y - 82, 5f, 5f, fill)
        fill.color = Theme.BAD; canvas.drawRoundRect(x - 40, y - 90, x - 40 + 80 * k, y - 84, 3f, 3f, fill)
        stroke.color = Theme.ACCENT; stroke.strokeWidth = 1.2f; canvas.drawRoundRect(x - 42, y - 92, x + 42, y - 82, 5f, 5f, stroke)
    }

    private fun gem(c: Canvas, k: Int) {
        val (light, mid, deep) = GEMS[k]
        val p = sPath
        p.reset(); p.moveTo(0f, -9f); p.lineTo(7f, -1f); p.lineTo(0f, 10f); p.lineTo(-7f, -1f); p.close()
        sp.shader = radial(-2.5f, -4f, 14f, light, mid, deep); c.drawPath(p, sp); sp.shader = null
        outline(c, p, Color.rgb(11, 34, 54), 1.2f)
        p.reset(); p.moveTo(0f, -9f); p.lineTo(3f, -1f); p.lineTo(-3f, -1f); p.close()
        sp.color = Color.argb(190, 255, 255, 255); c.drawPath(p, sp)
    }

    private fun holyRing(canvas: Canvas, r: Float) {
        stroke.strokeWidth = 12f; stroke.color = Color.argb(30, 255, 233, 168); canvas.drawCircle(HX, HY, r, stroke)
        stroke.strokeWidth = 1.6f; stroke.color = Color.argb(140, 255, 243, 196); canvas.drawCircle(HX, HY, r, stroke)
        fill.color = Color.argb(14, 255, 233, 168); canvas.drawCircle(HX, HY, r, fill)
        fill.color = Color.argb(190, 255, 233, 168)
        for (i in 0 until 12) {
            val a = i * 0.5236f + played * 0.6f
            canvas.save(); canvas.translate(HX + cos(a) * r, HY + sin(a) * r); canvas.rotate(Math.toDegrees(a.toDouble()).toFloat())
            path.reset(); path.moveTo(-4f, -4f); path.lineTo(4f, -4f); path.lineTo(0f, 5f); path.close(); canvas.drawPath(path, fill)
            canvas.restore()
        }
    }

    private fun blades(canvas: Canvas) {
        val n = game.bladeCount
        if (n == 0) return
        val r = game.bladeRadius
        for (k in 0 until n) {
            val a = game.bladeAngle + k * 6.2832f / n
            val bx = HX + cos(a) * r; val by = HY + sin(a) * r
            // A fading arc behind each blade.
            box.set(HX - r, HY - r, HX + r, HY + r)
            stroke.strokeWidth = 10f; stroke.color = Color.argb(40, 255, 210, 90)
            canvas.drawArc(box, Math.toDegrees(a.toDouble()).toFloat() - 50f, 50f, false, stroke)
            stroke.strokeWidth = 2f; stroke.color = Color.argb(110, 255, 243, 196)
            canvas.drawArc(box, Math.toDegrees(a.toDouble()).toFloat() - 40f, 40f, false, stroke)
            glow(canvas, bx, by, 26f, Theme.GOLD, 150)
            canvas.save(); canvas.rotate(played * 720f % 360f, bx, by)
            sprite(canvas, "blade", bx, by, 44f, 44f) { c ->
                for (q in 0 until 4) {
                    c.save(); c.rotate(q * 90f)
                    sPath.reset(); sPath.moveTo(0f, 0f); sPath.quadTo(6f, -8f, 3f, -19f); sPath.quadTo(-2f, -10f, -5f, -3f); sPath.close()
                    sp.shader = LinearGradient(-5f, -19f, 6f, 0f, intArrayOf(Color.rgb(255, 246, 208), Theme.GOLD, Color.rgb(154, 106, 26)), null, Shader.TileMode.CLAMP)
                    c.drawPath(sPath, sp); sp.shader = null
                    outline(c, sPath, Color.rgb(107, 74, 26), 1.2f)
                    c.restore()
                }
                sp.color = Color.rgb(107, 74, 26); c.drawCircle(0f, 0f, 4.5f, sp)
                sp.color = Color.rgb(255, 243, 196); c.drawCircle(0f, 0f, 2f, sp)
            }
            canvas.restore()
        }
    }

    private fun bolt(canvas: Canvas, x: Float, y: Float, vx: Float, vy: Float) {
        if (x < -40 || x > W + 40 || y < -40 || y > H + 40) return
        val sp0 = hypot(vx, vy).coerceAtLeast(1f)
        val ux = vx / sp0; val uy = vy / sp0
        stroke.strokeWidth = 9f; stroke.color = Color.argb(60, 52, 186, 215); canvas.drawLine(x - ux * 60, y - uy * 60, x, y, stroke)
        stroke.strokeWidth = 3f; stroke.color = Color.argb(150, 191, 243, 255); canvas.drawLine(x - ux * 44, y - uy * 44, x, y, stroke)
        glow(canvas, x, y, 24f, Theme.TEAL, 230)
        orb(canvas, x, y, 7.5f, Color.WHITE, Color.rgb(232, 253, 255), Theme.TEAL)
    }

    private fun bomb(canvas: Canvas, m: LastLight.Bomb, camX: Float, camY: Float) {
        val t = m.t.coerceIn(0f, 1f)
        val x = m.fromX + (m.x - m.fromX) * t - camX
        val y = m.fromY + (m.y - m.fromY) * t - camY - sin(t * Math.PI.toFloat()) * 90f
        glow(canvas, x, y, 22f, Color.rgb(255, 138, 42), 200)
        orb(canvas, x, y, 8f, Color.rgb(255, 220, 160), Color.rgb(200, 80, 30), Color.rgb(80, 20, 10))
        fill.color = Theme.GOLD; canvas.drawCircle(x + 5, y - 8, 2.5f + sin(played * 30) * 1f, fill)
        // Where it will land.
        stroke.strokeWidth = 2f; stroke.color = Color.argb((60 + 120 * t).toInt(), 255, 138, 42)
        canvas.drawCircle(m.x - camX, m.y - camY, m.radius * (0.4f + 0.6f * t), stroke)
    }

    private fun effects(canvas: Canvas, camX: Float, camY: Float) {
        // Frost and fire waves.
        val wi = waves.iterator()
        while (wi.hasNext()) {
            val w = wi.next(); val t = (played - w.at) / 0.45f
            if (t >= 1f) { wi.remove(); continue }
            val x = w.x - camX; val y = w.y - camY; val r = w.radius * (0.3f + 0.7f * t)
            val a = (255 * (1 - t)).toInt()
            if (w.frost) {
                fill.color = Color.argb(a / 5, 191, 243, 255); canvas.drawCircle(x, y, r, fill)
                stroke.strokeWidth = 6f; stroke.color = Theme.withAlpha(Color.rgb(191, 243, 255), a); canvas.drawCircle(x, y, r, stroke)
                stroke.strokeWidth = 2f
                for (k in 0 until 16) { val ang = k * 0.3927f; canvas.drawLine(x + cos(ang) * (r - 10), y + sin(ang) * (r - 10), x + cos(ang) * (r + 6), y + sin(ang) * (r + 6), stroke) }
            } else {
                glow(canvas, x, y, 90f, Color.rgb(255, 138, 42), a)
                fill.color = Color.argb(a / 3, 255, 120, 40); canvas.drawCircle(x, y, r, fill)
                stroke.strokeWidth = 4f; stroke.color = Theme.withAlpha(Color.rgb(255, 210, 138), a); canvas.drawCircle(x, y, r, stroke)
            }
        }
        // Souls rising from beaten foes.
        val di = wisps.iterator()
        while (di.hasNext()) {
            val d = di.next(); val t = (played - d.at) / 0.7f
            if (t >= 1f) { di.remove(); continue }
            val x = d.x - camX; val y = d.y - camY - 30 * t
            val colour = when (d.kind) { Kind.BAT -> Color.rgb(179, 132, 224); Kind.SKELETON -> Color.rgb(255, 250, 240); Kind.BANSHEE -> Color.rgb(168, 200, 255); Kind.NECROMANCER -> Color.rgb(255, 170, 70); else -> Color.rgb(200, 255, 170) }
            glow(canvas, x, y, if (d.kind == Kind.NECROMANCER) 80f else 22f, colour, (200 * (1 - t)).toInt())
            stroke.strokeWidth = 3f; stroke.color = Theme.withAlpha(Color.rgb(232, 243, 255), (150 * (1 - t)).toInt())
            path.reset(); path.moveTo(x, y); path.quadTo(x - 7, y - 12, x, y - 22); path.quadTo(x + 6, y - 30, x, y - 40)
            canvas.drawPath(path, stroke)
        }
        // Damage numbers.
        val ni = numbers.iterator()
        while (ni.hasNext()) {
            val n = ni.next(); val t = (played - n.at) / 0.6f
            if (t >= 1f) { ni.remove(); continue }
            outlined(canvas, n.words, n.x - camX, n.y - camY - 26 * t, if (n.big) 28f else 20f, if (n.big) Color.rgb(255, 154, 90) else Theme.GOLD, title, (255 * (1 - t * t)).toInt())
        }
    }

    private fun fireflies(canvas: Canvas, camX: Float, camY: Float) {
        for (i in 0 until 9) {
            val bx = (i * 311f + sin(played * 0.4f + i) * 60f) - camX * 0.9f
            val by = (i * 197f + cos(played * 0.3f + i * 2) * 40f) - camY * 0.9f
            val x = ((bx % W) + W) % W; val y = ((by % H) + H) % H
            val a = (140 + sin(played * 3f + i * 1.7f) * 100).toInt().coerceIn(0, 255)
            glow(canvas, x, y, 10f, Color.rgb(200, 255, 140), a)
            fill.color = Theme.withAlpha(Color.rgb(240, 255, 192), a); canvas.drawCircle(x, y, 1.6f, fill)
        }
    }

    private fun thumbStick(canvas: Canvas) {
        fill.color = Color.argb(16, 211, 230, 243); canvas.drawCircle(stickX, stickY, STICK, fill)
        stroke.strokeWidth = 3f; stroke.color = Color.argb(90, 241, 228, 194); canvas.drawCircle(stickX, stickY, STICK, stroke)
        fill.color = Color.argb(80, 241, 228, 194); canvas.drawCircle(thumbX, thumbY, 30f, fill)
        stroke.color = Color.argb(190, 241, 228, 194); canvas.drawCircle(thumbX, thumbY, 30f, stroke)
    }

    // ---- The bar along the top, the played, the level and what you carry ----

    private fun panel(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        fill.shader = plaque(t, b); canvas.drawRoundRect(l, t, r, b, 12f, 12f, fill); fill.shader = null
        stroke.strokeWidth = 2f; stroke.color = Theme.ACCENT; canvas.drawRoundRect(l, t, r, b, 12f, 12f, stroke)
        stroke.strokeWidth = 1f; stroke.color = Theme.withAlpha(Theme.ACCENT, 90); canvas.drawRoundRect(l + 4, t + 4, r - 4, b - 4, 9f, 9f, stroke)
    }

    private fun hud(canvas: Canvas) {
        // The gem bar.
        fill.color = Color.rgb(6, 18, 30); canvas.drawRect(0f, 0f, W, 18f, fill)
        val k = (game.xp.toFloat() / game.need).coerceIn(0f, 1f)
        fill.shader = xpBar; canvas.drawRoundRect(2f, 2f, 2 + 996 * k, 16f, 4f, 4f, fill); fill.shader = null
        fill.color = Color.argb(90, 255, 255, 255); canvas.drawRoundRect(2f, 2f, 2 + 996 * k, 7f, 3f, 3f, fill)
        if (k > 0.02f) glow(canvas, 2 + 996 * k, 9f, 12f, Theme.TEAL, 200)
        stroke.strokeWidth = 2f; stroke.color = Theme.ACCENT; canvas.drawLine(0f, 18f, W, 18f, stroke)
        // The played to dawn.
        panel(canvas, 420f, 28f, 580f, 92f)
        val left = (LastLight.NIGHT - game.time).coerceAtLeast(0f)
        title.textAlign = Paint.Align.CENTER; title.textSize = 32f; title.color = Theme.GOLD_TEXT
        canvas.drawText(clock((left * 1000).toLong() + 999), 500f, 66f, title)
        words.textAlign = Paint.Align.CENTER; words.textSize = 14f; words.color = Theme.DIM
        canvas.drawText("until dawn", 500f, 84f, words)
        // Level and foes.
        panel(canvas, 842f, 28f, 988f, 92f)
        title.textSize = 22f; title.color = Theme.GOLD; canvas.drawText("Level ${game.level}", 915f, 56f, title)
        words.textSize = 17f; words.color = Theme.TEXT; canvas.drawText("${number(game.kills)} foes", 915f, 80f, words)
        // Weapons on the first row, helpers on the second.
        val weapons = game.taken.filter { it.weapon }; val helpers = game.taken.filter { !it.weapon }
        for ((i, u) in weapons.withIndex()) slot(canvas, u, 36f + i * 52f, 58f, 44f)
        for ((i, u) in helpers.withIndex()) slot(canvas, u, 30f + i * 40f, 106f, 32f)
    }

    private fun slot(canvas: Canvas, u: Upgrade, x: Float, y: Float, s: Float) {
        fill.shader = plaque(y - s / 2, y + s / 2); canvas.drawRoundRect(x - s / 2, y - s / 2, x + s / 2, y + s / 2, 10f, 10f, fill); fill.shader = null
        stroke.strokeWidth = 2f; stroke.color = Theme.EDGE; canvas.drawRoundRect(x - s / 2, y - s / 2, x + s / 2, y + s / 2, 10f, 10f, stroke)
        icon(canvas, u.name, x, y, s * 0.82f)
        val lv = game.have(u)
        fill.color = Theme.SURFACE; canvas.drawCircle(x + s * 0.32f, y + s * 0.34f, s * 0.19f, fill)
        stroke.strokeWidth = 1.5f; stroke.color = Theme.ACCENT; canvas.drawCircle(x + s * 0.32f, y + s * 0.34f, s * 0.19f, stroke)
        title.textAlign = Paint.Align.CENTER; title.textSize = s * 0.27f; title.color = Theme.GOLD
        canvas.drawText("$lv", x + s * 0.32f, y + s * 0.34f + s * 0.1f, title)
    }

    /** An upgrade's or a boost's small picture, [s] world units wide. */
    private fun icon(canvas: Canvas, name: String, x: Float, y: Float, s: Float) {
        glow(canvas, x, y, s * 0.55f, ICON_GLOW[name] ?: Theme.GOLD, 120)
        sprite(canvas, "icon$name$s", x, y, s, s) { c ->
            c.scale(s / 30f, s / 30f)
            sp.style = Paint.Style.FILL; sp.shader = null
            when (name) {
                "BOLT" -> { sp.color = Color.rgb(232, 253, 255); c.drawCircle(0f, 0f, 6.5f, sp); sp.style = Paint.Style.STROKE; sp.strokeWidth = 2.5f; sp.color = Theme.TEAL; c.drawCircle(0f, 0f, 6.5f, sp) }
                "BLADES" -> for (q in 0 until 4) { c.save(); c.rotate(q * 90f); sPath.reset(); sPath.moveTo(0f, 0f); sPath.quadTo(4f, -6f, 2f, -13f); sPath.quadTo(-1f, -7f, -3f, -2f); sPath.close(); sp.color = Theme.GOLD; c.drawPath(sPath, sp); c.restore() }
                "RING" -> { sp.style = Paint.Style.STROKE; sp.strokeWidth = 2.5f; sp.color = Color.rgb(255, 233, 168); c.drawCircle(0f, 0f, 10f, sp); sp.style = Paint.Style.FILL; sp.color = Theme.GOLD; c.drawCircle(0f, 0f, 4f, sp) }
                "FROST" -> { sp.style = Paint.Style.STROKE; sp.strokeCap = Paint.Cap.ROUND; sp.strokeWidth = 2.4f; sp.color = Color.rgb(232, 253, 255)
                    for (q in 0 until 3) { c.save(); c.rotate(q * 60f); c.drawLine(0f, -11f, 0f, 11f, sp); c.drawLine(-3f, -8f, 0f, -5f, sp); c.drawLine(3f, -8f, 0f, -5f, sp); c.drawLine(-3f, 8f, 0f, 5f, sp); c.drawLine(3f, 8f, 0f, 5f, sp); c.restore() } }
                "FIRE" -> { sp.shader = radial(-3f, -1f, 12f, Color.rgb(255, 220, 160), Color.rgb(200, 80, 30), Color.rgb(80, 20, 10)); c.drawCircle(0f, 2f, 8f, sp); sp.shader = null
                    sp.style = Paint.Style.STROKE; sp.strokeWidth = 2f; sp.color = Color.rgb(138, 106, 52); c.drawLine(4f, -4f, 8f, -9f, sp); sp.style = Paint.Style.FILL; sp.color = Theme.GOLD; c.drawCircle(9f, -10f, 2.5f, sp) }
                "BOOTS", "QUICK" -> { sPath.reset(); sPath.moveTo(-7f, -11f); sPath.lineTo(3f, -11f); sPath.lineTo(3f, 3f); sPath.lineTo(12f, 6f); sPath.lineTo(12f, 11f); sPath.lineTo(-7f, 11f); sPath.close()
                    sp.color = Color.rgb(184, 120, 74); c.drawPath(sPath, sp); outline(c, sPath, Color.rgb(74, 42, 24), 1.5f); sp.color = Theme.GOLD; c.drawRect(-7f, -7f, 3f, -5f, sp) }
                "MAGNET", "REACH" -> { sp.style = Paint.Style.STROKE; sp.strokeWidth = 6f; sp.color = Color.rgb(232, 90, 90)
                    sPath.reset(); sPath.moveTo(-9f, 8f); sPath.lineTo(-9f, -2f); sPath.arcTo(-9f, -11f, 9f, 7f, 180f, 180f, false); sPath.lineTo(9f, 8f); c.drawPath(sPath, sp)
                    sp.style = Paint.Style.FILL; sp.color = Theme.TEXT; c.drawRect(-12f, 6f, -6f, 12f, sp); c.drawRect(6f, 6f, 12f, 12f, sp) }
                "STEW", "TOUGH", "HERBS" -> { sPath.reset(); sPath.moveTo(0f, 10f); sPath.quadTo(-14f, 0f, -10f, -7f); sPath.quadTo(-5f, -12f, 0f, -5f); sPath.quadTo(5f, -12f, 10f, -7f); sPath.quadTo(14f, 0f, 0f, 10f); sPath.close()
                    sp.color = if (name == "HERBS") Theme.GOOD else Color.rgb(255, 110, 110); c.drawPath(sPath, sp); outline(c, sPath, Color.rgb(122, 26, 26), 1.2f)
                    sp.color = Color.argb(180, 255, 255, 255); c.drawOval(-7.5f, -6.6f, -2.5f, -3.4f, sp) }
                "EDGE", "SHARP" -> { c.rotate(45f); sp.color = Color.rgb(220, 230, 240); sPath.reset(); sPath.moveTo(0f, -13f); sPath.lineTo(3f, -9f); sPath.lineTo(3f, 5f); sPath.lineTo(-3f, 5f); sPath.lineTo(-3f, -9f); sPath.close(); c.drawPath(sPath, sp)
                    sp.color = Theme.ACCENT; c.drawRect(-7f, 5f, 7f, 7.5f, sp); sp.color = Color.rgb(107, 74, 36); c.drawRect(-1.5f, 7.5f, 1.5f, 13f, sp) }
                "HOURGLASS" -> { sp.color = Theme.ACCENT; c.drawRect(-9f, -12f, 9f, -9f, sp); c.drawRect(-9f, 9f, 9f, 12f, sp)
                    sPath.reset(); sPath.moveTo(-7f, -9f); sPath.lineTo(7f, -9f); sPath.lineTo(0f, 0f); sPath.lineTo(7f, 9f); sPath.lineTo(-7f, 9f); sPath.lineTo(0f, 0f); sPath.close()
                    sp.color = Color.argb(150, 191, 243, 255); c.drawPath(sPath, sp); sp.color = Theme.GOLD; sPath.reset(); sPath.moveTo(-4f, 9f); sPath.lineTo(4f, 9f); sPath.lineTo(0f, 4f); sPath.close(); c.drawPath(sPath, sp) }
                "POUCH" -> { sp.color = Theme.GOLD; c.drawCircle(-4f, 3f, 6f, sp); c.drawCircle(4f, -2f, 6f, sp); sp.style = Paint.Style.STROKE; sp.strokeWidth = 1.5f; sp.color = Color.rgb(154, 106, 26); c.drawCircle(-4f, 3f, 6f, sp); c.drawCircle(4f, -2f, 6f, sp) }
            }
            sp.style = Paint.Style.FILL; sp.shader = null
        }
    }

    // ---- The three cards ----

    private fun cardLeft(i: Int) = 95f + i * 280f

    private fun cardAt(x: Float, y: Float): Int {
        val n = game.choices?.size ?: 0
        for (i in 0 until n) if (x in cardLeft(i)..cardLeft(i) + 250f && y in CARD_Y - 10f..CARD_Y + 300f) return i
        return -1
    }

    private fun cards(canvas: Canvas) {
        val list = game.choices ?: return
        fill.color = Color.argb(185, 4, 16, 28); canvas.drawRect(0f, 0f, W, H, fill)
        glow(canvas, W / 2, H / 2, 380f, Theme.GOLD, 40)
        outlined(canvas, "Level ${game.level}!", W / 2, 150f, 44f, Theme.GOLD, title)
        words.textAlign = Paint.Align.CENTER; words.textSize = 21f; words.color = Theme.TEXT
        canvas.drawText("Pick one. The night waits for you.", W / 2, 186f, words)
        for ((i, u) in list.withIndex()) {
            val lift = if (pressed == i) 10f else 0f
            val l = cardLeft(i); val t = CARD_Y - lift; val r = l + 250f; val b = t + 300f
            if (pressed == i) glow(canvas, (l + r) / 2, (t + b) / 2, 200f, Theme.GOLD, 120)
            fill.shader = LinearGradient(0f, t, 0f, b, intArrayOf(Color.rgb(31, 74, 112), Theme.SURFACE), floatArrayOf(0f, 0.7f), Shader.TileMode.CLAMP)
            canvas.drawRoundRect(l, t, r, b, 20f, 20f, fill); fill.shader = null
            stroke.strokeWidth = 3f; stroke.color = if (pressed == i) Theme.GOLD else Theme.EDGE; canvas.drawRoundRect(l, t, r, b, 20f, 20f, stroke)
            stroke.strokeWidth = 1.5f; stroke.color = Color.argb(40, 255, 255, 255); canvas.drawLine(l + 18, t + 2, r - 18, t + 2, stroke)
            val cx = (l + r) / 2
            fill.color = Color.rgb(6, 18, 30); canvas.drawCircle(cx, t + 58, 38f, fill)
            stroke.strokeWidth = 2f; stroke.color = if (pressed == i) Theme.GOLD else Theme.EDGE; canvas.drawCircle(cx, t + 58, 38f, stroke)
            icon(canvas, u.name, cx, t + 58, 52f)
            title.textAlign = Paint.Align.CENTER; title.textSize = 22f; title.color = Theme.GOLD_TEXT
            canvas.drawText(u.title, cx, t + 128, title)
            val now = game.have(u)
            val oneOff = u == Upgrade.HERBS || u == Upgrade.POUCH
            title.textSize = 16f; title.color = Theme.ACCENT
            canvas.drawText(if (oneOff) "Now" else if (now == 0) "New" else "Level ${now + 1}", cx, t + 154, title)
            if (!oneOff) pips(canvas, cx, t + 174, now + 1)
            words.textAlign = Paint.Align.CENTER; words.textSize = 18f; words.color = Theme.TEXT
            var y = t + 212
            for (row in wrap(u.detail(now + 1), 220f)) { canvas.drawText(row, cx, y, words); y += 24f }
        }
    }

    private fun pips(canvas: Canvas, cx: Float, y: Float, filled: Int) {
        for (k in 0 until LastLight.MAX_LEVEL) {
            val x = cx - 44 + k * 22
            if (k < filled) orb(canvas, x, y, 7f, Color.rgb(255, 243, 196), Theme.ACCENT, Color.rgb(122, 90, 36))
            else { stroke.strokeWidth = 2f; stroke.color = Theme.ACCENT; canvas.drawCircle(x, y, 6.5f, stroke) }
        }
    }

    private fun wrap(text: String, width: Float): List<String> {
        val rows = mutableListOf<String>(); var row = ""
        for (w in text.split(' ')) {
            val next = if (row.isEmpty()) w else "$row $w"
            if (words.measureText(next) > width && row.isNotEmpty()) { rows += row; row = w } else row = next
        }
        if (row.isNotEmpty()) rows += row
        return rows
    }

    // ---- Before the night: the shop ----

    private fun rowY(i: Int) = 128f + i * 88f

    private fun shop(canvas: Canvas) {
        fill.color = Color.argb(215, 4, 16, 28); canvas.drawRect(0f, 0f, W, H, fill)
        title.textAlign = Paint.Align.LEFT; title.textSize = 34f; title.color = Theme.GOLD
        canvas.drawText("Before the night", 50f, 72f, title)
        title.textAlign = Paint.Align.RIGHT; title.textSize = 26f; title.color = Theme.GOLD_TEXT
        canvas.drawText("$gold gold", 950f, 70f, title)
        orb(canvas, 950f - title.measureText("$gold gold") - 18f, 61f, 8f, Color.rgb(255, 243, 196), Theme.GOLD, Color.rgb(154, 106, 26))
        words.textAlign = Paint.Align.LEFT; words.textSize = 19f; words.color = Theme.DIM
        canvas.drawText("Gold from your nights buys small boosts that last. Totally optional.", 50f, 104f, words)
        for ((i, b) in Boost.entries.withIndex()) {
            val t = rowY(i); val lv = game.shop[i]
            fill.color = Color.rgb(19, 47, 74); canvas.drawRoundRect(50f, t, 950f, t + 74f, 14f, 14f, fill)
            stroke.strokeWidth = 2f; stroke.color = Theme.STROKE; canvas.drawRoundRect(50f, t, 950f, t + 74f, 14f, 14f, stroke)
            fill.color = Color.rgb(6, 18, 30); canvas.drawCircle(98f, t + 37, 26f, fill)
            stroke.color = Theme.EDGE; canvas.drawCircle(98f, t + 37, 26f, stroke)
            icon(canvas, b.name, 98f, t + 37, 36f)
            title.textAlign = Paint.Align.LEFT; title.textSize = 22f; title.color = Theme.GOLD_TEXT
            canvas.drawText(b.title, 142f, t + 32, title)
            words.textAlign = Paint.Align.LEFT; words.textSize = 17f; words.color = Theme.DIM
            canvas.drawText(b.detail, 142f, t + 57, words)
            pips(canvas, 700f, t + 37, lv)
            val full = lv >= LastLight.MAX_LEVEL
            val afford = !full && gold >= b.cost(lv)
            button(canvas, 790f, t + 10, 950f - 14f, t + 64f, if (full) "Max" else "${b.cost(lv)} gold", primary = false, dim = !afford, down = pressed == i)
        }
        button(canvas, 380f, START_Y, 620f, START_Y + 58f, "▶ Start the night", primary = true, dim = false, down = pressed == 99)
    }

    private fun button(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, label: String, primary: Boolean, dim: Boolean, down: Boolean) {
        val top = if (primary) Color.rgb(69, 200, 227) else Theme.BUTTON_TOP
        val bottom = if (primary) Theme.TEAL_DARK else Theme.BUTTON_BOTTOM
        fill.shader = LinearGradient(0f, t, 0f, b, if (down) intArrayOf(bottom, top) else intArrayOf(top, bottom), null, Shader.TileMode.CLAMP)
        fill.alpha = if (dim) 110 else 255
        canvas.drawRoundRect(l, t, r, b, (b - t) / 2, (b - t) / 2, fill); fill.shader = null; fill.alpha = 255
        stroke.strokeWidth = 2f; stroke.color = if (primary) Theme.ACCENT else Theme.EDGE; stroke.alpha = if (dim) 110 else 255
        canvas.drawRoundRect(l, t, r, b, (b - t) / 2, (b - t) / 2, stroke); stroke.alpha = 255
        title.textAlign = Paint.Align.CENTER; title.textSize = if (primary) 22f else 18f
        title.color = if (primary) Color.rgb(11, 34, 54) else Theme.GOLD_TEXT; title.alpha = if (dim) 110 else 255
        canvas.drawText(label, (l + r) / 2, (t + b) / 2 + title.textSize * 0.36f, title); title.alpha = 255
    }

    // ---- The end of a night ----

    private fun ending(canvas: Canvas) {
        fill.color = Color.argb(if (game.won) 60 else 150, 4, 16, 28); canvas.drawRect(0f, 0f, W, H, fill)
        val l = 250f; val t = 290f; val r = 750f; val b = 560f
        panel(canvas, l, t, r, b)
        outlined(canvas, if (game.won) "Dawn breaks!" else "The light went out", W / 2, t + 52, 38f, Theme.GOLD, title)
        words.textAlign = Paint.Align.CENTER; words.textSize = 19f; words.color = Theme.DIM
        canvas.drawText(if (game.won) "You kept the light burning all night" else "The night was too long this time", W / 2, t + 82, words)
        val survived = game.time.toInt()
        val newBest = survived >= best && survived > 0
        val rows = listOf(
            Triple("Survived", clock(survived * 1000L), if (newBest) "best!" else ""),
            Triple("Foes", number(game.kills), ""),
            Triple("Level", "${game.level}", ""),
            Triple("Gold", "+${game.gold}", "for the shop"),
        )
        for ((i, row) in rows.withIndex()) {
            val y = t + 122 + i * 30
            words.textAlign = Paint.Align.LEFT; words.textSize = 21f; words.color = Theme.TEXT; canvas.drawText(row.first, l + 50, y, words)
            title.textAlign = Paint.Align.RIGHT; title.textSize = 22f; title.color = Theme.GOLD_TEXT; canvas.drawText(row.second, l + 310, y, title)
            words.textSize = 16f; words.color = Theme.ACCENT; canvas.drawText(row.third, l + 330, y, words)
        }
        hint(canvas, "Tap to go on", b - 14)
    }

    private fun outlined(canvas: Canvas, s: String, x: Float, y: Float, size: Float, colour: Int, p: Paint, alpha: Int = 255) {
        p.textAlign = Paint.Align.CENTER; p.textSize = size
        p.style = Paint.Style.STROKE; p.strokeWidth = size * 0.2f; p.color = Color.rgb(26, 14, 4); p.alpha = alpha
        canvas.drawText(s, x, y, p)
        p.style = Paint.Style.FILL; p.color = colour; p.alpha = alpha
        canvas.drawText(s, x, y, p)
        p.alpha = 255
    }

    companion object {
        private const val W = LastLight.W
        private const val H = LastLight.H
        /** The hero's place on screen (the middle). */
        private const val HX = W / 2
        private const val HY = H / 2
        private const val TILE = 1000f
        private const val STICK = 70f
        /** The hero is drawn this much bigger than the foes' scale. */
        private const val HERO_K = 1.3f
        private const val CARD_Y = 210f
        private const val START_Y = 492f
        private val GEMS = listOf(
            Triple(Color.rgb(224, 251, 255), Theme.TEAL, Color.rgb(20, 90, 120)),
            Triple(Color.rgb(232, 255, 224), Theme.GOOD, Color.rgb(29, 106, 52)),
            Triple(Color.rgb(255, 230, 224), Color.rgb(255, 110, 110), Color.rgb(138, 32, 32)),
        )
        private val GEM_GLOW = intArrayOf(Theme.TEAL, Theme.GOOD, Color.rgb(255, 110, 110))
        private val ICON_GLOW = mapOf("BOLT" to Theme.TEAL, "FROST" to Theme.TEAL, "FIRE" to Color.rgb(255, 138, 42),
            "STEW" to Theme.BAD, "TOUGH" to Theme.BAD, "HERBS" to Theme.GOOD, "MAGNET" to Theme.BAD, "REACH" to Theme.BAD)
    }
}
