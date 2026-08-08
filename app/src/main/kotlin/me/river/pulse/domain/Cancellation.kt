package me.river.pulse.domain

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching], minus the bug.
 *
 * `runCatching` catches [Throwable], and on Android a very large share of the
 * throwables crossing a coroutine boundary are [CancellationException] — the
 * framework saying "stop, I'm taking this thread/process/service back". Catching
 * that turns a normal lifecycle event into an error, and an error into a story
 * the app tells the user.
 *
 * Nightbell shipped exactly that bug: a cancelled check became a failed check called
 * "Checker crashed", which vibrated the phone about a crash that never happened.
 * See [CheckerHealth] for the full account.
 *
 * Use this anywhere `runCatching` was reaching for. Cancellation goes back up the
 * stack where it belongs; everything else is still a [Result].
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (error: Throwable) {
        Result.failure(error)
    }

/**
 * True when [error] is Android/coroutines tearing work down rather than
 * something going wrong.
 *
 * Worth having as a named predicate: the check reads as an accident otherwise,
 * and a future `catch (Throwable)` that forgets it re-introduces the bug.
 */
fun isCancellation(error: Throwable): Boolean = error is CancellationException
