package me.river.nightbell

import me.river.nightbell.domain.CheckerFacts
import me.river.nightbell.domain.CheckerLimit
import me.river.nightbell.domain.CheckerLimits
import me.river.nightbell.domain.MonitorCadence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The middle category the app was missing: reasons checks run late that are real,
 * worth *showing*, and never worth a notification.
 *
 * Before 1.6.0 there were only two verdicts available — the monitor is fine, or
 * the monitor is down — so Doze deferring work, battery saver and a stopped
 * service all had to be squeezed into one of them, and they were squeezed into
 * *down*. These are the states that now have somewhere honest to live.
 */
class CheckerLimitsTest {

    private val minute = 60_000L

    private val healthy = CheckerFacts(
        backgroundChecksEnabled = true,
        enabledMonitors = 3,
        online = true,
        unmeteredOnly = false,
        onUnmeteredNetwork = true,
        backgroundRestricted = false,
        powerSaveMode = false,
        ignoringBatteryOptimizations = true,
        strictMode = false,
        cadences = listOf(MonitorCadence(intervalMinutes = 15, ageMs = 5 * minute)),
    )

    @Test
    fun `a healthy fleet reports no limit at all`() {
        assertEquals(CheckerLimit.NONE, CheckerLimits.diagnose(healthy))
        assertFalse(CheckerLimit.NONE.isLimited)
    }

    @Test
    fun `the user's own switch outranks everything else`() {
        // If they turned background checks off, that is the answer — telling them
        // about Doze instead would be technically true and completely unhelpful.
        val facts = healthy.copy(
            backgroundChecksEnabled = false,
            online = false,
            powerSaveMode = true,
            backgroundRestricted = true,
            cadences = listOf(MonitorCadence(15, 10 * 60 * minute)),
        )
        assertEquals(CheckerLimit.BACKGROUND_CHECKS_OFF, CheckerLimits.diagnose(facts))
    }

    @Test
    fun `no enabled monitor is reported before anything about the platform`() {
        val facts = healthy.copy(enabledMonitors = 0, powerSaveMode = true, online = false)
        assertEquals(CheckerLimit.NO_ENABLED_MONITORS, CheckerLimits.diagnose(facts))
    }

    @Test
    fun `offline outranks power and lateness`() {
        val facts = healthy.copy(
            online = false,
            powerSaveMode = true,
            cadences = listOf(MonitorCadence(15, 10 * 60 * minute)),
        )
        assertEquals(CheckerLimit.OFFLINE, CheckerLimits.diagnose(facts))
    }

    @Test
    fun `wifi-only on metered data is its own explanation`() {
        val facts = healthy.copy(unmeteredOnly = true, onUnmeteredNetwork = false)
        assertEquals(CheckerLimit.METERED_BLOCKED, CheckerLimits.diagnose(facts))

        // …and not reported when the setting is off.
        assertEquals(
            CheckerLimit.NONE,
            CheckerLimits.diagnose(healthy.copy(unmeteredOnly = false, onUnmeteredNetwork = false)),
        )
    }

    @Test
    fun `background restriction outranks battery saver`() {
        val facts = healthy.copy(backgroundRestricted = true, powerSaveMode = true)
        assertEquals(CheckerLimit.BACKGROUND_RESTRICTED, CheckerLimits.diagnose(facts))
    }

    @Test
    fun `battery saver is not blamed while strict mode is running`() {
        // A foreground service is not deferrable work, so saying battery saver is
        // delaying it would simply be wrong.
        val saver = healthy.copy(powerSaveMode = true)
        assertEquals(CheckerLimit.BATTERY_SAVER, CheckerLimits.diagnose(saver))
        assertEquals(CheckerLimit.NONE, CheckerLimits.diagnose(saver.copy(strictMode = true)))
    }

