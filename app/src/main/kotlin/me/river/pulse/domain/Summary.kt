package me.river.pulse.domain

/**
 * Fleet-level roll-up.
 *
 * Lives in `domain` — free of Android types — because three very different
 * surfaces need exactly the same verdict and must never disagree: the
 * dashboard header, the home-screen widget, and (see HANDOFF) a Wear tile.
 */
object Summary {

    /**
     * How loudly a state should be shouted about, worst first. This is the sort
     * key everywhere "the worst monitor" is needed.
     */
    fun severity(health: Health): Int = when (health) {
        Health.DOWN -> 0
        Health.DEGRADED -> 1
        Health.UNKNOWN -> 2
        Health.UP -> 3
        Health.PAUSED -> 4
    }

    data class Entry(
        val id: String,
        val name: String,
        val host: String,
        val health: Health,
        val latencyMs: Long,
        val lastCheckedAt: Long,
        val urgent: Boolean,
        /** Urgent outage still waiting for someone to acknowledge it. */
        val urgentNagging: Boolean,
        val message: String,
    )

    data class Fleet(
        val entries: List<Entry> = emptyList(),
    ) {
        val total: Int get() = entries.size
        val down: Int get() = entries.count { it.health == Health.DOWN }
        val degraded: Int get() = entries.count { it.health == Health.DEGRADED }
        val paused: Int get() = entries.count { it.health == Health.PAUSED }
        val urgentPending: Int get() = entries.count { it.urgentNagging }

        /** Sorted worst-first, then by name so the order is stable frame to frame. */
        val ranked: List<Entry>
            get() = entries.sortedWith(
                compareBy({ severity(it.health) }, { !it.urgentNagging }, { it.name.lowercase() }),
            )

        val worst: Entry? get() = ranked.firstOrNull()

        val worstHealth: Health
            get() = worst?.health ?: Health.UNKNOWN

        /** One line for a widget, a tile, or a foreground-service notification. */
        val headline: String
            get() = when {
                total == 0 -> "No monitors yet"
                down == 1 -> "1 of $total is down"
                down > 1 -> "$down of $total are down"
                degraded == 1 -> "1 of $total is slow"
                degraded > 1 -> "$degraded of $total are slow"
                entries.all { it.health == Health.PAUSED } -> "All $total paused"
                else -> "All $total operational"
            }
    }

    fun of(monitors: List<Monitor>, runtimes: Map<String, MonitorRuntime>): Fleet = Fleet(
        monitors.map { monitor ->
            val runtime = runtimes[monitor.id] ?: MonitorRuntime()
            Entry(
                id = monitor.id,
                name = monitor.displayName,
                host = monitor.prettyHost,
                health = if (!monitor.enabled) Health.PAUSED else runtime.health,
                latencyMs = runtime.lastLatencyMs,
                lastCheckedAt = runtime.lastCheckedAt,
                urgent = monitor.urgent,
                urgentNagging = monitor.urgent && runtime.urgentState.nagging,
                message = runtime.lastMessage,
            )
        },
    )
}
