package me.river.nightbell

import me.river.nightbell.domain.AlertDecider
import me.river.nightbell.domain.AlertSeverity
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.FiringAlert
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorRuntime
import me.river.nightbell.domain.PrometheusCheck
import me.river.nightbell.domain.PrometheusSource
import me.river.nightbell.domain.PrometheusWatch
import me.river.nightbell.domain.alertsFiring
import me.river.nightbell.domain.alertsFiringLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alerts a check carries out with it.
 *
 * Issue 14 asked for a view of the alerts, not a sentence about them, so the
 * check has to hand back something a list can be drawn from. These pin the
 * shape, the order and the lifetime of that list.
 */
class FiringAlertsTest {

    private fun monitor(watch: PrometheusWatch = PrometheusWatch(source = PrometheusSource.ALERTMANAGER)) =
        Monitor(id = "a", kind = MonitorKind.PROMETHEUS, url = "https://am.example.com", prometheus = watch)

    @Test
    fun `a firing alert comes back structured, not only as prose`() {
        val verdict = PrometheusCheck.evaluate(monitor(), 200, PrometheusPayloads.ALERTS)
        assertFalse(verdict.passed)
        assertEquals(2, verdict.alerts.size)
        val worst = verdict.alerts.first()
        assertEquals("DiskFillingUp", worst.name)
        assertEquals("critical", worst.severity)
        assertEquals("Disk will fill in 4 hours", worst.summary)
        assertEquals(AlertSeverity.CRITICAL, worst.rank)
    }

    @Test
    fun `alerts are sorted worst first, then oldest first`() {
        // The issue's own words: Alertmanager's view is "already filter and
        // sorted by severity", so this has to arrive in that order rather than
        // in whatever order the API happened to return.
        val body = """
            [
              {"labels":{"alertname":"NewWarning","severity":"warning"},
               "status":{"state":"active","silencedBy":[],"inhibitedBy":[]},
               "startsAt":"2026-09-14T20:00:00.000Z"},
              {"labels":{"alertname":"OldCritical","severity":"critical"},
               "status":{"state":"active","silencedBy":[],"inhibitedBy":[]},
               "startsAt":"2026-09-14T08:00:00.000Z"},
              {"labels":{"alertname":"NewCritical","severity":"critical"},
               "status":{"state":"active","silencedBy":[],"inhibitedBy":[]},
               "startsAt":"2026-09-14T19:00:00.000Z"}
            ]
        """
        val verdict = PrometheusCheck.evaluate(monitor(), 200, body)
        assertEquals(
            listOf("OldCritical", "NewCritical", "NewWarning"),
            verdict.alerts.map { it.name },
        )
        // And the headline names the worst first for the same reason.
        assertTrue(verdict.message, verdict.message.contains("OldCritical"))
    }

    @Test
    fun `an unranked severity sorts below the ranked ones without being dropped`() {
        val body = """
            [
              {"labels":{"alertname":"Ticketed","severity":"ticket"},
               "status":{"state":"active","silencedBy":[],"inhibitedBy":[]}},
              {"labels":{"alertname":"Real","severity":"warning"},
               "status":{"state":"active","silencedBy":[],"inhibitedBy":[]}}
            ]
        """
        val verdict = PrometheusCheck.evaluate(monitor(), 200, body)
        assertEquals(listOf("Real", "Ticketed"), verdict.alerts.map { it.name })
    }

    @Test
    fun `startsAt is read in both the shapes Alertmanager sends`() {
        val body = """
            [
              {"labels":{"alertname":"Zulu","severity":"warning"},
               "status":{"state":"active","silencedBy":[],"inhibitedBy":[]},
               "startsAt":"2026-09-14T18:00:00.000Z"},
              {"labels":{"alertname":"Offset","severity":"warning"},
               "status":{"state":"active","silencedBy":[],"inhibitedBy":[]},
               "startsAt":"2026-09-14T20:00:00.000+02:00"}
            ]
        """
        val verdict = PrometheusCheck.evaluate(monitor(), 200, body)
        val byName = verdict.alerts.associateBy { it.name }
        // Both spellings are the same instant, so neither may sort ahead of the
        // other by an accident of formatting.
        assertEquals(byName.getValue("Zulu").startedAt, byName.getValue("Offset").startedAt)
        assertTrue(byName.getValue("Zulu").startedAt > 0L)
    }

