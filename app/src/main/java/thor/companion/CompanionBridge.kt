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
    /** The shortcut that starts WoW Forever; Battle.net gets no companion. */
    private const val WOW_SHORTCUT_FILE = "World of Warcraft Forever.desktop"

    @Volatile var view: SurfaceView? = null
        private set
    @Volatile var xServer: XServer? = null
        private set

    @JvmStatic
    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    @JvmStatic
    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Called by the game's screen once it shows; opens the companion on the other screen for WoW. */
    @JvmStatic
    fun attach(activity: Activity, xServer: XServer, view: SurfaceView, shortcutPath: String?) {
        this.xServer = xServer
        this.view = view
        if (!enabled(activity) || shortcutPath?.endsWith("/$WOW_SHORTCUT_FILE") != true) return
        runCatching { open(activity) }
    }

    /** Called when the game's screen goes away. */
    @JvmStatic
    fun detach(view: SurfaceView?) {
        if (view != null && this.view !== view) return
        this.view = null
        xServer = null
    }

    /** Opens the companion on the screen the game is not on; nothing on a device with one screen. */
    private fun open(activity: Activity) {
        @Suppress("DEPRECATION")
        val here = activity.windowManager.defaultDisplay.displayId
        val dm = activity.getSystemService(DisplayManager::class.java) ?: return
        val other = dm.displays.firstOrNull { it.displayId != here } ?: return
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(other.displayId)
        activity.startActivity(
            Intent(activity, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            options.toBundle()
        )
    }
}
