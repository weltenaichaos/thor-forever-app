package thor.companion

import android.content.Context
import java.io.File

/**
 * The standalone companion updated itself from its GitHub releases. Built into Thor
 * Forever it comes with the app's own updates, so there is never a newer build to offer.
 */
object Updater {
    class Release(val version: String, val apkUrl: String)

    /** One fixed name: the companion's "updated to" note never shows. */
    @Suppress("UNUSED_PARAMETER")
    fun installedVersion(context: Context): String = "built-in"

    @Suppress("UNUSED_PARAMETER")
    fun check(context: Context): Release? = null

    @Suppress("UNUSED_PARAMETER")
    fun download(context: Context, release: Release, progress: (Int) -> Unit): File? = null

    @Suppress("UNUSED_PARAMETER")
    fun install(context: Context, apk: File, display: Int): Boolean = false
}
