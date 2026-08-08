package me.river.nightbell.ui.dashboard

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.river.nightbell.domain.CertificateWatch
import me.river.nightbell.domain.Health
import me.river.nightbell.domain.MonitorCard
import me.river.nightbell.domain.MonitorKind
import me.river.nightbell.domain.MonitorQuery
import me.river.nightbell.domain.MonitorTemplates
import me.river.nightbell.ui.components.AnimatedCounter
import me.river.nightbell.ui.components.ButtonTone
import me.river.nightbell.ui.components.EmptyState
import me.river.nightbell.ui.components.GlassCard
import me.river.nightbell.ui.components.GlassIconButton
import me.river.nightbell.ui.components.HistoryStrip
import me.river.nightbell.ui.components.IconBadge
import me.river.nightbell.ui.components.ChipSelector
import me.river.nightbell.ui.components.GlassField
import me.river.nightbell.ui.components.MicroTag
import me.river.nightbell.ui.components.PullToRefreshLayout
import me.river.nightbell.ui.components.NightbellButton
import me.river.nightbell.ui.components.NightbellMark
import me.river.nightbell.ui.components.SectionHeader
import me.river.nightbell.ui.components.SAMPLE_WINDOW
import me.river.nightbell.ui.components.Sparkline
import me.river.nightbell.ui.components.StaggeredEntrance
import me.river.nightbell.ui.components.rememberEntranceLog
import me.river.nightbell.ui.components.rememberFavicon
import me.river.nightbell.ui.components.StatusOrb
import me.river.nightbell.ui.components.StatusPill
import me.river.nightbell.ui.components.formatLatency
import me.river.nightbell.ui.components.formatRelative
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.rememberDashboardViewModel
import me.river.nightbell.ui.theme.LocalNowMs
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.accentFor
import me.river.nightbell.ui.theme.healthColor
import me.river.nightbell.ui.theme.healthRim
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.river.nightbell.ui.theme.rememberLoopingFloat
import androidx.compose.ui.platform.testTag

/**
 * Narrowest a monitor card may be before the grid drops a column.
 *
 * A card carries a name, a host, five or six tags, a sparkline and a row of
 * actions. Below roughly this width the tag row starts wrapping and the card stops
 * being glanceable, which is the only thing it is for.
 */
private val MIN_CARD_WIDTH = 340.dp

/**
 * Monitors needed before the search and filter/sort buttons appear in the header.
 *
 * Two: with one monitor there is nothing to search for, nothing to filter out and
 * nothing to put in an order. Above that the buttons are always there, because they
 * cost two icons rather than a permanent panel.
 */
private const val TOOLS_THRESHOLD = 2

fun kindIcon(kind: MonitorKind) = when (kind) {
    MonitorKind.HTTP_STATUS -> NightbellIcons.Server
    MonitorKind.ADVANCED_REQUEST -> NightbellIcons.Braces
    MonitorKind.WEBSITE_ELEMENT -> NightbellIcons.Pointer
}

