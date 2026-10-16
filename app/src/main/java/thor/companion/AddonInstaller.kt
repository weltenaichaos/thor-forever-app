package thor.companion

import android.content.Context

/**
 * The companion's addon, as Thor Forever ships it. The start screen's Companion button
 * puts it into the game (see com.winlator.cmod.core.CompanionAddon), so the companion
 * itself never installs anything.
 */
object AddonInstaller {
    const val ASSET_DIR = "thor/companion/ThorCompanion"

    /** The bundled addon's version, from its .toc. */
    fun version(context: Context): String? = runCatching {
        context.assets.open("$ASSET_DIR/ThorCompanion.toc").bufferedReader().useLines { lines ->
            lines.firstOrNull { it.startsWith("## Version:") }?.substringAfter(':')?.trim()
        }
    }.getOrNull()
}
