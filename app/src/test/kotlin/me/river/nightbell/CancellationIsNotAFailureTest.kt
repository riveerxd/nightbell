package me.river.nightbell

import me.river.nightbell.data.check.HttpChecker
import me.river.nightbell.domain.CheckResult
import me.river.nightbell.domain.FailureKind
import me.river.nightbell.domain.Monitor
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.runCatchingCancellable
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Cancellation must never come out of the check pipeline as a verdict.
 *
 * Exercised against real sockets, because the bug lived in the gap between what
 * OkHttp throws and what the coroutine machinery throws. `CancellationException`
 * is an `IllegalStateException`, *not* an `IOException`, so `HttpChecker.classify`
 * used to drop it into `FailureKind.UNKNOWN` and report "Check failed" — and
 * `CheckEngine`'s `catch (Throwable)` turned it into a failed check called
 * "Checker crashed", which then escalated through the down track into the URGENT
 * nag loop. On the reported device that produced six ongoing, DND-bypassing
 * "URGENT · … is down" notifications for six monitors that were all fine.
 */
class CancellationIsNotAFailureTest {

    private val checker = HttpChecker()

    private fun monitor(url: String) = Monitor(
        id = "cancel-test",
        name = "Cancel test",
        kind = MonitorKind.HTTP_STATUS,
        url = url,
        timeoutSeconds = 10,
    )

    /** Control: the same server, the same monitor, not cancelled. */
    @Test
    fun `an uncancelled check against the same server produces a real verdict`() {
        TinyHttpServer { TinyHttpServer.Response(body = "ok", delayMs = 200) }.use { server ->
            val result = runBlocking { checker.check(monitor(server.url("/slow"))) }
            assertTrue(result.message, result.ok)
            assertEquals(FailureKind.NONE, result.failureKind)
        }
    }

    @Test
    fun `a check cancelled in flight throws instead of returning a failed result`() {
        TinyHttpServer { TinyHttpServer.Response(body = "ok", delayMs = 1_500) }.use { server ->
            var result: CheckResult? = null
            var caught: Throwable? = null

            runBlocking {
                val job = launch(Dispatchers.IO) {
                    try {
                        result = checker.check(monitor(server.url("/slow")))
                    } catch (error: Throwable) {
                        caught = error
                    }
                }
                delay(250) // let the request actually leave
                job.cancel()
                job.join()
            }

            assertNull("a cancelled check must not produce a verdict at all", result)
            assertNotNull("cancellation has to reach the caller", caught)
            assertTrue(
                "expected CancellationException, got ${caught!!::class.java.name}",
                caught is CancellationException,
            )
        }
    }

    @Test
    fun `a check started on an already-cancelled scope throws immediately`() {
        TinyHttpServer { TinyHttpServer.Response(body = "ok") }.use { server ->
            var result: CheckResult? = null
            var caught: Throwable? = null
            val scope = CoroutineScope(Job() + Dispatchers.IO)
            scope.cancel()

            runBlocking {
                val job = scope.launch {
                    try {
                        result = checker.check(monitor(server.url("/ok")))
                    } catch (error: Throwable) {
                        caught = error
                    }
                }
                job.join()
            }

            assertNull(result)
            // The body never ran, so nothing observed the exception — which is
            // exactly right. What matters is that no verdict was produced.
            assertNull(caught)
        }
    }

    @Test
    fun `a genuine network failure is still classified as one`() {
        // The counterpart guard: rethrowing cancellation must not make the checker
        // stop reporting real IO failures. Port 1 is reliably refused.
        val result = runBlocking { checker.check(monitor("http://127.0.0.1:1/")) }
        assertTrue(result.message, !result.ok)
        assertTrue(
            "expected a connect/timeout classification, got ${result.failureKind}",
            result.failureKind in setOf(FailureKind.CONNECT, FailureKind.TIMEOUT),
        )
    }

    // ---- the helper the fix is built on --------------------------------------

    @Test
    fun `runCatchingCancellable swallows ordinary failures`() {
        val outcome = runCatchingCancellable { throw IOException("socket closed") }
        assertTrue(outcome.isFailure)
        assertTrue(outcome.exceptionOrNull() is IOException)
    }

    @Test
    fun `runCatchingCancellable lets cancellation through`() {
        var escaped: Throwable? = null
        try {
            runCatchingCancellable { throw CancellationException("stopping") }
        } catch (error: Throwable) {
            escaped = error
        }
        assertTrue(
            "runCatching would have captured this; that is the whole bug",
            escaped is CancellationException,
        )
    }

    @Test
    fun `runCatchingCancellable does not interfere with cooperative cancellation`() {
        // The realistic shape: something suspending inside the block, and the job
        // cancelled underneath it. `runCatching` here would report success-ish
        // nonsense; this must propagate and end the coroutine.
        var reachedEnd = false
        var caught: Throwable? = null
        runBlocking {
            val job = launch(Dispatchers.Default) {
                try {
                    runCatchingCancellable { delay(5_000) }
                    reachedEnd = true
                } catch (error: Throwable) {
                    caught = error
                }
            }
            delay(100)
            job.cancel()
            job.join()
        }
        assertTrue(!reachedEnd)
        assertTrue(caught is CancellationException)
    }

    @Test
    fun `a value-returning block still returns its value`() {
        val outcome = runBlocking {
            async { runCatchingCancellable { 42 } }.await()
        }
        assertEquals(42, outcome.getOrNull())
    }
}