@Composable
fun DashboardScreen(
    onAddMonitor: () -> Unit,
    onOpenMonitor: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onToast: (String) -> Unit,
    onPickTemplate: (String) -> Unit = { onAddMonitor() },
) {
    val viewModel = rememberDashboardViewModel()
    val cards by viewModel.cards.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val entrance = rememberEntranceLog()
    val nowMs = LocalNowMs.current

    val toast = viewModel.toast
    if (toast != null) {
        androidx.compose.runtime.LaunchedEffect(toast) {
            onToast(toast)
            viewModel.consumeToast()
        }
    }

    val topInset = WindowInsets.systemBars.asPaddingValues().calculateTopPadding()
    val bottomInset = WindowInsets.systemBars.asPaddingValues().calculateBottomPadding()

    val visible = viewModel.visible
    val selecting = viewModel.selecting
    // Dragging and bulk selection are mutually exclusive modes; the handle is hidden
    // while selecting so the two can never be in play at once.
    val canReorder = MonitorQuery.canReorder(viewModel.spec) && !selecting
    val reorder = rememberGridReorderState(gridState, scope)
    val reorderableKeys = remember(visible) { visible.map { it.monitor.id }.toSet() }

    // Edge scrolling has to be a loop rather than a per-event nudge: a finger held
    // still at the bottom of the screen produces no drag events, and that is exactly
    // the moment the list needs to keep moving.
    androidx.compose.runtime.LaunchedEffect(reorder.autoScroll) {
        while (reorder.autoScroll != 0) {
            reorder.scrollStep()
            kotlinx.coroutines.delay(16)
        }
    }

    var panel by remember { mutableStateOf(DashboardPanel.NONE) }
    // Tools appear at two monitors: below that there is nothing to search, nothing
    // to filter and nothing to arrange.
    val showTools = cards.size >= TOOLS_THRESHOLD
    if (!showTools && panel != DashboardPanel.NONE) panel = DashboardPanel.NONE

    // One handler, explicit priority. Two competing BackHandlers would leave which
    // one wins up to composition order.
    BackHandler(enabled = selecting || panel != DashboardPanel.NONE) {
        when {
            selecting -> viewModel.clearSelection()
            else -> panel = DashboardPanel.NONE
        }
    }

    Box(Modifier.fillMaxSize()) {
        PullToRefreshLayout(
            refreshing = viewModel.refreshing,
            onRefresh = { viewModel.checkAll() },
            modifier = Modifier.fillMaxSize(),
        ) {
            // Adaptive rather than fixed: a phone gets one column, a tablet or a
            // landscape phone gets as many 340 dp columns as fit. The cards were
            // being stretched to the full width of a 10-inch screen, which turned a
            // scannable list into six very wide rows.
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val columns = ((maxWidth - 36.dp) / MIN_CARD_WIDTH).toInt().coerceIn(1, 3)
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    state = gridState,
                    modifier = Modifier.fillMaxSize().testTag("dashboard-list"),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 18.dp,
                        end = 18.dp,
                        top = topInset + 14.dp,
                        bottom = bottomInset + if (selecting) 168.dp else 128.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                        DashboardHeader(
                            showTools = showTools,
                            panel = panel,
                            narrowing = viewModel.narrowed,
                            onTogglePanel = { requested ->
                                panel = if (panel == requested) DashboardPanel.NONE else requested
                            },
                            onOpenSettings = onOpenSettings,
                        )
                    }

                    item(key = "overview", span = { GridItemSpan(maxLineSpan) }) {
                        StaggeredEntrance(index = 0, key = "overview", log = entrance) {
                            FleetBanner(
                                stats = fleetStatsOf(cards, nowMs = nowMs, offline = offline),
                                refreshing = viewModel.refreshing,
                                onCheckAll = { viewModel.checkAll() },
                            )
                        }
                    }

                    if (panel != DashboardPanel.NONE) {
                        item(key = "panel", span = { GridItemSpan(maxLineSpan) }) {
                            when (panel) {
                                DashboardPanel.SEARCH -> SearchPanel(
                                    query = viewModel.spec.query,
                                    shownCount = visible.size,
                                    totalCount = cards.size,
                                    onQuery = viewModel::setQuery,
                                    onClose = { panel = DashboardPanel.NONE },
                                )

                                DashboardPanel.TUNE -> TunePanel(
                                    spec = viewModel.spec,
                                    shownCount = visible.size,
                                    totalCount = cards.size,
                                    onFilter = viewModel::setFilter,
                                    onSort = viewModel::setSort,
                                    onClear = viewModel::clearNarrowing,
                                    onClose = { panel = DashboardPanel.NONE },
                                )

                                DashboardPanel.NONE -> Unit
                            }
                        }
                    } else if (viewModel.narrowed) {
                        item(key = "narrowing", span = { GridItemSpan(maxLineSpan) }) {
                            NarrowingStrip(
                                spec = viewModel.spec,
                                shownCount = visible.size,
                                totalCount = cards.size,
                                onClear = viewModel::clearNarrowing,
                            )
                        }
                    }

                    if (cards.isEmpty()) {
                        item(key = "first-run", span = { GridItemSpan(maxLineSpan) }) {
                            Spacer(Modifier.height(24.dp))
                            FirstRunStarter(
                                onPickTemplate = onPickTemplate,
                                onBlank = onAddMonitor,
                            )
                        }
                    } else if (visible.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                            Spacer(Modifier.height(40.dp))
                            EmptyState(
                                title = "Nothing here",
                                message = MonitorQuery.emptyMessage(viewModel.spec, cards.size),
                                icon = NightbellIcons.Search,
                                action = {
                                    NightbellButton(
                                        text = "Clear filters",
                                        onClick = viewModel::clearNarrowing,
                                        icon = NightbellIcons.Close,
                                        tone = ButtonTone.Secondary,
                                    )
                                },
                            )
                        }
                    } else {
                        // itemsIndexed rather than a lookup inside the row: recovering
                        // the index with indexOfFirst ran a scan per row per
                        // recomposition for a number the list already knew.
                        itemsIndexed(visible, key = { _, card -> card.monitor.id }) { index, card ->
                            StaggeredEntrance(index = index + 1, key = card.monitor.id, log = entrance) {
                                MonitorRowCard(
                                    card = card,
                                    certLevel = if (settings.certAlertsEnabled) {
                                        CertificateWatch.level(
                                            expiresAt = card.runtime.certExpiresAt,
                                            nowMs = nowMs,
                                            warnDays = settings.certWarnDays,
                                            criticalDays = settings.certCriticalDays,
                                        )
                                    } else {
                                        CertificateWatch.Level.UNKNOWN
                                    },
                                    selecting = selecting,
                                    selected = card.monitor.id in viewModel.selection,
                                    dragging = reorder.draggingKey == card.monitor.id,
                                    dragDelta = reorder.delta,
                                    reorderHandle = if (canReorder) {
                                        {
                                            ReorderHandle(
                                                monitorName = card.monitor.displayName,
                                                onDragStart = {
                                                    viewModel.beginReorder()
                                                    reorder.start(card.monitor.id)
                                                },
                                                onDrag = { amount ->
                                                    reorder.drag(
                                                        amount,
                                                        reorderableKeys = reorderableKeys,
                                                    ) { fromId, toId ->
                                                        viewModel.moveInReorder(fromId, toId)
                                                    }
                                                },
                                                onDragEnd = {
                                                    reorder.end()
                                                    viewModel.commitReorder()
                                                },
                                                onMoveUp = if (index > 0) {
                                                    { viewModel.nudge(card.monitor.id, -1) }
                                                } else {
                                                    null
                                                },
                                                onMoveDown = if (index < visible.lastIndex) {
                                                    { viewModel.nudge(card.monitor.id, 1) }
                                                } else {
                                                    null
                                                },
                                            )
                                        }
                                    } else {
                                        null
                                    },
                                    // In selection mode a tap toggles instead of
                                    // navigating: opening a monitor while picking
                                    // several of them is never what was meant.
                                    onOpen = {
                                        if (selecting) {
                                            viewModel.toggleSelected(card.monitor.id)
                                        } else {
                                            onOpenMonitor(card.monitor.id)
                                        }
                                    },
                                    onLongPress = { viewModel.toggleSelected(card.monitor.id) },
                                    onCheck = { viewModel.check(card.monitor.id) },
                                    onToggle = { viewModel.setEnabled(card.monitor.id, it) },
                                    onAcknowledge = { viewModel.acknowledgeUrgent(card.monitor.id) },
                                )
                            }
                        }

                        item(key = "footer", span = { GridItemSpan(maxLineSpan) }) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = if (selecting) {
                                    "Tap to add or remove, long-press anywhere to start over"
                                } else {
                                    "Pull down and hold to re-check everything · long-press a " +
                                        "card to select several"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = NightbellColors.TextTertiary,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                    }
                }
            }
        }

        // The FAB steps aside for the selection bar rather than sitting on top of
        // it: "add a monitor" is meaningless while several are selected.
        AnimatedVisibility(
            visible = !selecting,
            enter = fadeIn() + scaleIn(initialScale = 0.8f),
            exit = fadeOut() + scaleOut(targetScale = 0.8f),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = bottomInset + 26.dp),
        ) {
            MorphingFab(onClick = onAddMonitor)
        }

        SelectionBar(
            count = viewModel.selection.size,
            visible = selecting,
            onPause = { viewModel.setEnabledForSelection(false) },
            onResume = { viewModel.setEnabledForSelection(true) },
            onMute = { viewModel.muteSelection(1) },
            onDelete = viewModel::deleteSelection,
            onSelectAll = viewModel::selectAllVisible,
            onDone = viewModel::clearSelection,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(start = 14.dp, end = 14.dp, bottom = bottomInset + 18.dp),
        )
    }

    // Keep the list pinned to the top when a monitor is added.
    androidx.compose.runtime.LaunchedEffect(cards.size) {
        if (cards.isNotEmpty() && gridState.firstVisibleItemIndex <= 1) {
            scope.launch { gridState.animateScrollToItem(0) }
        }
    }
}

