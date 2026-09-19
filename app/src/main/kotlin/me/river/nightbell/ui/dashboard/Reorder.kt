package me.river.nightbell.ui.dashboard

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Drag-to-reorder for the dashboard grid.
 *
 * Hand-rolled rather than pulled in, and deliberately grid-aware: the dashboard is
 * a `LazyVerticalGrid` so it can show two or three columns on a tablet or in
 * landscape, which means "where did the finger land" is a two-dimensional question
 * and the usual list-reorder trick of comparing y offsets does not answer it.
 *
 * Two decisions worth stating:
 *
 *  - **A handle, not a long-press.** Long-press already enters bulk-selection mode.
 *    Overloading it would make the two features fight, and the loser would be
 *    whichever one the user did not mean. The handle also gives the gesture a
 *    visible affordance, which a hidden long-press never does.
 *  - **The finger stays glued.** When a drag crosses into another card's slot the
 *    list reorders underneath immediately, which moves the dragged card's own slot.
 *    Its start offset is rebased to the slot it just took and the accumulated delta
 *    is recomputed against it, so the card does not jump out from under the thumb at
 *    the moment of the swap.
 */
class GridReorderState(
    private val gridState: LazyGridState,
    private val scope: CoroutineScope,
) {
    /** Key of the card currently under the finger, or null when idle. */
    var draggingKey: Any? by mutableStateOf(null)
        private set

    /** Pixels the dragged card is displaced from its laid-out slot. */
    var delta: Offset by mutableStateOf(Offset.Zero)
        private set

    /** -1 to scroll up, 1 to scroll down, 0 to hold. Driven by proximity to an edge. */
    var autoScroll: Int by mutableStateOf(0)
        private set

    private var slotOffset = IntOffset.Zero
    private var slotSize = IntSize.Zero

    /**
     * Where the dragged card logically sits, tracked here rather than read back from
     * the layout.
     *
     * This was the one real bug in the first version. `from` was taken from
     * `layoutInfo` on every pointer event, but a lazy layout does not re-measure
     * between events — and touch moves routinely arrive several to a frame. So each
     * event re-issued the *same* swap against the same stale index and the list
     * flip-flopped, ending up exactly where it started on an even number of events.
     * The index has to advance the moment a move is issued, not when the layout
     * catches up.
     */
    private var currentIndex = -1

    /** The drop animation, held so a new pickup can take the state back. */
    private var settle: Job? = null

    private fun info(key: Any): LazyGridItemInfo? =
        gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    fun start(key: Any) {
        val item = info(key) ?: return
        // A pickup during another card's settle wins outright. Leaving that
        // animation running would keep writing to `delta`, which now belongs to
        // this card.
        settle?.cancel()
        settle = null
        draggingKey = key
        slotOffset = item.offset
        slotSize = item.size
        currentIndex = item.index
        delta = Offset.Zero
    }

    /**
     * @param reorderableKeys the monitor ids that may be dropped onto. Passed in
     *   rather than inferred: the header, the fleet banner, the controls card and the
     *   footer are all full-span grid items with plain String keys too, and a
     *   "key is String" test would happily let a card be dropped into the footer.
     * @param onMove called with the dragged and target monitor ids
     */
    fun drag(
        change: Offset,
        reorderableKeys: Set<String>,
        onMove: (fromId: String, toId: String) -> Unit,
    ) {
        val key = draggingKey ?: return
        delta += change

        val centre = Offset(
            slotOffset.x + slotSize.width / 2f + delta.x,
            slotOffset.y + slotSize.height / 2f + delta.y,
        )

        // Only monitor cards are candidates, and never the slot we already hold —
        // `index != currentIndex` is what stops a stale layout re-issuing a swap it
        // has already been given.
        val target = gridState.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            item.key != key &&
                item.index != currentIndex &&
                item.key in reorderableKeys &&
                centre.x >= item.offset.x &&
                centre.x <= item.offset.x + item.size.width &&
                centre.y >= item.offset.y &&
                centre.y <= item.offset.y + item.size.height
        }
        if (target != null) {
            // Rebase onto the slot we are about to occupy, so the card stays exactly
            // where the finger is holding it.
            slotOffset = target.offset
            slotSize = target.size
            delta = Offset(
                centre.x - (target.offset.x + target.size.width / 2f),
                centre.y - (target.offset.y + target.size.height / 2f),
            )
            // Reported as ids, not indices. Grid indices depend on how many
            // full-span items happen to precede the cards — header, banner, and now a
            // disclosure panel or a narrowing strip that come and go — and every one
            // of those was an off-by-one waiting to happen. `currentIndex` stays, but
            // only as the guard against a stale layout re-issuing a move.
            onMove(key as String, target.key as String)
            currentIndex = target.index
        }

        val viewport = gridState.layoutInfo.viewportSize.height
        val margin = slotSize.height.coerceAtMost(220).coerceAtLeast(80)
        autoScroll = when {
            viewport <= 0 -> 0
            centre.y < margin -> -1
            centre.y > viewport - margin -> 1
            else -> 0
        }
    }

    /**
     * Put the card down where it landed, rather than teleporting it there.
     *
     * The first version of this zeroed [delta] on release, which meant the card
     * jumped from under the finger to its slot in a single frame: it was reported
     * as snapping into place too instantly, and it is the one moment in the
     * gesture with nothing to explain the movement. Everything else about a drag
     * is already animated, so the drop was the only cut.
     *
     * [draggingKey] is held for the length of the settle, which is what keeps the
     * card lifted and on top while it travels the last few pixels. It sets down
     * afterwards, in that order, because a card that flattens first and then
     * slides is a card sliding along the surface rather than being placed on it.
     *
     * Not animated when the user has turned motion off, and not animated when
     * there is nothing to travel: an accessibility nudge moves nothing on screen
     * and would otherwise pay for an animation of zero pixels.
     */
    fun end(animate: Boolean = true) {
        autoScroll = 0
        currentIndex = -1
        val key = draggingKey
        val from = delta
        settle?.cancel()
        settle = null
        if (key == null || !animate || from == Offset.Zero) {
            draggingKey = null
            delta = Offset.Zero
            return
        }
        settle = scope.launch {
            try {
                Animatable(from, Offset.VectorConverter).animateTo(
                    targetValue = Offset.Zero,
                    // Just short of critically damped: it arrives without a
                    // bounce, because a monitor card springing about is decoration
                    // and this movement is only here to be followable.
                    animationSpec = spring(
                        dampingRatio = 0.9f,
                        stiffness = Spring.StiffnessMedium,
                        visibilityThreshold = Offset.VisibilityThreshold,
                    ),
                ) { delta = value }
            } finally {
                // Also on cancellation, so a second pickup mid-settle does not
                // leave the previous card displaced forever.
                delta = Offset.Zero
                if (draggingKey == key) draggingKey = null
            }
        }
    }

    /** One step of edge scrolling. Called from a loop while [autoScroll] is non-zero. */
    suspend fun scrollStep() {
        gridState.scrollBy(autoScroll * AUTO_SCROLL_STEP)
    }

    fun launchScroll() {
        scope.launch { scrollStep() }
    }

    private companion object {
        const val AUTO_SCROLL_STEP = 14f
    }
}

