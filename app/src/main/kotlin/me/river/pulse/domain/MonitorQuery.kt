package me.river.pulse.domain

/**
 * Narrowing the dashboard.
 *
 * The list had exactly one order — [Summary.ranked], worst first — and no way to
 * search, filter or group. That is the right default and it stays the default:
 * the question you open a monitoring app to answer is "is anything broken", and
 * worst-first answers it before you read a word. It stops being sufficient at
 * about a dozen monitors, when the healthy ones you are not looking for are the
 * ones filling the screen.
 *
 * Pure, so the matching and ordering rules are testable without a device, and so
 * the widget could adopt the same filter later without duplicating them.
 */
object MonitorQuery {

    enum class Filter {
        ALL,
        /** Down or degraded — the ones that want a decision. */
        PROBLEMS,
        UP,
        PAUSED,
        /** Urgent outage still waiting for an acknowledgement. */
        UNACKNOWLEDGED,
        ;

        val label: String
            get() = when (this) {
                ALL -> "All"
                PROBLEMS -> "Problems"
                UP -> "Healthy"
                PAUSED -> "Paused"
                UNACKNOWLEDGED -> "Unacked"
            }
    }

    @kotlinx.serialization.Serializable
    enum class Sort {
        /** [Summary.severity] then name. The default, and deliberately so. */
        @kotlinx.serialization.SerialName("worst_first")
        WORST_FIRST,

        /**
         * Whatever order the user dragged them into.
         *
         * Not a comparator: it is the order the monitors are *stored* in, which is
         * why dragging needs no `position` field and no migration — the store has
         * always kept a list, and every existing install already has a meaningful
         * order in it (the order things were created).
         *
         * It has to be its own mode rather than a gesture available under any sort.
         * Dragging a card while the list is ranked worst-first would arrange
         * something that the next completed check immediately re-sorts, so the work
         * would visibly undo itself seconds later.
         */
        @kotlinx.serialization.SerialName("manual")
        MANUAL,

        @kotlinx.serialization.SerialName("name")
        NAME,

        @kotlinx.serialization.SerialName("slowest")
        SLOWEST,

        /** Most recently checked first — "what has Nightbell actually looked at". */
        @kotlinx.serialization.SerialName("recent")
        RECENT,

        /** Oldest check first, which is the same list read as "what is overdue". */
        @kotlinx.serialization.SerialName("stalest")
        STALEST,
        ;

        val label: String
            get() = when (this) {
                WORST_FIRST -> "Worst first"
                MANUAL -> "My order"
                NAME -> "Name"
                SLOWEST -> "Slowest"
                RECENT -> "Just checked"
                STALEST -> "Least recent"
            }
    }

    data class Spec(
        val query: String = "",
        val filter: Filter = Filter.ALL,
        val sort: Sort = Sort.WORST_FIRST,
    ) {
        /** Untouched: the plain worst-first dashboard with everything shown. */
        val isDefault: Boolean
            get() = hidesNothing && sort == Sort.WORST_FIRST

        /**
         * Every monitor is on screen.
         *
         * Distinct from [isDefault] because a sort hides nothing. This is what the
         * "Clear" affordance keys off: offering to clear a *sort* would mean
         * offering to throw away a hand-dragged order under a harmless label.
         */
        val hidesNothing: Boolean
            get() = query.isBlank() && filter == Filter.ALL
    }

    /**
     * Health as the *dashboard* sees it, which is not quite what the runtime
     * stores: a disabled monitor reads PAUSED regardless of the last verdict, and
     * filtering has to agree with the card the user is looking at.
     */
    private fun effectiveHealth(card: MonitorCard): Health =
        if (!card.monitor.enabled) Health.PAUSED else card.runtime.health

    /**
     * Substring match over name, URL and, for page-element monitors, the element
     * labels.
     *
     * Case-insensitive and unanchored, because people search for "api" and "shop",
     * not for the beginning of a hostname. Element labels are included because on
     * a page-element monitor the nickname is often the only thing the user
     * remembers having typed.
     */
    fun matches(card: MonitorCard, query: String): Boolean {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return true
        val monitor = card.monitor
        if (monitor.displayName.lowercase().contains(needle)) return true
        if (monitor.url.lowercase().contains(needle)) return true
        if (monitor.kind.label.lowercase().contains(needle)) return true
        return monitor.targets.any { it.displayLabel.lowercase().contains(needle) }
    }

