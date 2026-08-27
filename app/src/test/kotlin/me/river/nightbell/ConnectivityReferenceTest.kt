package me.river.nightbell

import java.io.File
import me.river.nightbell.domain.ConnectivityReference
import me.river.nightbell.domain.GlobalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #4: the latency probe should not be Google's by default.
 *
 * The probe times a known-good endpoint so a slow phone is not reported as a
 * slow website. Any always-up 204 does that equally well, and the one that
 * shipped was `www.gstatic.com/generate_204`, which meant an app whose whole
 * pitch is no server, no account and no third party was telling Google's edge
 * where the phone was every forty-five seconds. GrapheneOS runs the same check
 * and has no advertising business, so that is the default now.
 *
 * The field stays free text, so nothing here forbids gstatic. What it forbids is
 * arriving there without being asked.
 */
class ConnectivityReferenceTest {

    @Test
    fun `the default reference is the GrapheneOS endpoint`() {
        assertEquals(
            "https://connectivitycheck.grapheneos.network/generate_204",
            ConnectivityReference.DEFAULT_URL,
        )
        assertEquals(ConnectivityReference.DEFAULT_URL, GlobalSettings().latencyReferenceUrl)
    }

    @Test
    fun `no shipped default points at a Google host`() {
        val defaults = GlobalSettings()
        listOf(defaults.latencyReferenceUrl).forEach { url ->
            GOOGLE_HOSTS.forEach { host ->
                assertFalse("$url still points at $host", url.contains(host, ignoreCase = true))
            }
        }
    }

    @Test
    fun `the presets are all non-Google and all offer a real endpoint`() {
        assertTrue(ConnectivityReference.presets.isNotEmpty())
        assertEquals(ConnectivityReference.DEFAULT_URL, ConnectivityReference.presets.first().url)
        ConnectivityReference.presets.forEach { preset ->
            assertTrue(preset.url, preset.url.startsWith("https://"))
            assertTrue(preset.label.isNotBlank())
            GOOGLE_HOSTS.forEach { host ->
                assertFalse("preset ${preset.label} uses $host", preset.url.contains(host, true))
            }
        }
    }

    @Test
    fun `an install that never chose an endpoint is moved off Google's`() {
        // A stored value equal to the old default is on disk because a default was
        // written there, not because anybody picked it. Changing the default alone
        // would have fixed this for new installs and for nobody else.
        assertEquals(
            ConnectivityReference.DEFAULT_URL,
            ConnectivityReference.migrate(ConnectivityReference.LEGACY_GOOGLE_URL),
        )
        assertEquals(
            ConnectivityReference.DEFAULT_URL,
            ConnectivityReference.migrate("  ${ConnectivityReference.LEGACY_GOOGLE_URL}  "),
        )
    }

    @Test
    fun `an endpoint the user typed is left exactly as typed`() {
        // Including gstatic typed on purpose. The field is theirs.
        val chosen = "https://www.gstatic.com/generate_204?mine=1"
        assertEquals(chosen, ConnectivityReference.migrate(chosen))
        assertEquals("https://nas.local/ping", ConnectivityReference.migrate("https://nas.local/ping"))
        assertEquals("", ConnectivityReference.migrate(""))
    }

    /**
     * The source scan, as a test rather than as a note in a handoff.
     *
     * Greps the shipped sources for the old host and insists every remaining
     * mention is either prose explaining the change or the one constant the
     * migration matches against. A future edit that quietly reintroduces it as a
     * default fails here.
     */
    @Test
    fun `no default runtime path still reaches for gstatic`() {
        val main = sourceRoot()
        val offenders = mutableListOf<String>()
        main.walkTopDown()
            .filter { it.isFile && it.extension in setOf("kt", "xml") }
            .forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (!line.contains("gstatic", ignoreCase = true)) return@forEachIndexed
                    if (isProse(line) || line.contains("LEGACY_GOOGLE_URL")) return@forEachIndexed
                    offenders += "${file.name}:${index + 1}: ${line.trim()}"
                }
            }
        assertTrue(
            "gstatic is still reachable from code:\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `the hosts this app contacts on its own behalf are pinned to https`() {
        val config = File(sourceRoot(), "res/xml/network_security_config.xml").readText()
        val pinned = config.substringAfter("cleartextTrafficPermitted=\"false\"")
        listOf(
            "connectivitycheck.grapheneos.network",
            "api.github.com",
            "f-droid.org",
        ).forEach { host ->
            assertTrue("$host is not pinned", pinned.contains("<domain includeSubdomains=\"false\">$host</domain>"))
        }
    }

    private fun isProse(line: String): Boolean {
        val trimmed = line.trim()
        return trimmed.startsWith("//") ||
            trimmed.startsWith("*") ||
            trimmed.startsWith("/*") ||
            // An XML comment body, which is how the network security config
            // explains what moved and why.
            trimmed.startsWith("<!--") ||
            (!trimmed.contains('<') && !trimmed.contains('"'))
    }

    /** `src/main`, found from wherever Gradle decided to run the tests. */
    private fun sourceRoot(): File {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "app/src/main")
            if (candidate.isDirectory) return candidate
            val here = File(dir, "src/main")
            if (here.isDirectory) return here
            dir = dir.parentFile
        }
        error("could not locate src/main from ${File("").absolutePath}")
    }

    private companion object {
        val GOOGLE_HOSTS = listOf("gstatic.com", "google.com", "googleapis.com", "googleusercontent.com")
    }
}
