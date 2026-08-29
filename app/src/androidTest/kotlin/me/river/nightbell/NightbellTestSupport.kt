package me.river.nightbell

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.GlobalSettings
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
        val file = File(screenshotDir(), "$name.png")
        val encoded = file.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        // `Bitmap.compress` reports failure by returning false rather than by
        // throwing, and the stream has already created the file by then. Eight of
        // these were landing as 0-byte PNGs while their tests reported OK, which
        // is the exact shape of a screenshot assertion that proves nothing: the
        // run is green and there is no picture behind it.
        check(encoded) { "compress() refused to encode $name (${bitmap.width}x${bitmap.height})" }
        check(file.length() > 0L) { "$name.png was written empty" }
    }

    /**
     * The whole screen, as the device composited it.
     *
     * [captureScreenshot] renders one Compose window, which is the right tool
     * until a dialog is open. Then there are two roots and `onRoot` cannot say
     * which is meant; and even resolved, it would draw the modal on a
     * transparent ground with none of the dimmed, blurred dashboard behind it,
     * which is the half worth looking at. This asks the system for the composited
     * frame instead, so a screenshot of a modal shows what a person would see.
     */
    fun ComposeTestRule.captureDeviceScreenshot(name: String) {
        waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val file = File(screenshotDir(), "$name.png")
        val encoded = file.outputStream().use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
        // Same guard as above, and this path has its own way to produce nothing:
        // `takeScreenshot` can hand back a bitmap the platform failed to fill.
        check(encoded) { "compress() refused to encode $name (${bitmap.width}x${bitmap.height})" }
        check(file.length() > 0L) { "$name.png was written empty" }
    }

    /**
     * Switches Settings to one of its tabs.
     *
     * Settings is four pages behind a tab bar, so "scroll the settings list to X"
     * only means something once the page holding X is on screen. Named by the
     * tab's spoken label rather than by an index, so a reordered bar does not
     * silently drive the wrong page.
     */
    fun ComposeTestRule.openSettingsTab(label: String) {
        onNodeWithContentDescription("$label tab").performClick()
        waitForIdle()
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
