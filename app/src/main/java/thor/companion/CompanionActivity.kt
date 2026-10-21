package thor.companion

import android.app.Activity
import android.graphics.Color
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import thor.companion.strip.ActionKeys
import thor.companion.strip.GameState
import thor.companion.strip.CharacterInfo
import thor.companion.strip.ChatLine
import thor.companion.strip.Gear
import thor.companion.strip.BankCounts
import thor.companion.strip.CraftProgress
import thor.companion.strip.EnchantTarget
import thor.companion.strip.ItemCompare
import thor.companion.strip.ItemStats
import thor.companion.strip.Professions
import thor.companion.strip.Recipe
import thor.companion.strip.RecipePage
import thor.companion.strip.GearItem
import thor.companion.strip.ChatLog
import thor.companion.strip.Cooldowns
import thor.companion.strip.BagItem
import thor.companion.strip.Compact
import thor.companion.strip.Compass
import thor.companion.strip.CornerAreas
import thor.companion.strip.Errands
import thor.companion.strip.Readiness
import thor.companion.strip.Delta
import thor.companion.strip.Difficulty
import thor.companion.strip.ItemTips
import thor.companion.strip.ItemUse
import thor.companion.strip.IconPicture
import thor.companion.strip.ItemNames
import thor.companion.strip.MapPicture
import thor.companion.strip.AreaMask
import thor.companion.strip.AreaPicture
import thor.companion.strip.LevelPlan
import thor.companion.strip.PartAssembler
import thor.companion.strip.Pixels
import thor.companion.strip.Quest
import thor.companion.strip.QuestLog
import thor.companion.strip.QuestPages
import thor.companion.strip.SpellPlan
import thor.companion.strip.SpellToLearn
import thor.companion.strip.StripDecoder
import thor.companion.strip.StripFrame
import thor.companion.strip.Trail
import thor.companion.strip.MapPlace
import thor.companion.strip.ZoneMap
import thor.companion.Theme.ACCENT
import thor.companion.Theme.DIM
import thor.companion.Theme.GOLD
import thor.companion.Theme.PRESSED
import thor.companion.Theme.RAISED
import thor.companion.Theme.STROKE
import thor.companion.Theme.SURFACE
import thor.companion.Theme.TEXT
import java.util.Locale

/**
 * The bottom-screen companion: reads the addon's strip off the top screen a couple
 * of times a second and shows the game's panels here instead of over the game.
 */
class CompanionActivity : Activity() {

    private enum class Panel(val title: String) { BAGS("Bags"), CHARACTER("Character"), QUESTS("Quests"), CRAFT("Craft"), MAP("Map"), CHAT("Chat") }

    /** The chat's own tabs: which lines each shows, and the key that answers there. */
    private enum class ChatTab(val title: String, val kinds: Set<String>?) {
        ALL("All", null),
        GROUP("Group", setOf("party", "raid", "instance")),
        WHISPERS("Whispers", setOf("whisper", "whisper_to", "bnwhisper", "bnwhisper_to")),
        GUILD("Guild", setOf("guild")),
        GENERAL("General", setOf("say", "yell", "emote", "channel", "system")),
    }

    private lateinit var screen: TopScreen
    private lateinit var names: NameStore
    private lateinit var tips: TipStore
    private lateinit var icons: IconStore
    private lateinit var status: TextView
    private lateinit var statusDot: View
    private lateinit var loadButton: TextView
    private lateinit var exitButton: LinearLayout
    private lateinit var exitIcon: PowerIcon
    private lateinit var exitLabel: TextView
    private lateinit var exitAsk: TextView
    private lateinit var content: LinearLayout
    /** Above and below the scrolling part: the chat's tabs and answer keys, item details. */
    private lateinit var toolbar: LinearLayout
    private lateinit var footer: LinearLayout
    private val tabs = HashMap<Panel, TextView>()
    private var panel = Panel.BAGS
    private var state: GameState? = null
    private val chat = ChatLog()
    private var zoneMap: ZoneMap? = null
    private var character: CharacterInfo? = null
    private var gear: List<GearItem> = emptyList()
    private val trail = Trail()
    private var trailUnsaved = 0
    private var mapView: MapView? = null
    /** Where the map was dragged to, and on which map (the tab is drawn anew on every change). */
    private var mapPan: Pair<Int, DoubleArray>? = null
    private var mapTitle: TextView? = null
    private lateinit var scroll: ScrollView
    /** The whole companion page, and its height as its last measure said (see sheet). */
    private lateinit var pageRoot: LinearLayout
    private var pageHeight = 0
    private var quests: QuestLog? = null
    /** The quest log's first page (TL1) and its other pages (TL2): [quests] is all of them. */
    private var questFirst: QuestLog? = null
    @Volatile private var questPages = QuestPages()
    /** Class spells to learn soon (TV1), for the level card. */
    private var spells: SpellPlan? = null
    /** Your professions and what you made (TF1), their recipes a page at a time (TR1, kept as "TR1|<profession>|<page>"), the bank's materials (TK1). */
    @Volatile private var professions: Professions? = null
    private val recipePages = java.util.concurrent.ConcurrentHashMap<String, RecipePage>()
    private var bank: BankCounts? = null
    /** The Craft tab's open profession and recipe, and whether it shows only what you can make now. */
    private var craftProfession: String? = null
    private var craftRecipe: Recipe? = null
    private var craftOnlyMakeable = false
    private var craftRunning: CraftRun? = null
    private val completeRecipes = HashMap<String, List<Recipe>>()  // per profession, the last complete reading
    private val craftDone = mutableListOf<CraftJob>()  // made from the crafting list since it was started (this session)
    private var craftSheet: String? = null  // "list": the Craft tab shows the crafting list page
    private var sheetKey = ""  // the pop-up shown (see sheet) and how far down it is scrolled
    private var sheetY = 0
    // A finger on the screen: the Craft tab is drawn again only once it lifts (see dispatchTouchEvent).
    private var touching = false
    private var renderWaiting = false
    /** Enchanting: the list by gear piece instead of by enchantment, and the item picked (its [EnchantTarget.place]). */
    private var craftByGear = false
    private var craftTarget: Int? = null
    private var chatTab = ChatTab.ALL
    /** Per chat tab, the number of the last line seen there (see ChatLog.total). */
    private val chatSeen = HashMap<ChatTab, Int>()

