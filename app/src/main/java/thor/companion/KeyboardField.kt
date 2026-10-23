package thor.companion

import android.content.Context
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText

/**
 * A text box whose keyboard ⌫ (and Enter) always work. Some keyboards send those as key
 * presses, and Android drops a key press for a window that hasn't got the key focus (on the
 * Thor the game on the other screen can keep it), while typed letters still come in. Here
 * the box does them itself, as soon as the keyboard sends them. Logs only which key, never
 * the text.
 */
internal class KeyboardField(context: Context) : EditText(context) {

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        return object : InputConnectionWrapper(base, false) {
            override fun sendKeyEvent(event: KeyEvent): Boolean {
                val code = event.keyCode
                if (code != KeyEvent.KEYCODE_DEL && code != KeyEvent.KEYCODE_FORWARD_DEL && code != KeyEvent.KEYCODE_ENTER) {
                    return super.sendKeyEvent(event)
                }
                if (event.action == KeyEvent.ACTION_DOWN) {
                    android.util.Log.i("ThorCompanion", "box: key $code from the keyboard (window focus ${hasWindowFocus()})")
                    when (code) {
                        KeyEvent.KEYCODE_DEL -> erase(before = true)
                        KeyEvent.KEYCODE_FORWARD_DEL -> erase(before = false)
                        else -> onEditorAction(imeOptions and EditorInfo.IME_MASK_ACTION)
                    }
                }
                return true
            }
        }
    }

    /** Deletes the selection, or one letter before (or after) the cursor. */
    internal fun erase(before: Boolean) {
        val text = editableText ?: return
        val start = minOf(selectionStart, selectionEnd)
        val end = maxOf(selectionStart, selectionEnd)
        if (start < 0) return
        when {
            start != end -> text.delete(start, end)
            before && start > 0 -> text.delete(Character.offsetByCodePoints(text, start, -1), start)
            !before && end < text.length -> text.delete(end, Character.offsetByCodePoints(text, end, 1))
        }
    }
}