// ------------------------------------------------------------------- sections

/**
 * Identity only. The fleet's verdict used to live here as a subtitle; it is now
 * the [FleetBanner] directly below, which can say it far louder.
 */
@Composable
private fun DashboardHeader(
    showTools: Boolean,
    panel: DashboardPanel,
    narrowing: Boolean,
    onTogglePanel: (DashboardPanel) -> Unit,
    onOpenSettings: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        NightbellWordmark()
        Spacer(Modifier.weight(1f))
        // Entry points, not the controls themselves.
        //
        // The search field, five filter chips and five sort chips used to sit in a
        // card that was on screen permanently — a slab of chrome between the fleet
        // verdict and the monitors, present whether or not anyone was searching.
        // Hidden below two monitors, where there is nothing to search or arrange.
        if (showTools) {
            GlassIconButton(
                icon = NightbellIcons.Search,
                onClick = { onTogglePanel(DashboardPanel.SEARCH) },
                contentDescription = "Search monitors",
                size = 34.dp,
                accent = NightbellColors.TextSecondary,
                active = panel == DashboardPanel.SEARCH,
            )
            GlassIconButton(
                icon = NightbellIcons.Funnel,
                onClick = { onTogglePanel(DashboardPanel.TUNE) },
                contentDescription = "Filter and sort",
                size = 34.dp,
                accent = NightbellColors.TextSecondary,
                active = panel == DashboardPanel.TUNE,
                badged = narrowing,
            )
        }
        GlassIconButton(
            icon = NightbellIcons.Sliders,
            onClick = onOpenSettings,
            contentDescription = "Settings",
            size = 34.dp,
            accent = NightbellColors.TextSecondary,
        )
    }
}

/** Which disclosure panel, if any, is open under the header. */
enum class DashboardPanel { NONE, SEARCH, TUNE }

@Composable
private fun NightbellWordmark() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 34dp to match the GlassIconButtons at the other end of this row — the mark and
        // the controls now sit on one optical line instead of the mark looking like an
        // afterthought beside them.
        //
        // Static, where the old one had a dot sweeping along the trace. The fleet banner
        // directly underneath is already a live, moving thing, and a header with two
        // independent animations in it reads as restless rather than alive.
        NightbellMark(size = 34.dp)
        Spacer(Modifier.width(8.dp))
        // Small and tracked-out: the banner underneath is the loud thing now, and
        // two competing headlines at the top of one screen is one too many.
        Mono(
            text = "NIGHTBELL",
            color = NightbellColors.TextSecondary,
            size = 11,
            weight = androidx.compose.ui.text.font.FontWeight.Bold,
            tracking = 3.0,
            spoken = "Nightbell",
        )
    }
}

