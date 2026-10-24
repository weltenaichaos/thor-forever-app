package thor.companion.games

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/** Our own tile symbols (Mahjong, Memory Pairs): 18 kinds, drawn, no font or artwork needed. */
object Symbols {
    val COLOURS = intArrayOf(Color.rgb(212, 85, 61), Color.rgb(58, 163, 99), Color.rgb(59, 127, 209))

    /** Draws kind [k]: shape k / 3 (ring, diamond, star, moon, leaf, cross) in colour k % 3 (red, green, blue). */
    fun draw(canvas: Canvas, paint: Paint, path: Path, k: Int, x: Float, y: Float, s: Float, alpha: Int) {
        paint.color = COLOURS[k % 3]; paint.alpha = alpha
        path.reset()
        when (k / 3) {
            0 -> {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = s * 0.12f
                canvas.drawCircle(x, y, s * 0.32f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(x, y, s * 0.1f, paint)
                return
            }
            1 -> { path.moveTo(x, y - s * 0.42f); path.lineTo(x + s * 0.3f, y); path.lineTo(x, y + s * 0.42f); path.lineTo(x - s * 0.3f, y); path.close() }
            2 -> {
                for (p in 0 until 10) {
                    val a = p * Math.PI / 5 - Math.PI / 2; val rr = if (p % 2 == 1) s * 0.17f else s * 0.4f
                    val px = x + (Math.cos(a) * rr).toFloat(); val py = y + (Math.sin(a) * rr).toFloat()
                    if (p == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
            }
            3 -> {
                // A crescent: a big circle with a smaller one cut away.
                path.addCircle(x - s * 0.04f, y, s * 0.38f, Path.Direction.CW)
                val cut = Path().apply { addCircle(x + s * 0.12f, y - s * 0.06f, s * 0.3f, Path.Direction.CW) }
                path.op(cut, Path.Op.DIFFERENCE)
            }
            4 -> {
                path.moveTo(x, y + s * 0.42f)
                path.cubicTo(x - s * 0.42f, y, x - s * 0.25f, y - s * 0.4f, x, y - s * 0.42f)
                path.cubicTo(x + s * 0.25f, y - s * 0.4f, x + s * 0.42f, y, x, y + s * 0.42f)
                path.close()
            }
            else -> {
                path.addRoundRect(x - s * 0.1f, y - s * 0.4f, x + s * 0.1f, y + s * 0.4f, s * 0.05f, s * 0.05f, Path.Direction.CW)
                val bar = Path().apply { addRoundRect(x - s * 0.35f, y - s * 0.1f, x + s * 0.35f, y + s * 0.1f, s * 0.05f, s * 0.05f, Path.Direction.CW) }
                path.op(bar, Path.Op.UNION)
            }
        }
        canvas.drawPath(path, paint)
        if (k / 3 == 4) {
            paint.color = Color.rgb(246, 236, 212); paint.alpha = alpha; paint.strokeWidth = s * 0.05f
            canvas.drawLine(x, y - s * 0.3f, x, y + s * 0.38f, paint)
        }
    }
}
