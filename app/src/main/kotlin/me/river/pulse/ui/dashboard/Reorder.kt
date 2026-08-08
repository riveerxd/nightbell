package me.river.pulse.ui.dashboard

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.river.pulse.ui.components.MinTouchTarget
import me.river.pulse.ui.icons.NightbellIcons
import me.river.pulse.ui.theme.NightbellColors

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

    private fun info(key: Any): LazyGridItemInfo? =
        gridState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key }

    fun start(key: Any) {
        val item = info(key) ?: return
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

    fun end() {
        draggingKey = null
        delta = Offset.Zero
        autoScroll = 0
        currentIndex = -1
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
 * The grip.
 *
 * Carries "move up" and "move down" as accessibility actions as well as accepting a
 * drag, because a drag gesture is unusable with a screen reader and reordering is
 * exactly the kind of feature that gets shipped mouse-only. TalkBack users get the
 * same capability through the actions menu.
 */
@Composable
fun ReorderHandle(
    monitorName: String,
    onDragStart: () -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    Box(
        modifier
            .size(MinTouchTarget)
            // Not detectDragGestures.
            //
            // The handle lives inside a LazyVerticalGrid, and a scrollable ancestor
            // claims a vertical drag the moment it crosses touch slop.
            // detectDragGestures does not consume anything while it waits for slop,
            // so the grid won every gesture and onDragStart never fired at all — the
            // card simply scrolled instead of lifting.
            //
            // Consuming the down denies the grid the gesture up front, which is
            // exactly right for a dedicated grip: nobody wants to scroll the list by
            // starting on the reorder handle. The drag is then driven by hand so the
            // start can be deferred to the first real movement — otherwise a plain
            // tap on the grip would visibly pick the card up and put it back.
            .pointerInput(monitorName) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    var started = false
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (change.changedToUpIgnoreConsumed()) break
                        val amount = change.positionChange()
                        if (amount != Offset.Zero) {
                            if (!started) {
                                started = true
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onDragStart()
                            }
                            change.consume()
                            onDrag(amount)
                        }
                    }
                    if (started) onDragEnd()
                }
            }
            .semantics {
                contentDescription = "Reorder $monitorName"
                customActions = buildList {
                    onMoveUp?.let { add(CustomAccessibilityAction("Move up") { it(); true }) }
                    onMoveDown?.let { add(CustomAccessibilityAction("Move down") { it(); true }) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = NightbellIcons.Grip,
            contentDescription = null,
            tint = NightbellColors.TextTertiary,
            modifier = Modifier.size(18.dp),
        )
    }
}