@Composable
fun rememberGridReorderState(
    gridState: LazyGridState,
    scope: CoroutineScope,
): GridReorderState = remember(gridState, scope) { GridReorderState(gridState, scope) }

/**
 * Everything a card needs to be picked up and moved, or null when it cannot be.
 *
 * Passed down instead of a handle composable. There used to be a grip drawn in
 * every card's title row, and it was a lot of chrome for a mode that already
 * announces itself in a bar at the bottom of the screen: the whole card is the
 * target now, and holding it is the gesture.
 *
 * [onMoveUp] and [onMoveDown] are not optional extras. A drag is unusable with a
 * screen reader, and reordering is exactly the kind of feature that ships
 * mouse-only unless the same capability exists as an action. They were attached
 * to the grip; with the grip gone they belong on the card.
 */
data class CardReorder(
    /** Run from the card's own `onLongClick`. See [dragWhileHeld]. */
    val onPickUp: () -> Unit,
    /** Whether this card is the one currently off the surface. */
    val isHeld: () -> Boolean,
    val onDrag: (Offset) -> Unit,
    val onDrop: () -> Unit,
    /** Null at the top of the list, where there is nowhere up to go. */
    val onMoveUp: (() -> Unit)?,
    /** Null at the bottom, likewise. */
    val onMoveDown: (() -> Unit)?,
)

/**
 * The drag half of a hold, for a card whose hold is already handled elsewhere.
 *
 * This does not detect the long press. The card is a `combinedClickable` and its
 * own `onLongClick` is what picks the monitor up, for a reason that cost a suite
 * to find: a detector layered outside the clickable cannot win. The clickable is
 * the inner node, so it sees the finger lift first, and with no long-click handler
 * of its own it reported that lift as an ordinary tap. Holding a card opened it,
 * and consuming the event afterwards was already too late. With `onLongClick` set,
 * Compose's tap detector fires the long press itself and then swallows everything
 * up to the release, which is exactly the behaviour wanted.
 *
 * What is left for this to do is read the movement after the pickup and say when
 * the finger came off. [isHeld] is how it knows a pickup happened at all, and the
 * changes it reads are already consumed by the clickable, hence the
 * ignore-consumed variants throughout.
 *
 * [onDrop] fires on release whenever the card was held, travelled or not. Which of
 * the two things the gesture meant is the caller's to decide, because "it moved"
 * has to mean the order changed rather than the finger wobbled.
 */
fun Modifier.dragWhileHeld(
    key: Any,
    isHeld: () -> Boolean,
    onDrag: (Offset) -> Unit,
    onDrop: () -> Unit,
): Modifier = pointerInput(key) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.changedToUpIgnoreConsumed()) break
            if (isHeld()) onDrag(change.positionChangeIgnoreConsumed())
        }
        if (isHeld()) onDrop()
    }
}
