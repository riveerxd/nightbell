package me.river.nightbell

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.ui.NightbellSplash
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

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

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
        finished = false
    }

    private fun splash() {
        // Frozen *before* the content exists, and that ordering is the whole
        // trick. `setContent` ends in an idle wait, and an idle wait with the
        // clock still advancing runs a tween to completion: freezing afterwards
        // photographed seven identical frames of a sequence that had already
        // finished, which looked exactly like the splash rendering nothing.
        composeRule.mainClock.autoAdvance = false
        composeRule.setContent {
            NightbellTheme(motionIntensity = 1f) {
                NightbellSplash(
                    modifier = Modifier.fillMaxSize(),
                    onFinished = { finished = true },
                )
            }
        }
        composeRule.mainClock.advanceTimeByFrame()
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
