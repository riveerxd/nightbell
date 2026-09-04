package me.river.nightbell

import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.ui.NightbellSplash
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.After
import androidx.test.platform.app.InstrumentationRegistry

/**
 * The opening sequence, photographed at the millisecond each beat is supposed to
 * be at.
 *
 * Written when the total was cut from 2800ms to 1750ms, because a duration is the
 * one thing about an animation that cannot be checked by looking at a still, and
 * because every tuning note in `NightbellSplash` had been written while watching
 * it on a phone whose developer-options animator scale was 0.5. The sequence is a
 * Compose `tween`, so the platform halved it: the person tuning it saw 1400ms and
 * everyone else saw 2800ms.
 *
 * What this pins is that each beat still has something to show when its window
 * opens, and that the sequence hands over. The phase table it is checking against:
 *
 *  - 0 to 245ms, the bell arrives
 *  - 193 to 805ms, the strike and its two swings
 *  - 525 to 980ms, the word wipes in
 *  - 980 to 1435ms, the finished lockup holds
 *  - 1435 to 1750ms, it fades to the app
 *
 * The clock is stepped by hand. Left to itself the test framework would run the
 * whole tween inside the first idle wait, which is the same trap the toast host
 * sits in, and every frame would be photographed after the sequence had ended.
 */
@RunWith(AndroidJUnit4::class)
class SplashTimingInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var finished = false
    private var started by mutableStateOf(false)

    /**
     * The device's own animator setting, which this class cannot leave to chance.
     *
     * `NightbellSplash` reads `Settings.Global.ANIMATOR_DURATION_SCALE` directly
     * and hands over on the first frame when it is zero, because a splash is
     * decoration and that setting is asking for none. Correct behaviour, and fatal
     * to a test about the sequence: a device with animations off skips every beat
     * and the assertions here would be measuring an early return. nb_agent comes
     * up with the scale at 0 often enough that this is not hypothetical.
     */
    private var restoreScale: String = "1.0"

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
        finished = false
        restoreScale = shell("settings get global animator_duration_scale").trim()
            .ifBlank { "1.0" }
            .let { if (it == "null") "1.0" else it }
        shell("settings put global animator_duration_scale 1.0")
    }

    @After
    fun tearDown() {
        shell("settings put global animator_duration_scale $restoreScale")
    }

    private fun shell(command: String): String {
        val fd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).use {
            it.readBytes().toString(Charsets.UTF_8)
        }
    }

    /**
     * Lays the window out first, then freezes the clock, then mounts the splash.
     *
     * Both halves of that order were paid for. Freezing *after* `setContent` runs
     * the whole tween inside its idle wait, so every frame is photographed after
     * the sequence has ended: seven identical stills of nothing. Freezing before
     * it, which was the fix for that, leaves the Compose view itself unmeasured,
     * because the platform's measure and layout pass is driven by real frames and
     * a stopped test clock does not produce any. `captureToImage` then asks for a
     * zero by zero bitmap and throws "width and height must be > 0". That version
     * passed when run on its own and failed inside a two device Gradle run, which
     * is the signature of a test that depends on frames arriving by luck.
     *
     * Mounting the splash behind a flag settles it. The tree composes and lays out
     * with the clock running and nothing to animate, the clock stops, and only
     * then does the sequence begin, at a known t=0 in a window that already has a
     * size.
     */
    private fun splash() {
        composeRule.setContent {
            NightbellTheme(motionIntensity = 1f) {
                Box(Modifier.fillMaxSize()) {
                    if (started) {
                        NightbellSplash(
                            modifier = Modifier.fillMaxSize(),
                            onFinished = { finished = true },
                        )
                    }
                }
            }
        }
        composeRule.waitForIdle()
        composeRule.mainClock.autoAdvance = false
        started = true
        composeRule.mainClock.advanceTimeByFrame()
        // Proven, not assumed. A zero sized root is the failure this ordering
        // exists to prevent, and it should say so rather than throwing out of
        // Bitmap.createBitmap several frames later.
        val root = composeRule.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "the window must be laid out before any capture, got ${root.width} x ${root.height}",
            root.width > 0.dp && root.height > 0.dp,
        )
    }

    @Test
    fun everyBeatHasSomethingToShowWhenItsWindowOpens() {
        splash()
        var at = 0L
        // One frame inside each window rather than on its edge, so a capture
        // cannot land on the frame before a beat starts.
        listOf(60L, 220L, 500L, 700L, 900L, 1_100L, 1_500L).forEach { t ->
            composeRule.mainClock.advanceTimeBy(t - at)
            at = t
            composeRule.captureScreenshot("splash-%04d".format(t))
        }
        assertTrue("the sequence must not have handed over mid-word", !finished)

        // Past the end, where it has to be gone rather than holding a still.
        composeRule.mainClock.advanceTimeBy(400)
        composeRule.mainClock.autoAdvance = true
        composeRule.waitForIdle()
        assertTrue("the splash must hand over by 1750ms", finished)
    }
}