/**
 * The first-run surface.
 *
 * What used to be here was a single "Create a monitor" button opening a blank
 * four-step wizard, which asks someone who has never used the app to make five
 * decisions before seeing anything work. Each of these instead answers the
 * kind-and-expectations questions and drops the user on the URL field.
 *
 * They pre-fill a draft rather than creating a monitor: the wizard still runs, the
 * values are still visible and still editable, and nothing is saved until the user
 * says so. A template that silently created a monitor would be a template that
 * pages you at 3am about something you never read.
 */
@Composable
private fun FirstRunStarter(
    onPickTemplate: (String) -> Unit,
    onBlank: () -> Unit,
) {
    Column {
        GlassCard {
            Text(
                text = "Watch something",
                style = MaterialTheme.typography.headlineMedium,
                color = NightbellColors.TextPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Pick a starting point and Nightbell fills in the cadence, the " +
                    "expectations and the alerting. You only need a URL — everything " +
                    "else is still yours to change before it saves.",
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }
        Spacer(Modifier.height(12.dp))
        MonitorTemplates.all.forEach { template ->
            GlassCard(
                onClick = { onPickTemplate(template.id) },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = 16.dp,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(
                        icon = when (template.id) {
                            "website" -> NightbellIcons.Globe
                            "health-endpoint" -> NightbellIcons.Shield
                            "api-latency" -> NightbellIcons.Gauge
                            else -> NightbellIcons.Pointer
                        },
                        accent = NightbellColors.Aqua,
                        size = 40.dp,
                    )
                    Spacer(Modifier.width(13.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = template.title,
                            style = MaterialTheme.typography.titleLarge,
                            color = NightbellColors.TextPrimary,
                        )
                        Text(
                            text = template.blurb,
                            style = MaterialTheme.typography.bodySmall,
                            color = NightbellColors.TextTertiary,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        NightbellIcons.ChevronDown,
                        contentDescription = null,
                        tint = NightbellColors.TextTertiary,
                        modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = -90f },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
        }
        // Deliberately not full-width. The floating add button is anchored to the
        // bottom-right corner and this is the last thing in the list, so a
        // full-width button put two controls for the same action on top of each
        // other. Leaving the right third clear keeps both legible and lets the FAB
        // stay where every other dashboard state has it.
        Row(Modifier.fillMaxWidth()) {
            NightbellButton(
                text = "Start from scratch",
                onClick = onBlank,
                icon = NightbellIcons.Plus,
                tone = ButtonTone.Secondary,
                modifier = Modifier.weight(0.68f),
            )
            Spacer(Modifier.weight(0.32f))
        }
    }
}

/**
 * Search, filter and sort.
 *
 * Deliberately one card rather than a top app bar with a search icon: the fleet
 * banner above it is the thing this screen exists to show, and a search field in
 * the chrome would compete with it every time the app opens. Here it reads as
 * "tools for the list below", which is what it is.
 */
@Composable
private fun SearchPanel(
    query: String,
    shownCount: Int,
    totalCount: Int,
    onQuery: (String) -> Unit,
    onClose: () -> Unit,
) {
    val focus = remember { FocusRequester() }
    // Tapping search means you want to type. Anything else is a second tap for
    // nothing.
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    GlassCard {
        // No section header above the field. It would have said "Search" directly
        // over a field already labelled SEARCH — duplicated text, and two nodes
        // answering to the same name for a screen reader. The field is its own title;
        // the close sits beside it.
        Row(verticalAlignment = Alignment.Bottom) {
            GlassField(
                value = query,
                onValueChange = onQuery,
                label = "Search",
                placeholder = "name, host or element",
                leadingIcon = NightbellIcons.Search,
                imeAction = ImeAction.Search,
                modifier = Modifier.weight(1f).focusRequester(focus),
                trailing = if (query.isNotBlank()) {
                    {
                        GlassIconButton(
                            icon = NightbellIcons.Close,
                            onClick = { onQuery("") },
                            contentDescription = "Clear search",
                            size = 30.dp,
                        )
                    }
                } else {
                    null
                },
            )
            Spacer(Modifier.width(8.dp))
            panelClose(onClose)()
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (query.isBlank()) {
                "$totalCount monitors"
            } else {
                "$shownCount of $totalCount match"
            },
            style = MaterialTheme.typography.bodySmall,
            color = NightbellColors.TextTertiary,
        )
    }
}

/**
 * The close affordance every disclosure panel carries.
 *
 * An X rather than a "Done" button. Dismissing a panel is the one action here with a
 * genuinely universal glyph, it needs no label, and a text button at the foot of each
 * panel was competing for weight with the controls the panel exists to show. Slots
 * into [SectionHeader]'s trailing position so the title and its close share one row.
 */
private fun panelClose(onClose: () -> Unit): @Composable () -> Unit = {
    GlassIconButton(
        icon = NightbellIcons.Close,
        onClick = onClose,
        contentDescription = "Close panel",
        size = 32.dp,
    )
}

@Composable
private fun TunePanel(
    spec: MonitorQuery.Spec,
    shownCount: Int,
    totalCount: Int,
    onFilter: (MonitorQuery.Filter) -> Unit,
    onSort: (MonitorQuery.Sort) -> Unit,
    onClear: () -> Unit,
    onClose: () -> Unit,
) {
    GlassCard {
        SectionHeader(
            "Show",
            icon = NightbellIcons.Eye,
            accent = NightbellColors.Aqua,
            trailing = panelClose(onClose),
        )
        // Icon *and* text, which is what this component was built for and what the
        // setup screen already does with monitor kinds. Icon-only would be wrong
        // here: "Unacked" and "Least recent" have no glyph anyone would guess, and a
        // row of six abstract marks is something you have to learn rather than read.
        // The icon makes the row scannable; the label is what makes it legible.
        ChipSelector(
            options = MonitorQuery.Filter.entries.toList(),
            selected = spec.filter,
            onSelect = onFilter,
            label = { it.label },
            icon = { filter ->
                when (filter) {
                    MonitorQuery.Filter.ALL -> NightbellIcons.Layers
                    MonitorQuery.Filter.PROBLEMS -> NightbellIcons.Warning
                    MonitorQuery.Filter.UP -> NightbellIcons.Check
                    MonitorQuery.Filter.PAUSED -> NightbellIcons.Pause
                    MonitorQuery.Filter.UNACKNOWLEDGED -> NightbellIcons.Zap
                }
            },
        )
        Spacer(Modifier.height(16.dp))
        SectionHeader("Order", icon = NightbellIcons.Sliders, accent = NightbellColors.Violet)
        ChipSelector(
            options = MonitorQuery.Sort.entries.toList(),
            selected = spec.sort,
            onSelect = onSort,
            label = { it.label },
            accent = NightbellColors.Violet,
            icon = { sort ->
                when (sort) {
                    MonitorQuery.Sort.WORST_FIRST -> NightbellIcons.Warning
                    // The grip, literally: this is the mode where the grips appear.
                    MonitorQuery.Sort.MANUAL -> NightbellIcons.Grip
                    MonitorQuery.Sort.NAME -> NightbellIcons.SortLines
                    MonitorQuery.Sort.SLOWEST -> NightbellIcons.Gauge
                    MonitorQuery.Sort.RECENT -> NightbellIcons.Refresh
                    MonitorQuery.Sort.STALEST -> NightbellIcons.History
                }
            },
        )
        AnimatedVisibility(visible = spec.sort == MonitorQuery.Sort.MANUAL) {
            Column {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = "Drag the grip on any card to arrange them. Nothing re-sorts " +
                        "them behind your back while this is on.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = if (shownCount == totalCount) {
                    "Showing all $totalCount"
                } else {
                    "Showing $shownCount of $totalCount"
                },
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
                modifier = Modifier.weight(1f),
            )
            if (!spec.hidesNothing) {
                NightbellButton(
                    text = "Show all",
                    onClick = onClear,
                    tone = ButtonTone.Secondary,
                )
            }
            // "Show all" keeps its words: low-frequency, consequential, and no glyph
            // says "stop hiding things" unambiguously.
        }
    }
}

/**
 * The slim reminder that monitors are being hidden.
 *
 * Shown whenever a filter or a search is narrowing the list and the panel that set
 * it is closed. Without it a filtered dashboard is just a short dashboard, which is
 * indistinguishable from having lost monitors — the one thing this app must never
 * look like.
 */
@Composable
private fun NarrowingStrip(
    spec: MonitorQuery.Spec,
    shownCount: Int,
    totalCount: Int,
    onClear: () -> Unit,
) {
    GlassCard(accent = NightbellColors.Amber, contentPadding = 12.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                NightbellIcons.Eye,
                contentDescription = null,
                tint = NightbellColors.Amber,
                modifier = Modifier.size(15.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = "Showing $shownCount of $totalCount",
                    style = MaterialTheme.typography.titleMedium,
                    color = NightbellColors.TextPrimary,
                )
                Text(
                    text = buildList {
                        if (spec.filter != MonitorQuery.Filter.ALL) add(spec.filter.label)
                        if (spec.query.isNotBlank()) add("“${spec.query.trim()}”")
                    }.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
            NightbellButton(text = "Show all", onClick = onClear, tone = ButtonTone.Secondary)
        }
    }
}

/**
 * The bulk-action bar.
 *
 * Sits at the bottom, in the thumb's reach, and takes the FAB's place rather than
 * sharing the corner with it. Delete asks first — it is the one action here that
 * destroys history, and it can now destroy eight monitors' worth at once.
 */
@Composable
private fun SelectionBar(
    count: Int,
    visible: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onMute: () -> Unit,
    onDelete: () -> Unit,
    onSelectAll: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    // Reset the confirmation whenever the bar goes away, so re-entering selection
    // never lands on a primed Delete.
    if (!visible && confirmDelete) confirmDelete = false

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { it },
        exit = fadeOut() + slideOutVertically { it },
        modifier = modifier,
    ) {
        GlassCard(accent = if (confirmDelete) NightbellColors.Rose else NightbellColors.Aqua) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (count == 1) "1 selected" else "$count selected",
                    style = MaterialTheme.typography.titleMedium,
                    color = NightbellColors.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                NightbellButton(
                    text = "Select all",
                    onClick = onSelectAll,
                    tone = ButtonTone.Secondary,
                )
                Spacer(Modifier.width(8.dp))
                GlassIconButton(
                    icon = NightbellIcons.Close,
                    onClick = onDone,
                    contentDescription = "Leave selection mode",
                    size = 34.dp,
                )
            }
            Spacer(Modifier.height(12.dp))
            if (confirmDelete) {
                Text(
                    text = "Delete ${if (count == 1) "this monitor" else "these $count monitors"}? " +
                        "Their history and scheduled checks go with them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextSecondary,
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NightbellButton(
                        text = "Keep them",
                        onClick = { confirmDelete = false },
                        tone = ButtonTone.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    NightbellButton(
                        text = "Delete",
                        onClick = {
                            confirmDelete = false
                            onDelete()
                        },
                        tone = ButtonTone.Danger,
                        icon = NightbellIcons.Trash,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                // Two rows of two, not four across. Four weighted buttons plus an
                // icon left roughly 55 dp of text width each, which wrapped the
                // labels to "Pau / se" and "Res / um / e" on a normal phone.
                //
                // Delete is a labelled button rather than a bare trash icon for the
                // same reason: it is the one action here that destroys history for
                // several monitors at once, and it should be the least ambiguous
                // thing on the bar rather than the most.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NightbellButton(
                        text = "Pause",
                        onClick = onPause,
                        icon = NightbellIcons.Pause,
                        tone = ButtonTone.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    NightbellButton(
                        text = "Resume",
                        onClick = onResume,
                        icon = NightbellIcons.Play,
                        tone = ButtonTone.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NightbellButton(
                        text = "Mute 1h",
                        onClick = onMute,
                        icon = NightbellIcons.BellOff,
                        tone = ButtonTone.Secondary,
                        modifier = Modifier.weight(1f),
                    )
                    NightbellButton(
                        text = "Delete",
                        onClick = { confirmDelete = true },
                        icon = NightbellIcons.Trash,
                        tone = ButtonTone.Danger,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// ----------------------------------------------------------------- list items

@Composable
private fun MonitorRowCard(
    card: MonitorCard,
    certLevel: CertificateWatch.Level,
    selecting: Boolean,
    selected: Boolean,
    dragging: Boolean,
    dragDelta: Offset,
    reorderHandle: (@Composable () -> Unit)?,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onCheck: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onAcknowledge: () -> Unit,
) {
    val monitor = card.monitor
    val runtime = card.runtime
    val (accent, accentEnd) = accentFor(monitor.accent)
    val health = if (!monitor.enabled) Health.PAUSED else runtime.health
    val now = LocalNowMs.current
    val muted = runtime.mutedUntil > now
    val urgentPending = monitor.urgent && runtime.urgentState.nagging

    GlassCard(
        // A muted monitor is a decision, not an emergency. Red is reserved for
        // "this needs you now"; once you've snoozed it the rim goes amber so
        // the card still stands out without competing with a live outage.
        //
        // Selection overrides both: while picking monitors, "is this one in the
        // set" is the only question the rim needs to answer, and health is still
        // carried by the pill and the orb.
        accent = when {
            dragging -> NightbellColors.Aqua
            selected -> NightbellColors.Aqua
            muted && healthRim(health) != Color.Transparent -> NightbellColors.Amber
            else -> healthRim(health)
        },
        // Elevation and shadow both grow while held, so the card reads as picked up
        // off the surface rather than merely sliding along it.
        elevation = if (dragging) 26.dp else 12.dp,
        onClick = onOpen,
        onLongClick = onLongPress,
        modifier = Modifier
            .then(
                if (dragging) {
                    Modifier
                        .zIndex(1f)
                        .graphicsLayer {
                            translationX = dragDelta.x
                            translationY = dragDelta.y
                            // Just enough to lift it visually without the drop
                            // target maths having to account for a scaled hit box.
                            alpha = 0.94f
                        }
                } else {
                    Modifier
                },
            )
            .fillMaxWidth()
            .semantics {
                if (selecting) {
                    this.selected = selected
                }
                contentDescription = buildString {
                    append(monitor.displayName)
                    append(", ")
                    append(health.label)
                    if (muted) append(", muted")
                    if (urgentPending) append(", urgent, not acknowledged")
                    if (certLevel == CertificateWatch.Level.EXPIRED) {
                        append(", certificate expired")
                    } else if (certLevel == CertificateWatch.Level.WARN ||
                        certLevel == CertificateWatch.Level.CRITICAL
                    ) {
                        append(
                            ", certificate expires in " +
                                "${CertificateWatch.daysLeft(runtime.certExpiresAt, now)} days",
                        )
                    }
                    append(if (selecting) ", tap to select" else ", open details")
                }
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // A page-element monitor *is* the page it watches, so the site's own
            // mark identifies it far faster than one more identical cursor glyph
            // down a list. Only for that kind: an API endpoint has no favicon
            // worth showing, and the kind icon is the useful signal there.
            val favicon = rememberFavicon(
                pageUrl = monitor.url,
                enabled = monitor.kind == MonitorKind.WEBSITE_ELEMENT,
            )
            // The grip lives in the title row, not down with the actions.
            //
            // The action row is already full — kind, method, interval, pause,
            // re-check — and adding 48 dp of handle to it pushed the re-check button
            // clean off the card. The title row has slack by construction: the
            // name/host column is weighted and simply gives some up. Leading also
            // keeps it away from the bottom-right corner, where the floating add
            // button is drawn over whichever card happens to be at the fold.
            reorderHandle?.let {
                it()
                Spacer(Modifier.width(6.dp))
            }
            IconBadge(
                icon = kindIcon(monitor.kind),
                accent = accent,
                size = 42.dp,
                image = favicon,
            )
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = monitor.displayName,
                    style = MaterialTheme.typography.titleLarge,
                    color = NightbellColors.TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = monitor.prettyHost,
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(10.dp))
            if (selecting) {
                SelectionTick(selected = selected)
            } else {
                StatusOrb(health = health, checking = card.checking, size = 11.dp)
            }
        }

        Spacer(Modifier.height(13.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusPill(health = health, checking = card.checking)
            Spacer(Modifier.width(8.dp))
            if (muted) {
                MicroTag(
                    text = "Muted",
                    color = NightbellColors.Amber,
                    background = NightbellColors.Amber.copy(alpha = 0.14f),
                    icon = NightbellIcons.BellOff,
                )
                Spacer(Modifier.width(6.dp))
            }
            if (urgentPending) {
                MicroTag(
                    text = "Urgent",
                    color = NightbellColors.Rose,
                    background = NightbellColors.Rose.copy(alpha = 0.16f),
                    icon = NightbellIcons.Zap,
                )
                Spacer(Modifier.width(6.dp))
            }
            if (runtime.lastLatencyMs > 0) {
                MicroTag(
                    text = formatLatency(runtime.lastLatencyMs),
                    color = if (health == Health.DEGRADED) NightbellColors.Amber else NightbellColors.TextSecondary,
                    icon = NightbellIcons.Gauge,
                )
                Spacer(Modifier.width(6.dp))
            }
            // Says why a number that looks slow was not treated as slow. Without
            // this the compensation is invisible, and an invisible correction to a
            // number the user is reading is indistinguishable from a bug.
            if (runtime.lastLatencySuspect) {
                MicroTag(
                    text = "connection",
                    color = NightbellColors.Sky,
                    background = NightbellColors.Sky.copy(alpha = 0.14f),
                    icon = NightbellIcons.Wifi,
                )
                Spacer(Modifier.width(6.dp))
            } else if (runtime.lastNetworkExcessMs > 0) {
                MicroTag(
                    text = "−${formatLatency(runtime.lastNetworkExcessMs)}",
                    color = NightbellColors.Sky,
                    icon = NightbellIcons.Wifi,
                )
                Spacer(Modifier.width(6.dp))
            }
            if (runtime.lastCode > 0) {
                MicroTag(
                    text = runtime.lastCode.toString(),
                    color = if (runtime.health == Health.UP) NightbellColors.Mint else NightbellColors.Amber,
                )
                Spacer(Modifier.width(6.dp))
            }
            // A cert deadline earns a tag but never the card's rim: the service is
            // up, and painting it amber would put a warning colour on something
            // that is working. Red only for one already expired, which is an
            // outage in every practical sense.
            if (certLevel != CertificateWatch.Level.OK &&
                certLevel != CertificateWatch.Level.UNKNOWN
            ) {
                MicroTag(
                    text = CertificateWatch.tag(
                        certLevel,
                        CertificateWatch.daysLeft(runtime.certExpiresAt, now),
                    ),
                    color = if (certLevel == CertificateWatch.Level.EXPIRED) {
                        NightbellColors.Rose
                    } else {
                        NightbellColors.Amber
                    },
                    icon = NightbellIcons.Shield,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                text = formatRelative(runtime.lastCheckedAt, now),
                style = MaterialTheme.typography.bodySmall,
                color = NightbellColors.TextTertiary,
            )
        }

        if (runtime.samples.isNotEmpty()) {
            // One list, both charts. They are stacked and read as a single
            // figure, so a failure has to appear at the same x in each; windowing
            // them separately is what put a red tick under a blue line.
            val history = runtime.samples.takeLast(SAMPLE_WINDOW)
            Spacer(Modifier.height(12.dp))
            // A single data point isn't a trend — the strip alone reads better.
            if (history.size >= 2) {
                // No accent passed: the chart takes the operational green from its
                // default rather than the card's per-monitor blue. The monitor's own
                // accent still colours its badge and rim above.
                Sparkline(
                    samples = history,
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                )
                Spacer(Modifier.height(8.dp))
            }
            // Same reasoning as the sparkline above, and the same colour: the comment
            // there is right that these two read as a single figure, so a green line
            // over a blue strip was the one arrangement guaranteed to look like a bug.
            HistoryStrip(
                samples = history,
                modifier = Modifier.fillMaxWidth().height(5.dp),
            )
        }

        AnimatedVisibility(
            visible = !runtime.ok(monitor.enabled) && runtime.lastMessage.isNotBlank(),
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            val tone = if (muted) NightbellColors.Amber else NightbellColors.Rose
            Column {
                Spacer(Modifier.height(11.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(tone.copy(alpha = 0.10f))
                        .padding(horizontal = 11.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (muted) NightbellIcons.BellOff else NightbellIcons.Warning,
                        contentDescription = null,
                        tint = tone,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = if (muted) {
                            "${runtime.lastMessage} · muted, no alerts"
                        } else {
                            runtime.lastMessage
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = NightbellColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        AnimatedVisibility(visible = urgentPending, enter = fadeIn(), exit = fadeOut()) {
            Column {
                Spacer(Modifier.height(11.dp))
                NightbellButton(
                    text = "Acknowledge urgent alert",
                    onClick = onAcknowledge,
                    icon = NightbellIcons.Check,
                    tone = ButtonTone.Danger,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MicroTag(text = monitor.kind.label, color = accent, icon = kindIcon(monitor.kind))
            if (monitor.kind == MonitorKind.WEBSITE_ELEMENT && monitor.targets.size > 1) {
                MicroTag(
                    text = "${monitor.targets.size} elements",
                    color = NightbellColors.TextTertiary,
                    icon = NightbellIcons.Target,
                )
            }
            if (monitor.kind != MonitorKind.WEBSITE_ELEMENT) {
                MicroTag(text = monitor.method.name, color = NightbellColors.TextTertiary)
            }
            MicroTag(text = "${monitor.intervalMinutes}m", color = NightbellColors.TextTertiary, icon = NightbellIcons.Clock)
            Spacer(Modifier.weight(1f))
            // Per-card actions step aside during selection: a re-check button that
            // fires a real request is the last thing that should be one mis-tap
            // away while the finger is busy picking rows.
            if (!selecting) {
                GlassIconButton(
                    icon = if (monitor.enabled) NightbellIcons.Pause else NightbellIcons.Play,
                    onClick = { onToggle(!monitor.enabled) },
                    contentDescription = if (monitor.enabled) "Pause monitor" else "Resume monitor",
                    size = 34.dp,
                    accent = NightbellColors.TextSecondary,
                )
                GlassIconButton(
                    icon = NightbellIcons.Refresh,
                    onClick = onCheck,
                    contentDescription = "Check now",
                    size = 34.dp,
                    accent = accent,
                    enabled = !card.checking,
                )
            }
        }
    }
}

/** Checkbox in the orb's slot, so nothing on the card moves when the mode flips. */
@Composable
private fun SelectionTick(selected: Boolean) {
    val shape = RoundedCornerShape(8.dp)
    Box(
        Modifier
            .size(22.dp)
            .clip(shape)
            .background(if (selected) NightbellColors.Aqua else NightbellColors.sheen(0.07f))
            .border(1.dp, if (selected) NightbellColors.Aqua else NightbellColors.sheen(0.16f), shape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                NightbellIcons.Check,
                contentDescription = null,
                tint = NightbellColors.Void,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

private fun me.river.nightbell.domain.MonitorRuntime.ok(enabled: Boolean): Boolean =
    !enabled || health == Health.UP || health == Health.UNKNOWN || health == Health.DEGRADED

// ------------------------------------------------------------------------ fab

/**
 * The plus button: a spring-loaded press and a quarter-turn morph as it hands
 * off to the setup flow.
 */
@Composable
fun MorphingFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    var launching by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }

    val scale by animateFloatAsState(
        targetValue = when {
            launching -> 1.28f
            pressed -> 0.9f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "fabScale",
    )
    val rotation by animateFloatAsState(
        targetValue = if (launching) 135f else 0f,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium),
        label = "fabRotate",
    )
    androidx.compose.runtime.LaunchedEffect(launching) {
        if (launching) {
            delay(140)
            onClick()
            delay(280)
            launching = false
        }
    }

    val halo = NightbellColors.Aqua.copy(alpha = 0.10f)
    Box(modifier.size(96.dp), contentAlignment = Alignment.Center) {
        // One faint pool of light so the button doesn't float on nothing. The
        // pulsing halo that used to live here read as decoration, not affordance.
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(halo, Color.Transparent),
                    center = center,
                    radius = size.minDimension / 2f,
                ),
                radius = size.minDimension / 2f,
                center = center,
            )
        }
        Box(
            Modifier
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    rotationZ = rotation
                }
                .size(62.dp)
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        listOf(NightbellColors.Aqua, NightbellColors.Indigo, NightbellColors.Violet),
                    ),
                )
                .clickable(
                    interactionSource = interaction,
                    indication = ripple(bounded = false, color = Color.White),
                ) { if (!launching) launching = true }
                .semantics { contentDescription = "Add a monitor" },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = NightbellIcons.Plus,
                contentDescription = null,
                tint = NightbellColors.Void,
                modifier = Modifier.size(27.dp),
            )
        }
    }
}

@Composable
fun DashboardCountBadge(count: Int, accent: Color = NightbellColors.Aqua) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = 0.16f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedCounter(
            value = count,
            color = accent,
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 12.sp),
        )
    }
}
