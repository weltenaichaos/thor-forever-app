package thor.companion

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Typeface
import android.os.Looper
import android.view.View
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pictures of the companion's tabs as they look on the Thor's bottom screen (1240 x 1080, at
 * the density Jesse's own screenshot shows), filled with the sample messages in
 * companion-sample.txt, so a change to the look can be seen without the device.
 * Only runs when asked: -PcompanionScreens=<folder> (see app/build.gradle).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w564dp-h491dp-land-352dpi")
class CompanionScreensTest {

    private val dir = File(System.getProperty("companion.screens.dir") ?: "build/companion-screens").apply { mkdirs() }

    /** The sample's messages: records separated by a line "---", each without its leading # comments. */
    private val messages: List<String> by lazy {
        val text = javaClass.classLoader!!.getResource("companion-sample.txt")!!.readText()
        text.split(Regex("(?m)^---\\s*$")).map { record ->
            record.trim('\n', '\r').lines().dropWhile { it.startsWith("#") }.joinToString("\n").trimEnd('\n', '\r')
        }.filter { it.isNotBlank() }.map(::movedToNow)
    }

    /** The sample's times (written at time() = 1792352400) moved to now, so "read 5 min ago" stays 5 min. */
    private fun movedToNow(message: String): String {
        val shift = System.currentTimeMillis() / 1000 - 1792352400L
        return Regex("\\b179\\d{7}\\b").replace(message) { (it.value.toLong() + shift).toString() }
    }

    private fun shoot(name: String, tab: String, loaded: Boolean = true, upTo: Int = messages.size, craft: String? = null, scrolled: Int = 0) {
        // Run outside Gradle (no app resources): the fonts from their files.
        System.getProperty("companion.fonts.dir")?.let { fonts ->
            Theme.useFonts(Typeface.createFromFile(File(fonts, "cinzel_bold.ttf")), Typeface.createFromFile(File(fonts, "wine_tahoma.ttf")))
        }
        val controller = Robolectric.buildActivity(CompanionActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)  // as in the manifest
        controller.create()
        val activity = controller.get()
        activity.preview(messages.take(upTo), tab, loaded, craft)
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.findViewById<View>(android.R.id.content)
        val w = 1240; val h = 1080
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        shadowOf(Looper.getMainLooper()).idle()
        // Further down the tab: the list scrolled by [scrolled] pixels.
        if (scrolled > 0) scrollView(root)?.scrollTo(0, scrolled)
        val picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(picture))
        File(dir, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.destroy()
    }

    private fun scrollView(v: View): android.widget.ScrollView? = when (v) {
        is android.widget.ScrollView -> v
        is android.view.ViewGroup -> (0 until v.childCount).firstNotNullOfOrNull { scrollView(v.getChildAt(it)) }
        else -> null
    }

    @Test fun bags() = shoot("1-bags", "BAGS")
    @Test fun character() = shoot("2-character", "CHARACTER")
    @Test fun gear() = shoot("2b-gear", "CHARACTER", scrolled = 760)
    @Test fun quests() = shoot("3-quests", "QUESTS")
    @Test fun questList() = shoot("3b-quest-list", "QUESTS", scrolled = 640)
    @Test fun craft() = shoot("4-craft", "CRAFT", craft = "Tailoring")
    @Test fun map() = shoot("5-map", "MAP")
    @Test fun chat() = shoot("6-chat", "CHAT")
    /** The start-up card, part way: the status, bags and character are in, the rest still coming. */
    @Test fun loading() = shoot("0-loading", "BAGS", loaded = false, upTo = 4)
}
