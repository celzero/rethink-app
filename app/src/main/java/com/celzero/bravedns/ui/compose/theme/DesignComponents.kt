/*
 * Copyright 2024 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:OptIn(ExperimentalMaterial3ExpressiveApi::class)

package com.celzero.bravedns.ui.compose.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardColors
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.compose.theme.Dimensions.Elevation
import com.celzero.bravedns.ui.compose.theme.Dimensions.Opacity

// ==================== ANIMATED SECTIONS ====================

// ==================== CARDS ====================

/**
 * Standard app card with M3 Expressive corner radius.
 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    colors: CardColors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceContainer
    ),
    content: @Composable ColumnScope.() -> Unit
) {
    if (onClick != null) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimensions.cardCornerRadius),
            colors = colors,
            elevation = CardDefaults.cardElevation(defaultElevation = Elevation.medium),
            onClick = onClick,
            content = content
        )
    } else {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Dimensions.cardCornerRadius),
            colors = colors,
            elevation = CardDefaults.cardElevation(defaultElevation = Elevation.low),
            content = content
        )
    }
}

// ==================== EMPTY STATES ====================

/**
 * Compact empty state for inline use in lists/sections.
 */
@Composable
fun CompactEmptyState(
    modifier: Modifier = Modifier,
    message: String,
    icon: ImageVector? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(Dimensions.spacingLg),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier
                    .size(Dimensions.iconSizeSm)
                    .alpha(Opacity.HINT),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(Dimensions.spacingSm))
        }
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.alpha(Opacity.HINT)
        )
    }
}

// ==================== LIST ITEMS ====================

