package me.river.nightbell

import me.river.nightbell.data.check.UpdateChecker
import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.domain.UpdateSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a version out of each source, over a real socket.
 *
 * Both payloads here are the shape the live endpoints actually return, copied
 * from a request made while this was written.
 */
class UpdateCheckerTest {

    private val githubRelease = """
        {
          "id": 377361469,
          "tag_name": "v3.1.1",
          "name": "Nightbell 3.1.1",
          "prerelease": false,
          "draft": false,
          "html_url": "https://github.com/riveerxd/nightbell/releases/tag/v3.1.1",
          "assets": [
            {
              "name": "nightbell-3.1.1.apk.sha256",
              "size": 96,
              "browser_download_url": "https://github.com/riveerxd/nightbell/releases/download/v3.1.1/nightbell-3.1.1.apk.sha256"
            },
            {
              "name": "nightbell-3.1.1.apk",
              "size": 9123456,
              "browser_download_url": "https://github.com/riveerxd/nightbell/releases/download/v3.1.1/nightbell-3.1.1.apk"
            }
          ]
        }
    """.trimIndent()

    private val fdroidPackage = """
        {
          "packageName": "me.river.nightbell",
          "suggestedVersionCode": 27,
          "packages": [
            { "versionName": "3.0.5", "versionCode": 27, "size": 8123456 },
            { "versionName": "3.0.4", "versionCode": 26, "size": 8000000 }
          ]
        }
    """.trimIndent()

    @Test
    fun `the github release is read as a version and a page to open`() {
        TinyHttpServer { TinyHttpServer.Response(body = githubRelease, contentType = "application/json") }
            .use { server ->
                val release = runBlocking {
                    UpdateChecker(githubBase = server.baseUrl).latest(UpdateSource.GITHUB)
                }
                assertEquals("3.1.1", release?.version)
                assertEquals(UpdateSource.GITHUB, release?.source)
                assertEquals("https://github.com/riveerxd/nightbell/releases/tag/v3.1.1", release?.url)
                // The tag carries a `v`; the version does not, so it can be
                // compared against BuildConfig.VERSION_NAME without ceremony.
                assertTrue(AppUpdate.isNewer(release!!.version, "3.1.0"))
            }
    }

    @Test
    fun `the apk attached to the release is what the install button fetches`() {
        TinyHttpServer { TinyHttpServer.Response(body = githubRelease, contentType = "application/json") }
            .use { server ->
                val release = runBlocking {
                    UpdateChecker(githubBase = server.baseUrl).latest(UpdateSource.GITHUB)
                }
                // The .apk, not the .sha256 sitting in front of it in the list.
                assertEquals(
                    "https://github.com/riveerxd/nightbell/releases/download/v3.1.1/nightbell-3.1.1.apk",
                    release?.apkUrl,
                )
                assertEquals(9123456L, release?.apkSize)
            }
    }

    @Test
    fun `a release with nothing attached offers no apk rather than a guess`() {
        val bare = """{ "tag_name": "v3.1.1", "html_url": "https://example.com/r" }"""
        TinyHttpServer { TinyHttpServer.Response(body = bare, contentType = "application/json") }
            .use { server ->
                val release = runBlocking {
                    UpdateChecker(githubBase = server.baseUrl).latest(UpdateSource.GITHUB)
                }
                assertEquals("3.1.1", release?.version)
                assertEquals("", release?.apkUrl)
            }
    }

    @Test
    fun `the f-droid apk is the one f-droid signed, addressed by version code`() {
        TinyHttpServer { TinyHttpServer.Response(body = fdroidPackage, contentType = "application/json") }
            .use { server ->
                val release = runBlocking {
                    UpdateChecker(fdroidBase = server.baseUrl).latest(UpdateSource.FDROID)
                }
                // Their build, not the GitHub asset: the two are signed by
                // different keys and Android refuses to install one over the other.
                assertEquals(server.baseUrl + "/repo/me.river.nightbell_27.apk", release?.apkUrl)
            }
    }

    @Test
    fun `it asks github for the latest release of this repository`() {
        var path = ""
        TinyHttpServer { request ->
            path = request.path
            TinyHttpServer.Response(body = githubRelease, contentType = "application/json")
        }.use { server ->
            runBlocking { UpdateChecker(githubBase = server.baseUrl).latest(UpdateSource.GITHUB) }
        }
        assertEquals("/repos/riveerxd/nightbell/releases/latest", path)
    }

    @Test
    fun `the version check never spends the user's token`() {
        // A question about this app, asked with no credential of theirs attached.
        var auth: String? = "unset"
        TinyHttpServer { request ->
            auth = request.headers["authorization"]
            TinyHttpServer.Response(body = githubRelease, contentType = "application/json")
        }.use { server ->
            runBlocking { UpdateChecker(githubBase = server.baseUrl).latest(UpdateSource.GITHUB) }
        }
        assertNull(auth)
    }

    @Test
    fun `f-droid reports the version its own clients can install`() {
        TinyHttpServer { TinyHttpServer.Response(body = fdroidPackage, contentType = "application/json") }
            .use { server ->
                val release = runBlocking {
                    UpdateChecker(fdroidBase = server.baseUrl).latest(UpdateSource.FDROID)
                }
                // The suggested code, not the highest listed: that is what an
                // F-Droid client would actually hand the user.
                assertEquals("3.0.5", release?.version)
                assertEquals(UpdateSource.FDROID, release?.source)
                assertEquals(AppUpdate.FDROID_URL, release?.url)
            }
    }

    @Test
    fun `it asks f-droid about this package`() {
        var path = ""
        TinyHttpServer { request ->
            path = request.path
            TinyHttpServer.Response(body = fdroidPackage, contentType = "application/json")
        }.use { server ->
            runBlocking { UpdateChecker(fdroidBase = server.baseUrl).latest(UpdateSource.FDROID) }
        }
        assertEquals("/api/v1/packages/me.river.nightbell", path)
    }

    @Test
    fun `a source that is down answers nothing rather than guessing`() {
        TinyHttpServer { TinyHttpServer.Response(code = 503, reason = "Service Unavailable") }
            .use { server ->
                val checker = UpdateChecker(githubBase = server.baseUrl, fdroidBase = server.baseUrl)
                assertNull(runBlocking { checker.latest(UpdateSource.GITHUB) })
                assertNull(runBlocking { checker.latest(UpdateSource.FDROID) })
            }
    }

    @Test
    fun `nonsense in the response is not a version`() {
        TinyHttpServer { TinyHttpServer.Response(body = "<html>nope</html>") }.use { server ->
            val checker = UpdateChecker(githubBase = server.baseUrl, fdroidBase = server.baseUrl)
            assertNull(runBlocking { checker.latest(UpdateSource.GITHUB) })
            assertNull(runBlocking { checker.latest(UpdateSource.FDROID) })
        }
    }

    @Test
    fun `an unreachable host is a non-event`() {
        // Port 1 on loopback with nothing listening: connection refused, at once.
        val checker = UpdateChecker(githubBase = "http://127.0.0.1:1", fdroidBase = "http://127.0.0.1:1")
        assertNull(runBlocking { checker.latest(UpdateSource.GITHUB) })
        assertNull(runBlocking { checker.latest(UpdateSource.FDROID) })
    }
}
