package thor.companion.games

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import thor.companion.Theme
import thor.companion.games.LastLight.Hero
import thor.companion.games.LastLight.Night
import java.io.File
import java.time.Duration
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Last Light played to dawn on every night with the real board drawing its pictures (one frame
 * in three, to keep the test quick), to catch what piles up over a long night: pictures painted
 * again and again, effects or foes that are never let go, a growing heap.
 * A helper plays: it walks away from crowds and wanders when nobody is near, takes the first card.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
// The app brings Conscrypt for Android, whose native part Robolectric can't load on a PC.
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [33])
class LastLightLongPlayTest {

    @Test
    fun everyNightPlaysToDawnWithoutPilingUp() {
        // Run outside Gradle (no app resources): the fonts from their files.
        System.getProperty("companion.fonts.dir")?.let { fonts ->
            Theme.useFonts(Typeface.createFromFile(File(fonts, "cinzel_bold.ttf")), Typeface.createFromFile(File(fonts, "wine_tahoma.ttf")))
        }
        val context = RuntimeEnvironment.getApplication()
        val report = StringBuilder()
        val problems = mutableListOf<String>()
        fun problem(what: String, fine: Boolean) { if (!fine) problems += what }
        val only = System.getProperty("longplay.nights")?.split(',')
        for ((night, hero) in listOf(Night.MEADOW to Hero.PALADIN, Night.GRAVEYARD to Hero.MAGE, Night.RUINS to Hero.HUNTER)) {
            if (only != null && night.name !in only) continue
            val prefs = context.getSharedPreferences("long-play-${night.name}", Context.MODE_PRIVATE)
            val view = LastLightView(context, prefs)
            // The board as big as on the Thor's bottom screen with -Dlongplay.full; half as big by
            // default, which paints the same pictures (smaller) in a quarter of the time.
            val full = System.getProperty("longplay.full") != null
            val w = if (full) 1240 else 620; val h = if (full) 770 else 385
            view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
            view.layout(0, 0, w, h)
            val canvas = Canvas(Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888))
            // All boosts bought, so the helper lives to see the end of the night.
            val game = LastLight(IntArray(LastLight.Boost.entries.size) { LastLight.MAX_LEVEL }, kotlin.random.Random(3), hero, night)
            view.game = game
            game.start()

            // Each picture painted: when (seconds into the night) and its name.
            val painted = mutableListOf<Pair<Int, String>>()
            view.onPainted = { painted += game.time.toInt() to it }
            var warm: Pictures? = null
            var heapWarm = 0L
            var mostBytes = 0L; var mostFoes = 0; var mostGems = 0; var mostEffects = 0
            var frames = 0
            val limit = ((night.length + 60) * 1000 / FRAME_MS).toInt()
            while (!game.over && frames < limit) {
                steer(game)
                game.choices?.let { game.choose(0) }
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS))
                view.draw(canvas)
                frames++
                val p = view.pictures()
                if (warm == null && game.time >= 60f) { warm = p; heapWarm = heap() }
                if (warm != null) {
                    mostBytes = maxOf(mostBytes, p.bytes)
                    mostFoes = maxOf(mostFoes, game.foes.size)
                    mostGems = maxOf(mostGems, game.gems.size)
                    mostEffects = maxOf(mostEffects, view.effectCount())
                }
            }
            val end = view.pictures()
            val late = painted.filter { it.first >= 60 }
            val heapEnd = heap()
            val line = "$night $hero: ${game.time.toInt()} s, ${if (game.won) "dawn" else "fell"}, level ${game.level}, $frames frames; " +
                "pictures painted ${end.made}, after the first minute ${late.size} (kept ${end.kept}, most ${mostBytes / 1024} KB); " +
                "most foes $mostFoes, gems $mostGems, effects $mostEffects; heap ${heapWarm / MB} -> ${heapEnd / MB} MB" +
                "\n  painted after the first minute: ${late.joinToString(", ") { "${it.first}s ${it.second}" }}"
            println(line)
            report.appendLine(line)

            problem("The helper should see dawn, so the whole night is tested. $line", game.won)
            // Each picture is painted once and kept: none painted twice (the cache let go of it),
            // a few hundred at most, and hardly any new ones near dawn (in build 226 a new glow
            // picture was painted nearly every frame until the app ran out of memory).
            val twice = painted.groupBy { it.second }.filter { it.value.size > 1 }.keys
            problem("Pictures are painted again: ${twice.take(20)}. $line", twice.isEmpty())
            problem("Too many pictures. $line", end.made <= 350)
            val nearDawn = painted.count { it.first >= night.length - 120 }
            problem("New pictures keep coming near dawn ($nearDawn). $line", nearDawn <= 60)
            problem("The kept pictures take too much memory. $line", mostBytes <= (if (full) 48L else 12L) * MB)
            problem("Too many foes. $line", mostFoes <= LastLight.MAX_FOES + 60)  // a swarm or a bunch may come on top
            problem("Too many gems. $line", mostGems <= LastLight.MAX_GEMS)
            problem("Effects pile up. $line", mostEffects <= 150)
            problem("The heap grows over the night. $line", heapEnd - heapWarm <= 32L * MB)
        }
        System.getProperty("companion.screens.dir")?.let { File(it).apply { mkdirs() }.resolve("last-light-long-play.txt").writeText(report.toString()) }
        assertTrue(problems.joinToString("\n\n"), problems.isEmpty())
    }

    /** Away from the foes close by; wandering in a slow circle when nobody is near. */
    private fun steer(g: LastLight) {
        var sx = 0f; var sy = 0f
        for (f in g.foes) {
            val dx = g.x - f.x; val dy = g.y - f.y; val d2 = dx * dx + dy * dy + 1
            if (d2 < 250f * 250f) { sx += dx / d2; sy += dy / d2 }
        }
        val l = hypot(sx, sy)
        if (l > 0) { g.steerX = sx / l; g.steerY = sy / l } else { g.steerX = cos(g.time * 0.3f); g.steerY = sin(g.time * 0.3f) }
    }

    private fun heap(): Long {
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(50) }
        return rt.totalMemory() - rt.freeMemory()
    }

    private companion object {
        const val MB = 1024L * 1024L
        /** Time between two drawn frames: the board steps the game in small steps in between (as on a slow frame). */
        const val FRAME_MS = 48L
    }
}
