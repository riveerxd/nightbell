package me.river.nightbell.data.check

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import me.river.nightbell.domain.CheckProgress

/**
 * Carried in the coroutine context of a Test now run, and nowhere else.
 *
 * A context element rather than a parameter on every checker between the setup
 * screen and the socket, and rather than a field on the shared engine: scheduled
 * checks run on the same engine at the same time, and a field would let one of
 * them report its tries onto the setup screen. A scheduled check has no sink in
 * its context, so it reports nowhere.
 *
 * [report] is called on the checker's IO thread.
 */
class ProgressSink(val report: (CheckProgress) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProgressSink>
}
