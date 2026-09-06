package me.river.nightbell

import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.data.check.GitHubChecker
import me.river.nightbell.data.check.UpdateChecker
import java.io.File
import java.net.URI
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every host this app talks to on its own behalf is nailed down in
 * `network_security_config.xml`, and this is the test that says so.
 *
 * The config's base is deliberately permissive: cleartext allowed and
 * user-installed CAs trusted, because a large share of what people point this app
 * at is a NAS on a LAN or a homelab box behind a private CA, and refusing those
 * would make them unmonitorable rather than safe. The compensating half is the
 * `domain-config` block, which holds the handful of hosts the app chose for itself
 * to HTTPS and to system anchors alone.
 *
 * That half was a comment saying "if a future release adds any other endpoint of
 * its own, add it here", and a comment is not a mechanism. The release manifest
 * host was added to the app and not to that block, and it took a security pass to
 * notice. What it bought an attacker was narrow, because
 * `UpdateInstaller.verify` refuses an archive signed with the wrong key, but not
 * nothing: a CA in front of the manifest can answer "you are current" forever and
 * hold a security fix back for as long as it likes.
 *
 * So the list lives here now, derived from the constants the app actually uses
 * rather than retyped, which is what makes changing a host fail this test instead
 * of silently widening what can sit in front of it.
 */
class NetworkTrustTest {

    private val config = File("src/main/res/xml/network_security_config.xml")

    /**
     * The domains inside `domain-config`, and only those.
     *
     * Read off the strict block on purpose. A host named anywhere else in the file,
     * including in the long comment explaining the block, must not count: the
     * comment is where the previous version of this invariant lived and it is
     * exactly what a looser match would have accepted.
     */
    private fun pinnedDomains(): Set<String> {
        assertTrue("${config.absolutePath} is missing", config.isFile)
        val text = config.readText()
        val block = text.substringAfter("<domain-config").substringBefore("</domain-config>")
        return Regex("<domain[^>]*>([^<]+)</domain>")
            .findAll(block)
            .map { it.groupValues[1].trim() }
            .toSet()
    }

    private fun hostOf(url: String): String = URI(url).host

    @Test
    fun `the release manifest host is pinned`() {
        assertTrue(
            "${hostOf(AppUpdate.SITE_BASE)} is not in domain-config, so a user or MDM CA can " +
                "sit in front of the manifest and decide what this app offers to install",
            hostOf(AppUpdate.SITE_BASE) in pinnedDomains(),
        )
    }

    @Test
    fun `the two other version check hosts are pinned`() {
        val pinned = pinnedDomains()
        assertTrue("api.github.com is not pinned", hostOf(GitHubChecker.API_BASE) in pinned)
        assertTrue("f-droid.org is not pinned", hostOf(UpdateChecker.FDROID_BASE) in pinned)
    }

    @Test
    fun `the strict block refuses cleartext and trusts only system anchors`() {
        val block = config.readText().substringAfter("<domain-config").substringBefore("</domain-config>")
        assertTrue(
            "domain-config no longer sets cleartextTrafficPermitted=false",
            config.readText().contains("""<domain-config cleartextTrafficPermitted="false">"""),
        )
        assertTrue("domain-config no longer trusts system anchors", block.contains("""src="system""""))
        // The whole point of the block. Trusting `user` in here would make it
        // identical to the permissive base and quietly undo every line above.
        assertTrue("domain-config trusts user CAs, which defeats it", !block.contains("""src="user""""))
    }
}
