package me.river.nightbell

import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.HealthFormat
import me.river.nightbell.domain.HealthFormat.Reading
import me.river.nightbell.domain.HealthLogLevel
import me.river.nightbell.domain.HealthStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Reading a health endpoint's response, per `docs/health-format.md`.
 *
 * The contract is public, so these are written against what the document
 * promises a service rather than against what the parser happens to do.
 */
class HealthFormatTest {

    private val now = Instant.parse("2026-10-09T13:00:00Z")

    private fun read(
        body: String?,
        status: Int = 200,
        date: Instant? = null,
        stale: Int = 0,
        oversize: Boolean = false,
    ): Reading = HealthFormat.parse(status, body, date, now, stale, oversize)

    private fun report(body: String, status: Int = 200, date: Instant? = null, stale: Int = 0): Reading.Report {
        val reading = read(body, status, date, stale)
        assertTrue("expected a report, got $reading", reading is Reading.Report)
        return reading as Reading.Report
    }

    private fun httpDate(text: String): Instant =
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()

    // ---- the three real ASP.NET outputs ----------------------------------------

    @Test
    fun aspNetDownIsReadFromThe503Body() {
        val reading = report(HealthPayloads.ASPNET_DOWN, status = 503, date = httpDate(HealthPayloads.ASPNET_DOWN_DATE))

        assertEquals(HealthStatus.DOWN, reading.verdict)
        assertEquals("payments: 12 card payments failed in the last 5 minutes", reading.message)
        assertEquals(listOf("payments", "database"), reading.state.components.map { it.id })
        assertEquals(12.0, reading.state.components[0].value)
        assertEquals("12 failures", reading.state.components[0].reading)
        assertEquals(Instant.parse("2026-10-09T12:57:33.197926Z").toEpochMilli(), reading.state.reportedAt)
    }

    @Test
    fun aspNetDegradedAndUp() {
        val degraded = report(HealthPayloads.ASPNET_DEGRADED)
        val up = report(HealthPayloads.ASPNET_UP)

        assertEquals(HealthStatus.DEGRADED, degraded.verdict)
        assertEquals("", degraded.message)
        assertEquals(HealthStatus.UP, up.verdict)
        assertEquals("0 failures", up.state.components[0].reading)
        assertEquals("", up.state.disagreement)
    }

    @Test
    fun theFullExampleFromTheDocs() {
        val reading = report(HealthPayloads.FULL)

        assertEquals(HealthStatus.DOWN, reading.verdict)
        assertEquals("Card payments are failing", reading.message)
        assertEquals(listOf("Payments", "Database", "Email queue"), reading.state.components.map { it.displayName })
        assertEquals(Instant.parse("2026-10-09T13:58:00Z").toEpochMilli(), reading.state.components[0].since)
        assertEquals("340", reading.state.components[2].reading)
        assertEquals(3, reading.state.componentsTotal)
        assertEquals("payments-api", reading.state.service)
        assertEquals("4.12.0", reading.state.version)
        assertEquals("production", reading.state.environment)
        assertEquals(38.0, reading.state.components[0].durationMs, 0.0)
        assertEquals(-1.0, reading.state.components[2].durationMs, 0.0)
    }

    // ---- conditions and the log ----------------------------------------------------

    @Test
    fun conditionsCarryWhatWasMeasuredAndTheRule() {
        val payments = report(HealthPayloads.FULL).state.components[0]
        val failed = payments.conditions[0]
        val gateway = payments.conditions[1]

        assertEquals("Failed card payments", failed.displayName)
        assertEquals(HealthStatus.DOWN, failed.status)
        assertEquals("14", failed.observed)
        assertEquals(">= 10", failed.rule)
        assertEquals("failures", failed.unit)
        assertEquals("5m", failed.window)
        assertEquals("true", gateway.observed)
        assertEquals(HealthStatus.UP, gateway.status)
    }

    @Test
    fun theLogIsEvidenceInOrderWithItsData() {
        val log = report(HealthPayloads.FULL).state.components[0].log

        assertEquals(2, log.size)
        assertEquals(HealthLogLevel.ERROR, log[0].level)
        assertEquals("Gateway declined payment", log[0].message)
        assertEquals(listOf("code" to "card_declined_by_issuer", "attempt" to "3"), log[0].data)
        assertEquals(Instant.parse("2026-10-09T14:01:40Z").toEpochMilli(), log[1].at)
        assertEquals(HealthLogLevel.WARN, log[1].level)
    }