/**
 * Shared grid tile used by configure/about quick actions.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RethinkGridTile(
    title: String,
    iconRes: Int,
    accentColor: Color,
    shape: RoundedCornerShape,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    titleColor: Color = MaterialTheme.colorScheme.onSurface,
    iconTint: Color = accentColor,
    iconContainerColor: Color = accentColor.copy(alpha = 0.16f),
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null
) {
    val iconShape = MaterialShapes.Sunny.toShape()

    if (onClick != null) {
        Surface(
            onClick = onClick,
            shape = shape,
            color = containerColor,
            modifier = modifier
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = iconShape,
                    color = iconContainerColor,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                trailing?.invoke()
            }
        }
    } else {
        Surface(
            shape = shape,
            color = containerColor,
            modifier = modifier
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = iconShape,
                    color = iconContainerColor,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(id = iconRes),
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = titleColor,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                trailing?.invoke()
            }
        }
    }
}

// ==================== SECTION HEADERS ====================

enum class RethinkSecondaryActionStyle { TONAL, OUTLINED, TEXT }

@Composable
fun <T> RethinkSegmentedChoiceRow(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    fillEqually: Boolean = false,
    minHeight: Dp = 0.dp,
    icon: (@Composable (option: T, selected: Boolean) -> Unit)? = null,
    label: @Composable (option: T, selected: Boolean) -> Unit
) {
    SingleChoiceSegmentedButtonRow(modifier = modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selectedOption
            SegmentedButton(
                modifier =
                    Modifier
                        .then(if (fillEqually) Modifier.weight(1f) else Modifier)
                        .heightIn(min = minHeight),
                selected = isSelected,
                onClick = { if (!isSelected) onOptionSelected(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {
                    icon?.invoke(option, isSelected)
                },
                label = {
                    label(option, isSelected)
                }
            )
        }
    }
}

@Composable
fun <T> RethinkConnectedChoiceButtonRow(
    options: List<T>,
    selectedOption: T,
    onOptionSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
    buttonMinHeight: Dp = 0.dp,
    icon: (@Composable (option: T, selected: Boolean) -> Unit)? = null,
    label: @Composable (option: T, selected: Boolean) -> Unit
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selectedOption
            ToggleButton(
                checked = isSelected,
                onCheckedChange = { checked ->
                    if (checked && !isSelected) {
                        onOptionSelected(option)
                    }
                },
                shapes =
                    when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    },
                colors =
                    ToggleButtonDefaults.toggleButtonColors(
                        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        checkedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.82f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                border = null,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = buttonMinHeight)
                    .semantics { role = Role.RadioButton }
            ) {
                icon?.let {
                    it(option, isSelected)
                    Spacer(modifier = Modifier.size(ToggleButtonDefaults.IconSpacing))
                }
                label(option, isSelected)
            }
        }
    }
}

@Composable
fun RethinkTwoOptionSegmentedRow(
    leftLabel: String,
    rightLabel: String,
    leftSelected: Boolean,
    onLeftClick: () -> Unit,
    onRightClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp = 0.dp
) {
    RethinkSegmentedChoiceRow(
        options = listOf(true, false),
        selectedOption = leftSelected,
        onOptionSelected = { selected ->
            if (selected) onLeftClick() else onRightClick()
        },
        modifier = modifier,
        fillEqually = true,
        minHeight = minHeight,
        label = { selected, _ ->
            Text(text = if (selected) leftLabel else rightLabel)
        }
    )
}

@Composable
fun RethinkDropdownSelector(
    selectedText: String,
    options: List<String>,
    onOptionSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    val containerColor =
        if (enabled) {
            MaterialTheme.colorScheme.surfaceContainerLow
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }

    Box(modifier = modifier.fillMaxWidth()) {
        Surface(
            shape = RoundedCornerShape(Dimensions.cornerRadiusMdLg),
            color = containerColor,
            border = BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f)
            ),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = enabled) { expanded = true }
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = Dimensions.spacingLg,
                            vertical = Dimensions.spacingMd
                        ),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = selectedText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        DropdownMenu(
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        expanded = false
                        onOptionSelected(option)
                    }
                )
            }
        }
    }
}

@Composable
fun RethinkConfirmDialog(
    onDismissRequest: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    message: String? = null,
    dismissText: String? = null,
    onDismiss: (() -> Unit)? = onDismissRequest,
    isConfirmDestructive: Boolean = false,
    confirmEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    text: (@Composable (() -> Unit))? = null
) {
    val confirmColors =
        if (isConfirmDestructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        title = title?.let { { Text(text = it) } },
        text = text ?: message?.let { { Text(text = it) } },
        confirmButton = {
            TextButton(onClick = onConfirm, colors = confirmColors, enabled = confirmEnabled) {
                Text(text = confirmText)
            }
        },
        dismissButton =
            if (dismissText != null && onDismiss != null) {
                {
                    TextButton(onClick = onDismiss, enabled = dismissEnabled) {
                        Text(text = dismissText)
                    }
                }
            } else {
                null
            }
    )
}

@Composable
fun RethinkMultiActionDialog(
    onDismissRequest: () -> Unit,
    title: String,
    primaryText: String,
    onPrimary: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    secondaryText: String? = null,
    onSecondary: (() -> Unit)? = null,
    tertiaryText: String? = null,
    onTertiary: (() -> Unit)? = null,
    isPrimaryDestructive: Boolean = false,
    text: (@Composable (() -> Unit))? = null
) {
    val primaryColors =
        if (isPrimaryDestructive) {
            ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
        } else {
            ButtonDefaults.textButtonColors()
        }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = onDismissRequest,
        title = { Text(text = title) },
        text = text ?: message?.let { { Text(text = it) } },
        confirmButton = {
            TextButton(onClick = onPrimary, colors = primaryColors) {
                Text(text = primaryText)
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingXs)) {
                if (secondaryText != null && onSecondary != null) {
                    TextButton(onClick = onSecondary) {
                        Text(text = secondaryText)
                    }
                }
                if (tertiaryText != null && onTertiary != null) {
                    TextButton(onClick = onTertiary) {
                        Text(text = tertiaryText)
                    }
                }
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RethinkBottomSheetDragHandle(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = Dimensions.spacingXs, bottom = Dimensions.spacingSm),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .width(44.dp)
                .height(5.dp),
            shape = RoundedCornerShape(100),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.42f)
        ) {}
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RethinkModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    dragHandle: @Composable (() -> Unit)? = { RethinkBottomSheetDragHandle() },
    containerColor: Color = Color.Unspecified,
    contentPadding: PaddingValues = PaddingValues(
        horizontal = Dimensions.screenPaddingHorizontal,
        vertical = Dimensions.spacingSm
    ),
    verticalSpacing: Dp = Dimensions.spacingLg,
    includeBottomSpacer: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        dragHandle = dragHandle,
        containerColor =
            if (containerColor == Color.Unspecified) {
                MaterialTheme.colorScheme.surface
            } else {
                containerColor
            }
    ) {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(verticalSpacing)
        ) {
            content()
            if (includeBottomSpacer) {
                Spacer(modifier = Modifier.height(Dimensions.spacing2xl))
            }
        }
    }
}

@Composable
fun RethinkBottomSheetCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = RoundedCornerShape(Dimensions.cornerRadius3xl),
    contentPadding: PaddingValues = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f)),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            verticalArrangement = Arrangement.spacedBy(Dimensions.spacingSm),
            content = content
        )
    }
}

@Composable
fun RethinkBottomSheetActionRow(
    modifier: Modifier = Modifier,
    primaryText: String,
    onPrimaryClick: () -> Unit,
    secondaryText: String? = null,
    onSecondaryClick: (() -> Unit)? = null,
    primaryEnabled: Boolean = true,
    secondaryEnabled: Boolean = true,
    secondaryStyle: RethinkSecondaryActionStyle = RethinkSecondaryActionStyle.TONAL,
    useCardContainer: Boolean = false
) {
    val content: @Composable () -> Unit = {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Dimensions.spacingMd,
                        vertical = Dimensions.spacingSmMd
                    ),
            horizontalArrangement = Arrangement.spacedBy(Dimensions.spacingSmMd),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (secondaryText != null && onSecondaryClick != null) {
                when (secondaryStyle) {
                    RethinkSecondaryActionStyle.TONAL -> {
                        FilledTonalButton(
                            modifier = Modifier.weight(1f),
                            onClick = onSecondaryClick,
                            enabled = secondaryEnabled
                        ) {
                            Text(text = secondaryText)
                        }
                    }

                    RethinkSecondaryActionStyle.OUTLINED -> {
                        OutlinedButton(
                            modifier = Modifier.weight(1f),
                            onClick = onSecondaryClick,
                            enabled = secondaryEnabled
                        ) {
                            Text(text = secondaryText)
                        }
                    }

                    RethinkSecondaryActionStyle.TEXT -> {
                        TextButton(
                            modifier = Modifier.weight(1f),
                            onClick = onSecondaryClick,
                            enabled = secondaryEnabled
                        ) {
                            Text(text = secondaryText)
                        }
                    }
                }
                Button(
                    modifier = Modifier.weight(1f),
                    onClick = onPrimaryClick,
                    enabled = primaryEnabled
                ) {
                    Text(text = primaryText)
                }
            } else {
                Spacer(modifier = Modifier.weight(1f))
                Button(
                    onClick = onPrimaryClick,
                    enabled = primaryEnabled
                ) {
                    Text(text = primaryText)
                }
            }
        }
    }

    if (useCardContainer) {
        RethinkBottomSheetCard(
            modifier = modifier,
            shape = RoundedCornerShape(Dimensions.cornerRadiusXl),
            contentPadding = PaddingValues(0.dp)
        ) {
            content()
        }
    } else {
        Box(
            modifier =
                modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = Dimensions.screenPaddingHorizontal,
                        vertical = Dimensions.spacingXs
                    )
        ) {
            content()
        }
    }
}

/**
 * Section header — M3 Expressive style with more prominent styling and typography.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = Dimensions.screenPaddingHorizontal,
                end = Dimensions.screenPaddingHorizontal,
                top = Dimensions.spacingMd,
                bottom = Dimensions.spacingSm
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.1.sp,
            color = color
        )
        if (actionLabel != null && onAction != null) {
            TextButton(
                onClick = onAction,
                contentPadding = PaddingValues(horizontal = Dimensions.spacingSm, vertical = 0.dp)
            ) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = color,
                    letterSpacing = 0.1.sp
                )
            }
        }
    }
}

/**
 * Section header with optional subtitle for more context.
 */
