package thor.companion.games

import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import thor.companion.Theme

/**
 * The games page in the Forever Companion (More > Games): a Continue card for the last game,
 * then every game as a tile, calm ones (no timer) first. Each game runs only in this app; it
 * never reads or sends anything to WoW. Every game keeps where you stopped, between flights
 * and across restarts.
 *
 * The page draws into the companion's [toolbar] (above) and [content] (the scrolling part);
 * [fill] stretches the content to the screen's bottom while a board is shown.
 */
class Arcade(
    private val context: Context,
    private val toolbar: LinearLayout,
    private val content: LinearLayout,
    private val fill: (Boolean) -> Unit,
    private val chip: (String, () -> Unit) -> TextView,
    private val primary: (TextView) -> Unit,
    private val onBack: () -> Unit,
) {
    class Entry(val id: String, val name: String, val calm: Boolean, val ready: Boolean)

    private val prefs = context.getSharedPreferences("forever_games", Context.MODE_PRIVATE)
    private val d = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    /** The game shown now (null: the list of games). */
    var open: String? = null
        private set
    private var board: GameView? = null
    private var header: TextView? = null
    /** A second tap on New game within 3 s starts over (a game in the middle of it). */
    private var newAskedAt = -10_000L
    /** The won game whose best was counted already. */
    private var counted: Any? = null

    /** Draws the page: the list, or the game that is open. */
    fun render() {
        toolbar.removeAllViews()
        content.removeAllViews()
        val id = open
        if (id == null || ENTRIES.none { it.id == id && it.ready }) { open = null; renderList() } else renderGame(id)
    }

    /** Leaving the page: the clock stops and the game is kept. */
    fun leave() {
        board?.let { it.pause(); keep(it) }
    }

    /** Opens [id] straight away (the screenshot test). */
    internal fun openGame(id: String) { open = id; board = null; render() }

    private fun renderList() {
        fill(false)
        board = null
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(6)) }
        top.addView(chip("◀ Back") { onBack() })
        top.addView(title("Games"), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        top.addView(TextView(context).apply { text = "Your progress is kept"; setTextColor(Theme.DIM); textSize = 12.5f; typeface = Theme.body(context) })
        toolbar.addView(top)

        val last = prefs.getString("last", null)?.let { id -> ENTRIES.firstOrNull { it.id == id && it.ready } }
        val summary = last?.let { summary(it.id) }
        if (last != null && summary != null) {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = Theme.panel(context, gold = true)
                setPadding(dp(12), dp(8), dp(10), dp(8))
                setOnClickListener { start(last.id) }
            }
            card.addView(GameIcon(context, last.id), LinearLayout.LayoutParams(dp(34), dp(34)))
            val words = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(8), 0) }
            words.addView(TextView(context).apply { text = "Continue ${last.name}"; setTextColor(Theme.GOLD_TEXT); textSize = 14f; typeface = Theme.heading(context) })
            words.addView(TextView(context).apply { text = summary; setTextColor(Theme.DIM); textSize = 12f; typeface = Theme.body(context); maxLines = 1 })
            card.addView(words, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(chip("▶ Continue") { start(last.id) }.also(primary))
            content.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(4) })
        }
        section("Calm · no timer, stop any time")
        grid(ENTRIES.filter { it.calm })
        section("Action · quick rounds")
        grid(ENTRIES.filter { !it.calm })
    }

    private fun title(words: String) = TextView(context).apply {
        text = words; setTextColor(Theme.GOLD_TEXT); textSize = 18f; typeface = Theme.heading(context); maxLines = 1
    }

    private fun section(words: String) {
        content.addView(TextView(context).apply {
            text = words; setTextColor(Theme.ACCENT); textSize = 11.5f; typeface = Theme.heading(context)
            setPadding(dp(2), dp(8), 0, dp(4))
        })
    }

    private fun grid(entries: List<Entry>) {
        for (row in entries.chunked(6)) {
            val line = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            for (i in 0 until 6) {
                val e = row.getOrNull(i)
                val params = LinearLayout.LayoutParams(0, dp(84), 1f).apply { if (i > 0) marginStart = dp(5) }
                if (e == null) { line.addView(View(context), params); continue }
                line.addView(tile(e), params)
            }
            content.addView(line, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(5) })
        }
    }

    private fun tile(e: Entry) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = Theme.framed(context, Color.rgb(19, 47, 74), Color.rgb(19, 47, 74), Theme.STROKE, null, radiusDp = 10f)
        setPadding(dp(2), dp(4), dp(2), dp(4))
        addView(GameIcon(context, e.id), LinearLayout.LayoutParams(dp(30), dp(30)))
        addView(TextView(context).apply {
            text = e.name; setTextColor(Theme.GOLD_TEXT); textSize = 10.5f; typeface = Theme.heading(context)
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0)
        })
        addView(TextView(context).apply {
            text = if (e.ready) bestLine(e.id) else "Soon"
            setTextColor(if (e.ready) Theme.ACCENT else Theme.DIM); textSize = 10f; typeface = Theme.body(context)
            gravity = Gravity.CENTER; maxLines = 1
        })
        if (!e.ready) alpha = 0.5f
        setOnClickListener {
            if (e.ready) start(e.id)
            else (getChildAt(2) as TextView).text = "In a later build"
        }
    }

    private fun start(id: String) {
        open = id
        board = null
        prefs.edit().putString("last", id).apply()
        render()
    }

    // ---- One game ----

    private fun renderGame(id: String) {
        fill(true)
        val entry = ENTRIES.first { it.id == id }
        val view = board ?: load(id).also { board = it }
        view.onChange = { changed(id, view) }
        val top = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(6)) }
        top.addView(chip("◀ Games") { leave(); open = null; render() })
        // Games with several buttons of their own leave out the title (the screen is only so wide).
        if (view !is MinesView && view !is MahjongView && view !is NonogramView) top.addView(title(entry.name), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) })
        val line = TextView(context).apply {
            setTextColor(Theme.TEXT); textSize = 13.5f; typeface = Theme.body(context); gravity = Gravity.END
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(8), 0, dp(8), 0)
        }
        header = line
        top.addView(line, LinearLayout.LayoutParams(0, -2, 1f))
        when (view) {
            is SolitaireView -> top.addView(chip("Undo") { view.undo() }, gapParams())
            is MinesView -> {
                val flag = chip("Flag mode") {}
                fun lit() { flag.background = Theme.button(context, primary = view.flagMode); flag.setTextColor(if (view.flagMode) Color.WHITE else Theme.TEXT) }
                flag.setOnClickListener { view.flagMode = !view.flagMode; lit() }
                lit()
                top.addView(flag, gapParams())
                val size = chip(MineField.SIZE_NAMES[minesSize()]) {}
                size.setOnClickListener {
                    val next = (minesSize() + 1) % MineField.SIZES.size
                    prefs.edit().putInt("mines_size", next).apply()
                    size.text = MineField.SIZE_NAMES[next]
                    if (!view.field.laid || view.field.over) newGame(id, view)
                    else line.text = "${MineField.SIZE_NAMES[next]} from the next New game"
                }
                top.addView(size, gapParams())
            }
            is SudokuView -> top.addView(sizeChip("sudoku_level", Sudoku.LEVELS, line, { !inProgress(view) }) { newGame(id, view) }, gapParams())
            is NonogramView -> {
                val mode = chip("■ Fill") {}
                fun lit() {
                    mode.text = if (view.markMode) "✕ Mark" else "■ Fill"
                    mode.background = Theme.button(context, primary = true); mode.setTextColor(Color.WHITE)
                }
                mode.setOnClickListener { view.markMode = !view.markMode; lit() }
                lit()
                top.addView(mode, gapParams())
                top.addView(sizeChip("nonogram_size", Nonogram.SIZE_NAMES, line, { !inProgress(view) }) { newGame(id, view) }, gapParams())
            }
            is MahjongView -> {
                top.addView(chip("Hint") { view.hint() }, gapParams())
                top.addView(chip("Shuffle") { view.shuffle() }, gapParams())
                top.addView(chip("Undo") { view.undo() }, gapParams())
            }
            is PipesView -> top.addView(sizeChip("pipes_size", Pipes.SIZE_NAMES, line, { !inProgress(view) }) { newGame(id, view) }, gapParams())
            is SlideView -> top.addView(sizeChip("slide_size", SlidePuzzle.SIZE_NAMES, line, { !inProgress(view) }) { newGame(id, view) }, gapParams())
            is WordSearchView -> top.addView(sizeChip("words_level", WordSearch.LEVELS, line, { !inProgress(view) }) { newGame(id, view) }, gapParams())
            is MemoryView -> top.addView(sizeChip("memory_size", Memory.SIZE_NAMES, line, { !inProgress(view) }) { newGame(id, view) }, gapParams())
            else -> {}
        }
        val again = chip("New game") {}
        again.setOnClickListener {
            val now = SystemClock.uptimeMillis()
            if (!inProgress(view) || now - newAskedAt < 3000) { newAskedAt = -10_000L; newGame(id, view) }
            else { newAskedAt = now; line.text = "Tap New game again to start over" }
        }
        top.addView(again, gapParams())
        toolbar.addView(top)
        (view.parent as? android.view.ViewGroup)?.removeView(view)
        content.addView(view, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(2); bottomMargin = dp(4) })
        line.text = view.line()
    }

    private fun gapParams() = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(5) }

    private fun minesSize() = prefs.getInt("mines_size", 1).coerceIn(0, MineField.SIZES.size - 1)
    private fun choice(key: String, count: Int, default: Int = 1) = prefs.getInt(key, default).coerceIn(0, count - 1)

    /** A chip that steps through [names] (kept under [key]); it starts a new game at once when [now] says so. */
    private fun sizeChip(key: String, names: List<String>, line: TextView, now: () -> Boolean, restart: () -> Unit): TextView {
        val c = chip(names[choice(key, names.size)]) {}
        c.setOnClickListener {
            val next = (choice(key, names.size) + 1) % names.size
            prefs.edit().putInt(key, next).apply()
            c.text = names[next]
            if (now()) restart() else line.text = "${names[next]} from the next New game"
        }
        return c
    }

    private fun inProgress(v: GameView) = when (v) {
        is GemView -> v.board.score > 0
        is TilesView -> !v.game.over && v.game.score > 0
        is MinesView -> v.field.laid && !v.field.over
        is SolitaireView -> !v.game.won && v.game.moves > 0
        is SudokuView -> !v.game.won && v.game.values.indices.any { !v.game.given[it] && (v.game.values[it] != 0 || v.game.notes[it] != 0) }
        is NonogramView -> !v.game.won && v.game.marks.any { it != 0 }
        is MahjongView -> !v.game.won && v.game.left < Mahjong.SPOTS.size
        is BlockDropView -> !v.game.over && v.game.score > 0
        is PipesView -> !v.game.won && v.game.moves > 0
        is SlideView -> !v.game.won && v.game.moves > 0
        is WordSearchView -> !v.game.won && v.game.foundCount > 0
        is MemoryView -> !v.game.won && v.game.turns > 0
        else -> false
    }

    private fun newGame(id: String, v: GameView) {
        when (v) {
            is GemView -> v.board = GemBoard()
            is TilesView -> v.game = Tiles2048()
            is MinesView -> { v.pause(); v.field = MineField.SIZES[minesSize()].let { (w, h, m) -> MineField(w, h, m) } }
            is SolitaireView -> { v.pause(); v.game = Klondike() }
            is SudokuView -> { v.pause(); v.game = Sudoku.new(choice("sudoku_level", 3)) }
            is NonogramView -> { v.pause(); v.game = Nonogram.new(Nonogram.SIZES[choice("nonogram_size", 3)]) }
            is MahjongView -> { v.pause(); v.game = Mahjong.new() }
            is BlockDropView -> v.game = BlockDrop()
            is PipesView -> { v.pause(); v.game = Pipes.new(Pipes.SIZES[choice("pipes_size", 3)]) }
            is SlideView -> { v.pause(); v.game = SlidePuzzle.new(SlidePuzzle.SIZES[choice("slide_size", 3)]) }
            is WordSearchView -> { v.pause(); v.game = WordSearch.new(choice("words_level", 3)) }
            is MemoryView -> { v.pause(); v.game = Memory.SIZES[choice("memory_size", 3)].let { (c, r) -> Memory.new(c, r) } }
        }
        v.resume()
        counted = null
        changed(id, v)
        v.invalidate()
    }

    /** After every move: the header line, the save, and the best score. */
    private fun changed(id: String, v: GameView) {
        header?.text = v.line()
        keep(v)
        val e = prefs.edit()
        when (v) {
            is GemView -> if (v.board.score > prefs.getInt("best_gems", 0)) e.putInt("best_gems", v.board.score)
            is TilesView -> if (v.game.score > prefs.getInt("best_2048", 0)) e.putInt("best_2048", v.game.score)
            is MinesView -> if (v.field.won && counted !== v.field) {
                counted = v.field
                val key = "best_mines_${MineField.SIZES.indexOfFirst { it.first == v.field.width }}"
                val best = prefs.getLong(key, 0L)
                if (best == 0L || v.field.millis < best) e.putLong(key, v.field.millis)
            }
            is SolitaireView -> if (v.game.won && counted !== v.game) {
                counted = v.game
                e.putInt("wins_solitaire", prefs.getInt("wins_solitaire", 0) + 1)
                val best = prefs.getLong("best_solitaire", 0L)
                if (best == 0L || v.game.millis < best) e.putLong("best_solitaire", v.game.millis)
            }
            is SudokuView -> if (v.game.won && counted !== v.game) {
                counted = v.game
                fastest(e, "best_sudoku_${v.game.level}", v.game.millis)
            }
            is NonogramView -> if (v.game.won && counted !== v.game) {
                counted = v.game
                fastest(e, "best_nonogram_${Nonogram.SIZES.indexOf(v.game.size)}", v.game.millis)
            }
            is MahjongView -> if (v.game.won && counted !== v.game) {
                counted = v.game
                e.putInt("wins_mahjong", prefs.getInt("wins_mahjong", 0) + 1)
                fastest(e, "best_mahjong", v.game.millis)
            }
            is BlockDropView -> if (v.game.score > prefs.getInt("best_blocks", 0)) e.putInt("best_blocks", v.game.score)
            is PipesView -> if (v.game.won && counted !== v.game) { counted = v.game; fastest(e, "best_pipes_${Pipes.SIZES.indexOf(v.game.size)}", v.game.millis) }
            is SlideView -> if (v.game.won && counted !== v.game) { counted = v.game; fastest(e, "best_slide_${SlidePuzzle.SIZES.indexOf(v.game.size)}", v.game.millis) }
            is WordSearchView -> if (v.game.won && counted !== v.game) { counted = v.game; fastest(e, "best_words_${v.game.level}", v.game.millis) }
            is MemoryView -> if (v.game.won && counted !== v.game) {
                counted = v.game
                val key = "best_memory_${Memory.SIZES.indexOf(v.game.cols to v.game.rows)}"
                val best = prefs.getInt(key, 0)
                if (best == 0 || v.game.turns < best) e.putInt(key, v.game.turns)
            }
        }
        e.apply()
    }

    private fun fastest(e: android.content.SharedPreferences.Editor, key: String, ms: Long) {
        val best = prefs.getLong(key, 0L)
        if (best == 0L || ms < best) e.putLong(key, ms)
    }

    private fun keep(v: GameView) {
        val id = when (v) {
            is GemView -> "gems"; is TilesView -> "2048"; is MinesView -> "mines"; is SolitaireView -> "solitaire"
            is SudokuView -> "sudoku"; is NonogramView -> "nonogram"; is MahjongView -> "mahjong"; is BlockDropView -> "blocks"
            is PipesView -> "pipes"; is SlideView -> "slide"; is WordSearchView -> "words"; is MemoryView -> "memory"
            else -> return
        }
        prefs.edit().putString("save_$id", v.save()).apply()
    }

    /** The kept game, or a new one. A won game counts as counted already. */
    private fun load(id: String): GameView {
        val saved = prefs.getString("save_$id", null)
        return when (id) {
            "gems" -> GemView(context, saved?.let { GemBoard.load(it) } ?: GemBoard()) { prefs.getInt("best_gems", 0) }
            "2048" -> TilesView(context, saved?.let { Tiles2048.load(it) } ?: Tiles2048())
            "mines" -> MinesView(context, saved?.let { MineField.load(it) } ?: MineField.SIZES[minesSize()].let { (w, h, m) -> MineField(w, h, m) })
                .also { if (it.field.won) counted = it.field }
            "sudoku" -> SudokuView(context, saved?.let { Sudoku.load(it) } ?: Sudoku.new(choice("sudoku_level", 3))).also { if (it.game.won) counted = it.game }
            "nonogram" -> NonogramView(context, saved?.let { Nonogram.load(it) } ?: Nonogram.new(Nonogram.SIZES[choice("nonogram_size", 3)])).also { if (it.game.won) counted = it.game }
            "mahjong" -> MahjongView(context, saved?.let { Mahjong.load(it) } ?: Mahjong.new()).also { if (it.game.won) counted = it.game }
            "pipes" -> PipesView(context, saved?.let { Pipes.load(it) } ?: Pipes.new(Pipes.SIZES[choice("pipes_size", 3)])).also { if (it.game.won) counted = it.game }
            "slide" -> SlideView(context, saved?.let { SlidePuzzle.load(it) } ?: SlidePuzzle.new(SlidePuzzle.SIZES[choice("slide_size", 3)])).also { if (it.game.won) counted = it.game }
            "words" -> WordSearchView(context, saved?.let { WordSearch.load(it) } ?: WordSearch.new(choice("words_level", 3))).also { if (it.game.won) counted = it.game }
            "memory" -> MemoryView(context, saved?.let { Memory.load(it) } ?: Memory.SIZES[choice("memory_size", 3)].let { (c, r) -> Memory.new(c, r) }).also { if (it.game.won) counted = it.game }
            "blocks" -> BlockDropView(context, saved?.let { BlockDrop.load(it) } ?: BlockDrop()) { prefs.getInt("best_blocks", 0) }
            else -> SolitaireView(context, saved?.let { Klondike.load(it) } ?: Klondike()).also { if (it.game.won) counted = it.game }
        }
    }

    /** The Continue card's line for a kept game, or null when there is nothing to go on with. */
    private fun summary(id: String): String? {
        val saved = prefs.getString("save_$id", null) ?: return null
        return when (id) {
            "gems" -> GemBoard.load(saved)?.takeIf { it.score > 0 }?.let { "Level ${it.level} · ${GameView.number(it.roundScore)} points · ${it.movesLeft} moves left" }
            "2048" -> Tiles2048.load(saved)?.takeIf { !it.over && it.score > 0 }?.let { "Score ${GameView.number(it.score)} · biggest tile ${it.cells.max()}" }
            "mines" -> MineField.load(saved)?.takeIf { it.laid && !it.over }?.let { "${MineField.SIZE_NAMES[MineField.SIZES.indexOfFirst { s -> s.first == it.width }.coerceAtLeast(0)]} field · ${it.flagsLeft} mines left · ${GameView.clock(it.millis)}" }
            "solitaire" -> Klondike.load(saved)?.takeIf { !it.won && it.moves > 0 }?.let { "${it.moves} moves · ${GameView.clock(it.millis)}" }
            "sudoku" -> Sudoku.load(saved)?.takeIf { !it.won }?.let { g -> "${Sudoku.LEVELS[g.level]} · ${g.values.count { it == 0 }} squares to go · ${GameView.clock(g.millis)}" }
            "nonogram" -> Nonogram.load(saved)?.takeIf { !it.won && it.marks.any { m -> m != 0 } }?.let { "${it.size} x ${it.size} picture · ${GameView.clock(it.millis)}" }
            "mahjong" -> Mahjong.load(saved)?.takeIf { !it.won && it.left < Mahjong.SPOTS.size }?.let { "${it.left} tiles left · ${GameView.clock(it.millis)}" }
            "blocks" -> BlockDrop.load(saved)?.takeIf { !it.over && it.score > 0 }?.let { "Score ${GameView.number(it.score)}" }
            "pipes" -> Pipes.load(saved)?.takeIf { !it.won && it.moves > 0 }?.let { "${it.size} x ${it.size} · ${it.moves} turns · ${GameView.clock(it.millis)}" }
            "slide" -> SlidePuzzle.load(saved)?.takeIf { !it.won && it.moves > 0 }?.let { "${it.size} x ${it.size} · ${it.moves} moves · ${GameView.clock(it.millis)}" }
            "words" -> WordSearch.load(saved)?.takeIf { !it.won && it.foundCount > 0 }?.let { "${it.foundCount} of ${it.words.size} words · ${GameView.clock(it.millis)}" }
            "memory" -> Memory.load(saved)?.takeIf { !it.won && it.turns > 0 }?.let { "${it.found} of ${it.pairs} pairs · ${it.turns} turns" }
            else -> null
        }
    }

    /** The tile's small line under the name. */
    private fun bestLine(id: String): String = when (id) {
        "gems" -> prefs.getInt("best_gems", 0).let { if (it > 0) "Best ${GameView.number(it)}" else "Play" }
        "2048" -> prefs.getInt("best_2048", 0).let { if (it > 0) "Best ${GameView.number(it)}" else "Play" }
        "mines" -> prefs.getLong("best_mines_${minesSize()}", 0L).let { if (it > 0) "Best ${GameView.clock(it)}" else "Play" }
        "solitaire" -> prefs.getInt("wins_solitaire", 0).let { w -> if (w > 0) "$w won · best ${GameView.clock(prefs.getLong("best_solitaire", 0L))}" else "Play" }
        "sudoku" -> prefs.getLong("best_sudoku_${choice("sudoku_level", 3)}", 0L).let { if (it > 0) "Best ${GameView.clock(it)}" else "Play" }
        "nonogram" -> prefs.getLong("best_nonogram_${choice("nonogram_size", 3)}", 0L).let { if (it > 0) "Best ${GameView.clock(it)}" else "Play" }
        "mahjong" -> prefs.getInt("wins_mahjong", 0).let { w -> if (w > 0) "$w cleared · best ${GameView.clock(prefs.getLong("best_mahjong", 0L))}" else "Play" }
        "pipes" -> prefs.getLong("best_pipes_${choice("pipes_size", 3)}", 0L).let { if (it > 0) "Best ${GameView.clock(it)}" else "Play" }
        "slide" -> prefs.getLong("best_slide_${choice("slide_size", 3)}", 0L).let { if (it > 0) "Best ${GameView.clock(it)}" else "Play" }
        "words" -> prefs.getLong("best_words_${choice("words_level", 3)}", 0L).let { if (it > 0) "Best ${GameView.clock(it)}" else "Play" }
        "memory" -> prefs.getInt("best_memory_${choice("memory_size", 3)}", 0).let { if (it > 0) "Best $it turns" else "Play" }
        "blocks" -> prefs.getInt("best_blocks", 0).let { if (it > 0) "Best ${GameView.number(it)}" else "Play" }
        else -> "Play"
    }

    /** Settings > Games: forget every best score (the games themselves stay). */
    fun resetBest() {
        val e = prefs.edit()
        for (k in prefs.all.keys) if (k.startsWith("best_") || k.startsWith("wins_")) e.remove(k)
        e.apply()
    }

    companion object {
        val ENTRIES = listOf(
            Entry("gems", "Gem Match", true, true),
            Entry("solitaire", "Solitaire", true, true),
            Entry("mines", "Minesweeper", true, true),
            Entry("2048", "2048", true, true),
            Entry("sudoku", "Sudoku", true, true),
            Entry("nonogram", "Nonogram", true, true),
            Entry("mahjong", "Mahjong", true, true),
            Entry("blocks", "Block Drop", true, true),
            Entry("pipes", "Pipe Connect", true, true),
            Entry("slide", "Slide Puzzle", true, true),
            Entry("words", "Word Search", true, true),
            Entry("memory", "Memory Pairs", true, true),
            Entry("pegs", "Peg Bounce", false, false),
            Entry("gryphon", "Gryphon Glide", false, false),
            Entry("fishing", "Fishing Bobber", false, false),
            Entry("snake", "Snake", false, false),
            Entry("bricks", "Brick Breaker", false, false),
        )
    }
}
