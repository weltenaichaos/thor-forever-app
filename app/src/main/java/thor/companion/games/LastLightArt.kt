package thor.companion.games

import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import thor.companion.Theme

/**
 * Last Light's figures (pictures: mockups/survivors-v3, Jesse 2026-10-27: "more WoW-like", our
 * own drawings): a knight in plate with the Forever tabard, ghouls, vampire bats, skeleton
 * warriors, banshees and the Necromancer. Each is drawn centred on 0, 0 in world units, in the
 * same shapes as the mockup (its path strings are used as they are). The board paints each one
 * once into a small picture and then only copies it.
 */
internal object LastLightArt {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val OUTLINE = Color.rgb(20, 12, 6)
    private val WHITE = Color.WHITE

    // ---- Colours of the mockup ----
    private fun steel(l: Float, t: Float, r: Float, b: Float) = LinearGradient(l, t, r, b, intArrayOf(Color.rgb(238, 244, 250), Color.rgb(159, 176, 194), Color.rgb(74, 88, 104)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
    private fun gold(t: Float, b: Float) = LinearGradient(0f, t, 0f, b, intArrayOf(Color.rgb(255, 243, 196), Color.rgb(224, 176, 74), Color.rgb(138, 90, 20)), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
    private fun radial(cx: Float, cy: Float, r: Float, vararg c: Int) = RadialGradient(cx, cy, r, c, null, Shader.TileMode.CLAMP)
    private fun bone(cx: Float, cy: Float, r: Float) = RadialGradient(cx, cy, r, intArrayOf(Color.rgb(255, 250, 240), Color.rgb(217, 207, 182), Color.rgb(122, 110, 84)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    private fun robe(t: Float, b: Float) = LinearGradient(0f, t, 0f, b, intArrayOf(Color.rgb(106, 58, 154), Color.rgb(58, 26, 90), Color.rgb(26, 10, 42)), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    private fun glowing(cx: Float, cy: Float, r: Float, colour: Int, alpha: Int = 220) =
        RadialGradient(cx, cy, r, intArrayOf(Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour)), 0), null, Shader.TileMode.CLAMP)
    private val FEL = Color.rgb(122, 255, 58)
    private val ICE = Color.rgb(159, 216, 255)

    // ---- A small reader for the mockup's path strings (M L H V Q C Z, upper and lower case) ----
    private val cache = HashMap<String, Path>()
    fun svg(d: String): Path = cache.getOrPut(d) {
        val path = Path()
        val tokens = Regex("[MmLlHhVvQqCcZz]|-?\\d*\\.?\\d+").findAll(d).map { it.value }.toList()
        var i = 0; var cmd = 'M'; var x = 0f; var y = 0f; var sx = 0f; var sy = 0f
        fun n() = tokens[i++].toFloat()
        while (i < tokens.size) {
            val t = tokens[i]
            if (t[0].isLetter()) { cmd = t[0]; i++; if (cmd == 'Z' || cmd == 'z') { path.close(); x = sx; y = sy; continue } }
            val rel = cmd.isLowerCase()
            val bx = if (rel) x else 0f; val by = if (rel) y else 0f
            when (cmd.uppercaseChar()) {
                'M' -> { x = bx + n(); y = by + n(); path.moveTo(x, y); sx = x; sy = y; cmd = if (rel) 'l' else 'L' }
                'L' -> { x = bx + n(); y = by + n(); path.lineTo(x, y) }
                'H' -> { x = bx + n(); path.lineTo(x, y) }
                'V' -> { y = (if (rel) y else 0f) + n(); path.lineTo(x, y) }
                'Q' -> { val ax = bx + n(); val ay = by + n(); x = bx + n(); y = by + n(); path.quadTo(ax, ay, x, y) }
                'C' -> { val ax = bx + n(); val ay = by + n(); val cx = bx + n(); val cy = by + n(); x = bx + n(); y = by + n(); path.cubicTo(ax, ay, cx, cy, x, y) }
                else -> i++
            }
        }
        path
    }

    private fun fill(c: Canvas, d: String, colour: Int = WHITE, shader: Shader? = null) {
        p.style = Paint.Style.FILL; p.shader = shader; p.color = colour; c.drawPath(svg(d), p); p.shader = null
    }
    private fun line(c: Canvas, d: String, width: Float, colour: Int = OUTLINE, shader: Shader? = null) {
        p.style = Paint.Style.STROKE; p.strokeWidth = width; p.strokeCap = Paint.Cap.ROUND; p.strokeJoin = Paint.Join.ROUND
        p.shader = shader; p.color = colour; c.drawPath(svg(d), p); p.shader = null; p.style = Paint.Style.FILL
    }
    /** Filled, then outlined in dark ink. */
    private fun shape(c: Canvas, d: String, colour: Int, shader: Shader?, outline: Float) { fill(c, d, colour, shader); line(c, d, outline) }
    /** A limb: dark ink under a thinner coloured stroke. */
    private fun limb(c: Canvas, d: String, width: Float, colour: Int, shader: Shader? = null) { line(c, d, width + 2.2f); line(c, d, width, colour, shader) }
    private fun circle(c: Canvas, x: Float, y: Float, r: Float, colour: Int, shader: Shader? = null, outline: Float = 0f) {
        p.style = Paint.Style.FILL; p.shader = shader; p.color = colour; c.drawCircle(x, y, r, p); p.shader = null
        if (outline > 0) { p.style = Paint.Style.STROKE; p.strokeWidth = outline; p.color = OUTLINE; c.drawCircle(x, y, r, p); p.style = Paint.Style.FILL }
    }
    private fun oval(c: Canvas, x: Float, y: Float, rx: Float, ry: Float, colour: Int) { p.style = Paint.Style.FILL; p.shader = null; p.color = colour; c.drawOval(x - rx, y - ry, x + rx, y + ry, p) }
    private fun glow(c: Canvas, x: Float, y: Float, r: Float, colour: Int, alpha: Int = 220) { p.style = Paint.Style.FILL; p.shader = glowing(x, y, r, colour, alpha); c.drawCircle(x, y, r, p); p.shader = null }
    private fun shadow(c: Canvas, rx: Float, ry: Float, y: Float) {
        p.style = Paint.Style.FILL; p.shader = null; p.maskFilter = BlurMaskFilter(2.2f, BlurMaskFilter.Blur.NORMAL)
        p.color = Color.argb(115, 0, 0, 0); c.drawOval(-rx, y - ry, rx, y + ry, p); p.maskFilter = null
    }
    private fun eyes(c: Canvas, colour: Int, glowColour: Int, dx: Float, y: Float, r: Float) {
        for (ex in floatArrayOf(-dx, dx)) { glow(c, ex, y, r * 2.4f, glowColour); oval(c, ex, y, r, r * 1.25f, colour) }
    }

    /** The Lightbearer: plate, round shoulder guards, the Forever tabard, a lantern staff. */
    fun hero(c: Canvas) {
        shadow(c, 20f, 6f, 25f)
        shape(c, "M-14 -6 Q-22 10 -18 24 Q0 28 18 24 Q22 10 14 -6Z", WHITE, LinearGradient(0f, -6f, 0f, 26f, Color.rgb(42, 90, 138), Color.rgb(13, 34, 56), Shader.TileMode.CLAMP), 1.6f)
        for (lx in floatArrayOf(-9f, 2f)) {
            p.shader = steel(lx, 14f, lx + 7, 25f); p.color = WHITE; c.drawRoundRect(lx, 14f, lx + 7, 25f, 2f, 2f, p); p.shader = null
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f; p.color = OUTLINE; c.drawRoundRect(lx, 14f, lx + 7, 25f, 2f, 2f, p); p.style = Paint.Style.FILL
        }
        shape(c, "M-11 -6 Q0 -10 11 -6 L10 12 Q0 15 -10 12Z", WHITE, steel(-11f, -10f, 11f, 15f), 1.5f)
        shape(c, "M-7 -2 L7 -2 L6 20 L0 23 L-6 20Z", WHITE, LinearGradient(0f, -2f, 0f, 23f, Color.rgb(63, 134, 200), Color.rgb(23, 60, 102), Shader.TileMode.CLAMP), 1.3f)
        line(c, "M-5.7 0 L5.7 0 L4.9 17 L0 19.6 L-4.9 17Z", 1.2f, WHITE, gold(0f, 20f))
        // Our ∞ on the tabard.
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f; p.color = Theme.GOLD
        c.drawOval(-4.6f, 6.6f, -0.2f, 10f, p); c.drawOval(0.2f, 6.6f, 4.6f, 10f, p); p.style = Paint.Style.FILL
        p.color = Color.rgb(90, 54, 24); c.drawRoundRect(-11f, 7f, 11f, 11f, 1.5f, 1.5f, p)
        p.shader = gold(6.5f, 11.5f); c.drawRoundRect(-2.5f, 6.5f, 2.5f, 11.5f, 1f, 1f, p); p.shader = null
        // Left arm, the staff, the right arm.
        limb(c, "M-16 0 Q-20 8 -16 14", 5f, WHITE, steel(-20f, 0f, -14f, 14f))
        circle(c, -16f, 15f, 3.6f, WHITE, steel(-20f, 11f, -12f, 19f), 1.2f)
        line(c, "M20 -28 L16 20", 4.5f); line(c, "M20 -28 L16 20", 2.6f, Color.rgb(138, 90, 42))
        limb(c, "M16 0 Q20 6 17 11", 5f, WHITE, steel(15f, 0f, 21f, 11f))
        circle(c, 17.5f, 9f, 3.8f, WHITE, steel(14f, 5f, 21f, 13f), 1.2f)
        // The lantern (its light is drawn live by the board).
        shape(c, "M16 -40 L26 -40 L25 -28 L17 -28Z", Color.rgb(255, 240, 184), null, 1.4f)
        shape(c, "M15 -42 L27 -42 L21 -47Z", WHITE, gold(-47f, -42f), 1.2f)
        p.shader = gold(-29f, -26f); c.drawRoundRect(15f, -29f, 27f, -26f, 1f, 1f, p); p.shader = null
        oval(c, 21f, -34f, 2.4f, 3.6f, WHITE)
        // Shoulder guards with a blue stone.
        for (sx in floatArrayOf(-13f, 13f)) {
            circle(c, sx, -6f, 9.5f, WHITE, radial(sx - 3f, -9f, 15f, Color.rgb(244, 248, 255), Color.rgb(143, 162, 184), Color.rgb(54, 66, 79)), 1.6f)
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.6f; p.shader = gold(-14f, 2f); c.drawCircle(sx, -6f, 7.6f, p); p.shader = null; p.style = Paint.Style.FILL
            circle(c, sx, -7f, 2.4f, Theme.TEAL, null, 0.8f)
        }
        // The helmet, its gold crest, and the eyes behind the visor.
        shape(c, "M-9 -12 Q-10 -27 0 -28 Q10 -27 9 -12 Q0 -8 -9 -12Z", WHITE, steel(-10f, -28f, 10f, -9f), 1.6f)
        shape(c, "M0 -28 Q2 -36 -2 -40 Q8 -36 4 -27Z", WHITE, gold(-40f, -27f), 1f)
        line(c, "M-6 -19 L6 -19 M0 -19 L0 -12", 2.6f, Color.rgb(10, 10, 20))
        eyes(c, Color.rgb(255, 246, 208), Theme.GOLD, 3.5f, -19f, 1f)
    }

    /** A ghoul: hunched, long clawed arms reaching forward, ribs and yellow eyes. */
    fun ghoul(c: Canvas, flash: Boolean) {
        shadow(c, 15f, 4.5f, 19f)
        val skin = if (flash) null else radial(-4f, -6f, 26f, Color.rgb(196, 208, 160), Color.rgb(125, 140, 92), Color.rgb(47, 58, 34))
        val claw = if (flash) WHITE else Color.rgb(232, 224, 192)
        line(c, "M-7 10 L-9 19 L-4 19 M5 10 L7 19 L2 19", 4.5f); line(c, "M-7 10 L-9 19 L-4 19 M5 10 L7 19 L2 19", 2.6f, if (flash) WHITE else Color.rgb(106, 122, 76))
        shape(c, "M-11 12 Q-15 -6 -4 -12 Q8 -14 11 -2 Q12 8 8 12Z", WHITE, skin, 1.6f)
        shape(c, "M-9 8 L9 8 L7 14 L3 11 L0 15 L-3 11 L-7 14Z", if (flash) WHITE else Color.rgb(90, 68, 48), null, 1.1f)
        if (!flash) { line(c, "M-6 -4 Q0 -2 6 -4 M-6 0 Q0 2 6 0 M-5 4 Q0 6 5 4", 1.2f, Color.rgb(58, 70, 40)); line(c, "M-4 -12 l2 -2 l2 2 l2 -2 l2 2", 1.2f, Color.rgb(58, 70, 40)) }
        limb(c, "M-9 -6 Q-18 2 -14 12", 3.4f, WHITE, skin); line(c, "M-16 12 l-2 4 M-14 13 l-1 4.5 M-12 12.5 l0 4", 1.4f, claw)
        limb(c, "M9 -6 Q18 0 15 10", 3.4f, WHITE, skin); line(c, "M14 10 l-1 4.5 M16 10 l1 4.5 M18 9 l2 3.5", 1.4f, claw)
        shape(c, "M-8 -11 Q-9 -23 0 -23 Q9 -23 8 -11 Q6 -5 0 -5 Q-6 -5 -8 -11Z", WHITE, if (flash) null else radial(-3f, -18f, 16f, Color.rgb(196, 208, 160), Color.rgb(125, 140, 92), Color.rgb(47, 58, 34)), 1.6f)
        if (flash) return
        line(c, "M-7 -21 Q0 -26 7 -21", 1.4f, Color.rgb(58, 70, 40))
        eyes(c, Color.rgb(255, 246, 160), Theme.GOLD, 3.4f, -15f, 1.7f)
        fill(c, "M-4.5 -9 L4.5 -9 L3 -6.5 L-3 -6.5Z", Color.rgb(26, 10, 6))
        line(c, "M-3 -9 l1 2 l1 -2 l1 2 l1 -2 l1 2", 0.9f, claw)
    }

    /** A vampire bat with big ears and fangs; [up] is the wings' upper beat. */
    fun bat(c: Canvas, up: Boolean, flash: Boolean) {
        p.maskFilter = BlurMaskFilter(2f, BlurMaskFilter.Blur.NORMAL); oval(c, 0f, 26f, 14f, 4f, Color.argb(80, 0, 0, 0)); p.maskFilter = null
        val f = if (up) -8f else 4f
        val wing = if (flash) null else radial(0f, -8f, 36f, Color.rgb(179, 132, 224), Color.rgb(93, 50, 144), Color.rgb(36, 16, 64))
        shape(c, "M0 2 Q-8 ${-14 + f} -32 ${-12 + f} Q-26 -4 -27 8 Q-20 1 -15 9 Q-10 2 -6 8 Q0 4 6 8 Q10 2 15 9 Q20 1 27 8 Q26 -4 32 ${-12 + f} Q8 ${-14 + f} 0 2Z", WHITE, wing, 1.6f)
        if (!flash) line(c, "M-6 ${-9 + f / 2} L-15 8 M-12 ${-11 + f / 2} L-27 8 M6 ${-9 + f / 2} L15 8 M12 ${-11 + f / 2} L27 8", 1.1f, Color.argb(200, 42, 16, 64))
        p.shader = wing; p.color = WHITE; c.drawOval(-7f, -6f, 7f, 10f, p); p.shader = null
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.3f; p.color = OUTLINE; c.drawOval(-7f, -6f, 7f, 10f, p); p.style = Paint.Style.FILL
        shape(c, "M-6 -3 L-7 -14 L-1 -5Z M6 -3 L7 -14 L1 -5Z", if (flash) WHITE else Color.rgb(122, 74, 176), null, 1.1f)
        if (flash) return
        fill(c, "M-5 -5 L-6 -11 L-2.5 -5.5Z M5 -5 L6 -11 L2.5 -5.5Z", Color.rgb(224, 160, 192))
        eyes(c, Color.rgb(255, 208, 208), Theme.BAD, 2.6f, 0f, 1.5f)
        fill(c, "M-2 4.5 L-1.2 7.5 L-0.4 4.5Z M0.4 4.5 L1.2 7.5 L2 4.5Z", WHITE)
    }

    /** A skeleton warrior: rusty cap, round wooden shield, notched sword, icy eyes. */
    fun skeleton(c: Canvas, flash: Boolean) {
        shadow(c, 14f, 4f, 21f)
        val b = if (flash) WHITE else Color.rgb(217, 207, 182)
        line(c, "M-5 20 L-4 8 M5 20 L4 8", 5f); line(c, "M-5 20 L-4 8 M5 20 L4 8", 3f, b)
        shape(c, "M-7 -4 Q0 -6 7 -4 L6 9 Q0 11 -6 9Z", WHITE, if (flash) null else bone(-2f, -2f, 14f), 1.4f)
        if (!flash) line(c, "M-5 -1 L5 -1 M-5 3 L5 3 M-4 6.5 L4 6.5 M0 -4 L0 9", 1.2f, Color.rgb(90, 78, 58))
        line(c, "M8 -2 L24 -20", 5f); line(c, "M8 -2 L24 -20", 3.2f, WHITE, if (flash) null else steel(8f, -20f, 24f, -2f))
        line(c, "M5 1 L11 -5", 4f, if (flash) WHITE else Color.rgb(90, 54, 24)); line(c, "M4 -4 L12 2", 2.5f, WHITE, if (flash) null else gold(-4f, 2f))
        circle(c, -12f, 3f, 11f, WHITE, if (flash) null else LinearGradient(-23f, -8f, -1f, 14f, Color.rgb(176, 122, 68), Color.rgb(90, 54, 24), Shader.TileMode.CLAMP), 1.6f)
        if (!flash) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.2f; p.color = Color.rgb(106, 110, 116); c.drawCircle(-12f, 3f, 9.4f, p); p.style = Paint.Style.FILL
            line(c, "M-19 -3 L-5 9 M-19 9 L-5 -3", 1f, Color.argb(150, 58, 36, 16))
            circle(c, -12f, 3f, 3.2f, WHITE, steel(-15f, 0f, -9f, 6f), 1f)
        }
        shape(c, "M-8 -9 Q-9 -21 0 -21 Q9 -21 8 -9 Q7 -4 3 -3 L-3 -3 Q-7 -4 -8 -9Z", WHITE, if (flash) null else bone(-3f, -15f, 16f), 1.5f)
        shape(c, "M-9 -13 Q-9 -25 0 -25 Q9 -25 9 -13 Z", WHITE, if (flash) null else LinearGradient(-9f, -25f, 9f, -13f, intArrayOf(Color.rgb(184, 138, 90), Color.rgb(122, 84, 52), Color.rgb(58, 36, 20)), null, Shader.TileMode.CLAMP), 1.4f)
        line(c, "M-10 -13 L10 -13", 2.2f)
        if (flash) return
        circle(c, -5f, -19f, 0.9f, Color.rgb(217, 164, 106)); circle(c, 5f, -19f, 0.9f, Color.rgb(217, 164, 106))
        oval(c, -3.5f, -9.5f, 2.6f, 2.8f, Color.rgb(20, 6, 10)); oval(c, 3.5f, -9.5f, 2.6f, 2.8f, Color.rgb(20, 6, 10))
        glow(c, -3.5f, -9.5f, 3.2f, ICE); glow(c, 3.5f, -9.5f, 3.2f, ICE)
        circle(c, -3.5f, -9.5f, 1.1f, Color.rgb(191, 243, 255)); circle(c, 3.5f, -9.5f, 1.1f, Color.rgb(191, 243, 255))
        line(c, "M-3 -4.5 l1 1.5 l1 -1.5 l1 1.5 l1 -1.5 l1 1.5", 0.9f, Color.rgb(90, 78, 58))
    }

    /** A banshee: a wailing spirit with flowing hair and a tail that fades out. */
    fun banshee(c: Canvas, flash: Boolean) {
        glow(c, 0f, 0f, 26f, Theme.TEAL, 90)
        val ink = Color.argb(180, 42, 74, 122)
        fill(c, "M-8 -18 Q-22 -16 -26 -2 Q-20 -8 -14 -6 Q-22 4 -18 14 Q-12 2 -8 -2Z", if (flash) WHITE else Color.argb(180, 207, 230, 255))
        line(c, "M-8 -18 Q-22 -16 -26 -2 Q-20 -8 -14 -6 Q-22 4 -18 14 Q-12 2 -8 -2Z", 1f, ink)
        val body = "M-12 -6 Q-14 10 -6 18 Q-2 30 4 36 Q4 24 8 18 Q14 8 12 -6 Q0 -2 -12 -6Z"
        fill(c, body, WHITE, if (flash) null else LinearGradient(0f, -6f, 0f, 36f, intArrayOf(Color.argb(242, 230, 244, 255), Color.argb(216, 122, 180, 232), Color.argb(0, 42, 90, 154)), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP))
        line(c, body, 1.2f, ink)
        line(c, "M-11 -4 Q-22 -2 -26 -12 M11 -4 Q22 -2 26 -12", 3.2f, if (flash) WHITE else Color.rgb(191, 224, 255))
        line(c, "M-26 -12 l-3 -2 M-26 -12 l-1 -4 M26 -12 l3 -2 M26 -12 l1 -4", 1.4f, Color.rgb(230, 244, 255))
        p.color = Color.rgb(230, 244, 255); c.drawOval(-8f, -24.5f, 8f, -5.5f, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f; p.color = ink; c.drawOval(-8f, -24.5f, 8f, -5.5f, p); p.style = Paint.Style.FILL
        fill(c, "M-8 -18 Q0 -30 8 -18 Q10 -26 2 -27 Q-8 -28 -8 -18Z", Color.rgb(207, 230, 255)); line(c, "M-8 -18 Q0 -30 8 -18 Q10 -26 2 -27 Q-8 -28 -8 -18Z", 1f, ink)
        if (flash) return
        oval(c, -3f, -16f, 2f, 2.6f, Color.rgb(10, 26, 58)); oval(c, 3f, -16f, 2f, 2.6f, Color.rgb(10, 26, 58)); oval(c, 0f, -9.5f, 2.6f, 3.6f, Color.rgb(10, 26, 58))
        circle(c, -3f, -16f, 1f, Color.rgb(159, 240, 255)); circle(c, 3f, -16f, 1f, Color.rgb(159, 240, 255))
    }

    /** The Necromancer: purple robes, a bone crown, a skull staff with green flame. */
    fun necromancer(c: Canvas, flash: Boolean) {
        glow(c, 0f, 0f, 74f, FEL, 70)
        shadow(c, 34f, 8f, 48f)
        val r = if (flash) null else robe(-50f, 44f)
        val boneS = if (flash) null else bone(-2f, -4f, 10f)
        shape(c, "M-28 -6 Q-36 22 -30 40 Q-18 34 -12 42 Q-4 34 0 44 Q4 34 12 42 Q18 34 30 40 Q36 22 28 -6Z", WHITE, r, 2f)
        if (!flash) line(c, "M-26 34 Q-18 30 -12 38 Q-4 30 0 40 Q4 30 12 38 Q18 30 26 34", 1.6f, Color.argb(200, 122, 255, 58))
        fill(c, "M-10 -2 L10 -2 L8 34 L0 38 L-8 34Z", if (flash) WHITE else Color.rgb(42, 16, 62))
        line(c, "M-10 -2 L10 -2 L8 34 L0 38 L-8 34Z", 1.6f, WHITE, gold(-2f, 38f))
        if (!flash) { fill(c, "M0 6 L4 12 L0 18 L-4 12Z", Color.argb(216, 122, 255, 58)); fill(c, "M0 22 L3 26 L0 30 L-3 26Z", Color.argb(150, 122, 255, 58)) }
        shape(c, "M-34 -16 Q-30 -26 -18 -24 L-12 -8 Q-26 -4 -34 -16Z M34 -16 Q30 -26 18 -24 L12 -8 Q26 -4 34 -16Z", WHITE, r, 1.8f)
        shape(c, "M-34 -16 L-38 -24 L-30 -22Z M34 -16 L38 -24 L30 -22Z", Color.rgb(217, 207, 182), null, 1.2f)
        circle(c, -22f, -18f, 5f, WHITE, if (flash) null else bone(-24f, -20f, 7f), 1.2f)
        circle(c, 22f, -18f, 5f, WHITE, if (flash) null else bone(20f, -20f, 7f), 1.2f)
        shape(c, "M-16 -14 Q-18 -40 0 -44 Q18 -40 16 -14 Q0 -8 -16 -14Z", WHITE, r, 2f)
        fill(c, "M-10 -16 Q-10 -34 0 -34 Q10 -34 10 -16 Q6 -12 0 -12 Q-6 -12 -10 -16Z", Color.rgb(10, 4, 16))
        shape(c, "M-7 -22 Q-7 -32 0 -32 Q7 -32 7 -22 Q6 -17 3 -16 L-3 -16 Q-6 -17 -7 -22Z", WHITE, boneS, 1f)
        shape(c, "M-8 -42 L-6 -50 L-2 -44 L0 -52 L2 -44 L6 -50 L8 -42Z", WHITE, if (flash) null else gold(-52f, -42f), 1.2f)
        if (!flash) {
            eyes(c, Color.rgb(234, 255, 192), FEL, 3f, -24f, 1.7f)
            line(c, "M-2.5 -17.5 l0.8 1.4 l0.8 -1.4 l0.8 1.4 l0.8 -1.4 l0.8 1.4", 0.8f, Color.rgb(58, 46, 30))
            circle(c, 0f, -46f, 1.6f, FEL)
        }
        // Arms and the staff.
        line(c, "M-30 -10 Q-38 -2 -36 8", 8f, WHITE, r); line(c, "M-36 8 l-3 4 M-35 9 l-1 5 M-33 9 l1 4.5", 1.6f, Color.rgb(217, 207, 182))
        line(c, "M36 -52 L32 40", 5.5f); line(c, "M36 -52 L32 40", 3.4f, Color.rgb(90, 58, 36))
        line(c, "M30 -10 Q38 -4 36 4", 8f, WHITE, r)
        glow(c, 36f, -60f, 26f, FEL)
        shape(c, "M31 -58 Q31 -66 36 -66 Q41 -66 41 -58 Q40 -55 38 -54 L34 -54 Q32 -55 31 -58Z", WHITE, if (flash) null else bone(34f, -63f, 7f), 1f)
        if (flash) return
        circle(c, 34f, -60f, 1.3f, FEL); circle(c, 38f, -60f, 1.3f, FEL)
        fill(c, "M32 -66 Q30 -76 36 -82 Q35 -74 40 -72 Q42 -78 40 -84 Q48 -74 40 -66Z", Color.argb(216, 154, 255, 74))
        fill(c, "M34 -67 Q34 -74 37 -77 Q37 -71 39 -69Z", Color.rgb(240, 255, 208))
    }

    /** Sizes of each picture (world units, wide x high), around its middle. */
    const val HERO_W = 84f; const val HERO_H = 112f
}