    @Test
    fun `a missing startsAt is zero rather than a guess`() {
        val body = """[{"labels":{"alertname":"NoDate","severity":"warning"},
            "status":{"state":"active","silencedBy":[],"inhibitedBy":[]}}]"""
        assertEquals(0L, PrometheusCheck.evaluate(monitor(), 200, body).alerts.single().startedAt)
    }

    @Test
    fun `nothing firing carries no alerts`() {
        val verdict = PrometheusCheck.evaluate(monitor(), 200, "[]")
        assertTrue(verdict.passed)
        assertTrue(verdict.alerts.isEmpty())
    }

    @Test
    fun `a silenced alert is not in the list either`() {
        val verdict = PrometheusCheck.evaluate(monitor(), 200, PrometheusPayloads.ALERTS)
        assertTrue(verdict.alerts.none { it.name == "SilencedNoise" })
    }

    @Test
    fun `the runtime keeps the newest list and never merges`() {
        val two = AlertDecider.advance(
            previous = MonitorRuntime(),
            result = CheckResult(
                ok = false,
                latencyMs = 4,
                statusCode = 200,
                message = "2 firing",
                alerts = listOf(
                    FiringAlert(name = "A", severity = "critical"),
                    FiringAlert(name = "B", severity = "warning"),
                ),
                at = 1_000L,
            ),
            historyDepth = 10,
        )
        assertEquals(listOf("A", "B"), two.lastAlerts.map { it.name })

        // One resolved. Merging would leave a fixed outage on screen forever.
        val one = AlertDecider.advance(
            previous = two,
            result = CheckResult(
                ok = false,
                latencyMs = 4,
                statusCode = 200,
                message = "1 firing",
                alerts = listOf(FiringAlert(name = "A", severity = "critical")),
                at = 2_000L,
            ),
            historyDepth = 10,
        )
        assertEquals(listOf("A"), one.lastAlerts.map { it.name })
    }

    @Test
    fun `a check that never reached Alertmanager reports nothing rather than the old list`() {
        // Showing yesterday's alerts next to "could not connect" would be the app
        // asserting something it has no evidence for.
        val held = AlertDecider.advance(
            previous = MonitorRuntime(),
            result = CheckResult(
                ok = false,
                latencyMs = 4,
                statusCode = 200,
                message = "1 firing",
                alerts = listOf(FiringAlert(name = "A", severity = "critical")),
                at = 1_000L,
            ),
            historyDepth = 10,
        )
        val offline = AlertDecider.advance(
            previous = held,
            result = CheckResult(
                ok = false,
                latencyMs = 0,
                message = "Can't resolve am.example.com",
                at = 2_000L,
            ),
            historyDepth = 10,
        )
        assertTrue(offline.lastAlerts.isEmpty())
    }

    // ------------------------------------------------- what makes one of them

    @Test
    fun `an alert carries the labels that say which one it is`() {
        val verdict = PrometheusCheck.evaluate(monitor(), 200, PrometheusPayloads.ALERTS_ONE_RULE)
        val first = verdict.alerts.first { it.labels["pod"] == "checkout-7f9c" }
        // alertname and severity have fields of their own. Repeating them in the
        // label list would print the same word twice on one row.
        assertFalse(first.labels.containsKey("alertname"))
        assertFalse(first.labels.containsKey("severity"))
        assertEquals("payments", first.labels["namespace"])
        assertEquals("kube-state-metrics", first.labels["job"])
        assertEquals("aaa1", first.fingerprint)
    }

    @Test
    fun `the identity is the label that separates two alerts on one rule`() {
        val verdict = PrometheusCheck.evaluate(monitor(), 200, PrometheusPayloads.ALERTS_ONE_RULE)
        val crashing = verdict.alerts.filter { it.name == "KubePodCrashLooping" }
        assertEquals(2, crashing.size)
        // Both rows would otherwise read KubePodCrashLooping and stop there.
        assertEquals(
            listOf("checkout-7f9c · payments", "ledger-2b41 · payments"),
            crashing.map { it.identity },
        )
        // And an alert labelled the node-exporter way identifies by instance.
        assertEquals(
            "gw-01",
            verdict.alerts.single { it.name == "CertificateExpiringSoon" }.identity,
        )
    }

    @Test
    fun `identity falls back to a label rather than to nothing`() {
        val body = """[{"labels":{"alertname":"Odd","severity":"warning","shard":"7"},
            "status":{"state":"active","silencedBy":[],"inhibitedBy":[]}}]"""
        assertEquals("shard=7", PrometheusCheck.evaluate(monitor(), 200, body).alerts.single().identity)
    }