    fun keep(card: MonitorCard, filter: Filter): Boolean = when (filter) {
        Filter.ALL -> true
        Filter.PROBLEMS -> effectiveHealth(card).let {
            it == Health.DOWN || it == Health.DEGRADED
        }
        Filter.UP -> effectiveHealth(card) == Health.UP
        Filter.PAUSED -> effectiveHealth(card) == Health.PAUSED
        Filter.UNACKNOWLEDGED -> card.monitor.urgent && card.runtime.urgentState.nagging
    }

    /**
     * Apply a [Spec].
     *
     * Every ordering is total and ends in the monitor's name, because a sort that
     * ties on its key would otherwise let rows swap places between frames — the
     * list is re-derived on every check, and a jumping dashboard is worse than an
     * imperfect order.
     */
    fun apply(cards: List<MonitorCard>, spec: Spec): List<MonitorCard> {
        val kept = cards.filter { keep(it, spec.filter) && matches(it, spec.query) }
        val byName = compareBy<MonitorCard> { it.monitor.displayName.lowercase() }
        return when (spec.sort) {
            // Deliberately no sort at all: `cards` arrives in store order, and in
            // this mode store order *is* the answer.
            Sort.MANUAL -> kept
            Sort.WORST_FIRST -> kept.sortedWith(
                compareBy<MonitorCard> { Summary.severity(effectiveHealth(it)) }
                    .thenBy { !(it.monitor.urgent && it.runtime.urgentState.nagging) }
                    .then(byName),
            )
            Sort.NAME -> kept.sortedWith(byName)
            // Descending, and unchecked monitors sort last rather than first: a
            // latency of 0 means "no reading", and floating those to the top of a
            // "slowest" list would be actively misleading.
            Sort.SLOWEST -> kept.sortedWith(
                compareBy<MonitorCard> { it.runtime.lastLatencyMs <= 0L }
                    .thenByDescending { it.runtime.lastLatencyMs }
                    .then(byName),
            )
            Sort.RECENT -> kept.sortedWith(
                compareByDescending<MonitorCard> { it.runtime.lastCheckedAt }.then(byName),
            )
            // Never-checked first: those are the stalest thing there is.
            Sort.STALEST -> kept.sortedWith(
                compareBy<MonitorCard> { it.runtime.lastCheckedAt }.then(byName),
            )
        }
    }

    /**
     * Whether cards may be dragged right now.
     *
     * Only in [Sort.MANUAL], and only with nothing filtered out. Dragging within a
     * subset is ambiguous in a way that has no good answer — dropping card 2 above
     * card 5 of a filtered view says nothing about where either belongs among the
     * monitors you cannot see — so the handle is simply absent instead of guessing.
     */
    fun canReorder(spec: Spec): Boolean =
        spec.sort == Sort.MANUAL && spec.filter == Filter.ALL && spec.query.isBlank()

    /**
     * Move one id within an order.
     *
     * Returns the list unchanged for out-of-range indices rather than throwing: the
     * caller is a drag gesture reading a lazy layout, and an item can be recycled
     * out from under it mid-drag.
     */
    fun reordered(ids: List<String>, from: Int, to: Int): List<String> {
        if (from == to) return ids
        if (from !in ids.indices || to !in ids.indices) return ids
        return ids.toMutableList().apply { add(to, removeAt(from)) }
    }

    /** What to say when a filter or search has hidden everything. */
    fun emptyMessage(spec: Spec, total: Int): String = when {
        total == 0 -> "Add your first monitor and Nightbell will keep an eye on it."
        spec.query.isNotBlank() -> "Nothing matches “${spec.query.trim()}”."
        spec.filter == Filter.PROBLEMS -> "Nothing is broken. That is the answer you wanted."
        spec.filter == Filter.PAUSED -> "Nothing is paused."
        spec.filter == Filter.UP -> "Nothing is currently healthy."
        spec.filter == Filter.UNACKNOWLEDGED -> "No urgent outage is waiting on you."
        else -> "Nothing to show."
    }
}
