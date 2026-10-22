package me.river.nightbell.domain

/**
 * Parts of a health report the user has marked as expected to be failing.
 *
 * Asked for from real use: a staging API that is red on purpose paged every
 * check, and the only ways to stop it (mute, or switching alerts off) also hid
 * the next real change. An expectation is a part's id and the status it had
 * when it was marked. While the part keeps that status it is left out of the
 * verdict; the first report that shows it with any other status drops the
 * expectation, so the next outage pages like any other.
 *
 * Only the status is compared, never the message. Staging messages carry
 * latencies and counts that change on every check, and keying on them would
 * page on every check, which is the thing this exists to stop.
 */
object HealthExpectation {

    /** What one check came to once the expected parts were taken out. */
    data class Applied(val result: CheckResult, val expected: Map<String, HealthStatus>)

    fun covers(expected: Map<String, HealthStatus>, part: HealthComponent): Boolean =
        part.status != HealthStatus.UP && expected[part.id] == part.status

    /**
     * The expectations a fresh report leaves standing.
     *
     * A part that is missing from the report keeps its expectation: a report
     * that skipped it once is not news about it, and dropping it would page on
     * the next blip.
     */
    fun prune(expected: Map<String, HealthStatus>, components: List<HealthComponent>): Map<String, HealthStatus> {
        if (expected.isEmpty()) return expected
        val byId = components.associateBy { it.id }
        return expected.filter { (id, status) -> byId[id]?.let { it.status == status } ?: true }
    }

    /**
     * The verdict once expected parts are left out, or null when there was no
     * report to judge.
     *
     * Lowered only when the parts explain the report's verdict. A report that
     * says down while no part says worse than degraded has a reason Nightbell
     * cannot see, and an override (an unknown word, a stale report) is
     * Nightbell's own judgement about the document, not about a part.
     */
    fun verdict(state: HealthState, expected: Map<String, HealthStatus>): HealthStatus? {
        val stated = state.verdict ?: return null
        if (stated == HealthStatus.UP || state.override.isNotBlank() || expected.isEmpty()) return stated
        val parts = state.components
        val worstOfAll = parts.maxOfOrNull { it.status.rank } ?: return stated
        if (worstOfAll < stated.rank) return stated
        if (parts.none { covers(expected, it) }) return stated
        val left = parts.filter { !covers(expected, it) }.maxOfOrNull { it.status.rank } ?: HealthStatus.UP.rank
        return HealthStatus.entries.first { it.rank == minOf(left, stated.rank) }
    }

    /**
     * Folds a health check's result through the expectations.
     *
     * A result that read no report (a timeout, a login page, a refused token)
     * changes nothing and drops nothing: it is not evidence about any part.
     */
    fun apply(result: CheckResult, expected: Map<String, HealthStatus>): Applied {
        val state = result.health
        if (state?.verdict == null || expected.isEmpty()) return Applied(result, expected)
        val kept = prune(expected, state.components)
        val stated = state.verdict
        if (state.components.none { covers(kept, it) }) return Applied(result, kept)
        val effective = verdict(state, kept) ?: stated
        val left = state.components.filter { !covers(kept, it) }
        val troubles = HealthFormat.troubleLines(left).joinToString("\n")
        val adjusted = when (effective) {
            HealthStatus.DOWN -> result.copy(
                // The document's own message stays. One Nightbell wrote from the
                // first failing part is written again, because that part may be
                // the expected one.
                message = if (result.message == HealthFormat.downMessage("", state.components)) {
                    HealthFormat.downMessage("", left)
                } else {
                    result.message
                },
                detail = troubles,
            )
            else -> result.copy(
                ok = true,
                failureKind = FailureKind.NONE,
                degraded = effective == HealthStatus.DEGRADED,
                message = "Reported ${effective.label.lowercase()} in ${result.latencyMs}ms",
                detail = troubles,
            )
        }
        return Applied(adjusted, kept)
    }
}
