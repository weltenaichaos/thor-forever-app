package com.winlator.cmod.ui.start

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import androidx.preference.PreferenceManager
import com.winlator.cmod.OnboardingActivity
import com.winlator.cmod.ui.settings.TestBuildUpdater.Note
import com.winlator.cmod.xenvironment.ImageFs
import com.winlator.cmod.xenvironment.ImageFsInstaller
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Pictures of the start screen on the Thor's top screen (1920 x 1080), with the page that
 * offers a new version of the app. Runs with the companion's pictures: -PcompanionScreens=<folder>.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [33], qualifiers = "w873dp-h491dp-land-352dpi")
class StartScreenShotsTest {

    private val dir = File(System.getProperty("companion.screens.dir") ?: "build/companion-screens").apply { mkdirs() }

    private val notes = listOf(
        Note("The Forever Companion:", Note.Kind.GROUP),
        Note("Round buttons again, in the colours of the start screen.", Note.Kind.POINT),
        Note("Names in chat have their class colour.", Note.Kind.POINT),
        Note("The equipment boxes all have the same height.", Note.Kind.POINT),
        Note("The app:", Note.Kind.GROUP),
        Note("The start screen says when a new version of the app is out, and installs it with one tap.", Note.Kind.POINT)
    )

    private fun shoot(name: String, setUp: ThorStartActivity.() -> Unit = {}) {
        // Past the first setup, so the start screen shows instead of Winlator's.
        val app = RuntimeEnvironment.getApplication()
        ImageFs.find(app).createImgVersionFile(ImageFsInstaller.LATEST_VERSION.toInt())
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(OnboardingActivity.PREF_ONBOARDING_COMPLETE, true).commit()
        val controller = Robolectric.buildActivity(ThorStartActivity::class.java).setup()
        val activity = controller.get()
        activity.setUp()
        // The screen's own loading runs on a thread: give it a moment, then draw.
        repeat(20) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(50) }
        val root = activity.window.decorView
        val w = 1920; val h = 1080
        root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, w, h)
        shadowOf(Looper.getMainLooper()).idle()
        val picture = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(picture))
        File(dir, "$name.png").outputStream().use { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    private val hint = "You have 0.4.0. Your game, add-ons and settings stay."

    @Test fun start() = shoot("start-1-screen")

    /** Only the small button at the top right and a line in the middle: nothing opens by itself. */
    @Test fun newVersion() = shoot("start-2-new-version") {
        previewAppUpdate("0.5.0", "Thor Forever 0.5.0", hint, notes, page = false)
    }

    @Test fun updatePage() = shoot("start-3-update-page") {
        previewAppUpdate("0.5.0", "Thor Forever 0.5.0", hint, notes, page = true)
    }

    /** Back from the page while it downloads: the button shows how far it is. */
    @Test fun downloading() = shoot("start-4-downloading") {
        previewAppUpdate("0.5.0", "Thor Forever 0.5.0", hint, notes, page = false, progress = 0.42f)
    }
}
