package thor.companion

import android.graphics.Bitmap
import android.graphics.Canvas
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
 * Pictures of the companion's tabs as they look on the Thor's bottom screen (1240 x 1080),
 * filled with the sample messages in companion-sample.txt, so a change to the look can be
 * seen without the device. Only runs when asked: -PcompanionScreens=<folder> (see app/build.gradle).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33], qualifiers = "w472dp-h411dp-land-420dpi")
class CompanionScreensTest {

    private val dir = File(System.getProperty("companion.screens.dir") ?: "build/companion-screens").apply { mkdirs() }

    /** The sample's messages: records separated by a line "---", each without its leading # comments. */
    private val messages: List<String> by lazy {
        val text = javaClass.classLoader!!.getResource("companion-sample.txt")!!.readText()
        text.split(Regex("(?m)^---\\s*$")).map { record ->
            record.trim('\n', '\r').lines().dropWhile { it.startsWith("#") }.joinToString("\n").trimEnd('\n', '\r')
        }.filter { it.isNotBlank() }
    }

    private fun shoot(name: String, tab: String, loaded: Boolean = true, upTo: Int = messages.size) {
        val controller = Robolectric.buildActivity(CompanionActivity::class.java).create()
        val activity = controller.get()
        activity.preview(messages.take(upTo), tab, loaded)
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.findViewById<View>(android.R.id.content)
        val w = 1240; val h = 1080
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        shadowOf(Looper.getMainLooper()).idle()
        val picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(picture))
        File(dir, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun bags() = shoot("1-bags", "BAGS")
    @Test fun character() = shoot("2-character", "CHARACTER")
    @Test fun quests() = shoot("3-quests", "QUESTS")
    @Test fun craft() = shoot("4-craft", "CRAFT")
    @Test fun map() = shoot("5-map", "MAP")
    @Test fun chat() = shoot("6-chat", "CHAT")
    /** The start-up card, part way: the status, bags and character are in, the rest still coming. */
    @Test fun loading() = shoot("0-loading", "BAGS", loaded = false, upTo = 4)
}
