package me.river.pulse.ui.theme

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Genuine backdrop blur on API 31+, and an honest opaque pane everywhere else.
 *
 * ### How it works
 * The scrolling subtree records itself into a [GraphicsLayer] carrying a
 * [BlurEffect]. An overlay pane draws *that same layer*, translated by the
 * difference between its own position and the host's, clipped to its own shape
 * — so it shows a blurred copy of exactly the pixels underneath it.
 * `RenderEffect` is a GPU pass, so the blur itself is close to free.
 *
 * ### Why the source/sink split
 * A pane cannot blur a layer it is itself recorded into: frame N would contain
 * frame N−1's blurred pane, smearing worse every frame. [BackdropScope] forces
 * the roles apart — `recordBackdrop` marks the source, `backdropBlur` marks a
 * sink, and sinks live in the overlay slot, outside the recording.
 *
 * ### Why `Modifier.Node` and not `drawBehind`
 * Compose only redraws a node when state *it* reads changes. A pane that draws
 * a layer somebody else records reads nothing, so scrolling the content behind
 * it would leave a frozen blur. The source node therefore calls
 * [DrawModifierNode.invalidateDraw] on every registered sink right after it
 * records. Sinks draw later in the tree than the source, so they pick up the
 * fresh recording in the same frame. This cannot loop: invalidating a sink
 * never dirties the source.
 *
 * ### Fallbacks
 * `RenderEffect` is API 31+. Below that — and when the user turns
 * `realBlurEnabled` off — a sink degrades to the flat pane the app shipped
 * with. The scrim is painted over the blur in both modes, so text contrast is
 * identical either way and nothing ever becomes see-through.
 */
object Backdrop {
    /** Real blur needs `android.graphics.RenderEffect`, added in API 31. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Blur sigma in pixels. High enough that glyphs behind a pane are entirely
     * unreadable — half-legible text under a toolbar is worse than none.
     */
    const val RADIUS_PX = 32f
}

/**
 * Shared state between one source and its sinks. Identity-compared by the
 * modifier elements, so it must be remembered.
 *
 * Two layers, not one. [GraphicsLayer.renderEffect] applies every time a layer
 * is drawn, so a single blurred layer would blur the actual screen as well as
 * the pane — the content itself would go out of focus. [sharp] is what the user
 * sees; [blurred] re-records it through the effect and is what sinks sample.
 * The second recording is a layer composite, not a re-draw of the tree, so it
 * costs one GPU blit.
 */
@Stable
class BackdropState internal constructor(
    internal val sharp: GraphicsLayer,
    internal val blurred: GraphicsLayer,
) {
    internal var hostPosition: Offset = Offset.Zero
    private val sinks = mutableListOf<DrawModifierNode>()

    internal fun register(node: DrawModifierNode) {
        sinks += node
    }

    internal fun unregister(node: DrawModifierNode) {
        sinks -= node
    }

    internal fun invalidateSinks() {
        for (index in sinks.indices) sinks[index].invalidateDraw()
    }
}

@Stable
class BackdropScope internal constructor(internal val state: BackdropState?) {

    /** Marks the subtree whose pixels overlay panes may blur. */
    fun Modifier.recordBackdrop(): Modifier {
        val active = state ?: return this
        return this then BackdropSourceElement(active)
    }

    /**
     * Frosted-glass pane. Falls back to an opaque fill when real blur is off or
     * unsupported, so callers never branch on API level.
     *
     * @param scrim tint painted over the blur; this is what carries the text
     *   contrast and prevents the see-through-panel problem.
     */
    @Composable
    fun Modifier.backdropBlur(
        shape: Shape,
        scrim: Color = NightbellColors.GlassFill,
        fallback: Color = NightbellColors.ToastFill,
        border: Color = NightbellColors.GlassStrokeSoft,
    ): Modifier {
        val active = state
            ?: return clip(shape).background(fallback).border(1.dp, border, shape)
        return clip(shape)
            .then(BackdropSinkElement(active, scrim))
            .border(1.dp, border, shape)
    }
}

/**
 * Hosts a blurrable backdrop.
 *
 * [content] is recorded; [overlay] draws on top and may call
 * `Modifier.backdropBlur`. Anything in [overlay] is deliberately outside the
 * recording.
 */
