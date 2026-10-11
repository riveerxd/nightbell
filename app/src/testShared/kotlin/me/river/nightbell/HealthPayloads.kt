package me.river.nightbell

/**
 * Health reports, shared by the JVM suite and the on-device one.
 *
 * The three ASP.NET ones come from the first writer in `docs/health-format.md`,
 * compiled on .NET 8 with two registered checks. [ASPNET_DOWN] is that run's
 * output with one change: the marker key, renamed since to `healthReport`. Its
 * `Date` header is the one it was served under, and its `checkedAt` is a second
 * later than that header because Kestrel stamps the header before the writer
 * runs. The up and degraded ones were not kept from that run and are the same
 * writer's output for 0 and 3 failures, written out by hand with the timestamp
 * the down run had. None of them has conditions or a log, which that writer
 * did not produce; [FULL] has both.
 */
object HealthPayloads {

    /** Served with HTTP 503. */
    const val ASPNET_DOWN =
        """{"healthReport":1,"status":"down","checkedAt":"2026-10-09T12:57:33.197926+00:00","components":[{"id":"payments","status":"down","message":"12 card payments failed in the last 5 minutes","value":12,"unit":"failures"},{"id":"database","status":"up"}]}"""

    /** The `Date` header [ASPNET_DOWN] came with. */
    const val ASPNET_DOWN_DATE = "Fri, 09 Oct 2026 12:57:32 GMT"

    /** Served with HTTP 200. */
    const val ASPNET_DEGRADED =
        """{"healthReport":1,"status":"degraded","checkedAt":"2026-10-09T12:57:33.197926+00:00","components":[{"id":"payments","status":"degraded","message":"3 card payments failed in the last 5 minutes","value":3,"unit":"failures"},{"id":"database","status":"up"}]}"""

    /** Served with HTTP 200. */
    const val ASPNET_UP =
        """{"healthReport":1,"status":"up","checkedAt":"2026-10-09T12:57:33.197926+00:00","components":[{"id":"payments","status":"up","message":"No failed payments in the last 5 minutes","value":0,"unit":"failures"},{"id":"database","status":"up"}]}"""

    const val MINIMAL = """{ "healthReport": 1, "status": "up" }"""

    /** The full example from `docs/health-format.md`, copied as it stands there. */
    val FULL = """
        {
          "healthReport": 1,
          "status": "down",
          "message": "Card payments are failing",
          "checkedAt": "2026-10-09T14:02:11Z",
          "service": "payments-api",
          "version": "4.12.0",
          "environment": "production",
          "components": [
            {
              "id": "payments",
              "name": "Payments",
              "status": "down",
              "message": "14 card payments failed in the last 5 minutes",
              "since": "2026-10-09T13:58:00Z",
              "value": 14,
              "unit": "failures",
              "durationMs": 38,
              "conditions": [
                {
                  "id": "failed-payments",
                  "name": "Failed card payments",
                  "status": "down",
                  "observed": 14,
                  "rule": ">= 10",
                  "unit": "failures",
                  "window": "5m",
                  "message": "14 failures, down at 10 or more"
                },
                {
                  "id": "gateway-reachable",
                  "name": "Gateway reachable",
                  "status": "up",
                  "observed": true,
                  "rule": "down when false"
                }
              ],
              "log": [
                {
                  "at": "2026-10-09T13:58:00Z",
                  "level": "error",
                  "message": "Gateway declined payment",
                  "data": { "code": "card_declined_by_issuer", "attempt": 3 }
                },
                {
                  "at": "2026-10-09T14:01:40Z",
                  "level": "warn",
                  "message": "Retry queue above 100",
                  "data": { "queued": 140 }
                }
              ]
            },
            {
              "id": "database",
              "name": "Database",
              "status": "up",
              "value": 12,
              "unit": "ms",
              "conditions": [
                { "id": "query-latency", "status": "up", "observed": 12, "rule": "degraded when > 500, down when > 2000", "unit": "ms" }
              ]
            },
            {
              "id": "email-queue",
              "name": "Email queue",
              "status": "degraded",
              "message": "340 waiting, usually under 50",
              "value": 340,
              "conditions": [
                { "id": "queue-depth", "status": "degraded", "observed": 340, "rule": "> 100", "unit": "messages" }
              ]
            }
          ]
        }
    """.trimIndent()

    /** What a web app's catch-all route answers for a path it does not know. */
    const val SPA_FALLBACK =
        """<!doctype html><html lang="en"><head><meta charset="utf-8"><title>Admin</title></head><body><div id="root"></div><script src="/assets/index-4f1c.js"></script></body></html>"""

    /** [count] components, every one up. */
    fun manyComponents(count: Int): String = buildString {
        append("""{"healthReport":1,"status":"up","components":[""")
        for (i in 1..count) {
            if (i > 1) append(',')
            append("""{"id":"part-$i","name":"Part $i","status":"up"}""")
        }
        append("]}")
    }
}