@Composable
fun SectionHeaderWithSubtitle(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String? = null,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(
                start = Dimensions.screenPaddingHorizontal,
                end = Dimensions.screenPaddingHorizontal,
                top = Dimensions.spacingMd,
                bottom = Dimensions.spacingSm
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 0.1.sp,
                color = color
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(
                        horizontal = Dimensions.spacingSm,
                        vertical = 0.dp
                    )
                ) {
                    Text(
                        text = actionLabel,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = color,
                        letterSpacing = 0.1.sp
                    )
                }
            }
        }
        if (subtitle != null) {
            Spacer(modifier = Modifier.height(Dimensions.spacingXs))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.sp
            )
        }
    }
}

// ==================== BUTTONS ====================

/**
 * Primary action button — M3 Expressive full-pill shape.
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(Dimensions.buttonHeight),
        enabled = enabled,
        shape = RoundedCornerShape(Dimensions.buttonCornerRadius),
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        )
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.iconSizeSm)
            )
            Spacer(modifier = Modifier.width(Dimensions.spacingSm))
        }
        Text(text = text, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Secondary action button — pill shape.
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(Dimensions.buttonHeight),
        enabled = enabled,
        shape = RoundedCornerShape(Dimensions.buttonCornerRadius)
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(Dimensions.iconSizeSm)
            )
            Spacer(modifier = Modifier.width(Dimensions.spacingSm))
        }
        Text(text = text)
    }
}

// ==================== STAT ITEMS ====================

/**
 * Stat display for dashboard cards.
 */