    @Test
    fun `strict mode means checks are running even with background checks off`() {
        // The two toggles are independent: nothing gates the strict switch on
        // `backgroundChecksEnabled`, and NightbellMonitorService never consults it. With
        // strict on, every monitor is being checked on its exact interval — so
        // "Nightbell only checks while you have it open" would be flatly false.
        val facts = healthy.copy(backgroundChecksEnabled = false, strictMode = true)
        assertEquals(CheckerLimit.NONE, CheckerLimits.diagnose(facts))
        assertEquals(
            CheckerLimit.BACKGROUND_CHECKS_OFF,
            CheckerLimits.diagnose(facts.copy(strictMode = false)),
        )
    }

    // ---- lateness -----------------------------------------------------------

    @Test
    fun `a monitor inside three intervals is not late`() {
        val facts = healthy.copy(cadences = listOf(MonitorCadence(30, 80 * minute)))
        assertFalse(CheckerLimits.isRunningLate(facts))
        assertEquals(CheckerLimit.NONE, CheckerLimits.diagnose(facts))
    }

    @Test
    fun `well past three intervals is Android deferring us`() {
        val facts = healthy.copy(cadences = listOf(MonitorCadence(60, 5 * 60 * minute)))
        assertTrue(CheckerLimits.isRunningLate(facts))
        assertEquals(CheckerLimit.DELAYED_BY_ANDROID, CheckerLimits.diagnose(facts))
    }

    @Test
    fun `each monitor is judged against its own interval, not the fleet's tightest`() {
        // The regression: a healthy fleet of one 15-minute and one 2-hour monitor.
        // Comparing the *oldest* age (the 2-hour monitor, legitimately up to 120 min
        // old) against the *tightest* interval (15 min, tolerance 50 min) reported
        // "Android is delaying checks" for most of every two hours.
        val facts = healthy.copy(
            enabledMonitors = 2,
            cadences = listOf(
                MonitorCadence(intervalMinutes = 15, ageMs = 4 * minute),
                MonitorCadence(intervalMinutes = 120, ageMs = 110 * minute),
            ),
        )
        assertFalse(CheckerLimits.isRunningLate(facts))
        assertEquals(CheckerLimit.NONE, CheckerLimits.diagnose(facts))

        // The 2-hour monitor genuinely overdue by its own standard still reports.
        val late = facts.copy(
            cadences = listOf(
                MonitorCadence(intervalMinutes = 15, ageMs = 4 * minute),
                MonitorCadence(intervalMinutes = 120, ageMs = 7 * 60 * minute),
            ),
        )
        assertTrue(CheckerLimits.isRunningLate(late))
        assertEquals(CheckerLimit.DELAYED_BY_ANDROID, CheckerLimits.diagnose(late))
    }

    @Test
    fun `a one-minute interval is not called delayed just for hitting the platform floor`() {
        // WorkManager's periodic minimum is 15 minutes, so a monitor asking for one
        // minute is *always* three intervals late in the background. That is the
        // documented floor, not a fault, and reporting it as one would train the
        // user to ignore this card. Hence the floor on the tolerance.
        val facts = healthy.copy(cadences = listOf(MonitorCadence(1, 20 * minute)))
        assertFalse(CheckerLimits.isRunningLate(facts))
        assertEquals(CheckerLimit.NONE, CheckerLimits.diagnose(facts))

        // Genuinely stuck for hours is still reported.
        assertTrue(CheckerLimits.isRunningLate(healthy.copy(cadences = listOf(MonitorCadence(1, 3 * 60 * minute)))))
    }

    @Test
    fun `a monitor that has never been checked is not late`() {
        // SystemLimits omits such monitors entirely, and an empty list is not late.
        assertFalse(CheckerLimits.isRunningLate(healthy.copy(cadences = emptyList())))
        assertEquals(CheckerLimit.NONE, CheckerLimits.diagnose(healthy.copy(cadences = emptyList())))
        assertFalse(CheckerLimits.isLate(MonitorCadence(15, 0L)))
    }

    @Test
    fun `every limit has copy and only NONE reads as unlimited`() {
        CheckerLimit.entries.forEach { limit ->
            assertTrue("$limit needs a headline", limit.headline.isNotBlank())
            assertTrue("$limit needs a hint", limit.hint.isNotBlank())
            assertEquals(limit != CheckerLimit.NONE, limit.isLimited)
        }
    }
}
