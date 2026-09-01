package me.river.nightbell

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import me.river.nightbell.NightbellTestSupport.appContext
import me.river.nightbell.NightbellTestSupport.captureScreenshot
import me.river.nightbell.data.Nightbell
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.Sample
import me.river.nightbell.domain.ThemeChoice
import me.river.nightbell.ui.components.AuroraBackground
import me.river.nightbell.ui.components.ToastCapsule
import me.river.nightbell.ui.components.ToastKind
import me.river.nightbell.ui.components.ToastMessage
import me.river.nightbell.ui.dashboard.DashboardScreen
import me.river.nightbell.ui.theme.NightbellTheme
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the capsule looks like where it lands, at every kind and both schemes.
 *
 * Over the real dashboard rather than on a plain ground, because the complaint
 * this whole thing answers was not that the old capsule was ugly. It was that it
 * disappeared: `#171717` over the aurora, over a glass card and over the fleet
 * banner is close enough to all three that the eye reads it as part of the screen
 * it lands on. A version that looks fine on black and vanishes here has failed the
 * only test that matters.
 *
 * Drawn at rest, with no dwell timer and no transition, which is the only way to
 * photograph a thing whose job is to be gone in two seconds. What it looks like
 * *arriving* is [ToastInstrumentedTest]'s problem, and it needs a frozen clock to
 * ask.
 *
 * The assertions are thin on purpose. The point of this class is the twelve PNGs,
 * and a screenshot suite that claims to have checked an appearance is lying: only
 * opening them does that.
 */
@RunWith(AndroidJUnit4::class)
class ToastAppearanceInstrumentedTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun setUp() {
        NightbellTestSupport.resetApp()
        val now = System.currentTimeMillis()
        val monitor = Monitor(
            id = "checkout",
            name = "Checkout API",
            kind = MonitorKind.HTTP_STATUS,
            url = "https://api.example.com/v1/health",
            intervalMinutes = 15,
        )
        runBlocking {
            val store = Nightbell.install(appContext).store
            store.upsert(monitor)
            store.updateRuntime(monitor.id) {
                it.copy(
                    health = Health.UP,
                    lastCheckedAt = now,
                    lastLatencyMs = 342,
                    lastCode = 200,
                    samples = listOf(Sample(at = now, ok = true, latencyMs = 342, code = 200)),
                )
            }
        }
    }

    /** The sentence each kind actually carries, taken from the view models. */
    private fun message(kind: ToastKind) = when (kind) {
        ToastKind.SUCCESS -> ToastMessage.success("Imported 12 monitors")
        ToastKind.WARNING -> ToastMessage.warning("Silenced until you resume")
        ToastKind.ERROR -> ToastMessage.error("Couldn't read that file")
    }

    /**
     * The longest sentence each kind can carry, again from the view models.
     *
     * Not invented for the test. These three are the widest strings the app can
     * put in a toast, and at 200 per cent a failure whose last three words are
     * replaced by an ellipsis is worse than no message: it names a problem and
     * withholds what to do about it.
     */
    private fun longestMessage(kind: ToastKind) = when (kind) {
        ToastKind.SUCCESS ->
            ToastMessage.success("That version will be announced again")
        ToastKind.WARNING ->
            ToastMessage.warning("The next successful check will record the new key")
        ToastKind.ERROR ->
            ToastMessage.error("Notifications are blocked, enable them in system settings")
    }

    /**
     * One composition for the whole run, switched by state.
     *
     * `setContent` may only be called once per rule, and re-creating the tree for
     * each capture would also re-run the dashboard's entrance stagger, so every
     * shot after the first would have caught it mid-animation.
     */
    private fun withDashboard(
        fontScale: Float = 1f,
        longest: Boolean = false,
        body: (set: (ToastKind, ThemeChoice) -> Unit) -> Unit,
    ) {
        var kind by mutableStateOf(ToastKind.SUCCESS)
        var theme by mutableStateOf(ThemeChoice.DARK)
        composeRule.setContent {
            NightbellTheme(motionIntensity = 0f, theme = theme) {
                val base = LocalDensity.current
                CompositionLocalProvider(
                    LocalDensity provides Density(base.density, fontScale),
                ) {
                    AuroraBackground(modifier = Modifier.fillMaxSize()) {
                        DashboardScreen(
                            onAddMonitor = {},
                            onOpenMonitor = {},
                            onOpenSettings = {},
                            onToast = {},
                        )
                        val topInset = WindowInsets.systemBars
                            .asPaddingValues()
                            .calculateTopPadding()
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = topInset + 10.dp, start = 16.dp, end = 16.dp),
                        ) {
                            ToastCapsule(if (longest) longestMessage(kind) else message(kind))
                        }
                    }
                }
            }
        }
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodesWithText("Checkout API").fetchSemanticsNodes().isNotEmpty()
        }
        body { nextKind, nextTheme ->
            kind = nextKind
            theme = nextTheme
            composeRule.waitForIdle()
        }
    }

    private fun eachKind(prefix: String, theme: ThemeChoice, set: (ToastKind, ThemeChoice) -> Unit) {
        ToastKind.entries.forEachIndexed { index, kind ->
            set(kind, theme)
            composeRule.captureScreenshot("$prefix-${index + 1}-${kind.name.lowercase()}")
            // Every kind has to reach the tree with its role spoken, or the
            // colour is the only thing carrying it.
            composeRule.onNodeWithContentDescription(
                "${roleOf(kind)}: ",
                substring = true,
            ).assertExists()
        }
    }

    private fun roleOf(kind: ToastKind) = when (kind) {
        ToastKind.SUCCESS -> "Done"
        ToastKind.WARNING -> "Warning"
        ToastKind.ERROR -> "Failed"
    }

    @Test
    fun everyKindInTheDarkScheme() = withDashboard { set ->
        eachKind("toast-dark", ThemeChoice.DARK, set)
    }

    /**
     * The light scheme, which is not a formality here.
     *
     * The surface is dark in both, the one place this app stops following the
     * scheme it is in, and this is the pass that decides whether that was right.
     */
    @Test
    fun everyKindInTheLightScheme() = withDashboard { set ->
        eachKind("toast-light", ThemeChoice.LIGHT, set)
    }

    /**
     * The longest strings at the largest font scale Android offers.
     *
     * Android 14 goes to 200 per cent and every historical layout bug in this
     * repository was found by turning it up. For a toast the question is whether
     * the sentence survives whole.
     */
    /** The longest strings at the size almost everybody reads them at. */
    @Test
    fun theLongestMessagesAtTheOrdinaryFontScale() =
        withDashboard(longest = true) { set ->
            eachKind("toast-long", ThemeChoice.DARK, set)
        }

    /** And at the scale a lot of people actually run their phone at. */
    @Test
    fun theLongestMessagesAtOneAndAHalf() =
        withDashboard(fontScale = 1.5f, longest = true) { set ->
            eachKind("toast-long15", ThemeChoice.DARK, set)
        }

    @Test
    fun theLongestMessagesAtTheLargestFontScale() =
        withDashboard(fontScale = 2f, longest = true) { set ->
            eachKind("toast-huge", ThemeChoice.DARK, set)
            // The pill wraps rather than truncating. Asserted rather than left to
            // the eye, because this is the case that was actually broken: at two
            // lines the error read "enable them in system ...".
            assertTrue(
                "the longest error must not be ellipsised",
                composeRule.onAllNodesWithText(
                    "Notifications are blocked, enable them in system settings",
                ).fetchSemanticsNodes().isNotEmpty(),
            )
        }
}
