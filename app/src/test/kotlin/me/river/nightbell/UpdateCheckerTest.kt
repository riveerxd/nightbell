package me.river.nightbell

import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.data.check.UpdateChecker
import me.river.nightbell.domain.AppUpdate
import me.river.nightbell.domain.UpdateSource
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertNotNull
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

    /**
     * Byte for byte what `website/scripts/gen-version-manifest.mjs` writes.
     *
     * Copied out of `website/public/v1/release.json` rather than retyped, because
     * a fixture that agrees with an idea of the payload instead of with the
     * generator is a test that passes while the two ends disagree.
     */
    private val manifest = """
        {
          "schema": 1,
          "version": "3.9.0",
          "versionCode": 38,
          "tag": "v3.9.0",
          "url": "https://github.com/riveerxd/nightbell/releases/tag/v3.9.0",
          "notes": "Nightbell 3.9.0",
          "apkUrl": "https://github.com/riveerxd/nightbell/releases/download/v3.9.0/Nightbell-3.9.0-release.apk",
          "apkSize": 2469692,
          "apkSha256": "574530103f1ad85cacba4dba0650a01912e80f68ced431c6ba95c46037aa50e6"
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

    // ---- the app's own site --------------------------------------------------

    @Test
    fun `the manifest is read as a version and an apk to install`() {
        TinyHttpServer { TinyHttpServer.Response(body = manifest, contentType = "application/json") }
            .use { server ->
                val release = runBlocking {
                    UpdateChecker(siteBase = server.baseUrl).latest(UpdateSource.DIRECT)
                }
                assertEquals("3.9.0", release?.version)
                assertEquals(UpdateSource.DIRECT, release?.source)
                assertEquals("https://github.com/riveerxd/nightbell/releases/tag/v3.9.0", release?.url)
                assertEquals(
                    "https://github.com/riveerxd/nightbell/releases/download/v3.9.0/" +
                        "Nightbell-3.9.0-release.apk",
                    release?.apkUrl,
                )
                assertEquals(2469692L, release?.apkSize)
                assertTrue(AppUpdate.isNewer(release!!.version, "3.8.0"))
            }
    }

    @Test
    fun `it asks the site for the versioned manifest path`() {
        var path = ""
        TinyHttpServer { request ->
            path = request.path
            TinyHttpServer.Response(body = manifest, contentType = "application/json")
        }.use { server ->
            runBlocking { UpdateChecker(siteBase = server.baseUrl).latest(UpdateSource.DIRECT) }
        }
        // The version in the path is the promise that a future payload shape can
        // ship without breaking installs that only understand this one.
        assertEquals("/v1/release.json", path)
    }

    @Test
    fun `a trailing slash on the site base does not double up`() {
        var path = ""
        TinyHttpServer { request ->
            path = request.path
            TinyHttpServer.Response(body = manifest, contentType = "application/json")
        }.use { server ->
            runBlocking {
                UpdateChecker(siteBase = server.baseUrl + "/").latest(UpdateSource.DIRECT)
            }
        }
        assertEquals("/v1/release.json", path)
    }

    // ---- the census, which is one word in one header -------------------------

    @Test
    fun `the first check ever says new and carries the installed version`() {
        TinyHttpServer { TinyHttpServer.Response(body = manifest, contentType = "application/json") }
            .use { server ->
                val checker = UpdateChecker(
                    siteBase = server.baseUrl,
                    installedVersion = { "3.9.0" },
                )
                runBlocking { checker.latest(UpdateSource.DIRECT, firstEver = true) }
                assertEquals(
                    "Nightbell/3.9.0 (Android; new)",
                    server.received.single().headers["user-agent"],
                )
            }
    }

    @Test
    fun `every later check carries the version without the word`() {
        TinyHttpServer { TinyHttpServer.Response(body = manifest, contentType = "application/json") }
            .use { server ->
                val checker = UpdateChecker(
                    siteBase = server.baseUrl,
                    installedVersion = { "3.9.0" },
                )
                runBlocking { checker.latest(UpdateSource.DIRECT, firstEver = false) }
                assertEquals(
                    "Nightbell/3.9.0 (Android)",
                    server.received.single().headers["user-agent"],
                )
            }
    }

    @Test
    fun `a forced check is marked on the wire`() {
        TinyHttpServer { TinyHttpServer.Response(body = manifest, contentType = "application/json") }
            .use { server ->
                val checker = UpdateChecker(
                    siteBase = server.baseUrl,
                    installedVersion = { "3.9.0" },
                )
                runBlocking { checker.latest(UpdateSource.DIRECT, firstEver = false, forced = true) }
                assertEquals(
                    "Nightbell/3.9.0 (Android; tap)",
                    server.received.single().headers["user-agent"],
                )
            }
    }

    @Test
    fun `the version goes to the app's own site and to nobody else`() {
        // The whole point of the census agent living on one source. A monitored
        // site learning which build is watching it would be a leak this app has
        // no reason to introduce, and GitHub is a monitored site as far as this
        // question is concerned.
        TinyHttpServer { request ->
            val body = if (request.path.startsWith("/repos")) githubRelease else fdroidPackage
            TinyHttpServer.Response(body = body, contentType = "application/json")
        }.use { server ->
            val checker = UpdateChecker(
                githubBase = server.baseUrl,
                fdroidBase = server.baseUrl,
                siteBase = server.baseUrl,
                installedVersion = { "3.9.0" },
            )
            runBlocking {
                checker.latest(UpdateSource.GITHUB, firstEver = true)
                checker.latest(UpdateSource.FDROID, firstEver = true)
            }
            val agents = server.received.map { it.headers["user-agent"] }
            assertEquals(2, agents.size)
            agents.forEach { agent ->
                assertEquals(HttpChecker.USER_AGENT, agent)
                assertFalse("$agent leaked a version", agent!!.contains("3.9.0"))
                assertFalse("$agent leaked the census flag", agent.contains("new"))
            }
        }
    }

    @Test
    fun `a missing manifest is silence and not a wrong version`() {
        // The failure that matters on a deploy that forgets the file. An install
        // hearing nothing loses update notices for a while; an install hearing a
        // guess would be told something false about its own software.
        TinyHttpServer { TinyHttpServer.Response(code = 404, reason = "Not Found") }.use { server ->
            val checker = UpdateChecker(siteBase = server.baseUrl)
            assertNull(runBlocking { checker.latest(UpdateSource.DIRECT) })
        }
    }

    @Test
    fun `a manifest with no version in it is not a release`() {
        val body = """{"schema":1,"tag":"v3.9.0","notes":"Nightbell 3.9.0"}"""
        TinyHttpServer { TinyHttpServer.Response(body = body, contentType = "application/json") }
            .use { server ->
                assertNull(
                    runBlocking { UpdateChecker(siteBase = server.baseUrl).latest(UpdateSource.DIRECT) },
                )
            }
    }

    @Test
    fun `the parser agrees with the file the website actually generates`() {
        // The fixture above is a copy, and a copy is a thing that goes stale. This
        // reads the real output of website/scripts/gen-version-manifest.mjs, so
        // renaming a field on the site fails here instead of at four in the
        // morning six weeks later when every install has quietly stopped seeing
        // releases. The two ends of this feature live in different languages in
        // different directories and nothing else makes them agree.
        val generated = File("../website/public/v1/release.json")
        // Skipped rather than failed when it is not there, because the path is
        // relative to the module directory and that is a property of the runner
        // rather than of the code: Gradle sets it, an IDE run configuration
        // rooted at the repository does not. A hard failure there would be this
        // test reporting on the working directory. The build declares the file as
        // an input in app/build.gradle.kts, so under Gradle it both runs and
        // re-runs when the manifest changes, and `npm run verify` fails hard if
        // the generator was never run at all.
        assumeTrue(
            "${generated.absolutePath} is not readable from here, skipping",
            generated.isFile,
        )
        val payload = generated.readText()
        TinyHttpServer { TinyHttpServer.Response(body = payload, contentType = "application/json") }
            .use { server ->
                val release = runBlocking {
                    UpdateChecker(siteBase = server.baseUrl).latest(UpdateSource.DIRECT)
                }
                assertNotNull("the generated manifest did not parse into a release", release)
                assertTrue("no version in the generated manifest", release!!.version.isNotBlank())
                assertTrue(
                    "no apk url in the generated manifest",
                    release.apkUrl.startsWith("https://"),
                )
                assertTrue("no apk size in the generated manifest", release.apkSize > 0L)
                assertTrue("no release page in the generated manifest", release.url.startsWith("https://"))
                assertEquals(UpdateSource.DIRECT, release.source)
            }
    }

    @Test
    fun `the site being down is a non-event like any other source`() {
        val checker = UpdateChecker(siteBase = "http://127.0.0.1:1")
        assertNull(runBlocking { checker.latest(UpdateSource.DIRECT) })
    }
}
