package me.river.nightbell.domain

/**
 * A report's parts sorted by what they ask of the person reading them: the ones
 * paging, the ones marked as expected, and the ones that are fine.
 *
 * The detail screen used to list every part in one column, worst first, with
 * the way to mark one as expected inside an expanded row. On a report with a
 * dozen parts the one that needed attention, and the button for it, were lost
 * among the ten that did not.
 */
data class HealthTriage(
    /** Not up and not expected, worst first, then in the service's order. */
    val failing: List<HealthComponent>,
    val expected: List<HealthComponent>,
    val healthy: List<HealthComponent>,
) {
    val isEmpty: Boolean get() = failing.isEmpty() && expected.isEmpty() && healthy.isEmpty()

    /** "1 down · 1 degraded · 1 expected · 10 up", leaving out what is zero. */
    val tally: String
        get() = listOf(
            failing.count { it.status == HealthStatus.DOWN } to "down",
            failing.count { it.status == HealthStatus.DEGRADED } to "degraded",
            expected.size to "expected",
            healthy.size to "up",
        ).filter { it.first > 0 }.joinToString(" · ") { (n, word) -> "$n $word" }

    /**
     * The sentence under the verdict when nothing is failing but something is
     * expected. Said in full, because a green screen over a report with a part
     * down is the one state that needs explaining.
     */
    val expectedLine: String
        get() = when (expected.size) {
            0 -> ""
            1 -> "${expected[0].displayName} is ${expected[0].status.label.lowercase()}, and you marked that expected. " +
                "Nothing else needs you."
            else -> "${expected.size} parts are failing as expected. Nothing else needs you."
        }

    companion object {
        fun of(state: HealthState, expected: Map<String, HealthStatus>): HealthTriage {
            val parts = state.components
            val held = parts.filter { HealthExpectation.covers(expected, it) }
            val failing = parts.withIndex()
                .filter { (_, p) -> p.status != HealthStatus.UP && !HealthExpectation.covers(expected, p) }
                .sortedWith(compareByDescending<IndexedValue<HealthComponent>> { it.value.status.rank }.thenBy { it.index })
                .map { it.value }
            return HealthTriage(failing, held, parts.filter { it.status == HealthStatus.UP })
        }
    }
}
