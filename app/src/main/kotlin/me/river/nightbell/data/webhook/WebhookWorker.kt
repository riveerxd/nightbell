package me.river.nightbell.data.webhook

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import kotlin.coroutines.cancellation.CancellationException
import me.river.nightbell.data.Nightbell
import me.river.nightbell.data.diag.Diag
import me.river.nightbell.domain.LogEvent

/** Delivers whatever the outbox has due. Scheduled by [WebhookWake]. */
class WebhookWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        Nightbell.install(applicationContext).webhooks.flush()
        Result.success()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        // Success anyway. The outbox still holds whatever failed, and the flush
        // that runs next re-arms the wake from it; a WorkManager retry on top
        // would be a second schedule fighting the first.
        Diag.logError(LogEvent.WEBHOOK_FAILED, error)
        Result.success()
    }
}

/**
 * Turns "wake me at" into WorkManager work.
 *
 * Each wake is named after its minute and enqueued with KEEP, rather than one
 * name replaced each time. A flush running inside [WebhookWorker] arms the next
 * wake on its way out, and replacing the work it is itself running as would
 * cancel it mid-write, the same self-cancelling shape that once made every
 * monitor report "Checker crashed" (see MonitorWorker). A spare wake that finds
 * an empty outbox reads the store once and stops, so the cost of never
 * cancelling one is nothing.
 */
class WebhookWake(private val context: Context, private val nowMs: () -> Long = System::currentTimeMillis) {

    fun at(wakeAtMs: Long?) {
        if (wakeAtMs == null) return
        val delay = (wakeAtMs - nowMs()).coerceAtLeast(0L)
        val request = OneTimeWorkRequestBuilder<WebhookWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .addTag(TAG)
            .build()
        runCatching {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "$NAME.${wakeAtMs / 60_000}",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }

    companion object {
        const val TAG = "nightbell.webhook"
        private const val NAME = "nightbell.webhook.wake"
    }
}
