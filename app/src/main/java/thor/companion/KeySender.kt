package thor.companion

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.SystemClock
import com.winlator.cmod.xserver.Keyboard
import com.winlator.cmod.xserver.XKeycode

/**
 * Sends one key (or one key with modifiers, like CTRL-F9) to the game. One call is one
 * key press. The key goes straight into Thor Forever's own X server, which gives it to
 * the game's window, so no root helper and no focus switch between the screens is needed.
 */
object KeySender {
    private val names: Map<String, XKeycode> = mapOf(
        "CTRL" to XKeycode.KEY_CTRL_L,
        "SHIFT" to XKeycode.KEY_SHIFT_L,
        "ALT" to XKeycode.KEY_ALT_L,
        "ENTER" to XKeycode.KEY_ENTER,
        "ESCAPE" to XKeycode.KEY_ESC,
        "BACKSPACE" to XKeycode.KEY_BKSP,
        "TAB" to XKeycode.KEY_TAB,
        "V" to XKeycode.KEY_V,
    ) + (1..12).associate { "F$it" to XKeycode.valueOf("KEY_F$it") } +
        (0..9).associate { "NUMPAD$it" to XKeycode.valueOf("KEY_KP_$it") }

    /** How long a key stays down, as a quick press on a keyboard. */
    private const val HOLD_MS = 40L

    /** Sends [key] in WoW's notation (e.g. "CTRL-F9"); returns what went wrong, or null. */
    @Suppress("UNUSED_PARAMETER")
    fun send(context: Context, displayId: Int, key: String): String? {
        val x = CompanionBridge.xServer ?: return "the game isn't running"
        val codes = key.split('-').map { names[it] ?: return "unknown key $it" }
        val modifiers = codes.dropLast(1)
        modifiers.forEach { x.injectKeyPress(it) }
        x.injectKeyPress(codes.last())
        SystemClock.sleep(HOLD_MS)
        x.injectKeyRelease(codes.last())
        modifiers.asReversed().forEach { x.injectKeyRelease(it) }
        return null
    }

    /**
     * Types [text] into the game's open text box (chat, a new character's name, the
     * login), as a keyboard would: each letter on its own key, with Shift for capitals
     * and the like. Only a character the keyboard has no key for (é, ß, …) goes in on a
     * spare key that gets that character first. Changing a key's character makes Wine
     * set up its whole keyboard again, so letters never do that.
     */
    @Suppress("UNUSED_PARAMETER")
    fun type(context: Context, displayId: Int, text: String): String? {
        val x = CompanionBridge.xServer ?: return "the game isn't running"
        var spare = false
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            i += Character.charCount(cp)
            if (cp < 0x20 || cp in 0x7F..0x9F) continue  // control characters: no key
            val key = plainKey(cp)
            if (key != null) {
                val (code, shift) = key
                if (shift) x.injectKeyPress(XKeycode.KEY_SHIFT_L)
                // The key's own keysym: the key keeps its characters (see InputDeviceManager.onKeyPress).
                x.injectKeyPress(code, cp)
                SystemClock.sleep(HOLD_MS / 2)
                x.injectKeyRelease(code)
                if (shift) x.injectKeyRelease(XKeycode.KEY_SHIFT_L)
            } else {
                spare = true
                // X keysyms: Latin-1 as is, everything else as 0x01000000 + the code point.
                val keysym = if (cp in 0x20..0xFF) cp else 0x01000000 or cp
                x.injectKeyPress(SPARE_KEY, keysym)
                SystemClock.sleep(HOLD_MS / 2)
                x.injectKeyRelease(SPARE_KEY)
            }
            SystemClock.sleep(HOLD_MS / 2)
        }
        // What happened, never what was typed.
        android.util.Log.i("ThorCompanion", "typed: " + if (spare) "keys and the spare key" else "keys only")
        return null
    }

    /** A key with no character of its own on the app's X keyboard, for characters no key has. */
    private val SPARE_KEY = XKeycode.KEY_PRTSCN

    /** The key and Shift for [cp] on the app's X keyboard as it starts (US layout), or null. */
    internal fun plainKey(cp: Int): Pair<XKeycode, Boolean>? = plainKeys[cp]

    private val plainKeys: Map<Int, Pair<XKeycode, Boolean>> by lazy {
        val board = Keyboard.createKeyboard(null)
        val map = HashMap<Int, Pair<XKeycode, Boolean>>()
        for (code in XKeycode.values()) {
            val index = code.id - Keyboard.MIN_KEYCODE
            if (index < 0 || index >= Keyboard.KEYS_COUNT / Keyboard.KEYSYMS_PER_KEYCODE) continue
            val low = board.keysyms[index * Keyboard.KEYSYMS_PER_KEYCODE]
            val high = board.keysyms[index * Keyboard.KEYSYMS_PER_KEYCODE + 1]
            if (low in 0x20..0x7E) map.putIfAbsent(low, code to false)
            if (high in 0x20..0x7E && high != low) map.putIfAbsent(high, code to true)
        }
        map
    }

    private const val WATCH_SOCKET = ".companion.watch"
    @Volatile private var server: LocalServerSocket? = null

    /**
     * A connection that reports what the game does, line by line ([GameFiles]), after a
     * "W" command. [GameFiles] runs in the app itself: the game's files and processes are
     * this app's own, so no root is needed.
     */
    fun open(context: Context): LocalSocket? {
        val name = context.packageName + WATCH_SOCKET
        val app = context.applicationContext
        synchronized(this) {
            if (server == null) {
                server = runCatching { LocalServerSocket(name) }.getOrNull()?.also { s ->
                    Thread({ serve(s, app) }, "game-files").apply { isDaemon = true; start() }
                }
            }
        }
        if (server == null) return null
        return runCatching { LocalSocket().apply { connect(LocalSocketAddress(name)) } }.getOrNull()
    }

    /** Answers each connection's "W [folder]" with the game's events, until it closes. */
    private fun serve(server: LocalServerSocket, context: Context) {
        while (true) {
            val socket = runCatching { server.accept() }.getOrElse { return }
            Thread {
                socket.use { s ->
                    val out = s.outputStream.bufferedWriter()
                    var files: GameFiles? = null
                    try {
                        s.inputStream.bufferedReader().forEachLine { line ->
                            if (!line.startsWith("W")) return@forEachLine
                            files?.stop()
                            // The folder the start screen found the game in first, then the companion's own guess.
                            val hint = CompanionBridge.gameFolder(context)
                                ?: line.substringAfter(' ', "").trim().ifEmpty { null }
                            files = GameFiles(out, hint)
                        }
                    } catch (e: java.io.IOException) {
                        // The companion went away.
                    } finally {
                        files?.stop()
                    }
                }
            }.apply { isDaemon = true }.start()
        }
    }
}
