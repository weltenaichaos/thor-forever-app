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
import org.robolectric.annotation.ConscryptMode
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
// The app brings Conscrypt for Android, whose native part Robolectric can't load on a PC.
@ConscryptMode(ConscryptMode.Mode.OFF)
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

    private fun shoot(name: String, tab: String, loaded: Boolean = true, upTo: Int = messages.size, craft: String? = null, scrolled: Int = 0,
                      byGear: Boolean = false, target: Int? = null, recipe: String? = null, enchanting: String? = null, extra: List<String> = emptyList(),
                      lastEnchant: String? = null, replace: Boolean = false, sheetScrolled: Int = 0, list: List<String> = emptyList(), sheet: String? = null,
                      done: List<String> = emptyList(), exitAsked: Boolean = false, more: String? = null, playing: String? = null) {
        // Run outside Gradle (no app resources): the fonts from their files.
        System.getProperty("companion.fonts.dir")?.let { fonts ->
            Theme.useFonts(Typeface.createFromFile(File(fonts, "cinzel_bold.ttf")), Typeface.createFromFile(File(fonts, "wine_tahoma.ttf")))
        }
        val controller = Robolectric.buildActivity(CompanionActivity::class.java)
        controller.get().setTheme(android.R.style.Theme_DeviceDefault_NoActionBar)  // as in the manifest
        controller.create()
        val activity = controller.get()
        activity.preview(messages.take(upTo) + extra, tab, loaded, craft, byGear, target, recipe, enchanting, lastEnchant, replace, list, sheet, done, exitAsked, more, playing)
        shadowOf(Looper.getMainLooper()).idle()
        val root = activity.findViewById<View>(android.R.id.content)
        val w = 1240; val h = 1080
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        shadowOf(Looper.getMainLooper()).idle()
        // Further down the tab: the list scrolled by [scrolled] pixels.
        if (scrolled > 0) scrollView(root)?.scrollTo(0, scrolled)
        // The pop-up at the bottom (a recipe's card) scrolled down by [sheetScrolled] pixels.
        if (sheetScrolled > 0) scrollViews(root).lastOrNull()?.scrollTo(0, sheetScrolled)
        val picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(picture))
        File(dir, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.destroy()
    }

    private fun scrollViews(v: View): List<android.widget.ScrollView> = when (v) {
        is android.widget.ScrollView -> listOf(v) + (0 until v.childCount).flatMap { scrollViews(v.getChildAt(it)) }
        is android.view.ViewGroup -> (0 until v.childCount).flatMap { scrollViews(v.getChildAt(it)) }
        else -> emptyList()
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
    /** Enchanting, both ways: by enchantment with one's card open, by gear piece, and the wrist's enchantments. */
    @Test fun enchantments() = shoot("4b-enchant", "CRAFT", craft = "Enchanting", recipe = "Enchant Chest - Lesser Health")
    @Test fun enchantList() = shoot("4a-enchant-list", "CRAFT", craft = "Enchanting")
    @Test fun enchantByGear() = shoot("4c-enchant-gear", "CRAFT", craft = "Enchanting", byGear = true)
    @Test fun enchantByGearBags() = shoot("4c2-enchant-gear-bags", "CRAFT", craft = "Enchanting", byGear = true, scrolled = 420)
    @Test fun enchantOneGearPiece() = shoot("4d-enchant-wrist", "CRAFT", craft = "Enchanting", byGear = true, target = 9)
    /** The game asks before the enchantment replaces another one: Yes and No in the app. */
    @Test fun enchantAsking() = shoot("4e-enchant-asking", "CRAFT", craft = "Enchanting", recipe = "Enchant Bracer - Minor Stamina",
        enchanting = "Ivycloth Bracelets", extra = listOf(messages.first { it.startsWith("TF1|") } +
            "\nc\t1\t0\t1\tasking\tDo you want to replace \"Minor Health\" with \"Minor Stamina\"?\tEnchant Bracer - Minor Stamina"))
    /** Skilling: Enchant again on the item enchanted last, and the replace switch on its card. */
    @Test fun enchantAgain() = shoot("4f-enchant-again", "CRAFT", craft = "Enchanting", recipe = "Enchant Bracer - Minor Stamina",
        lastEnchant = "Enchanting\t4\tEnchant Bracer - Minor Stamina\t9\t9793", replace = true)
    /** The same card scrolled down: the materials under the Enchant button, then the switch. */
    @Test fun enchantAgainMaterials() = shoot("4g-enchant-materials", "CRAFT", craft = "Enchanting", recipe = "Enchant Bracer - Minor Stamina",
        lastEnchant = "Enchanting\t4\tEnchant Bracer - Minor Stamina\t9\t9793", replace = true, sheetScrolled = 400)
    /** Skilling from the list: 5 of the same enchantment on one item, made one tap each; 2 done, it stays first. */
    @Test fun enchantOnList() = shoot("4h-enchant-list", "CRAFT", craft = "Enchanting", sheet = "list",
        list = listOf("Enchanting\t4\t0\t5\tEnchant Bracer - Minor Stamina\t9\t9793\t2", "Tailoring\t7\t4241\t2\tWoolen Bag"))
    /** The same while the game makes the 3rd: the entry counts in place. */
    @Test fun enchantOnListMaking() = shoot("4i-enchant-list-making", "CRAFT", craft = "Enchanting", sheet = "list",
        recipe = "Enchant Bracer - Minor Stamina", enchanting = "Ivycloth Bracelets",
        list = listOf("Enchanting\t4\t0\t5\tEnchant Bracer - Minor Stamina\t9\t9793\t2", "Tailoring\t7\t4241\t2\tWoolen Bag"))
    /** Further on: the first entry made (✓), the second one next. */
    @Test fun enchantOnListDone() = shoot("4j-enchant-list-done", "CRAFT", craft = "Enchanting", sheet = "list",
        done = listOf("Enchanting\t4\t0\t4\tEnchant Bracer - Minor Stamina\t9\t9793\t4"),
        list = listOf("Enchanting\t8\t0\t5\tEnchant Chest - Lesser Health\t5\t6264\t0", "Tailoring\t7\t4241\t2\tWoolen Bag"))
    /** Exit asks in the status line before it closes the app. */
    @Test fun exitAsks() = shoot("7-exit-asks", "CRAFT", craft = "Enchanting", sheet = "list", exitAsked = true,
        list = listOf("Enchanting\t4\t0\t5\tEnchant Bracer - Minor Stamina\t9\t9793\t2", "Tailoring\t7\t4241\t2\tWoolen Bag"))
    @Test fun map() = shoot("5-map", "MAP")
    @Test fun games() = shoot("8-games", "BAGS", more = "games")
    @Test fun gemMatch() = shoot("8a-gem-match", "BAGS", more = "games", playing = "gems")
    @Test fun solitaire() = shoot("8b-solitaire", "BAGS", more = "games", playing = "solitaire")
    @Test fun minesweeper() = shoot("8c-minesweeper", "BAGS", more = "games", playing = "mines")
    @Test fun tiles2048() = shoot("8d-2048", "BAGS", more = "games", playing = "2048")
    @Test fun settings() = shoot("9-settings", "BAGS", more = "settings")
    @Test fun chat() = shoot("6-chat", "CHAT")
    /** The start-up card, part way: the status, bags and character are in, the rest still coming. */
    @Test fun loading() = shoot("0-loading", "BAGS", loaded = false, upTo = 4)
}