    @Test
    fun `one alert reads as one line`() {
        val verdict = PrometheusCheck.evaluate(monitor(), 200, PrometheusPayloads.ALERTS)
        assertEquals("DiskFillingUp (critical) · db01", verdict.alerts.first().line)
    }

    @Test
    fun `the summary and the description are two different things`() {
        val verdict = PrometheusCheck.evaluate(monitor(), 200, PrometheusPayloads.ALERTS_ONE_RULE)
        val pod = verdict.alerts.first { it.labels["pod"] == "checkout-7f9c" }
        assertEquals("Pod is restarting", pod.summary)
        assertEquals("checkout-7f9c has restarted 14 times in the last hour", pod.description)

        // A rule that writes only a description still has a headline, and does
        // not then print the same paragraph twice.
        val cert = verdict.alerts.single { it.name == "CertificateExpiringSoon" }
        assertEquals("The gateway certificate expires in 9 days", cert.summary)
        assertEquals("", cert.description)
    }

    @Test
    fun `a wide label set is cut where it stops saying anything`() {
        val labels = (1..30).joinToString(",") { """"label$it":"value$it"""" }
        val body = """[{"labels":{"alertname":"Wide","severity":"critical","pod":"api-1",$labels},
            "status":{"state":"active","silencedBy":[],"inhibitedBy":[]}}]"""
        val alert = PrometheusCheck.evaluate(monitor(), 200, body).alerts.single()
        assertEquals(12, alert.labels.size)
        // The cap must never fall on the label that says which pod this is, so
        // the identity ones are kept first and the rest fill what is left.
        assertEquals("api-1", alert.labels["pod"])
        assertEquals("api-1", alert.identity)
    }

    @Test
    fun `the headline separates two alerts that share a name`() {
        val message = PrometheusCheck.evaluate(monitor(), 200, PrometheusPayloads.ALERTS_ONE_RULE).message
        // "KubePodCrashLooping, KubePodCrashLooping" is a sentence that reads as
        // a bug in the app rather than as two pods.
        assertTrue(message, message.contains("KubePodCrashLooping (critical) checkout-7f9c · payments"))
        assertTrue(message, message.contains("ledger-2b41"))
    }

    @Test
    fun `a name that is only used once is not padded with a label`() {
        val message = PrometheusCheck.evaluate(monitor(), 200, PrometheusPayloads.ALERTS).message
        assertEquals("2 firing: DiskFillingUp (critical), HighLatency (warning)", message)
    }

    @Test
    fun `the store keeps the worst fifty and counts the rest`() {
        val many = (1..62).map {
            FiringAlert(name = "Alert$it", severity = "critical", startedAt = it.toLong())
        }
        val runtime = AlertDecider.advance(
            previous = MonitorRuntime(),
            result = CheckResult(
                ok = false,
                latencyMs = 4,
                statusCode = 200,
                message = "62 firing",
                alerts = many,
                at = 1_000L,
            ),
            historyDepth = 10,
        )
        assertEquals(AlertDecider.ALERTS_KEPT, runtime.lastAlerts.size)
        // The count has to stay true, or a header reading "50 alerts firing"
        // under a list that dropped twelve is the app understating an outage.
        assertEquals(62, runtime.lastAlertsTotal)
        assertEquals("Alert1", runtime.lastAlerts.first().name)
    }

    @Test
    fun `a list stored before the count existed still counts`() {
        // 3.13.0 and earlier wrote lastAlerts with no total beside it, so a
        // monitor that was down across the upgrade has a list and a zero. The
        // header has to read the list in that case rather than announce that
        // nothing is firing above three rows saying otherwise.
        val old = MonitorRuntime(
            lastAlerts = listOf(
                FiringAlert(name = "A", severity = "critical"),
                FiringAlert(name = "B", severity = "warning"),
            ),
        )
        assertEquals(2, old.alertsFiring)
        assertEquals("2 alerts firing", old.alertsFiringLine)
        assertEquals("1 alert firing", MonitorRuntime(lastAlertsTotal = 1).alertsFiringLine)
    }

    @Test
    fun `every other kind of check carries none`() {
        val runtime = AlertDecider.advance(
            previous = MonitorRuntime(),
            result = CheckResult(ok = true, latencyMs = 8, statusCode = 200, at = 1L),
            historyDepth = 10,
        )
        assertTrue(runtime.lastAlerts.isEmpty())
    }
}
