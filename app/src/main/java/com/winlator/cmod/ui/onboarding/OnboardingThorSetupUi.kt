package com.winlator.cmod.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import com.winlator.cmod.R

private val Background = Color(16, 38, 59)
private val HeaderTop = Color(65, 110, 151)
private val Sky = Color(169, 207, 231)
private val TextColor = Color(211, 230, 243)
private val Teal = Color(52, 186, 215)
private val TealDark = Color(32, 129, 165)
private val Gold = Color(201, 168, 106)
private val GoldLight = Color(241, 228, 194)
private val Edge = Color(65, 110, 151)

/**
 * First start, in the look of the Thor Forever start screen: installs the recommended setup
 * (our Wine, DXVK and Turnip, all inside the app) with a loading bar and a line saying what
 * it is doing. "Choose components myself" opens the normal component screen instead.
 */
@Composable
internal fun OnboardingThorSetupScreen(
    coreReady: Boolean,
    coreProgress: Int,
    label: String?,
    progress: Int,
    done: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onContinue: () -> Unit,
    onChooseComponents: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        val u = (maxHeight / 24).coerceAtLeast(16.dp)
        val panelWidth = (maxWidth * 3 / 5).coerceAtLeast(u * 24).coerceAtMost(maxWidth)
        val px = with(LocalDensity.current) { 1f.toDp() }
        val heading = FontFamily(Font(R.font.cinzel_bold, FontWeight.Bold))
        val density = LocalDensity.current
        fun sp(d: Dp) = with(density) { d.toSp() }
        val body = TextStyle(fontFamily = FontFamily(Font(R.font.wine_tahoma)), fontSize = sp(u * 2 / 3))
        Column(
            Modifier.width(panelWidth)
                .background(Brush.verticalGradient(0f to HeaderTop, 0.35f to Background))
                .border(px, Gold).padding(px).border(px, Color(74, 52, 24))
                .padding(horizontal = u, vertical = u * 3 / 4),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("WORLD OF WARCRAFT", fontFamily = heading, color = GoldLight, fontSize = sp(u))
            Text("FOREVER", fontFamily = heading, color = Color.White, fontSize = sp(u * 3 / 2))
            Text("Setting up the AYN Thor", fontFamily = heading, color = Sky, fontSize = sp(u / 2))
            Spacer(Modifier.height(u * 3 / 4))
            val text = when {
                error != null -> error
                done -> "Everything is installed. Press Continue."
                !coreReady -> "Installing the system files"
                label != null -> label
                else -> "Getting ready"
            }
            val shown = if (error == null && !done) {
                val p = if (!coreReady) coreProgress else progress
                if (p >= 0) "$text... $p%" else "$text..."
            } else text
            Text(shown, style = body, color = if (error != null) Color(255, 170, 150) else TextColor, textAlign = TextAlign.Center)
            Spacer(Modifier.height(u / 2))
            LoadingBar(if (done) 100 else if (!coreReady) coreProgress else progress, error != null, u, px)
            Spacer(Modifier.height(u / 3))
            Text("Wine 11 (ARM64) · DXVK 2.6.2 Thor · Turnip 26.2.3", style = body.copy(fontSize = sp(u / 2)), color = Sky)
            Spacer(Modifier.height(u * 3 / 4))
            Row(horizontalArrangement = Arrangement.spacedBy(u)) {
                val bw = (panelWidth - u * 3) / 2
                SetupButton("Choose components myself", false, true, u, bw, px, onChooseComponents)
                if (error != null) SetupButton("Try again", true, true, u, bw, px, onRetry)
                else SetupButton("Continue", true, done, u, bw, px, onContinue)
            }
        }
    }
}

/** A gold-framed bar in the logo's teal; it sweeps while the size of a step is unknown. */
@Composable
private fun LoadingBar(progress: Int, failed: Boolean, u: Dp, px: Dp) {
    Box(Modifier.fillMaxWidth().height(u * 2 / 3).background(Color(9, 24, 38)).border(px, Gold).padding(px * 2)) {
        if (failed) return@Box
        if (progress >= 0) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(progress.coerceIn(0, 100) / 100f)
                .background(Brush.verticalGradient(listOf(Teal, TealDark))))
        } else {
            val sweep by rememberInfiniteTransition(label = "sweep").animateFloat(
                0f, 1f, infiniteRepeatable(tween(1200), RepeatMode.Reverse), label = "sweep"
            )
            Row(Modifier.fillMaxSize()) {
                if (sweep > 0.01f) Spacer(Modifier.fillMaxHeight().weight(sweep * 3))
                Box(Modifier.fillMaxHeight().weight(1f).background(Brush.verticalGradient(listOf(Teal, TealDark))))
                if (sweep < 0.99f) Spacer(Modifier.fillMaxHeight().weight((1 - sweep) * 3))
            }
        }
    }
}

@Composable
private fun SetupButton(text: String, main: Boolean, enabled: Boolean, u: Dp, width: Dp, px: Dp, onClick: () -> Unit) {
    var top = if (main) Teal else Color(37, 70, 102)
    var bottom = if (main) TealDark else Color(22, 48, 73)
    var ink = if (main) Color.White else TextColor
    var edge = if (main) Gold else Edge
    if (!enabled) { top = Color(44, 58, 72); bottom = Color(34, 46, 58); ink = Color(128, 146, 162); edge = Color(70, 88, 106) }
    Box(
        Modifier.width(width).height(u * 2)
            .clickable(enabled = enabled, onClick = onClick)
            .background(Brush.verticalGradient(listOf(top, bottom)))
            .border(px, edge),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = ink, fontFamily = FontFamily(Font(R.font.cinzel_bold, FontWeight.Bold)),
            fontSize = with(LocalDensity.current) { (u / 2).toSp() }, textAlign = TextAlign.Center)
    }
}
