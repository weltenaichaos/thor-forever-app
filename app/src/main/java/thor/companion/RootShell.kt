package thor.companion

import android.util.Log

/**
 * The standalone companion ran shell commands as root through the Thor's root service.
 * Built into Thor Forever it needs no root: the game's picture and keys are in this app.
 * The few commands left (saving the app's log) run as the app itself, without root.
 */
object RootShell {
    private const val TAG = "ThorCompanion"

    /** The command's first line of output (run as the app, not root), or null when it couldn't run. */
    fun exec(cmd: String): String? = try {
        val process = ProcessBuilder("sh", "-c", cmd).redirectErrorStream(true).start()
        val out = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        out.lineSequence().firstOrNull().orEmpty()
    } catch (t: Throwable) {
        Log.w(TAG, "shell: $t")
        null
    }

    /** Single-quoted for sh. */
    fun quote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