    @Test
    fun aBrokenLogEntryOrConditionCostsOnlyItself() {
        val part = report(
            """
            {"healthReport":1,"status":"up","components":[{"id":"a","status":"up",
              "conditions":[{"id":"x"},{"status":"up"},{"id":"ok","status":"up","observed":0.5},{"id":"odd","status":"meh"}],
              "log":[{"at":"not a time","level":"info","message":"m"},{"at":"2026-10-09T12:00:00Z","level":"info"},
                     {"at":"2026-10-09T12:00:00Z","level":"loud","message":"kept","data":{"n":null,"deep":{"x":1},"list":[1]}}]}]}
            """.trimIndent(),
        ).state.components.single()

        assertEquals(listOf("ok", "odd"), part.conditions.map { it.id })
        assertEquals("0.5", part.conditions[0].observed)
        assertEquals(HealthStatus.DOWN, part.conditions[1].status)
        assertTrue(part.conditions[1].message.startsWith("Unknown status \"meh\""))
        val entry = part.log.single()
        assertEquals(HealthLogLevel.INFO, entry.level)
        assertEquals(listOf("n" to "null"), entry.data)
    }

    @Test
    fun theWholeReportKeepsAtMostTwoHundredLogEntriesAndConditions() {
        val part = { i: Int ->
            val logs = (1..50).joinToString(",") { """{"at":"2026-10-09T12:00:00Z","level":"info","message":"p$i e$it"}""" }
            val conditions = (1..20).joinToString(",") { """{"id":"c$it","status":"up"}""" }
            """{"id":"p$i","status":"up","conditions":[$conditions],"log":[$logs]}"""
        }
        val doc = """{"healthReport":1,"status":"up","components":[${(1..6).joinToString(",") { part(it) }}]}"""
        val parts = report(doc).state.components

        assertEquals(HealthFormat.LOG_KEPT, parts.sumOf { it.log.size })
        assertEquals(50, parts[3].log.size)
        assertEquals(0, parts[4].log.size)
        assertEquals(120, parts.sumOf { it.conditions.size })
    }

    @Test
    fun aPartIsCappedAtTwentyConditionsAndFiftyLogEntries() {
        val logs = (1..60).joinToString(",") { """{"at":"2026-10-09T12:00:00Z","level":"info","message":"e$it"}""" }
        val conditions = (1..30).joinToString(",") { """{"id":"c$it","status":"up"}""" }
        val part = report(
            """{"healthReport":1,"status":"up","components":[{"id":"a","status":"up","conditions":[$conditions],"log":[$logs]}]}""",
        ).state.components.single()

        assertEquals(HealthFormat.CONDITIONS_PER_PART, part.conditions.size)
        assertEquals(HealthFormat.LOG_PER_PART, part.log.size)
        assertEquals("e1", part.log.first().message)
    }

    @Test
    fun theMinimalDocument() {
        val reading = report(HealthPayloads.MINIMAL)

        assertEquals(HealthStatus.UP, reading.verdict)
        assertTrue(reading.state.components.isEmpty())
        assertEquals(0L, reading.state.reportedAt)
    }

    // ---- words -------------------------------------------------------------------

    @Test
    fun aliasesInAnyCase() {
        for (word in listOf("UP", "Ok", "pAss", "Healthy")) {
            assertEquals(word, HealthStatus.UP, report("""{"healthReport":1,"status":"$word"}""").verdict)
        }
        for (word in listOf("Degraded", "WARN", "Warning")) {
            assertEquals(word, HealthStatus.DEGRADED, report("""{"healthReport":1,"status":"$word"}""").verdict)
        }
        for (word in listOf("DOWN", "Fail", "error", "UnHealthy")) {
            assertEquals(word, HealthStatus.DOWN, report("""{"healthReport":1,"status":"$word"}""").verdict)
        }
    }

    @Test
    fun anUnknownTopLevelWordFailsWithTheWordQuoted() {
        val reading = report("""{"healthReport":1,"status":"Groovy"}""")

        assertEquals(HealthStatus.DOWN, reading.verdict)
        assertTrue(reading.message, reading.message.contains("\"Groovy\""))
    }

    @Test
    fun aMissingStatusIsDown() {
        assertEquals(HealthStatus.DOWN, report("""{"healthReport":1}""").verdict)
    }

