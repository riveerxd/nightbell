package me.river.nightbell

import android.Manifest
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicInteger
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import kotlinx.coroutines.runBlocking
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.GlobalSettings
import me.river.nightbell.domain.HealthSignIn
import me.river.nightbell.domain.HealthWatch
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.SsoGrant
import me.river.nightbell.domain.ThemeChoice
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The browser sign-in, through a fake web app and a fake provider on one local
 * server.
 *
 * The web app's Sign in and the provider's login form are pressed by script on
 * the page, not by a tap: driving content inside a WebView from a test needs
 * espresso-web, which this project does not depend on. What the script cannot
 * fake is the part under test, which is that the web app's request is caught,
 * the form POSTs and the provider's 302 to the callback is caught without the
 * callback page ever loading.
 */
@RunWith(AndroidJUnit4::class)
class SsoSignInInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.POST_NOTIFICATIONS)

    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp(GlobalSettings(motionIntensity = 0f, theme = ThemeChoice.DARK))
    }

    @After
    fun tearDown() {
        scenario?.close()
    }

    /** What the fake provider does with the authorization request. */
    private enum class Answer { CODE, DENIED, NO_OFFLINE }

    private class Fake(val answer: Answer) {
        val exchanges = AtomicInteger()
        val authorizations = AtomicInteger()
        val callbackLoads = AtomicInteger()
        var lastScope = ""

        fun handle(server: TinyHttpServer, request: TinyHttpServer.Request): TinyHttpServer.Response {
            val base = server.baseUrl
            val path = request.path.substringBefore('?')
            val query = request.path.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8")
            }
            return when (path) {
                "/api/health" -> if (request.headers["authorization"] == "Bearer good-token") {
                    TinyHttpServer.Response(body = HealthPayloads.MINIMAL)
                } else {
                    TinyHttpServer.Response(code = 401, reason = "Unauthorized", body = "Missing or invalid Authorization header")
                }
                "/" -> html(
                    """<button id="go" onclick="location.href='$base/realms/r/protocol/openid-connect/auth?client_id=web&redirect_uri=${enc("$base/auth/callback")}&response_type=code&scope=openid%20email&code_challenge=x&code_challenge_method=S256&state=app'">Sign in</button>
                       <script>setTimeout(function(){document.getElementById('go').click()}, 600)</script>""",
                )
                "/realms/r/.well-known/openid-configuration" -> TinyHttpServer.Response(
                    contentType = "application/json",
                    body = """{"issuer":"$base/realms/r","authorization_endpoint":"$base/realms/r/protocol/openid-connect/auth","token_endpoint":"$base/realms/r/protocol/openid-connect/token"}""",
                )
                "/realms/r/protocol/openid-connect/auth" -> {
                    authorizations.incrementAndGet()
                    lastScope = query["scope"].orEmpty()
                    if (answer == Answer.NO_OFFLINE && lastScope.contains("offline_access")) {
                        redirect("${query["redirect_uri"]}?error=invalid_scope&state=${query["state"]}")
                    } else {
                        html(
                            """<form method="post" action="$base/login?state=${enc(query["state"].orEmpty())}&redirect=${enc(query["redirect_uri"].orEmpty())}">
                               <input name="username" value="tester"><input name="password" type="password" value="synthetic"></form>
                               <script>setTimeout(function(){document.forms[0].submit()}, 600)</script>""",
                        )
                    }
                }
                "/login" -> {
                    val target = query["redirect"].orEmpty()
                    if (answer == Answer.DENIED) {
                        redirect("$target?error=access_denied&state=${query["state"]}")
                    } else {
                        redirect("$target?code=single-use&state=${query["state"]}")
                    }
                }
                "/realms/r/protocol/openid-connect/token" -> {
                    exchanges.incrementAndGet()
                    TinyHttpServer.Response(
                        contentType = "application/json",
                        body = """{"access_token":"good-token","expires_in":300,"refresh_token":"r1"}""",
                    )
                }
                "/auth/callback" -> {
                    callbackLoads.incrementAndGet()
                    html("<p>The web app would spend the code here.</p>")
                }
                else -> TinyHttpServer.Response(code = 404, reason = "Not Found")
            }
        }

        private fun enc(value: String) = java.net.URLEncoder.encode(value, "UTF-8")

        private fun html(body: String) =
            TinyHttpServer.Response(contentType = "text/html; charset=utf-8", body = "<!doctype html><html><body>$body</body></html>")

        private fun redirect(to: String) =
            TinyHttpServer.Response(code = 302, reason = "Found", extraHeaders = mapOf("Location" to to))
    }

    private fun run(answer: Answer, shots: String, check: (Fake) -> Unit) {
        val fake = Fake(answer)
        lateinit var server: TinyHttpServer
        server = TinyHttpServer { fake.handle(server, it) }
        server.use {
            scenario = ActivityScenario.launch(MainActivity::class.java)
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Add a monitor").performClick()
            composeRule.onNodeWithText("Health endpoint").performScrollTo().performClick()
            composeRule.onNodeWithText("Continue").performClick()
            composeRule.onNodeWithContentDescription("Health endpoint URL").performScrollTo()
                .performTextInput(server.url("/api/health"))
            composeRule.onNodeWithContentDescription("Name").performScrollTo().performClick()
            Espresso.closeSoftKeyboard()
            composeRule.waitUntil(20_000) {
                composeRule.onAllNodesWithTag("health-needs-sign-in").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.captureScreenshot("$shots-01-needs-sign-in")
            composeRule.onNodeWithTag("health-sign-in").performClick()
            composeRule.waitForIdle()
            composeRule.captureScreenshot("$shots-02-overlay")
            check(fake)
        }
    }

    /**
     * A monitor moved from staging to production. The provider, client and
     * redirect it learned from the staging web app used to survive the new
     * address, and Sign in went straight to the staging login without opening
     * the production web app at all.
     */
    @Test
    fun aNewAddressSignsInThroughTheNewWebApp() {
        val staging = Fake(Answer.CODE)
        val prod = Fake(Answer.CODE)
        lateinit var stagingServer: TinyHttpServer
        lateinit var prodServer: TinyHttpServer
        stagingServer = TinyHttpServer { staging.handle(stagingServer, it) }
        prodServer = TinyHttpServer { prod.handle(prodServer, it) }
        stagingServer.use {
            prodServer.use {
                val old = stagingServer.baseUrl
                val graph = Nightbell.install(NightbellTestSupport.appContext)
                runBlocking {
                    graph.store.upsert(
                        Monitor(
                            id = "moved",
                            name = "Moved",
                            kind = MonitorKind.HEALTH_ENDPOINT,
                            url = "$old/api/health",
                            health = HealthWatch(
                                signIn = HealthSignIn.SSO,
                                grant = SsoGrant.BROWSER,
                                issuer = "$old/realms/r",
                                authorizationEndpoint = "$old/realms/r/protocol/openid-connect/auth",
                                tokenEndpoint = "$old/realms/r/protocol/openid-connect/token",
                                clientId = "web",
                                redirectUri = "$old/auth/callback",
                                scope = "openid email",
                                webAppUrl = old,
                            ),
                        ),
                    )
                }
                scenario = ActivityScenario.launch(MainActivity::class.java)
                composeRule.waitForIdle()
                composeRule.onNodeWithText("Moved").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithContentDescription("Edit monitor").performClick()
                composeRule.waitForIdle()
                composeRule.onNodeWithText("Continue").performClick()
                composeRule.waitForIdle()

                composeRule.onNodeWithContentDescription("Health endpoint URL").performScrollTo()
                    .performTextReplacement(prodServer.url("/api/health"))
                composeRule.waitForIdle()
                // The web app was typed as the endpoint's own host, so it moves with it.
                composeRule.onNodeWithText(prodServer.baseUrl).performScrollTo().assertExists()
                Espresso.closeSoftKeyboard()
                composeRule.captureScreenshot("sso-moved-01-new-address")

                composeRule.onNodeWithTag("health-browser-sign-in").performScrollTo().performClick()
                composeRule.waitUntil(30_000) {
                    composeRule.onAllNodesWithTag("health-signed-in").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithTag("health-signed-in").performScrollTo()
                composeRule.captureScreenshot("sso-moved-02-signed-in")
                assertEquals(0, staging.authorizations.get())
                assertEquals(0, staging.exchanges.get())
                assertEquals(1, prod.authorizations.get())
                assertEquals(1, prod.exchanges.get())
            }
        }
    }

    @Test
    fun theCodeIsCaughtExchangedOnceAndTheCallbackNeverLoads() = run(Answer.CODE, "sso-code") { fake ->
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("health-signed-in").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("health-signed-in").performScrollTo()
        composeRule.captureScreenshot("sso-code-03-signed-in")
        assertEquals(1, fake.exchanges.get())
        assertEquals(0, fake.callbackLoads.get())
        assertTrue(fake.lastScope.contains("offline_access"))
    }

    @Test
    fun aRefusalSaysSoAndSignsNothingIn() = run(Answer.DENIED, "sso-denied") { fake ->
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("sso-failure").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.captureScreenshot("sso-denied-03-failure")
        assertEquals(0, fake.exchanges.get())
        assertEquals(0, fake.callbackLoads.get())
    }

    @Test
    fun refusedOfflineAccessIsAskedAgainWithoutIt() = run(Answer.NO_OFFLINE, "sso-no-offline") { fake ->
        composeRule.waitUntil(30_000) {
            composeRule.onAllNodesWithTag("health-signed-in").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.captureScreenshot("sso-no-offline-03-signed-in")
        assertEquals(1, fake.exchanges.get())
        assertTrue(!fake.lastScope.contains("offline_access"))
    }
}