@Composable
fun BackdropHost(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    radiusPx: Float = Backdrop.RADIUS_PX,
    content: @Composable BackdropScope.() -> Unit,
    overlay: @Composable BoxScope.(BackdropScope) -> Unit = {},
) {
    val active = enabled && Backdrop.isSupported
    // Called unconditionally: skipping a remember on some frames would desync
    // the composition's slot table.
    val sharp = rememberGraphicsLayer()
    val blurred = rememberGraphicsLayer()
    val state = remember(sharp, blurred) { BackdropState(sharp, blurred) }

    // Only the sampled copy carries the effect; `sharp` must stay in focus.
    blurred.renderEffect = if (active) BlurEffect(radiusPx, radiusPx, TileMode.Clamp) else null

    val scope = remember(active, state) { BackdropScope(if (active) state else null) }

    Box(
        modifier.then(
            if (active) BackdropHostElement(state) else Modifier,
        ),
    ) {
        Box(Modifier.fillMaxSize()) { scope.content() }
        overlay(scope)
    }
}

// ---------------------------------------------------------------- host anchor

private data class BackdropHostElement(
    val state: BackdropState,
) : ModifierNodeElement<BackdropHostNode>() {
    override fun create() = BackdropHostNode(state)
    override fun update(node: BackdropHostNode) {
        node.state = state
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "backdropHost"
    }
}

private class BackdropHostNode(
    var state: BackdropState,
) : Modifier.Node(), GlobalPositionAwareModifierNode {
    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        state.hostPosition = coordinates.positionInRoot()
    }
}

// -------------------------------------------------------------------- source

private data class BackdropSourceElement(
    val state: BackdropState,
) : ModifierNodeElement<BackdropSourceNode>() {
    override fun create() = BackdropSourceNode(state)
    override fun update(node: BackdropSourceNode) {
        node.state = state
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "recordBackdrop"
    }
}

private class BackdropSourceNode(
    var state: BackdropState,
) : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        val sharp = state.sharp
        sharp.record { this@draw.drawContent() }
        // Re-record the finished layer through the blur. Compositing an
        // existing layer, not re-running the draw tree.
        state.blurred.record { drawLayer(sharp) }
        // What actually reaches the screen is the un-blurred copy.
        drawLayer(sharp)
        // Sinks draw after us in the same pass, so this lands the same frame.
        state.invalidateSinks()
    }
}

// ---------------------------------------------------------------------- sink

private data class BackdropSinkElement(
    val state: BackdropState,
    val scrim: Color,
) : ModifierNodeElement<BackdropSinkNode>() {
    override fun create() = BackdropSinkNode(state, scrim)
    override fun update(node: BackdropSinkNode) {
        node.rebind(state)
        node.scrim = scrim
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "backdropBlur"
        properties["scrim"] = scrim
    }
}

private class BackdropSinkNode(
    private var state: BackdropState,
    var scrim: Color,
) : Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode {

    private var position = Offset.Zero

    fun rebind(next: BackdropState) {
        if (next === state) return
        if (isAttached) {
            state.unregister(this)
            next.register(this)
        }
        state = next
    }

    override fun onAttach() {
        state.register(this)
    }

    override fun onDetach() {
        state.unregister(this)
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val next = coordinates.positionInRoot()
        if (next != position) {
            position = next
            invalidateDraw()
        }
    }

    override fun ContentDrawScope.draw() {
        val host = state.hostPosition
        translate(host.x - position.x, host.y - position.y) {
            drawLayer(state.blurred)
        }
        drawRect(scrim)
        drawContent()
    }
}

/**
 * The treatment used by sheets that float over content: a frosted pane with a
 * hairline edge, generously scrimmed so a form inside it stays legible against
 * whatever scrolls underneath.
 */
@Composable
fun Modifier.sheetSurface(
    scope: BackdropScope,
    corner: Dp = NightbellRadii.sheet,
): Modifier = with(scope) {
    val shape = RoundedCornerShape(topStart = corner, topEnd = corner)
    backdropBlur(
        shape = shape,
        // Deliberately heavy. A sheet holding a form has to win against the
        // content behind it, and a mostly-opaque pane over a real blur still
        // reads unmistakably as glass.
        scrim = NightbellColors.SheetScrim,
        fallback = NightbellColors.ToastFill,
        border = NightbellColors.GlassStrokeSoft,
    )
}