    @Test
    fun anUnknownComponentWordMakesThatComponentDown() {
        val reading = report(
            """{"healthReport":1,"status":"up","components":[{"id":"q","status":"meh","message":"slow"}]}""",
        )

        val part = reading.state.components.single()
        assertEquals(HealthStatus.DOWN, part.status)
        assertTrue(part.message, part.message.startsWith("Unknown status \"meh\""))
        assertTrue(part.message.endsWith("slow"))
        assertEquals(HealthStatus.UP, reading.verdict)
    }

    // ---- not this format ---------------------------------------------------------

    @Test
    fun aMissingMarkerIsNotHealthFormat() {
        val reading = read("""{"status":"up"}""")

        assertTrue(reading is Reading.NotHealthFormat)
    }

    @Test
    fun theOldMarkerIsNotThisFormat() {
        assertTrue(read("""{"nightbell":1,"status":"up"}""") is Reading.NotHealthFormat)
    }

    @Test
    fun aStringMarkerIsNotHealthFormat() {
        assertTrue(read("""{"healthReport":"1","status":"up"}""") is Reading.NotHealthFormat)
    }

    @Test
    fun aNewerVersionSaysSo() {
        val reading = read("""{"healthReport":2,"status":"up"}""") as Reading.NotHealthFormat

        assertEquals("Health report version 2 is newer than this app reads", reading.message)
    }

    @Test
    fun theSpaFallbackIsNotHealthFormatWithTheBodyQuoted() {
        val reading = read(HealthPayloads.SPA_FALLBACK) as Reading.NotHealthFormat

        assertEquals("Not a health document", reading.message)
        assertTrue(reading.excerpt.startsWith("<!doctype html>"))
        assertTrue(reading.excerpt.length <= HealthFormat.EXCERPT_CHARS)
    }

    @Test
    fun anExcerptLosesAnythingShapedLikeAToken() {
        val jwt = "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2lnbmF0dXJlc2lnbmF0dXJl"
        val reading = read("token was $jwt") as Reading.NotHealthFormat

        assertFalse(reading.excerpt, reading.excerpt.contains(jwt))
    }

    @Test
    fun anEmptyBody() {
        val reading = read("") as Reading.NotHealthFormat

        assertEquals("The endpoint answered with an empty body", reading.message)
    }

    @Test
    fun theSizeLimit() {
        val padding = " ".repeat(HealthFormat.MAX_BODY_BYTES)
        val tooBig = read(HealthPayloads.MINIMAL + padding) as Reading.NotHealthFormat
        val flagged = read(HealthPayloads.MINIMAL, oversize = true) as Reading.NotHealthFormat
        val justUnder = read(HealthPayloads.MINIMAL + " ".repeat(HealthFormat.MAX_BODY_BYTES - HealthPayloads.MINIMAL.length))

        assertEquals("Response too large to be a health document", tooBig.message)
        assertEquals("Response too large to be a health document", flagged.message)
        assertTrue(justUnder is Reading.Report)
    }

    // ---- what the status code decides --------------------------------------------

    @Test
    fun a503WithADocumentIsTheDocument() {
        assertEquals(HealthStatus.UP, report(HealthPayloads.MINIMAL, status = 503).verdict)
    }

    @Test
    fun a401WithoutADocumentIsARefusal() {
        val reading = read("Missing or invalid Authorization header", status = 401) as Reading.Refused

        assertEquals(401, reading.code)
        assertEquals("Missing or invalid Authorization header", reading.excerpt)
    }

    @Test
    fun a404WithoutADocumentIsAnOrdinaryFailure() {
        assertEquals(404, (read("Not Found", status = 404) as Reading.HttpError).code)
    }

    @Test
    fun aspNetPlainTextIsReadWhateverTheStatus() {
        val healthy = report("Healthy")
        val unhealthy = report("Unhealthy\n", status = 503)

        assertEquals(HealthStatus.UP, healthy.verdict)
        assertTrue(healthy.plainText)
        assertEquals(HealthStatus.DOWN, unhealthy.verdict)
        assertEquals(HealthStatus.DEGRADED, report(" degraded ").verdict)
    }

    // ---- components --------------------------------------------------------------

