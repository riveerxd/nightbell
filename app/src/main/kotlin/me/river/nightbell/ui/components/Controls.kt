@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package me.river.nightbell.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import me.river.nightbell.domain.Validation
import me.river.nightbell.ui.icons.NightbellIcons
import me.river.nightbell.ui.theme.NightbellColors
import me.river.nightbell.ui.theme.NightbellRadii
import me.river.nightbell.ui.theme.glassInteractive
import me.river.nightbell.ui.theme.rememberLoopingFloat

// ---------------------------------------------------------------- text fields

@Composable
fun GlassField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    note: Validation.Note? = null,
    helper: String = "",
    leadingIcon: ImageVector? = null,
    trailing: @Composable (() -> Unit)? = null,
    accent: Color = NightbellColors.Aqua,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val isError = note?.severity == Validation.Severity.ERROR
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) NightbellColors.Rose else NightbellColors.TextTertiary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassInteractive(
                    shape = RoundedCornerShape(NightbellRadii.field),
                    focused = focused,
                    accent = accent,
                    error = isError,
                )
                .padding(horizontal = 14.dp, vertical = if (singleLine) 13.dp else 14.dp),
            verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top,
        ) {
            if (leadingIcon != null) {
                Icon(
                    imageVector = leadingIcon,
                    contentDescription = null,
                    tint = if (focused) accent else NightbellColors.TextTertiary,
                    modifier = Modifier.size(17.dp).padding(end = 0.dp),
                )
                Spacer(Modifier.width(11.dp))
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NightbellColors.TextTertiary.copy(alpha = 0.65f),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    minLines = minLines,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = NightbellColors.TextPrimary,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = imeAction,
                    ),
                    // The keys that mean "that's the answer" have to put the
                    // keyboard away. Without these the field asks for Done or
                    // Search and then ignores the tap, so the only way out was
                    // the back gesture. Next is left to the default, which walks
                    // to the following field.
                    keyboardActions = KeyboardActions(
                        onDone = { dismissKeyboard(focusManager, keyboard) },
                        onGo = { dismissKeyboard(focusManager, keyboard) },
                        onSearch = { dismissKeyboard(focusManager, keyboard) },
                        onSend = { dismissKeyboard(focusManager, keyboard) },
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = if (singleLine) 20.dp else (minLines * 21).dp)
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = label },
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(10.dp))
                trailing()
            }
        }
        FieldNote(note = note, helper = helper)
    }
}

/**
 * Puts the keyboard away and takes focus off the field.
 *
 * Both halves are needed: clearing focus on its own leaves the IME on screen.
 */
private fun dismissKeyboard(
    focusManager: androidx.compose.ui.focus.FocusManager,
    keyboard: androidx.compose.ui.platform.SoftwareKeyboardController?,
) {
    focusManager.clearFocus()
    keyboard?.hide()
}

@Composable
fun FieldNote(note: Validation.Note?, helper: String = "") {
    val message = note?.message ?: helper.ifBlank { null }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn() + expandVertically(),
        exit = fadeOut() + shrinkVertically(),
    ) {
        val severity = note?.severity
        val color = when (severity) {
            Validation.Severity.ERROR -> NightbellColors.Rose
            Validation.Severity.WARNING -> NightbellColors.Amber
            Validation.Severity.HINT -> NightbellColors.TextTertiary
            null -> NightbellColors.TextTertiary
        }
        val icon = when (severity) {
            Validation.Severity.ERROR -> NightbellIcons.Warning
            Validation.Severity.WARNING -> NightbellIcons.Warning
            else -> NightbellIcons.Info
        }
        Row(
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = message.orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = color,
            )
        }
    }
}

// -------------------------------------------------------------------- buttons

enum class ButtonTone { Primary, Secondary, Ghost, Danger }

@Composable
fun NightbellButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: ButtonTone = ButtonTone.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    accent: Color = NightbellColors.Aqua,
    accentEnd: Color = NightbellColors.Indigo,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "buttonScale",
    )
    val shape = RoundedCornerShape(NightbellRadii.chip)
    val alpha = if (enabled && !loading) 1f else 0.45f

    val background: Modifier = when (tone) {
        ButtonTone.Primary -> Modifier.background(
            Brush.linearGradient(listOf(accent.copy(alpha = alpha), accentEnd.copy(alpha = alpha))),
        )

        ButtonTone.Secondary -> Modifier
            .background(NightbellColors.sheen(0.08f * alpha))
            .border(BorderStroke(1.dp, NightbellColors.sheen(0.16f)), shape)

        ButtonTone.Ghost -> Modifier

        ButtonTone.Danger -> Modifier
            .background(NightbellColors.Rose.copy(alpha = 0.16f * alpha))
            .border(BorderStroke(1.dp, NightbellColors.Rose.copy(alpha = 0.45f)), shape)
    }

    val contentColor = when (tone) {
        ButtonTone.Primary -> NightbellColors.Void
        ButtonTone.Danger -> NightbellColors.Rose
        else -> NightbellColors.TextPrimary
    }.copy(alpha = alpha)

    Row(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(shape)
            .then(background)
            .clickable(
                enabled = enabled && !loading,
                interactionSource = interaction,
                indication = ripple(color = if (tone == ButtonTone.Primary) Color.Black else accent),
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            SpinnerDot(color = contentColor)
            Spacer(Modifier.width(9.dp))
        } else if (icon != null) {
            Icon(icon, contentDescription = null, tint = contentColor, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(9.dp))
        }
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = contentColor,
        )
    }
}

