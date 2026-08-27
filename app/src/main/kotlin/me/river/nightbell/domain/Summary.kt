package me.river.nightbell.domain

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
        val kind: MonitorKind = MonitorKind.HTTP_STATUS,

        // ---- repository facts, for a GITHUB_REPO entry ----------------------
        // A latency reading is the wrong answer on a repository row: it measures
        // api.github.com, which nobody is watching. These are what the row shows
        // instead, and each is -1 or blank when the monitor is not watching that
        // track: a releases-only monitor gets no star count it never asked for.
        val stars: Int = -1,
        val openIssues: Int = -1,
        val releaseTag: String = "",
    ) {
        val isRepo: Boolean get() = kind == MonitorKind.GITHUB_REPO
    }

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

    /**
     * [fleetPaused] is a pause that has stopped the checks for everything.
     *
     * It reads through to every entry as [Health.PAUSED], which the headline
     * already knows how to say. Without it a placed widget went on displaying the
     * last verdict it had for as long as the pause lasted: "All 4 operational" on
     * someone's home screen for eight hours during which nothing was checked,
     * which is the one claim a monitoring app must never make falsely. A pause
     * that only stops the *alerts* is not passed here, because those readings are
     * still current.
     */
    fun of(
        monitors: List<Monitor>,
        runtimes: Map<String, MonitorRuntime>,
        fleetPaused: Boolean = false,
    ): Fleet = Fleet(
        monitors.map { monitor ->
            val runtime = runtimes[monitor.id] ?: MonitorRuntime()
            val repo = monitor.kind == MonitorKind.GITHUB_REPO && runtime.github.seeded
            val watch = monitor.github
            Entry(
                id = monitor.id,
                name = monitor.displayName,
                host = monitor.prettyHost,
                health = if (!monitor.enabled || fleetPaused) Health.PAUSED else runtime.health,
                latencyMs = runtime.lastLatencyMs,
                lastCheckedAt = runtime.lastCheckedAt,
                urgent = monitor.urgent,
                urgentNagging = monitor.urgent && runtime.urgentState.nagging,
                message = runtime.lastMessage,
                kind = monitor.kind,
                stars = if (repo && watch.notifyOnStars) runtime.github.lastStarCount else -1,
                openIssues = if (repo && (watch.notifyOnIssues || watch.watchPullRequests)) {
                    runtime.github.openIssues
                } else {
                    -1
                },
                releaseTag = if (repo && watch.watchReleases) runtime.github.lastReleaseTag else "",
            )
        },
    )
}
