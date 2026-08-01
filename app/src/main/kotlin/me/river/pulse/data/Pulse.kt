package me.river.pulse.data

import android.content.Context
import me.river.pulse.data.alerts.AlertCenter
import me.river.pulse.data.check.CheckEngine
import me.river.pulse.data.check.ElementChecker
import me.river.pulse.data.check.HttpChecker
import me.river.pulse.data.work.MonitorScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Hand-rolled service locator. A DI framework would be ceremony for a graph this
 * small, and workers/receivers can bootstrap it from any [Context].
 */
object Pulse {

    @Volatile
    private var graph: Graph? = null

    class Graph(context: Context) {
        val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = PulseStore(context, appScope)
        val alerts = AlertCenter(context)
        val http = HttpChecker()
        val element = ElementChecker(context)
        val engine = CheckEngine(store, http, element, alerts)
        val scheduler = MonitorScheduler(context)
    }

    fun install(context: Context): Graph = graph ?: synchronized(this) {
        graph ?: Graph(context.applicationContext).also { graph = it }
    }

    fun require(): Graph = graph ?: error("Pulse.install() has not been called yet")

    fun from(context: Context): Graph = install(context)

    val store: PulseStore get() = require().store
    val engine: CheckEngine get() = require().engine
    val alerts: AlertCenter get() = require().alerts
    val scheduler: MonitorScheduler get() = require().scheduler
    val scope: CoroutineScope get() = require().appScope
}