@Composable
fun SpinnerDot(color: Color, size: androidx.compose.ui.unit.Dp = 15.dp) {
    val angle by rememberLoopingFloat(
        initialValue = 0f,
        targetValue = 360f,
        durationMillis = 900,
        label = "spin",
    )
    Icon(
        imageVector = NightbellIcons.Refresh,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(size).rotate(angle),
    )
}

/**
 * Material's floor for anything a finger has to hit.
 *
 * Enforced on the *touch* area rather than the drawn one: several of these
 * buttons are deliberately small — a 34 dp cog next to a wordmark is the right
 * visual weight — and growing the paint to satisfy the guideline would trade one
 * real problem for another. The pattern throughout is a [MinTouchTarget] box
 * that takes the gesture, with the visual centred inside at whatever size the
 * design calls for.
 */
val MinTouchTarget = 48.dp

@Composable
fun GlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    accent: Color = NightbellColors.TextSecondary,
    size: androidx.compose.ui.unit.Dp = 42.dp,
    enabled: Boolean = true,
    /** Marks the control as holding non-default state, e.g. an active filter. */
    badged: Boolean = false,
    /** Pressed-in look, for a toggle that reveals a panel. */
    active: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "iconScale",
    )
    val shape = RoundedCornerShape(size / 2.6f)
    Box(
        modifier = modifier
            .size(maxOf(size, MinTouchTarget))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = ripple(bounded = false, color = accent),
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .size(size)
                .clip(shape)
                .background(
                    if (active) {
                        accent.copy(alpha = 0.20f)
                    } else {
                        NightbellColors.sheen(if (enabled) 0.07f else 0.03f)
                    },
                )
                .border(
                    BorderStroke(
                        1.dp,
                        if (active) accent.copy(alpha = 0.55f) else NightbellColors.sheen(0.10f),
                    ),
                    shape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) accent else accent.copy(alpha = 0.4f),
                modifier = Modifier.size(size * 0.45f),
            )
        }
        if (badged) {
            // Drawn on the outer, unclipped box.
            //
            // Inside the visual box it was invisible: that box clips to the button's
            // rounded shape, so a dot offset past the corner is simply cut away. A
            // filter that hides monitors has to be visible from the collapsed state,
            // or a short list looks like lost data.
            Box(
                Modifier
                    .align(Alignment.Center)
                    .offset(x = size / 2 - 2.dp, y = -(size / 2) + 2.dp)
                    .size(9.dp)
                    .clip(RoundedCornerShape(50))
                    .background(NightbellColors.Amber)
                    .border(2.dp, NightbellColors.Void, RoundedCornerShape(50)),
            )
        }
    }
}

// ------------------------------------------------------------------ selectors

