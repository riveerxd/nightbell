@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package me.river.pulse.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
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
import me.river.pulse.domain.Validation
import me.river.pulse.ui.icons.PulseIcons
import me.river.pulse.ui.theme.PulseColors
import me.river.pulse.ui.theme.PulseRadii
import me.river.pulse.ui.theme.glassInteractive
import me.river.pulse.ui.theme.rememberLoopingFloat

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
    accent: Color = PulseColors.Aqua,
    singleLine: Boolean = true,
    minLines: Int = 1,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    enabled: Boolean = true,
) {
    var focused by remember { mutableStateOf(false) }
    val isError = note?.severity == Validation.Severity.ERROR

    Column(modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (isError) PulseColors.Rose else PulseColors.TextTertiary,
            modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .glassInteractive(
                    shape = RoundedCornerShape(PulseRadii.field),
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
                    tint = if (focused) accent else PulseColors.TextTertiary,
                    modifier = Modifier.size(17.dp).padding(end = 0.dp),
                )
                Spacer(Modifier.width(11.dp))
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = PulseColors.TextTertiary.copy(alpha = 0.65f),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    enabled = enabled,
                    singleLine = singleLine,
                    minLines = minLines,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = PulseColors.TextPrimary,
                        fontWeight = FontWeight.Medium,
                    ),
                    cursorBrush = SolidColor(accent),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = keyboardType,
                        imeAction = imeAction,
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
            Validation.Severity.ERROR -> PulseColors.Rose
            Validation.Severity.WARNING -> PulseColors.Amber
            Validation.Severity.HINT -> PulseColors.TextTertiary
            null -> PulseColors.TextTertiary
        }
        val icon = when (severity) {
            Validation.Severity.ERROR -> PulseIcons.Warning
            Validation.Severity.WARNING -> PulseIcons.Warning
            else -> PulseIcons.Info
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
fun PulseButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    tone: ButtonTone = ButtonTone.Primary,
    enabled: Boolean = true,
    loading: Boolean = false,
    accent: Color = PulseColors.Aqua,
    accentEnd: Color = PulseColors.Indigo,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "buttonScale",
    )
    val shape = RoundedCornerShape(PulseRadii.chip)
    val alpha = if (enabled && !loading) 1f else 0.45f

    val background: Modifier = when (tone) {
        ButtonTone.Primary -> Modifier.background(
            Brush.linearGradient(listOf(accent.copy(alpha = alpha), accentEnd.copy(alpha = alpha))),
        )

        ButtonTone.Secondary -> Modifier
            .background(Color.White.copy(alpha = 0.08f * alpha))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)), shape)

        ButtonTone.Ghost -> Modifier

        ButtonTone.Danger -> Modifier
            .background(PulseColors.Rose.copy(alpha = 0.16f * alpha))
            .border(BorderStroke(1.dp, PulseColors.Rose.copy(alpha = 0.45f)), shape)
    }

    val contentColor = when (tone) {
        ButtonTone.Primary -> PulseColors.Void
        ButtonTone.Danger -> PulseColors.Rose
        else -> PulseColors.TextPrimary
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
        imageVector = PulseIcons.Refresh,
        contentDescription = null,
        tint = color,
        modifier = Modifier.size(size).rotate(angle),
    )
}

@Composable
fun GlassIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
    accent: Color = PulseColors.TextSecondary,
    size: androidx.compose.ui.unit.Dp = 42.dp,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMedium),
        label = "iconScale",
    )
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(size)
            .clip(RoundedCornerShape(size / 2.6f))
            .background(Color.White.copy(alpha = if (enabled) 0.07f else 0.03f))
            .border(
                BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)),
                RoundedCornerShape(size / 2.6f),
            )
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = ripple(bounded = false, color = accent),
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (enabled) accent else accent.copy(alpha = 0.4f),
            modifier = Modifier.size(size * 0.45f),
        )
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
    accent: Color = PulseColors.Aqua,
) {
    if (options.isEmpty()) return
    val index = options.indexOf(selected).coerceAtLeast(0)
    val shape = RoundedCornerShape(PulseRadii.chip)

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White.copy(alpha = 0.055f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.09f)), shape)
            .padding(4.dp),
    ) {
        val itemWidth = maxWidth / options.size
        val offsetX by animateDpAsState(
            targetValue = itemWidth * index,
            animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow),
            label = "segmentOffset",
        )
        Box(
            Modifier
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
                        .height(34.dp)
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
                        color = if (isSelected) PulseColors.TextPrimary else PulseColors.TextTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** Wrapping chip picker for larger option sets. */
@Composable
fun <T> ChipSelector(
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    accent: Color = PulseColors.Aqua,
    icon: ((T) -> ImageVector?)? = null,
) {
    FlowRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            val isSelected = option == selected
            val chipShape = RoundedCornerShape(PulseRadii.chip)
            val scale by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0.98f,
                animationSpec = spring(dampingRatio = 0.6f),
                label = "chipScale",
            )
            Row(
                modifier = Modifier
                    .graphicsLayer { scaleX = scale; scaleY = scale }
                    .clip(chipShape)
                    .background(
                        if (isSelected) {
                            Brush.linearGradient(
                                listOf(accent.copy(alpha = 0.30f), accent.copy(alpha = 0.14f)),
                            )
                        } else {
                            Brush.linearGradient(
                                listOf(Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.04f)),
                            )
                        },
                    )
                    .border(
                        BorderStroke(
                            1.dp,
                            if (isSelected) accent.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f),
                        ),
                        chipShape,
                    )
                    .clickable(
                        indication = ripple(color = accent),
                        interactionSource = remember { MutableInteractionSource() },
                    ) { onSelect(option) }
                    .padding(horizontal = 13.dp, vertical = 9.dp)
                    .semantics { stateDescription = if (isSelected) "Selected" else "Not selected" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                icon?.invoke(option)?.let {
                    Icon(
                        it,
                        contentDescription = null,
                        tint = if (isSelected) accent else PulseColors.TextTertiary,
                        modifier = Modifier.size(13.dp),
                    )
                }
                Text(
                    text = label(option),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) PulseColors.TextPrimary else PulseColors.TextSecondary,
                )
            }
        }
    }
}

// --------------------------------------------------------------------- rows

@Composable
fun ToggleRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String = "",
    icon: ImageVector? = null,
    accent: Color = PulseColors.Aqua,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            IconBadge(icon = icon, accent = if (checked) accent else PulseColors.TextTertiary, size = 34.dp)
            Spacer(Modifier.width(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = if (enabled) PulseColors.TextPrimary else PulseColors.TextTertiary,
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = PulseColors.TextTertiary,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PulseColors.Void,
                checkedTrackColor = accent,
                checkedBorderColor = accent,
                uncheckedThumbColor = PulseColors.TextTertiary,
                uncheckedTrackColor = Color.White.copy(alpha = 0.06f),
                uncheckedBorderColor = Color.White.copy(alpha = 0.16f),
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
    accent: Color = PulseColors.Aqua,
    note: Validation.Note? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            if (icon != null) {
                IconBadge(icon = icon, accent = accent, size = 34.dp)
                Spacer(Modifier.width(13.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = PulseColors.TextPrimary,
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
                    color = PulseColors.TextPrimary,
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
    Box(
        modifier = Modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .size(34.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.10f)), RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = interaction,
                indication = ripple(bounded = false, color = PulseColors.Aqua),
                onClick = onClick,
            )
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = MaterialTheme.typography.titleLarge, color = PulseColors.TextSecondary)
    }
}