    @Volatile private var worker: Thread? = null
    @Volatile private var lastKeyAt = 0L
    /** When the square was last read: the dot by the status line is green while that is recent. */
    @Volatile private var frameAt = 0L
    /** When one of the addon's corner squares was last read, and their cell size (0: none read yet). */
    @Volatile private var cornersAt = 0L
    @Volatile private var cornerCell = 0.0
    /** When the last status (with the position) came in: it is only sent when something changed. */
    private var movedAt = 0L
    /** Start time and duration (ms) of each capture in the last minute, for the battery check. */
    private val captures = java.util.ArrayDeque<LongArray>()
    /**
     * Key presses go out one at a time, in the order they were tapped. A mistake while
     * sending them is written to app-exits.txt and shown, and never closes the app: the
     * game runs in this app too and would close with it.
     */
    private val keys = java.util.concurrent.Executors.newSingleThreadExecutor { task ->
        Thread(task, "companion-keys").apply {
            setUncaughtExceptionHandler { t, e ->
                android.util.Log.e("ThorCompanion", "keys: error while sending keys", e)
                runCatching {
                    com.winlator.cmod.core.CrashReasonLogger.note("Companion key error in thread \"${t.name}\" (the app and the game kept running)\n" +
                        android.util.Log.getStackTraceString(e))
                }
                runOnUiThread { say("Keys not sent: an error (written to app-exits.txt)") }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A crash leaves its cause in a file, shown once at the next start (so it can be reported).
        val crashFile = java.io.File(filesDir, "crash.txt")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { crashFile.writeText(android.util.Log.getStackTraceString(e).take(4000)) }
            previous?.uncaughtException(t, e)
        }
        // Taps here must not take the key focus (or the controller) away from the game.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        screen = TopScreen(this)
        names = NameStore(this)
        tips = TipStore(this)
        bagSort = getPreferences(MODE_PRIVATE).getInt("bagSort", 0)
        tracked = getPreferences(MODE_PRIVATE).getString("tracked", null)

        icons = IconStore(this)

        val root = object : LinearLayout(this) {
            override fun onMeasure(w: Int, h: Int) { pageHeight = MeasureSpec.getSize(h); super.onMeasure(w, h) }
        }.apply {
            orientation = LinearLayout.VERTICAL
            background = ScreenFrame(dp(150), Theme.px(context))
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        // The tabs: a row of buttons like the start screen's, the open one in teal.
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (p in Panel.entries) {
            val b = TextView(this).apply {
                text = p.title
                textSize = 13f
                gravity = Gravity.CENTER
                typeface = Theme.heading(context)
                maxLines = 1
                setOnClickListener { show(p) }
            }
            tabs[p] = b
            header.addView(b, LinearLayout.LayoutParams(0, dp(44), if (p == Panel.CHARACTER) 1.3f else 1f).apply {
                if (p != Panel.entries.first()) marginStart = dp(4)
            })
        }
        // The status line: a dot that says whether the game's data is coming in, what happened
        // last, and the one key that asks the game for everything when something is missing.
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(6), 0, dp(6))
        }
        statusDot = View(this).apply {
            background = Theme.box(context, Theme.WARN, 5)
        }
        status = TextView(this).apply {
            setTextColor(DIM)
            textSize = 13f
            typeface = Theme.body(context)
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8), 0, dp(8), 0)
            // Long-press: the battery check (how much reading the game's screen costs), and
            // the Thor's recent log saved in Download, to send along when something goes wrong.
            setOnLongClickListener { say(captureStats()); saveLog(); true }
        }
        loadButton = chip("Load from the game") {
            pressKey(ActionKeys.REFRESH, onDone = { say("Asked the game for everything again…") }) { err -> say("Couldn't ask the game: $err") }
        }
        statusRow.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)))
        statusRow.addView(status, LinearLayout.LayoutParams(0, -2, 1f))
        statusRow.addView(loadButton)
        // Closes the app for good (no task manager needed). Quiet at the end of this line, not
        // a button among the tab's buttons (Jesse, 2026-10-20: it looked out of place and was
        // easy to hit). A tap asks in this line and lights Exit up; a second tap closes, another
        // tab (or a while) puts it back.
        exitIcon = PowerIcon(this)
        exitLabel = line("Exit", DIM, 12.5f).apply { setPadding(dp(6), 0, 0, 0) }
        exitButton = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            addView(exitIcon, LinearLayout.LayoutParams(dp(15), dp(15)))
            addView(exitLabel)
            setOnClickListener { if (exitAsk.visibility == View.VISIBLE) quit() else askExit(true) }
        }
        exitAsk = line("Close the Forever Companion? The game keeps running.", Theme.GOLD_TEXT, 13f).apply {
            setPadding(dp(8), 0, dp(8), 0); visibility = View.GONE; maxLines = 2
        }
        statusRow.addView(exitAsk, LinearLayout.LayoutParams(0, -2, 1f))
        statusRow.addView(exitButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(4) })
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        toolbar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(header)
        root.addView(statusRow)
        root.addView(toolbar)
        scroll = ScrollView(this).apply { addView(content); isVerticalScrollBarEnabled = false }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(footer)
        pageRoot = root
        setContentView(root)
        runCatching { trail.load(trailFile().readText()) }
        loadLast()
        show(Panel.BAGS)
        updateCompass()
        say("Looking for the game…")
        // The last crash, once: its first lines, to send along.
        if (crashFile.exists()) {
            val trace = runCatching { crashFile.readText() }.getOrDefault("")
            crashFile.delete()
            if (trace.isNotBlank()) android.app.AlertDialog.Builder(this)
                .setTitle("The app crashed last time")
                .setMessage(trace.lines().take(12).joinToString("\n"))
                .setPositiveButton("OK", null)
                .show()
        }
    }

    /** Exit's question in the status line and Exit lit up ([on]), or back to the status; it goes by itself after a while. */
    private fun askExit(on: Boolean) {
        exitAsk.visibility = if (on) View.VISIBLE else View.GONE
        status.visibility = if (on) View.GONE else View.VISIBLE
        exitButton.background = if (on) Theme.goldButton(this) else null
        exitIcon.colour = if (on) Theme.GOLD_TEXT else DIM; exitIcon.invalidate()
        exitLabel.setTextColor(if (on) Theme.GOLD_TEXT else DIM)
        updateLoadButton()
        exitAsk.removeCallbacks(exitTimeout)
        if (on) exitAsk.postDelayed(exitTimeout, 8000)
    }
    private val exitTimeout = Runnable { askExit(false) }

    /** Exits: saves what it shows, stops reading the screen and removes the app from the recent apps (after any key still on its way). */
    private fun quit() {
        android.util.Log.i("ThorCompanion", "Exit: closing the companion (the game keeps running)")
        say("Closing…")
        keys.execute { runOnUiThread { finishAndRemoveTask() } }
    }

    /** Set in onStop: onStart then starts the redraw timers again. */
    private var wasStopped = false

    override fun onStart() {
        super.onStart()
        android.util.Log.i("ThorCompanion", "onStart")
        worker = Thread(::readSafely, "strip-reader").apply { isDaemon = true; start() }
        // Back on screen: the redraw timers stopped in onStop go on (each ends by itself when not needed).
        if (wasStopped) {
            wasStopped = false
            scroll.post(loadingTick)
            content.post(craftTick)
            content.post(cooldownTick)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        android.util.Log.i("ThorCompanion", "window focus $hasFocus")
        // A tap on the game (or anything else) while typing here: the box and its keyboard
        // close and the key focus goes back to the game. This used to wait until the game had
        // the focus; with the login box still open on the character screen the box then stayed,
        // and the next controller button found no focused window and Android closed the app
        // as not responding (Jesse's app-exits.txt, 2026-10-17 19:11 and 19:12).
        if (!hasFocus && composing != null) content.postDelayed({
            if (composing != null && !hasWindowFocus() && !askingMic) {
                android.util.Log.i("ThorCompanion", "focus left while typing (game has it: ${CompanionBridge.gameHasFocus()}): closing the box")
                closeCompose()
            }
        }, 300)
    }

    /**
     * Gives the key focus back to the game. While typing here this screen has it; made
     * unfocusable again, it kept it, with no window to take the keys: a controller button
     * then waited for one, and after 5 s Android closed the app as not responding
     * ("Input dispatching timed out (Application does not have a focused window)").
     */
    private fun giveFocusToGame() {
        val id = CompanionBridge.gameTaskId
        if (id < 0 || CompanionBridge.gameHasFocus()) return
        android.util.Log.i("ThorCompanion", "giving the key focus back to the game")
        runCatching { getSystemService(android.app.ActivityManager::class.java).moveTaskToFront(id, 0) }
            .onFailure { android.util.Log.w("ThorCompanion", "focus back to the game failed", it) }
    }

    override fun onStop() {
        android.util.Log.i("ThorCompanion", "onStop")
        closeCompose(giveBack = false)  // going away (Home, Exit): don't bring the game back over it
        worker = null
        watcher = null
        runCatching { watchSocket?.close() }
        // Nothing is shown: no redrawing every second for the loading card or a craft in progress.
        scroll.removeCallbacks(loadingTick)
        content.removeCallbacks(craftTick)
        content.removeCallbacks(cooldownTick)
        wasStopped = true
        saveTrail()
        saveLast()
        super.onStop()
    }

    override fun onDestroy() {
        // The key thread and the picture copier's thread end with the app.
        keys.shutdown()
        screen.close()
        super.onDestroy()
    }

    private fun trailFile() = java.io.File(filesDir, "trail.txt")

    // The last message of each kind that describes the game's state, kept so a restarted
    // app shows the bags, character and map right away instead of after the addon's
    // next refresh (or a tap on Refresh).
    /** False while the bags shown are the ones kept from before a restart: then a tap could use whatever moved into that slot since. */
    private var bagsFresh = false
    private var bagsDifferSince = 0L
    private var addonWarned = false
    /** How the bags are sorted: 0 as in the game, 1 type, 2 quality, 3 sell value. */
    private var bagSort = 0
    private val lastMessages = java.util.concurrent.ConcurrentHashMap<String, String>()
    /** The kinds (as kept, "TP1|", "TL2|2") that came from the game since it last said who is playing: current whatever the status's checksums say. */
    private val fresh: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    /** The character whose kept messages [lastMessages] holds; each character has its own file. */
    @Volatile private var keptFor: String? = null
    private fun lastFile(name: String?) =
        if (name == null) java.io.File(filesDir, "last-messages.txt")
        else java.io.File(filesDir, "kept-" + name.replace(Regex("[^\\p{L}\\p{N}_-]"), "_") + ".txt")

    /** The key a message is kept under: its kind, and for a quest page also its number ("TL2|2"). */
    private fun keptKey(message: String): String? {
        val kind = message.take(4)
        return when {
            kind == "TL2|" -> message.substringBefore('\n').split('|').getOrNull(1)?.let { "TL2|$it" }
            kind == "TR1|" -> message.substringBefore('\n').split('|').takeIf { it.size > 4 }?.let { "TR1|${it[1]}|${it[4]}" }
            kind in KEPT -> kind
            else -> null
        }
    }

    private fun remember(message: String) {
        val key = keptKey(message) ?: return
        if (lastMessages[key] == message) return
        lastMessages[key] = message
        if (key != "TS1|") saveLast()
    }

    private fun saveLast() = runCatching {
        // One message per record; messages contain newlines, so records are separated by a NUL.
        lastFile(keptFor).writeText(lastMessages.values.joinToString("\u0000"))
    }

    /** The kept messages of [name] (or, from before they were kept per character, the one file there was). */
    private fun readKept(name: String?): List<String> = runCatching {
        val file = lastFile(name).takeIf { it.exists() } ?: return@runCatching emptyList()
        file.readText().split('\u0000').filter { it.length >= 4 }
    }.getOrDefault(emptyList())

    private fun loadLast() {
        keptFor = getPreferences(MODE_PRIVATE).getString("keptFor", null)
        var kept = readKept(keptFor)
        if (keptFor == null && kept.isNotEmpty()) {
            // The single file of older versions: it becomes its character's own.
            keptFor = kept.firstOrNull { it.startsWith("TS1|") }?.split('|')?.getOrNull(1)
            getPreferences(MODE_PRIVATE).edit().putString("keptFor", keptFor).apply()
        }
        for (m in kept) keptKey(m)?.let { lastMessages[it] = m }
        applyKept()
    }

    /** Shows what [lastMessages] holds (on the UI thread), in place of whatever was shown. */
    private fun applyKept() {
        state = null; character = null; gear = emptyList(); zoneMap = null; spells = null
        questFirst = null; quests = null; bagsFresh = false
        professions = null; recipePages.clear(); bank = null; craftRecipe = null; craftDone.clear(); craftRunning = null
        val pages = QuestPages()
        for ((key, m) in lastMessages) {
            when {
                key.startsWith("TL2|") -> pages.add(m)
                key.startsWith("TR1|") -> RecipePage.parse(m)?.let { recipePages[key] = it }
                m.startsWith("TF1|") -> professions = Professions.parse(m)
                m.startsWith("TK1|") -> bank = BankCounts.parse(m)
                m.startsWith("TM1|") -> zoneMap = ZoneMap.parse(m)
                m.startsWith("TP1|") -> character = CharacterInfo.parse(m)
                m.startsWith("TQ1|") -> gear = Gear.parse(m).orEmpty()
                m.startsWith("TL1|") -> questFirst = QuestLog.parse(m)
                m.startsWith("TV1|") -> spells = SpellPlan.parse(m)
            }
        }
        // Status first, then the bags on top of it.
        lastMessages["TS1|"]?.let { GameState.parse(it, null)?.let { s -> state = s } }
        lastMessages["TB1|"]?.let { GameState.parse(it, state)?.let { s -> state = s } }
        questPages = pages
        quests = questFirst?.let { pages.whole(it) }
    }

    /**
     * Another character is logged in than the one the kept messages are of (runs on the
     * reading thread, before anything of the new character is kept): the old one's are
     * saved to its own file, the new one's taken out, and the start-up card shows again.
     */
    private fun switchCharacter(name: String) {
        val old = keptFor
        if (old != null) saveLast()
        lastMessages.clear()
        fresh.clear()
        keptFor = name
        getPreferences(MODE_PRIVATE).edit().putString("keptFor", name).apply()
        for (m in readKept(name)) keptKey(m)?.let { lastMessages[it] = m }
        runOnUiThread {
            applyKept()
            switchedFrom = old
            startLoading()
        }
    }

    private fun saveTrail() {
        if (trailUnsaved == 0) return
        trailUnsaved = 0
        runCatching { trailFile().writeText(trail.save()) }
    }

    /**
     * The reading loop, started again after a mistake in it instead of ending the app: the
     * companion runs inside Thor Forever, and an uncaught error here closed the game too.
     */
    private fun readSafely() {
        while (worker === Thread.currentThread()) {
            try {
                loop()
                return
            } catch (e: RuntimeException) {
                android.util.Log.e("ThorCompanion", "reading loop failed, starting it again", e)
                runOnUiThread { say("The app hit a reading error and carried on (logged)") }
                SystemClock.sleep(1000)
            }
        }
    }

    private fun loop() {
        if (watcher == null) watcher = Thread(::watchGame, "game-watch").apply { isDaemon = true; start() }
        var misses = 0
        var seenSquare = false
        var absent = 0
        var messageAt = 0L
        var changedAt = 0L
        var near = -1
        // Where the square or bar was last read, with room around it: only that corner of
        // the picture is copied (see [lockAround]). Let go when the picture changes size,
        // or when nothing was read there for a while (the bar moved with /thor pos).
        var lock: Rect? = null
        var lockSize: Rect? = null
        var lockAt = 0L
        var whyAt = 0L
        val assembler = PartAssembler()
        // The addon's corner squares (its default, see Corners.lua): your position top left,
        // the chat bottom left, the status bottom right, everything else top right. Each has
        // its own message numbers, so each its own assembler. Only those four small parts of
        // the picture are copied; the whole picture only while no corner square was read
        // lately (an older addon, or another /thor shape).
        val cornerParts = HashMap<Int, PartAssembler>()
        fun assemblerOf(version: Int) =
            if (version == StripDecoder.POSITION_VERSION || version == StripDecoder.CHAT_VERSION || version == StripDecoder.STATUS_VERSION)
                cornerParts.getOrPut(version) { PartAssembler() } else assembler
        val cornerNear = IntArray(4) { -1 }
        var cornerAreasFor: Rect? = null
        var cornerAreas: List<IntArray> = emptyList()
        val shownLast = HashMap<Int, Int>()
        while (worker === Thread.currentThread()) {
            val t0 = SystemClock.uptimeMillis()
            // The game's files say there is nothing to see (closed, starting, the character
            // screen, a loading screen): no screenshot at all, just wait for the next event.
            if (blind()) {
                absent = 0
                SystemClock.sleep(500)
                continue
            }
            val size = screen.size()
            // The four corners first. (Their places follow the picture's size and the cell size read last.)
            val frames = ArrayList<StripFrame>()
            var result: StripDecoder.Result? = null
            var px: Pixels? = null
            if (size != null) {
                if (size != cornerAreasFor) {
                    cornerAreasFor = size
                    cornerAreas = CornerAreas.areas(size.width(), size.height(), cornerCell)
                    cornerNear.fill(-1)
                }
                for ((i, a) in cornerAreas.withIndex()) {
                    val shot = screen.captureArea(Rect(a[0], a[1], a[2], a[3])) ?: continue
                    px = shot.pixels
                    val r = safeDecode(shot.pixels, cornerNear[i])
                    val f = (r as? StripDecoder.Result.Ok)?.frame
                    cornerNear[i] = f?.row ?: -1
                    if (f != null) synchronized(cornerReads) { cornerReads.add(i, f) }
                    if (f != null && f.crcOk) frames += CornerAreas.moved(f, shot.left, shot.top)
                    if (result !is StripDecoder.Result.Ok) result = r
                }
            }
            val corners = frames.any { it.version in StripDecoder.POSITION_VERSION..StripDecoder.DATA_VERSION }
            if (corners) {
                cornersAt = t0
                val cell = frames.first { it.version in StripDecoder.POSITION_VERSION..StripDecoder.DATA_VERSION }.cellPx
                // Cells much bigger than guessed (/thor cell): bigger areas, so the squares fit.
                if (cornerCell == 0.0 || kotlin.math.abs(cell - cornerCell) > 0.5) {
                    cornerCell = cell
                    cornerAreasFor = null
                }
            }
            // No corner square lately: the whole picture (or where the bar or square was last read).
            if (frames.isEmpty() && t0 - cornersAt > LOCK_MS) {
                if (lock != null && (size != lockSize || t0 - lockAt > LOCK_MS)) lock = null
                val shot = lock?.let { screen.captureArea(it) } ?: screen.capture()?.let { TopScreen.Shot(0, 0, it) }
                px = shot?.pixels
                val r = if (shot == null) null else safeDecode(shot.pixels, if (near >= 0) near - shot.top else -1)
                if (r != null && result !is StripDecoder.Result.Ok) result = r
                val f = (r as? StripDecoder.Result.Ok)?.frame?.takeIf { it.crcOk }
                if (f != null) {
                    near = f.row + shot!!.top
                    lock = lockAround(f, shot.left, shot.top)
                    lockSize = size
                    lockAt = t0
                    frames += CornerAreas.moved(f, shot.left, shot.top)
                }
            }
            val read = frames.isNotEmpty()
            // Why a read failed, now and then, for the log (long-press the status line).
            if (!read && t0 - whyAt > 5000) {
                whyAt = t0
                val why = when (result) {
                    null -> "capture failed"
                    is StripDecoder.Result.Failed -> result.reason
                    is StripDecoder.Result.Ok -> result.frame.let { "damaged v${it.version} cell ${it.cellPx} at ${it.x.toInt()},${it.row}" }
                }
                android.util.Log.i("ThorCompanion", "read: $why")
            }
            synchronized(captures) {
                captures.addLast(longArrayOf(t0, SystemClock.uptimeMillis() - t0))
                while (captures.first()[0] < t0 - 60_000) captures.removeFirst()
            }
            if (read) {
                misses = 0
                frameAt = SystemClock.uptimeMillis()
            }
            for (frame in frames) {
                // Something new on the data square (a part or a message): more is likely to follow.
                // (The position changes all the time while walking: that says nothing.)
                val shown = frame.seq * 16 + frame.part
                if (shownLast[frame.version] != shown) {
                    shownLast[frame.version] = shown
                    if (frame.version != StripDecoder.POSITION_VERSION) changedAt = SystemClock.uptimeMillis()
                }
                val parts = assemblerOf(frame.version)
                // The short and packed forms (P..., S..., B..., Z..., several in a Y...) written out again.
                for (message in parts.add(frame)?.let { Compact.expandAll(it) } ?: emptyList()) {
                    if (seenAt == 0L) seenAt = SystemClock.uptimeMillis()
                    messageAt = SystemClock.uptimeMillis()
                    if (game != Game.UNKNOWN && game != Game.IN_WORLD) game = Game.IN_WORLD
                    if (away) { away = false; runOnUiThread { render() } }
                    if (message.startsWith("TP2|")) {
                        // Only your position (the top-left corner): into the last status.
                        if (!awaitStatus) moved(message)
                    } else if (message.startsWith("TS1|") || !awaitStatus) {
                        handle(message, parts.lastSeq)
                        // Who is playing is known now: what came before the status is theirs.
                        if (message.startsWith("TS1|") && awaitStatus) {
                            awaitStatus = false
                            runOnUiThread { loadingSince = SystemClock.uptimeMillis() }
                            while (held.isNotEmpty()) handle(held.removeFirst(), -1)
                        }
                    } else if (!message.startsWith("TW1|") && !message.startsWith("TI1|") && !message.startsWith("TA1|")) {
                        // Until the status says which character this is, nothing is kept or shown:
                        // it could be another character's bags than the ones the app holds.
                        held.addLast(message)
                        while (held.size > 40) held.removeFirst()
                    }
                    // While the start-up card shows, each message may tick something off.
                    if (!ready) runOnUiThread { if (!ready) render() }
                }
            }
            if (!read && ++misses == 4 && !barMode && listening == null && SystemClock.uptimeMillis() >= quietUntil) {
                near = -1
                val why = when {
                    px == null -> "the capture failed"
                    result is StripDecoder.Result.Failed -> result.reason
                    else -> "the data was damaged"
                }
                runOnUiThread { say("No data from the addon ($why). Is the game open with the Forever Companion addon on?") }
            }
            // Reading the screen is what costs battery, so look once a second while
            // nothing is going on. The addon shows each part of a longer message for
            // about a third of a second, so read fast while one is coming in, and
            // right after a tap, when the bags are about to change. Slower still
            // while the game isn't showing the square at all.
            // The addon shows each part once (its fast speed) as long as the square keeps
            // changing, so keep reading fast for a moment after anything new.
            val fast = assembler.waiting || cornerParts.values.any { it.waiting } || SystemClock.uptimeMillis() - lastKeyAt < 3000 ||
                SystemClock.uptimeMillis() - changedAt < BUSY_MS
            // While you walk the position changes all the time: look more often, so the
            // arrow on the map keeps up, and slow down again once you stand still.
            val walking = SystemClock.uptimeMillis() - movedAt < 3000
            // While the square isn't there (the game starting, the character screen, a
            // loading screen) the app leaves the game's screen alone: taking screenshots
            // of it then crashed the game as it loaded into the world. After the square
            // goes away it waits a while, then looks only now and then until it is back.
            // Only a square that isn't there counts: a damaged read (the square changed
            // while it was taken) is read again at the usual pace.
            if (result is StripDecoder.Result.Ok) {
                absent = 0; seenSquare = true
                barMode = result.frame.version == StripDecoder.BAR_VERSION || t0 - cornersAt < LOCK_MS
            } else absent++
            // Gone for a few seconds: logged out, the character screen or a loading screen.
            // The card stands in for the tabs until the game says again who is playing,
            // so the last character's bags are not shown (or tapped) for the next one.
            // A square that stays but says nothing new is no game either: a closed game can
            // leave its last picture on the top screen. The addon sends its status at least
            // every 10 s, so nothing new for longer means it is gone.
            val stale = messageAt != 0L && SystemClock.uptimeMillis() - messageAt > STALE_MS
            // While the microphone listens (and a little after), the game may stand still: the
            // speech service takes the sound, and the game can pause for that. Not "gone".
            val speaking = listening != null || SystemClock.uptimeMillis() < quietUntil
            // The colour bar is only there while something is sent: its absence says nothing
            // (the status comes every 10 s, so "stale" still notices a game that is gone).
            if ((absent == 3 && seenSquare && !barMode || stale) && !away && !speaking) {
                away = true
                awaitStatus = true
                fresh.clear()
                runOnUiThread { startLoading() }
            }
            val period = when {
                game == Game.LOADING && result !is StripDecoder.Result.Ok -> 1000L
                // Closed or on the character screen: fewer looks, but often enough not to miss the
                // addon's first messages after a login when the file events say nothing (or come late).
                game == Game.CLOSED && result !is StripDecoder.Result.Ok -> 5000L
                game == Game.STARTING && result !is StripDecoder.Result.Ok -> 1000L
                absent > 0 && !seenSquare -> 1000L
                // The bar comes and goes: look a few times a second for the next message
                // (only its corner is copied, so this is cheap; the addon shows a message
                // that comes after a quiet spell for at least 0.6 s).
                // The corner squares too come and go; only four small parts are copied, so looking
                // often is cheap (the position stays 0.4 s, the rest at least 0.6 s).
                t0 - cornersAt < LOCK_MS -> CORNERS_MS
                barMode && absent > 0 && lock != null -> 400L
                barMode && absent > 0 -> 1000L
                absent in 1..4 -> 1000L
                absent == 5 -> 10_000L
                absent > 5 -> 5000L
                fast -> 200L
                // The game is back but its status (who is playing) slipped by: read
                // fast for the next one, so what is held back can go to the right character.
                held.isNotEmpty() -> 200L
                away -> 5000L
                walking -> 250L
                else -> 1000L
            }
            SystemClock.sleep((period - (SystemClock.uptimeMillis() - t0)).coerceIn(30, period))
        }
    }

    /**
     * The corner of the picture to copy next: the [frame] just read (found in a copy that
     * started at [left], [top]) with room around it, for the bar (80 x 3 cells), or for the
     * dark square or line (20 x 20 or 400 x 1 cells) the older shapes use.
     */
    private fun lockAround(frame: StripFrame, left: Int, top: Int): Rect {
        val cell = frame.cellPx
        val bar = frame.version == StripDecoder.BAR_VERSION
        val wide = if (bar) 80 else StripDecoder.COLS * StripDecoder.COLS
        val tall = if (bar) 3 else StripDecoder.COLS
        val x = left + frame.x
        val y = top + frame.row
        val margin = (4 * cell).toInt() + 8
        return Rect(
            (x - margin).toInt(), (y - cell - LOCK_ROWS).toInt(),
            (x + wide * cell + margin).toInt(), (y + tall * cell + LOCK_ROWS).toInt(),
        )
    }

    /**
     * What the game is doing, as its files tell it (see [GameFiles], run in this app
     * through [KeySender.open]): no timer, the kernel says when a file is opened or
     * written. UNKNOWN when that can't be followed (no game folder, or the game was
     * already running when the app started): then the screen alone tells, as before.
     */
    enum class Game { UNKNOWN, CLOSED, STARTING, LOADING, IN_WORLD, OUT }
    @Volatile private var game = Game.UNKNOWN
    /** The character entering the world or last in it ("Sanbica-Norton"). */
    @Volatile private var gameCharacter: String? = null
    /** When the game last changed its state (L), and when the addons loaded (A; 0 before). */
    @Volatile private var gameAt = 0L
    @Volatile private var addonsAt = 0L
    @Volatile private var watcher: Thread? = null
    @Volatile private var watchSocket: android.net.LocalSocket? = null

    /** True while the game's files say there is nothing on the top screen to read. */
    private fun blind(): Boolean {
        val now = SystemClock.uptimeMillis()
        return when (game) {
            // Loading into the world: a moment's rest right after the addons loaded (the addon
            // itself waits 3 s then). The long wait the standalone app had (its root screenshots
            // could crash the game while it set up the world) is not needed here: the picture is
            // read from Thor Forever's own view, which the game never sees. Waiting made the app
            // miss everything the addon sends right after a login when the file events came late.
            Game.LOADING -> addonsAt != 0L && now - addonsAt < 3000
            else -> false
        }
    }

    /** Follows the game through its file events ([GameFiles]), for as long as the app is shown. */
    private fun watchGame() {
        while (watcher === Thread.currentThread()) {
            val socket = KeySender.open(this)
            if (socket == null) { game = Game.UNKNOWN; SystemClock.sleep(30_000); continue }
            watchSocket = socket
            try {
                val folder = getPreferences(MODE_PRIVATE).getString("gameFolder", null).orEmpty()
                socket.outputStream.write("W $folder\n".toByteArray())
                socket.outputStream.flush()
                socket.inputStream.bufferedReader().forEachLine { line ->
                    if (watcher === Thread.currentThread()) onGameEvent(line)
                }
            } catch (e: java.io.IOException) {
                // The watching ended (or the app stops): start again below.
            }
            runCatching { socket.close() }
            game = Game.UNKNOWN
            if (watcher === Thread.currentThread()) SystemClock.sleep(5000)
        }
    }

    private fun onGameEvent(line: String) {
        if (!line.startsWith("C ")) android.util.Log.i("ThorCompanion", "game: $line")
        val what = line.take(1)
        val arg = line.drop(2).trim()
        val now = SystemClock.uptimeMillis()
        val was = game
        when (what) {
            "F" -> {
                if (arg == "-") { game = Game.UNKNOWN; return }
                getPreferences(MODE_PRIVATE).edit().putString("gameFolder", arg).apply()
                game = Game.CLOSED
            }
            "R" -> game = Game.UNKNOWN
            "S" -> game = Game.STARTING
            "L" -> {
                game = Game.LOADING; gameCharacter = arg; addonsAt = 0L
                // The character is known before the game shows anything: their data, not the last one's.
                val name = arg.substringBefore('-')
                if (name.isNotEmpty() && name != keptFor) switchCharacter(name)
            }
            "A" -> {
                // Also after a /reload, which writes the saved settings (O) and loads them again.
                if (game == Game.LOADING || game == Game.OUT || game == Game.STARTING) {
                    game = Game.LOADING; gameCharacter = arg; addonsAt = now
                    val name = arg.substringBefore('-')
                    if (name.isNotEmpty() && name != keptFor) switchCharacter(name)
                }
            }
            "O" -> if (game == Game.IN_WORLD || game == Game.LOADING || game == Game.UNKNOWN) game = Game.OUT
            "X" -> game = Game.CLOSED
            else -> return
        }
        gameAt = now
        if (game == was && what != "L" && what != "A") return
        // Out of the world: the card stands in for the tabs until the next character is in.
        if (game != Game.UNKNOWN && game != Game.IN_WORLD && (was == Game.IN_WORLD || was == Game.UNKNOWN || what == "L")) {
            away = true
            awaitStatus = true
            fresh.clear()
            runOnUiThread { startLoading() }
        } else runOnUiThread { if (!ready) render() }
    }

    /**
     * Your position from the top-left corner square (TP2|mapID|x|y|facing): the same
     * fields as in the status, so the last status with these in it is taken as a new one.
     */
    private fun moved(message: String) {
        val p = message.split('|')
        val status = lastMessages["TS1|"]?.split('|')?.toMutableList() ?: return
        if (p.size < 4 || status.size < 8) return
        status[4] = p[1]; status[5] = p[2]; status[6] = p[3]; status[7] = p.getOrElse(4) { "" }
        handle(status.joinToString("|"), -1)
    }

    /**
     * The decoder, never throwing: the companion runs inside Thor Forever, so a crash
     * here took the game down with it. A mistake in it is logged and counts as no read.
     */
    private fun safeDecode(px: Pixels, near: Int): StripDecoder.Result = try {
        StripDecoder.decode(px, near)
    } catch (e: RuntimeException) {
        android.util.Log.w("ThorCompanion", "decoder failed", e)
        StripDecoder.Result.Failed("decoder error: $e")
    }

    /** The addon's data square in a whole picture: in the top-right corner while it uses the corners. */
    private fun dataFrame(px: Pixels): StripFrame? =
        if (SystemClock.uptimeMillis() - cornersAt < LOCK_MS) runCatching { CornerAreas.dataFrame(px, cornerCell) }.getOrNull()
        else (safeDecode(px, -1) as? StripDecoder.Result.Ok)?.frame?.takeIf { it.crcOk }

    /** Messages that came while the app waits for the status (see [awaitStatus]), oldest first. */
    private val held = ArrayDeque<String>()

    /** One whole message from the addon (runs on the reading thread); [seq] is its frame's, -1 for one that was held back. */
    /**
     * For the screenshot test (CompanionScreensTest): shows [messages] as if the game had just
     * sent them, without reading the top screen, on the tab named [tab] ("BAGS", "MAP"...).
     * With [loaded] false the start-up card stays, ticking off what the messages brought.
     * The Craft tab: [craft] is its profession, [byGear] and [target] its enchanting view,
     * [recipe] the recipe whose card is open, [enchanting] an item an enchantment is going on,
     * [list] the crafting list's entries (as kept) and [sheet] the pop-up open ("list").
     */
    internal fun preview(messages: List<String>, tab: String, loaded: Boolean = true, craft: String? = null,
                         byGear: Boolean = false, target: Int? = null, recipe: String? = null, enchanting: String? = null,
                         lastEnchant: String? = null, replace: Boolean = false, list: List<String> = emptyList(), sheet: String? = null,
                         done: List<String> = emptyList(), exitAsked: Boolean = false) {
        frameAt = SystemClock.uptimeMillis()
        craftProfession = craft
        craftByGear = byGear
        craftTarget = target
        seenAt = frameAt
        game = Game.IN_WORLD
        for (m in messages) {
            handle(m, -1)
            if (m.startsWith("TS1|")) { awaitStatus = false; gameCharacter = m.split('|').getOrNull(1) }
        }
        if (loaded) {
            bagsFresh = true
            finishLoading()
            for ((_, b) in tabs) b.alpha = 1f
        }
        if (lastEnchant != null) getPreferences(MODE_PRIVATE).edit().putString(craftKey("lastEnchant"), lastEnchant).putBoolean(craftKey("lastWasEnchant"), true).apply()
        if (replace) getPreferences(MODE_PRIVATE).edit().putBoolean("enchantReplace", true).apply()
        if (list.isNotEmpty()) saveCraftList(list.mapNotNull { CraftJob.parse(it) })
        craftDone.clear(); craftDone += done.mapNotNull { CraftJob.parse(it) }
        craftSheet = sheet
        if (craft != null && recipe != null && sheet == null) craftRecipe = recipesOf(craft).firstOrNull { it.name == recipe }
        if (craft != null && recipe != null && enchanting != null) craftRunning = CraftRun(CraftJob(craft, 0, 0, recipe, 1), 1, 1, 0, 0, on = enchanting)
            .also { if (list.isNotEmpty()) it.listed = Listed.NEXT }  // (the list's first entry being made)
        show(Panel.valueOf(tab))
        if (exitAsked) askExit(true)
        saidAt = SystemClock.uptimeMillis() - NOTICE_MS - 1
        connected()
    }

    private fun handle(raw: String, seq: Int) {
        // A small change (TD1) becomes the whole message again, from the last one the app kept.
        val message = if (raw.startsWith("TD1|")) Delta.apply(raw) { lastMessages[it] } ?: return else raw
        val page = ItemNames.parse(message)
        if (message.startsWith("TS1|")) {
            movedAt = SystemClock.uptimeMillis()
            val name = message.split('|').getOrNull(1)
            if (name != null && name != "error" && name != "?" && name != keptFor) switchCharacter(name)
        }
        remember(message)
        keptKey(message)?.let { fresh += it }
        when {
            message.startsWith("TH1|") -> runOnUiThread { onChat(message) }
            message.startsWith("TM1|") -> ZoneMap.parse(message)?.let { runOnUiThread { onMap(it) } }
            message.startsWith("TP1|") -> CharacterInfo.parse(message)?.let { runOnUiThread { onCharacter(it, gear) } }
            message.startsWith("TQ1|") -> Gear.parse(message)?.let { runOnUiThread { onCharacter(character, it) } }
            message.startsWith("TW1|") -> MapPicture.parse(message)?.let { takeMapPicture(it, message) }
            message.startsWith("TI1|") -> IconPicture.parse(message)?.let { if (seq >= 0) takeIcons(it, seq) }
            message.startsWith("TA1|") -> AreaPicture.parse(message)?.let { if (seq >= 0) takeAreas(it, seq) }
            message.startsWith("TE1|") -> message.split('|').let { f ->
                f.getOrNull(1)?.toIntOrNull()?.let { n ->
                    chatBox = ChatBox(n, f.getOrNull(2) == "open", f.getOrNull(3).orEmpty(), f.getOrNull(4)?.toIntOrNull())
                }
            }
            message.startsWith("TU1|") -> ItemUse.parse(message)?.let { runOnUiThread { onUse(it) } }
            message.startsWith("TC2|") -> Cooldowns.parse(message)?.let { runOnUiThread { onCooldowns(it) } }
            message.startsWith("TL1|") -> QuestLog.parse(message)?.let {
                questFirst = it
                val all = questPages.whole(it)
                runOnUiThread { onQuests(all) }
            }
            message.startsWith("TL2|") -> if (questPages.add(message) == true) questFirst?.let {
                val all = questPages.whole(it)
                runOnUiThread { onQuests(all) }
            }
            message.startsWith("TV1|") -> SpellPlan.parse(message)?.let { runOnUiThread { onSpells(it) } }
            message.startsWith("TT1|") -> ItemTips.parse(message)?.let { tips.addAll(it) }
            message.startsWith("TF1|") -> Professions.parse(message)?.let { runOnUiThread { onProfessions(it) } }
            message.startsWith("TR1|") -> RecipePage.parse(message)?.let { p -> keptKey(message)?.let { k -> runOnUiThread { onRecipePage(k, p) } } }
            message.startsWith("TK1|") -> BankCounts.parse(message)?.let { runOnUiThread { onBank(it) } }
            page != null -> {
                val changed = names.addAll(page)
                runOnUiThread { onNames(changed) }
            }
            else -> runOnUiThread { onMessage(message) }
        }
    }

    /** The addon version the game runs, while it isn't the one this app brought (else null). */
    @Volatile private var gameAddon: String? = null

    private fun onMessage(message: String) {
        if (message.startsWith("TB1|") && !bagsFresh) {
            bagsFresh = true
            if (panel == Panel.BAGS) render()
        }
        val parsed = GameState.parse(message, state)
        if (parsed != null && message.startsWith("TS1|")) {
            // The game still running an older addon than the one this app brought: say so, once.
            val bundled = AddonInstaller.version(this)
            // Until it is the new one, "Connected" says so too: the old addon may lack what this app expects.
            gameAddon = if (bundled != null && parsed.addonVersion != bundled) parsed.addonVersion ?: "?" else null
            if (!addonWarned && bundled != null && parsed.addonVersion != bundled) {
                addonWarned = true
                say("The game is running an older Forever Companion addon (${parsed.addonVersion ?: "an old version"}); " +
                    "this app has $bundled. Close the game and start it again to get the new one.", notice = true)
            }
            // Bags kept from before a restart are right when the addon's checksum of its last bags says so.
            val kept = lastMessages["TB1|"]
            val matches = parsed.bagHash != null && kept != null && GameState.hash(kept) == parsed.bagHash
            if (!bagsFresh && matches) {
                bagsFresh = true
                if (panel == Panel.BAGS) render()
            }
            // Bags that stay different from the game's for a while (a missed change): say so, and offer to load them.
            if (matches || parsed.bagHash == null) bagsDifferSince = 0L
            else if (bagsDifferSince == 0L) bagsDifferSince = SystemClock.uptimeMillis()
            else if (bagsFresh && SystemClock.uptimeMillis() - bagsDifferSince > 8000) {
                bagsFresh = false
                if (panel == Panel.BAGS) render()
            }
            // A new game session (login or /reload): the start-up card checks everything again.
            // (Not while the card shows anyway: it would start over what it already shows.)
            if (liveSession != null && parsed.session != null && parsed.session != liveSession && ready) startLoading()
            if (parsed.session != null) liveSession = parsed.session
            if (!live) { live = true; loadingSince = SystemClock.uptimeMillis() }
            // The path walked is per game session: a new login or /reload starts it afresh.
            val prefs = getPreferences(MODE_PRIVATE)
            if (parsed.session != null && parsed.session != prefs.getString("trailSession", null)) {
                prefs.edit().putString("trailSession", parsed.session).apply()
                trail.paths.clear()
                trailUnsaved = 1
                saveTrail()
            }
        }
        if (parsed == null) say("Connected, but the addon sent something unexpected: ${message.take(80)}") else connected()
        if (parsed == null || parsed == state) return
        val moved = parsed.copy(x = state?.x, y = state?.y, facing = state?.facing) == state
        state = parsed
        val id = parsed.mapId
        if (id != null && parsed.x != null && parsed.y != null && trail.add(id, parsed.x!!, parsed.y!!)) {
            if (++trailUnsaved >= 30) saveTrail()
        }
        // Walking only moves the arrow on the map; the other tabs don't show where you are.
        if (!moved) render() else if (panel == Panel.MAP && mapView != null) updateMap()
        updateCompass()
    }

    private fun mapFile(id: Int) = java.io.File(java.io.File(filesDir, "maps").apply { mkdirs() }, "$id.png")

    /**
     * The world map is open in the game: one full screenshot, the map cut out of it and
     * kept as that zone's background. Only when the same screenshot still shows the
     * addon saying so, so a map closed in between is not taken. Runs on the reader thread.
     */
    private fun takeMapPicture(pic: MapPicture, message: String) {
        fun fail(why: String) = runOnUiThread {
            pictureNote = "Map picture not taken: $why"
            if (panel == Panel.MAP) updateMap()
        }
        val px = screen.capture(rows = 4000) ?: return fail("the screenshot failed")
        val frame = dataFrame(px) ?: return fail("the data square was not readable in the screenshot")
        if (Compact.expand(String(frame.payload)) != message) return fail("the map was closed or changed before the screenshot")
        val r = pic.onScreen(frame)
        val l = r[0].coerceIn(0, px.width); val t = r[1].coerceIn(0, px.height)
        val w = r[2].coerceIn(0, px.width) - l; val h = r[3].coerceIn(0, px.height) - t
        if (w < 50 || h < 50) return fail("the map is outside the screen (${r.joinToString(",")} on ${px.width}x${px.height})")
        val pixels = IntArray(w * h) { i -> px.rgb(l + i % w, t + i / w) or (0xFF shl 24) }
        val bitmap = android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888)
        val saved = runCatching { mapFile(pic.mapId).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        if (saved.isFailure) return fail("could not save it (${saved.exceptionOrNull()?.message})")
        runOnUiThread {
            pictures.remove(pic.mapId)
            pictureNote = "Took a picture of map ${pic.mapId} (${w}x$h)"
            if (panel == Panel.MAP) updateMap()
        }
    }

    /**
     * The addon shows item icons in a grid for a few seconds: one full screenshot, and
     * each icon cut out of it and kept. Only when the same screenshot still shows that
     * message ([seq]), so icons that went away in between are not taken. Runs on the
     * reader thread.
     */
    private fun takeIcons(pic: IconPicture, seq: Int) {
        fun done(note: String) = runOnUiThread {
            say(note)
            if (panel == Panel.BAGS || panel == Panel.CHARACTER) render()
        }
        val px = screen.capture(rows = 4000) ?: return done("Item icons not taken: the screenshot failed")
        val frame = dataFrame(px)
        if (frame == null || frame.seq != seq) return done("Item icons not taken: they went away before the screenshot")
        // The icons sit on black: if the gaps around them aren't black, the grid had gone
        // (the square can still show its message) and these would be bits of the world.
        fun dark(x: Int, y: Int) = x !in 0 until px.width || y !in 0 until px.height ||
            px.rgb(x, y).let { Color.red(it) < 48 && Color.green(it) < 48 && Color.blue(it) < 48 }
        var light = 0
        for (i in pic.itemIds.indices) {
            val r = pic.onScreen(frame, i)
            if (!dark(r[0] - 3, (r[1] + r[3]) / 2) || !dark((r[0] + r[2]) / 2, r[1] - 3)) light++
        }
        if (light * 5 > pic.itemIds.size) return done("Item icons not taken: they went away before the screenshot")
        var taken = 0
        for ((i, id) in pic.itemIds.withIndex()) {
            val r = pic.onScreen(frame, i)
            // One pixel in from each side: the edges blend into the black around the icon.
            val l = r[0] + 1; val t = r[1] + 1; val w = r[2] - r[0] - 2; val h = r[3] - r[1] - 2
            if (w < 8 || h < 8 || l < 0 || t < 0 || l + w > px.width || t + h > px.height) continue
            val pixels = IntArray(w * h) { k -> px.rgb(l + k % w, t + k / w) or (0xFF shl 24) }
            icons.put(id, android.graphics.Bitmap.createBitmap(pixels, w, h, android.graphics.Bitmap.Config.ARGB_8888))
            taken++
        }
        done(if (taken == 0) "Item icons not taken: they were outside the screen" else "Took $taken item icons")
    }

    private fun areaFile(id: Int) = java.io.File(java.io.File(filesDir, "areas").apply { mkdirs() }, "$id.txt")

    /** Quest areas taken so far, per map: the quest's name in its q places, and its area (main thread only). */
    private val areas = HashMap<Int, LinkedHashMap<String, AreaMask>>()

    private fun areasOf(id: Int): LinkedHashMap<String, AreaMask> = areas.getOrPut(id) {
        val out = LinkedHashMap<String, AreaMask>()
        runCatching {
            areaFile(id).takeIf { it.exists() }?.forEachLine { line ->
                AreaMask.load(line.substringAfter('\t', ""))?.let { out[line.substringBefore('\t')] = it }
            }
        }
        out
    }

    /**
     * The addon shows the quests' areas in small copies of the zone map for a few seconds:
     * one full screenshot, each copy cut out of it and kept as that quest's area. Only when
     * the same screenshot still shows that message ([seq]), so copies that went away in
     * between are not taken. Runs on the reader thread.
     */
    private fun takeAreas(pic: AreaPicture, seq: Int) {
        fun done(note: String) = runOnUiThread {
            pictureNote = note
            if (panel == Panel.MAP) updateMap()
        }
        val px = screen.capture(rows = 4000) ?: return done("Quest areas not taken: the screenshot failed")
        val frame = dataFrame(px)
        if (frame == null || frame.seq != seq) return done("Quest areas not taken: they went away before the screenshot")
        // The copies sit on black: if the gaps around them aren't black, the grid had gone.
        fun dark(x: Int, y: Int) = x !in 0 until px.width || y !in 0 until px.height ||
            px.rgb(x, y).let { Color.red(it) < 48 && Color.green(it) < 48 && Color.blue(it) < 48 }
        var light = 0
        for (i in pic.titles.indices) {
            val r = pic.onScreen(frame, i)
            if (!dark(r[0] - 3, (r[1] + r[3]) / 2) || !dark((r[0] + r[2]) / 2, r[1] - 3)) light++
        }
        if (light * 5 > pic.titles.size) return done("Quest areas not taken: they went away before the screenshot")
        val taken = LinkedHashMap<String, AreaMask>()
        for ((i, title) in pic.titles.withIndex()) {
            val r = pic.onScreen(frame, i)
            val l = r[0]; val t = r[1]; val w = r[2] - r[0]; val h = r[3] - r[1]
            if (w < 16 || h < 16 || l < 0 || t < 0 || l + w > px.width || t + h > px.height) continue
            taken[title] = AreaMask.fromPixels(IntArray(w * h) { k -> px.rgb(l + k % w, t + k / w) }, w, h)
        }
        if (taken.isEmpty()) return done("Quest areas not taken: they were outside the screen")
        val shaped = taken.values.count { !it.empty }
        runOnUiThread {
            val kept = areasOf(pic.mapId)
            for ((title, m) in taken) { kept.remove(title); kept[title] = m }
            while (kept.size > 60) kept.remove(kept.keys.first())
            runCatching { areaFile(pic.mapId).writeText(kept.entries.joinToString("\n") { "${it.key}\t${it.value.save()}" }) }
            pictureNote = "Took the areas of ${taken.size} quest${if (taken.size == 1) "" else "s"}" +
                if (shaped < taken.size) " (${taken.size - shaped} without a shape in the game)" else ""
            if (panel == Panel.MAP) updateMap()
        }
    }

    /** How much longer a unit across [map] is than one down (the zone maps are about 3:2). */
    private fun aspect(map: ZoneMap) = if (map.width > 0 && map.height > 0) map.width.toDouble() / map.height else 1.5

    /** A quest area's place moved to the point of its real shape nearest to [x], [y] (where you are when inside). */
    private fun nearPoint(map: ZoneMap, p: MapPlace, x: Double, y: Double): MapPlace {
        if (p.kind != 'q') return p
        val near = areasOf(map.mapId)[p.label]?.takeIf { !it.empty }?.nearest(x, y, aspect(map)) ?: return p
        return p.copy(x = near[0], y = near[1])
    }

    /** What happened to the last map picture, shown under the map (the status line changes too often). */
    private var pictureNote = ""
    private var pictureLine: TextView? = null

    /** Zone pictures loaded so far (null: there is none yet). */
    private val pictures = HashMap<Int, android.graphics.Bitmap?>()

    private fun picture(id: Int): android.graphics.Bitmap? = pictures.getOrPut(id) {
        mapFile(id).takeIf { it.exists() }?.let { android.graphics.BitmapFactory.decodeFile(it.absolutePath) }
    }

    private fun onCharacter(c: CharacterInfo?, g: List<GearItem>) {
        connected()
        if (c == character && g == gear) return
        character = c
        gear = g
        if (panel == Panel.CHARACTER) render()
    }

    private fun onMap(map: ZoneMap) {
        connected()
        if (map == zoneMap) return
        zoneMap = map
        if (!followCorpse(map) && panel == Panel.MAP) render()
        updateCompass()
    }

    /** While you are a ghost: the map leads you to your corpse (set when that began). */
    private var ghost = false
    /** The tab you were on when the map turned to your corpse by itself, to go back to once you live again. */
    private var tabBeforeDeath: Panel? = null
    /** ✕ on the corpse line: no leading until the next death. */
    private var corpseHidden = false

    /**
     * Idea 6 (Jesse 2026-10-17): once you release your spirit, the Map tab opens by itself
     * and leads you to your corpse (a ring, and how far and which way on the line on top),
     * zoomed to show you both; also when the corpse is in the zone next door. Back to the
     * tab you were on once you live again. True when it drew the tab itself.
     */
    private fun followCorpse(map: ZoneMap): Boolean {
        if (map.ghost == ghost) return false
        ghost = map.ghost
        corpseHidden = false
        if (ghost) {
            android.util.Log.i("ThorCompanion", "ghost: leading to the corpse (${map.corpse?.label ?: "not on this map"})")
            val c = map.corpse
            val x = state?.x; val y = state?.y
            if (c != null && x != null && y != null && x > 0) {
                val far = maxOf(Math.abs(c.x - x), Math.abs(c.y - y) * 1.5)
                getPreferences(MODE_PRIVATE).edit().putBoolean("mapClose", far < 0.38)
                    .putFloat("mapSpan", (far * 2.6).coerceIn(MapView.MIN_SPAN, 1.0).toFloat()).apply()
            }
            mapPan = null
            say(if (c != null) "You died: the map leads you to your corpse" else "You died: the game doesn't show where your corpse is")
            // Not while you type a chat message: that box would close.
            if (composing != null) return false
            tabBeforeDeath = panel.takeIf { it != Panel.MAP }
            show(Panel.MAP)
            return true
        }
        android.util.Log.i("ThorCompanion", "alive again")
        say("You're alive again")
        val back = tabBeforeDeath
        tabBeforeDeath = null
        if (back != null && panel == Panel.MAP && composing == null) { show(back); return true }
        return false
    }

    private fun onChat(message: String) {
        connected()
        if (chat.add(message) == true && panel == Panel.CHAT) render()
    }

    /** Bag items on cooldown: when each is ready again (uptime ms). */
    private var readyAt: Map<Int, Long> = emptyMap()

    private fun onCooldowns(left: Map<Int, Long>) {
        connected()
        val now = SystemClock.uptimeMillis()
        val next = left.mapValues { now + it.value * 1000 }
        // The same cooldowns again (a resend) give the same times give or take a second.
        if (next.keys == readyAt.keys && next.all { (id, t) -> Math.abs(t - (readyAt[id] ?: 0)) < 2000 }) return
        readyAt = next
        if (panel == Panel.BAGS) render()
    }

    private fun cooldownLeft(itemId: Int): Long = ((readyAt[itemId] ?: 0) - SystemClock.uptimeMillis()) / 1000

    /** The last tap on a bag item, until the addon says what came of it. */
    private var tapped: Pair<Int, String>? = null
    private var lastUse = -1

    private fun onUse(use: ItemUse) {
        if (use.n == lastUse) return
        lastUse = use.n
        val label = names[use.itemId]?.name ?: tapped?.second ?: "the item"
        tapped = null
        say(when (use.result) {
            "ok" -> "Used $label"
            "nouse" -> "$label can't be used: it does nothing when used (long-press it for details)"
            else -> "Couldn't use $label: ${use.result}"
        })
    }

    private fun onSpells(plan: SpellPlan) {
        connected()
        if (plan == spells) return
        spells = plan
        if (panel == Panel.QUESTS || panel == Panel.CHARACTER) render()
    }

    private fun onQuests(log: QuestLog) {
        connected()
        if (log == quests) return
        quests = log
        if (panel == Panel.QUESTS || panel == Panel.CHARACTER) render()
    }

    private fun onNames(changed: Boolean) {
        connected()
        if (changed && (panel == Panel.BAGS || panel == Panel.CHARACTER || panel == Panel.CRAFT)) render()
    }

    private fun onProfessions(p: Professions) {
        connected()
        if (p == professions) return
        professions = p
        if (panel == Panel.CRAFT) render()
    }

    /** A page of recipes: pages of the same profession read before it (the window was opened again since) go. */
    private fun onRecipePage(key: String, page: RecipePage) {
        connected()
        if (recipePages[key] == page) return
        var dropped = false
        for ((k, old) in recipePages) {
            if (k != key && old.profession == page.profession && (old.readAt < page.readAt || old.page > page.pages)) {
                recipePages.remove(k)
                lastMessages.remove(k)
                dropped = true
            }
        }
        if (dropped) saveLast()
        recipePages[key] = page
        if (panel == Panel.CRAFT) render()
    }

    private fun onBank(b: BankCounts) {
        connected()
        if (b == bank) return
        bank = b
        if (panel == Panel.CRAFT) render()
    }

    private fun show(p: Panel) {
        if (p != Panel.CHAT) closeCompose()
        if (p != panel && exitAsk.visibility == View.VISIBLE) askExit(false)  // another tab: Exit's question goes
        panel = p
        for ((key, b) in tabs) {
            b.background = Theme.button(this, primary = key == p, radiusDp = Theme.TAB_RADIUS_DP)
            b.setTextColor(if (key == p) Color.WHITE else Theme.GOLD_TEXT)
        }
        scroll.scrollTo(0, 0)
        render()
    }

    /**
     * Notes when a finger is on the screen. The Craft tab is drawn again whenever the game
     * sends something (bags, progress), which swapped the views under a scrolling finger;
     * it now waits until the finger lifts. The tap's own click (posted while the UP is
     * handled) still comes first, then the waiting redraw.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val action = ev.actionMasked
        if (action == android.view.MotionEvent.ACTION_DOWN) touching = true
        val end = action == android.view.MotionEvent.ACTION_UP || action == android.view.MotionEvent.ACTION_CANCEL
        if (end) touching = false
        val handled = super.dispatchTouchEvent(ev)
        if (end && renderWaiting) { renderWaiting = false; content.post { render() } }
        return handled
    }

    private fun render() {
        if (touching && panel == Panel.CRAFT) { renderWaiting = true; return }
        renderWaiting = false
        // On the Map tab the tab is stretched to the bottom of the screen, and the map takes
        // what the buttons above it leave (the other tabs scroll as before).
        scroll.isFillViewport = panel == Panel.MAP
        content.removeAllViews()
        toolbar.removeAllViews()
        // While you type a chat message, the box below stays as it is.
        if (composing == null) footer.removeAllViews()
        mapView = null
        placeCard = null
        questLine = null
        updateLoadButton()
        updateBadges()
        val s = state
        if (!ready && renderLoading()) return
        if (s == null) {
            content.addView(empty("Waiting for the game…", "Open WoW Forever with the Forever Companion addon on. If it is already open, tap Load from the game."))
            return
        }
        when (panel) {
            Panel.BAGS -> renderBags(s)
            Panel.CHARACTER -> renderCharacter(s)
            Panel.MAP -> renderMap()
            Panel.CHAT -> renderChat()
            Panel.QUESTS -> renderQuests()
            Panel.CRAFT -> renderCraft(s)
        }
    }

    /**
     * The start-up card: until everything the tabs show has come from the game (or was
     * kept and the addon's checksums say it is still right), it stands in for the tabs,
     * so nothing old can be tapped. Shown again for another character or a /reload.
     */
    @Volatile private var ready = false
    /** Whether a status came from the game since the app started (kept data alone counts for nothing). */
    private var live = false
    /** When the first message of any kind came from the game since the app started (0 before). */
    @Volatile private var seenAt = 0L
    /** True until a status came after the app started or after the square was gone: until then nobody knows who is playing. */
    @Volatile private var awaitStatus = true
    /** The square has been gone for a few seconds (logged out, the character screen, a loading screen). */
    @Volatile private var away = false
    private var liveSession: String? = null
    private var loadingSince = 0L
    /** The character whose data was put away for the one now logged in, for the card's first line. */
    private var switchedFrom: String? = null
    private val loadingTick = Runnable { if (!ready) render() }

    private fun startLoading() {
        ready = false
        loadingSince = SystemClock.uptimeMillis()
        render()
    }

    private fun finishLoading() {
        ready = true
        switchedFrom = null
        scroll.removeCallbacks(loadingTick)
        val s = state
        if (s?.freeSlots != null) {
            val unnamed = s.items.map { it.itemId }.distinct().count { names[it] == null }
            say(if (unnamed > 0) "Ready; $unnamed item names are still coming in" else "Everything is loaded", notice = true)
        }
    }

    /** Draws the start-up card in place of the tab; false once there is nothing to wait for (then the tab is drawn). */
    private fun renderLoading(): Boolean {
        for ((_, b) in tabs) b.alpha = 0.35f
        val s = state
        val known = live && !awaitStatus && s != null
        val parts = if (known) Readiness.parts(s, lastMessages, bagsFresh, fresh) { names[it] != null } else null
        // An addon without checksums (older than 0.18.7) gives nothing to wait for.
        if (known && (parts == null || Readiness.done(parts))) {
            finishLoading()
            for ((_, b) in tabs) b.alpha = 1f
            return false
        }
        scroll.removeCallbacks(loadingTick)
        scroll.postDelayed(loadingTick, 1000)
        val box = card().apply { background = Theme.panel(context, gold = true); setPadding(dp(18), dp(16), dp(18), dp(16)) }
        val seen = seenAt != 0L
        val who = s?.name?.takeIf { known && it != "?" }
        val g = game
        val entering = gameCharacter?.substringBefore('-')
        box.addView(line(when {
            g == Game.CLOSED -> "WoW is closed"
            g == Game.STARTING -> "WoW is starting"
            // From the moment the files say who enters the world until everything is in: one title.
            (g == Game.LOADING || g == Game.IN_WORLD) && entering != null -> "Getting $entering ready"
            g == Game.OUT && entering != null -> "$entering logged out"
            away -> "Waiting for a character"
            who != null -> "Getting $who ready"
            seen -> "Checking who is playing…"
            else -> "Looking for the game…"
        }, Theme.GOLD_TEXT, 18f).apply { typeface = Theme.heading(context); setPadding(0, 0, 0, 0) })
        val c = character
        val sub = when {
            g == Game.CLOSED -> "Start WoW Forever; the app follows it from there."
            g == Game.STARTING -> "Log in and choose a character."
            (g == Game.LOADING || g == Game.IN_WORLD) && entering != null -> listOfNotNull(
                when {
                    g == Game.LOADING && addonsAt == 0L -> "Loading into the world…"
                    g == Game.LOADING -> "Almost there: the addons are loading."
                    !known -> "In the world: the data is coming in."
                    else -> listOfNotNull(s?.level?.let { "Level $it" }, c?.race, c?.className).joinToString(" ")
                        .let { l -> listOf(l, zoneMap?.zone.orEmpty()).filter { it.isNotEmpty() }.joinToString("  ·  ") }
                },
                switchedFrom?.let { "$it's data is put away" },
            ).filter { it.isNotEmpty() }.joinToString("\n")
            g == Game.OUT -> "Choose a character, or quit the game. Their data is put away."
            away -> "No data from the game: logged out, on the character screen, a loading screen, or the game is closed. " +
                (keptFor?.let { "$it's data is put away until the game says who is playing." } ?: "")
            !seen -> "Open WoW Forever with the Forever Companion addon on."
            !known -> "Found the game; its status says which character is in (it comes right after loading, or within 10 s)."
            switchedFrom != null -> "Switched from $switchedFrom; their data is put away"
            else -> listOfNotNull(s?.level?.let { "Level $it" }, c?.race, c?.className).joinToString(" ").let { l ->
                listOf(l, zoneMap?.zone.orEmpty()).filter { it.isNotEmpty() }.joinToString("  ·  ")
            }
        }
        if (sub.isNotEmpty()) box.addView(line(sub, DIM, 13f).apply { setPadding(0, dp(4), 0, dp(10)) })
        // The login screen (and the character screen): type there from this screen, with the
        // keyboard down here, and fix typos before they go in.
        if ((g == Game.STARTING || g == Game.OUT) && composing == null) {
            box.addView(chip("⌨  Type in the game (password, names)") { typeInGame() }.apply {
                primary()
            }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(10) })
        }
        val rows = parts ?: if (seen && !away || g == Game.LOADING) PLACEHOLDER.map { Readiness.Part(it, Readiness.Status.MISSING, "waiting") } else emptyList()
        for (part in rows) {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val (mark, colour) = when (part.status) {
                Readiness.Status.READY -> "✓" to Theme.GOOD
                Readiness.Status.LOADING -> "…" to ACCENT
                Readiness.Status.MISSING -> "○" to DIM
            }
            r.addView(line("", TEXT, 14.5f).apply {
                text = android.text.SpannableStringBuilder(mark).apply {
                    setSpan(android.text.style.ForegroundColorSpan(colour), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    append("  ").append(part.title)
                }
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (part.detail.isNotEmpty()) r.addView(line(part.detail, DIM, 13f).apply { setPadding(dp(8), 0, 0, 0) })
            box.addView(View(this).apply { setBackgroundColor(STROKE) }, LinearLayout.LayoutParams(-1, 1))
            box.addView(r)
        }
        if (parts != null) {
            val done = parts.count { it.status == Readiness.Status.READY }
            box.addView(Bar(this).apply { value = done.toFloat() / parts.size; colour = ACCENT }, LinearLayout.LayoutParams(-1, dp(6)).apply { topMargin = dp(12) })
        }
        val waited = SystemClock.uptimeMillis() - (if (live) loadingSince else seenAt)
        val slow = seen && !away && g != Game.LOADING && waited > SLOW_MS
        box.addView(line(
            if (g == Game.CLOSED || g == Game.STARTING || g == Game.OUT) "The game's own files tell the app when you enter the world; until then it only glances at the screen now and then."
            else if (g == Game.LOADING) "The tabs open as soon as everything is in."
            else if (away) "The app looks again every few seconds and carries on as soon as the game is back."
            else if (slow) "The game sends what changed by itself. For the rest, one tap asks it for everything."
            else if (seen) "The tabs open as soon as everything is in. Nothing can be tapped before that, so no tap goes to an old bag slot."
            else "Already open? Then the data square may be hidden (/thor), or the app reads the other screen (hold the dot).",
            DIM, 12.5f).apply { setPadding(0, dp(12), 0, 0) })
        if (slow) {
            val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(12), 0, 0) }
            buttons.addView(chip("Load from the game") {
                pressKey(ActionKeys.REFRESH, onDone = { say("Asked the game for everything again…") }) { err -> say("Couldn't ask the game: $err") }
            }.apply { primary() })
            buttons.addView(chip("Open anyway") {
                finishLoading()
                for ((_, b) in tabs) b.alpha = 1f
                render()
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
            box.addView(buttons)
        }
        content.addView(box, cardParams())
        return true
    }

    /** When the status line last said something other than "Connected", which then stays for a while. */
    private var saidAt = 0L

    /** Data came in: the dot goes green, and the line says so unless it is still showing something you should read. */
    private fun connected() {
        if (SystemClock.uptimeMillis() - saidAt > NOTICE_MS) say(gameAddon?.let { "Connected · old addon $it in the game: close and start the game again" } ?: "Connected", notice = false) else say(status.text.toString(), notice = false)
    }

    /** The status line, with its dot: green while the game's data comes in, red when it stopped, amber before it ever came. */
    private fun say(text: String, notice: Boolean = true) {
        if (notice) android.util.Log.i("ThorCompanion", "say: $text")
        if (notice) saidAt = SystemClock.uptimeMillis()
        status.text = text
        val since = SystemClock.uptimeMillis() - frameAt
        val colour = when {
            frameAt == 0L -> Theme.WARN
            since < 5000 -> Theme.GOOD
            else -> Theme.BAD
        }
        (statusDot.background as GradientDrawable).setColor(colour)
        updateLoadButton()
        updateBadges()
    }

    /** Load from the game shows only while something is missing or old: then one tap asks the addon for everything. */
    private fun updateLoadButton() {
        // While the start-up card shows, its own button does this.
        val missing = ready && (state == null || state?.freeSlots == null || !bagsFresh || character == null || gear.isEmpty())
        loadButton.visibility = if (missing && exitAsk.visibility != View.VISIBLE) View.VISIBLE else View.GONE
    }

    /** Name, level, experience, item level, stats and what you wear. */
    private fun renderCharacter(s: GameState) {
        val c = character
        val classColour = c?.classFile?.let { classColour(it) } ?: TEXT
        val head = card()
        head.addView(line(s.name, classColour, 24f).apply { typeface = Theme.heading(context); setPadding(0, 0, 0, 0) })
        head.addView(line(listOfNotNull("Level ${s.level ?: "?"}", c?.race, c?.className).joinToString(" "), TEXT, 15f).apply { setPadding(0, dp(2), 0, 0) })
        c?.guild?.let { head.addView(line("<$it>", Theme.GOOD, 13f).apply { setPadding(0, dp(2), 0, 0) }) }
        content.addView(head, cardParams())
        levelCard()?.let { content.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }) }

        val facts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        facts.addView(fact("Item level", c?.itemLevel?.let { String.format(Locale.US, "%.1f", it) } ?: "?", ACCENT), weighted())
        facts.addView(fact("Gold", money(s), GOLD), weighted(1.6f))
        facts.addView(fact("Armor", c?.armor?.toString() ?: "?", TEXT), weighted())
        content.addView(facts, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (c != null && c.stats.any { it != null }) {
            val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, name) in listOf("Strength", "Agility", "Stamina", "Intellect").withIndex()) {
                stats.addView(fact(name, c.stats[i]?.toString() ?: "?", TEXT), weighted())
            }
            content.addView(stats, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }

        val worn = gear.associateBy { it.slot }
        if (worn.values.any { (it.durability ?: 100) <= 20 }) {
            content.addView(line("Some gear is almost broken: repair soon.", Theme.BAD, 15f, bold = true).apply { setPadding(dp(4), dp(10), 0, 0) })
        }
        content.addView(section("Equipment" + iconHint(gear.map { it.itemId })))
        val grid = GridLayout(this).apply { columnCount = 2 }
        for (slot in GEAR_SLOTS) {
            val g = worn[slot]
            val known = g?.let { names[it.itemId] }
            val dur = g?.durability
            val detail = listOfNotNull(g?.itemLevel?.takeIf { it > 0 }?.let { "ilvl $it" }, dur?.let { "$it%" }).joinToString("  ·  ")
            val quality = known?.let { qualityColour(it.quality) }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(6), dp(5), dp(8), dp(5))
                background = Theme.panel(context, edge = quality?.let { Theme.withAlpha(it, 130) } ?: STROKE)
            }
            tile.addView(icon(g?.itemId, quality, 36), LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(8) })
            if (g != null) {
                // Worn items do nothing on a tap, so a tap shows their details too.
                tile.setOnClickListener { showDetails(g.itemId) }
                tile.setOnLongClickListener { showDetails(g.itemId); true }
            }
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            words.addView(line(Gear.slotName(slot), DIM, 11f).apply { setPadding(0, 0, 0, 0) })
            words.addView(line(if (g == null) "Empty" else known?.name ?: "#${g.itemId}", quality ?: DIM, 13f).apply {
                setPadding(0, 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            })
            // Always three lines (an empty one kept invisible), so every box is as high as the others.
            words.addView(line(detail.ifEmpty { " " }, when {
                dur != null && dur <= 20 -> Theme.BAD
                dur != null && dur <= 50 -> Theme.WARN
                else -> DIM
            }, 11f).apply { setPadding(0, 0, 0, 0); maxLines = 1; if (detail.isEmpty()) visibility = View.INVISIBLE })
            tile.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        content.addView(grid)
    }

    /** WoW's class colours. */
    private fun classColour(file: String): Int = when (file) {
        "WARRIOR" -> Color.rgb(198, 155, 109); "PALADIN" -> Color.rgb(244, 140, 186); "HUNTER" -> Color.rgb(170, 211, 114)
        "ROGUE" -> Color.rgb(255, 244, 104); "PRIEST" -> Color.rgb(255, 255, 255); "DEATHKNIGHT" -> Color.rgb(196, 30, 58)
        "SHAMAN" -> Color.rgb(0, 112, 221); "MAGE" -> Color.rgb(63, 199, 235); "WARLOCK" -> Color.rgb(135, 136, 238)
        "MONK" -> Color.rgb(0, 255, 152); "DRUID" -> Color.rgb(255, 124, 10); "DEMONHUNTER" -> Color.rgb(163, 48, 201)
        "EVOKER" -> Color.rgb(51, 147, 127)
        else -> TEXT
    }

    /** The zone drawn from the addon's places, with the path walked and an arrow for you. */
    private fun renderMap() {
        val prefs = getPreferences(MODE_PRIVATE)
        // The zone and where you are, on the map itself (bottom left), so the map gets the room.
        val title = line("", TEXT, 15f, bold = true).apply { setPadding(0, 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        val view = MapView(this).apply {
            close = prefs.getBoolean("mapClose", false)
            span = prefs.getFloat("mapSpan", 0.4f).toDouble()
            questAreas = prefs.getBoolean("mapQuests", true)
            panned = mapPan?.takeIf { it.first == state?.mapId }?.second?.clone()
            turns = prefs.getBoolean("mapTurns", false)
            highlight = tracked
            onPlaces = ::showPlaces
            // A tap on nothing in particular puts the card away.
            setOnClickListener { hidePlaceCard() }
        }
        val whole = chip("") {}
        fun zoomed() {
            prefs.edit().putBoolean("mapClose", view.close).putFloat("mapSpan", view.span.toFloat()).apply()
            mapPan = view.panned?.let { (state?.mapId ?: -1) to it.clone() }
            // Dragged away from you: back to you first.
            whole.text = when {
                view.panned != null -> "Back to me"
                view.close -> "Whole zone"
                else -> "Around me"
            }
            view.invalidate()
        }
        view.onZoom = ::zoomed
        view.onPan = ::zoomed
        whole.setOnClickListener {
            if (view.panned != null) view.panned = null else view.close = !view.close
            zoomed()
        }
        // Zoom in steps, like the minimap's + and −; a pinch on the map works too.
        fun zoom(by: Double) {
            val now = if (view.close) view.span else 1.0
            val next = (now * by).coerceIn(MapView.MIN_SPAN, 1.0)
            view.close = next < 0.95
            if (view.close) view.span = next
            zoomed()
        }
        // Switches, lit while on.
        fun lit(c: TextView, on: Boolean) {
            c.background = Theme.button(this, primary = on)
            c.setTextColor(if (on) Color.WHITE else TEXT)
        }
        val quests = chip("Quest areas") {}
        lit(quests, view.questAreas)
        quests.setOnClickListener {
            view.questAreas = !view.questAreas
            prefs.edit().putBoolean("mapQuests", view.questAreas).apply()
            lit(quests, view.questAreas)
            view.invalidate()
        }
        // The map turns with you, like the minimap: around you, where you face is up.
        val turning = chip("Turn with me") {}
        lit(turning, view.turns)
        turning.setOnClickListener {
            view.turns = !view.turns
            prefs.edit().putBoolean("mapTurns", view.turns).apply()
            if (view.turns) { view.panned = null; view.close = true }
            lit(turning, view.turns)
            zoomed()
        }
        val take = chip("Get zone picture") {
            // One key: the addon shows the zone's map art for a few seconds and the app takes it.
            pictureNote = "Asked the game for the zone picture…"
            updateMap()
            pressKey(ActionKeys.PICTURE) { err -> pictureNote = "Map picture not taken: $err"; updateMap() }
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(whole, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        controls.addView(quests, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        controls.addView(turning, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(6) })
        controls.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        controls.addView(take)
        content.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2); bottomMargin = dp(6) })
        // The selected quest: how far, and ✕ to clear it. It lies over the top of the map,
        // so following a quest doesn't push the map down off the screen.
        val questRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.panel(context, Theme.withAlpha(SURFACE, 225), Theme.EDGE)
            setPadding(dp(12), dp(4), dp(4), dp(4))
        }
        val questText = line("", TEXT, 14f).apply { setPadding(0, 0, 0, 0) }
        questRow.addView(questText, LinearLayout.LayoutParams(0, -2, 1f))
        questRow.addView(chip("✕") {
            // While the line shows your corpse: only that goes (the quest you follow comes back).
            if (ghost && !corpseHidden) corpseHidden = true
            else {
                tracked = null
                ledTo = null
                getPreferences(MODE_PRIVATE).edit().remove("tracked").apply()
                view.highlight = null
            }
            updateCompass()
        }, LinearLayout.LayoutParams(dp(44), -2))
        questLine = questText
        // What a tap on the map hit, under the line above: what it is, how far, and Lead me there.
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.panel(context, Theme.withAlpha(SURFACE, 235), Theme.EDGE)
            setPadding(dp(12), dp(4), dp(4), dp(8))
            visibility = View.GONE
        }
        placeCard = card
        val topBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        topBox.addView(questRow, LinearLayout.LayoutParams(-1, -2))
        topBox.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        val mapBox = android.widget.FrameLayout(this)
        mapBox.addView(view, android.widget.FrameLayout.LayoutParams(-1, -1))
        mapBox.addView(topBox, android.widget.FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply { setMargins(dp(6), dp(6), dp(6), 0) })
        // Bottom left: what happened to the last picture (only after a try), the zone and where you are.
        val note = line("", TEXT, 12f).apply { setPadding(0, 0, 0, dp(2)) }
        val label = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.panel(context, Theme.withAlpha(SURFACE, 205), Theme.withAlpha(Theme.EDGE, 160))
            setPadding(dp(10), dp(5), dp(10), dp(5))
            addView(note)
            addView(title)
        }
        mapBox.addView(label, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
            setMargins(dp(6), 0, dp(120), dp(6))
        })
        // Bottom right: zoom in steps, like the minimap's + and −; a pinch on the map works too.
        val zoomButtons = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((text, by) in listOf("+" to 1 / 1.4, "−" to 1.4)) {
            zoomButtons.addView(chip(text) { zoom(by) }.apply {
                textSize = 20f
                typeface = Theme.body(context, bold = true)
                background = Theme.button(context, radiusDp = 24f)  // a circle
                setPadding(0, 0, 0, 0)
            }, LinearLayout.LayoutParams(dp(48), dp(48)).apply { topMargin = dp(6) })
        }
        mapBox.addView(zoomButtons, android.widget.FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply {
            setMargins(0, 0, dp(6), dp(6))
        })
        // All the room below the buttons row, down to the bottom of the screen, now and
        // whenever the window changes size (the tab fills the screen, see render()). Its
        // height used to be worked out once when the tab was drawn, from a height that
        // could be 0 or out of date then: the map stopped at 240 dp, the rest empty
        // (Jesse's screenshot, 2026-10-17). On a very small window it stays 240 dp and the tab scrolls.
        mapBox.minimumHeight = dp(240)
        content.addView(mapBox, LinearLayout.LayoutParams(-1, 0, 1f))
        pictureLine = note
        pictureButton = take
        mapView = view
        mapTitle = title
        zoomed()
        updateMap()
        updateCompass()
    }

    private var pictureButton: TextView? = null

    /** The card for what was tapped on the map (null while the Map tab isn't shown). */
    private var placeCard: LinearLayout? = null

    /** A place you asked to be led to (not a quest), and on which map. */
    private var ledTo: Pair<Int, MapPlace>? = null

    private fun hidePlaceCard() {
        placeCard?.let { it.removeAllViews(); it.visibility = View.GONE }
    }

    /** "240 yd north-east", "you're there", or only the way while the zone's size is unknown. */
    private fun wayTo(p: MapPlace): String? {
        val s = state
        val map = zoneMap?.takeIf { it.mapId == s?.mapId } ?: return null
        val x = s?.x ?: return null
        val y = s.y ?: return null
        if (x <= 0) return null
        val h = Compass.toPlace(map, nearPoint(map, p, x, y), x, y)
        val yd = h.yards
        return when {
            yd == null -> Compass.direction(h.bearing)
            yd < 15 -> "you're there"
            else -> "$yd yd ${Compass.direction(h.bearing)}"
        }
    }

    /** The quest in the log a map label (maybe cut short) belongs to, or the label itself. */
    private fun questTitle(label: String) = quests?.quests?.firstOrNull { Compass.matches(label, it.title) }?.title ?: label

    /** The card for a tap on the map: a pin, or the quests whose areas are under the finger. */
    private fun showPlaces(list: List<MapPlace>) {
        val card = placeCard ?: return
        val first = list.firstOrNull() ?: return
        card.removeAllViews()
        card.visibility = View.VISIBLE
        val coords = String.format(Locale.US, "%.1f, %.1f", first.x * 100, first.y * 100)
        val where = listOfNotNull(wayTo(first), coords).joinToString("  ·  ")
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val kind = when {
            first.kind != 'q' -> MapView.kindName(first.kind)
            list.size == 1 -> "Quest area"
            else -> "${list.size} quest areas here"
        }
        head.addView(line("$kind  ·  $where", DIM, 12f).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(chip("✕") { hidePlaceCard() }, LinearLayout.LayoutParams(dp(44), -2))
        card.addView(head)
        fun row(name: String, go: () -> Unit) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            r.addView(line(name, TEXT, 14f, bold = true).apply { setPadding(0, dp(2), dp(6), dp(2)) }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(chip("Lead me there") { go() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
            card.addView(r, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        if (first.kind != 'q') {
            row(first.label.ifEmpty { MapView.kindName(first.kind) }) { leadTo(first) }
        } else {
            // A quest is followed like one tapped in the Quests tab.
            for (title in list.map { questTitle(it.label) }.distinct().take(6)) {
                row(title) {
                    val q = questOf(title)
                    if (q != null) selectQuest(q) else { ledTo = null; tracked = title; getPreferences(MODE_PRIVATE).edit().putString("tracked", title).apply(); render() }
                }
            }
        }
    }

    /** Leads you to a pin on the map: the line on top says how far and which way, until ✕. */
    private fun leadTo(p: MapPlace) {
        val id = state?.mapId ?: return
        ledTo = id to p
        tracked = null
        getPreferences(MODE_PRIVATE).edit().remove("tracked").apply()
        mapView?.highlight = null
        hidePlaceCard()
        updateCompass()
        say("Leading you to ${p.label.ifEmpty { MapView.kindName(p.kind) }}")
    }

    private fun updateMap() {
        val view = mapView ?: return
        val s = state
        val map = zoneMap?.takeIf { it.mapId == s?.mapId }
        view.zone = map
        view.glideTo(s?.x?.takeIf { it > 0 }, s?.y?.takeIf { it > 0 }, s?.facing)
        view.trail = s?.mapId?.let { trail.paths[it] }.orEmpty()
        view.picture = s?.mapId?.let { picture(it) }
        view.areas = s?.mapId?.let { areasOf(it) } ?: emptyMap()
        val where = if (s?.x != null && s.y != null && s.x!! > 0) String.format(Locale.US, "   %.1f, %.1f", s.x!! * 100, s.y!! * 100) else ""
        mapTitle?.text = (map?.zone?.ifEmpty { null } ?: "Map ${s?.mapId ?: "unknown"}") + where
        // Without a picture yet, the button that takes one is the thing to tap.
        pictureButton?.let { b ->
            val none = s?.mapId != null && view.picture == null
            b.text = if (none) "Get zone picture" else "New picture"
            b.background = Theme.button(this, primary = none)
            b.setTextColor(if (none) Color.WHITE else TEXT)
        }
        pictureLine?.text = pictureNote
        pictureLine?.visibility = if (pictureNote.isEmpty()) View.GONE else View.VISIBLE
        view.invalidate()
    }

    /**
     * The chat, one kind at a time if you like: its own tabs on top (with how many new
     * lines each has), the lines newest at the bottom, coloured like WoW's chat frame,
     * and below the key that opens the game's chat box to answer there.
     */
    private fun renderChat() {
        val tabsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, 0, 0, dp(6)) }
        for (t in ChatTab.entries) {
            val unread = if (t == chatTab) 0 else unread(t)
            val label = t.title + if (unread > 0) "  $unread" else ""
            val chip = chip(label) { chatTab = t; render() }
            if (t == chatTab) chip.primary()
            tabsRow.addView(chip, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        }
        toolbar.addView(tabsRow)
        markSeen(chatTab)
        updateBadges()

        val shown = chat.lines.filter { chatTab.kinds == null || it.kind in chatTab.kinds!! }
        if (shown.isEmpty()) {
            content.addView(empty(if (chat.lines.isEmpty()) "No chat yet" else "Nothing here yet",
                "New lines show up here. Tap a line to whisper its sender; the buttons below open the game's chat box to answer."))
        } else {
            val box = card().apply { setPadding(dp(10), dp(6), dp(10), dp(6)) }
            for (l in shown.takeLast(100)) box.addView(chatLine(l))
            content.addView(box, cardParams())
            scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        }

        if (composing != null) return
        // Answer: type here, with the keyboard on this screen; Send opens the game's chat box and puts it in.
        val answers = when (chatTab) {
            ChatTab.ALL -> listOf("Group" to GROUP, "Guild" to "/g ", "Say" to "/s ", "Reply" to "/r ")
            ChatTab.GROUP -> listOf("Write to your group" to GROUP)
            ChatTab.WHISPERS -> listOf("Reply to the last whisper" to "/r ")
            ChatTab.GUILD -> listOf("Write to your guild" to "/g ")
            ChatTab.GENERAL -> listOf("Say" to "/s ") + chat.channels.map { (id, name) -> name to "/$id " }
        }
        // Four buttons per row; more channels get more rows.
        for (group in answers.chunked(4)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            for ((label, command) in group) {
                row.addView(chip(label) { compose(label, command) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            }
            repeat(if (answers.size > 4) 4 - group.size else 0) { row.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f)) }
            footer.addView(row)
        }
    }

    private fun chatSeq(i: Int) = chat.total - chat.lines.size + i + 1

    /** New lines in [t] since it was last open (or All was). */
    private fun unread(t: ChatTab): Int {
        val seen = maxOf(chatSeen[t] ?: 0, chatSeen[ChatTab.ALL] ?: 0)
        var n = 0
        chat.lines.forEachIndexed { i, l -> if (chatSeq(i) > seen && (t.kinds == null || l.kind in t.kinds)) n++ }
        return n
    }

    private fun markSeen(t: ChatTab) {
        chatSeen[t] = chat.total
    }

    /**
     * Little marks on the tabs for what needs a look: new whispers and group, guild
     * lines in Chat, quests ready to turn in, bags almost full, gear almost broken.
     */
    private fun updateBadges() {
        if (tabs.isEmpty()) return
        val s = state
        val chatNew = if (panel == Panel.CHAT) 0 else unread(ChatTab.WHISPERS) + unread(ChatTab.GROUP) + unread(ChatTab.GUILD)
        val ready = quests?.readyCount ?: 0
        val full = s?.freeSlots?.let { it <= 2 } == true
        val broken = gear.any { (it.durability ?: 100) <= 20 }
        for ((p, b) in tabs) {
            val text = android.text.SpannableStringBuilder(p.title)
            fun badge(mark: String, colour: Int) {
                val start = text.length
                text.append("  ").append(mark)
                text.setSpan(android.text.style.ForegroundColorSpan(if (p == panel) Color.WHITE else colour), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            when (p) {
                Panel.CHAT -> if (chatNew > 0) badge(chatNew.toString(), Color.rgb(255, 128, 255))
                Panel.QUESTS -> if (ready > 0) badge(ready.toString(), ACCENT)
                Panel.BAGS -> if (full) badge("●", Theme.BAD)
                Panel.CHARACTER -> if (broken) badge("●", Theme.BAD)
                Panel.MAP, Panel.CRAFT -> {}
            }
            b.text = text
        }
    }

    /** The game's colours for how sure a recipe's skill point is. */
    private fun recipeColour(c: Char): Int = when (c) {
        'o' -> Color.rgb(255, 128, 64)
        'm' -> Color.rgb(255, 255, 0)
        'e' -> Color.rgb(64, 191, 64)
        else -> Color.rgb(128, 128, 128)
    }

    /** "3 h 20 min", "12 min", "1 d 4 h". */
    private fun duration(seconds: Long): String {
        val m = (seconds + 59) / 60
        return when {
            m >= 24 * 60 -> "${m / (24 * 60)} d ${m / 60 % 24} h"
            m >= 60 -> "${m / 60} h ${m % 60} min"
            else -> "$m min"
        }
    }

    /**
     * The Craft tab: your professions with their levels, the open one's recipes (what you
     * can make from your bags, grey when nothing), a recipe's materials and Make buttons,
     * and the crafting list. The recipes are the ones the game showed the last time
     * the profession window was open.
     */
    private fun renderCraft(s: GameState) {
        settleListEnchant()  // an enchantment from the list the game has finished counts on its entry
        if (craftSheet == "list") { renderCraftList(); return }
        val p = professions
        // Recipes kept under a name the skill list doesn't use (the window's own) count as a profession too.
        val list = p?.list.orEmpty().filter { it.name.isNotBlank() }.toMutableList()
        for (page in recipePages.values) {
            if (page.profession.isNotBlank() && list.none { it.name == page.profession }) list += thor.companion.strip.Profession(page.profession, page.level, page.max)
        }
        if (list.isEmpty()) {
            content.addView(empty(if (p == null) "Professions are coming in…" else "No professions yet",
                if (p == null) "They come from the game in a moment (the addon 0.26 or newer). If nothing comes, tap Load from the game."
                else "This character hasn't learned a profession yet."))
            return
        }
        // The main professions first; then the secondary ones, small, Cooking and First Aid also when not learned.
        val (minor, major) = list.partition { it.name in SECONDARY_PROFESSIONS }
        val selected = list.firstOrNull { it.name == craftProfession } ?: (major + minor).first()
        craftProfession = selected.name
        val missing = listOf("First Aid", "Cooking").filter { name -> minor.none { it.name == name } }

        // Fixed above the recipes: the professions, then one row for the list.
        fun profBox(prof: thor.companion.strip.Profession?, name: String, compact: Boolean, tight: Boolean = false): View {
            val on = prof != null && prof.name == selected.name
            val box = LinearLayout(this).apply {
                orientation = if (compact) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                background = Theme.panel(context, if (on) RAISED else SURFACE, gold = on)
                setPadding(dp(10), dp(if (compact) 5 else 6), dp(10), dp(if (compact) 5 else 6))
                setOnClickListener {
                    if (prof == null) say("$name isn't learned yet: a $name trainer in any city teaches it")
                    else { craftProfession = prof.name; craftRecipe = null; craftTarget = null; scroll.scrollTo(0, 0); render() }
                }
            }
            val level = when {
                tight -> prof?.level?.toString() ?: ""  // (four to a row: the name needs the room)
                prof == null -> "not learned"
                prof.max > 0 -> "${prof.level}/${prof.max}"
                else -> "${prof.level}"
            }
            box.addView(line(name, if (on) Theme.GOLD_TEXT else if (prof == null) DIM else TEXT, if (compact) 13f else 14f).apply {
                typeface = Theme.heading(context)
                setPadding(0, 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, if (compact) LinearLayout.LayoutParams(0, -2, 1f) else LinearLayout.LayoutParams(-2, -2))
            box.addView(line(level, DIM, 12f).apply { setPadding(if (compact) dp(6) else 0, 0, 0, if (compact) 0 else dp(2)) })
            if (!compact && prof != null && prof.max > 0) box.addView(Bar(this).apply { value = prof.level.toFloat() / prof.max; colour = ACCENT }, LinearLayout.LayoutParams(-1, dp(4)))
            return box
        }
        fun rowOf(views: List<View>, wide: Int = maxOf(2, major.size), weights: List<Float>? = null) {
            val bar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
            for ((i, v) in views.withIndex()) bar.addView(v, LinearLayout.LayoutParams(0, -2, weights?.getOrNull(i) ?: 1f).apply { setMargins(dp(2), 0, dp(2), 0) })
            // An odd one keeps its half: a row is two wide (three when there are three main ones).
            repeat((wide - views.size % wide) % wide) { bar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { setMargins(dp(2), 0, dp(2), 0) }) }
            toolbar.addView(bar)
        }
        if (craftRecipe != null) {
            // A card is open: all professions small, four to a row, so the card has the room;
            // each as wide as its words ("Enchanting 109" needs more than "Cooking").
            val profs = (major + minor).map { it to it.name } + missing.map { null to it }
            val wide = minOf(4, maxOf(2, profs.size))
            profs.chunked(wide).forEach { part ->
                rowOf(part.map { (prof, name) -> profBox(prof, name, true, tight = true) }, wide,
                    if (part.size == wide) part.map { (prof, name) -> name.length + (prof?.level?.toString()?.length ?: 0) + 4f } else null)
            }
        } else {
            if (major.isNotEmpty()) rowOf(major.map { profBox(it, it.name, false) })
            val minorViews = minor.map { profBox(it, it.name, true) } + missing.map { profBox(null, it, true) }
            if (minorViews.isNotEmpty()) minorViews.chunked(maxOf(2, major.size)).forEach { rowOf(it) }
        }

        // The list row: Make next (or Make again), and the list on a tap.
        val queue = craftList()
        val again = lastJob()
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(2)) }
        val run = craftRunning?.takeIf { !it.finished() }
        craftShown = craftRunning?.let { "${it.made()}|${it.finished()}" } ?: ""
        content.removeCallbacks(craftTick)
        if (run != null) content.postDelayed(craftTick, 1000)
        val main = mainAction(queue, run, again)
        if (main != null) {
            actions.addView(chip(main) { makeNextFromList() }.apply {
                if (run != null) { background = Theme.goldButton(context); setTextColor(Theme.GOLD_TEXT) }
                else primary()
                maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        } else {
            actions.addView(line("Tap a recipe to make it or put it on the list", DIM, 12f).apply { setPadding(dp(4), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        actions.addView(chip(if (queue.isEmpty()) "List" else "List (${queue.size})") { openCraftList() }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(4) })
        toolbar.addView(actions)
        enchantStatus()?.let { toolbar.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }) }

        // What you have: the bags as they are now, the bank as it was when last open.
        val inBags = HashMap<Int, Int>()
        for (i in s.items) inBags[i.itemId] = (inBags[i.itemId] ?: 0) + i.count
        val inBank = bank?.counts.orEmpty()
        val bags: (Int) -> Int = { inBags[it] ?: 0 }
        val both: (Int) -> Int = { (inBags[it] ?: 0) + (inBank[it] ?: 0) }

        val pages = recipePages.values.filter { it.profession == selected.name }
        val newest = pages.maxOfOrNull { it.readAt }
        val shown = pages.filter { it.readAt == newest }.sortedBy { it.page }
        val recipes = recipesOf(selected.name)

        // Below the recipes, like a small pop-up: the recipe tapped or the list.
        val open = craftRecipe
        when {
            open != null && recipes.any { it == open } -> renderRecipe(open, recipes.indexOf(open) + 1, selected.name, bags, inBank)
        }
        if (open != null && recipes.none { it == open }) craftRecipe = null

        if (recipes.isEmpty()) {
            content.addView(empty("Open ${selected.name} in the game once",
                "The game shows the recipes only while the profession window is open (for Mining that is Smelting). Open it once and they stay here, also after a restart."))
            return
        }
        // Enchanting goes both ways, as in the game: an enchantment and then the item, or an item and then what fits it.
        val enchanting = recipes.any { it.enchantment }
        if (enchanting) {
            val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
            modes.addView(chip("By enchantment") { craftByGear = false; craftTarget = null; render() }.apply { if (!craftByGear) primary() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            modes.addView(chip("By gear piece") { craftByGear = true; craftRecipe = null; render() }.apply { if (craftByGear) primary() },
                LinearLayout.LayoutParams(0, -2, 1f))
            content.addView(modes)
            if (craftByGear) { renderEnchantGear(recipes, bags, both); return }
        }
        val first = shown.firstOrNull() ?: RecipePage(selected.name, 0, 0, 1, 1, 0, emptyList())
        val age = newest?.let { System.currentTimeMillis() / 1000 - it }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), 0, dp(4)) }
        head.addView(line("Recipes" +
            (age?.takeIf { it > 3600 }?.let { "  ·  read ${duration(it)} ago" } ?: "") +
            (if (shown.size < first.pages) "  ·  updating (${shown.size} of ${first.pages})" else ""), DIM, 13f).apply { setPadding(0, 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(chip(if (craftOnlyMakeable) "✓ Can make now" else "Can make now") { craftOnlyMakeable = !craftOnlyMakeable; render() }.apply {
            if (craftOnlyMakeable) primary()
            setPadding(dp(10), dp(5), dp(10), dp(5))
        })
        content.addView(head)
        var heading: String? = null
        var any = false
        for (r in recipes) {
            if (r.heading) { heading = r.name; continue }
            if (craftOnlyMakeable && (r.canMake(bags) ?: 0) <= 0) continue
            heading?.let { content.addView(section(it)); heading = null }
            any = true
            content.addView(recipeRow(r, bags, both), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        }
        if (!any) content.addView(empty("Nothing to make right now", "Your bags don't have the materials for any of these recipes."))
    }

    /** A line of the recipe list: its skill-point colour, what it makes, and how many the bags allow. Tap for its card. */
    private fun recipeRow(r: Recipe, bags: (Int) -> Int, both: (Int) -> Int): View {
        val now = System.currentTimeMillis() / 1000
        val n = r.canMake(bags)
        val withBank = r.canMake(both)
        val known = names[r.itemId]
        val greyed = n == 0
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Theme.panel(context, if (r == craftRecipe) RAISED else SURFACE, gold = r == craftRecipe)
            setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { craftRecipe = if (craftRecipe == r) null else r; craftSheet = null; render() }
            setOnLongClickListener { if (r.itemId > 0) showDetails(r.itemId); true }
        }
        row.addView(View(this).apply { background = Theme.box(context, recipeColour(r.colour), 5) }, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
        row.addView(recipeIcon(r, known?.quality, 28).apply { if (greyed) alpha = 0.45f },
            LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) })
        val nameColour = if (greyed) DIM else known?.let { qualityColour(it.quality) } ?: TEXT
        row.addView(line(r.name, nameColour, 14f).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        val wait = r.readyAt - now
        val right = when {
            wait > 0 -> "ready in ${duration(wait)}"
            n == null -> ""
            n > 0 -> "can make $n"
            (withBank ?: 0) > 0 -> "$withBank with the bank"
            else -> "missing"
        }
        row.addView(line(right, if (wait > 0) Theme.WARN else if ((n ?: 0) > 0) Theme.GOOD else DIM, 13f).apply { setPadding(dp(8), 0, 0, 0) })
        return row
    }

    /**
     * The items an enchantment goes on, as the game listed them when the window was last
     * open, without those that have gone since: a worn item that isn't worn any more, a bag
     * item that isn't in the bags. [stale] is told whether any went.
     */
    private fun currentFits(r: Recipe, stale: (() -> Unit)? = null): List<EnchantTarget> {
        val worn = gear.associate { it.slot to it.itemId }
        val carried = state?.items.orEmpty().map { it.itemId }.toSet()
        return r.fits.orEmpty().filter { t ->
            val there = if (t.worn) gear.isEmpty() || worn[t.place] == t.itemId else state == null || t.itemId in carried
            if (!there) stale?.invoke()
            there
        }
    }

    /** An enchantable item's name, and where it is ("Wrist", "in your bags"). */
    private fun targetName(t: EnchantTarget) = names[t.itemId]?.name ?: "Item #${t.itemId}"
    private fun targetPlace(t: EnchantTarget) = if (t.worn) Gear.slotName(t.place) else "in your bags"

    /**
     * One enchantable item: icon, name in its quality's colour, where it is, and a note on the
     * right. [compact]: on one line, where it is beside the name (the enchantment's card, kept short).
     */
    private fun targetRow(t: EnchantTarget, note: String, picked: Boolean, compact: Boolean = false, onTap: () -> Unit): View {
        val known = names[t.itemId]
        val size = if (compact) 26 else 32
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Theme.panel(context, if (picked) RAISED else SURFACE, gold = picked)
            if (compact) setPadding(dp(8), dp(4), dp(10), dp(4)) else setPadding(dp(10), dp(6), dp(10), dp(6))
            setOnClickListener { onTap() }
            setOnLongClickListener { showDetails(t.itemId); true }
        }
        row.addView(icon(t.itemId, known?.let { qualityColour(it.quality) }, size), LinearLayout.LayoutParams(dp(size), dp(size)).apply { marginEnd = dp(10) })
        val name = line(targetName(t), known?.let { qualityColour(it.quality) } ?: TEXT, 14f).apply { setPadding(0, 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        if (compact) {
            row.addView(name, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(line(targetPlace(t), DIM, 12f).apply { setPadding(dp(8), 0, 0, 0) })
        } else {
            val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            words.addView(name)
            words.addView(line(targetPlace(t), DIM, 11f).apply { setPadding(0, 0, 0, 0) })
            row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        }
        if (note.isNotEmpty()) row.addView(line(note, if (picked) Theme.GOLD_TEXT else DIM, 13f).apply { setPadding(dp(8), 0, 0, 0); minWidth = dp(18) })
        return row
    }

    /**
     * Enchanting by gear piece: your items that an enchantment you know goes on (worn ones in
     * the paper doll's order, then the bags'), and for the one tapped, the enchantments that fit it.
     */
    private fun renderEnchantGear(recipes: List<Recipe>, bags: (Int) -> Int, both: (Int) -> Int) {
        var stale = false
        val fitting = LinkedHashMap<Int, MutableList<Recipe>>()
        val targets = HashMap<Int, EnchantTarget>()
        for (r in recipes) {
            if (!r.enchantment) continue
            for (t in currentFits(r) { stale = true }) {
                targets.putIfAbsent(t.place, t)
                fitting.getOrPut(t.place) { mutableListOf() } += r
            }
        }
        val picked = craftTarget?.let { targets[it] }
        if (picked != null) {
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(2)) }
            head.addView(targetRow(picked, "", true) {}, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
            head.addView(chip("✕") { craftTarget = null; craftRecipe = null; render() }.apply { setPadding(dp(14), dp(9), dp(14), dp(9)) })
            content.addView(head)
            content.addView(section("Enchantments for it"))
            for (r in fitting[picked.place].orEmpty()) content.addView(recipeRow(r, bags, both), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            return
        }
        if (craftTarget != null) craftTarget = null
        if (targets.isEmpty()) {
            content.addView(empty("No gear to enchant", if (stale) "Your gear changed since Enchanting was last open. Open it in the game once, and the list catches up."
                else "None of your items takes an enchantment you know. Open Enchanting in the game once after you change gear."))
            return
        }
        val worn = targets.values.filter { it.worn }.sortedBy { GEAR_SLOTS.indexOf(it.place).let { i -> if (i < 0) 99 else i } }
        val carried = targets.values.filter { !it.worn }.sortedBy { it.place }
        for ((title, list) in listOf("What you wear" to worn, "In your bags" to carried)) {
            if (list.isEmpty()) continue
            content.addView(section(title))
            for (t in list) {
                val n = fitting[t.place].orEmpty().size
                content.addView(targetRow(t, if (n == 1) "1 enchantment" else "$n enchantments", false) { craftTarget = t.place; craftRecipe = null; render() },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
            }
        }
        content.addView(line(if (stale) "Your gear changed since Enchanting was last open: open it in the game once, and the list catches up."
            else "Only gear that an enchantment you know goes on is listed, as the game's enchanting window says.", DIM, 12f).apply { setPadding(dp(4), dp(8), dp(4), 0) })
    }

    /** While an enchantment is on: the game's question with Yes and No; once it ended, how (tap to put it away). */
    private fun enchantStatus(): View? {
        val r = craftRunning ?: return null
        val on = r.on ?: return null
        val g = r.game()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Theme.panel(context, RAISED, gold = true)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        when {
            g?.asking == true -> {
                box.addView(line(g.reason.ifEmpty { "The game asks something before it enchants $on." }, Theme.GOLD_TEXT, 14f).apply { setPadding(0, 0, 0, dp(6)) })
                val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                buttons.addView(chip("Yes") { answerEnchant(true) }.apply { primary() }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
                buttons.addView(chip("No") { answerEnchant(false) }, LinearLayout.LayoutParams(0, -2, 1f))
                box.addView(buttons)
                box.addView(line("The game shows the same question on its screen.", DIM, 12f).apply { setPadding(0, dp(4), 0, 0) })
            }
            !r.finished() -> return null  // the button above says it is on
            r.stopped() != null -> box.addView(line("Not enchanted: ${r.stopped()}", Theme.WARN, 13f).apply { setPadding(0, 0, 0, 0) })
            else -> return null  // done: the list's count (green) and the bags show it (Jesse, 2026-10-20)
        }
        if (g?.asking != true) box.setOnClickListener { craftRunning = null; render() }
        return box
    }

    /** The app's Yes or No to the game's question (one key press, as a click on the game's own button). */
    private fun answerEnchant(yes: Boolean) {
        say(if (yes) "Yes: enchanting" else "No: nothing enchanted")
        pressKey(if (yes) Professions.YES else Professions.NO) { err -> say("Couldn't answer: $err") }
    }

    /**
     * A small pop-up at the bottom (the footer): a title with a close button over [body], kept
     * short with its own scrolling, at most [share] of the screen's height.
     */
    private fun sheet(title: String, onClose: () -> Unit, share: Float = 0.42f, key: String = title, body: LinearLayout.() -> Unit) {
        val box = card().apply { background = Theme.panel(context, gold = true); setPadding(dp(12), dp(8), dp(12), dp(10)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(line(title, TEXT, 14f, bold = true).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(chip("✕") { onClose() }.apply { setPadding(dp(12), dp(4), dp(12), dp(4)) })
        box.addView(top)
        val inner = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; body() }
        val cap = (resources.displayMetrics.heightPixels * share).toInt()
        // Drawn again (the bags changed, a craft went on), the same pop-up stays where it was
        // scrolled to: it used to jump back to the top while you scrolled (Jesse, 2026-10-20).
        if (key != sheetKey) { sheetKey = key; sheetY = 0 }
        var restore = sheetY
        val scroller = object : ScrollView(this) {
            // At most [share] of the screen, and never more than the space left under the tabs
            // and the tab's top part: the footer may ask for the whole height (the list above
            // takes what is left), so a tall card reached past the screen's bottom edge and its
            // last line, the replace switch, was cut off (Jesse, 2026-10-20).
            override fun onMeasure(w: Int, h: Int) {
                var above = 0
                for (i in 0 until pageRoot.childCount) pageRoot.getChildAt(i).let { if (it !== footer && it !== scroll) above += it.measuredHeight }
                val room = pageHeight - pageRoot.paddingTop - pageRoot.paddingBottom - above -
                    top.measuredHeight - box.paddingTop - box.paddingBottom - dp(4)
                val max = if (pageHeight > 0) minOf(cap, maxOf(dp(80), room)) else cap
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST))
            }
            override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
                super.onLayout(changed, l, t, r, b)
                if (restore > 0) { scrollTo(0, restore); restore = 0 }
            }
        }.apply {
            isVerticalScrollBarEnabled = false; addView(inner)
            setOnScrollChangeListener { _, _, y, _, _ -> if (sheetKey == key) sheetY = y }
        }
        box.addView(scroller)
        footer.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    /** The main button's words: what is being made, the list's next one, or Make (Enchant) again. */
    private fun mainAction(queue: List<CraftJob>, run: CraftRun?, again: CraftJob? = lastJob()): String? = when {
        run != null && run.on != null -> queue.firstOrNull()?.takeIf { run.listed == Listed.NEXT && it.on != null }
            ?.let { j -> enchantStep(j).let { (n, of) -> "⏳  Enchanting $n of $of: ${run.on}" } } ?: "⏳  Enchanting ${run.on}"
        run != null -> "⏳  Making ${run.job.name}: ${run.made()} of ${run.total()}"
        queue.firstOrNull()?.on != null -> enchantStep(queue.first()).let { (n, of) -> "▶  Enchant $n of $of: ${enchantWhat(queue.first(), short = true)}" }
        queue.isNotEmpty() && craftDone.isEmpty() -> "▶  Start the list (${queue.size})"
        queue.isNotEmpty() -> "▶  Next (${craftDone.size + 1} of ${craftDone.size + queue.size}): ${jobLabel(queue.first(), short = true)}"
        lastWasEnchant() -> lastEnchant()?.let { "↻  Enchant again: ${it.name.substringAfter(" - ", it.name)} on ${targetName(it.on)}" }
        else -> again?.let { "↻  Make again: ${it.label()}" }
    }

    /** Make next: the list's first entry (moved to the made ones), or the last thing made again when the list is empty. */
    private fun makeNextFromList() {
        settleListEnchant()
        val now = craftList()
        val job = now.firstOrNull()
        if (job == null) {
            if (lastWasEnchant()) enchantAgain() else lastJob()?.let { runJob(it) }
            return
        }
        craftRunning?.let { if (!it.finished()) {
            say(if (it.on != null) "Still enchanting ${it.on}: Next works once it is done" else "Still making ${it.job.name} (${it.made()} of ${it.total()}): Next works once it is done")
            return
        } }
        if (job.on != null) { enchantFromList(now, job); return }
        val seqBefore = professions?.progress?.seq ?: 0
        val count = runJob(job)
        if (count > 0) {
            now.removeAt(0); saveCraftList(now)
            craftDone += job
            val r = recipeOf(job)
            craftRunning = if (job.itemId > 0 || professions?.makeKeys == true)
                CraftRun(job, count, maxOf(1, r?.made ?: 1), haveOf(job.itemId), seqBefore).also { it.listed = Listed.DONE } else null
            render()
        }
    }

    private fun haveOf(itemId: Int) = state?.items.orEmpty().filter { it.itemId == itemId }.sumOf { it.count }

    /**
     * Make next on an enchantment entry: one enchantment on its item (found again when it
     * moved in the bags), as one tap of the game's Enchant button. The entry stays where it
     * is, counting "3 of 5", until they are all done (see settleListEnchant).
     */
    private fun enchantFromList(now: MutableList<CraftJob>, job: CraftJob) {
        val target = job.on ?: return
        val (row, r) = findJob(job) ?: run { say("${job.name} isn't in the ${job.profession} recipes any more: open that window in the game once"); return }
        val fits = currentFits(r)
        val on = fits.firstOrNull { it.place == target.place && it.itemId == target.itemId } ?: fits.firstOrNull { it.itemId == target.itemId }
            ?: run { say("${targetName(target)} doesn't take ${job.name} now: take it off the list (✕)"); return }
        val have = state?.items.orEmpty().groupBy { it.itemId }.mapValues { g -> g.value.sumOf { it.count } }
        val can = r.canMake { have[it] ?: 0 } ?: 0
        if (can <= 0) {
            // + all with nothing left for another one: that entry is done.
            if (job.count == ALL && job.made > 0) { now.removeAt(0); saveCraftList(now); craftDone += job.copy(count = job.made); render(); return }
            say("Not enough materials for ${job.name} in your bags"); return
        }
        if (!runEnchant(job.profession, row, r, on)) return
        if (job.row != row || job.on != on) { now[0] = job.copy(row = row, on = on); saveCraftList(now) }
        craftRunning?.listed = Listed.NEXT
        render()
    }

    /**
     * An enchantment from the list that the game finished: counted on its entry when it
     * went on (not when the game said no or stopped it), and the entry moves to the made
     * ones (✓) once they are all done, or with + all once the materials for another are gone.
     */
    private fun settleListEnchant() {
        val run = craftRunning ?: return
        if (run.listed != Listed.NEXT || run.settled || !run.finished()) return
        run.settled = true
        if ((run.game()?.made ?: 0) < 1) return
        val now = craftList()
        val head = now.firstOrNull() ?: return
        if (head.on == null || head.name != run.job.name || head.profession != run.job.profession) return
        val made = head.made + 1
        val have = state?.items.orEmpty().groupBy { it.itemId }.mapValues { g -> g.value.sumOf { it.count } }
        val gone = head.count == ALL && (recipeOf(head)?.canMake { have[it] ?: 0 } ?: 0) <= 0
        if ((head.count != ALL && made >= head.count) || gone) {
            now.removeAt(0)
            craftDone += head.copy(count = made, made = made)
        } else now[0] = head.copy(made = made)
        saveCraftList(now)
    }

    /**
     * An entry of the list the game is making now. The addon (0.29) counts the crafts and
     * says when the game stopped ([CraftProgress], newer than [seqBefore]); with an older
     * one the bags tell: done when they hold [count] crafts of [each], stopped when nothing
     * new came for a while.
     */
    private inner class CraftRun(val job: CraftJob, val count: Int, val each: Int, val before: Int, val seqBefore: Int, val on: String? = null) {
        val started = SystemClock.uptimeMillis()
        /** Which list entry it makes: the last made one (a craft) or the next one (an enchantment, counted when done). */
        var listed: Listed? = null
        var settled = false
        var last = before
        var lastMade = -1
        var lastStatus = ""
        var lastAt = started
        fun game(): CraftProgress? = professions?.progress?.takeIf { it.seq > seqBefore }
        fun total(): Int = game()?.wanted?.takeIf { it > 0 } ?: count
        /** The bags' count: what they got since the start (often the first to know, but wrong once they are full). */
        fun inBags(): Int {
            if (job.itemId <= 0) return 0
            val now = haveOf(job.itemId)
            if (now != last) { last = now; lastAt = SystemClock.uptimeMillis() }
            return ((now - before) / each).coerceIn(0, count)
        }
        fun made(): Int {
            val bags = inBags()
            val g = game()
            if (g != null) {
                if (g.made != lastMade || g.status != lastStatus) { lastMade = g.made; lastStatus = g.status; lastAt = SystemClock.uptimeMillis() }
                // Whichever message came first: the game's count or the bags.
                return maxOf(g.made, if (g.running) bags else 0).coerceIn(0, total())
            }
            return bags
        }
        fun finished(): Boolean {
            val m = made()
            val g = game()
            // (While the game asks before an enchantment, it waits for an answer as long as it takes.)
            if (g != null) return !g.running || (!g.asking && SystemClock.uptimeMillis() - lastAt > 60_000)
            return m >= count || SystemClock.uptimeMillis() - lastAt > STALL_MS
        }
        /** What the game said when it stopped early (as "Inventory is full."), or null. */
        fun stopped(): String? {
            val g = game()
            if (g != null) return if (g.status == "stopped" || (finished() && g.made < g.wanted)) g.reason.ifEmpty { "stopped" } else null
            return if (finished() && made() < count) "stopped" else null
        }
    }

    private enum class Listed { DONE, NEXT }

    /** Redraws the Craft tab while an entry of the list is being made, for its progress. */
    private var craftShown = ""
    private val craftTick: Runnable = Runnable {
        val r = craftRunning ?: return@Runnable
        // Drawn again only when something changed: a redraw every second would make the list jump.
        val now = "${r.made()}|${r.finished()}"
        if (panel == Panel.CRAFT && now != craftShown) render()
        else if (panel == Panel.CRAFT && !r.finished()) content.postDelayed(craftTick, 1000)
    }

    /**
     * What the bags allow for each entry still on the list, in order: the materials an
     * earlier entry uses are gone for the later ones. Null where it can be made in full,
     * else a short note ("only 1 of 2", "no Copper Bar left").
     */
    private fun listShortfalls(queue: List<CraftJob>): List<String?> {
        val left = HashMap<Int, Int>()
        for (i in state?.items.orEmpty()) left[i.itemId] = (left[i.itemId] ?: 0) + i.count
        return queue.map { job ->
            val r = recipeOf(job) ?: return@map "not in the recipes now"
            val n = r.canMake { left[it] ?: 0 } ?: return@map null
            val want = if (job.count == ALL) n else job.count - job.made
            val made = minOf(n, want)
            for (m in r.materials) left[m.itemId] = (left[m.itemId] ?: 0) - made * m.count
            when {
                n <= 0 -> {
                    val short = r.materials.firstOrNull { (left[it.itemId] ?: 0) < it.count }
                    val what = short?.let { names[it.itemId]?.name ?: it.name.ifEmpty { "materials" } } ?: "materials"
                    "no $what left"
                }
                job.count != ALL && n < want -> "only $n of $want"
                else -> null
            }
        }
    }

    /** Where the recipes were scrolled to when the list page opened (back there on Close). */
    private var listReturnY = 0
    private var clearAskedAt = -10_000L

    private fun openCraftList() {
        listReturnY = scroll.scrollY
        craftSheet = "list"; craftRecipe = null
        render()
        scroll.post { scroll.scrollTo(0, 0) }
    }

    private fun closeCraftList() {
        craftSheet = null
        render()
        scroll.post { scroll.scrollTo(0, listReturnY) }
    }

    /**
     * The crafting list as its own page, in the whole space under the tabs (Jesse,
     * 2026-10-20): Close at the top, the entries in order (made ones ✓, the next one
     * marked, each with its count on the right, green once some are done), and at the
     * bottom the button that makes the next one, with a small Clear beside it.
     */
    private fun renderCraftList() {
        val queue = craftList()
        val run = craftRunning
        val busy = run != null && !run.finished()
        craftShown = run?.let { "${it.made()}|${it.finished()}" } ?: ""
        content.removeCallbacks(craftTick)
        if (busy) content.postDelayed(craftTick, 1000)

        val total = craftDone.size + queue.size
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(6), 0, dp(4)) }
        top.addView(line("Crafting list", Theme.GOLD_TEXT, 16f).apply { typeface = Theme.heading(context); setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (total > 0 && queue.isEmpty()) top.addView(line("all made ✓", Theme.GOOD, 13f).apply { setPadding(0, 0, dp(12), 0) })
        top.addView(chip("Close") { closeCraftList() }.apply { setPadding(dp(18), dp(9), dp(18), dp(9)) })
        toolbar.addView(top)

        if (total == 0) {
            content.addView(empty("The list is empty", "Tap a recipe, then +1, +5 or + all under \"To the list\". The button at the bottom of this page then makes them one by one: one tap each."))
            return
        }
        var n = 0
        for ((i, job) in craftDone.withIndex()) {
            n++
            val mine = run != null && run.listed == Listed.DONE && i == craftDone.size - 1
            val count = when {
                mine && busy -> "⏳ ${run!!.made()} of ${run.total()}" to ACCENT
                job.on != null -> "${job.made} of ${job.made} done" to Theme.GOOD
                else -> "made" to Theme.GOOD
            }
            val why = if (mine && !busy) run!!.stopped()?.let { "Stopped at ${run.made()} of ${run.total()}: $it" } else null
            content.addView(listEntry(n, job, done = !(mine && busy), next = false, count = count, why = why, onRemove = null), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        val short = listShortfalls(queue)
        for ((i, job) in queue.withIndex()) {
            n++
            val next = i == 0
            val making = next && busy && run!!.listed == Listed.NEXT
            val count = short.getOrNull(i)?.let { it to Theme.BAD } ?: job.on?.let {
                val (step, of) = enchantStep(job)
                when {
                    making -> "⏳ $step of $of" to ACCENT
                    job.made > 0 -> "${job.made} of $of done" to Theme.GOOD
                    else -> "0 of $of done" to DIM
                }
            } ?: ((if (job.count == ALL) "all you can" else "${job.count} to make") to DIM)
            // The last enchantment of this entry didn't go on (a no, or the game stopped it): not counted.
            val why = if (next && run != null && run.listed == Listed.NEXT && !busy) run.stopped()?.let { "The last one wasn't done ($it), so it isn't counted." } else null
            content.addView(listEntry(n, job, done = false, next = next, count = count, why = why) {
                saveCraftList(craftList().also { if (i < it.size) it.removeAt(i) }); render()
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
        if (short.any { it != null }) content.addView(line("Red: not enough materials left once the entries before it are made.", Theme.BAD, 12f).apply { setPadding(dp(4), dp(6), 0, 0) })
        content.addView(line(if (queue.any { it.on != null }) "An enchantment is one tap each, as in the game's window." else "One tap makes one entry.", DIM, 12f).apply { setPadding(dp(4), dp(4), 0, dp(4)) })

        // At the bottom: the game's question (Yes / No) when it asks, then the button and Clear
        // (why the last one wasn't done is on its entry above).
        if (run?.game()?.asking == true) enchantStatus()?.let { footer.addView(it, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }) }
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(2)) }
        val label = if (queue.isEmpty()) "✓  All made" else mainAction(queue, run?.takeIf { busy }) ?: "▶  Next"
        buttons.addView(chip(label) { if (queue.isNotEmpty()) makeNextFromList() }.apply {
            when {
                busy -> { background = Theme.goldButton(context); setTextColor(Theme.GOLD_TEXT) }
                queue.isEmpty() -> setTextColor(DIM)
                else -> primary()
            }
            textSize = 14f
            setPadding(dp(12), dp(12), dp(12), dp(12))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        // Small, and a second tap within 3 s, so a tap meant for the button beside it can't empty the list.
        buttons.addView(chip("Clear") {
            val now = SystemClock.uptimeMillis()
            if (now - clearAskedAt < 3000) { clearAskedAt = -10_000L; saveCraftList(emptyList()); craftDone.clear(); craftRunning = null; say("The list is empty"); render() }
            else { clearAskedAt = now; say("Tap Clear again to empty the list") }
        }.apply { setTextColor(DIM); setPadding(dp(14), dp(12), dp(14), dp(12)) })
        footer.addView(buttons)
    }

    /**
     * One entry of the list page: its number (✓ when made), the recipe and under it the item
     * it goes on (or the profession), its count on the right, and a small ✕ to take it off.
     */
    private fun listEntry(n: Int, job: CraftJob, done: Boolean, next: Boolean, count: Pair<String, Int>, why: String?, onRemove: (() -> Unit)?): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = Theme.panel(context, if (next) RAISED else SURFACE, gold = next)
            setPadding(dp(10), dp(6), dp(6), dp(6))
        }
        row.addView(line(if (done) "✓" else "$n", if (done) Theme.GOOD else if (next) ACCENT else DIM, 16f, bold = true).apply {
            gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER; setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(dp(26), -2).apply { marginEnd = dp(8) })
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val times = if (job.on != null || job.count == ALL) "" else "${job.count} × "
        words.addView(line(times + job.name, if (done) DIM else if (next) ACCENT else TEXT, 15f, bold = next).apply {
            setPadding(0, 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        words.addView(line(job.on?.let { "on ${targetName(it)}" } ?: job.profession, DIM, 12.5f).apply {
            setPadding(0, dp(1), 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        })
        if (why != null) words.addView(line(why, Theme.WARN, 12f).apply { setPadding(0, dp(2), 0, 0) })
        row.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(line(count.first, count.second, 13.5f, bold = count.second == Theme.GOOD || count.second == ACCENT).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL; textAlignment = View.TEXT_ALIGNMENT_VIEW_END; setPadding(dp(8), 0, dp(8), 0); maxLines = 1
        }, LinearLayout.LayoutParams(-2, -2))
        // (A made one keeps an unseen ✕, so its count lines up with the others.)
        row.addView(chip("✕") { onRemove?.invoke() }.apply {
            setTextColor(DIM); setPadding(dp(10), dp(4), dp(10), dp(4))
            if (onRemove == null) { visibility = View.INVISIBLE; isClickable = false }
        })
        return row
    }


    /** A recipe's card: what it makes, its materials (have / need, grey when missing) and the Make buttons. */
    private fun renderRecipe(r: Recipe, row: Int, profession: String, bags: (Int) -> Int, inBank: Map<Int, Int>) {
        val known = names[r.itemId]
        // An enchantment's card is a little taller: its Enchant button also waits under the items it goes on.
        sheet(if (r.enchantment) "Enchantment" else "Recipe", { craftRecipe = null; render() }, if (r.enchantment) 0.56f else 0.42f, "recipe|$profession|${r.name}") { recipeCard(this, r, row, profession, bags, inBank) }
    }

    private fun recipeCard(box: LinearLayout, r: Recipe, row: Int, profession: String, bags: (Int) -> Int, inBank: Map<Int, Int>) {
        val known = names[r.itemId]
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            if (r.itemId > 0) setOnClickListener { showDetails(r.itemId) }
        }
        top.addView(recipeIcon(r, known?.quality, 40), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(line(r.name, known?.let { qualityColour(it.quality) } ?: TEXT, 16f, bold = true).apply { setPadding(0, 0, 0, 0) })
        val makes = when {
            r.enchantment -> "Goes on an item"
            r.madeMax > r.made -> "Makes ${r.made} to ${r.madeMax}"
            else -> "Makes ${r.made}"
        }
        val point = when (r.colour) {
            'o' -> "orange: a skill point every time"
            'm' -> "yellow: a skill point most times"
            'e' -> "green: a skill point now and then"
            else -> "grey: no more skill points"
        }
        words.addView(line("$makes  ·  ", DIM, 13f).apply {
            val t = android.text.SpannableStringBuilder("$makes  ·  ")
            val start = t.length
            t.append(point)
            t.setSpan(android.text.style.ForegroundColorSpan(recipeColour(r.colour)), start, t.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            text = t
            setPadding(0, dp(2), 0, 0)
        })
        top.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(top)
        // An enchantment: what it goes on and Enchant first, so they fit on the card without scrolling.
        if (r.enchantment) enchantCard(box, r, row, profession, bags)

        box.addView(line("Materials", DIM, 12f).apply { setPadding(0, dp(8), 0, dp(2)) })
        for (m in r.materials) {
            val have = if (m.itemId > 0) bags(m.itemId) else 0
            val banked = if (m.itemId > 0) inBank[m.itemId] ?: 0 else 0
            val mName = names[m.itemId]?.name ?: m.name.ifEmpty { "Item #${m.itemId}" }
            val enough = have >= m.count
            val withBank = have + banked >= m.count
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(3), 0, dp(3)) }
            row.addView(icon(m.itemId.takeIf { it > 0 }, names[m.itemId]?.let { qualityColour(it.quality) }, 28).apply { if (!enough) alpha = 0.45f },
                LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(8) })
            row.addView(line(mName, if (enough) TEXT else DIM, 14f).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            val count = "$have / ${m.count}" + if (banked > 0) "  (+$banked in the bank)" else ""
            row.addView(line(count, when { enough -> Theme.GOOD; withBank -> Theme.WARN; else -> DIM }, 13f).apply { setPadding(dp(8), 0, 0, 0) })
            if (m.itemId > 0) row.setOnClickListener { showDetails(m.itemId, have) }
            box.addView(row)
        }
        if (bank == null && r.materials.isNotEmpty()) box.addView(line("Open your bank once to count what is in it too.", DIM, 12f).apply { setPadding(0, dp(2), 0, 0) })

        if (r.enchantment) {
            enchantSwitch(box)
            box.addView(line("When the item already has an enchantment, or isn't bound to you yet, the game asks first: answer with Yes or No here.", DIM, 12f).apply { setPadding(0, dp(6), 0, 0) })
            return
        }

        // Make (see runJob), or onto the crafting list for later.
        val n = r.canMake(bags) ?: 0
        val waiting = r.readyAt > System.currentTimeMillis() / 1000
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
        for ((label, count) in listOf("Make one" to 1, "Make 5" to 5, "Make all ($n)" to ALL)) {
            buttons.addView(chip(label) {
                when {
                    waiting -> say("${r.name} isn't ready yet: its cooldown is still running")
                    n <= 0 -> say("Not enough materials for ${r.name} in your bags")
                    craftRunning?.finished() == false -> say("Still making ${craftRunning!!.job.name}: wait until it is done (or put this on the list)")
                    else -> runJob(CraftJob(profession, row, r.itemId, r.name, count))
                }
            }.apply {
                if (n <= 0 || waiting || (count == 5 && n < 5)) setTextColor(DIM)
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        }
        box.addView(buttons)
        val later = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        later.addView(line("To the list:", DIM, 13f).apply { setPadding(0, 0, dp(6), 0) })
        for ((label, count) in listOf("+1" to 1, "+5" to 5, "+ all" to ALL)) {
            later.addView(chip(label) {
                addToList(CraftJob(profession, row, r.itemId, r.name, count))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        }
        box.addView(later)
    }

    /**
     * An enchantment's card, above its materials: the items it goes on (as the game listed
     * them; tap one to pick it) and Enchant, which puts it on the picked one.
     */
    private fun enchantCard(box: LinearLayout, r: Recipe, row: Int, profession: String, bags: (Int) -> Int) {
        var stale = false
        val fits = currentFits(r) { stale = true }
        box.addView(line("Put it on", DIM, 12f).apply { setPadding(0, dp(8), 0, dp(2)) })
        if (fits.isEmpty()) {
            box.addView(line(if (stale) "Your gear changed since Enchanting was last open: open it in the game once, and this catches up."
                else "None of your items takes it right now.", DIM, 13f).apply { setPadding(0, dp(2), 0, 0) })
            return
        }
        // The item picked here, else the one enchanted last (as the game's window keeps it in its slot).
        val last = lastEnchant()?.on
        val pick = fits.firstOrNull { it.place == craftTarget }
            ?: last?.let { l -> fits.firstOrNull { it.place == l.place && it.itemId == l.itemId } ?: fits.firstOrNull { it.itemId == l.itemId } }
            ?: fits.first()
        for (t in fits) {
            box.addView(targetRow(t, if (t == pick) "✓" else "", t == pick, compact = true) { craftTarget = t.place; render() },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
        }
        val n = r.canMake(bags) ?: 0
        box.addView(chip("✦  Enchant ${targetName(pick)}") {
            when {
                n <= 0 -> say("Not enough materials for ${r.name} in your bags")
                craftRunning?.finished() == false -> say("Still busy with ${craftRunning!!.job.name}: wait until it is done")
                else -> runEnchant(profession, row, r, pick)
            }
        }.apply {
            if (n > 0) primary() else setTextColor(DIM)
            textSize = 14f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        if (n <= 0) box.addView(line("Not enough materials in your bags yet: see below.", Theme.WARN, 12f).apply { setPadding(0, dp(4), 0, 0) })
        // For skilling: the same item several times, one tap of Make next each (as the game's Enchant button).
        val later = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        later.addView(line("To the list:", DIM, 13f).apply { setPadding(0, 0, dp(6), 0) })
        for ((label, count) in listOf("+1" to 1, "+5" to 5, "+ all" to ALL)) {
            later.addView(chip(label) {
                addToList(CraftJob(profession, row, 0, r.name, count, on = pick))
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        }
        box.addView(later)
        if (stale) box.addView(line("Some gear changed since Enchanting was last open: open it in the game once to see it all here.", DIM, 12f).apply { setPadding(0, dp(4), 0, 0) })
    }

    /**
     * For skilling (the same item again and again): the Enchant tap also says yes when the
     * game asks to replace the enchantment on it. Binding an item to you is always asked.
     * Below the materials, so they come right under the Enchant button.
     */
    private fun enchantSwitch(box: LinearLayout) {
        val replace = replaceWithoutAsking()
        val toggle = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        toggle.addView(line("Replace an old enchantment without asking", TEXT, 13f).apply { setPadding(0, 0, dp(8), 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        toggle.addView(chip(if (replace) "On" else "Off") { setReplaceWithoutAsking(!replace); render() }.apply {
            if (replace) primary()
            setPadding(dp(16), dp(6), dp(16), dp(6))
        })
        box.addView(toggle)
    }

    /** The enchanting switch above (kept on this device): one tap enchants and replaces. */
    private fun replaceWithoutAsking() = getPreferences(MODE_PRIVATE).getBoolean("enchantReplace", false)
    private fun setReplaceWithoutAsking(on: Boolean) {
        getPreferences(MODE_PRIVATE).edit().putBoolean("enchantReplace", on).apply()
        say(if (on) "Enchant now replaces an old enchantment without asking (binding an item is still asked)" else "The game asks again before replacing an enchantment")
    }

    /** The last enchantment made from the app and the item it went on, for Enchant again (kept per character). */
    private data class LastEnchant(val profession: String, val row: Int, val name: String, val on: EnchantTarget) {
        fun line() = listOf(profession, row, name, on.place, on.itemId).joinToString("\t")
        companion object {
            fun parse(text: String): LastEnchant? {
                val f = text.split('\t')
                if (f.size < 5) return null
                return LastEnchant(f[0], f[1].toIntOrNull() ?: return null, f[2], EnchantTarget(f[3].toIntOrNull() ?: return null, f[4].toIntOrNull() ?: 0))
            }
        }
    }
    private fun lastEnchant(): LastEnchant? = getPreferences(MODE_PRIVATE).getString(craftKey("lastEnchant"), null)?.let { LastEnchant.parse(it) }
    /** Whether the last thing done was an enchantment (Enchant again) or a craft (Make again). */
    private fun lastWasEnchant() = getPreferences(MODE_PRIVATE).getBoolean(craftKey("lastWasEnchant"), false) && lastEnchant() != null

    /** Enchant again: the last enchantment on the same item, found again when it moved in the bags. */
    private fun enchantAgain() {
        val e = lastEnchant() ?: return
        craftRunning?.let { if (!it.finished()) { say("Still busy with ${it.job.name}: wait until it is done"); return } }
        val (row, r) = findJob(CraftJob(e.profession, e.row, 0, e.name, 1))
            ?: run { say("${e.name} isn't in the ${e.profession} recipes any more: open that window in the game once"); return }
        val fits = currentFits(r)
        val on = fits.firstOrNull { it.place == e.on.place && it.itemId == e.on.itemId } ?: fits.firstOrNull { it.itemId == e.on.itemId }
        if (on == null) {
            craftProfession = e.profession; craftByGear = false; craftRecipe = r; craftSheet = null; render()
            say("${targetName(e.on)} doesn't take ${e.name} now: pick an item on the card")
            return
        }
        val have = state?.items.orEmpty().groupBy { it.itemId }.mapValues { g -> g.value.sumOf { it.count } }
        if ((r.canMake { have[it] ?: 0 } ?: 0) <= 0) { say("Not enough materials for ${e.name} in your bags"); return }
        runEnchant(e.profession, row, r, on)
    }

    /**
     * Enchants [on] with [r], as the game's own enchanting window does (addon 0.30.4): one
     * tap, the Make keys with the item's place, the profession's open key first when its
     * window is closed. Kept for Enchant again (the same item, as when skilling), not as the
     * last craft; with the replace switch on (addon 0.30.6) the same tap also says yes when
     * the game asks to replace the item's enchantment.
     */
    private fun runEnchant(profession: String, row: Int, r: Recipe, on: EnchantTarget): Boolean {
        val job = CraftJob(profession, row, 0, r.name, 1)
        val at = findJob(job)?.first ?: run { say("${r.name} isn't in the $profession recipes any more: open that window in the game once"); return false }
        val p = professions
        val openKey = p?.openKeys?.get(profession)?.let { ActionKeys.forIndex(it) }
        val n = openKey?.substringAfterLast("-F")?.toIntOrNull()
        val seqBefore = p?.progress?.seq ?: 0
        val label = "${r.name} on ${targetName(on)}"
        val replace = replaceWithoutAsking()
        if (p != null && p.makeKeys && openKey != null && n != null) {
            sendMakeKeys(openKey, Professions.makeKeys(n, at, 1, on, replace), p.open != profession || gameAddon == null, "Enchanting: $label")
        } else {
            make(label, profession, "1", r.name.lowercase(Locale.ROOT) + " @${on.place}:${on.itemId}" + if (replace) ":1" else "")
        }
        getPreferences(MODE_PRIVATE).edit().putString(craftKey("lastEnchant"), LastEnchant(profession, at, r.name, on).line())
            .putBoolean(craftKey("lastWasEnchant"), true).apply()
        craftTarget = on.place
        craftRunning = CraftRun(job, 1, 1, 0, seqBefore, on = targetName(on))
        render()
        return true
    }

    /** The Make keys for one tap ([press], see Professions.makeKeys), [openKey] first when the window may be closed. */
    private fun sendMakeKeys(openKey: String, press: List<String>, opening: Boolean, label: String) {
        val target = gameDisplay()
        lastKeyAt = SystemClock.uptimeMillis()
        say(label)
        keys.execute {
            if (opening) {
                KeySender.send(this, target, openKey)
                SystemClock.sleep(300)  // the window opens on the key's release, a frame or two later
            }
            for (k in press) {
                val err = KeySender.send(this, target, k)
                if (err != null && !err.startsWith("(focus")) return@execute runOnUiThread { say("Not done: $err") }
                SystemClock.sleep(10)
            }
            lastKeyAt = SystemClock.uptimeMillis()
        }
    }

    /**
     * Something to make: a recipe (its [row] in the profession's recipes, 1 first) and how many
     * ([ALL]: as many as the bags allow). An enchantment also says the item it goes [on]; it is
     * made one tap each, as the game's window enchants an item once per tap, and [made] counts
     * the ones the game did so far (the entry stays where it is until they are all done).
     */
    private data class CraftJob(val profession: String, val row: Int, val itemId: Int, val name: String, val count: Int,
                                val on: EnchantTarget? = null, val made: Int = 0) {
        fun label() = (if (count == ALL) "all" else "$count") + " × " + name
        fun line() = (listOf(profession, row, itemId, count, name) + (on?.let { listOf(it.place, it.itemId, made) } ?: emptyList())).joinToString("\t")
        companion object {
            fun parse(s: String): CraftJob? {
                val f = s.split('\t')
                if (f.size < 5) return null
                val on = if (f.size >= 7) EnchantTarget(f[5].toIntOrNull() ?: return null, f[6].toIntOrNull() ?: 0) else null
                val made = f.getOrNull(7)?.toIntOrNull() ?: 0
                return CraftJob(f[0], f[1].toIntOrNull() ?: return null, f[2].toIntOrNull() ?: 0, f[4], f[3].toIntOrNull() ?: return null, on, made)
            }
        }
    }

    /** A list entry as it is shown: "5 × Enchant Chest - Lesser Health on Gray Woolen Robe" (shorter: without "Enchant Chest - "). */
    private fun jobLabel(job: CraftJob, short: Boolean = false): String {
        val on = job.on ?: return job.label()
        return (if (job.count == ALL) "all" else "${job.count}") + " × " + enchantWhat(job, short)
    }
    private fun enchantWhat(job: CraftJob, short: Boolean) =
        (if (short) job.name.substringAfter(" - ", job.name) else job.name) + (job.on?.let { " on " + targetName(it) } ?: "")

    /** An enchantment entry's next one and how many in all: (3, 5) for "3 of 5"; with + all, what the bags still allow. */
    private fun enchantStep(job: CraftJob): Pair<Int, Int> {
        val total = if (job.count != ALL) job.count else {
            val have = state?.items.orEmpty().groupBy { it.itemId }.mapValues { g -> g.value.sumOf { it.count } }
            job.made + maxOf(1, recipeOf(job)?.canMake { have[it] ?: 0 } ?: 0)
        }
        return minOf(job.made + 1, total) to total
    }

    /** The crafting list (made one by one with Make next) and the last thing made, kept per character. */
    private fun craftKey(what: String) = "$what|${keptFor.orEmpty()}"
    private fun craftList(): MutableList<CraftJob> =
        getPreferences(MODE_PRIVATE).getString(craftKey("craftList"), null)?.split('\n')?.mapNotNull { CraftJob.parse(it) }?.toMutableList()
            ?: mutableListOf()
    private fun saveCraftList(list: List<CraftJob>) =
        getPreferences(MODE_PRIVATE).edit().putString(craftKey("craftList"), list.joinToString("\n") { it.line() }).apply()
    private fun lastJob(): CraftJob? = getPreferences(MODE_PRIVATE).getString(craftKey("lastCraft"), null)?.let { CraftJob.parse(it) }

    private fun addToList(job: CraftJob) {
        val list = craftList()
        if (list.isEmpty() && craftRunning?.finished() != false) { craftDone.clear(); craftRunning = null }  // the last list was done: this starts a new one
        // The same recipe again: one entry with both counts.
        val i = list.indexOfFirst { it.profession == job.profession && it.row == job.row && it.name == job.name && it.on == job.on }
        if (i >= 0) {
            val old = list[i]
            list[i] = old.copy(count = if (old.count == ALL || job.count == ALL) ALL else old.count + job.count)  // (made stays)
        } else list += job
        saveCraftList(list)
        say("On the crafting list: ${jobLabel(if (i >= 0) list[i] else job)}")
        render()
    }

    /**
     * A profession's recipes: all its pages from the last time the window was read, or,
     * while a newer reading is still coming in page by page, the last complete one (so the
     * list doesn't shrink to a page and grow again).
     */
    private fun recipesOf(profession: String): List<Recipe> {
        val joined = RecipePage.join(recipePages.values.filter { it.profession == profession })
        if (joined != null) { completeRecipes[profession] = joined; return joined }
        return completeRecipes[profession] ?: run {
            val pages = recipePages.values.filter { it.profession == profession }
            val newest = pages.maxOfOrNull { it.readAt }
            pages.filter { it.readAt == newest }.sortedBy { it.page }.flatMap { it.recipes }
        }
    }

    /**
     * Where [job]'s recipe is in the recipes now (1 first) and the recipe, or null when it
     * isn't there any more. Looked up by name when a recipe learned since moved the rows.
     */
    private fun findJob(job: CraftJob): Pair<Int, Recipe>? {
        val recipes = recipesOf(job.profession)
        recipes.getOrNull(job.row - 1)?.takeIf { it.name == job.name }?.let { return job.row to it }
        val i = recipes.indexOfFirst { !it.heading && it.name == job.name && (job.itemId == 0 || it.itemId == job.itemId) }
        return if (i >= 0) (i + 1) to recipes[i] else null
    }

    private fun recipeOf(job: CraftJob): Recipe? = findJob(job)?.second

    /**
     * Makes [job]: with the addon's Make keys, one key press says it all and crafts (see
     * Professions.makeKeys; the profession's open key first when its window is closed), so
     * nothing is typed; with an older addon, through the chat (see make). True when it went out.
     */
    private fun runJob(job: CraftJob): Int {
        val found = findJob(job)
        if (found == null) { say("${job.name} isn't in the ${job.profession} recipes any more: open that window in the game once"); return 0 }
        val (row, r) = found
        val have = state?.items.orEmpty().groupBy { it.itemId }.mapValues { e -> e.value.sumOf { it.count } }
        val can = r.canMake { have[it] ?: 0 } ?: 0
        if (can <= 0) { say("Not enough materials for ${job.name} in your bags"); return 0 }
        val count = if (job.count == ALL) can else minOf(job.count, can)
        getPreferences(MODE_PRIVATE).edit().putString(craftKey("lastCraft"), job.line()).putBoolean(craftKey("lastWasEnchant"), false).apply()
        val p = professions
        val openKey = p?.openKeys?.get(job.profession)?.let { ActionKeys.forIndex(it) }
        val n = openKey?.substringAfterLast("-F")?.toIntOrNull()
        if (p != null && p.makeKeys && openKey != null && n != null) {
            // The addon this app brought (0.29) makes the open key do nothing while the window
            // is open, so it is pressed every time: what the app last heard about the window can be old.
            sendMakeKeys(openKey, Professions.makeKeys(n, row, count), p.open != job.profession || gameAddon == null, "Making $count × ${job.name}")
        } else {
            make(job.name, job.profession, count.toString(), if (r.itemId > 0) r.itemId.toString() else r.name.lowercase(Locale.ROOT))
        }
        return count
    }

    /**
     * Make by typing, when the addon's Make keys aren't known to the app yet: the game
     * crafts only in the open profession window, so when it isn't open the app first opens
     * it (the addon's open key, or /cast with an older addon) and types /thor make right
     * after; the addon waits a few seconds for the window and makes it then. The app doesn't
     * wait for the game to say the window is open: that message can come late.
     */
    private fun make(name: String, profession: String, count: String, what: String) {
        val p = professions
        if (p?.open != profession) {
            val key = p?.openKeys?.get(profession)?.let { ActionKeys.forIndex(it) }
            if (key != null) pressKey(key) { err -> say("Couldn't open the $profession window: $err") }
            else sendChat("Open $profession", "", "/cast ${Professions.windowSpell(profession)}")
        }
        sendChat("Make $name", "", "/thor make $count $what")
        // Then ask the game for its keys again (it sends everything once more), so the next Make needs no typing.
        if (p?.makeKeys != true || p.openKeys[profession] == null) pressKey(ActionKeys.REFRESH) { }
    }

    /** The quest log: quests in this zone first, ready ones marked; tap one to see it on the map. */
    private fun renderQuests() {
        val log = quests
        if (log == null) {
            content.addView(empty("No quests yet", "They arrive with the next update from the game."))
            return
        }
        levelCard()?.let { content.addView(it, cardParams()) }
        errandsCard(log)?.let { content.addView(it, cardParams()) }
        if (log.quests.isEmpty()) {
            content.addView(empty("Your quest log is empty", ""))
            return
        }
        // Nearest first, so the next thing to do is on top.
        val near = log.quests.associateWith { heading(it) }
        fun byDistance(list: List<Quest>) = list.sortedBy { q -> near[q]?.let { h -> h.yards ?: 0 } ?: Int.MAX_VALUE }
        for ((title, list) in listOf("Ready to turn in" to byDistance(log.quests.filter { it.ready }),
            "In this zone" to byDistance(log.quests.filter { it.here && !it.ready }), "Elsewhere" to log.quests.filter { !it.here && !it.ready })) {
            if (list.isEmpty()) continue
            content.addView(section(title))
            for (q in list) content.addView(questCard(q, near[q]), cardParams())
        }
    }

    private fun questCard(q: Quest, h: Compass.Heading?): View {
        val selected = q.title == tracked
        val box = card().apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
            if (selected) background = Theme.panel(context, Theme.RAISED, gold = true)
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        // The title in the game's quest colours: red too hard, yellow right, green easy, grey trivial.
        val colour = when (q.difficulty(state?.level ?: 0)) {
            Difficulty.VERY_HARD -> Color.rgb(255, 64, 64)
            Difficulty.HARD -> Color.rgb(255, 128, 64)
            Difficulty.NORMAL -> Color.rgb(255, 210, 0)
            Difficulty.EASY -> Color.rgb(64, 192, 64)
            Difficulty.TRIVIAL -> Color.rgb(160, 160, 160)
        }
        val name = if (q.level > 0) "[${q.level}] ${q.title}" else q.title
        top.addView(line(name, colour, 15f, bold = true).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (q.xp > 0) top.addView(line(String.format(Locale.US, "%,d XP", q.xp), Theme.XP_TEXT, 12f).apply { setPadding(dp(8), 0, 0, 0) })
        box.addView(top)
        if (q.ready) box.addView(line("Ready to turn in", Theme.GOOD, 12f).apply { setPadding(0, dp(2), 0, 0) })
        val away = q.waypoint?.takeIf { it.mapId != state?.mapId }?.zone?.ifEmpty { null }
        val where = when {
            h != null -> distance(h)
            q.ready && away != null -> "Turn it in at ${turnInPlace(q, away)}"
            away != null -> "In $away"
            // No waypoint from the game, but the quest's text names where to hand it in.
            q.ready && q.waypoint == null && q.place.isNotEmpty() -> "Turn it in at ${q.place}"
            else -> null
        }
        if (where != null) box.addView(line(where, if (selected) Color.WHITE else DIM, 12f).apply { setPadding(0, dp(2), 0, 0) })
        for (o in q.objectives) {
            box.addView(line((if (o.done) "✓ " else "• ") + o.text, if (o.done) DIM else TEXT, 13f).apply { setPadding(dp(4), dp(1), 0, 0) })
        }
        box.setOnClickListener { selectQuest(q) }
        return box
    }

    /** Where a quest ready to turn in is handed in, in another zone: "Brill, Tirisfal Glades", or the zone alone. */
    private fun turnInPlace(q: Quest, zone: String) = listOf(q.place, zone).filter { it.isNotEmpty() }.distinct().joinToString(", ")

    /** Selects quest [q]: its places get a white ring on the map, which zooms to show you and the nearest one. */
    private fun selectQuest(q: Quest) {
        run {
            tracked = q.title
            ledTo = null
            getPreferences(MODE_PRIVATE).edit().putString("tracked", q.title).apply()
            val h = heading(q)
            val x = state?.x; val y = state?.y
            if (h != null && x != null && y != null) {
                val far = maxOf(Math.abs(h.place.x - x), Math.abs(h.place.y - y) * 1.5)
                getPreferences(MODE_PRIVATE).edit().putBoolean("mapClose", far < 0.38)
                    .putFloat("mapSpan", (far * 2.6).coerceIn(MapView.MIN_SPAN, 1.0).toFloat()).apply()
            }
            show(Panel.MAP)
            val away = q.waypoint?.takeIf { it.mapId != state?.mapId }?.zone?.ifEmpty { null }
            say(when {
                h != null -> "${q.title}: marked with a white ring on the map"
                q.ready && away != null -> "${q.title}: turn it in at ${turnInPlace(q, away)}, not in this zone"
                away != null -> "${q.title}: the next step is in $away, not in this zone"
                q.ready && q.waypoint == null && q.place.isNotEmpty() -> "${q.title}: turn it in at ${q.place} (the game gives no map spot for it)"
                else -> "${q.title}: not on this zone's map"
            })
        }
    }

    /**
     * Errands: the quests ready to turn in grouped by the town where they are handed in,
     * plus the class trainer when spells are ready, so you can plan one trip for several.
     * Folded to its title and count until tapped; stops in this zone come first, nearest
     * on top, the rest by experience. Tapping a stop selects its first quest on the map.
     */
    private fun errandsCard(log: QuestLog): View? {
        val stops = Errands.of(log, spells)
        if (stops.isEmpty()) return null
        val count = stops.sumOf { it.errands.size }
        val open = getPreferences(MODE_PRIVATE).getBoolean("errandsOpen", false)
        val box = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(line("Errands ($count)", Theme.GOLD_TEXT, 16f).apply { typeface = Theme.heading(context); setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (!open) top.addView(line(if (stops.size == 1) "1 stop" else "${stops.size} stops", DIM, 13f).apply { setPadding(dp(8), 0, dp(8), 0) })
        top.addView(line(if (open) "▴" else "▾", DIM, 18f).apply { setPadding(dp(4), 0, 0, 0) })
        top.setOnClickListener {
            getPreferences(MODE_PRIVATE).edit().putBoolean("errandsOpen", !open).apply()
            render()
        }
        box.addView(top)
        if (!open) return box
        // How far each stop is: its nearest turn-in on this zone's map, when there is one.
        val yards = stops.associateWith { stop ->
            stop.errands.mapNotNull { e -> e.quest?.let { heading(it) } }.minByOrNull { it.yards ?: Int.MAX_VALUE }
        }
        val here = stops.filter { yards[it] != null || (it.at != null && it.at?.mapId == state?.mapId) }
            .sortedBy { yards[it]?.yards ?: Int.MAX_VALUE }
        val known = stops.filter { it !in here && it.name != Errands.UNKNOWN }.sortedByDescending { it.xp }
        val ordered = here + known + stops.filter { it !in here && it.name == Errands.UNKNOWN }
        val best = stops.maxByOrNull { it.xp }?.takeIf { stops.size > 1 && it.xp > 0 }
        for (stop in ordered) {
            val s = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                background = Theme.panel(context, RAISED, if (stop == best) ACCENT else STROKE)
                setPadding(dp(10), dp(6), dp(10), dp(7))
            }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(line(stop.name, TEXT, 14f, bold = true).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            if (stop == best) head.addView(line("most XP", ACCENT, 11f, bold = true).apply { setPadding(dp(8), 0, 0, 0) })
            val where = listOfNotNull(
                stop.zone.ifEmpty { null },
                yards[stop]?.yards?.let { if (it < 15) "you're there" else "$it yd" },
            ).joinToString("  ·  ")
            if (where.isNotEmpty()) head.addView(line(where, DIM, 12f).apply { setPadding(dp(8), 0, 0, 0) })
            s.addView(head)
            for (e in stop.errands) {
                val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                r.addView(line((if (e.trainer) "✦ " else "! ") + e.title, if (e.trainer) ACCENT else TEXT, 13f).apply { setPadding(0, dp(2), 0, dp(2)) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                when {
                    e.xp > 0 -> r.addView(line(String.format(Locale.US, "%,d XP", e.xp), Theme.XP_TEXT, 12f).apply { setPadding(dp(8), dp(2), 0, dp(2)) })
                    e.cost > 0 -> r.addView(line("", DIM, 12f).apply { text = coins(e.cost); setPadding(dp(8), dp(2), 0, dp(2)) })
                }
                s.addView(r)
            }
            stop.errands.firstOrNull { it.quest != null }?.quest?.let { q -> s.setOnClickListener { selectQuest(q) } }
            box.addView(s, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val turnIns = stops.sumOf { st -> st.errands.count { it.quest != null } }
        val xp = stops.sumOf { it.xp }
        if (turnIns > 0) {
            val c = character
            val toGo = c?.let { (it.xpMax ?: 0) - (it.xp ?: 0) } ?: 0
            var sum = String.format(Locale.US, "%d turn-in%s  ·  %,d XP", turnIns, if (turnIns == 1) "" else "s", xp)
            if (toGo in 1..xp) sum += ", that's level ${(state?.level ?: 0) + 1}"
            box.addView(line(sum, DIM, 12f).apply { setPadding(dp(2), dp(8), 0, 0) })
        }
        return box
    }

    /** The selected quest (the last one tapped in the Quests tab): a white ring on the map and its distance, until ✕. */
    private var tracked: String? = null

    /** Where the nearest place of quest [q] is from you on this zone's map, or null. */
    private fun heading(q: Quest?): Compass.Heading? {
        if (q == null) return null
        val s = state
        val map = zoneMap?.takeIf { it.mapId == s?.mapId } ?: return null
        val x = s?.x ?: return null
        val y = s.y ?: return null
        if (x <= 0) return null
        // To a quest area's nearest edge (you're there once inside it), not its middle.
        val near = map.places.map { if (Compass.matches(it.label, q.title)) nearPoint(map, it, x, y) else it }
        return Compass.toQuest(map.copy(places = near), q.title, x, y, q.waypoint)
    }

    /** The quest with this title in the log, or null. */
    private fun questOf(title: String?) = quests?.quests?.firstOrNull { it.title == title }

    /** "120 yd north-east to the objective" for a quest, or null when it isn't on this map. */
    private fun distance(h: Compass.Heading): String {
        val what = when (h.place.kind) { 'Q' -> "to turn it in"; 'a' -> "to where it starts"; else -> "to the objective" }
        return when (val yd = h.yards) {
            null -> "On the map ($what); walk a bit and the yards appear"
            in 0..14 -> "You're there"
            else -> "$yd yd ${Compass.direction(h.bearing)} $what"
        }
    }

    /** The selected quest's line on the Map tab, kept up to date as you walk. */
    private fun updateCompass() {
        val line = questLine ?: return
        val title = tracked
        val row = line.parent as? View
        val map = zoneMap?.takeIf { it.mapId == state?.mapId }
        if (ghost && !corpseHidden && map != null && map.ghost) {
            // A ghost: your corpse first, before any quest or pin.
            row?.visibility = View.VISIBLE
            val c = map.corpse
            line.text = if (c == null) "Your corpse: the game doesn't show where it is"
                else "${c.label.ifEmpty { "Your corpse" }}  ·  " + (wayTo(c)?.let { if (it == "you're there") "You're there" else it } ?: "walk a bit and the yards appear")
            mapView?.targetNote = c?.let { it to "" }
            mapView?.highlight = null
            mapView?.invalidate()
            return
        }
        val led = ledTo?.takeIf { it.first == state?.mapId }?.second
        if (title == null && led != null) {
            // A pin you asked to be led to.
            row?.visibility = View.VISIBLE
            val name = led.label.ifEmpty { MapView.kindName(led.kind) }
            line.text = "$name  ·  " + (wayTo(led)?.let { if (it == "you're there") "You're there" else it } ?: "walk a bit and the yards appear")
            mapView?.targetNote = led to ""
            mapView?.highlight = null
            mapView?.invalidate()
            return
        }
        if (title == null) { row?.visibility = View.GONE; mapView?.targetNote = null; return }
        row?.visibility = View.VISIBLE
        val q = questOf(title)
        val h = heading(q)
        val away = q?.waypoint?.takeIf { it.mapId != state?.mapId }?.zone?.ifEmpty { null }
        line.text = when {
            h != null -> "$title  ·  ${distance(h)}"
            away != null -> "$title  ·  go to $away"
            else -> "$title  ·  not on this zone's map"
        }
        // Only the ring on the map: the yards are on the quest line above it (the map is small).
        mapView?.targetNote = h?.let { it.place to "" }
        mapView?.highlight = title
        mapView?.invalidate()
    }

    private var questLine: TextView? = null

    /**
     * The level checker: how far to the next level, the rested experience, how many
     * kills that is (the last kill's experience, with the rested bonus), and how much
     * the quests ready to turn in give, so you see at a glance when to go back.
     */
    private fun levelCard(): View? {
        val c = character ?: return null
        val xp = c.xp ?: return null
        val xpMax = c.xpMax ?: return null
        val log = quests
        val rested = c.rested ?: 0
        val kill = log?.lastKill ?: 0
        val plan = LevelPlan.of(xp, xpMax, rested, kill, log?.readyXp ?: 0) ?: return null
        val level = state?.level
        val box = card()
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(line(if (level != null) "Level $level → ${level + 1}" else "Next level", Theme.GOLD_TEXT, 17f).apply { typeface = Theme.heading(context); setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(line(String.format(Locale.US, "%.1f%%", plan.percent), ACCENT, 17f, bold = true).apply { setPadding(0, 0, 0, 0) })
        box.addView(top)
        box.addView(Bar(this).apply {
            value = xp.toFloat() / xpMax
            second = (xp + rested).toFloat() / xpMax
        }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8); bottomMargin = dp(4) })
        fun row(label: String, value: String, colour: Int = TEXT) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            r.addView(line(label, DIM, 14f).apply { setPadding(0, dp(2), 0, dp(2)) }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(line(value, colour, 14f, bold = true).apply { setPadding(0, dp(2), 0, dp(2)) })
            box.addView(r)
        }
        row("XP", String.format(Locale.US, "%,d / %,d  (%,d to go)", xp, xpMax, plan.toGo))
        row("Rested", String.format(Locale.US, "%,d", rested), Theme.RESTED_TEXT)
        row("Kills", plan.kills?.let { String.format(Locale.US, "~%d  (~%,d XP each)", it, kill) } ?: "kill something to find out")
        val ready = log?.readyCount ?: 0
        row("Quests ready ($ready)", String.format(Locale.US, "%,d XP", log?.readyXp ?: 0), if (ready > 0) ACCENT else TEXT)
        val advice = when {
            plan.questsEnough -> "Turn in your ready quests to ding!"
            ready > 0 && plan.killsAfterQuests != null -> "Kill ~${plan.killsAfterQuests}, then turn in to ding"
            plan.kills != null -> "Kill ~${plan.kills} to ding"
            else -> ""
        }
        if (advice.isNotEmpty()) box.addView(line(advice, Theme.GOOD, 15f, bold = true).apply { setPadding(0, dp(6), 0, 0) })
        spellsChip()?.let { box.addView(it, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) }) }
        return box
    }

    /** "✦ 1 spell ready at your trainer · 2 at level 15", or null when there is nothing to learn soon. */
    private fun spellsChip(): View? {
        val plan = spells?.takeIf { it.spells.isNotEmpty() } ?: return null
        fun count(n: Int) = if (n == 1) "1 spell" else "$n spells"
        val parts = mutableListOf<String>()
        if (plan.ready.isNotEmpty()) parts += "${count(plan.ready.size)} ready at your trainer"
        plan.later.entries.firstOrNull()?.let { (level, list) ->
            parts += if (parts.isEmpty()) "${count(list.size)} at level $level" else "${list.size} at level $level"
        }
        return chip("✦ " + parts.joinToString(" · ")) { showSpells() }.apply {
            setTextColor(Theme.GOLD_TEXT)
            background = Theme.goldButton(context)
        }
    }

    /** The spells to learn soon, under the list like an item's details; ✕ or a tap closes it. */
    private fun showSpells() {
        val plan = spells ?: return
        footer.removeAllViews()
        val box = card().apply { background = Theme.panel(context, RAISED, gold = true) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(line("Spells to learn", TEXT, 16f, bold = true).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(line("✕", DIM, 18f).apply { setPadding(dp(12), 0, 0, 0) })
        box.addView(top)
        fun section(title: String, colour: Int) {
            box.addView(line(title.uppercase(Locale.US), colour, 12f, bold = true).apply {
                letterSpacing = 0.06f
                setPadding(0, dp(10), 0, dp(2))
            })
        }
        fun spell(s: SpellToLearn) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val name = android.text.SpannableStringBuilder(s.name)
            if (s.rank.isNotEmpty()) {
                val start = name.length
                name.append("  ").append(s.rank)
                name.setSpan(android.text.style.ForegroundColorSpan(DIM), start, name.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                name.setSpan(android.text.style.RelativeSizeSpan(0.85f), start, name.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            r.addView(line("", TEXT, 14f).apply { text = name; setPadding(0, dp(3), 0, dp(3)) }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(line("", DIM, 13f).apply {
                text = if (s.cost > 0) coins(s.cost) else "cost after a trainer visit"
                setPadding(dp(8), dp(3), 0, dp(3))
            })
            box.addView(r)
        }
        if (plan.ready.isNotEmpty()) {
            section("Ready now at your trainer", Theme.GOOD)
            plan.ready.forEach { spell(it) }
        }
        for ((level, list) in plan.later) {
            section("Level $level", DIM)
            list.forEach { spell(it) }
        }
        if (plan.readyCost > 0) {
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(8), 0, 0) }
            r.addView(line("Ready now costs", DIM, 14f).apply { setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
            r.addView(line("", TEXT, 14f, bold = true).apply { text = coins(plan.readyCost); setPadding(0, 0, 0, 0) })
            box.addView(r)
        }
        box.setOnClickListener { footer.removeAllViews() }
        footer.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    /** An item's details under the list, after a long-press on it; a tap closes them. */
    private fun showDetails(itemId: Int, count: Int = 1, usable: Boolean = false) {
        footer.removeAllViews()
        val known = names[itemId]
        val box = card().apply { background = Theme.panel(context, RAISED, known?.let { Theme.withAlpha(qualityColour(it.quality), 170) } ?: STROKE) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        top.addView(icon(itemId, known?.let { qualityColour(it.quality) }, 40), LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(10) })
        val words = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        words.addView(line(known?.name ?: "Item #$itemId", known?.let { qualityColour(it.quality) } ?: TEXT, 16f, bold = true).apply { setPadding(0, 0, 0, 0) })
        known?.type?.takeIf { it.isNotEmpty() }?.let { words.addView(line(it, DIM, 13f).apply { setPadding(0, 0, 0, 0) }) }
        top.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(top)
        val facts = listOfNotNull(
            known?.itemLevel?.takeIf { it > 0 }?.let { "Item level $it" },
            known?.requiredLevel?.takeIf { it > 1 }?.let { "Requires level $it" },
        )
        if (facts.isNotEmpty()) box.addView(line(facts.joinToString("  ·  "), TEXT, 13f).apply { setPadding(0, dp(6), 0, 0) })
        // What the game's tooltip says: stats, "Use: ..." in green like the game shows it.
        tips[itemId]?.takeIf { it.isNotEmpty() }?.let { lines ->
            for (t in lines) {
                val colour = when {
                    t.startsWith("Use:") || t.startsWith("Equip:") || t.startsWith("Chance on hit:") -> Theme.GOOD
                    t.startsWith("\"") -> Color.rgb(255, 210, 0)
                    t.startsWith("+") -> TEXT
                    else -> DIM
                }
                box.addView(line(t, colour, 13f).apply { setPadding(0, dp(2), 0, 0) })
            }
        }
        compareWithGear(itemId)?.let { box.addView(it) }
        val price = known?.sellPrice ?: 0
        box.addView(line("", TEXT, 13f).apply {
            text = when {
                known == null -> "No details yet: they arrive with the item's name."
                price <= 0 -> "Can't be sold to a vendor"
                count > 1 -> android.text.SpannableStringBuilder("Sells for ").append(coins(price)).append("  ·  all $count: ").append(coins(price * count))
                else -> android.text.SpannableStringBuilder("Sells for ").append(coins(price))
            }
            setPadding(0, dp(2), 0, 0)
        })
        box.addView(line(if (usable) "Tap the item to use it. Tap here to close." else "Tap here to close.", DIM, 12f).apply { setPadding(0, dp(6), 0, 0) })
        box.setOnClickListener { footer.removeAllViews() }
        footer.addView(box, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
    }

    /**
     * For something you can wear: what it changes against what you wear in that slot
     * ("+32 Armor  +2 Strength  -1 Stamina", gains green, losses red), from both tooltips.
     * Null for what isn't worn, for what you wear yourself, and before the tooltip is in.
     */
    private fun compareWithGear(itemId: Int): View? {
        val lines = tips[itemId] ?: return null
        val item = ItemStats.parse(lines)
        val (slot, worn) = ItemCompare.wornFor(item, gear) ?: return null
        if (gear.any { it.itemId == itemId }) return null
        val slotName = Gear.slotName(slot).lowercase(Locale.ROOT)
        val wornStats = worn?.let { w -> tips[w.itemId]?.let { ItemStats.parse(it) } }
        val view = line("", TEXT, 13f).apply { setPadding(0, dp(8), 0, dp(2)) }
        if (worn != null && wornStats == null) {
            view.text = "Compared with your $slotName: its details are still coming in"
            view.setTextColor(DIM)
            return view
        }
        val t = android.text.SpannableStringBuilder()
        val title = if (worn == null) "Your $slotName slot is empty: you gain" else "Instead of ${names[worn.itemId]?.name ?: "your $slotName"}:"
        t.append(title)
        t.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, t.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val changes = ItemCompare.changes(item, wornStats)
        if (changes.isEmpty()) t.append("  the same stats")
        for (c in changes) {
            val v = if (c.change == Math.rint(c.change)) c.change.toLong().toString() else String.format(Locale.US, "%.1f", c.change)
            val start = t.length
            t.append("  ").append(if (c.change > 0) "+$v" else v).append(" ").append(c.stat)
            t.setSpan(android.text.style.ForegroundColorSpan(if (c.change > 0) Theme.GOOD else Theme.BAD), start, t.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        view.text = t
        return view
    }

    private fun chatLine(l: ChatLine): TextView {
        val n = l.name
        val colour = when (l.kind) {
            "whisper", "whisper_to" -> Color.rgb(255, 128, 255)
            "bnwhisper", "bnwhisper_to" -> Color.rgb(0, 255, 246)
            "party" -> Color.rgb(170, 170, 255)
            "raid" -> Color.rgb(255, 127, 0)
            "instance" -> Color.rgb(255, 127, 0)
            "guild" -> Color.rgb(64, 255, 64)
            "yell" -> Color.rgb(255, 64, 64)
            "emote" -> Color.rgb(255, 128, 64)
            "system" -> Color.rgb(255, 255, 0)
            "channel" -> Color.rgb(255, 192, 192)
            else -> TEXT
        }
        // The channel quiet, the name bold, then what was said.
        val text = android.text.SpannableStringBuilder()
        fun add(part: String, vararg spans: Any) {
            if (part.isEmpty()) return  // an empty span can't be set
            val start = text.length
            text.append(part)
            for (span in spans) text.setSpan(span, start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (l.channel.isNotEmpty()) add("${l.channel}  ", android.text.style.ForegroundColorSpan(DIM))
        val (before, after) = when {
            n.isEmpty() -> "" to ""
            l.kind == "whisper" -> "" to " whispers: "
            l.kind == "whisper_to" -> "To " to ": "
            l.kind == "emote" -> "" to " "
            l.kind == "yell" -> "" to " yells: "
            else -> "" to ": "
        }
        if (n.isNotEmpty()) {
            // The name in its class colour, like the game's chat frame (addon 0.30.3 and later say the class).
            val classFile = l.classFile ?: chat.classOf(l.sender)
            add(before, android.text.style.StyleSpan(Typeface.BOLD))
            if (classFile != null) add(n, android.text.style.StyleSpan(Typeface.BOLD), android.text.style.ForegroundColorSpan(classColour(classFile)))
            else add(n, android.text.style.StyleSpan(Typeface.BOLD))
            add(after, android.text.style.StyleSpan(Typeface.BOLD))
        }
        add(l.text)
        val view = line("", colour, 15f).apply { this.text = text; setPadding(0, dp(3), 0, dp(3)) }
        // Tap a line: write a whisper to its sender.
        if (l.sender.isEmpty() || l.sender == "?" || l.kind == "system") return view
        view.setOnClickListener { compose("Whisper to $n", "/w ${l.sender} ") }
        return view
    }

    /** The message being written, and to whom: (label, the chat command it starts with). */
    private var composing: Pair<String, String>? = null

    /** Stands for your group's command, which the addon says when the chat box opens ("/p ", "/raid " or "/i "). */
    private val GROUP = "group"

    /**
     * A text field with this screen's keyboard, to write a chat message. While it is
     * open the app takes the keyboard (the game gets it back after); Send then
     * presses the game's own Open Chat key (like Enter), waits until the addon says
     * the box is open, types the chat command and the message and presses Enter:
     * one tap on Send is one message, as if you had typed it in the game.
     */
    private fun compose(label: String, command: String) {
        if (panel != Panel.CHAT) show(Panel.CHAT)
        composing = label to command
        footer.removeAllViews()
        val field = KeyboardField(this).apply {
            hint = "$label…"
            setHintTextColor(DIM)
            setTextColor(TEXT)
            textSize = 15f
            typeface = Theme.body(context)
            isSingleLine = true
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEND
            background = Theme.panel(context, gold = true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            filters = arrayOf(android.text.InputFilter.LengthFilter(255))  // the game's limit
        }
        fun send() {
            val text = field.text.toString().trim()
            closeCompose()
            if (text.isNotEmpty()) sendChat(label, command, text)
        }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
        // Quick replies: one tap sends one of your short messages to the same place.
        val quick = Flow(this, dp(6)).apply { setPadding(0, dp(4), 0, 0) }
        fun fillQuick() {
            quick.removeAllViews()
            for (reply in quickReplies()) {
                quick.addView(chip(reply) {
                    closeCompose()
                    sendChat(label, command, reply)
                }.apply {
                    setOnLongClickListener {
                        saveQuickReplies(quickReplies() - reply)
                        say("Quick reply \"$reply\" removed")
                        fillQuick()
                        true
                    }
                })
            }
            quick.addView(chip("+ Save typed text") {
                val text = field.text.toString().trim()
                when {
                    text.isEmpty() -> say("Type a message first, then tap + Save typed text to keep it as a quick reply")
                    text in quickReplies() -> say("\"$text\" is already a quick reply")
                    else -> { saveQuickReplies(quickReplies() + text); say("Saved \"$text\" as a quick reply"); fillQuick() }
                }
            }.apply {
                background = Theme.panel(context, Color.TRANSPARENT, Theme.EDGE)
                setTextColor(DIM)
            })
        }
        fillQuick()
        footer.addView(quick)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        row.addView(field, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        // What else the speech recognizer heard: a tap puts that in instead.
        val alts = Flow(this, dp(6)).apply { setPadding(0, dp(4), 0, 0); visibility = View.GONE }
        footer.addView(alts)
        val mic = chip("🎤") {}
        mic.setOnClickListener { if (listening != null) stopListening() else listen(field, mic, alts) }
        // A long press picks the language it listens for (English with an accent: try another English).
        mic.setOnLongClickListener {
            val now = speechChoice()
            val next = SPEECH_CHOICES[(SPEECH_CHOICES.indexOfFirst { it.first == now } + 1) % SPEECH_CHOICES.size]
            getPreferences(MODE_PRIVATE).edit().putString("speechLang", next.first).apply()
            say("Speech to text: ${next.second} (hold 🎤 again for another)")
            true
        }
        row.addView(mic, LinearLayout.LayoutParams(dp(48), -2).apply { marginEnd = dp(6) })
        row.addView(chip("Send") { send() }.apply { primary() })
        row.addView(chip("✕") { closeCompose() }, LinearLayout.LayoutParams(dp(44), -2).apply { marginStart = dp(4) })
        footer.addView(row)
        // The keyboard needs a window that can take focus; the app's normally can't (so the game keeps the controller).
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        field.requestFocus()
        field.post {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /**
     * A box on this screen for what goes into the game's own fields (the password at the login
     * screen, a new character's name): typed and fixed here with the keyboard on this screen,
     * then typed into the game key by key. Its buttons delete in the game's field too.
     */
    private fun typeInGame() {
        composing = "Type" to ""
        footer.removeAllViews()
        val field = KeyboardField(this).apply {
            hint = "Type here, then tap Type it"
            setHintTextColor(DIM)
            setTextColor(TEXT)
            textSize = 15f
            typeface = Theme.body(context)
            isSingleLine = true
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE or
                android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            background = Theme.panel(context, gold = true)
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        val target = gameDisplay()
        fun pressKeys(vararg what: String) {
            lastKeyAt = SystemClock.uptimeMillis()
            keys.execute { for (k in what) KeySender.send(this, target, k) }
        }
        /** What is in the box into the game, then Enter when [enter]; the box is emptied. */
        fun typeIt(enter: Boolean) {
            val text = field.text.toString()
            field.setText("")
            lastKeyAt = SystemClock.uptimeMillis()
            keys.execute {
                if (text.isNotEmpty()) KeySender.type(this, target, text)
                if (enter) KeySender.send(this, target, "ENTER")
            }
            say(if (enter) "Typed in the game, with Enter" else "Typed in the game")
        }
        field.setOnEditorActionListener { _, action, _ ->
            if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { typeIt(true); true } else false
        }
        var shown = false
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, 0) }
        row.addView(field, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
        // Show or hide the letters (hidden first: it is usually a password).
        row.addView(chip("👁") {
            shown = !shown
            val at = field.selectionEnd
            field.inputType = android.text.InputType.TYPE_CLASS_TEXT or
                (if (shown) android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
            field.setSelection(at.coerceIn(0, field.text.length))
        }, LinearLayout.LayoutParams(dp(48), -2).apply { marginEnd = dp(6) })
        row.addView(chip("✕") { closeCompose() }, LinearLayout.LayoutParams(dp(44), -2))
        footer.addView(row)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(6), 0, 0) }
        fun button(label: String, accent: Boolean = false, onTap: () -> Unit) =
            buttons.addView(chip(label, onTap).apply { if (accent) primary() },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        button("Type it + Enter", accent = true) { typeIt(true) }
        button("Type it") { typeIt(false) }
        // In the game's field: one letter back, or all of it (more Backspaces than any password has).
        button("⌫ in game") { pressKeys("BACKSPACE") }
        button("Clear game field") { pressKeys(*Array(40) { "BACKSPACE" }); say("Cleared the game's field") }
        footer.addView(buttons)
        val more = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        more.addView(chip("Tab (next field)") { pressKeys("TAB") }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        more.addView(chip("Enter") { pressKeys("ENTER") }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        footer.addView(more)
        // The keyboard needs a window that can take focus; the app's normally can't (so the game keeps the controller).
        window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        field.requestFocus()
        field.post {
            getSystemService(android.view.inputmethod.InputMethodManager::class.java)
                .showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
        }
    }

    /** Your quick replies, in the order you saved them; a few to start with. */
    private fun quickReplies(): List<String> =
        getPreferences(MODE_PRIVATE).getString("quickReplies", null)?.split('\n')?.filter { it.isNotBlank() }
            ?: listOf("omw", "1 min", "ty", "brb", "ready")

    private fun saveQuickReplies(list: List<String>) {
        getPreferences(MODE_PRIVATE).edit().putString("quickReplies", list.joinToString("\n")).apply()
    }

    /** The addon sends with the colour bar (there only while sending), not the dark square. */
    @Volatile private var barMode = false

    /** Speech to text for the message being written (null: not listening). */
    @Volatile private var listening: android.speech.SpeechRecognizer? = null
    /** Until then a game that stands still is not taken for gone (see the reader loop). */
    @Volatile private var quietUntil = 0L
    private var micFor: Pair<android.widget.EditText, TextView>? = null
    private var micAlts: Flow? = null

    /**
     * Listens once through the Thor's microphone and puts what you said into [field],
     * after what is already there. Nothing is sent: you read it, fix it if need be, and
     * tap Send. The microphone is only on until you stop talking (or tap 🎤 again).
     */
    private fun listen(field: android.widget.EditText, mic: TextView, alts: Flow) {
        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            micFor = field to mic
            micAlts = alts
            askingMic = true  // its window takes the focus: the box stays open meanwhile
            requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), MIC_PERMISSION)
            return
        }
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(this)) {
            say("No speech recognition on this device: install Speech Services by Google from the Play Store")
            return
        }
        val before = field.text.toString().trimEnd()
        fun show(words: String) {
            val text = listOf(before, words.trim()).filter { it.isNotEmpty() }.joinToString(" ")
            field.setText(text.take(255))
            field.setSelection(field.text.length)
        }
        alts.removeAllViews(); alts.visibility = View.GONE
        val speechIntent = android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(android.speech.RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            // A few guesses, not just the best one: with an accent the right one is often second.
            putExtra(android.speech.RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            // The game's words it would not know: names, the zone, quests (Android 13+, where the speech app uses them).
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                putStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_BIASING_STRINGS, ArrayList(gameWords()))
            }
        }
        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
        listening = recognizer
        micFor = field to mic
        micAlts = alts
        mic.background = Theme.framed(this, Theme.BAD, Color.rgb(170, 40, 40), ACCENT, radiusDp = Theme.BUTTON_RADIUS_DP)
        say("Listening… speak your message")
        fun guesses(b: android.os.Bundle?) =
            b?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
        fun words(b: android.os.Bundle?) = guesses(b).firstOrNull().orEmpty()
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onPartialResults(b: android.os.Bundle?) { words(b).takeIf { it.isNotBlank() }?.let(::show) }
            override fun onResults(b: android.os.Bundle?) {
                busyRetried = false
                val all = guesses(b)
                val w = all.firstOrNull().orEmpty()
                if (w.isNotBlank()) { show(w); say("Check the text, then tap Send") } else say("Didn't catch that: tap 🎤 to try again")
                // The other guesses, to tap instead.
                val others = all.drop(1).map { it.trim() }.distinct().filter { !it.equals(w.trim(), true) }.take(3)
                alts.removeAllViews()
                for (o in others) alts.addView(chip(o) { show(o); alts.removeAllViews(); alts.visibility = View.GONE }.apply {
                    background = Theme.panel(context, Color.TRANSPARENT, Theme.EDGE)
                })
                alts.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
                stopListening()
            }
            override fun onError(error: Int) {
                if (listening !== recognizer) return
                stopListening()
                // Busy: the last listener was still closing. Once more, a moment later.
                if (error == android.speech.SpeechRecognizer.ERROR_RECOGNIZER_BUSY && !busyRetried) {
                    busyRetried = true
                    footer.postDelayed({ if (composing != null) listen(field, mic, alts) }, 700)
                    return
                }
                busyRetried = false
                say(when (error) {
                    android.speech.SpeechRecognizer.ERROR_NO_MATCH, android.speech.SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Didn't catch that: tap 🎤 to try again"
                    android.speech.SpeechRecognizer.ERROR_NETWORK, android.speech.SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Speech recognition needs the internet here"
                    android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The app may not use the microphone (Android settings)"
                    12, 13 -> "Speech recognition has no language to use (error $error)"
                    else -> "Speech recognition stopped (error $error)"
                })
            }
            override fun onReadyForSpeech(p: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rms: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() { mic.background = Theme.button(this@CompanionActivity) }
            override fun onEvent(type: Int, p: android.os.Bundle?) {}
        })
        fun start(language: String?) {
            if (listening !== recognizer) return
            language?.let { speechIntent.putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE, it) }
            // Which language it listens for, so you can tell when it picked the wrong one.
            say("Listening (${language ?: "the speech app's own language"})… speak your message")
            runCatching { recognizer.startListening(speechIntent) }.onFailure { stopListening(); say("Speech recognition didn't start: ${it.message}") }
        }
        // Android 13+: ask once which languages it has (with a recognizer of its own: one
        // that is still answering that is "busy"), and pick the system's or the nearest one.
        val chosen = speechChoice().takeIf { it != "auto" }
        if (chosen != null) start(chosen)
        else if (android.os.Build.VERSION.SDK_INT >= 33 && !speechChecked) {
            val checker = android.speech.SpeechRecognizer.createSpeechRecognizer(this)
            var answered = false
            fun go(language: String?) {
                if (answered) return
                answered = true
                speechChecked = true
                speechLang = language
                footer.post { runCatching { checker.destroy() } }
                footer.postDelayed({ start(language) }, 300)
            }
            val asked = runCatching {
                checker.checkRecognitionSupport(speechIntent, mainExecutor, object : android.speech.RecognitionSupportCallback {
                    override fun onSupportResult(support: android.speech.RecognitionSupport) {
                        val online = if (android.os.Build.VERSION.SDK_INT >= 34) support.onlineLanguages else emptyList()
                        go(runCatching { speechLanguage(support.installedOnDeviceLanguages + online) }.getOrNull())
                    }
                    override fun onError(error: Int) { go(null) }
                })
            }.isSuccess
            if (!asked) go(null)
            // No answer at all: listen without choosing.
            footer.postDelayed({ go(null) }, 3000)
        } else start(speechLang)
    }

    /** The speech language you picked by holding 🎤 ("auto": the Thor's own, see [speechLanguage]). */
    private fun speechChoice(): String = getPreferences(MODE_PRIVATE).getString("speechLang", null) ?: "auto"

    /** Words of the game the speech recognizer is told to expect: names, the zone, quest titles. */
    private fun gameWords(): List<String> {
        val words = LinkedHashSet<String>()
        state?.name?.takeIf { it != "?" }?.let { words += it }
        zoneMap?.let { words += it.zone; if (it.parent.isNotBlank()) words += it.parent }
        for (l in chat.lines.takeLast(100)) if (l.name.isNotBlank()) words += l.name
        questFirst?.quests?.forEach { words += it.title }
        return words.filter { it.isNotBlank() }.take(100)
    }

    /** The language picked for speech (null: the speech app's own), asked once per app start. */
    private var speechChecked = false
    private var speechLang: String? = null
    private var busyRetried = false

    /** The system language if the speech app has it, else the same language from another country, else English. */
    private fun speechLanguage(have: List<String>): String? {
        if (have.isEmpty()) return null
        val system = Locale.getDefault()
        fun same(tag: String) = Locale.forLanguageTag(tag)
        return have.firstOrNull { same(it) == system }
            ?: have.firstOrNull { same(it).language == system.language }
            ?: have.firstOrNull { it.equals("en-US", true) }
            ?: have.firstOrNull { same(it).language == "en" }
            ?: have.first()
    }

    private fun stopListening() {
        quietUntil = SystemClock.uptimeMillis() + 15_000
        // Destroyed after the callback that stopped it has returned, not inside it.
        listening?.let { r -> runCatching { r.cancel() }; footer.post { runCatching { r.destroy() } } }
        listening = null
        micFor?.second?.background = Theme.button(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode != MIC_PERMISSION) return
        askingMic = false
        val (field, mic) = micFor ?: return
        if (results.firstOrNull() == android.content.pm.PackageManager.PERMISSION_GRANTED && composing != null) micAlts?.let { listen(field, mic, it) }
        else say("Without the microphone, speech to text can't work")
    }

    /** While Android asks for the microphone (its window has the focus then). */
    private var askingMic = false

    private fun closeCompose(giveBack: Boolean = true) {
        stopListening()
        micFor = null
        if (composing == null) return
        composing = null
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            .hideSoftInputFromWindow(footer.windowToken, 0)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
        if (giveBack) giveFocusToGame()
        if (panel == Panel.CHAT) render() else footer.removeAllViews()
    }

    /**
     * The addon's last word on the chat box: how often it got the cursor, whether it is
     * open, your group's command, and how long its text is (null from addons before 0.18.4).
     */
    data class ChatBox(val n: Int, val open: Boolean, val group: String, val length: Int?)

    @Volatile private var chatBox: ChatBox? = null

    /** Pastes in a row that didn't reach the chat box: from two on, lines are typed straight away. */
    @Volatile private var pasteFailures = 0

    private fun sendChat(label: String, command: String, text: String) {
        val target = gameDisplay()
        val before = chatBox?.n
        lastKeyAt = SystemClock.uptimeMillis()
        say("$label: sending…")
        keys.execute {
            val t0 = SystemClock.uptimeMillis()
            val err = KeySender.send(this, target, ActionKeys.OPEN_CHAT)
            if (err != null && !err.startsWith("(focus")) return@execute runOnUiThread { say("$label not sent: $err") }
            // Wait for the addon to see the box open; never type into the game without it.
            val until = SystemClock.uptimeMillis() + 3000
            while (SystemClock.uptimeMillis() < until && chatBox?.n == before) {
                lastKeyAt = SystemClock.uptimeMillis()
                SystemClock.sleep(50)
            }
            val box = chatBox
            android.util.Log.i("ThorCompanion", "chat: box ${if (box == null || box.n == before) "not seen" else if (box.open) "open" else "closed"} after ${SystemClock.uptimeMillis() - t0} ms")
            if (box == null || box.n == before || !box.open) {
                return@execute runOnUiThread { say("$label not sent: the game's chat box didn't open (a game menu open, or the addon older than v41?)") }
            }
            // Your own "/1 ...", "/2 ..." and so on goes in as you wrote it.
            val prefix = when {
                text.startsWith("/") -> ""
                command == GROUP -> box.group.ifEmpty { "/p " }
                else -> command
            }
            // The whole line goes in with one paste (CTRL-V): the app's own X server holds it
            // as the X clipboard, where Wine takes the game's paste from (GamePaste), so
            // Android's clipboard and its "copied" pop-up stay out of it. The box opens empty,
            // so text the addon then reports came from the paste. If the game doesn't take
            // it, the line is typed key by key.
            val line = prefix + text
            val tried = pasteFailures < 2 && box.length == 0
            val pasted = tried && pasteLine(target, line, box)
            if (tried) pasteFailures = if (pasted) 0 else pasteFailures + 1
            if (tried && !pasted) {
                // Whatever went in partly: cleared before typing (the box was empty).
                clearChatBox(target, box)
            }
            if (!pasted) {
                val typed = KeySender.type(this, target, line)
                if (typed != null && !typed.startsWith("(focus")) return@execute runOnUiThread { say("$label not sent: $typed") }
            }
            // Enter, and make sure it took: the addon says when the box closes. Still open
            // with the text after a while: that Enter got lost (the game had just come back
            // to the front), so once more. Open and empty: the line went out but the game
            // kept the box open, so it is closed with Escape as soon as the addon says so
            // (only on its word that the box is open; with the box closed, Escape opens the menu).
            var enters = 0
            while (enters < 2) {
                val beforeEnter = chatBox
                val enterAt = SystemClock.uptimeMillis()
                KeySender.send(this, target, "ENTER")
                enters++
                val until = SystemClock.uptimeMillis() + 3000
                var left: ChatBox? = null
                while (SystemClock.uptimeMillis() < until) {
                    val now = chatBox
                    if (now !== beforeEnter) {
                        left = now
                        // Closed, or open and empty (the box held the line until this Enter): no need to wait on.
                        if (now == null || !now.open || (now.n == box.n && now.length == 0)) break
                    }
                    lastKeyAt = SystemClock.uptimeMillis()
                    SystemClock.sleep(50)
                }
                android.util.Log.i("ThorCompanion", "chat: Enter $enters at ${enterAt - t0} ms, ${SystemClock.uptimeMillis() - enterAt} ms later " +
                    (left?.let { "the box ${if (it.open) "open" else "closed"} (${it.length} bytes, ${if (it.n == box.n) "same" else "another"} opening)" } ?: "no word from the addon"))
                // Only on the addon's word after this Enter.
                val last = left ?: break
                if (!last.open || last.n != box.n) break
                if (last.length == 0) {
                    android.util.Log.i("ThorCompanion", "chat box open and empty after Enter: Escape")
                    KeySender.send(this, target, "ESCAPE")
                    break
                }
                android.util.Log.i("ThorCompanion", "chat box still holds the text after Enter: once more")
            }
            runOnUiThread { say(if (tried && !pasted) "$label: sent (typed, the game didn't take the paste)" else "$label: sent") }
        }
    }

    /**
     * Pastes [line] into the open chat [box]; true once the addon shows text there that
     * the game fetched from the X clipboard. Logs only what happened, never the text.
     */
    private fun pasteLine(target: Int, line: String, box: ChatBox): Boolean {
        val offer = GamePaste.offer(line) ?: return false
        fun await(ms: Long, done: () -> Boolean) = GamePaste.await(ms) { lastKeyAt = SystemClock.uptimeMillis(); done() }
        // Wine may still hold the last line (or the game's own copy): until it has looked
        // again, a paste could bring that back.
        if (offer.mustWait && !await(PASTE_LOOK_MS) { GamePaste.seen(offer) }) {
            android.util.Log.i("ThorCompanion", "paste: Wine didn't look at the clipboard")
            return false
        }
        for (attempt in 1..2) {
            val seenBefore = GamePaste.seen(offer)
            KeySender.send(this, target, "CTRL-V")
            if (await(1000) { GamePaste.taken(offer) }) {
                val shown = await(2500) { chatBox.let { it != null && it.n == box.n && (it.length ?: 0) > 0 } }
                android.util.Log.i("ThorCompanion", "paste: the game took it (try $attempt), chat box " +
                    (if (shown) "shows ${chatBox?.length} bytes" else "stays empty"))
                return shown
            }
            // Not taken: Wine hadn't seen this line yet, so the game pasted nothing or an old one.
            android.util.Log.i("ThorCompanion", "paste: the game didn't take it (try $attempt, Wine ${if (seenBefore) "had" else "hadn't"} seen it)")
            if (seenBefore || attempt == 2) return false
            if (!await(PASTE_LOOK_MS) { GamePaste.seen(offer) }) {
                android.util.Log.i("ThorCompanion", "paste: Wine didn't look at the clipboard")
                return false
            }
            clearChatBox(target, box)
        }
        return false
    }

    /** Empties the chat [box] the app opened (it was empty then), with Backspace. */
    private fun clearChatBox(target: Int, box: ChatBox) {
        // The addon reports the box's length when it changes: a moment for that to come.
        GamePaste.await(600) { chatBox.let { it != null && it.n == box.n && (it.length ?: 0) > 0 } }
        val now = chatBox
        val partly = if (now != null && now.n == box.n) now.length ?: 0 else 0
        if (partly > 0) repeat(partly) { KeySender.send(this, target, "BACKSPACE"); SystemClock.sleep(5) }
    }

    /** The Thor's recent log (this app, the speech service, the game's input) and the game's picture, into Download. */
    private fun saveLog() {
        keys.execute {
            val out = "/sdcard/Download/ForeverCompanion-log.txt"
            val ok = RootShell.exec("logcat -d -t 3000 -v time > $out 2>&1; echo done")?.trim() == "done"
            // And the game's picture, as the app sees it.
            val shot = runCatching {
                val px = screen.capture(rows = 4000) ?: return@runCatching false
                val bitmap = android.graphics.Bitmap.createBitmap(px.width, px.height, android.graphics.Bitmap.Config.ARGB_8888)
                val row = IntArray(px.width)
                for (y in 0 until px.height) {
                    for (x in 0 until px.width) row[x] = px.rgb(x, y) or (0xFF shl 24)
                    bitmap.setPixels(row, 0, px.width, 0, y, px.width, 1)
                }
                java.io.File("/sdcard/Download/ForeverCompanion-screen.png").outputStream().use {
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                }
                bitmap.recycle()
                true
            }.getOrDefault(false)
            runOnUiThread {
                say(if (ok && shot) "Log and screenshot saved in Download (ForeverCompanion-log.txt, ForeverCompanion-screen.png)"
                    else if (ok) "Log saved: Download/ForeverCompanion-log.txt" else "Log not saved")
            }
        }
    }

    /**
     * How well each corner square reads (for trying /thor dense on, or another cell size):
     * squares read whole and damaged, their size in cells, and how far apart the closest
     * two colour levels came out (in screen steps of 0 to 255: the smaller, the closer to misreads).
     */
    private class CornerReads {
        private val ok = IntArray(4); private val bad = IntArray(4)
        private val spread = DoubleArray(4) { Double.MAX_VALUE }
        private val dense = BooleanArray(4); private val cell = DoubleArray(4)
        var since = SystemClock.uptimeMillis()
        fun add(i: Int, f: StripFrame) {
            if (f.crcOk) ok[i]++ else bad[i]++
            spread[i] = minOf(spread[i], f.shadeMinDistance)
            dense[i] = f.version != StripDecoder.cornerKind(f.version)
            cell[i] = f.cellPx
        }
        fun report(): String? {
            if (ok.sum() + bad.sum() == 0) return null
            val names = listOf("position", "data", "chat", "status")
            val s = (SystemClock.uptimeMillis() - since) / 1000
            val lines = names.indices.filter { ok[it] + bad[it] > 0 }.joinToString("; ") { i ->
                "${names[i]} ${ok[i]} read, ${bad[i]} damaged, ${if (dense[i]) "8" else "4"} levels, " +
                    "closest levels %.0f apart, cell %.1f px".format(spread[i], cell[i])
            }
            ok.fill(0); bad.fill(0); spread.fill(Double.MAX_VALUE); since = SystemClock.uptimeMillis()
            return "Corners in the last $s s: $lines."
        }
    }
    private val cornerReads = CornerReads()

    /** Battery check: how often and how long the app read the game's screen in the last minute. */
    private fun captureStats(): String {
        val (count, total) = synchronized(captures) { captures.size to captures.sumOf { it[1] } }
        val corners = synchronized(cornerReads) { cornerReads.report() }?.let { "\n$it" } ?: ""
        if (count == 0) return "Battery check: the game's screen hasn't been read in the last minute.$corners"
        return "Battery check: read the game's screen $count times in the last minute, " +
            "${total / count} ms each (busy ${total * 100 / 60_000}% of the time).$corners"
    }

    /** Sends one key to the game's screen; the callbacks run on the UI thread. */
    private fun pressKey(key: String, onDone: () -> Unit = {}, onError: (String) -> Unit) {
        val target = gameDisplay()
        lastKeyAt = SystemClock.uptimeMillis()
        keys.execute {
            val err = KeySender.send(this, target, key)
            runOnUiThread { if (err == null) onDone() else onError(err) }
        }
    }

    /** The other screen: the one this app is not on. */
    private fun gameDisplay(): Int {
        val here = display?.displayId ?: 0
        val dm = getSystemService(android.hardware.display.DisplayManager::class.java)
        return dm.displays.map { it.displayId }.firstOrNull { it != here } ?: 0
    }

    /** Redraws the bags now and then while something there is on cooldown, so the time left counts down. */
    private val cooldownTick = Runnable { if (panel == Panel.BAGS) render() }

    private fun renderBags(s: GameState) {
        content.removeCallbacks(cooldownTick)
        if (s.items.any { cooldownLeft(it.itemId) > 0 }) content.postDelayed(cooldownTick, 15_000)
        val head = card().apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(line("", GOLD, 18f, bold = true).apply { text = s.copper?.let { coins(it) } ?: "Gold unknown"; setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        val free = s.freeSlots; val total = s.totalSlots
        val slots = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        slots.addView(line(if (free != null && total != null) "$free of $total free" else "Slots unknown", TEXT, 14f).apply { setPadding(0, 0, 0, dp(4)) })
        if (free != null && total != null && total > 0) {
            val bar = Bar(this).apply {
                value = (total - free).toFloat() / total
                colour = if (free <= 3) Theme.BAD else if (free <= 8) Theme.WARN else Theme.GOOD
            }
            slots.addView(bar, LinearLayout.LayoutParams(dp(110), dp(6)))
        }
        head.addView(slots)
        content.addView(head, cardParams())

        // Grey items are what you sell; worth knowing when the bags fill up.
        val junk = s.items.filter { names[it.itemId]?.quality == 0 }
        val junkValue = junk.sumOf { (names[it.itemId]?.sellPrice ?: 0L) * it.count }
        if (junk.isNotEmpty() || (free != null && free <= 3)) {
            val note = android.text.SpannableStringBuilder()
            if (free != null && free <= 3) note.append(if (free == 0) "Your bags are full.  " else "Your bags are almost full.  ")
            if (junk.isNotEmpty()) note.append("${junk.size} grey item${if (junk.size == 1) "" else "s"}, worth ").append(coins(junkValue)).append(" at a vendor")
            content.addView(line("", if (free != null && free <= 3) Theme.WARN else DIM, 13f).apply { text = note; setPadding(dp(4), 0, 0, dp(2)) })
        }

        // Sort: as in the bags, or by type, quality or what they sell for.
        val sorts = listOf("Bag order", "Type", "Quality", "Value")
        val sortRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(4), 0, 0) }
        for ((i, label) in sorts.withIndex()) {
            sortRow.addView(chip(label) { bagSort = i; getPreferences(MODE_PRIVATE).edit().putInt("bagSort", i).apply(); render() }.apply {
                if (i == bagSort) primary()
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(4) })
        }
        content.addView(sortRow)
        val items = when (bagSort) {
            1 -> s.items.sortedWith(compareBy({ names[it.itemId]?.type ?: "~" }, { names[it.itemId]?.name ?: "~" }))
            2 -> s.items.sortedWith(compareByDescending<BagItem> { names[it.itemId]?.quality ?: -1 }.thenBy { names[it.itemId]?.name ?: "~" })
            3 -> s.items.sortedByDescending { (names[it.itemId]?.sellPrice ?: 0L) * it.count }
            else -> s.items
        }

        val ids = s.items.map { it.itemId }
        val hint = iconHint(ids)
        content.addView(section((if (s.items.any { it.key != null } && bagsFresh) "Tap to use, hold for details" else "Hold an item for details") + hint))
        val grid = GridLayout(this).apply { columnCount = 5; useDefaultMargins = false }
        for (item in items) {
            val known = names[item.itemId]
            val quality = known?.let { qualityColour(it.quality) }
            val tile = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(3), dp(6), dp(3), dp(4))
                background = Theme.panel(context, edge = known?.takeIf { it.quality >= 2 }?.let { Theme.withAlpha(qualityColour(it.quality), 170) } ?: STROKE)
            }
            val pic = FrameLayout(this)
            pic.addView(icon(item.itemId, quality, 40), FrameLayout.LayoutParams(dp(40), dp(40)))
            if (item.count > 1) pic.addView(TextView(this).apply {
                text = item.count.toString()
                textSize = 11f
                typeface = Theme.body(context, bold = true)
                setTextColor(Color.WHITE)
                setShadowLayer(3f, 0f, 0f, Color.BLACK)
            }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(1) })
            val wait = cooldownLeft(item.itemId)
            if (wait > 0) {
                // On cooldown: the icon darkened with the time left on it, like the game's own.
                pic.addView(TextView(this).apply {
                    text = Cooldowns.left(wait).replace(" min", "m").replace(" h", "h").replace(" s", "s").substringBefore(' ')
                    textSize = 11f
                    typeface = Theme.body(context, bold = true)
                    gravity = Gravity.CENTER
                    setTextColor(Color.WHITE)
                    background = Theme.box(context, Color.argb(170, 0, 0, 0), 6)
                }, FrameLayout.LayoutParams(-1, -1))
            }
            tile.addView(pic, LinearLayout.LayoutParams(dp(40), dp(40)))
            tile.addView(TextView(this).apply {
                text = known?.name ?: "#${item.itemId}"
                gravity = Gravity.CENTER
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                textSize = 10.5f
                typeface = Theme.body(context)
                setTextColor(quality ?: DIM)
                setPadding(0, dp(3), 0, 0)
            })
            // One tap, one key: the addon bound this key to "use the item in this slot".
            val key = item.key?.let { ActionKeys.forIndex(it) }
            val label = known?.name ?: "the item"
            tile.setOnLongClickListener { showDetails(item.itemId, item.count, usable = key != null); true }
            tile.setOnClickListener { v ->
                if (!bagsFresh) {
                    say("These are your bags from before; tap Load from the game to update them before using items.")
                    return@setOnClickListener
                }
                if (key == null) {
                    say("$label has no tap key yet. Is the new addon loaded? /thor taps in game shows the keys.")
                    return@setOnClickListener
                }
                val wait = cooldownLeft(item.itemId)
                if (wait > 0) {
                    say("$label is on cooldown: ready in ${Cooldowns.left(wait)}")
                    return@setOnClickListener
                }
                (v.background as GradientDrawable).setColor(PRESSED)
                v.postDelayed({ (v.background as GradientDrawable).setColor(SURFACE) }, 250)
                val tap = item.itemId to label
                tapped = tap
                pressKey(key, onDone = {
                    say("Using $label…")
                    // The addon answers within a second or so; silence means the key didn't reach the item.
                    content.postDelayed({
                        if (tapped === tap) {
                            tapped = null
                            say("The game didn't react to $label's key ($key). Is the addon v38 loaded, and tap-to-use on (/thor taps)?")
                        }
                    }, 3000)
                }) { err ->
                    say("Couldn't use $label: $err")
                }
            }
            grid.addView(tile, GridLayout.LayoutParams().apply {
                width = 0
                height = dp(92)
                columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1f)
                // GridLayout lines tiles up by text baseline by default, which pushed
                // tiles with one line of name above their neighbours.
                rowSpec = GridLayout.spec(GridLayout.UNDEFINED, GridLayout.FILL)
                setMargins(dp(3), dp(3), dp(3), dp(3))
            })
        }
        content.addView(grid)
        when {
            s.freeSlots == null -> content.addView(empty("The bags haven't arrived yet", "Tap Load from the game above."))
            !bagsFresh -> content.addView(line("These are your bags from before; they update when the game sends them.", DIM).apply { setPadding(dp(4), dp(6), 0, 0) })
            s.items.isEmpty() -> content.addView(empty("Your bags are empty", ""))
        }
    }

    /** "  ·  N without icons" and a tap that asks the game for them, or "" when every icon is here. */
    private fun iconHint(ids: List<Int>): String {
        val missing = ids.distinct().count { !icons.has(it) }
        return if (missing == 0) "" else "  ·  $missing without icon"
    }

    /** An item's icon, or a quiet square with a question mark until the app has it. */
    private fun icon(itemId: Int?, quality: Int?, sizeDp: Int): View {
        val bitmap = itemId?.let { icons[it] }
        if (bitmap != null) return ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.FIT_XY
            clipToOutline = true
            background = Theme.panel(context, RAISED, Theme.INNER)
        }
        return TextView(this).apply {
            text = if (itemId == null) "" else "?"
            gravity = Gravity.CENTER
            textSize = sizeDp / 2.6f
            typeface = Theme.heading(context)
            setTextColor(quality?.let { Theme.withAlpha(it, 160) } ?: DIM)
            background = Theme.panel(context, RAISED, Theme.INNER)
        }
    }

    /** A recipe's icon: what it makes, or a gold ✦ for an enchantment (it makes no item). */
    private fun recipeIcon(r: Recipe, quality: Int?, sizeDp: Int): View =
        if (r.enchantment) icon(null, null, sizeDp).apply {
            this as TextView
            text = "✦"; setTextColor(Theme.GOLD_TEXT); textSize = sizeDp / 2.2f
        } else icon(r.itemId.takeIf { it > 0 }, quality?.let { qualityColour(it) }, sizeDp)

    /** One key: the addon shows the icons of your items for a few seconds and the app takes them. */
    private fun askForIcons() {
        say("Asked the game for the item icons…")
        pressKey(ActionKeys.ICONS) { err -> say("Item icons not taken: $err") }
    }

    /** A title over a group, with a "Get icons" tap when some icons are missing. */
    private fun section(title: String): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), dp(12), 0, dp(4)) }
        row.addView(line(title, Theme.SKY, 12.5f).apply { typeface = Theme.heading(context); setPadding(0, 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        if (title.contains("without icon")) row.addView(chip("Get icons") { askForIcons() })
        return row
    }

    /** A panel to group things in. */
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.panel(context)
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }

    private fun cardParams() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) }

    private fun weighted(w: Float = 1f) = LinearLayout.LayoutParams(0, -2, w).apply { setMargins(dp(3), 0, dp(3), 0) }

    /** A small labelled value, like "Item level 24.5". */
    private fun fact(label: String, value: String, colour: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Theme.panel(context)
        setPadding(dp(10), dp(6), dp(10), dp(7))
        addView(line(label, Theme.SKY, 10.5f).apply { typeface = Theme.heading(context); setPadding(0, 0, 0, 0) })
        addView(line(value, colour, 16f, bold = true).apply { setPadding(0, 0, 0, 0); maxLines = 1 })
    }

    /** A button for the few things to tap that are not items, like the start screen's. */
    private fun chip(text: String, onClick: () -> Unit) = TextView(this).apply {
        this.text = text
        textSize = 12.5f
        gravity = Gravity.CENTER
        typeface = Theme.heading(context)
        setTextColor(TEXT)
        background = Theme.button(context)
        setPadding(dp(12), dp(9), dp(12), dp(9))
        setOnClickListener { onClick() }
    }

    /** The main button (and the switch that is on): teal in a gold edge, as Play on the start screen. */
    private fun TextView.primary() = apply {
        background = Theme.button(context, primary = true)
        setTextColor(Color.WHITE)
    }

    /** What a tab shows while it has nothing yet. */
    private fun empty(title: String, detail: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(dp(16), dp(28), dp(16), dp(28))
        addView(line(title, Theme.GOLD_TEXT, 16f).apply { typeface = Theme.heading(context); gravity = Gravity.CENTER })
        if (detail.isNotEmpty()) addView(line(detail, DIM, 13f).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
    }

    /** WoW's item quality colours. */
    private fun qualityColour(q: Int): Int = when (q) {
        0 -> Color.rgb(157, 157, 157)
        2 -> Color.rgb(30, 255, 0)
        3 -> Color.rgb(0, 112, 221)
        4 -> Color.rgb(163, 53, 238)
        5 -> Color.rgb(255, 128, 0)
        6 -> Color.rgb(230, 204, 128)
        7, 8 -> Color.rgb(0, 204, 255)
        else -> TEXT
    }

    private fun money(s: GameState): String =
        if (s.copper == null) "?" else "${s.gold}g ${s.silver}s ${s.copperPart}c"

    /** The money with each coin in its colour, like the game's bag frame. */
    private fun coins(copper: Long): CharSequence {
        val gold = copper / 10000; val silver = copper / 100 % 100
        val text = android.text.SpannableStringBuilder()
        fun add(v: Long?, unit: String, colour: Int) {
            val start = text.length
            text.append(String.format(Locale.US, "%,d", v ?: 0)).append(unit).append("  ")
            text.setSpan(android.text.style.ForegroundColorSpan(colour), start, text.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        if (gold > 0) add(gold, "g", GOLD)
        if (gold > 0 || silver > 0) add(silver, "s", Theme.SILVER)
        add(copper % 100, "c", Theme.COPPER)
        return text.trimEnd()
    }

    private fun line(text: String, colour: Int, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        setTextColor(colour)
        textSize = size
        typeface = Theme.body(context, bold)
        setPadding(0, dp(4), 0, dp(4))
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private companion object {
        const val MIC_PERMISSION = 7
        /** Message kinds kept across restarts. */
        /** After the square last changed, how long the app keeps reading five times a second (the addon assumes 2.5 s). */
        const val BUSY_MS = 3000L
        /** How long a message in the status line stays before "Connected" replaces it. */
        const val NOTICE_MS = 6000L
        /** After this long on the start-up card, it offers to ask the game for everything, or to open anyway. */
        const val SLOW_MS = 15_000L
        /** No new message for this long: the game is gone (it sends its status at least every 10 s). */
        const val STALE_MS = 30_000L
        /** Nothing read in the remembered corner for this long: look at the whole top again. */
        const val LOCK_MS = 25_000L
        /** How often the four corners are read while the addon uses them. */
        const val CORNERS_MS = 150L
        /** The languages holding 🎤 goes through, in turn. */
        val SPEECH_CHOICES = listOf(
            "auto" to "the Thor's language", "en-US" to "English (US)", "en-GB" to "English (UK)",
            "en-AU" to "English (Australia)", "en-IN" to "English (India)", "de-DE" to "German",
        )
        /** Rows above and below the remembered place that are copied too (the decoder looks 16 either way). */
        const val LOCK_ROWS = 24
        /** The start-up card's rows before the status says what there is. */
        val PLACEHOLDER = listOf("Character and gear", "Bags", "Item names", "Map", "Quests", "Spells to learn")
        /** A craft count: as many as the bags allow when it is made. */
        const val ALL = -1
        /** A list entry counts as stopped when the bags got nothing new for this long. */
        const val STALL_MS = 15_000L
        /** Wine looks at the X clipboard every two seconds (when the game pastes too): this long covers one look. */
        const val PASTE_LOOK_MS = 3000L
        /** Professions every character can learn on top of its two main ones; shown in the second row. */
        val SECONDARY_PROFESSIONS = setOf("Cooking", "First Aid", "Fishing", "Riding", "Archaeology")
        val KEPT = setOf("TS1|", "TB1|", "TM1|", "TP1|", "TQ1|", "TL1|", "TV1|", "TF1|", "TK1|")
        /** The paper doll's slots, left column then right, as Character.lua sends them. */
        val GEAR_SLOTS = listOf(1, 2, 3, 15, 5, 4, 19, 9, 10, 6, 7, 8, 11, 12, 13, 14, 16, 17)
    }
}
