package me.river.nightbell

import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.serialization.json.Json
import me.river.nightbell.data.NightbellSnapshot
import me.river.nightbell.data.NightbellStore
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a write does to a store document this build cannot read.
 *
 * It used to read as empty, and the next write of anything saved the empty
 * snapshot over it. The document is now copied aside first, and a write with
 * nothing of the user's in it is not made at all.
 */
class StoreCorruptionTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val read: (String?) -> NightbellSnapshot? = { raw ->
        if (raw.isNullOrBlank()) NightbellSnapshot() else runCatching { json.decodeFromString<NightbellSnapshot>(raw) }.getOrNull()
    }
    private val encode: (NightbellSnapshot) -> String = { json.encodeToString(it) }

    private val unreadable = """{"monitors":[{"id":"a","url":"https://example.com","timeoutSeconds":"fifteen"}]}"""

    @Test
    fun aWorkerStampingAGhostLeavesTheDocumentAlone() {
        val prefs = mutablePreferencesOf(NightbellStore.SNAPSHOT_KEY to unreadable)

        val written = NightbellStore.commit(prefs, read, encode) { snap ->
            snap.copy(runtimes = snap.runtimes + ("a" to MonitorRuntime(lastCheckedAt = 42L)))
        }

        assertNull(written)
        assertEquals(unreadable, prefs[NightbellStore.SNAPSHOT_KEY])
        assertEquals(unreadable, prefs[NightbellStore.UNREADABLE_KEY])
    }

    @Test
    fun aRealWriteGoesAheadWithTheOriginalKeptAside() {
        val prefs = mutablePreferencesOf(NightbellStore.SNAPSHOT_KEY to unreadable)

        val written = NightbellStore.commit(prefs, read, encode) { snap ->
            snap.copy(monitors = listOf(Monitor(id = "new", url = "https://example.net")))
        }

        assertNotNull(written)
        assertEquals(listOf("new"), read(prefs[NightbellStore.SNAPSHOT_KEY])!!.monitors.map { it.id })
        assertEquals(unreadable, prefs[NightbellStore.UNREADABLE_KEY])
    }

    @Test
    fun theFirstCopyIsNeverReplaced() {
        val prefs = mutablePreferencesOf(
            NightbellStore.SNAPSHOT_KEY to "{ second broken one",
            NightbellStore.UNREADABLE_KEY to unreadable,
        )

        NightbellStore.commit(prefs, read, encode) { it }

        assertEquals(unreadable, prefs[NightbellStore.UNREADABLE_KEY])
    }

    @Test
    fun aReadableStoreIsWrittenAsBefore() {
        val start = NightbellSnapshot(monitors = listOf(Monitor(id = "a")))
        val prefs = mutablePreferencesOf(NightbellStore.SNAPSHOT_KEY to encode(start))

        val written = NightbellStore.commit(prefs, read, encode) { it }

        assertEquals(start.revision + 1, written!!.revision)
        assertNull(prefs[NightbellStore.UNREADABLE_KEY])
    }

    @Test
    fun aFreshInstallIsNotTreatedAsUnreadable() {
        val prefs = mutablePreferencesOf()

        val written = NightbellStore.commit(prefs, read, encode) { snap ->
            snap.copy(runtimes = mapOf("a" to MonitorRuntime()))
        }

        assertNotNull(written)
        assertNull(prefs[NightbellStore.UNREADABLE_KEY])
    }
}
