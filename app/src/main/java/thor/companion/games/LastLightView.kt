package thor.companion.games

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import thor.companion.Theme
import thor.companion.games.LastLight.Boost
import thor.companion.games.LastLight.Hero
import thor.companion.games.LastLight.Kind
import thor.companion.games.LastLight.Night
import thor.companion.games.LastLight.Upgrade
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Last Light's field (pictures: mockups/survivors-v4 and v5, Jesse 2026-10-27). Before a night you
 * pick a class (Paladin, Mage, Hunter), one of the nights you have opened, and small boosts bought
 * with gold. In the night the hero stays in the middle with their lantern; the ground tilts away
 * into the fog (2.5D: far things are smaller), every figure is lit from the top left and throws a
 * long shadow away from the lantern. The bars look like WoW's: your frame, the boss frame, the
 * timer, the purple XP bar and an action bar with what you carry. A full XP bar shows three
 * tooltip cards (green new, blue stronger, purple the last rank).
 *
 * Saved in [prefs]: "light_gold", "light_shop" (boost levels), "light_class", "light_night",
 * "light_open" (how many nights are open, 1..3; 4 once the last one is cleared), "best_light"
 * (the longest night in seconds, kept by the Arcade).
 *
 * Everything with shading (figures, gems, the ground) is painted once into small pictures at the
 * screen's scale and then only copied each frame, so 200 foes stay smooth.
 */
class LastLightView(context: Context, private val prefs: SharedPreferences) : ActionView(context) {
    var game = fresh()
        private set
    /** This night's gold was added already. */
    private var paid = false
    private var opened: Night? = null
    private var lastSecond = -1
    private var bosses = 0
    private var swarms = 0

    // The thumb stick: where the thumb came down, and where it is.
    private var stick = false
    private var stickX = 0f; private var stickY = 0f
    private var thumbX = 0f; private var thumbY = 0f
    /** The card or button under the thumb (-1 none). */
    private var pressed = -1

    /** Seconds of play, for everything that moves on its own (stops while paused). */
    private var played = 0f

