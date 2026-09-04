package me.river.nightbell

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.data.alerts.UrgentAlarm
import me.river.nightbell.domain.VibrationStyle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Two threads ending the same page.
 *
 * Not hypothetical. Acknowledging calls `alarm.stop()` from whichever thread the
 * tap arrived on, deliberately, so the phone goes quiet at once instead of at the
 * service loop's next tick; the loop then stops it again when it sees nothing is
 * paging. Both read the same player, and the loser called `isPlaying` on an
 * instance the winner had already released. It was caught and logged, so every
 * acknowledged page wrote an `IllegalStateException` into logcat and no test ever
 * went red.
 *
 * Ten threads rather than two: the window is a few instructions wide, and a
 * two-thread version of this passed on the unfixed code about half the time.
 */
@RunWith(AndroidJUnit4::class)
class UrgentAlarmRaceInstrumentedTest {

    @Test
    fun stoppingATwiceOverDoesNotFaultThePlayer() {
        val alarm = UrgentAlarm(appContext)
        try {
            repeat(5) {
                alarm.start(style = VibrationStyle.TICK, vibrate = false, respectRinger = false)
                if (!alarm.isPlaying) {
                    // Some emulator system images ship no media behind
                    // `DEFAULT_ALARM_ALERT_URI`, so there is no player to race.
                    Log.w(TAG, "Skipping: this device has no alarm tone")
                    return
                }
                val go = CountDownLatch(1)
                val done = CountDownLatch(THREADS)
                repeat(THREADS) {
                    Thread {
                        go.await()
                        alarm.stop()
                        done.countDown()
                    }.start()
                }
                go.countDown()
                assertTrue("stops did not finish", done.await(10, TimeUnit.SECONDS))
                assertTrue("the siren is still playing", !alarm.isPlaying)
            }
            assertEquals("a player was released twice", 0, alarm.stopFaults)
        } finally {
            alarm.stop()
        }
    }

    /** Ducking races the same teardown, from the speaker's `finally`. */
    @Test
    fun duckingWhileTheSirenIsBeingTornDownIsSafe() {
        val alarm = UrgentAlarm(appContext)
        try {
            repeat(5) {
                alarm.start(style = VibrationStyle.TICK, vibrate = false, respectRinger = false)
                if (!alarm.isPlaying) {
                    Log.w(TAG, "Skipping: this device has no alarm tone")
                    return
                }
                val go = CountDownLatch(1)
                val done = CountDownLatch(2)
                Thread {
                    go.await()
                    alarm.setDucked(true)
                    alarm.setDucked(false)
                    done.countDown()
                }.start()
                Thread {
                    go.await()
                    alarm.stop()
                    done.countDown()
                }.start()
                go.countDown()
                assertTrue("threads did not finish", done.await(10, TimeUnit.SECONDS))
            }
            assertEquals("the player faulted", 0, alarm.stopFaults)
            assertTrue("ducking outlived the siren", !alarm.isDucked)
        } finally {
            alarm.stop()
        }
    }

    private companion object {
        const val THREADS = 10
        const val TAG = "UrgentAlarmRaceTest"
    }
}
