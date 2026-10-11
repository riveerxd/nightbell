package me.river.nightbell

import android.net.TrafficStats
import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import me.river.nightbell.data.check.ElementChecker
import me.river.nightbell.domain.ElementTarget
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The saver against real sites, measured the way Android's data screen measures:
 * bytes charged to this app's uid. Needs the internet, like the other Real tests.
 *
 * Prints one line per site to logcat under [TAG] so the numbers in the changelog
 * come from a run rather than from arithmetic.
 */
@RunWith(AndroidJUnit4::class)
class RealDataSaverInstrumentedTest {

    // Public pages that carry images and stylesheets worth caching, each with an
    // element that has sat in the same place for years.
    private val sites = listOf(
        "https://nightbell.app" to "div.hero-copy:nth-of-type(1) > div.actions > a.btn.btn-primary:nth-of-type(1)",
        "https://www.wikipedia.org" to "h1.central-textlogo-wrapper",
        "https://f-droid.org/en/" to "a.site-title",
        "https://www.gnu.org/" to "h2",
    )

    private fun spent(block: () -> Unit): Long {
        val uid = Process.myUid()
        val before = TrafficStats.getUidRxBytes(uid) + TrafficStats.getUidTxBytes(uid)
        block()
        return TrafficStats.getUidRxBytes(uid) + TrafficStats.getUidTxBytes(uid) - before
    }

    @Test
    fun aWarmLoadCostsLessThanAColdOne() = runBlocking {
        val checker = ElementChecker(NightbellTestSupport.appContext)
        var coldTotal = 0L
        var warmTotal = 0L
        for ((url, selector) in sites) {
            val monitor = Monitor(
                id = url,
                name = url,
                kind = MonitorKind.WEBSITE_ELEMENT,
                url = url,
                element = ElementTarget(cssSelector = selector),
                timeoutSeconds = 30,
            )
            var found = ""
            val cold = spent {
                runBlocking {
                    val result = checker.check(monitor, cached = false)
                    found = if (result.ok) "true" else "false (${result.message})"
                }
            }
            // Primes the cache under the saver's own rules, then measures a repeat.
            spent { runBlocking { checker.check(monitor, cached = true) } }
            val warm = spent { runBlocking { checker.check(monitor, cached = true) } }
            Log.i(TAG, "$url cold=$cold warm=$warm found=$found")
            coldTotal += cold
            warmTotal += warm
        }
        Log.i(TAG, "total cold=$coldTotal warm=$warmTotal")
        assertTrue("warm $warmTotal is not below cold $coldTotal", warmTotal < coldTotal)
    }

    private companion object {
        const val TAG = "DataSaverMeasure"
    }
}
