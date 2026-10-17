package thor.companion

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.view.SurfaceView
import com.winlator.cmod.xserver.XServer

/**
 * Connects the companion to the game Thor Forever runs: the view the game is drawn into
 * (the companion reads the addon's square from it) and the X server the game takes its
 * keys from. Both live in this app, so no root service is needed.
 */
object CompanionBridge {
    private const val PREFS = "thor_companion"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_GAME_FOLDER = "gameFolder"
    /** The shortcut that starts WoW Forever; Battle.net gets no companion. */
    private const val WOW_SHORTCUT_FILE = "World of Warcraft Forever.desktop"

    @Volatile var view: SurfaceView? = null
        private set
    @Volatile var xServer: XServer? = null
        private set

    /** The game screen's task, to give the key focus back to it (see CompanionActivity.giveFocusToGame); -1: none. */
    @Volatile var gameTaskId: Int = -1
        private set

    /** Whether the game's screen has the key focus now (the main thread only). */
    fun gameHasFocus(): Boolean = view?.hasWindowFocus() == true

    @JvmStatic
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** The game's folder (the one with WTF, Logs and Interface), which the companion follows through its files. */
    @JvmStatic
    fun gameFolder(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_GAME_FOLDER, null)

    @JvmStatic
    fun setGameFolder(context: Context, path: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_GAME_FOLDER, path).apply()
    }

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Called by the game's screen once it shows; opens the companion on the other screen for WoW. */
    @JvmStatic
    fun attach(activity: Activity, xServer: XServer, view: SurfaceView, shortcutPath: String?) {
        this.xServer = xServer
        this.view = view
        gameTaskId = activity.taskId
        if (!enabled(activity) || shortcutPath?.endsWith("/$WOW_SHORTCUT_FILE") != true) return
        runCatching { open(activity) }
    }

    /** Called when the game's screen goes away. */
    @JvmStatic
    fun detach(view: SurfaceView?) {
        if (view != null && this.view !== view) return
        this.view = null
        xServer = null
        gameTaskId = -1
    }

    /** Opens the companion on the screen the game is not on; nothing on a device with one screen. */
    private fun open(activity: Activity) {
        @Suppress("DEPRECATION")
        val here = activity.windowManager.defaultDisplay.displayId
        val dm = activity.getSystemService(DisplayManager::class.java) ?: return
        val other = dm.displays.firstOrNull { it.displayId != here } ?: return
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(other.displayId)
        // Starting it pauses the game's screen; the game keeps running all the same while it
        // is shown (see XServerDisplayActivity.onPause).
        activity.startActivity(
            Intent(activity, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            options.toBundle()
        )
    }
}