    @Test
    fun duplicateAndMalformedComponents() {
        val reading = report(
            """
            {"healthReport":1,"status":"up","components":[
              {"id":"a","status":"up","message":"first"},
              {"id":"a","status":"down","message":"second"},
              {"status":"up"},
              {"id":"b"},
              "not an object",
              {"id":"c","status":"up","value":"12"}
            ]}
            """.trimIndent(),
        )

        assertEquals(listOf("a", "c"), reading.state.components.map { it.id })
        assertEquals("first", reading.state.components[0].message)
        assertNull(reading.state.components[1].value)
        assertEquals(4, reading.state.notes.size)
        assertTrue(reading.state.notes.any { it.contains("\"a\" appears more than once") })
        assertTrue(reading.state.notes.any { it.contains("has no id") })
        assertTrue(reading.state.notes.any { it.contains("\"b\" has no status") })
        assertTrue(reading.state.notes.any { it.contains("is not an object") })
    }

    @Test
    fun sixtyFourComponentsAreCappedAtFiftyWithTheTrueTotal() {
        val reading = report(HealthPayloads.manyComponents(64))

        assertEquals(50, reading.state.components.size)
        assertEquals(64, reading.state.componentsTotal)
        assertEquals("part-50", reading.state.components.last().id)
    }

    @Test
    fun stringsAreCutToTheDocumentedLengths() {
        val long = "x".repeat(500)
        val part = report(
            """{"healthReport":1,"status":"up","components":[{"id":"$long","name":"$long","status":"up","message":"$long","unit":"$long"}]}""",
        ).state.components.single()

        assertEquals(HealthFormat.ID_CHARS, part.id.length)
        assertEquals(HealthFormat.NAME_CHARS, part.name.length)
        assertEquals(HealthFormat.MESSAGE_CHARS, part.message.length)
        assertEquals(HealthFormat.UNIT_CHARS, part.unit.length)
    }

    @Test
    fun disagreementIsNotedAndTheVerdictStands() {
        val reading = report(
            """{"healthReport":1,"status":"up","components":[{"id":"pay","name":"Payments","status":"down"},{"id":"db","status":"up"}]}""",
        )

        assertEquals(HealthStatus.UP, reading.verdict)
        assertEquals("The report says up while Payments says down.", reading.state.disagreement)
    }

    @Test
    fun aDownVerdictWithNoMessageNamesTheWorstPart() {
        val named = report(
            """{"healthReport":1,"status":"down","components":[{"id":"db","name":"Database","status":"down"}]}""",
        )
        val bare = report("""{"healthReport":1,"status":"down"}""")

        assertEquals("Database down", named.message)
        assertEquals("Reported down", bare.message)
    }

    // ---- timestamps --------------------------------------------------------------

    @Test
    fun timestampsWithAnyFractionAndEitherOffsetForm() {
        val cases = mapOf(
            "2026-10-09T14:02:11Z" to "2026-10-09T14:02:11Z",
            "2026-10-09T14:02:11.123Z" to "2026-10-09T14:02:11.123Z",
            "2026-10-09T14:02:11.1979260Z" to "2026-10-09T14:02:11.197926Z",
            "2026-10-09T14:02:11.123456789Z" to "2026-10-09T14:02:11.123456789Z",
            "2026-10-09T16:02:11+02:00" to "2026-10-09T14:02:11Z",
            "2026-10-09T16:02:11.123+02:00" to "2026-10-09T14:02:11.123Z",
        )
        for ((text, expected) in cases) {
            assertEquals(text, Instant.parse(expected), HealthFormat.timestamp(text))
        }
    }

    @Test
    fun anUnparsableTimestampIsIgnoredAndNoted() {
        val reading = report("""{"healthReport":1,"status":"up","checkedAt":"yesterday-ish"}""", stale = 5)

        assertEquals(HealthStatus.UP, reading.verdict)
        assertEquals(0L, reading.state.reportedAt)
        assertTrue(reading.state.notes.single().contains("checkedAt"))
        assertNull(HealthFormat.timestamp("2026-10-09T14:02:11"))
    }

    // ---- staleness ---------------------------------------------------------------

    @Test
    fun aDateHeaderAheadOfCheckedAtIsClampedNotNegative() {
        val reading = report(
            HealthPayloads.ASPNET_DOWN.replace("\"down\",\"checkedAt", "\"up\",\"checkedAt"),
            date = httpDate(HealthPayloads.ASPNET_DOWN_DATE),
            stale = 1,
        )

        assertEquals(HealthStatus.UP, reading.verdict)
        assertEquals("", reading.override)
    }

