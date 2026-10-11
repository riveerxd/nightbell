package me.river.nightbell

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A store written by a newer build, read by this one.
 *
 * Before [MonitorKind.UNSUPPORTED] one kind name this build did not know failed
 * the decode of the entire snapshot, and the store's fallback for that is an
 * empty one, which the next write then saved. So the cost of a downgrade was
 * every monitor, not the one this build could not run.
 */
class MonitorKindDecodingTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val written = """
        {
          "monitors": [
            { "id": "a", "name": "Front door", "kind": "http_status", "url": "https://example.com" },
            { "id": "b", "name": "Later", "kind": "something_from_2030", "url": "https://example.org/x",
              "intervalMinutes": 5, "futureField": { "deep": [1, 2, 3] } }
          ]
        }
    """.trimIndent()

    @Test
    fun anUnknownKindCostsThatMonitorAndNothingElse() {
        val snapshot = json.decodeFromString<NightbellSnapshot>(written)

        assertEquals(listOf("a", "b"), snapshot.monitors.map { it.id })
        assertEquals(MonitorKind.HTTP_STATUS, snapshot.monitors[0].kind)
        val later = snapshot.monitors[1]
        assertEquals(MonitorKind.UNSUPPORTED, later.kind)
        assertEquals("something_from_2030", later.unsupportedKind)
        assertEquals("Later", later.name)
        assertEquals(5, later.intervalMinutes)
    }

    @Test
    fun itGoesBackOutUnderTheNameItCameWith() {
        val once = json.decodeFromString<NightbellSnapshot>(written)
        val text = json.encodeToString(once)
        val monitors = json.parseToJsonElement(text).jsonObject["monitors"]!!.jsonArray

        assertEquals("something_from_2030", monitors[1].jsonObject["kind"]!!.jsonPrimitive.content)
        assertFalse("unsupportedKind" in monitors[1].jsonObject)
        assertEquals(once, json.decodeFromString<NightbellSnapshot>(text))
    }

    @Test
    fun aKnownKindIsUntouched() {
        val monitor = Monitor(id = "p", kind = MonitorKind.PROMETHEUS)
        val text = json.encodeToString(Monitor.serializer(), monitor)

        assertFalse("unsupportedKind" in text)
        assertEquals(monitor, json.decodeFromString(Monitor.serializer(), text))
    }

    @Test
    fun itIsNeverScheduledNorOffered() {
        val later = json.decodeFromString<NightbellSnapshot>(written).monitors[1]

        assertTrue(later.enabled)
        assertFalse(later.runs)
        assertFalse(MonitorKind.UNSUPPORTED.offered)
        assertTrue(MonitorKind.entries.filter { it != MonitorKind.UNSUPPORTED }.all { it.offered })
    }
}