@Composable
fun StatItem(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    isHighlighted: Boolean = false
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = if (isHighlighted) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurface,
            letterSpacing = (-0.5).sp
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = Opacity.MEDIUM),
            letterSpacing = 0.2.sp
        )
    }
}

// ==================== EXPRESSIVE LAYOUT PRIMITIVES ====================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RethinkTopBar(
    title: String,
    onBackClick: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    scrolledContainerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    actions: @Composable RowScope.() -> Unit = {}
) {
    TopAppBar(
        title = {
            Text(
                text = title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = (-0.15).sp
            )
        },
        navigationIcon = {
            if (onBackClick != null) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_navigate_back)
                    )
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = scrolledContainerColor,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RethinkLargeTopBar(
    title: String,
    subtitle: String? = null,
    onBackClick: (() -> Unit)? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    scrolledContainerColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    titleTextStyle: TextStyle = MaterialTheme.typography.titleLarge,
    titleStartPadding: Dp = 0.dp,
    titleLeading: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    LargeTopAppBar(
        title = {
            Row(
                modifier = Modifier.padding(start = titleStartPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                titleLeading?.invoke()
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = title,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = titleTextStyle,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.2).sp
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            text = subtitle,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        },
        navigationIcon = {
            if (onBackClick != null) {
                IconButton(onClick = onBackClick) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.cd_navigate_back)
                    )
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = containerColor,
            scrolledContainerColor = scrolledContainerColor,
            navigationIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

enum class CardPosition {
    First, Middle, Last, Single
}

fun cardPositionFor(index: Int, lastIndex: Int): CardPosition {
    return when {
        lastIndex <= 0 -> CardPosition.Single
        index == 0 -> CardPosition.First
        index == lastIndex -> CardPosition.Last
        else -> CardPosition.Middle
    }
}

/**
 * Expressive grouped list container — M3 Expressive card shape.
 */
@Composable
fun RethinkListGroup(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        content = content
    )
}

/**
 * Expressive list item with spring press animation and tinted icon container.
 */
@Composable
fun RethinkListItem(
    modifier: Modifier = Modifier,
    headline: String,
    headlineAnnotated: AnnotatedString? = null,
    supporting: String? = null,
    supportingAnnotated: AnnotatedString? = null,
    contentOffset: Modifier = Modifier,
    leadingIcon: ImageVector? = null,
    leadingIconPainter: Painter? = null,
    leadingIconTint: Color = MaterialTheme.colorScheme.primary,
    leadingIconContainerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
    leadingIconContainerShape: Shape = RoundedCornerShape(Dimensions.iconContainerRadius),
    leadingContent: @Composable (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    position: CardPosition = CardPosition.Middle,
    enabled: Boolean = true,
    highlighted: Boolean = false,
    defaultContainerColor: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    highlightContainerColor: Color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.34f),
    showTrailingChevron: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val hapticFeedback = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessMediumLow),
        label = "listItemScale"
    )

    val itemShape = when (position) {
        CardPosition.Single -> RoundedCornerShape(Dimensions.cornerRadius3xl)
        CardPosition.First -> RoundedCornerShape(
            topStart = 22.dp,
            topEnd = 22.dp,
            bottomStart = 6.dp,
            bottomEnd = 6.dp
        )

        CardPosition.Last -> RoundedCornerShape(
            topStart = 6.dp,
            topEnd = 6.dp,
            bottomStart = 22.dp,
            bottomEnd = 22.dp
        )

        CardPosition.Middle -> RoundedCornerShape(Dimensions.cornerRadiusSm)
    }

    val contentAlpha = if (enabled) 1f else 0.5f
    val highlightAlpha by animateFloatAsState(
        targetValue = if (highlighted) 1f else 0f,
        animationSpec = tween(durationMillis = 120),
        label = "listItemHighlight"
    )
    val containerColor = lerp(defaultContainerColor, highlightContainerColor, highlightAlpha)
    val wrappedOnClick =
        if (onClick != null) {
            {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
        } else {
            null
        }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .scale(scale)
    ) {
        Surface(
            shape = itemShape,
            color = containerColor,
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    top = if (position == CardPosition.First || position == CardPosition.Single) 0.dp else 2.dp
                ),
            onClick = wrappedOnClick ?: {},
            enabled = wrappedOnClick != null && enabled,
            interactionSource = interactionSource
        ) {
            ListItem(
                headlineContent = {
                    Text(
                        text = headlineAnnotated ?: AnnotatedString(headline),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                        letterSpacing = 0.sp,
                        modifier = contentOffset
                    )
                },
                supportingContent =
                    if (supporting != null || supportingAnnotated != null) {
                        {
                            Text(
                                text = supportingAnnotated ?: AnnotatedString(supporting.orEmpty()),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.82f * contentAlpha),
                                letterSpacing = 0.sp,
                                modifier = contentOffset.then(Modifier.padding(top = Dimensions.spacingXs))
                            )
                        }
                    } else {
                        null
                },
                leadingContent = {
                    if (leadingContent != null) {
                        leadingContent()
                    } else if (leadingIcon != null || leadingIconPainter != null) {
                        Surface(
                            shape = leadingIconContainerShape,
                            color = leadingIconContainerColor,
                            modifier = Modifier.size(Dimensions.iconContainerSm)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(Dimensions.iconContainerSm)
                            ) {
                                if (leadingIcon != null) {
                                    Icon(
                                        imageVector = leadingIcon,
                                        contentDescription = null,
                                        tint = leadingIconTint.copy(alpha = contentAlpha),
                                        modifier = Modifier.size(Dimensions.iconSizeSm)
                                    )
                                } else if (leadingIconPainter != null) {
                                    if (leadingIconTint == Color.Unspecified) {
                                        Image(
                                            painter = leadingIconPainter,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    } else {
                                        Icon(
                                            painter = leadingIconPainter,
                                            contentDescription = null,
                                            tint = leadingIconTint.copy(alpha = contentAlpha),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                },
                trailingContent =
                    when {
                        trailing != null -> trailing
                        showTrailingChevron && onClick != null && enabled -> {
                            {
                                Icon(
                                    imageVector = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                                )
                            }
                        }

                        else -> null
                    },
                colors = ListItemDefaults.colors(
                    containerColor = Color.Transparent
                ),
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                modifier = Modifier
                    .clip(itemShape)
                    .padding(horizontal = Dimensions.spacingNone, vertical = 1.dp)
            )
        }
    }
}

@Composable
fun RethinkActionListItem(
    title: String,
    description: String? = null,
    iconRes: Int? = null,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    leadingContent: @Composable (() -> Unit)? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    position: CardPosition = CardPosition.Middle,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    trailing: @Composable (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val resolvedPainter = iconPainter ?: iconRes?.let { painterResource(id = it) }
    RethinkListItem(
        headline = title,
        supporting = description,
        leadingIcon = icon,
        leadingIconPainter = resolvedPainter,
        leadingContent = leadingContent,
        leadingIconTint = accentColor,
        leadingIconContainerColor = accentColor.copy(alpha = 0.14f),
        position = position,
        highlighted = highlighted,
        enabled = enabled,
        showTrailingChevron = false,
        trailing = trailing,
        onClick = onClick
    )
}

@Composable
fun RethinkToggleListItem(
    title: String,
    description: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    iconRes: Int? = null,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    position: CardPosition = CardPosition.Middle,
    highlighted: Boolean = false,
    enabled: Boolean = true,
    onRowClick: (() -> Unit)? = null,
    trailingPrefix: @Composable (() -> Unit)? = null
) {
    val hapticFeedback = LocalHapticFeedback.current
    val onSwitchChange: (Boolean) -> Unit = { value ->
        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onCheckedChange(value)
    }
    RethinkActionListItem(
        title = title,
        description = description,
        iconRes = iconRes,
        icon = icon,
        iconPainter = iconPainter,
        accentColor = accentColor,
        position = position,
        highlighted = highlighted,
        enabled = enabled,
        trailing = {
            if (trailingPrefix == null) {
                Switch(
                    checked = checked,
                    onCheckedChange = onSwitchChange,
                    enabled = enabled
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    trailingPrefix()
                    Switch(
                        checked = checked,
                        onCheckedChange = onSwitchChange,
                        enabled = enabled
                    )
                }
            }
        },
        onClick = onRowClick ?: { onCheckedChange(!checked) }
    )
}

@Composable
fun RethinkRadioListItem(
    title: String,
    description: String? = null,
    selected: Boolean,
    onSelect: () -> Unit,
    iconRes: Int? = null,
    icon: ImageVector? = null,
    iconPainter: Painter? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    position: CardPosition = CardPosition.Middle,
    highlighted: Boolean = false,
    onInfoClick: (() -> Unit)? = null
) {
    val hapticFeedback = LocalHapticFeedback.current
    RethinkActionListItem(
        title = title,
        description = description,
        iconRes = iconRes,
        icon = icon,
        iconPainter = iconPainter,
        accentColor = accentColor,
        position = position,
        highlighted = highlighted,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (onInfoClick != null) {
                    IconButton(
                        onClick = onInfoClick,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = stringResource(id = R.string.lbl_info),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                RadioButton(
                    selected = selected,
                    onClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        onSelect()
                    }
                )
            }
        },
        onClick = onSelect
    )
}