    @Test
    fun aReportOlderThanTheLimitByTheServersClockIsDown() {
        val doc = """{"healthReport":1,"status":"up","checkedAt":"2026-10-09T12:40:00Z"}"""
        // The phone thinks it is 13:00, the server says 12:45: 5 minutes old, by the server.
        val fresh = report(doc, date = Instant.parse("2026-10-09T12:45:00Z"), stale = 10)
        val stale = report(doc, date = Instant.parse("2026-10-09T12:55:00Z"), stale = 10)

        assertEquals(HealthStatus.UP, fresh.verdict)
        assertEquals(HealthStatus.DOWN, stale.verdict)
        assertEquals("Report is 15 minutes old", stale.message)
    }

    @Test
    fun withNoDateHeaderThePhoneClockGetsTwoMinutesOfTolerance() {
        // 11 minutes old against a 10 minute limit is inside the tolerance; 13 is not.
        val inside = report("""{"healthReport":1,"status":"up","checkedAt":"2026-10-09T12:49:00Z"}""", stale = 10)
        val outside = report("""{"healthReport":1,"status":"up","checkedAt":"2026-10-09T12:47:00Z"}""", stale = 10)

        assertEquals(HealthStatus.UP, inside.verdict)
        assertEquals(HealthStatus.DOWN, outside.verdict)
    }

    @Test
    fun stalenessOffMeansAnyAgeIsFine() {
        val old = report("""{"healthReport":1,"status":"up","checkedAt":"2020-01-01T00:00:00Z"}""")

        assertEquals(HealthStatus.UP, old.verdict)
    }

    // ---- into a check result -----------------------------------------------------

    @Test
    fun degradedIsOkAndFlagged() {
        val result = HealthFormat.result(report(HealthPayloads.ASPNET_DEGRADED), 200, 40, 1L)

        assertTrue(result.ok)
        assertTrue(result.degraded)
        assertEquals("payments degraded: 3 card payments failed in the last 5 minutes", result.detail)
        assertNotNull(result.health)
    }

    @Test
    fun downIsReportedDownWithOneLinePerTroubledPart() {
        val result = HealthFormat.result(report(HealthPayloads.FULL), 503, 40, 1L)

        assertFalse(result.ok)
        assertEquals(FailureKind.REPORTED_DOWN, result.failureKind)
        assertEquals("Card payments are failing", result.message)
        assertEquals(
            listOf(
                "Payments down: 14 card payments failed in the last 5 minutes",
                "Email queue degraded: 340 waiting, usually under 50",
            ),
            result.detail.lines(),
        )
    }

    @Test
    fun aFailedReadClearsTheComponents() {
        val result = HealthFormat.result(read(HealthPayloads.SPA_FALLBACK), 200, 40, 1L)

        assertEquals(FailureKind.NOT_HEALTH_FORMAT, result.failureKind)
        assertTrue(result.health!!.components.isEmpty())
    }

    @Test
    fun aRefusalIsRecognisable() {
        val result = HealthFormat.result(read("nope", status = 401), 401, 40, 1L)

        assertTrue(HealthFormat.isRefusal(result))
        assertFalse(HealthFormat.isRefusal(HealthFormat.result(read("x", status = 404), 404, 40, 1L)))
    }

    @Test
    fun theCardSummary() {
        assertEquals("payments degraded · 1 of 2 OK", HealthFormat.summary(report(HealthPayloads.ASPNET_DEGRADED).state))
        assertEquals("2 parts OK", HealthFormat.summary(report(HealthPayloads.ASPNET_UP).state))
        assertEquals("", HealthFormat.summary(report(HealthPayloads.MINIMAL).state))
    }

    @Test
    fun onlyAReportCountsAsReadableWithoutASignIn() {
        fun answer(body: String, status: Int = 200) = HealthFormat.result(read(body, status), status, 12, 0)

        assertTrue(HealthFormat.isReport(answer("""{"healthReport": 1, "status": "up"}""")))
        assertTrue(HealthFormat.isReport(answer("""{"healthReport": 1, "status": "down"}""")))
        assertTrue(HealthFormat.isReport(answer("healthy")))
        // A login page, which is what a web app behind SSO answers an anonymous request with.
        assertFalse(HealthFormat.isReport(answer("<!doctype html><form><input name=password></form>")))
        assertFalse(HealthFormat.isReport(answer("", 200)))
        assertFalse(HealthFormat.isReport(answer("Not Found", 404)))
        assertFalse(HealthFormat.isReport(answer("no key", 401)))
    }
}