/** Sliding-pill segmented control. Works for any small enum-ish option set. */
@Composable
fun <T> SegmentedSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    accent: Color = NightbellColors.Aqua,
) {
    if (options.isEmpty()) return
    val index = options.indexOf(selected).coerceAtLeast(0)
    val shape = RoundedCornerShape(NightbellRadii.chip)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(NightbellColors.sheen(0.055f))
            .border(BorderStroke(1.dp, NightbellColors.sheen(0.09f)), shape)
            .padding(4.dp),
    ) {
        val itemWidth = maxWidth / options.size
        val offsetX by animateDpAsState(
            targetValue = itemWidth * index,
            animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
            label = "segmentOffset",
        )
        // The pill keeps its 34 dp height; the tappable segments around it are
        // MinTouchTarget tall, so the control grew a little breathing room rather
        // than the sliding indicator growing fat.
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .offset(x = offsetX)
                .width(itemWidth)
                .height(34.dp)
                .clip(shape)
                .background(
                    Brush.linearGradient(
                        listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.16f)),
                    ),
                )
                .border(BorderStroke(1.dp, accent.copy(alpha = 0.45f)), shape),
        )
        Row(Modifier.fillMaxWidth()) {
            options.forEach { option ->
                val isSelected = option == selected
                Box(
                    modifier = Modifier
                        .width(itemWidth)
                        .height(MinTouchTarget)
                        .clip(shape)
                        .clickable(
                            indication = ripple(color = accent),
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onSelect(option) }
                        .semantics {
                            stateDescription = if (isSelected) "Selected" else "Not selected"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label(option),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isSelected) NightbellColors.TextPrimary else NightbellColors.TextTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * The one chip geometry.
 *
 * Fixed so a set of chips reads as a family regardless of label length, and so the
 * filter row and the sort row cannot drift apart.
 */
private val CHIP_HEIGHT = 38.dp
private val CHIP_MIN_WIDTH = 78.dp

/** Wrapping chip picker for larger option sets. */
@Composable
fun <T> ChipSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    accent: Color = NightbellColors.Aqua,
    icon: ((T) -> ImageVector?)? = null,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // No extra vertical gap: each chip already carries the padding that lifts it
        // to the touch floor, and adding more on top opens a visible gutter.
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val chipShape = RoundedCornerShape(NightbellRadii.chip)
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0.98f,
                animationSpec = spring(dampingRatio = 0.6f),
                label = "chipScale",
            )
            Row(
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    // The touch floor is reached with padding *outside* the capsule,
                    // not by growing it.
                    //
                    // Stretching the visual to 48 dp while leaving 13 dp of side
                    // padding made a chip's shape depend on how long its label was:
                    // "All" came out 46×48 — a circle — next to a properly capsule
                    // "Problems". A chip set has to read as one family, so the height
                    // is fixed and a minimum width keeps even a three-letter label
                    // wider than it is tall.
                    .padding(vertical = (MinTouchTarget - CHIP_HEIGHT) / 2)
                    .height(CHIP_HEIGHT)
                    .defaultMinSize(minWidth = CHIP_MIN_WIDTH)
                    .clip(chipShape)
                    .background(
                        if (isSelected) {
                            Brush.linearGradient(
                                listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.14f)),
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(NightbellColors.sheen(0.06f), NightbellColors.sheen(0.04f)),
                            )
                        },
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isSelected) accent.copy(alpha = 0.55f) else NightbellColors.sheen(0.10f),
                        ),
                        chipShape,
                    )
                    .clickable(
                        indication = ripple(color = accent),
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onSelect(option) }
                    .padding(horizontal = 16.dp)
                    .semantics { stateDescription = if (isSelected) "Selected" else "Not selected" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                icon?.invoke(option)?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = if (isSelected) accent else NightbellColors.TextTertiary,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) NightbellColors.TextPrimary else NightbellColors.TextSecondary,
                )
            }
        }
    }
}

// --------------------------------------------------------------------- rows

/**
 * The one vertical gutter a settings row carries.
 *
 * Two rows in the same category therefore sit 2 x RowGutter apart, which has to
 * stay smaller than the gap that separates one category from the next, because
 * that is the whole grouping cue. At 10 dp each, adjacent toggles were 20 dp
 * apart while a category break was 18 dp, so every row read as its own island
 * and a heading belonged to nothing. Every row shape uses this, so a toggle and
 * a stepper sit the same distance under their heading instead of 20 dp and 10 dp.
 */
private val RowGutter = 4.dp

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    icon: ImageVector? = null,
    accent: Color = NightbellColors.Aqua,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = RowGutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon = icon, accent = if (checked) accent else NightbellColors.TextTertiary, size = 34.dp)
            Spacer(Modifier.width(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) NightbellColors.TextPrimary else NightbellColors.TextTertiary,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NightbellColors.TextTertiary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = NightbellColors.Void,
                checkedTrackColor = accent,
                checkedBorderColor = accent,
                uncheckedThumbColor = NightbellColors.TextTertiary,
                uncheckedTrackColor = NightbellColors.sheen(0.06f),
                uncheckedBorderColor = NightbellColors.sheen(0.16f),
            ),
        )
    }
}

@Composable
fun StepperRow(
    title: String,
    value: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    range: IntRange = 1..999,
    step: Int = 1,
    suffix: String = "",
    icon: ImageVector? = null,
    accent: Color = NightbellColors.Aqua,
    note: Validation.Note? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = RowGutter)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (icon != null) {
                IconBadge(icon = icon, accent = accent, size = 34.dp)
                Spacer(Modifier.width(13.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = NightbellColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            StepperButton("−", "Decrease $title") {
                onValueChange((value - step).coerceIn(range.first, range.last))
            }
            Box(
                modifier = Modifier.width(74.dp).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) {
                AnimatedCounter(
                    value = value,
                    suffix = suffix,
                    color = NightbellColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            StepperButton("+", "Increase $title") {
                onValueChange((value + step).coerceIn(range.first, range.last))
            }
        }
        FieldNote(note = note)
    }
}

@Composable
private fun StepperButton(glyph: String, description: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.85f else 1f, label = "stepScale")
    val shape = RoundedCornerShape(12.dp)
    // − and + sat 34 dp wide a few dp apart, which is the worst possible geometry
    // for a control people tap repeatedly to nudge a number.
    Box(
        modifier = Modifier
            .size(MinTouchTarget)
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, color = NightbellColors.Aqua),
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .size(34.dp)
                .clip(shape)
                .background(NightbellColors.sheen(0.07f))
                .border(BorderStroke(1.dp, NightbellColors.sheen(0.10f)), shape),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, style = MaterialTheme.typography.titleLarge, color = NightbellColors.TextSecondary)
        }
    }
}
