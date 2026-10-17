package thor.companion

import android.os.SystemClock
import com.winlator.cmod.xserver.Atom
import com.winlator.cmod.xserver.SelectionManager
import com.winlator.cmod.xserver.XServer

/**
 * Hands a chat line to the game's paste (CTRL-V) without Android's clipboard: Thor
 * Forever's own X server holds it as the X clipboard, the way an X program that copied it
 * would. Wine, which runs the game, takes the X clipboard over into the game's clipboard:
 * it looks every two seconds and when the game pastes, and fetches the text itself when
 * the game asks for it. So the app can tell when Wine has seen a line and when it took it.
 */
object GamePaste {
    /** One line offered: [gen] counts the lines; [mustWait] says Wine may still hold an older one. */
    class Offer(val gen: Int, val mustWait: Boolean)

    private fun clipboard(x: XServer): SelectionManager.Selection =
        x.selectionManager.getSelection(Atom.internAtom("CLIPBOARD"))

    /** Makes [text] the X clipboard. Null: the game isn't running. */
    fun offer(text: String): Offer? {
        val x = CompanionBridge.xServer ?: return null
        x.lock(XServer.Lockable.WINDOW_MANAGER).use {
            // Something copied in the game (Wine owned the clipboard) or the last line
            // already pasted: until Wine has looked again, a paste could bring that back.
            val fromGame = x.selectionManager.setServerText(Atom.internAtom("CLIPBOARD"), text)
            val selection = clipboard(x)
            return Offer(selection.gen, fromGame || selection.textLast)
        }
    }

    /** Whether Wine has looked at this line (asked which formats it comes in). */
    fun seen(offer: Offer): Boolean = CompanionBridge.xServer?.let { clipboard(it).targetsGen >= offer.gen } ?: false

    /** Whether Wine fetched this line's text (the game pasted it). */
    fun taken(offer: Offer): Boolean = CompanionBridge.xServer?.let { clipboard(it).textGen >= offer.gen } ?: false

    /** Waits up to [ms] for [done]; returns whether it came. */
    fun await(ms: Long, done: () -> Boolean): Boolean {
        val until = SystemClock.uptimeMillis() + ms
        while (!done()) {
            if (SystemClock.uptimeMillis() >= until) return false
            SystemClock.sleep(20)
        }
        return true
    }
}
