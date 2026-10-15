package com.winlator.cmod.ui.start

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent as AndroidKeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.nativeKeyCode
import androidx.compose.ui.input.key.key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.container.Container
import com.winlator.cmod.core.AddonInstaller
import com.winlator.cmod.core.AddonStore
import com.winlator.cmod.core.CompanionAddon
import com.winlator.cmod.core.GameUpdateCheck
import com.winlator.cmod.core.ThorGame
import com.winlator.cmod.core.ThorSetup
import com.winlator.cmod.core.WowInterfaceStore
import com.winlator.cmod.xenvironment.ImageFs
import com.winlator.cmod.xenvironment.ImageFsInstaller
import com.winlator.cmod.OnboardingActivity
import androidx.preference.PreferenceManager
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Thor Forever start screen, the first thing the app shows: the installed game version,
 * Play, Update with Battle.net and a few settings, in the design of the old Windows start
 * screen (colours after World of Warcraft: Forever, Cinzel font; no game artwork). Play and
 * Update start a session; when it ends the app comes back here. Without the game yet, it walks
 * the player through the first setup: install Battle.net, log in, install World of Warcraft.
 */
class ThorStartActivity : ComponentActivity() {
    private var game by mutableStateOf<ThorGame?>(null)
    private var loaded by mutableStateOf(false)
    private var version by mutableStateOf("")
    /** The version Battle.net would update to, when it differs from the installed one. */
    private var newVersion by mutableStateOf("")
    private var status by mutableStateOf("")
    private var fpsCap by mutableStateOf(0)
    /** The Forever Companion on the other screen: on or off (the button's own setting). */
    private var companionOn by mutableStateOf(false)
    private var overlay by mutableStateOf(0)
    /** The first-setup step shown while there is no WoW shortcut yet. */
    private var setup by mutableStateOf(Setup.NONE)
    private var setupContainer: Container? = null
    private var downloading = false
    /** The download failed: the player picks the installer they downloaded themselves. */
    private var pickInstaller by mutableStateOf(false)
    private val installerPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) prepareInstaller { container, progress ->
            val size = runCatching {
                contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) c.getLong(0) else -1L
                }
            }.getOrNull() ?: -1L
            contentResolver.openInputStream(uri).use { input ->
                ThorSetup.copyInstaller(container, input ?: throw java.io.IOException("Could not open the file"), size, progress)
            }
        }
    }

    /** The add-ons page: the installed add-ons and Install from file. */
    private var showAddons by mutableStateOf(false)
    private var addons by mutableStateOf<List<AddonInstaller.Addon>>(emptyList())
    private var addonStatus by mutableStateOf("")
    /** The add-on store, opened from the add-ons page. */
    private var showStore by mutableStateOf(false)
    private var storeEntries by mutableStateOf<List<AddonStore.Entry>>(emptyList())
    /** What the store offers for each add-on (by repository), once GitHub answered. */
    private val storeOffers = mutableStateMapOf<String, StoreOffer>()
    /** WoWInterface's add-ons that were checked to be made for the game, or null while checking. */
    private var wowiItems by mutableStateOf<List<WowInterfaceStore.Item>?>(null)
    private var wowiError by mutableStateOf<String?>(null)
    private var wowiChecking = false
    /** Checked WoWInterface downloads by "id:version": their add-on folders, empty when not for the game. */
    private val wowiChecked = java.util.concurrent.ConcurrentHashMap<String, List<String>>()
    private var installingAddon = false
    private val addonPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) installAddon(uri)
    }

    /** offer is null when the add-on has no file for this game (or error says why not). */
    private data class StoreOffer(val offer: AddonStore.Offer?, val error: String? = null)

    private enum class Setup { NONE, NO_CONTAINER, INSTALL_BATTLE_NET, OPEN_BATTLE_NET }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        companionOn = thor.companion.CompanionBridge.enabled(this)
        // Before the first setup, after an app update that needs new system files, and when
        // the app restarts itself onto one of its own pages, the normal Winlator screen takes over.
        val menuItem = intent.getIntExtra("selected_menu_item_id", 0)
        val imageFs = ImageFs.find(this)
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        if (menuItem > 0 || !imageFs.isValid || imageFs.version < ImageFsInstaller.LATEST_VERSION
            || !prefs.getBoolean(OnboardingActivity.PREF_ONBOARDING_COMPLETE, false)) {
            openWinlator(menuItem)
            finish()
            return
        }
        // Full screen like the game, without Android's navigation bar.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent { StartScreen() }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing && !downloading) load()
    }

    private fun load() {
        Thread {
            val manager = runCatching { ContainerManager(this) }.getOrNull()
            manager?.let { m -> runCatching { ThorSetup.turnOffEsyncForBattleNet(m) } }
            manager?.let { m -> runCatching { ThorSetup.fixAddedWowShortcuts(m) } }
            manager?.let { m -> runCatching { ThorSetup.redoThorDxvkFiles(m) } }
            var found = runCatching { manager?.let { ThorGame.find(it) } }.getOrNull()
            var container: Container? = null
            var step = Setup.NONE
            var added = false
            if (found == null && manager != null) {
                container = runCatching { ThorSetup.pickContainer(manager) }.getOrNull()
                // Battle.net installed the game: add its shortcut with the Thor settings.
                val wow = container?.let { c -> runCatching { ThorSetup.findInstalledWow(c) }.getOrNull() }
                if (container != null && wow != null && runCatching { ThorSetup.createWowShortcut(this, container, wow) }.isSuccess) {
                    found = runCatching { ThorGame.find(manager) }.getOrNull()
                    added = found != null
                }
                if (found == null) step = when {
                    container == null -> Setup.NO_CONTAINER
                    runCatching { ThorSetup.findBattleNet(container, null) }.getOrNull() != null -> Setup.OPEN_BATTLE_NET
                    else -> Setup.INSTALL_BATTLE_NET
                }
            }
            val installed = runCatching { found?.installedVersion() ?: "" }.getOrDefault("")
            val foundAddons = runCatching {
                AddonInstaller.installed(AddonInstaller.addonsDir(found), AddonStore.interfaceOf(installed))
            }.getOrDefault(emptyList())
            runCatching { found?.applyCoverArt(this) }
            runOnUiThread {
                setupContainer = container
                setup = step
                addons = foundAddons
                show(found, installed, added)
            }
        }.start()
    }

    private fun show(found: ThorGame?, installed: String, added: Boolean = false) {
        run {
            game = found
            version = installed
            fpsCap = found?.shortcut?.getExtra(EXTRA_FPS_LIMIT, "0")?.toIntOrNull() ?: 0
            overlay = found?.shortcut?.container?.getExtra("hudMode", "")?.toIntOrNull()
                ?: if (found?.shortcut?.container?.isShowFPS == true) 1 else 0
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            status = when {
                added -> "World of Warcraft is installed and set up for the Thor. Press Play."
                setup == Setup.NO_CONTAINER -> "No container yet. Open More (Winlator) to create one, then come back."
                setup == Setup.INSTALL_BATTLE_NET && pickInstaller -> "Download Battle.net-Setup.exe from battle.net/download in your browser, then press Choose installer and pick it."
                setup == Setup.INSTALL_BATTLE_NET -> "Welcome! Press Install Battle.net. Then log in, install World of Warcraft and close Battle.net when it's done."
                setup == Setup.OPEN_BATTLE_NET -> "Battle.net is installed. Open it, log in and install World of Warcraft. Keep it open until the download is done, then close it."
                found == null -> "No World of Warcraft shortcut found. Add one in Winlator (More), then come back."
                prefs.getBoolean(KEY_UPDATE_RAN, false) -> "Battle.net is closed. The version above is now installed."
                else -> "Press Update with Battle.net to check for a game update."
            }
            prefs.edit().putBoolean(KEY_UPDATE_RAN, false).apply()
            loaded = true
            newVersion = ""
            if (found != null && installed.isNotEmpty() && !added) checkForUpdate(found, installed)
        }
    }

    /** Asks Blizzard's version server whether Battle.net has a newer game version waiting. */
    private fun checkForUpdate(found: ThorGame, installed: String) {
        Thread {
            val latest = runCatching { GameUpdateCheck.latestVersion(found.product(), found.region()) }
                .onFailure { Log.w("ThorStart", "Update check failed", it) }
                .getOrDefault("")
            runOnUiThread {
                // Only for the game still shown: the screen may have reloaded meanwhile.
                if (game === found && GameUpdateCheck.updateAvailable(installed, latest)) {
                    newVersion = latest
                    status = "Update available: $latest. Press Update with Battle.net to install it."
                }
            }
        }.start()
    }

    private fun play() {
        val g = game ?: return
        g.shortcut.putExtra("lastRunAt", System.currentTimeMillis().toString())
        g.shortcut.saveData()
        val addonsDir = AddonInstaller.addonsDir(g)
        if (!companionOn || addonsDir == null) {
            startShortcut(g.shortcut.container.id, g.shortcut.file.path, update = false)
            return
        }
        // The companion's addon in the version this app carries, so app and addon match.
        Thread {
            runCatching { CompanionAddon.install(this, addonsDir) }
                .onFailure { Log.w("ThorStart", "companion addon: $it") }
            runOnUiThread { startShortcut(g.shortcut.container.id, g.shortcut.file.path, update = false) }
        }.start()
    }

    private fun update() {
        val g = game ?: return
        // The player's own Battle.net shortcut keeps its settings; otherwise start the launcher found on disk.
        val shortcutPath = g.battleNetShortcut?.file?.path ?: run {
            val battleNet = g.battleNetPath()
            if (battleNet == null) {
                Toast.makeText(this, "Battle.net was not found in the container \"${g.shortcut.container.name}\". Add a Battle.net shortcut in Winlator (More).", Toast.LENGTH_LONG).show()
                return
            }
            try {
                g.writeBattleNetShortcut(this, battleNet).path
            } catch (e: Exception) {
                Toast.makeText(this, "Could not start Battle.net: ${e.message}", Toast.LENGTH_LONG).show()
                return
            }
        }
        val containerId = g.battleNetShortcut?.container?.id ?: g.shortcut.container.id
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_UPDATE_RAN, true).apply()
        status = "Starting Battle.net... Press Update there if it offers one, then close Battle.net."
        startShortcut(containerId, shortcutPath, update = true)
    }

    /**
     * Starts a shortcut through Winlator's main screen, the same way its library does: started
     * straight from here, WoW crashed natively within a second.
     */
    private fun startShortcut(containerId: Int, shortcutPath: String, update: Boolean) {
        // Battle.net runs, but not smoothly in Wine: its window often stays white for a while.
        if (update) Toast.makeText(this, BATTLE_NET_HINT, Toast.LENGTH_LONG).show()
        startActivity(Intent(this, MainActivity::class.java)
            .putExtra("container_id", containerId)
            .putExtra(MainActivity.EXTRA_RUN_SHORTCUT, shortcutPath)
            .putExtra(ThorGame.EXTRA_UPDATE_SESSION, update))
    }

    /** Downloads Blizzard's installer into the container and starts it; it goes on to open Battle.net. */
    private fun installBattleNet() {
        if (pickInstaller) {
            installerPicker.launch(arrayOf("*/*"))
            return
        }
        prepareInstaller { container, progress -> ThorSetup.downloadInstaller(container, progress) }
    }

    /** Puts the installer in the container (fetch does the download or copy), then starts it. */
    private fun prepareInstaller(fetch: (Container, ThorSetup.Progress) -> Unit) {
        val container = setupContainer ?: return
        if (downloading) return
        downloading = true
        val action = if (pickInstaller) "Copying the Battle.net installer" else "Downloading the Battle.net installer from Blizzard"
        status = "$action..."
        Thread {
            val result = runCatching {
                fetch(container, ThorSetup.Progress { percent ->
                    if (percent >= 0) runOnUiThread { status = "$action... $percent%" }
                })
                ThorSetup.writeRunShortcut(this, container, "Battle.net Setup", ThorSetup.INSTALLER_DOS_PATH).path
            }
            runOnUiThread {
                downloading = false
                result.onSuccess { shortcutPath ->
                    pickInstaller = false
                    status = "Starting the installer... Follow it, log in to Battle.net, install World of Warcraft, then close Battle.net."
                    startShortcut(container.id, shortcutPath, update = true)
                }.onFailure { e ->
                    status = if (pickInstaller) "Could not use that file (${e.message}). Press Choose installer and pick Battle.net-Setup.exe."
                    else "The download did not work (${e.message}). Download Battle.net-Setup.exe from battle.net/download in your browser, then press Choose installer and pick it."
                    pickInstaller = true
                }
            }
        }.start()
    }

    private fun openBattleNet() {
        val container = setupContainer ?: return
        val battleNet = ThorSetup.findBattleNet(container, null) ?: return
        val shortcutPath = try {
            ThorSetup.writeRunShortcut(this, container, "Battle.net", battleNet).path
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start Battle.net: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        status = "Starting Battle.net... Log in, install World of Warcraft, then close Battle.net."
        startShortcut(container.id, shortcutPath, update = true)
    }

    /**
     * Copies the picked .zip into the app's cache (a picked file can only be read once) and
     * unpacks its add-ons into the game's AddOns folder.
     */
    private fun installAddon(uri: android.net.Uri) {
        val addonsDir = AddonInstaller.addonsDir(game) ?: return
        if (installingAddon) return
        installingAddon = true
        addonStatus = "Installing..."
        Thread {
            val copy = java.io.File(cacheDir, "addon-import.zip")
            val result = runCatching {
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw java.io.IOException("Could not open the file")
                    copy.outputStream().use { input.copyTo(it) }
                }
                AddonInstaller.install(copy, addonsDir).also { runCatching { AddonInstaller.remember(addonRecord(), it) } }
            }
            copy.delete()
            val list = runCatching { AddonInstaller.installed(addonsDir, AddonStore.interfaceOf(version)) }.getOrDefault(addons)
            runOnUiThread {
                installingAddon = false
                addons = list
                addonStatus = result.fold(
                    { "Installed: ${it.joinToString(", ")}. It works the next time WoW starts." },
                    { e ->
                        if (e is AddonInstaller.AlreadyInstalledException) "${e.message}. Updating an add-on is not possible yet."
                        else "Could not install it: ${e.message}"
                    })
            }
        }.start()
    }

    /** Opens the store and asks GitHub, for each add-on, for the file made for this game. */
    private fun openStore() {
        val gameInterface = AddonStore.interfaceOf(version)
        if (storeEntries.isEmpty()) storeEntries = runCatching {
            AddonStore.parseCatalog(assets.open(AddonStore.CATALOG_ASSET).bufferedReader().use { it.readText() })
        }.getOrDefault(emptyList())
        addonStatus = ""
        showStore = true
        if (gameInterface == 0) {
            addonStatus = "The game version is unknown, so the store can't tell which files fit."
            return
        }
        checkWowInterface(gameInterface)
        for (entry in storeEntries) {
            if (storeOffers[entry.repo]?.error == null && storeOffers.containsKey(entry.repo)) continue
            storeOffers.remove(entry.repo)
            Thread {
                val result = runCatching { AddonStore.findOffer(entry, gameInterface) }
                runOnUiThread {
                    storeOffers[entry.repo] = result.fold({ StoreOffer(it) }, { StoreOffer(null, it.message ?: "No connection") })
                }
            }.start()
        }
    }

    /** Downloads WoWInterface's list and checks the add-ons that may be made for the game. */
    private fun checkWowInterface(gameInterface: Int) {
        if (wowiChecking || (wowiItems != null && wowiError == null)) return
        wowiChecking = true
        wowiItems = null
        wowiError = null
        val skip = storeEntries.map { it.name.lowercase() }.toSet()
        val gameVersion = version
        Thread {
            val result = runCatching { WowInterfaceStore.findForGame(gameVersion, gameInterface, skip, wowiChecked, cacheDir) }
                .onFailure { Log.w("ThorStart", "WoWInterface check failed", it) }
            runOnUiThread {
                wowiChecking = false
                result.onSuccess { wowiItems = it }.onFailure { wowiItems = emptyList(); wowiError = it.message ?: "No connection" }
            }
        }.start()
    }

    /** Downloads an add-on from the store and installs it like a picked .zip. */
    private fun installFromStore(entry: AddonStore.Entry) {
        val offer = storeOffers[entry.repo]?.offer ?: return
        installStoreAddon(entry.name, listOf(entry.folder)) { addonsDir, progress ->
            AddonStore.install(entry, offer, AddonStore.interfaceOf(version), addonsDir, cacheDir, progress)
        }
    }

    private fun installFromWowInterface(item: WowInterfaceStore.Item) {
        installStoreAddon(item.title, item.folders) { addonsDir, progress ->
            WowInterfaceStore.install(item, AddonStore.interfaceOf(version), addonsDir, cacheDir, progress)
        }
    }

    private fun isInstalled(folders: List<String>): Boolean =
        folders.any { f -> addons.any { it.folder.equals(f, ignoreCase = true) } }

    /** Which folders each install added, in the app's own folder (for uninstalling). */
    private fun addonRecord() = java.io.File(filesDir, "thor/addon-installs.txt")

    /** Asks first, then deletes the add-on's folders (all that were installed with it). */
    private fun uninstallAddon(folder: String, name: String) {
        val addonsDir = AddonInstaller.addonsDir(game) ?: return
        if (installingAddon) return
        val folders = runCatching { AddonInstaller.foldersWith(addonRecord(), addonsDir, folder) }.getOrDefault(listOf(folder))
        val extra = if (folders.size > 1) "\n\nThis removes these folders: ${folders.joinToString(", ")}." else ""
        android.app.AlertDialog.Builder(this)
            .setTitle("Uninstall $name?")
            .setMessage("$name will be deleted from WoW's AddOns folder.$extra")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Uninstall") { _, _ ->
                installingAddon = true
                addonStatus = "Uninstalling $name..."
                Thread {
                    val result = runCatching { AddonInstaller.uninstall(addonRecord(), addonsDir, folders) }
                    val list = runCatching { AddonInstaller.installed(addonsDir, AddonStore.interfaceOf(version)) }.getOrDefault(addons)
                    runOnUiThread {
                        installingAddon = false
                        addons = list
                        addonStatus = result.fold(
                            { "$name is uninstalled. WoW notices it the next time it starts." },
                            { e -> "Could not uninstall $name: ${e.message}" })
                    }
                }.start()
            }
            .show()
    }

    /** The installed folder of a store entry, by name, for uninstalling it. */
    private fun installedFolder(folders: List<String>): String? =
        addons.firstOrNull { a -> folders.any { it.equals(a.folder, ignoreCase = true) } }?.folder

    private fun installStoreAddon(name: String, folders: List<String>, install: (java.io.File, ThorSetup.Progress) -> List<String>) {
        val addonsDir = AddonInstaller.addonsDir(game) ?: return
        if (installingAddon || isInstalled(folders)) return
        installingAddon = true
        addonStatus = "Downloading $name..."
        Thread {
            val result = runCatching {
                install(addonsDir, ThorSetup.Progress { percent ->
                    if (percent >= 0) runOnUiThread { addonStatus = "Downloading $name... $percent%" }
                }).also { runCatching { AddonInstaller.remember(addonRecord(), it) } }
            }
            val list = runCatching { AddonInstaller.installed(addonsDir, AddonStore.interfaceOf(version)) }.getOrDefault(addons)
            runOnUiThread {
                installingAddon = false
                addons = list
                addonStatus = result.fold(
                    { "$name is installed. It works the next time WoW starts." },
                    { e ->
                        if (e is AddonInstaller.AlreadyInstalledException) "${e.message}. Updating an add-on is not possible yet."
                        else "Could not install $name: ${e.message}"
                    })
            }
        }.start()
    }

    /**
     * Switches the Forever Companion on or off. On puts its addon into the game; off takes it
     * out again (only its own ThorCompanion folder).
     */
    private fun toggleCompanion() {
        val on = !companionOn
        thor.companion.CompanionBridge.setEnabled(this, on)
        companionOn = on
        val addonsDir = AddonInstaller.addonsDir(game)
        if (addonsDir == null) {
            status = if (on) "Companion on: it opens on the other screen when WoW starts." else "Companion off."
            return
        }
        Thread {
            val result = runCatching {
                if (on) CompanionAddon.install(this, addonsDir) else CompanionAddon.remove(addonRecord(), addonsDir)
            }
            val list = runCatching { AddonInstaller.installed(addonsDir, AddonStore.interfaceOf(version)) }.getOrDefault(addons)
            runOnUiThread {
                addons = list
                status = result.fold(
                    { if (on) "Companion on: it opens on the other screen when WoW starts." else "Companion off: its addon is removed." },
                    { e -> if (on) "Companion on, but its addon could not be installed: ${e.message}" else "Companion off, but its addon could not be removed: ${e.message}" })
            }
        }.start()
    }

    private fun nextFpsCap() {
        val shortcut = game?.shortcut ?: return
        val next = FPS_CAPS[(FPS_CAPS.indexOf(fpsCap) + 1) % FPS_CAPS.size]
        shortcut.putExtra("nativeFpsLimiterEnabled", null)
        shortcut.putExtra(EXTRA_FPS_LIMIT, next.toString())
        shortcut.saveData()
        fpsCap = next
    }

    private fun nextOverlay() {
        val container = game?.shortcut?.container ?: return
        val next = (overlay + 1) % OVERLAYS.size
        container.putExtra("hudMode", next.toString())
        container.saveData()
        overlay = next
    }

    private fun openWinlator(menuItem: Int = 0) {
        val intent = Intent(this, MainActivity::class.java)
        if (menuItem > 0) intent.putExtra("selected_menu_item_id", menuItem)
        startActivity(intent)
    }

    @Composable
    private fun StartScreen() {
        BoxWithConstraints(
            Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            // The panel fills the screen. The unit is a twentieth of its height (or a 34th of
            // its width on narrow screens), so the buttons stay a fitting size on the Thor.
            val u = minOf(maxHeight / 20f, maxWidth / 34f).coerceAtLeast(12.dp)
            // The add-on pages use a smaller logo, so their lists have more room.
            val subPage = showStore || showAddons
            val logo = if (subPage) u * 3 / 5 else u
            val headerHeight = logo * 17 / 4
            val px = onePx()
            // The old screen's frame, in screen pixels: gold, dark bronze, and 3 pixels in, sky blue.
            Box(
                Modifier.fillMaxSize()
                    .background(Background)
                    .border(px, Gold)
                    .padding(px).border(px, Color(74, 52, 24))
            ) {
                Header(logo, Modifier.fillMaxWidth().height(headerHeight))
                Box(Modifier.fillMaxSize().padding(px * 3).border(px, SkyDark))
                Column(Modifier.fillMaxSize().padding(
                    start = u, end = u, top = headerHeight + if (subPage) u / 3 else u / 2, bottom = if (subPage) u / 2 else u
                )) {
                    val body = TextStyle(fontFamily = FontFamily(Font(R.font.wine_tahoma)), fontSize = textSize(u * 3 / 5), lineHeight = textSize(u))
                    if (showStore) {
                        StorePage(u, body)
                        return@Column
                    }
                    if (showAddons) {
                        AddonsPage(u, body)
                        return@Column
                    }
                    Text(when {
                            version.isEmpty() -> "Installed game version: unknown"
                            newVersion.isNotEmpty() -> "Installed game version: $version (new: $newVersion)"
                            else -> "Installed game version: $version"
                        }, style = body, color = GoldLight, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(u))
                    Text(status, style = body, color = TextColor, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().height(u * 2))
                    Spacer(Modifier.weight(1f))
                    val enabled = loaded && game != null
                    val play = remember { FocusRequester() }
                    // Five settings slots in one centred block; Play is big in the middle above them,
                    // with Update and Quit beside it.
                    val gap = u / 2
                    val sw = u * 22 / 5
                    val blockWidth = sw * 5 + gap * 4
                    val pw = u * 9
                    val side = (blockWidth - pw - gap * 2) / 2
                    Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) {
                        StartButton(null, "Update with Battle.net", u, side, enabled = enabled, onClick = ::update)
                        val playText = when (setup) {
                            Setup.INSTALL_BATTLE_NET -> if (pickInstaller) "Choose installer" else "Install Battle.net"
                            Setup.OPEN_BATTLE_NET -> "Open Battle.net"
                            else -> "Play"
                        }
                        StartButton(null, playText, u, pw, height = u * 13 / 5, fontSize = if (playText.length <= 8) u * 17 / 20 else u * 13 / 20,
                            play = true, enabled = if (setup == Setup.INSTALL_BATTLE_NET || setup == Setup.OPEN_BATTLE_NET) loaded else enabled,
                            focusRequester = play, onClick = when (setup) {
                                Setup.INSTALL_BATTLE_NET -> ::installBattleNet
                                Setup.OPEN_BATTLE_NET -> ::openBattleNet
                                else -> ::play
                            })
                        StartButton(null, "Quit", u, side, onClick = { finishAndRemoveTask() })
                    }
                    Spacer(Modifier.height(u))
                    // Five settings, the companion app among them.
                    Row(Modifier.align(Alignment.CenterHorizontally), horizontalArrangement = Arrangement.spacedBy(gap)) {
                        StartButton("FPS cap", if (fpsCap == 0) "Off" else fpsCap.toString(), u, sw, enabled = enabled, onClick = ::nextFpsCap)
                        StartButton("Overlay", OVERLAYS[overlay.coerceIn(0, OVERLAYS.size - 1)], u, sw, enabled = enabled, onClick = ::nextOverlay)
                        StartButton("Add-ons", addons.size.toString(), u, sw, enabled = enabled, onClick = { addonStatus = ""; showAddons = true })
                        StartButton("Companion", if (companionOn) "On" else "Off", u, sw, onClick = ::toggleCompanion)
                        StartButton("More", "Winlator", u, sw, onClick = { openWinlator() })
                    }
                    val setupReady = loaded && (setup == Setup.INSTALL_BATTLE_NET || setup == Setup.OPEN_BATTLE_NET)
                    LaunchedEffect(enabled, setupReady) { if (enabled || setupReady) runCatching { play.requestFocus() } }
                }
            }
        }
    }

    /** An add-on page's title on the left and its hint (or progress) on the right, in one line. */
    @Composable
    private fun PageTitle(title: String, hint: String, u: Dp, body: TextStyle) {
        // A long message (an install error) may take a second line.
        Row(Modifier.fillMaxWidth().heightIn(min = u), verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = GoldLight, fontFamily = FontFamily(Font(R.font.cinzel_bold, FontWeight.Bold)), fontSize = textSize(u * 13 / 20), maxLines = 1)
            Spacer(Modifier.width(u))
            Text(hint, style = body.copy(fontSize = textSize(u / 2), lineHeight = textSize(u * 11 / 20)), color = if (addonStatus.isEmpty()) Sky else TextColor,
                textAlign = TextAlign.End, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(u / 5))
    }

    /** The list of an add-on page: it takes all the room left, with a thin scroll bar on the right. */
    @Composable
    private fun ColumnScope.PageList(u: Dp, content: @Composable () -> Unit) {
        val scroll = rememberScrollState()
        val bar = u / 8
        Column(
            Modifier.fillMaxWidth().weight(1f)
                .drawWithContent {
                    drawContent()
                    if (scroll.maxValue > 0) {
                        val h = size.height
                        val thumb = h * h / (h + scroll.maxValue)
                        val x = size.width - bar.toPx()
                        drawRect(SkyDark.copy(alpha = 0.25f), Offset(x, 0f), Size(bar.toPx(), h))
                        drawRect(SkyDark, Offset(x, (h - thumb) * scroll.value / scroll.maxValue), Size(bar.toPx(), thumb))
                    }
                }
                .verticalScroll(scroll)
                .padding(end = bar * 3)
        ) { content() }
        Spacer(Modifier.height(u / 2))
    }

    /** The installed add-ons in a list, with Store, Install from file and Back below. */
    @Composable
    private fun ColumnScope.AddonsPage(u: Dp, body: TextStyle) {
        BackHandler { showAddons = false }
        val install = remember { FocusRequester() }
        PageTitle("Add-ons (${addons.size})", addonStatus.ifEmpty { "Download an add-on's .zip in your browser, then press Install from file." }, u, body)
        PageList(u) {
            if (addons.isEmpty()) Text("No add-ons installed.", style = body, color = Sky)
            addons.forEachIndexed { i, addon ->
                StoreRow(addon.title, addon.folder, "Uninstall", u, body, enabled = !installingAddon, stripe = i % 2 == 1) {
                    uninstallAddon(addon.folder, addon.title)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(u / 2)) {
            StartButton(null, "Store", u, u * 13 / 2, height = u * 7 / 5, fontSize = u * 11 / 20, play = true, focusRequester = install, onClick = ::openStore)
            StartButton(null, "Install from file", u, u * 13 / 2, height = u * 7 / 5, fontSize = u * 11 / 20,
                onClick = { addonPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) })
            StartButton(null, "Back", u, u * 13 / 2, height = u * 7 / 5, fontSize = u * 11 / 20, onClick = { showAddons = false })
        }
        LaunchedEffect(Unit) { runCatching { install.requestFocus() } }
    }

    /** The store's add-ons, one row each: Install or Uninstall on the right (tap or A). */
    @Composable
    private fun ColumnScope.StorePage(u: Dp, body: TextStyle) {
        BackHandler { showStore = false; addonStatus = "" }
        val back = remember { FocusRequester() }
        PageTitle("Add-on store", addonStatus.ifEmpty { "Add-ons with a version for your game. Tap Install or Uninstall on the right." }, u, body)
        PageList(u) {
            // Only add-ons with a file made for this game: others would only give false hope.
            val shown = storeEntries.filter { e -> storeOffers[e.repo].let { it == null || it.offer != null || it.error != null } }
            val wowi = wowiItems
            if (shown.isEmpty() && wowi != null && wowi.isEmpty() && wowiError == null)
                Text("No add-ons for your game version yet.", style = body, color = Sky)
            var row = 0
            for (entry in shown) {
                val installed = isInstalled(listOf(entry.folder))
                val state = storeOffers[entry.repo]
                val action = when {
                    installed -> "Uninstall"
                    state == null -> "Checking..."
                    state.offer != null -> "Install"
                    else -> "Not reachable"
                }
                StoreRow(entry.name, entry.description, action, u, body, enabled = !installingAddon && (installed || state?.offer != null), stripe = row++ % 2 == 1) {
                    if (installed) installedFolder(listOf(entry.folder))?.let { uninstallAddon(it, entry.name) } else installFromStore(entry)
                }
            }
            // WoWInterface: only add-ons whose own files were checked to be made for the game.
            when {
                wowi == null -> Text("Checking WoWInterface...", style = body, color = Sky)
                wowiError != null -> Text("WoWInterface is not reachable right now.", style = body, color = Sky)
                else -> {
                    for (item in wowi) {
                        val installed = isInstalled(item.folders)
                        StoreRow(item.title, "${item.version} by ${item.author} (WoWInterface)", if (installed) "Uninstall" else "Install",
                            u, body, enabled = !installingAddon, stripe = row++ % 2 == 1) {
                            if (installed) installedFolder(item.folders)?.let { uninstallAddon(it, item.title) } else installFromWowInterface(item)
                        }
                    }
                }
            }
        }
        StartButton(null, "Back", u, u * 13 / 2, height = u * 7 / 5, fontSize = u * 11 / 20, focusRequester = back, onClick = { showStore = false; addonStatus = "" })
        LaunchedEffect(Unit) { runCatching { back.requestFocus() } }
    }

    /**
     * One add-on: name and description, and on the right its action. Only the action reacts to
     * a tap, so swiping through the list never installs or uninstalls anything.
     */
    @Composable
    private fun StoreRow(name: String, description: String, action: String, u: Dp, body: TextStyle, enabled: Boolean, stripe: Boolean, onClick: () -> Unit) {
        var focused by remember { mutableStateOf(false) }
        val px = onePx()
        Row(
            Modifier.fillMaxWidth().height(u * 3 / 2)
                .background(if (stripe) Color.White.copy(alpha = 0.03f) else Color.Transparent)
                .padding(horizontal = u / 4),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = body.copy(lineHeight = textSize(u * 3 / 4)), color = GoldLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(description, style = body.copy(fontSize = textSize(u * 9 / 20), lineHeight = textSize(u * 3 / 5)), color = Sky,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.width(u / 2))
            Box(
                Modifier.width(u * 21 / 5).height(u * 11 / 10)
                    .onFocusChanged { focused = it.isFocused }
                    .onKeyEvent {
                        if (enabled && it.type == KeyEventType.KeyUp && it.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_BUTTON_A) {
                            onClick(); true
                        } else false
                    }
                    .clickable(enabled = enabled, onClick = onClick)
                    .border(px, if (focused) GoldLight else if (enabled) Edge else Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Text(action, style = body, color = if (action == "Install") Teal else TextColor, maxLines = 1)
            }
        }
    }

    /** One screen pixel, for the thin frames of the old design. */
    @Composable
    private fun onePx(): Dp = with(LocalDensity.current) { 1f.toDp() }

    @Composable
    private fun textSize(size: Dp): TextUnit = with(LocalDensity.current) { size.toSp() }

    /** Play in the teal of the Forever logo, the others in deep blue; settings show a small label over the value. */
    @Composable
    private fun StartButton(
        label: String?, text: String, u: Dp, width: Dp, height: Dp = u * 2, fontSize: Dp = u * 3 / 5,
        play: Boolean = false, enabled: Boolean = true, focusRequester: FocusRequester? = null, onClick: () -> Unit
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        var focused by remember { mutableStateOf(false) }
        var top = if (play) Teal else Color(37, 70, 102)
        var bottom = if (play) TealDark else Color(22, 48, 73)
        var edge = if (play) Gold else Edge
        var ink = if (play) Color.White else TextColor
        if (!enabled) { top = Color(44, 58, 72); bottom = Color(34, 46, 58); edge = Color(70, 88, 106); ink = Color(128, 146, 162) }
        if (pressed) { val t = top; top = bottom; bottom = t }
        if (focused && enabled) edge = GoldLight
        val heading = FontFamily(Font(R.font.cinzel_bold, FontWeight.Bold))
        val px = onePx()
        var modifier = Modifier.width(width).height(height)
        if (focusRequester != null) modifier = modifier.focusRequester(focusRequester)
        Box(
            modifier
                .onFocusChanged { focused = it.isFocused }
                .onKeyEvent {
                    // The Thor's A button.
                    if (enabled && it.type == KeyEventType.KeyUp && it.key.nativeKeyCode == AndroidKeyEvent.KEYCODE_BUTTON_A) {
                        onClick(); true
                    } else false
                }
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
                .background(Brush.verticalGradient(listOf(top, bottom)))
                .border(px, edge)
                .padding(px).border(px, Color(9, 24, 38)),
            contentAlignment = Alignment.Center
        ) {
            if (label != null) {
                // As in the old screen: the label at the bottom of the top 9/20, the value
                // centred in the next 9/20.
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxWidth().weight(9f), contentAlignment = Alignment.BottomCenter) {
                        Text(label, color = if (enabled) Sky else ink, fontFamily = heading, fontSize = textSize(u * 9 / 20))
                    }
                    Box(Modifier.fillMaxWidth().weight(9f), contentAlignment = Alignment.Center) {
                        Text(text, color = if (enabled) GoldLight else ink, fontFamily = heading, fontSize = textSize(fontSize))
                    }
                    Spacer(Modifier.weight(2f))
                }
            } else {
                Text(text, color = ink, fontFamily = heading, fontSize = textSize(fontSize),
                    textAlign = TextAlign.Center, lineHeight = textSize(fontSize * 23 / 20), modifier = Modifier.padding(horizontal = px * 4))
            }
        }
    }

    /** WORLD OF WARCRAFT, FOREVER, "on the AYN Thor" and the infinity swash on a sky gradient. */
    @Composable
    private fun Header(u: Dp, modifier: Modifier) {
        val measurer = rememberTextMeasurer()
        val heading = FontFamily(Font(R.font.cinzel_bold, FontWeight.Bold))
        Canvas(modifier) {
            val unit = u.toPx()
            drawRect(Brush.verticalGradient(listOf(HeaderTop, Background)))
            logoWord(measurer, "WORLD OF WARCRAFT", heading, unit * 21 / 20, unit / 12, size.width / 2, unit, unit, GoldMetal)
            val forever = logoWord(measurer, "FOREVER", heading, unit * 31 / 20, unit / 8, size.width / 2, unit * 46 / 20, unit, WhiteMetal)
            val device = TextStyle(fontFamily = heading, fontWeight = FontWeight.Bold, fontSize = (unit / 2).toSp(), letterSpacing = (unit / 40).toSp())
            val small = measurer.measure("on the AYN Thor", device)
            val x0 = size.width / 2 + forever.first / 2 + unit / 3
            val y0 = forever.second - small.firstBaseline
            drawText(small, color = Color(10, 22, 34), topLeft = Offset(x0 + 1f, y0 + 1f))
            drawText(small, color = Sky, topLeft = Offset(x0, y0))
            swash(size.width / 2, unit * 7 / 2, unit * 6 / 5, unit / 22)
            swashTails(size.width / 2, unit * 7 / 2, unit * 6 / 5 + unit / 10, unit * 2.4f, unit / 22 * 1.2f)
        }
    }

    /**
     * A word like the game's logo: metal gradient, a thin dark edge, a bronze outline and a
     * soft shadow below, as in the old start screen. Returns its width and baseline.
     */
    private fun DrawScope.logoWord(
        measurer: TextMeasurer, word: String, family: FontFamily, size: Float, spacing: Float,
        cx: Float, cy: Float, unit: Float, metal: List<Color>
    ): Pair<Float, Float> {
        val style = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = size.toSp(), letterSpacing = spacing.toSp())
        val layout = measurer.measure(word, style)
        val topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f)
        // Outline widths of the old screen at 1080p (u = 45 px): shadow 3 px, bronze 2.5, dark 1.5.
        val px = unit / 45
        drawText(layout, Color(10, 22, 34), topLeft + Offset(0f, 2 * px), drawStyle = Stroke(6 * px))
        drawText(layout, Color(138, 102, 52), topLeft, drawStyle = Stroke(5 * px))
        drawText(layout, Color(42, 26, 12), topLeft, drawStyle = Stroke(3 * px))
        // The brush is in the word's own coordinates: from its top (0) to its bottom.
        val h = layout.size.height.toFloat()
        // Fill must be named: the outline passes above leave Stroke set on the shared text paint.
        drawText(layout, Brush.verticalGradient(0f to metal[0], 0.5f to metal[1], 1f to metal[2], startY = 0f, endY = h), topLeft, drawStyle = Fill)
        return layout.size.width.toFloat() to (topLeft.y + layout.firstBaseline)
    }

    /** The infinity sign under FOREVER: thin where the strokes cross, full on the loops. */
    private fun DrawScope.swash(cx: Float, cy: Float, halfWidth: Float, thickness: Float) {
        val colors = listOf(Color(8, 22, 34), Color(214, 210, 200), Color.White)
        val steps = 720
        for (pass in 0..2) {
            val grow = if (pass == 0) thickness * 22 / 30 else 0f
            val shrink = if (pass == 2) 0.35f else 1f
            val lift = if (pass == 2) 0.35f else 0f
            for (i in 0 until steps) {
                val a = 2 * Math.PI * i / steps
                val s = sin(a); val c = cos(a); val d = 1 + s * s
                val x = cx + halfWidth * c / d
                val y = cy + halfWidth * 1.1 * s * c / d
                val r = thickness * (0.3 + 0.7 * abs(c))
                drawCircle(colors[pass], (r * shrink + grow).toFloat(), Offset(x.toFloat(), (y - r * lift).toFloat()))
            }
        }
    }

    /** The lines left and right of the infinity sign, tapering to a sharp point. */
    private fun DrawScope.swashTails(cx: Float, cy: Float, start: Float, length: Float, r0: Float) {
        val steps = 60
        for (side in listOf(-1f, 1f)) {
            for ((color, extra) in listOf(Color(8, 22, 34).copy(alpha = 0.8f) to 0.7f, Color(226, 222, 212) to 0f)) {
                val path = Path()
                for (i in 0..steps) {
                    val f = i / steps.toFloat()
                    val r = r0 * (1 - f).toDouble().pow(1.4).toFloat() + extra * (1 - f)
                    val x = cx + side * (start + f * length)
                    if (i == 0) path.moveTo(x, cy - r) else path.lineTo(x, cy - r)
                }
                for (i in steps downTo 0) {
                    val f = i / steps.toFloat()
                    val r = r0 * (1 - f).toDouble().pow(1.4).toFloat() + extra * (1 - f)
                    path.lineTo(cx + side * (start + f * length), cy + r)
                }
                path.close()
                drawPath(path, color)
            }
        }
    }

    companion object {
        private const val PREFS = "thor_start"
        private const val KEY_UPDATE_RAN = "update_ran"
        private const val BATTLE_NET_HINT = "Battle.net may show a white window for a minute while it loads. If it stays white, close it and try again."
        private const val EXTRA_FPS_LIMIT = "nativeFpsLimit"
        private val FPS_CAPS = listOf(0, 30, 40, 45, 60)
        private val OVERLAYS = listOf("Off", "FPS", "FPS + graph")

        private val Background = Color(16, 38, 59)
        private val HeaderTop = Color(65, 110, 151)
        private val Sky = Color(169, 207, 231)
        private val SkyDark = Color(131, 176, 206)
        private val Edge = Color(65, 110, 151)
        private val TextColor = Color(211, 230, 243)
        private val Teal = Color(52, 186, 215)
        private val TealDark = Color(32, 129, 165)
        private val Gold = Color(201, 168, 106)
        private val GoldLight = Color(241, 228, 194)
        private val GoldMetal = listOf(Color(255, 253, 242), Color(241, 228, 194), Color(176, 140, 84))
        private val WhiteMetal = listOf(Color(255, 255, 255), Color(244, 242, 236), Color(184, 180, 170))
    }
}
