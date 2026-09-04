package me.river.nightbell

import me.river.nightbell.domain.LoadStage
import me.river.nightbell.domain.PageExpiry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The verdict a page monitor gives when its budget runs out.
 *
 * Before this, every way of running out produced "Page did not finish loading in
 * 15s". Issue 8 ended with three sites reporting exactly that, a reporter who
 * raised the timeout to sixty seconds and saw no change, and nothing anywhere
 * that could say which part of the sequence was stuck. Each case below is a
 * different answer to that question, and the point of each is that a reporter
 * can screenshot it.
 */
class PageExpiryTest {

    @Test
    fun `a load event that never arrives says so and does not blame the element`() {
        val expiry = PageExpiry(
            stage = LoadStage.LOADING,
            progress = 43,
            readyState = "interactive",
            pageFinished = false,
            requestsStarted = 87,
            resourceErrors = 2,
            elapsedMs = 60_000L,
        )
        assertTrue(expiry.headline(60).contains("never finished loading"))
        val detail = expiry.detail()
        assertTrue(detail.contains("43%"))
        assertTrue(detail.contains("\"interactive\""))
        assertTrue(detail.contains("never arrived"))
        assertTrue(detail.contains("87 requests"))
        assertTrue(detail.contains("2 of them failed"))
        // The shape of thewinepark.com's failure. A longer timeout does not fix
        // a document that is waiting on something that will not answer, and
        // saying "try a longer timeout" here would send somebody the wrong way
        // for a second time.
        assertTrue(detail.contains("longer timeout will not help"))
    }

    @Test
    fun `a page that rendered but never signalled is named separately`() {
        val expiry = PageExpiry(
            stage = LoadStage.LOADING,
            progress = 100,
            readyState = "complete",
            pageFinished = false,
            elapsedMs = 21_000L,
        )
        // Reaching complete without a load event is a different bug from never
        // getting there, and the two want different next steps.
        assertTrue(expiry.headline(15).contains("rendered but never signalled"))
    }

    @Test
    fun `an element that never appears does not claim the page failed to load`() {
        val expiry = PageExpiry(
            stage = LoadStage.POLLING,
            progress = 100,
            readyState = "complete",
            pageFinished = true,
            elapsedMs = 19_000L,
        )
        val headline = expiry.headline(15)
        assertTrue(headline.contains("loaded but the element never appeared"))
        assertFalse(headline.contains("never finished loading"))
        assertTrue(expiry.detail().contains("Load event: arrived"))
    }

    @Test
    fun `a navigation that never starts is its own case`() {
        val expiry = PageExpiry(stage = LoadStage.NAVIGATING, elapsedMs = 15_000L)
        assertTrue(expiry.headline(15).contains("never started loading"))
    }

    @Test
    fun `the second load after restoring a session is named as a second load`() {
        val expiry = PageExpiry(stage = LoadStage.RELOADING, elapsedMs = 15_000L)
        assertTrue(expiry.detail().contains("second load"))
        assertTrue(expiry.detail().contains("saved session"))
    }

    @Test
    fun `a stalled page below full progress is told to try a longer timeout first`() {
        val expiry = PageExpiry(
            stage = LoadStage.LOADING,
            progress = 12,
            readyState = "loading",
            pageFinished = false,
            elapsedMs = 15_000L,
        )
        assertTrue(expiry.detail().contains("Raising this monitor's timeout"))
    }

    @Test
    fun `no verdict names a host or a path`() {
        // These strings go on screen and into a screenshot, and a screenshot of
        // a monitor's verdict is something people post. The stage copy has to be
        // about the sequence, never about the address.
        for (stage in LoadStage.entries) {
            val expiry = PageExpiry(stage = stage, elapsedMs = 1_000L)
            assertFalse(expiry.headline(15).contains("http"))
            assertFalse(expiry.detail().contains("http"))
        }
    }
}