    private class Number(val x: Float, val y: Float, val words: String, val big: Boolean, val at: Float)
    private class Wisp(val x: Float, val y: Float, val kind: Kind, val at: Float)
    private class Wave(val x: Float, val y: Float, val radius: Float, val wave: LastLight.Wave, val at: Float)
    private val numbers = ArrayList<Number>()
    private val wisps = ArrayList<Wisp>()
    private val waves = ArrayList<Wave>()
    private var news: String? = null
    private var newsAt = -10f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val bmp = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Theme.body(context) }
    private val bold = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Theme.body(context), Typeface.BOLD) }
    private val title = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Theme.heading(context) }
    private val path = Path()
    private val box = RectF()

    /** Light and dark over the field (fixed: the hero is always in the middle). */
    private val warm = RadialGradient(HX, GY - 10, 320f, intArrayOf(Color.argb(87, 255, 207, 112), Color.argb(0, 255, 176, 80)), null, Shader.TileMode.CLAMP)
        .also { it.setLocalMatrix(Matrix().apply { setScale(1f, 0.6f, HX, GY - 10) }) }
    private val dark = RadialGradient(HX, GY - 20, 560f, intArrayOf(0, 0, Color.argb(205, 2, 7, 14)), floatArrayOf(0f, 0.38f, 1f), Shader.TileMode.CLAMP)
        .also { it.setLocalMatrix(Matrix().apply { setScale(1f, 0.62f, HX, GY - 20) }) }
    private val fog = LinearGradient(0f, 0f, 0f, H, intArrayOf(Color.argb(235, 8, 20, 32), Color.argb(90, 11, 29, 46), Color.argb(0, 11, 29, 46)), floatArrayOf(0f, 0.35f, 0.6f), Shader.TileMode.CLAMP)
    private val hurtVeil = RadialGradient(HX, HY, 560f, intArrayOf(0, Color.argb(150, 200, 30, 30)), floatArrayOf(0.55f, 1f), Shader.TileMode.CLAMP)
    private val dawn = LinearGradient(0f, 0f, 0f, H, intArrayOf(Color.argb(110, 255, 190, 120), Color.argb(50, 255, 150, 90)), null, Shader.TileMode.CLAMP)
    private fun plaque(t: Float, b: Float) = LinearGradient(0f, t, 0f, b, intArrayOf(Color.argb(235, 31, 74, 112), Color.argb(235, 11, 29, 46)), null, Shader.TileMode.CLAMP)
    /** WoW-like dark stone for the frames and the action bar. */
    private fun stone(t: Float, b: Float) = LinearGradient(0f, t, 0f, b, intArrayOf(Color.rgb(58, 60, 68), Color.rgb(34, 36, 42), Color.rgb(18, 19, 24)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)

    // ---- What is saved: gold, boosts, class and nights ----

    private fun shopLevels(): IntArray {
        val kept = (prefs.getString("light_shop", "") ?: "").split(',').map { it.toIntOrNull() ?: 0 }
        return IntArray(Boost.entries.size) { kept.getOrElse(it) { 0 }.coerceIn(0, LastLight.MAX_LEVEL) }
    }
    private var gold: Int
        get() = prefs.getInt("light_gold", 0)
        set(v) { prefs.edit().putInt("light_gold", v).apply() }
    private val best get() = prefs.getInt("best_light", 0)
    private val hero: Hero get() = Hero.entries.firstOrNull { it.name == prefs.getString("light_class", null) } ?: Hero.PALADIN
    /** How many nights are open (1..3), or 4 once the last one is cleared. */
    private val open: Int get() = prefs.getInt("light_open", 1).coerceIn(1, Night.entries.size + 1)
    private val night: Night get() = Night.entries.getOrNull(prefs.getInt("light_night", 0))?.takeIf { it.ordinal < open } ?: Night.MEADOW
    private fun nightBest(n: Night) = prefs.getInt("light_best_${n.name}", 0)

    private fun fresh() = LastLight(shopLevels(), hero = hero, night = night)

    /** A fresh night, back at the class picker. */
    fun newNight() {
        game = fresh()
        paid = false; opened = null; lastSecond = -1; bosses = 0; swarms = 0
        stick = false; pressed = -1
        numbers.clear(); wisps.clear(); waves.clear(); news = null
        onChange(); invalidate()
    }

    /**
     * For the screenshot test: a night played by a simple helper for [seconds] (walking away from
     * crowds, taking the first card), stopping at a level up when [atCards].
     */
    internal fun preview(seconds: Float, atCards: Boolean, who: Hero = hero, at: Night = night) {
        game = LastLight(shopLevels(), kotlin.random.Random(7), who, at)
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
        game = fresh()
        onChange()
    }

    private fun pickClass(h: Hero) { prefs.edit().putString("light_class", h.name).apply(); game = fresh(); onChange() }
    private fun pickNight(n: Night) { if (n.ordinal >= open) return; prefs.edit().putInt("light_night", n.ordinal).apply(); game = fresh(); onChange() }

    // ---- ActionView ----

    override fun line(): String = when {
        !game.started -> "$gold gold" + if (best > 0) " · best ${clock(best * 1000L)}" else ""
        game.over -> if (game.won) "Dawn! · ${number(game.kills)} foes" else "${clock((game.time * 1000).toLong())} · best ${clock(maxOf(best, game.time.toInt()) * 1000L)}"
        else -> "${clock(((game.night.length - game.time) * 1000).toLong())} left" + if (best > 0) " · best ${clock(best * 1000L)}" else ""
    }

    override fun moving() = game.started && !game.over && game.choices == null

    override fun step(dt: Float) {
        game.step(dt)
        played += dt
        if (game.choices != null) { stick = false; game.steerX = 0f; game.steerY = 0f }
        if (game.bossesSent != bosses) { bosses = game.bossesSent; announce("The Necromancer rises!") }
        if (game.swarmsSent != swarms) { swarms = game.swarmsSent; announce("They come from all sides!") }
        if (game.over && !paid) finish()
        val s = game.time.toInt()
        if (s != lastSecond) { lastSecond = s; onChange() }
    }

    /** The night is over: gold, this night's best, and the next night opens at dawn. */
    private fun finish() {
        paid = true
        val e = prefs.edit()
        e.putInt("light_gold", gold + game.gold)
        val n = game.night
        if (game.time.toInt() > nightBest(n)) e.putInt("light_best_${n.name}", game.time.toInt())
        if (game.won && open <= n.ordinal + 1) {
            e.putInt("light_open", n.ordinal + 2)
            opened = n.next
            // The next night is picked for you.
            n.next?.let { e.putInt("light_night", it.ordinal) }
        }
        e.apply()
        onChange()
    }

    private fun announce(w: String) { news = w; newsAt = played }

    /** Off screen, the pictures are let go (they are painted again when needed). */
    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sprites.values.forEach { it.recycle() }; sprites.clear()
    }

    override fun touch(action: Int, x: Float, y: Float) {
        when {
            !game.started -> pickerTouch(action, x, y)
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

    private fun pickerTouch(action: Int, x: Float, y: Float) {
        val at = when {
            x in START_L..START_R && y in BOOST_T - 4f..BOOST_B + 4f -> 99
            y in CLASS_T..CLASS_B -> Hero.entries.indices.firstOrNull { x in col(it)..col(it) + COL_W }?.let { 10 + it } ?: -1
            y in NIGHT_T..NIGHT_B -> Night.entries.indices.firstOrNull { x in col(it)..col(it) + COL_W }?.let { 20 + it } ?: -1
            y in BOOST_T..BOOST_B -> Boost.entries.indices.firstOrNull { x in boostL(it)..boostL(it) + BOOST_W }?.let { 30 + it } ?: -1
            else -> -1
        }
        when (action) {
            MotionEvent.ACTION_DOWN -> pressed = at
            MotionEvent.ACTION_MOVE -> if (at != pressed) pressed = -1
            MotionEvent.ACTION_UP -> {
                if (at == pressed) when (at) {
                    99 -> { game.start(); announce("Night ${game.night.ordinal + 1} · ${game.night.title}"); onChange() }
                    in 10..12 -> pickClass(Hero.entries[at - 10])
                    in 20..22 -> pickNight(Night.entries[at - 20])
                    in 30..33 -> buy(Boost.entries[at - 30])
                }
                pressed = -1
            }
            MotionEvent.ACTION_CANCEL -> pressed = -1
        }
    }

    // ---- 2.5D: the ground tilts away from the eye ----

    // The last projection (to save allocations): screen x, y and how big things are there.
    private var px = 0f; private var py = 0f; private var ps = 1f

    /** Where world point [wx], [wy] is on screen (into [px], [py], [ps]); false when it is behind the eye. */
    private fun project(wx: Float, wy: Float): Boolean {
        val rx = wx - game.x; val ry = wy - game.y
        val z = ry * TILT_SIN
        if (z > EYE - 60f) return false
        ps = EYE / (EYE - z) * ZOOM
        px = HX + rx * ps; py = GY + ry * TILT_COS * ps
        return true
    }

    private fun onScreen(margin: Float) = px > -margin && px < W + margin && py > -margin && py < H + margin * 1.6f

    override fun drawWorld(canvas: Canvas) {
        if (spritesFor != sc) { sprites.values.forEach { it.recycle() }; sprites.clear(); spritesFor = sc }
        if (!game.started) { picker(canvas); return }
        ground(canvas)
        fill.shader = warm; canvas.drawRect(0f, 0f, W, H, fill); fill.shader = null
        takeEvents()

        // Gems lying on the ground.
        for (g in game.gems) {
            if (!project(g.x, g.y) || !onScreen(20f)) continue
            val k = when { g.value >= 25 -> 2; g.value >= 3 -> 1; else -> 0 }
            fill.color = Color.argb(100, 0, 0, 0); canvas.drawOval(px - 6 * ps, py - 2 * ps, px + 6 * ps, py + 2 * ps, fill)
            glow(canvas, px, py - 9 * ps, 15f * ps, GEM_GLOW[k], 120)
            sprite(canvas, "gem$k", px, py - (9 + sin(played * 3 + g.x) * 1.5f) * ps, 20f * ps, 24f * ps, k = ps) { gem(it, k) }
        }
        for (pu in game.pickups) pickup(canvas, pu)
        // Rings that lie on the ground.
        if (game.ringRadius > 0f) holyRing(canvas, game.ringRadius)
        effectsOnGround(canvas)

        // Everything that stands, back to front: foes, the hero, the wolf, graves, trees, pillars.
        standing(canvas)

        // Weapons in the air.
        for (m in game.bombs) bomb(canvas, m)
        blades(canvas)
        for (b in game.bolts) bolt(canvas, b)
        effectsInAir(canvas)
        fireflies(canvas)

        // The dark beyond the lantern and the fog far away; red when hurt, warm at dawn.
        fill.shader = dark; canvas.drawRect(0f, 0f, W, H, fill)
        fill.shader = fog; canvas.drawRect(0f, 0f, W, H, fill)
        if (game.hurt > 0 && !game.over) { fill.shader = hurtVeil; fill.alpha = (255 * (game.hurt / 0.25f)).toInt().coerceIn(0, 255); canvas.drawRect(0f, 0f, W, H, fill); fill.alpha = 255 }
        if (game.won) { fill.shader = dawn; canvas.drawRect(0f, 0f, W, H, fill) }
        fill.shader = null
        if (stick && moving()) thumbStick(canvas)
        hud(canvas)
        val n = news
        if (n != null && played - newsAt < 2.6f && moving()) {
            val a = ((2.6f - (played - newsAt)) / 0.6f).coerceIn(0f, 1f)
            outlined(canvas, n, W / 2, 150f, 30f, Theme.GOLD, title, (255 * a).toInt())
        }
        when {
            game.over -> ending(canvas)
            game.choices != null -> cards(canvas)
            game.time < 4f -> hint(canvas, "Put your thumb anywhere and drag to walk. You fight on your own.", 520f)
        }
        if (moving() && (numbers.isNotEmpty() || wisps.isNotEmpty() || waves.isNotEmpty())) postInvalidateOnAnimation()
    }

    private fun takeEvents() {
        for (h in game.hits) numbers += Number(h.x + (Math.random() * 10 - 5).toFloat(), h.y, h.damage.toString(), h.big, played)
        game.hits.clear()
        while (numbers.size > 28) numbers.removeAt(0)
        for (d in game.deaths) wisps += Wisp(d.x, d.y, d.kind, played)
        game.deaths.clear()
        while (wisps.size > 40) wisps.removeAt(0)
        for (r in game.rings) waves += Wave(r.x, r.y, r.radius, r.wave, played)
        game.rings.clear()
        for (p in game.used) announce("${p.title}!")
        game.used.clear()
    }

    // ---- The ground: one big seamless picture per night, laid flat and tilted ----

    /** Each night's own ground: colours, and how many graves, trees and broken pillars stand on it. */
    private class Look(val ground: Int, val grass: IntArray, val dirt: Int, val flowers: Int, val graves: Int, val trees: Int, val pillars: Int, val mist: Int, val runes: Boolean)
    private fun look(n: Night) = when (n) {
        Night.MEADOW -> Look(Color.rgb(27, 51, 38), intArrayOf(Color.rgb(63, 112, 72), Color.rgb(47, 90, 58)), Color.rgb(86, 72, 50), 60, 5, 4, 0, Color.rgb(207, 230, 255), false)
        Night.GRAVEYARD -> Look(Color.rgb(28, 40, 42), intArrayOf(Color.rgb(70, 96, 82), Color.rgb(52, 74, 64)), Color.rgb(74, 68, 58), 16, 16, 6, 0, Color.rgb(190, 214, 230), false)
        Night.RUINS -> Look(Color.rgb(36, 30, 46), intArrayOf(Color.rgb(92, 82, 112), Color.rgb(66, 58, 86)), Color.rgb(82, 72, 84), 0, 4, 3, 9, Color.rgb(200, 180, 255), true)
    }

    private val warp = Matrix()
    private val src = FloatArray(8)
    private val dst = FloatArray(8)

    private fun ground(canvas: Canvas) {
        val n = game.night
        val key = "ground${n.name}"
        if (key !in sprites) {
            // Only one night's ground at a time: it is the biggest picture.
            sprites.keys.filter { it.startsWith("ground") }.forEach { sprites.remove(it)?.recycle() }
        }
        val tile = sprites.getOrPut(key) { paintGround(n) }
        // One matrix for the whole tilted plane (in world units around the hero).
        val l = -WIDE; val r = WIDE; val t = -FAR; val b = NEAR
        src[0] = l; src[1] = t; src[2] = r; src[3] = t; src[4] = r; src[5] = b; src[6] = l; src[7] = b
        for (i in 0 until 4) { project(game.x + src[i * 2], game.y + src[i * 2 + 1]); dst[i * 2] = px; dst[i * 2 + 1] = py }
        warp.setPolyToPoly(src, 0, dst, 0, 4)
        canvas.drawColor(look(n).ground)
        canvas.save()
        canvas.concat(warp)
        canvas.clipRect(l, t, r, b)
        val x0 = floor((game.x + l) / TILE) * TILE; val y0 = floor((game.y + t) / TILE) * TILE
        var ty = y0
        while (ty < game.y + b) {
            var tx = x0
            while (tx < game.x + r) {
                box.set(tx - game.x, ty - game.y, tx - game.x + TILE, ty - game.y + TILE)
                canvas.drawBitmap(tile, null, box, bmp)
                tx += TILE
            }
            ty += TILE
        }
        canvas.restore()
    }

    private fun paintGround(n: Night): Bitmap {
        val lk = look(n)
        val px = (TILE * sc * 0.6f).toInt().coerceIn(8, 1400)
        val b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.scale(px / TILE, px / TILE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        c.drawColor(lk.ground)
        val rnd = java.util.Random(11L + n.ordinal * 7)
        fun r() = rnd.nextFloat()
        /** Draws [what] at x, y and again across each edge it touches, so the tile has no seams. */
        fun wrap(x: Float, y: Float, reach: Float, what: (Float, Float) -> Unit) {
            for (dx in -1..1) for (dy in -1..1) {
                val ax = x + dx * TILE; val ay = y + dy * TILE
                if (ax + reach < 0 || ax - reach > TILE || ay + reach < 0 || ay - reach > TILE) continue
                what(ax, ay)
            }
        }
        // The tilt squashes the ground up and down; things drawn on it are made taller to match.
        val tall = 1f / TILT_COS
        // Soft lighter and darker patches.
        p.maskFilter = BlurMaskFilter(18f, BlurMaskFilter.Blur.NORMAL)
        repeat(26) {
            val x = r() * TILE; val y = r() * TILE; val w = 40 + r() * 70; val h = 18 + r() * 30
            p.color = if (it % 3 == 0) Color.argb(60, 10, 22, 22) else Theme.withAlpha(lk.grass[0], 56)
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
        p.color = Theme.withAlpha(lk.dirt, 200); p.strokeWidth = 62f; c.drawPath(path, p)
        p.color = Color.argb(90, 255, 240, 210); p.strokeWidth = 30f; p.alpha = 30; c.drawPath(path, p)
        p.style = Paint.Style.FILL
        repeat(70) {
            val x = r() * TILE
            val y = TILE * 0.62f + 70 * sin(x / TILE * 6.2832f) + 30 * sin(x / TILE * 12.566f + 1f) + (r() - 0.5f) * 44
            p.color = Color.rgb(122, 106, 80); p.alpha = 170
            val w = 2 + r() * 3; val h = (1.5f + r() * 2) * tall
            wrap(x, y, 8f) { ax, ay -> c.drawOval(ax - w, ay - h, ax + w, ay + h, p) }
        }
        p.alpha = 255
        // Flat stones with a light top.
        repeat(16) {
            val x = r() * TILE; val y = r() * TILE; val rx = 8 + r() * 12; val ry = (5 + r() * 6) * tall
            wrap(x, y, rx + 12) { ax, ay ->
                p.color = Color.argb(100, 0, 0, 0); c.drawOval(ax - rx, ay + 3 - ry * 0.6f, ax + rx, ay + 3 + ry * 0.6f, p)
                p.color = Color.rgb(86, 100, 108); c.drawOval(ax - rx, ay - ry, ax + rx, ay + ry, p)
                p.color = Color.argb(170, 138, 154, 162); c.drawOval(ax - rx * 0.75f, ay - ry * 0.7f, ax + rx * 0.15f, ay - ry * 0.05f, p)
            }
        }
        // Glowing runes in the ruins.
        if (lk.runes) repeat(10) {
            val x = r() * TILE; val y = r() * TILE; val rr = 22 + r() * 16
            wrap(x, y, rr + 10) { ax, ay ->
                p.style = Paint.Style.STROKE; p.strokeWidth = 2f
                p.color = Color.argb(110, 122, 255, 58); c.drawOval(ax - rr, ay - rr, ax + rr, ay + rr, p)
                p.color = Color.argb(70, 122, 255, 58); c.drawOval(ax - rr * 0.7f, ay - rr * 0.7f, ax + rr * 0.7f, ay + rr * 0.7f, p)
                for (k in 0 until 6) { val a = k * 1.047f; c.drawLine(ax + cos(a) * rr * 0.7f, ay + sin(a) * rr * 0.7f, ax + cos(a) * rr, ay + sin(a) * rr, p) }
                p.style = Paint.Style.FILL
            }
        }
        // Grass tufts with light tips.
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f
        repeat(260) {
            val x = r() * TILE; val y = r() * TILE
            val col = if (r() < 0.5f) lk.grass[0] else lk.grass[1]
            wrap(x, y, 26f) { ax, ay ->
                p.color = col
                path.reset()
                path.moveTo(ax, ay); path.quadTo(ax - 3, ay - 10 * tall, ax - 6, ay - 13 * tall)
                path.moveTo(ax, ay); path.quadTo(ax, ay - 12 * tall, ax + 1, ay - 16 * tall)
                path.moveTo(ax, ay); path.quadTo(ax + 3, ay - 9 * tall, ax + 7, ay - 12 * tall)
                c.drawPath(path, p)
            }
        }
        p.style = Paint.Style.FILL
        // Little flowers.
        val petals = intArrayOf(Color.rgb(200, 176, 255), Color.rgb(255, 243, 196), Color.rgb(159, 216, 255))
        repeat(lk.flowers) { i ->
            val x = r() * TILE; val y = r() * TILE
            wrap(x, y, 8f) { ax, ay ->
                p.color = petals[i % 3]; p.alpha = 220
                for (k in 0 until 5) { val a = k * 1.2566f; c.drawCircle(ax + cos(a) * 3f, ay + sin(a) * 3f * tall, 2f, p) }
                p.alpha = 255; p.color = Theme.GOLD; c.drawCircle(ax, ay, 1.6f, p)
            }
        }
        // Mist.
        p.maskFilter = BlurMaskFilter(20f, BlurMaskFilter.Blur.NORMAL)
        repeat(5) {
            val x = r() * TILE; val y = r() * TILE; val w = 200 + r() * 160
            p.color = Theme.withAlpha(lk.mist, 20)
            wrap(x, y, w + 40) { ax, ay -> c.drawOval(ax - w, ay - 30, ax + w, ay + 30, p) }
        }
        p.maskFilter = null
        return b
    }

    /** Graves, trees and pillars standing on each tile: where (in the tile) and which. */
    private class Deco(val x: Float, val y: Float, val what: Int, val flip: Boolean)
    private val decos = HashMap<Night, List<Deco>>()
    private fun decosOf(n: Night) = decos.getOrPut(n) {
        val lk = look(n); val rnd = java.util.Random(50L + n.ordinal); val list = mutableListOf<Deco>()
        repeat(lk.graves) { val x = rnd.nextFloat() * TILE; val y = rnd.nextFloat() * TILE; list += Deco(x, y, 0, rnd.nextBoolean()); if (rnd.nextFloat() < 0.5f) list += Deco(x + 44, y + 14, 0, rnd.nextBoolean()) }
        repeat(lk.trees) { list += Deco(rnd.nextFloat() * TILE, rnd.nextFloat() * TILE, 1, rnd.nextBoolean()) }
        repeat(lk.pillars) { list += Deco(rnd.nextFloat() * TILE, rnd.nextFloat() * TILE, 2 + rnd.nextInt(2), rnd.nextBoolean()) }
        list
    }

    // ---- Standing things, back to front ----

    /** One thing to draw: its depth (world y), and what. */
    private class Up(val y: Float, val foe: LastLight.Foe?, val deco: Deco?, val dx: Float, val dy: Float, val what: Int)
    private val ups = ArrayList<Up>(300)

    private fun standing(canvas: Canvas) {
        ups.clear()
        for (f in game.foes) {
            if (!project(f.x, f.y) || !onScreen(90f)) continue
            ups += Up(f.y, f, null, 0f, 0f, 0)
        }
        ups += Up(game.y, null, null, 0f, 0f, HERO)
        if (game.have(Upgrade.WOLF) > 0) ups += Up(game.wolfY, null, null, 0f, 0f, WOLF)
        val list = decosOf(game.night)
        val x0 = floor((game.x - WIDE) / TILE) * TILE; val y0 = floor((game.y - FAR) / TILE) * TILE
        var ty = y0
        while (ty < game.y + NEAR + 60f) {
            var tx = x0
            while (tx < game.x + WIDE) {
                for (d in list) {
                    val wy = ty + d.y
                    if (wy < game.y - FAR || wy > game.y + NEAR + 60f) continue
                    if (!project(tx + d.x, wy) || !onScreen(80f)) continue
                    ups += Up(wy, null, d, tx + d.x, wy, DECO)
                }
                tx += TILE
            }
            ty += TILE
        }
        ups.sortBy { it.y }
        for (u in ups) when {
            u.foe != null -> foe(canvas, u.foe)
            u.what == HERO -> hero(canvas)
            u.what == WOLF -> wolf(canvas)
            u.deco != null -> deco(canvas, u.deco, u.dx, u.dy)
        }
    }

    /** A soft shadow from the feet at [px], [py], long and pointing away from the lantern. */
    private fun longShadow(canvas: Canvas, k: Float, alpha: Int = 120) {
        val ang = atan2(py - GY, px - HX)
        val dist = hypot(px - HX, py - GY)
        val len = minOf(60f, 10f + dist * 0.08f) * ps
        canvas.save()
        canvas.rotate(Math.toDegrees(ang.toDouble()).toFloat() * 0.5f, px, py)
        val cx = px + cos(ang) * len / 2; val cy = py + sin(ang) * len / 4
        val w = len + 22f * ps * k; val h = 15f * ps * k
        sprite(canvas, "shadow", cx, cy, w, h, alpha = alpha) { c ->
            sp.maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL); sp.color = Color.BLACK
            c.drawOval(-42f, -12f, 42f, 12f, sp); sp.maskFilter = null
        }
        canvas.restore()
    }

    private fun hero(canvas: Canvas) {
        project(game.x, game.y)
        val walking = moving() && hypot(game.steerX, game.steerY) > 0.05f
        val bob = if (walking) sin(played * 18f) * 1.6f else sin(played * 2f) * 0.6f
        val flip = game.facing < 0
        val h = game.hero
        val s = ps
        glow(canvas, px, py - 40f * s, 70f * s, Theme.GOLD, 70)
        longShadow(canvas, HERO_K, 90)
        if (game.hurt > 0) glow(canvas, px, py - 30f * s, 40f * s, Theme.BAD, 160)
        // The class's glow: holy gold, arcane purple, the hunter's lantern.
        val (gx, gy, gc) = when (h) {
            Hero.PALADIN -> Triple(22f, -18f, Theme.GOLD)
            Hero.MAGE -> Triple(19f, -38f, Color.rgb(192, 122, 255))
            Hero.HUNTER -> Triple(0f, -10f, Theme.GOLD)
        }
        val flicker = 120 + (sin(played * 9f) * 25 + sin(played * 23f) * 15).toInt()
        val cy = py - (FOOT_HERO * HERO_K + bob) * s
        glow(canvas, px + gx * HERO_K * game.facing * s, cy + gy * HERO_K * s, 40f * s, gc, flicker)
        sprite(canvas, "hero${h.name}", px, cy, HERO_BOX_W * HERO_K * s, HERO_BOX_H * HERO_K * s, flip, lit = true, k = s) { c -> c.scale(HERO_K, HERO_K); LastLightArt.hero(c, h) }
        // A small health bar under the feet.
        val f = (game.hp / game.maxHp).coerceIn(0f, 1f)
        fill.color = Color.rgb(6, 18, 30); canvas.drawRoundRect(px - 26, py + 9, px + 26, py + 16, 3.5f, 3.5f, fill)
        fill.shader = hpBar(py + 10, py + 15, f > 0.35f); canvas.drawRoundRect(px - 25, py + 10, px - 25 + 50 * f, py + 15, 2.5f, 2.5f, fill); fill.shader = null
    }

    private fun wolf(canvas: Canvas) {
        if (!project(game.wolfX, game.wolfY)) return
        longShadow(canvas, 1.2f)
        val run = moving() && sin(played * 20f) > 0
        sprite(canvas, "wolf$run", px, py - 14f * WOLF_K * ps, 66f * WOLF_K * ps, 52f * WOLF_K * ps, flip = game.wolfFacing < 0, lit = true, k = ps) { c ->
            c.scale(WOLF_K, WOLF_K); c.translate(-2f, 0f); LastLightArt.wolf(c, run)
        }
    }

    private fun foe(canvas: Canvas, f: LastLight.Foe) {
        project(f.x, f.y)
        val flash = f.flash > 0
        val s = ps
        val bob = sin(played * 4 + f.phase) * 1.5f
        if (f.kind != Kind.BANSHEE) longShadow(canvas, if (f.kind == Kind.NECROMANCER) 3f else 1f)
        if (f.frozen > 0) glow(canvas, px, py - 16 * s, f.kind.radius * 2.6f * s, Color.rgb(191, 243, 255), 190)
        else if (f.slow > 0) glow(canvas, px, py - 16 * s, f.kind.radius * 2f * s, Theme.TEAL, 110)
        val left = game.x < f.x
        when (f.kind) {
            Kind.GHOUL -> sprite(canvas, "ghoul$flash", px, py - (19 + bob * 0.5f) * s, 44f * s, 52f * s, flip = left, lit = !flash, k = s) { LastLightArt.ghoul(it, flash) }
            Kind.BANSHEE -> sprite(canvas, "banshee$flash", px, py - (34 + bob * 2f) * s, 62f * s, 80f * s, flip = left, lit = !flash, k = s) { LastLightArt.banshee(it, flash) }
            Kind.SKELETON -> sprite(canvas, "skeleton$flash", px, py - 21 * s, 56f * s, 56f * s, flip = left, lit = !flash, k = s) { LastLightArt.skeleton(it, flash) }
            Kind.BAT -> {
                val up = sin(played * 16 + f.phase) > 0
                // A roomy box (the wings reach far out), drawn a little bigger than before.
                sprite(canvas, "bat$up$flash", px, py - 40 * s, 92f * s, 80f * s, lit = !flash, k = s) { c -> c.scale(1.15f, 1.15f); LastLightArt.bat(c, up, flash) }
            }
            Kind.NECROMANCER -> sprite(canvas, "necromancer$flash", px, py - (48 + bob * 2f) * s, 160f * s, 180f * s, flip = left, lit = !flash, k = s) { LastLightArt.necromancer(it, flash) }
        }
    }

    private fun deco(canvas: Canvas, d: Deco, wx: Float, wy: Float) {
        project(wx, wy)
        val s = ps * DECO_K
        longShadow(canvas, if (d.what == 1) 2.2f else 1.4f, 100)
        when (d.what) {
            0 -> sprite(canvas, "grave${d.flip}", px, py - 13 * s, 52f * s, 80f * s, lit = true, k = s) { c -> grave(c, sp, 0f, -8f, if (d.flip) 6f else -5f) }
            1 -> sprite(canvas, "tree", px, py - 37 * s, 110f * s, 110f * s, flip = d.flip, lit = true, k = s) { c -> deadTree(c, sp, -10f, -23f) }
            else -> sprite(canvas, "pillar${d.what}", px, py - 34 * s, 50f * s, 90f * s, flip = d.flip, lit = true, k = s) { c -> pillar(c, d.what == 3) }
        }
    }

    private fun grave(c: Canvas, p: Paint, x: Float, y: Float, tilt: Float) {
        c.save(); c.rotate(tilt, x, y + 21)
        p.style = Paint.Style.FILL
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

    /** A broken stone pillar from the ruins; [short] is the more broken one. */
    private fun pillar(c: Canvas, short: Boolean) {
        val top = if (short) -10f else -36f
        sp.style = Paint.Style.FILL
        sp.shader = LinearGradient(-16f, 0f, 16f, 0f, intArrayOf(Color.rgb(150, 144, 160), Color.rgb(96, 90, 108), Color.rgb(46, 42, 56)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        sPath.reset(); sPath.moveTo(-11f, 30f); sPath.lineTo(-11f, top + 4); sPath.lineTo(-5f, top); sPath.lineTo(0f, top + 6); sPath.lineTo(5f, top - 2); sPath.lineTo(11f, top + 5); sPath.lineTo(11f, 30f); sPath.close()
        c.drawPath(sPath, sp); sp.shader = null
        outline(c, sPath, Color.rgb(20, 16, 26), 1.6f)
        sp.style = Paint.Style.STROKE; sp.strokeWidth = 1.2f; sp.color = Color.argb(120, 30, 26, 38)
        for (gx in floatArrayOf(-5f, 0f, 5f)) c.drawLine(gx, top + 8, gx, 26f, sp)
        sp.style = Paint.Style.FILL
        sp.color = Color.rgb(70, 64, 80); c.drawRoundRect(-16f, 24f, 16f, 33f, 2f, 2f, sp)
        outline(c, Path().apply { addRoundRect(-16f, 24f, 16f, 33f, 2f, 2f, Path.Direction.CW) }, Color.rgb(20, 16, 26), 1.4f)
        // Ivy, and a green glow from a crack.
        sp.style = Paint.Style.STROKE; sp.strokeWidth = 2.2f; sp.color = Color.rgb(58, 96, 52)
        sPath.reset(); sPath.moveTo(-10f, 28f); sPath.quadTo(-2f, 14f, -8f, 4f); sPath.quadTo(-12f, -4f, -4f, top + 12); c.drawPath(sPath, sp)
        sp.style = Paint.Style.FILL
        sp.shader = RadialGradient(3f, 10f, 9f, Color.argb(160, 122, 255, 58), 0, Shader.TileMode.CLAMP); c.drawCircle(3f, 10f, 9f, sp); sp.shader = null
    }

    // ---- Small painted pictures, made once per size ----

    private val sprites = HashMap<String, Bitmap>()
    private var spritesFor = 0f
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP) }

    /**
     * Copies the picture [key] ([w] x [h] world units, centred on [x], [y]); [paint] draws it the
     * first time, centred on 0, 0, at its natural size: [k] times smaller than [w] x [h] (far
     * things are drawn small, but the picture stays sharp up close). With [lit], the picture gets
     * light from the top left and shade on the far side, so it looks round.
     */
    private fun sprite(canvas: Canvas, key: String, x: Float, y: Float, w: Float, h: Float, flip: Boolean = false, alpha: Int = 255, lit: Boolean = false, k: Float = 1f, paint: (Canvas) -> Unit) {
        val b = sprites.getOrPut(key) {
            val nw = w / k; val nh = h / k
            val pw = (nw * sc).toInt().coerceIn(2, 1024); val ph = (nh * sc).toInt().coerceIn(2, 1024)
            Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888).also {
                val c = Canvas(it); c.scale(pw / nw, ph / nh); c.translate(nw / 2, nh / 2); paint(c)
                if (lit) {
                    light.shader = LinearGradient(-nw * 0.45f, -nh * 0.5f, nw * 0.45f, nh * 0.35f,
                        intArrayOf(Color.argb(80, 255, 244, 214), Color.argb(0, 255, 255, 255), Color.argb(115, 4, 6, 20)), floatArrayOf(0f, 0.42f, 1f), Shader.TileMode.CLAMP)
                    c.drawRect(-nw / 2, -nh / 2, nw / 2, nh / 2, light); light.shader = null
                }
            }
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

    private fun gem(c: Canvas, k: Int) {
        val (light, mid, deep) = GEMS[k]
        val p = sPath
        p.reset(); p.moveTo(0f, -9f); p.lineTo(7f, -1f); p.lineTo(0f, 10f); p.lineTo(-7f, -1f); p.close()
        sp.shader = radial(-2.5f, -4f, 14f, light, mid, deep); c.drawPath(p, sp); sp.shader = null
        outline(c, p, Color.rgb(11, 34, 54), 1.2f)
        p.reset(); p.moveTo(0f, -9f); p.lineTo(3f, -1f); p.lineTo(-3f, -1f); p.close()
        sp.color = Color.argb(190, 255, 255, 255); c.drawPath(p, sp)
    }

    // ---- Weapons and effects, in 2.5D ----

    /** The oval a ground circle of [r] around world [wx], [wy] makes on screen (into [box]); false when not seen. */
    private fun groundOval(wx: Float, wy: Float, r: Float, lift: Float = 0f): Boolean {
        if (!project(wx, wy)) return false
        box.set(px - r * ps, py - r * ps * TILT_COS - lift * ps, px + r * ps, py + r * ps * TILT_COS - lift * ps)
        return true
    }

    private fun holyRing(canvas: Canvas, r: Float) {
        if (!groundOval(game.x, game.y, r)) return
        stroke.strokeWidth = 12f; stroke.color = Color.argb(30, 255, 233, 168); canvas.drawOval(box, stroke)
        stroke.strokeWidth = 1.6f; stroke.color = Color.argb(140, 255, 243, 196); canvas.drawOval(box, stroke)
        fill.color = Color.argb(14, 255, 233, 168); canvas.drawOval(box, fill)
        fill.color = Color.argb(190, 255, 233, 168)
        for (i in 0 until 12) {
            val a = i * 0.5236f + played * 0.6f
            project(game.x + cos(a) * r, game.y + sin(a) * r)
            canvas.drawCircle(px, py, 3.2f * ps, fill)
        }
    }

    private fun blades(canvas: Canvas) {
        val n = game.bladeCount
        if (n == 0) return
        val r = game.bladeRadius
        for (k in 0 until n) {
            val a = game.bladeAngle + k * 6.2832f / n
            // A fading arc behind each blade.
            if (groundOval(game.x, game.y, r, 18f)) {
                stroke.strokeWidth = 10f; stroke.color = Color.argb(40, 255, 210, 90)
                canvas.drawArc(box, Math.toDegrees(a.toDouble()).toFloat() - 50f, 50f, false, stroke)
                stroke.strokeWidth = 2f; stroke.color = Color.argb(110, 255, 243, 196)
                canvas.drawArc(box, Math.toDegrees(a.toDouble()).toFloat() - 40f, 40f, false, stroke)
            }
            if (!project(game.x + cos(a) * r, game.y + sin(a) * r)) continue
            fill.color = Color.argb(70, 0, 0, 0); canvas.drawOval(px - 12 * ps, py - 3 * ps, px + 12 * ps, py + 3 * ps, fill)
            val by = py - 18f * ps
            glow(canvas, px, by, 26f * ps, Theme.GOLD, 150)
            canvas.save(); canvas.rotate(played * 720f % 360f, px, by)
            sprite(canvas, "blade", px, by, 44f * ps, 44f * ps, k = ps) { c ->
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

    private fun bolt(canvas: Canvas, b: LastLight.Bolt) {
        // Which way it flies on screen: from where it was a moment ago.
        if (!project(b.x - b.vx * 0.04f, b.y - b.vy * 0.04f)) return
        val bx0 = px; val by0 = py
        if (!project(b.x, b.y) || !onScreen(40f)) return
        val dx = px - bx0; val dy = py - by0; val d = hypot(dx, dy).coerceAtLeast(0.01f)
        val ux = dx / d; val uy = dy / d
        val lift = 20f * ps
        fill.color = Color.argb(70, 0, 0, 0); canvas.drawOval(px - 9 * ps, py - 2 * ps, px + 9 * ps, py + 2 * ps, fill)
        val x = px; val y = py - lift
        if (b.arrow) {
            stroke.strokeWidth = 6f * ps; stroke.color = Color.argb(46, 191, 224, 160); canvas.drawLine(x - ux * 46 * ps, y - uy * 46 * ps, x, y, stroke)
            canvas.save(); canvas.rotate(Math.toDegrees(atan2(uy, ux).toDouble()).toFloat(), x, y)
            sprite(canvas, "arrow", x - 7f * ps, y, 44f * ps, 12f * ps, k = ps) { c -> c.translate(7f, 0f); LastLightArt.arrow(c) }
            canvas.restore()
        } else {
            stroke.strokeWidth = 9f * ps; stroke.color = Color.argb(60, 52, 186, 215); canvas.drawLine(x - ux * 60 * ps, y - uy * 60 * ps, x, y, stroke)
            stroke.strokeWidth = 3f * ps; stroke.color = Color.argb(150, 191, 243, 255); canvas.drawLine(x - ux * 44 * ps, y - uy * 44 * ps, x, y, stroke)
            glow(canvas, x, y, 24f * ps, Color.rgb(192, 122, 255), 200)
            orb(canvas, x, y, 7.5f * ps, Color.WHITE, Color.rgb(240, 216, 255), Color.rgb(160, 74, 255))
        }
    }

    private fun bomb(canvas: Canvas, m: LastLight.Bomb) {
        val t = m.t.coerceIn(0f, 1f)
        // Where it will land.
        if (groundOval(m.x, m.y, m.radius * (0.4f + 0.6f * t))) {
            stroke.strokeWidth = 2f; stroke.color = if (m.holy) Color.argb((60 + 120 * t).toInt(), 255, 214, 90) else Color.argb((60 + 120 * t).toInt(), 255, 138, 42)
            canvas.drawOval(box, stroke)
        }
        val wx = m.fromX + (m.x - m.fromX) * t; val wy = m.fromY + (m.y - m.fromY) * t
        if (!project(wx, wy)) return
        fill.color = Color.argb(70, 0, 0, 0); canvas.drawOval(px - 8 * ps, py - 2.5f * ps, px + 8 * ps, py + 2.5f * ps, fill)
        val y = py - (20f + sin(t * Math.PI.toFloat()) * 90f) * ps
        if (m.holy) {
            glow(canvas, px, y, 30f * ps, Theme.GOLD, 220)
            canvas.save(); canvas.rotate(t * 540f, px, y)
            sprite(canvas, "hammer", px, y, 34f * ps, 34f * ps, k = ps) { c -> LastLightArt.hammerIcon(c) }
            canvas.restore()
        } else {
            glow(canvas, px, y, 22f * ps, Color.rgb(255, 138, 42), 200)
            orb(canvas, px, y, 8f * ps, Color.rgb(255, 220, 160), Color.rgb(200, 80, 30), Color.rgb(80, 20, 10))
            fill.color = Theme.GOLD; canvas.drawCircle(px + 5 * ps, y - 8 * ps, (2.5f + sin(played * 30) * 1f) * ps, fill)
        }
    }

    /** Frost, fire, holy and howl waves spreading on the ground. */
    private fun effectsOnGround(canvas: Canvas) {
        val wi = waves.iterator()
        while (wi.hasNext()) {
            val w = wi.next(); val t = (played - w.at) / 0.35f
            if (t >= 1f) { wi.remove(); continue }
            val r = w.radius * (0.3f + 0.7f * t)
            if (!groundOval(w.x, w.y, r)) continue
            val a = (255 * (1 - t)).toInt()
            if (w.wave == LastLight.Wave.FROST) {
                fill.color = Color.argb(a / 5, 191, 243, 255); canvas.drawOval(box, fill)
                stroke.strokeWidth = 6f; stroke.color = Theme.withAlpha(Color.rgb(191, 243, 255), a); canvas.drawOval(box, stroke)
                stroke.strokeWidth = 2f
                val cx = box.centerX(); val cy = box.centerY(); val rx = box.width() / 2; val ry = box.height() / 2
                for (k in 0 until 16) {
                    val ang = k * 0.3927f
                    canvas.drawLine(cx + cos(ang) * (rx - 10), cy + sin(ang) * (ry - 10 * TILT_COS), cx + cos(ang) * (rx + 6), cy + sin(ang) * (ry + 6 * TILT_COS), stroke)
                }
            } else {
                // Fire is orange, the paladin's holy burst gold, the wolf's howl pale grey.
                val (core, rim) = when (w.wave) {
                    LastLight.Wave.HOLY -> Color.rgb(255, 214, 90) to Color.rgb(255, 244, 200)
                    LastLight.Wave.HOWL -> Color.rgb(170, 185, 200) to Color.rgb(225, 232, 240)
                    else -> Color.rgb(255, 138, 42) to Color.rgb(255, 210, 138)
                }
                glow(canvas, box.centerX(), box.centerY() - 10f, 90f * ps, core, a)
                fill.color = Theme.withAlpha(core, a / 3); canvas.drawOval(box, fill)
                stroke.strokeWidth = 4f; stroke.color = Theme.withAlpha(rim, a); canvas.drawOval(box, stroke)
            }
        }
    }

    /** Souls rising from beaten foes, and damage numbers. */
    private fun effectsInAir(canvas: Canvas) {
        val di = wisps.iterator()
        while (di.hasNext()) {
            val d = di.next(); val t = (played - d.at) / 0.5f
            if (t >= 1f) { di.remove(); continue }
            if (!project(d.x, d.y)) continue
            val x = px; val y = py - (24 + 30 * t) * ps
            val colour = when (d.kind) { Kind.BAT -> Color.rgb(179, 132, 224); Kind.SKELETON -> Color.rgb(255, 250, 240); Kind.BANSHEE -> Color.rgb(168, 200, 255); Kind.NECROMANCER -> Color.rgb(255, 170, 70); else -> Color.rgb(200, 255, 170) }
            glow(canvas, x, y, (if (d.kind == Kind.NECROMANCER) 80f else 22f) * ps, colour, (200 * (1 - t)).toInt())
            stroke.strokeWidth = 3f * ps; stroke.color = Theme.withAlpha(Color.rgb(232, 243, 255), (150 * (1 - t)).toInt())
            path.reset(); path.moveTo(x, y); path.quadTo(x - 7 * ps, y - 12 * ps, x, y - 22 * ps); path.quadTo(x + 6 * ps, y - 30 * ps, x, y - 40 * ps)
            canvas.drawPath(path, stroke)
        }
        val ni = numbers.iterator()
        while (ni.hasNext()) {
            val n = ni.next(); val t = (played - n.at) / 0.45f
            if (t >= 1f) { ni.remove(); continue }
            if (!project(n.x, n.y)) continue
            outlined(canvas, n.words, px, py - (34 + 26 * t) * ps, (if (n.big) 28f else 20f) * ps.coerceIn(0.7f, 1.2f), if (n.big) Color.rgb(255, 154, 90) else Theme.GOLD, title, (255 * (1 - t * t)).toInt())
        }
    }

    private fun fireflies(canvas: Canvas) {
        for (i in 0 until 9) {
            val bx = (i * 311f + sin(played * 0.4f + i) * 60f) - game.x * 0.9f
            val by = (i * 197f + cos(played * 0.3f + i * 2) * 40f) - game.y * 0.6f
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

    // ---- Power-ups on the ground (mockups/survivors-v6, at half the picture's size) ----

    private fun powerColour(p: LastLight.Power) = when (p) {
        LastLight.Power.NOVA, LastLight.Power.GOLD -> Theme.GOLD
        LastLight.Power.FROST -> Color.rgb(159, 216, 255)
        LastLight.Power.MAGNET -> Color.rgb(255, 122, 122)
        LastLight.Power.POTION -> Color.rgb(255, 106, 106)
        LastLight.Power.FURY -> Color.rgb(255, 138, 58)
    }

    private fun pickup(canvas: Canvas, pu: LastLight.Pickup) {
        if (!project(pu.x, pu.y) || !onScreen(40f)) return
        val col = powerColour(pu.power)
        val s = ps
        val pulse = 0.85f + 0.15f * sin(played * 5f + pu.x)
        // A soft light on the ground and a beam going up.
        fill.color = Theme.withAlpha(col, 80); canvas.drawOval(px - 13 * s, py - 4 * s, px + 13 * s, py + 4 * s, fill)
        fill.shader = LinearGradient(0f, py, 0f, py - 55 * s, Color.argb(130, 255, 246, 208), Color.argb(0, 255, 246, 208), Shader.TileMode.CLAMP)
        canvas.drawRect(px - 5 * s, py - 55 * s, px + 5 * s, py, fill); fill.shader = null
        val bob = sin(played * 3f + pu.y) * 2f * s
        glow(canvas, px, py - 16 * s + bob, 16f * s * pulse, col, 110)
        sprite(canvas, "power${pu.power.name}", px, py - 16 * s + bob, 32f * s, 32f * s, k = s) { c -> c.scale(0.8f, 0.8f); powerIcon(c, pu.power) }
    }

    /** A power-up's picture, about 30 units across, centred on 0, 0. */
    private fun powerIcon(c: Canvas, p: LastLight.Power) {
        sp.style = Paint.Style.FILL; sp.shader = null
        fun line(colour: Int, w: Float) { sp.style = Paint.Style.STROKE; sp.strokeWidth = w; sp.color = colour }
        when (p) {
            LastLight.Power.NOVA -> {
                for (i in 0 until 8) {
                    c.save(); c.rotate(i * 45f)
                    sPath.reset(); sPath.moveTo(0f, -10f); sPath.lineTo(2.5f, -15f); sPath.lineTo(0f, -19f); sPath.lineTo(-2.5f, -15f); sPath.close()
                    sp.color = Theme.GOLD; c.drawPath(sPath, sp); outline(c, sPath, Color.rgb(138, 90, 20), 0.8f)
                    c.restore()
                }
                sp.color = Color.rgb(255, 243, 196); c.drawCircle(0f, 0f, 9f, sp)
                line(Color.rgb(138, 90, 20), 1.4f); c.drawCircle(0f, 0f, 9f, sp)
                sp.style = Paint.Style.FILL; sp.color = Color.rgb(255, 233, 168); c.drawCircle(0f, 0f, 5f, sp)
            }
            LastLight.Power.FROST -> {
                for (a in floatArrayOf(0f, 60f, 120f)) {
                    c.save(); c.rotate(a)
                    sPath.reset(); sPath.moveTo(0f, -15f); sPath.lineTo(4f, -6f); sPath.lineTo(0f, 15f); sPath.lineTo(-4f, -6f); sPath.close()
                    sp.color = Color.rgb(223, 244, 255); c.drawPath(sPath, sp); outline(c, sPath, Color.rgb(42, 106, 154), 1f)
                    c.restore()
                }
                sp.color = Color.WHITE; c.drawCircle(0f, 0f, 4f, sp)
            }
            LastLight.Power.MAGNET -> {
                line(Color.rgb(232, 90, 90), 6f)
                sPath.reset(); sPath.moveTo(-9f, 8f); sPath.lineTo(-9f, -2f); sPath.arcTo(-9f, -11f, 9f, 7f, 180f, 180f, false); sPath.lineTo(9f, 8f); c.drawPath(sPath, sp)
                sp.style = Paint.Style.FILL; sp.color = Color.rgb(232, 240, 248); c.drawRect(-12f, 6f, -6f, 12f, sp); c.drawRect(6f, 6f, 12f, 12f, sp)
            }
            LastLight.Power.POTION -> {
                sp.color = Color.rgb(160, 112, 64); c.drawRoundRect(-3f, -15f, 3f, -10f, 1f, 1f, sp)
                sPath.reset(); sPath.moveTo(-3f, -10f); sPath.lineTo(-3f, -6f); sPath.quadTo(-11f, -2f, -11f, 5f); sPath.quadTo(-11f, 13f, 0f, 13f); sPath.quadTo(11f, 13f, 11f, 5f); sPath.quadTo(11f, -2f, 3f, -6f); sPath.lineTo(3f, -10f); sPath.close()
                sp.color = Color.rgb(208, 32, 48); c.drawPath(sPath, sp); outline(c, sPath, Color.rgb(58, 10, 16), 1.3f)
                sp.color = Color.rgb(255, 80, 96); c.drawOval(-9f, 2f, 9f, 12f, sp)
                sp.color = Color.argb(150, 255, 255, 255); c.drawOval(-7f, -4.5f, -3f, 2.5f, sp)
            }
            LastLight.Power.FURY -> {
                line(Color.rgb(138, 90, 42), 2.6f); c.drawLine(-10f, 10f, 8f, -8f, sp); c.drawLine(10f, 10f, -8f, -8f, sp)
                sp.style = Paint.Style.FILL; sp.color = Color.rgb(223, 232, 240)
                sPath.reset(); sPath.moveTo(4f, -12f); sPath.quadTo(12f, -14f, 13f, -4f); sPath.quadTo(8f, -6f, 4f, -4f); sPath.close(); c.drawPath(sPath, sp); outline(c, sPath, Color.rgb(58, 58, 72), 1f)
                sPath.reset(); sPath.moveTo(-4f, -12f); sPath.quadTo(-12f, -14f, -13f, -4f); sPath.quadTo(-8f, -6f, -4f, -4f); sPath.close(); c.drawPath(sPath, sp); outline(c, sPath, Color.rgb(58, 58, 72), 1f)
            }
            LastLight.Power.GOLD -> {
                sPath.reset(); sPath.moveTo(-9f, -4f); sPath.quadTo(-12f, 12f, 0f, 12f); sPath.quadTo(12f, 12f, 9f, -4f); sPath.quadTo(4f, -8f, 0f, -6f); sPath.quadTo(-4f, -8f, -9f, -4f); sPath.close()
                sp.color = Color.rgb(160, 112, 58); c.drawPath(sPath, sp); outline(c, sPath, Color.rgb(58, 34, 16), 1.2f)
                sp.color = Color.rgb(192, 138, 82)
                sPath.reset(); sPath.moveTo(-6f, -6f); sPath.lineTo(-3f, -12f); sPath.lineTo(0f, -8f); sPath.lineTo(3f, -12f); sPath.lineTo(6f, -6f); sPath.close(); c.drawPath(sPath, sp); outline(c, sPath, Color.rgb(58, 34, 16), 1f)
                sp.color = Theme.GOLD; c.drawCircle(0f, 3f, 4f, sp); line(Color.rgb(138, 90, 20), 1f); c.drawCircle(0f, 3f, 4f, sp)
            }
        }
        sp.style = Paint.Style.FILL; sp.shader = null
    }

    // ---- WoW-like bars: your frame, the boss frame, the timer, XP and the action bar ----

    private fun hpBar(t: Float, b: Float, good: Boolean) = LinearGradient(0f, t, 0f, b,
        if (good) intArrayOf(Color.rgb(156, 255, 122), Color.rgb(34, 192, 34), Color.rgb(10, 106, 10)) else intArrayOf(Color.rgb(255, 154, 138), Color.rgb(208, 32, 32), Color.rgb(112, 16, 16)),
        floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)

    private fun panel(canvas: Canvas, l: Float, t: Float, r: Float, b: Float) {
        fill.shader = plaque(t, b); canvas.drawRoundRect(l, t, r, b, 12f, 12f, fill); fill.shader = null
        stroke.strokeWidth = 2f; stroke.color = Theme.ACCENT; canvas.drawRoundRect(l, t, r, b, 12f, 12f, stroke)
        stroke.strokeWidth = 1f; stroke.color = Theme.withAlpha(Theme.ACCENT, 90); canvas.drawRoundRect(l + 4, t + 4, r - 4, b - 4, 9f, 9f, stroke)
    }

    private fun stonePanel(canvas: Canvas, l: Float, t: Float, r: Float, b: Float, radius: Float = 6f) {
        fill.shader = stone(t, b); canvas.drawRoundRect(l, t, r, b, radius, radius, fill); fill.shader = null
        stroke.strokeWidth = 2f; stroke.color = Theme.ACCENT; canvas.drawRoundRect(l, t, r, b, radius, radius, stroke)
    }

    /** A round portrait: [who] drawn into a circle at [cx], [cy]. */
    private fun portrait(canvas: Canvas, cx: Float, cy: Float, bg: Int, who: (Canvas, Float, Float) -> Unit) {
        fill.color = Color.rgb(6, 18, 30); canvas.drawCircle(cx, cy, 34f, fill)
        canvas.save()
        path.reset(); path.addCircle(cx, cy, 31f, Path.Direction.CW); canvas.clipPath(path)
        fill.color = bg; canvas.drawCircle(cx, cy, 31f, fill)
        who(canvas, cx, cy)
        canvas.restore()
        stroke.strokeWidth = 3f; stroke.color = Theme.ACCENT; canvas.drawCircle(cx, cy, 34f, stroke)
        stroke.strokeWidth = 1f; stroke.color = Color.argb(90, 255, 243, 196); canvas.drawCircle(cx, cy, 34f, stroke)
    }

    private fun hud(canvas: Canvas) {
        val h = game.hero
        // Your frame (top left).
        stonePanel(canvas, 70f, 34f, 290f, 84f)
        title.textAlign = Paint.Align.LEFT; title.textSize = 17f; title.color = CLASS_COLOUR[h.ordinal]
        canvas.drawText(h.title, 92f, 54f, title)
        words.textAlign = Paint.Align.RIGHT; words.textSize = 14f; words.color = Theme.DIM
        canvas.drawText("level ${game.level}", 280f, 54f, words)
        val k = (game.hp / game.maxHp).coerceIn(0f, 1f)
        fill.color = Color.rgb(6, 18, 30); canvas.drawRoundRect(90f, 62f, 280f, 76f, 3f, 3f, fill)
        fill.shader = hpBar(63f, 75f, k > 0.35f); canvas.drawRoundRect(91f, 63f, 91f + 188f * k, 75f, 2.5f, 2.5f, fill); fill.shader = null
        fill.color = Color.argb(76, 255, 255, 255); canvas.drawRoundRect(91f, 63f, 91f + 188f * k, 67f, 2f, 2f, fill)
        shadowed(canvas, "${game.hp.toInt().coerceAtLeast(if (game.hp > 0) 1 else 0)} / ${game.maxHp.toInt()}", 185f, 73.5f, 11f)
        portrait(canvas, 58f, 60f, look(game.night).ground) { c, x, y -> sprite(c, "face${h.name}", x, y + 28f, 140f, 200f, lit = true) { it.scale(2f, 2f); LastLightArt.hero(it, h) } }
        // Battle Fury under your frame, with the seconds left.
        if (game.fury > 0f) {
            fill.color = Color.rgb(10, 12, 18); canvas.drawRoundRect(92f, 92f, 124f, 124f, 4f, 4f, fill)
            glow(canvas, 108f, 108f, 20f, Color.rgb(255, 138, 58), 150)
            sprite(canvas, "buffFURY", 108f, 108f, 26f, 26f) { c -> c.scale(0.8f, 0.8f); powerIcon(c, LastLight.Power.FURY) }
            stroke.strokeWidth = 1.6f; stroke.color = Color.rgb(138, 122, 90); canvas.drawRoundRect(92f, 92f, 124f, 124f, 4f, 4f, stroke)
            shadowed(canvas, "${kotlin.math.ceil(game.fury).toInt()}s", 108f, 140f, 12f)
        }

        // The boss frame (top right), while the Necromancer walks.
        game.foes.firstOrNull { it.kind == Kind.NECROMANCER }?.let { boss ->
            stonePanel(canvas, 710f, 34f, 930f, 84f)
            title.textAlign = Paint.Align.RIGHT; title.textSize = 17f; title.color = Color.rgb(255, 122, 106)
            canvas.drawText("Necromancer", 908f, 54f, title)
            val bk = (boss.hp / boss.maxHp).coerceIn(0f, 1f)
            fill.color = Color.rgb(6, 18, 30); canvas.drawRoundRect(720f, 62f, 910f, 76f, 3f, 3f, fill)
            fill.shader = hpBar(63f, 75f, false); canvas.drawRoundRect(909f - 188f * bk, 63f, 909f, 75f, 2.5f, 2.5f, fill); fill.shader = null
            portrait(canvas, 942f, 60f, Color.rgb(26, 10, 42)) { c, x, y -> sprite(c, "bossface", x, y + 22f, 150f, 170f) { it.scale(0.94f, 0.94f); LastLightArt.necromancer(it, false) } }
        }

        // Time until dawn.
        panel(canvas, 430f, 24f, 570f, 82f)
        val left = (game.night.length - game.time).coerceAtLeast(0f)
        title.textAlign = Paint.Align.CENTER; title.textSize = 30f; title.color = Theme.GOLD_TEXT
        canvas.drawText(clock((left * 1000).toLong() + 999), 500f, 58f, title)
        words.textAlign = Paint.Align.CENTER; words.textSize = 13f; words.color = Theme.DIM
        canvas.drawText("until dawn · ${number(game.kills)} foes", 500f, 76f, words)

        // The action bar, with the purple XP bar on top.
        stonePanel(canvas, 250f, 548f, 750f, 612f, 8f)
        stroke.strokeWidth = 1f; stroke.color = Color.argb(64, 255, 243, 196); canvas.drawLine(258f, 551f, 742f, 551f, stroke)
        fill.color = Color.rgb(10, 6, 18); canvas.drawRoundRect(262f, 538f, 738f, 547f, 3f, 3f, fill)
        stroke.strokeWidth = 1f; stroke.color = Color.BLACK; canvas.drawRoundRect(262f, 538f, 738f, 547f, 3f, 3f, stroke)
        val xk = (game.xp.toFloat() / game.need).coerceIn(0f, 1f)
        fill.shader = LinearGradient(0f, 539f, 0f, 546f, intArrayOf(Color.rgb(224, 176, 255), Color.rgb(160, 80, 220), Color.rgb(90, 32, 122)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(263f, 539f, 263f + 474f * xk, 546f, 2.5f, 2.5f, fill); fill.shader = null
        stroke.color = Color.argb(150, 0, 0, 0); for (i in 1..9) canvas.drawLine(262f + i * 47.6f, 539f, 262f + i * 47.6f, 546f, stroke)
        val weapons = game.taken.filter { it.weapon }; val helpers = game.taken.filter { !it.weapon }
        for (i in 0 until 8) {
            val u = if (i < 4) weapons.getOrNull(i) else helpers.getOrNull(i - 4)
            slot(canvas, u?.name, 280f + i * 55f + (if (i >= 4) 14f else 0f), 580f, 46f, u?.let { game.have(it) } ?: 0)
        }
    }

    /** White words with a black edge (numbers on bars). */
    private fun shadowed(canvas: Canvas, s: String, x: Float, y: Float, size: Float, align: Paint.Align = Paint.Align.CENTER, colour: Int = Color.WHITE) {
        bold.textAlign = align; bold.textSize = size
        bold.style = Paint.Style.STROKE; bold.strokeWidth = size * 0.24f; bold.color = Color.BLACK; canvas.drawText(s, x, y, bold)
        bold.style = Paint.Style.FILL; bold.color = colour; canvas.drawText(s, x, y, bold)
    }

    /** An action-bar slot of size [s] at [x], [y] with the picture [name] (empty when null) and its rank. */
    private fun slot(canvas: Canvas, name: String?, x: Float, y: Float, s: Float, rank: Int) {
        val l = x - s / 2; val t = y - s / 2; val r = x + s / 2; val b = y + s / 2
        fill.color = Color.rgb(10, 12, 18); canvas.drawRoundRect(l, t, r, b, 4f, 4f, fill)
        fill.shader = plaque(t + 2, b - 2); canvas.drawRoundRect(l + 2, t + 2, r - 2, b - 2, 3f, 3f, fill); fill.shader = null
        if (name != null) icon(canvas, name, x, y, s * 0.8f)
        fill.color = Color.argb(26, 255, 255, 255); canvas.drawRoundRect(l + 2, t + 2, r - 2, t + 2 + (s - 4) * 0.42f, 3f, 3f, fill)
        stroke.strokeWidth = 1.6f; stroke.color = Color.rgb(138, 122, 90); canvas.drawRoundRect(l, t, r, b, 4f, 4f, stroke)
        stroke.strokeWidth = 1f; stroke.color = Color.argb(150, 0, 0, 0); canvas.drawRoundRect(l + 3, t + 3, r - 3, b - 3, 2f, 2f, stroke)
        if (rank > 0) shadowed(canvas, "$rank", r - 4, b - 4, s * 0.36f, Paint.Align.RIGHT)
    }

    /** An upgrade's or a boost's small picture, [s] world units wide. */
    private fun icon(canvas: Canvas, name: String, x: Float, y: Float, s: Float) {
        glow(canvas, x, y, s * 0.55f, ICON_GLOW[name] ?: Theme.GOLD, 110)
        sprite(canvas, "icon$name$s", x, y, s, s) { c ->
            c.scale(s / 30f, s / 30f)
            sp.style = Paint.Style.FILL; sp.shader = null
            when (name) {
                "HAMMER" -> LastLightArt.hammerIcon(c)
                "ARROWS" -> LastLightArt.arrowsIcon(c)
                "WOLF" -> { c.translate(-4f, 4f); c.scale(0.55f, 0.55f); LastLightArt.wolf(c) }
                "BOLT" -> { sp.shader = radial(0f, 0f, 11f, Color.argb(240, 244, 224, 255), Color.argb(0, 160, 74, 255)); c.drawCircle(0f, 0f, 11f, sp); sp.shader = null
                    sp.color = Color.rgb(240, 216, 255); c.drawCircle(0f, 0f, 6.5f, sp); sp.style = Paint.Style.STROKE; sp.strokeWidth = 2.5f; sp.color = Color.rgb(160, 74, 255); c.drawCircle(0f, 0f, 6.5f, sp) }
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

    // ---- The level up: three tooltip cards ----

    private fun cardLeft(i: Int) = 115f + i * 265f

    private fun cardAt(x: Float, y: Float): Int {
        val n = game.choices?.size ?: 0
        for (i in 0 until n) if (x in cardLeft(i)..cardLeft(i) + CARD_W && y in CARD_T - 20f..CARD_B) return i
        return -1
    }

    private fun cards(canvas: Canvas) {
        val list = game.choices ?: return
        fill.color = Color.argb(150, 4, 16, 28); canvas.drawRect(0f, 0f, W, H, fill)
        fill.shader = LinearGradient(0f, 95f, 0f, 520f, intArrayOf(Color.argb(245, 44, 46, 54), Color.argb(245, 18, 19, 24)), null, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(75f, 95f, 925f, 520f, 12f, 12f, fill); fill.shader = null
        stroke.strokeWidth = 3f; stroke.color = Theme.ACCENT; canvas.drawRoundRect(75f, 95f, 925f, 520f, 12f, 12f, stroke)
        outlined(canvas, "Level ${game.level}", W / 2, 148f, 40f, Theme.GOLD, title)
        words.textAlign = Paint.Align.CENTER; words.textSize = 18f; words.color = Theme.DIM
        canvas.drawText("Choose one", W / 2, 176f, words)
        for ((i, u) in list.withIndex()) {
            val down = pressed == i
            val l = cardLeft(i); val t = CARD_T - (if (down) 6f else 0f); val r = l + CARD_W; val b = CARD_B - (if (down) 6f else 0f)
            if (down) glow(canvas, (l + r) / 2, (t + b) / 2, 190f, Theme.GOLD, 110)
            fill.color = Color.argb(245, 6, 8, 18); canvas.drawRoundRect(l, t, r, b, 6f, 6f, fill)
            stroke.strokeWidth = 2f; stroke.color = if (down) Theme.GOLD else Color.rgb(138, 138, 150); canvas.drawRoundRect(l, t, r, b, 6f, 6f, stroke)
            val now = game.have(u)
            val oneOff = u == Upgrade.HERBS || u == Upgrade.POUCH
            val quality = when { oneOff -> Color.WHITE; now == 0 -> QUALITY_NEW; now + 1 >= LastLight.MAX_LEVEL -> QUALITY_LAST; else -> QUALITY_UP }
            slot(canvas, u.name, l + 34f, t + 2f, 60f, 0)
            bold.textAlign = Paint.Align.LEFT; bold.textSize = 22f; bold.style = Paint.Style.FILL; bold.color = quality
            canvas.drawText(u.title, l + 18f, t + 76f, bold)
            words.textAlign = Paint.Align.LEFT; words.textSize = 16f; words.color = Color.WHITE
            val kind = if (u.weapon) "Weapon" else "Helper"
            canvas.drawText(when { oneOff -> "Use now"; now == 0 -> "New ${kind.lowercase()}"; else -> "$kind · Rank ${now + 1}" }, l + 18f, t + 100f, words)
            words.textSize = 18f; words.color = Color.rgb(255, 210, 0)
            var y = t + 134f
            for (row in wrap(u.detail(now + 1), CARD_W - 36f)) { canvas.drawText(row, l + 18f, y, words); y += 25f }
            words.textSize = 15f; words.color = QUALITY_NEW
            canvas.drawText("Tap to learn", l + 18f, b - 16f, words)
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

    // ---- Before the night: class, night and boosts ----

    private fun col(i: Int) = 40f + i * 310f
    private fun boostL(i: Int) = 40f + i * 186f

    private fun picker(canvas: Canvas) {
        // The chosen night's ground, dimmed, behind it all.
        ground(canvas)
        fill.color = Color.argb(205, 4, 16, 28); canvas.drawRect(0f, 0f, W, H, fill)
        title.textAlign = Paint.Align.LEFT; title.textSize = 32f; title.color = Theme.GOLD
        canvas.drawText("Choose your class", 44f, 54f, title)
        title.textAlign = Paint.Align.RIGHT; title.textSize = 24f; title.color = Theme.GOLD_TEXT
        canvas.drawText("$gold gold", 956f, 52f, title)
        orb(canvas, 956f - title.measureText("$gold gold") - 16f, 44f, 7f, Color.rgb(255, 243, 196), Theme.GOLD, Color.rgb(154, 106, 26))

        // The three classes.
        val chosen = hero
        for (h in Hero.entries) {
            val l = col(h.ordinal); val r = l + COL_W; val t = CLASS_T; val b = CLASS_B
            val on = h == chosen; val down = pressed == 10 + h.ordinal
            if (on) glow(canvas, (l + r) / 2, (t + b) / 2, 200f, Theme.GOLD, 60)
            fill.shader = LinearGradient(0f, t, 0f, b, intArrayOf(Color.rgb(24, 58, 90), Color.rgb(11, 29, 46)), null, Shader.TileMode.CLAMP)
            canvas.drawRoundRect(l, t, r, b, 16f, 16f, fill); fill.shader = null
            stroke.strokeWidth = if (on) 3f else 2f; stroke.color = if (on || down) Theme.GOLD else Theme.EDGE
            canvas.drawRoundRect(l, t, r, b, 16f, 16f, stroke)
            val cx = (l + r) / 2
            fill.color = Color.rgb(27, 51, 38); canvas.drawOval(cx - 92, t + 128, cx + 92, t + 154, fill)
            sprite(canvas, "card${h.name}", cx, t + 100f, HERO_BOX_W * 1.6f, HERO_BOX_H * 1.6f, lit = true) { c -> c.scale(1.6f, 1.6f); LastLightArt.hero(c, h) }
            title.textAlign = Paint.Align.CENTER; title.textSize = 28f; title.color = CLASS_COLOUR[h.ordinal]
            canvas.drawText(h.title, cx, t + 196f, title)
            words.textAlign = Paint.Align.CENTER; words.textSize = 16f; words.color = Theme.DIM
            canvas.drawText(CLASS_LINE[h.ordinal], cx, t + 218f, words)
            words.textAlign = Paint.Align.LEFT; words.textSize = 15.5f; words.color = Color.rgb(255, 210, 0)
            for ((i, trait) in h.traits.withIndex()) canvas.drawText("• $trait", l + 18f, t + 243f + i * 20f, words)
        }

        // The nights: open ones can be picked, the next one is locked until the one before sees dawn.
        val now = night
        for (n in Night.entries) {
            val l = col(n.ordinal); val r = l + COL_W; val t = NIGHT_T; val b = NIGHT_B
            val isOpen = n.ordinal < open; val on = n == now; val down = pressed == 20 + n.ordinal
            fill.color = if (isOpen) Color.rgb(19, 47, 74) else Color.rgb(14, 26, 38)
            canvas.drawRoundRect(l, t, r, b, 12f, 12f, fill)
            stroke.strokeWidth = if (on) 3f else 2f; stroke.color = if (on || down) Theme.GOLD else Theme.STROKE
            canvas.drawRoundRect(l, t, r, b, 12f, 12f, stroke)
            val cx = l + 34f; val cy = (t + b) / 2
            fill.color = look(n).ground; canvas.drawCircle(cx, cy, 22f, fill)
            stroke.strokeWidth = 2f; stroke.color = if (isOpen) Theme.ACCENT else Theme.STROKE; canvas.drawCircle(cx, cy, 22f, stroke)
            if (isOpen) { title.textAlign = Paint.Align.CENTER; title.textSize = 22f; title.color = Theme.GOLD; canvas.drawText(ROMAN[n.ordinal], cx, cy + 8f, title) }
            else lock(canvas, cx, cy)
            title.textAlign = Paint.Align.LEFT; title.textSize = 18f; title.color = if (isOpen) Theme.GOLD_TEXT else Theme.DIM
            canvas.drawText(n.title, l + 66f, t + 28f, title)
            words.textAlign = Paint.Align.LEFT; words.textSize = 14.5f
            val cleared = n.ordinal + 1 < open
            val nb = nightBest(n)
            words.color = if (cleared) Theme.GOOD else Theme.DIM
            canvas.drawText(when {
                !isOpen -> "See dawn in night ${ROMAN[n.ordinal - 1]} to open"
                cleared -> "${clock(n.length.toLong() * 1000)} · dawn seen ✓"
                nb > 0 -> "${clock(n.length.toLong() * 1000)} · best ${clock(nb * 1000L)}"
                else -> "${clock(n.length.toLong() * 1000)} until dawn"
            }, l + 66f, t + 50f, words)
        }

        // Boosts, then Start.
        for ((i, b) in Boost.entries.withIndex()) {
            val l = boostL(i); val r = l + BOOST_W; val t = BOOST_T; val bt = BOOST_B
            val lv = game.shop[i]
            val full = lv >= LastLight.MAX_LEVEL
            val afford = !full && gold >= b.cost(lv)
            fill.color = Color.rgb(19, 47, 74); canvas.drawRoundRect(l, t, r, bt, 12f, 12f, fill)
            stroke.strokeWidth = 2f; stroke.color = if (pressed == 30 + i && afford) Theme.GOLD else Theme.STROKE; canvas.drawRoundRect(l, t, r, bt, 12f, 12f, stroke)
            slot(canvas, b.name, l + 26f, (t + bt) / 2, 38f, lv)
            title.textAlign = Paint.Align.LEFT; title.textSize = 15f; title.color = Theme.GOLD_TEXT
            canvas.drawText(b.title, l + 52f, t + 24f, title)
            words.textAlign = Paint.Align.LEFT; words.textSize = 13.5f; words.color = if (afford) Theme.ACCENT else Theme.DIM
            canvas.drawText(if (full) "Max" else "${b.cost(lv)} gold", l + 52f, t + 44f, words)
        }
        button(canvas, START_L, BOOST_T - 4f, START_R, BOOST_B + 4f, "▶ Start", primary = true, dim = false, down = pressed == 99)
        words.textAlign = Paint.Align.CENTER; words.textSize = 14f; words.color = Theme.DIM
        canvas.drawText("Tap a class and a night. Gold from your nights buys boosts that last.", W / 2, 596f, words)
    }

    private fun lock(canvas: Canvas, x: Float, y: Float) {
        stroke.strokeWidth = 3f; stroke.color = Theme.DIM
        box.set(x - 6f, y - 13f, x + 6f, y + 1f); canvas.drawArc(box, 180f, 180f, false, stroke)
        fill.color = Theme.DIM; canvas.drawRoundRect(x - 9f, y - 5f, x + 9f, y + 9f, 2f, 2f, fill)
        fill.color = Color.rgb(14, 26, 38); canvas.drawCircle(x, y + 2f, 2f, fill)
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
        val l = 250f; val t = 150f; val r = 750f; val b = 470f
        panel(canvas, l, t, r, b)
        outlined(canvas, if (game.won) "Dawn breaks!" else "The light went out", W / 2, t + 52, 38f, Theme.GOLD, title)
        words.textAlign = Paint.Align.CENTER; words.textSize = 19f; words.color = Theme.DIM
        canvas.drawText(if (game.won) "You kept the light burning through ${game.night.title}" else "The night was too long this time", W / 2, t + 82, words)
        val survived = game.time.toInt()
        val newBest = survived >= best && survived > 0
        val rows = listOf(
            Triple("Survived", clock(survived * 1000L), if (newBest) "best!" else ""),
            Triple("Foes", number(game.kills), ""),
            Triple("Level", "${game.level}", ""),
            Triple("Gold", "+${game.gold}", "for boosts"),
        )
        for ((i, row) in rows.withIndex()) {
            val y = t + 122 + i * 30
            words.textAlign = Paint.Align.LEFT; words.textSize = 21f; words.color = Theme.TEXT; canvas.drawText(row.first, l + 50, y, words)
            title.textAlign = Paint.Align.RIGHT; title.textSize = 22f; title.color = Theme.GOLD_TEXT; canvas.drawText(row.second, l + 310, y, title)
            words.textSize = 16f; words.color = Theme.ACCENT; canvas.drawText(row.third, l + 330, y, words)
        }
        val next = opened
        when {
            next != null -> outlined(canvas, "New night open: ${next.title}!", W / 2, t + 270, 22f, QUALITY_NEW, title)
            game.won && game.night.next == null -> outlined(canvas, "You saw every dawn!", W / 2, t + 270, 22f, QUALITY_LAST, title)
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
        /** The middle of the screen, and where the hero's feet stand. */
        private const val HX = W / 2
        private const val HY = H / 2
        private const val GY = 330f
        /**
         * The tilt of the ground (34°, fairly top-down), how far the eye is (far things are
         * smaller) and the zoom (Jesse 2026-10-27: more overview, less zoomed in).
         */
        private val TILT_SIN = sin(Math.toRadians(34.0)).toFloat()
        private val TILT_COS = cos(Math.toRadians(34.0)).toFloat()
        private const val EYE = 850f
        private const val ZOOM = 0.7f
        /** How much ground is drawn ahead (up), behind (down) and to each side of the hero, in world units. */
        private const val FAR = 1250f
        private const val NEAR = 420f
        private const val WIDE = 1500f
        private const val TILE = 1000f
        private const val STICK = 70f
        /** The hero is drawn this much bigger than the foes' scale; the picture's box; where the feet are in it. */
        private const val HERO_K = 1.3f
        private const val HERO_BOX_W = 72f
        private const val HERO_BOX_H = 104f
        private const val FOOT_HERO = 25f
        private const val WOLF_K = 1.2f
        private const val DECO_K = 1.25f
        /** Kinds of standing things. */
        private const val HERO = 1
        private const val WOLF = 2
        private const val DECO = 3
        // The picker's rows.
        private const val COL_W = 300f
        private const val CLASS_T = 72f
        private const val CLASS_B = 380f
        private const val NIGHT_T = 394f
        private const val NIGHT_B = 456f
        private const val BOOST_T = 472f
        private const val BOOST_B = 528f
        private const val BOOST_W = 176f
        private const val START_L = 800f
        private const val START_R = 960f
        // The level-up cards.
        private const val CARD_W = 236f
        private const val CARD_T = 210f
        private const val CARD_B = 494f
        private val ROMAN = arrayOf("I", "II", "III")
        /** WoW's class colours: Paladin pink, Mage light blue, Hunter green. */
        private val CLASS_COLOUR = intArrayOf(Color.rgb(245, 140, 186), Color.rgb(63, 199, 235), Color.rgb(170, 211, 114))
        private val CLASS_LINE = arrayOf("Tough, slow, holy", "Fragile, strong spells", "Quick, with a wolf")
        /** Tooltip colours: green new, blue stronger, purple the last rank. */
        private val QUALITY_NEW = Color.rgb(30, 255, 0)
        private val QUALITY_UP = Color.rgb(42, 140, 255)
        private val QUALITY_LAST = Color.rgb(176, 72, 248)
        private val GEMS = listOf(
            Triple(Color.rgb(224, 251, 255), Theme.TEAL, Color.rgb(20, 90, 120)),
            Triple(Color.rgb(232, 255, 224), Theme.GOOD, Color.rgb(29, 106, 52)),
            Triple(Color.rgb(255, 230, 224), Color.rgb(255, 110, 110), Color.rgb(138, 32, 32)),
        )
        private val GEM_GLOW = intArrayOf(Theme.TEAL, Theme.GOOD, Color.rgb(255, 110, 110))
        private val ICON_GLOW = mapOf("BOLT" to Color.rgb(192, 122, 255), "FROST" to Theme.TEAL, "FIRE" to Color.rgb(255, 138, 42), "HAMMER" to Theme.GOLD,
            "STEW" to Theme.BAD, "TOUGH" to Theme.BAD, "HERBS" to Theme.GOOD, "MAGNET" to Theme.BAD, "REACH" to Theme.BAD, "WOLF" to Color.rgb(170, 185, 200), "ARROWS" to Color.rgb(170, 211, 114))
    }
}
