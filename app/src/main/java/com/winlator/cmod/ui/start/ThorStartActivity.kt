package com.winlator.cmod.ui.start

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent as AndroidKeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.winlator.cmod.MainActivity
import com.winlator.cmod.R
import com.winlator.cmod.XServerDisplayActivity
import com.winlator.cmod.container.ContainerManager
import com.winlator.cmod.core.ThorGame
import com.winlator.cmod.xenvironment.ImageFs
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * The Thor Forever start screen, the first thing the app shows: the installed game version,
 * Play, Update with Battle.net and a few settings, in the design of the old Windows start
 * screen (colours after World of Warcraft: Forever, Cinzel font; no game artwork). Play and
 * Update start a session; when it ends the app comes back here.
 */
class ThorStartActivity : ComponentActivity() {
    private var game by mutableStateOf<ThorGame?>(null)
    private var loaded by mutableStateOf(false)
    private var version by mutableStateOf("")
    private var status by mutableStateOf("")
    private var fpsCap by mutableStateOf(0)
    private var overlay by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Before the first setup, and when the app restarts itself onto one of its own
        // pages, the normal Winlator screen takes over.
        val menuItem = intent.getIntExtra("selected_menu_item_id", 0)
        if (menuItem > 0 || !ImageFs.find(this).isValid) {
            openWinlator(menuItem)
            finish()
            return
        }
        setContent { StartScreen() }
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing) load()
    }

    private fun load() {
        Thread {
            val found = runCatching { ThorGame.find(ContainerManager(this)) }.getOrNull()
            val installed = runCatching { found?.installedVersion() ?: "" }.getOrDefault("")
            runOnUiThread { show(found, installed) }
        }.start()
    }

    private fun show(found: ThorGame?, installed: String) {
        run {
            game = found
            version = installed
            fpsCap = found?.shortcut?.getExtra(EXTRA_FPS_LIMIT, "0")?.toIntOrNull() ?: 0
            overlay = found?.shortcut?.container?.getExtra("hudMode", "")?.toIntOrNull()
                ?: if (found?.shortcut?.container?.isShowFPS == true) 1 else 0
            val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            status = when {
                found == null -> "No World of Warcraft shortcut found. Add one in Winlator (More), then come back."
                prefs.getBoolean(KEY_UPDATE_RAN, false) -> "Battle.net is closed. The version above is now installed."
                else -> "Press Update with Battle.net to check for a game update."
            }
            prefs.edit().putBoolean(KEY_UPDATE_RAN, false).apply()
            loaded = true
        }
    }

    private fun play() {
        val g = game ?: return
        startActivity(Intent(this, XServerDisplayActivity::class.java)
            .putExtra("container_id", g.shortcut.container.id)
            .putExtra("shortcut_path", g.shortcut.file.path))
    }

    private fun update() {
        val g = game ?: return
        val battleNet = g.battleNetPath()
        if (battleNet == null) {
            Toast.makeText(this, "Battle.net was not found in this container.", Toast.LENGTH_LONG).show()
            return
        }
        val shortcut = try {
            g.writeBattleNetShortcut(this, battleNet)
        } catch (e: Exception) {
            Toast.makeText(this, "Could not start Battle.net: ${e.message}", Toast.LENGTH_LONG).show()
            return
        }
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_UPDATE_RAN, true).apply()
        status = "Starting Battle.net... Press Update there if it offers one, then close Battle.net."
        startActivity(Intent(this, XServerDisplayActivity::class.java)
            .putExtra("container_id", g.shortcut.container.id)
            .putExtra("shortcut_path", shortcut.path)
            .putExtra(ThorGame.EXTRA_UPDATE_SESSION, true))
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
            val u = (maxHeight / 24).coerceAtLeast(16.dp)
            val panelWidth = (maxWidth * 3 / 5).coerceAtLeast(u * 24).coerceAtMost(maxWidth)
            val headerHeight = u * 17 / 4
            Box(
                Modifier.width(panelWidth).height(headerHeight + u * 11)
                    .background(Background)
                    .border(1.dp, Gold)
                    .padding(1.dp).border(1.dp, Color(74, 52, 24))
            ) {
                Header(u, Modifier.fillMaxWidth().height(headerHeight))
                Box(Modifier.fillMaxSize().padding(4.dp).border(1.dp, SkyDark))
                Column(Modifier.fillMaxSize().padding(start = u, end = u, top = headerHeight + u / 2)) {
                    val body = TextStyle(fontSize = textSize(u * 2 / 3), lineHeight = textSize(u))
                    Text(if (version.isEmpty()) "Installed game version: unknown" else "Installed game version: $version",
                        style = body, color = GoldLight, modifier = Modifier.height(u))
                    Text(status, style = body, color = TextColor, modifier = Modifier.height(u * 2))
                    Spacer(Modifier.height(u))
                    val enabled = loaded && game != null
                    val play = remember { FocusRequester() }
                    val bw = (panelWidth - u * 4) / 3
                    Row(horizontalArrangement = Arrangement.spacedBy(u)) {
                        StartButton("FPS cap", if (fpsCap == 0) "Off" else fpsCap.toString(), u, bw, enabled = enabled, onClick = ::nextFpsCap)
                        StartButton("Overlay", OVERLAYS[overlay.coerceIn(0, OVERLAYS.size - 1)], u, bw, enabled = enabled, onClick = ::nextOverlay)
                        StartButton("More", "Winlator", u, bw, onClick = { openWinlator() })
                    }
                    Spacer(Modifier.height(u / 2))
                    Row(horizontalArrangement = Arrangement.spacedBy(u)) {
                        StartButton(null, "Play", u, bw, play = true, enabled = enabled, focusRequester = play, onClick = ::play)
                        StartButton(null, "Update with Battle.net", u, bw, enabled = enabled, onClick = ::update)
                        StartButton(null, "Quit", u, bw, onClick = { finishAndRemoveTask() })
                    }
                    LaunchedEffect(enabled) { if (enabled) runCatching { play.requestFocus() } }
                }
            }
        }
    }

    @Composable
    private fun textSize(size: Dp): TextUnit = with(LocalDensity.current) { size.toSp() }

    /** Play in the teal of the Forever logo, the others in deep blue; settings show a small label over the value. */
    @Composable
    private fun StartButton(
        label: String?, text: String, u: Dp, width: Dp, play: Boolean = false, enabled: Boolean = true,
        focusRequester: FocusRequester? = null, onClick: () -> Unit
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
        var modifier = Modifier.width(width).height(u * 5 / 2)
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
                .border(1.dp, edge)
                .padding(1.dp).border(1.dp, Color(9, 24, 38)),
            contentAlignment = Alignment.Center
        ) {
            if (label != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, color = if (enabled) Sky else ink, fontFamily = heading, fontSize = textSize(u / 2))
                    Text(text, color = if (enabled) GoldLight else ink, fontFamily = heading, fontSize = textSize(u * 2 / 3))
                }
            } else {
                Text(text, color = ink, fontFamily = heading, fontSize = textSize(u * 2 / 3),
                    textAlign = TextAlign.Center, lineHeight = textSize(u * 9 / 10), modifier = Modifier.padding(horizontal = 4.dp))
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
            logoWord(measurer, "WORLD OF WARCRAFT", heading, unit * 21 / 20, unit / 12, size.width / 2, unit, GoldMetal)
            val forever = logoWord(measurer, "FOREVER", heading, unit * 31 / 20, unit / 8, size.width / 2, unit * 46 / 20, WhiteMetal)
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

    /** A word like the game's logo: metal gradient, bronze outline, soft shadow. Returns width and baseline. */
    private fun DrawScope.logoWord(
        measurer: TextMeasurer, word: String, family: FontFamily, size: Float, spacing: Float,
        cx: Float, cy: Float, metal: List<Color>
    ): Pair<Float, Float> {
        val base = TextStyle(fontFamily = family, fontWeight = FontWeight.Bold, fontSize = size.toSp(), letterSpacing = spacing.toSp())
        val layout = measurer.measure(word, base)
        val topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f)
        val px = size / 30
        drawText(measurer, word, topLeft + Offset(0f, px * 1.2f), base.copy(color = Color(10, 22, 34), drawStyle = Stroke(px * 3f)))
        drawText(measurer, word, topLeft, base.copy(color = Color(138, 102, 52), drawStyle = Stroke(px * 2.2f)))
        drawText(measurer, word, topLeft, base.copy(color = Color(42, 26, 12), drawStyle = Stroke(px * 1.1f)))
        val top = topLeft.y
        val h = layout.size.height.toFloat()
        drawText(measurer, word, topLeft, base.copy(brush = Brush.verticalGradient(
            0f to metal[0], 0.5f to metal[1], 1f to metal[2], startY = top, endY = top + h)))
        return layout.size.width.toFloat() to (topLeft.y + layout.firstBaseline)
    }

    /** The infinity sign under FOREVER: thin where the strokes cross, full on the loops. */
    private fun DrawScope.swash(cx: Float, cy: Float, halfWidth: Float, thickness: Float) {
        val colors = listOf(Color(8, 22, 34), Color(214, 210, 200), Color.White)
        val steps = 720
        for (pass in 0..2) {
            val grow = if (pass == 0) 0.6f else 0f
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

    /** The lines left and right of the infinity sign, tapering to a point. */
    private fun DrawScope.swashTails(cx: Float, cy: Float, start: Float, length: Float, r0: Float) {
        val steps = 120
        for (side in listOf(-1f, 1f)) for (i in 0 until steps) {
            val f = i / steps.toFloat()
            val r = r0 * (1 - f).toDouble().pow(1.4).toFloat()
            val x = cx + side * (start + f * length)
            drawCircle(Color(8, 22, 34).copy(alpha = 0.8f), r + 0.7f * (1 - f), Offset(x, cy))
            drawCircle(Color(226, 222, 212), r, Offset(x, cy))
        }
    }

    companion object {
        private const val PREFS = "thor_start"
        private const val KEY_UPDATE_RAN = "update_ran"
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
