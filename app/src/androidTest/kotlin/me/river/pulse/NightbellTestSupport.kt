package me.river.pulse

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onRoot
import androidx.test.platform.app.InstrumentationRegistry
import me.river.pulse.data.Nightbell
import me.river.pulse.data.NightbellSnapshot
import me.river.pulse.domain.GlobalSettings
import java.io.File
import kotlinx.coroutines.runBlocking

/** Shared helpers for the on-device suite. */
object NightbellTestSupport {

    val appContext: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * Wipes persisted state and pins motion to zero.
     *
     * Reduced motion is not cosmetic here: Nightbell's looping animations keep the
     * Compose frame clock busy forever, so the test framework would never see an
     * idle composition. `rememberLoopingFloat` collapses to a constant at
     * intensity 0, which makes the UI synchronisable.
     */
    fun resetApp(settings: GlobalSettings = GlobalSettings(motionIntensity = 0f)) {
        val graph = Nightbell.install(appContext)
        runBlocking {
            graph.store.replaceAll(
                NightbellSnapshot(
                    // Past the pager-setup gate unless a test says otherwise.
                    // That screen stands in front of the dashboard while any grant
                    // is missing, and on an emulator several always are, so every
                    // UI suite would otherwise be asserting against it instead of
                    // the app. `PagerSetupInstrumentedTest` opts back in.
                    settings = settings.copy(hasSeenPagerSetup = true),
                ),
            )
        }
    }

    /** Resets *into* the pager-setup gate, for the tests that are about it. */
    fun resetAppAtPagerSetup(settings: GlobalSettings = GlobalSettings(motionIntensity = 0f)) {
        val graph = Nightbell.install(appContext)
        runBlocking {
            graph.store.replaceAll(
                NightbellSnapshot(settings = settings.copy(hasSeenPagerSetup = false)),
            )
        }
    }

    /**
     * Internal storage on purpose: `adb shell` cannot list another app's
     * `Android/data` directory on API 30+, but `run-as` can always read the
     * internal files dir of a debuggable package.
     */
    fun screenshotDir(): File =
        File(appContext.filesDir, "screenshots").apply { mkdirs() }

    fun ComposeTestRule.captureScreenshot(name: String) {
        waitForIdle()
        val bitmap: Bitmap = onRoot().captureToImage().asAndroidBitmap()
        File(screenshotDir(), "$name.png").outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    /** Polls until [condition] holds, failing with a readable message on timeout. */
    fun awaitTrue(timeoutMs: Long = 15_000, description: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(120)
        }
        throw AssertionError("Timed out after ${timeoutMs}ms waiting for: $description")
    }
}
