package thor.companion

import android.content.Context

/**
 * The companion's addon, as Thor Forever ships it. The start screen's Companion button
 * puts it into the game (see com.winlator.cmod.core.CompanionAddon), so the companion
 * itself never installs anything.
 */
object AddonInstaller {
    const val ASSET_DIR = "thor/companion/ThorCompanion"

    data class Outcome(val installed: Boolean, val note: String, val missing: Boolean = false)

    /** The bundled addon's version, from its .toc. */
    fun version(context: Context): String? = runCatching {
        context.assets.open("$ASSET_DIR/ThorCompanion.toc").bufferedReader().useLines { lines ->
            lines.firstOrNull { it.startsWith("## Version:") }?.substringAfter(':')?.trim()
        }
    }.getOrNull()

    @Suppress("UNUSED_PARAMETER")
    fun install(context: Context, chosen: String? = null): Outcome = Outcome(false, "")

    @Suppress("UNUSED_PARAMETER")
    fun pathOf(tree: android.net.Uri): String? = null
}
