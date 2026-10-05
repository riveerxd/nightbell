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

    // The selectors are the ones the reporting fleet watches, so the early stop
    // fires exactly when it would have on that phone.
    private val sites = listOf(
        "https://nightbell.app" to "div.hero-copy:nth-of-type(1) > div.actions > a.btn.btn-primary:nth-of-type(1)",
        "https://riveer.cz" to "div.hero__acts > a.btn.btn--pri:nth-of-type(1)",
        "https://videre.cz" to "div.flex.flex-col > a.inline-flex.items-center:nth-of-type(1)",
        "https://www.clinicm.cz/" to "a.group.px-8:nth-of-type(1)",
        "https://flirtycall.me" to "div.character-card.group:nth-of-type(1) > " +
            "div.character-info-overlay.absolute:nth-of-type(2) > div.text-white > " +
            "div.flex.items-center:nth-of-type(1) > h3.text-xl.font-bold",
    )

    /** The fallbacks the picker captured alongside the selector, as on the phone. */
    private val fallbacks = mapOf(
        "https://flirtycall.me" to ElementTarget(
            xpath = "/html/body[1]/div[1]/div[1]/main[1]/div[1]/main[1]/div[1]/div[2]/div[1]/div[1]/" +
                "div[1]/div[2]/div[1]/div[1]/h3[1]",
            tagName = "h3",
            classSignature = "text-xl font-bold leading-tight",
            textSnippet = "Megan",
        ),
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
                element = (fallbacks[url] ?: ElementTarget()).copy(cssSelector = selector),
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
