/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.common

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import com.celzero.bravedns.tv.ui.theme.TvColors
import com.celzero.bravedns.tv.ui.theme.TvGradients
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSizes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles

/**
 * Expressive shared widgets for the TV flavor — the component layer of
 * the M3 Expressive overhaul. Screens should prefer these over raw
 * tv-material components so elevation layering, gradients, focus
 * glow, and type emphasis stay consistent app-wide.
 *
 * Design intent: a premium system/network utility — deep neutral
 * surfaces with clear tonal layering, controlled indigo/cyan/orange
 * accents, generous corner radii, and calm focus states.
 */

/**
 * Filled expressive button: primary fill, pill-radius, clear
 * focus treatment. Use for primary actions on a screen. Pass
 * [containerColor] / [contentColor] to render a state-colored CTA
 * (e.g. red Stop / teal Start); focused and pressed keep the
 * primaryContainer treatment so focus feedback stays consistent.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    leadingIcon: ImageVector? = null,
    containerColor: Color? = null,
    contentColor: Color? = null,
    textStyle: TextStyle = MaterialTheme.typography.labelLarge,
    contentPadding: PaddingValues = ButtonDefaults.ContentPadding,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = ButtonDefaults.shape(shape = TvShapes.Medium),
        colors = if (destructive) {
            ButtonDefaults.colors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
                focusedContainerColor = MaterialTheme.colorScheme.error,
                focusedContentColor = MaterialTheme.colorScheme.onError,
                pressedContainerColor = MaterialTheme.colorScheme.error,
                pressedContentColor = MaterialTheme.colorScheme.onError,
                disabledContainerColor = TvColors.DisabledContainer,
                disabledContentColor = TvColors.DisabledContent,
            )
        } else {
            ButtonDefaults.colors(
                containerColor = containerColor ?: MaterialTheme.colorScheme.primary,
                contentColor = contentColor ?: MaterialTheme.colorScheme.onPrimary,
                focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                disabledContainerColor = TvColors.DisabledContainer,
                disabledContentColor = TvColors.DisabledContent,
            )
        },
        contentPadding = contentPadding,
        modifier = modifier,
    ) {
        if (leadingIcon != null) {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                modifier = Modifier.size(TvSizes.LeadingIcon - 6.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = textStyle)
    }
}

/**
 * The one selection control for the TV UI: tabs, filter chips,
 * segmented pickers, and strategy toggles all share this tonal
 * language — selected rest fills with the primary container (bold
 * label), focus/press lift to the full primary fill, unselected
 * rest sits on the surface variant. No ✓ prefix: selection reads
 * through fill + bold alone so widths stay stable.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvSelectableChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    enabled: Boolean = true,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
        colors = TvSurfaceStyles.railItemColors(selected = selected),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)) {
            Text(
                text = label,
                style = style.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
            )
        }
    }
}

/**
 * Row of [TvSelectableChip]s for mutually-exclusive choices.
 * [selectedFocusRequester] is attached to the selected chip only,
 * so callers can park D-pad focus on the current choice.
 */
@Composable
fun <T> TvChipRow(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    selectedFocusRequester: FocusRequester? = null,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
    ) {
        options.forEach { option ->
            key(option) {
                val chipModifier = if (option == selected && selectedFocusRequester != null) {
                    Modifier.focusRequester(selectedFocusRequester)
                } else {
                    Modifier
                }
                TvSelectableChip(
                    label = label(option),
                    selected = option == selected,
                    onClick = { onSelect(option) },
                    modifier = chipModifier,
                )
            }
        }
    }
}

/**
 * Severity ramp for [TvStatusPill]. Each level maps to a semantic
 * container/content pair so status chips carry meaning by color alone.
 */
enum class PillSeverity {
    SUCCESS,
    WARNING,
    ERROR,
    NEUTRAL,
}

/**
 * Status pill for binary on/off states — active states use the
 * success green, inactive states use the graphite variant tone.
 * [severity] overrides the container family when a state needs the
 * warning/error ramp instead (e.g. "partially protected").
 */
@Composable
fun TvStatusPill(
    text: String,
    active: Boolean,
    modifier: Modifier = Modifier,
    severity: PillSeverity = PillSeverity.SUCCESS,
) {
    val container = when {
        !active -> TvColors.SurfaceContainerHigh
        severity == PillSeverity.WARNING -> TvColors.WarningContainer
        severity == PillSeverity.ERROR -> TvColors.ErrorContainer
        else -> TvColors.SuccessContainer
    }
    val content = when {
        !active -> MaterialTheme.colorScheme.onSurfaceVariant
        severity == PillSeverity.WARNING -> TvColors.OnWarningContainer
        severity == PillSeverity.ERROR -> TvColors.OnErrorContainer
        else -> TvColors.OnSuccessContainer
    }
    Box(
        modifier = modifier
            .background(container, TvShapes.Pill)
            .padding(horizontal = TvSpacing.ContentHorizontal, vertical = 6.dp),
    ) {
        Text(
            text = text,
            color = content,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/**
 * Layered stat tile for dashboards: one tonal step above section
 * panels (SurfaceContainerHigh) so tiles read as the densest data on
 * the screen, with a large numeric hero and a quiet label. Not
 * focusable — pure display. [valueColor] tints the number with a
 * semantic traffic tone when the caller wants direction/blocked
 * meaning; null keeps the default onSurface emphasis.
 */
@Composable
fun TvStatTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    dimmed: Boolean = false,
    valueColor: Color? = null,
) {
    Surface(
        shape = TvShapes.Medium,
        colors = SurfaceDefaults.colors(
            containerColor = TvColors.SurfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    horizontal = TvSpacing.ContentHorizontal,
                    vertical = TvSpacing.ContentVertical,
                ),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                            .copy(alpha = if (dimmed) 0.5f else 1f),
                        modifier = Modifier.size(TvSizes.RailIcon + 2.dp),
                    )
                    Spacer(Modifier.width(TvSpacing.HeaderGap))
                }
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                        .copy(alpha = if (dimmed) 0.6f else 1f),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.height(TvSpacing.HeaderGap + 4.dp))
            Text(
                text = value,
                color = (valueColor ?: MaterialTheme.colorScheme.onSurface)
                    .copy(alpha = if (dimmed) 0.6f else 1f),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/**
 * Expressive hero panel: a subtle indigo-toned gradient over the
 * card layer with extra-large radii. Hosts the single most important
 * content on a screen (protection status, subscription hero).
 */
@Composable
fun TvHeroCard(
    modifier: Modifier = Modifier,
    brush: Brush = TvGradients.hero(),
    content: @Composable () -> Unit,
) {
    Surface(
        shape = TvShapes.ExtraLarge,
        colors = SurfaceDefaults.colors(
            containerColor = TvColors.SurfaceCard,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(brush, TvShapes.ExtraLarge),
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(TvSpacing.SectionGap),
            ) {
                content()
            }
        }
    }
}

/**
 * Section header: uppercase primary-toned label separating toggle
 * groups / card grids. App-wide replacement for ad-hoc headers.
 * [trailing] optionally places an action (link, count) on the same
 * row, aligned to the end.
 */
@Composable
fun TvSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(top = TvSpacing.GroupGap, bottom = TvSpacing.HeaderGap, start = 4.dp),
    ) {
        Text(
            text = text.uppercase(),
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.weight(1f),
        )
        if (trailing != null) {
            trailing()
        }
    }
}

/**
 * Primary-navigation tabs rendered as a row of shared selection
 * chips. Click/center activates (unchanged semantics); D-pad L/R
 * traverses the chips. Pass [selectedFocusRequester] to park focus
 * on the active tab after screen entry.
 */
@Composable
fun <T> TvTabRow(
    tabs: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    selectedFocusRequester: FocusRequester? = null,
) {
    TvChipRow(
        options = tabs,
        selected = selected,
        label = label,
        onSelect = onSelect,
        modifier = modifier,
        selectedFocusRequester = selectedFocusRequester,
    )
}

/** Small check icon badge used inside status rows. */
@Composable
internal fun TvCheckBadge(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(TvColors.Success, TvShapes.Pill),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Check,
            contentDescription = null,
            tint = TvColors.OnSuccess,
            modifier = Modifier
                .padding(4.dp)
                .size(20.dp),
        )
    }
}

/** Small circular semantic-status indicator (active/off/attention). */
@Composable
fun TvStatusDot(
    color: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(10.dp)
            .background(color, TvShapes.Pill),
    )
}

/**
 * Compact label/value row with a semantic dot — the hero's right-hand
 * status modules (DNS / Firewall / Proxy). [detail] is an
 * optional supporting line (resolver name, proxy endpoint) that
 * ellipsizes to one line.
 */
@Composable
fun TvStatusModule(
    label: String,
    value: String,
    dotColor: Color,
    modifier: Modifier = Modifier,
    detail: String? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        TvStatusDot(color = dotColor)
        Spacer(Modifier.width(TvSpacing.HeaderGap / 2))
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.width(TvSpacing.HeaderGap / 2))
                Text(
                    text = value,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            if (!detail.isNullOrBlank()) {
                Text(
                    text = detail,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Quick-action card for the Home shortcut row. Icon chip + title
 * only — at TV card widths a description line plus chevron leaves
 * ~30dp for text and everything ellipsizes. All cards share one
 * neutral SurfaceContainer base (a single coherent system, not four
 * unrelated colorful cards); only the leading icon chip carries the
 * module accent — [accent] tints the icon, [accentContainer] fills
 * the chip at container strength. On focus the whole card lifts to
 * the primary container with bright content: tonal fill + scale +
 * restrained glow, the standard expressive focus language.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun TvQuickActionCard(
    icon: ImageVector,
    title: String,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accentContainer: Color = TvColors.SurfaceContainerHighest,
) {
    Surface(
        onClick = onClick,
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Panel),
        colors = TvSurfaceStyles.rowColors(
            container = TvColors.SurfaceContainer,
            content = MaterialTheme.colorScheme.onSurface,
        ),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(accentContainer, TvShapes.Medium),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(24.dp),
                )
            }
            Spacer(Modifier.width(TvSpacing.CardGap))
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * One recent-activity event: leading tinted icon chip + event type
 * label, the hostname/app (or query string) as the primary line, and
 * a relative time on the end. Deliberately NOT focusable — the
 * Recent Activity section stays secondary to the live dashboard
 * widgets; the Logs destination is where rows get interactive.
 */
@Composable
fun TvEventRow(
    kind: String,
    primary: String,
    secondary: String?,
    time: String,
    kindColor: Color,
    kindIcon: ImageVector,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = TvSpacing.RowGap),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(kindColor.copy(alpha = 0.16f), TvShapes.Small),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = kindIcon,
                contentDescription = kind,
                tint = kindColor,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(TvSpacing.CardGap))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = kind,
                    color = kindColor,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(TvSpacing.HeaderGap))
                Text(
                    text = primary,
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!secondary.isNullOrBlank()) {
                Text(
                    text = secondary,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(TvSpacing.CardGap))
        Text(
            text = time,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * One slot of the network-activity visualization: the per-bucket
 * counts merged across the connection and DNS log tables.
 */
data class ActivitySlot(
    val allowed: Long,
    val blocked: Long,
)

/**
 * The Home screen's activity visualization: a three-wave flowing
 * composition built from Material 3's wavy linear progress primitive.
 * Each wave is a Row of full-progress wavy segments — one per time
 * bucket — whose amplitude encodes that bucket's real value, so the
 * whole row reads as a single flowing wave whose envelope is the
 * data. Total (indigo) is the tallest band, Allowed (teal) the middle
 * one, Blocked (amber) the shortest; the lower bands overlap the ones
 * above slightly for a layered feel. Amplitude changes animate via the
 * primitive's built-in specs, so refreshes ease instead of snapping.
 * Pure display — not focusable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TvActivityWave(
    slots: List<ActivitySlot>,
    modifier: Modifier = Modifier,
) {
    val totals = slots.map { it.allowed + it.blocked }
    val allowed = slots.map { it.allowed }
    val blocked = slots.map { it.blocked }
    val summary = slots.fold(0L to 0L) { acc, slot ->
        acc.first + slot.allowed to acc.second + slot.blocked
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
        modifier = modifier
            .height(TvSizes.ActivityWaveCompositionHeight)
            // One semantics node for the whole composition — the raw
            // primitive would otherwise publish a progress-bar node
            // per segment and flood the TV accessibility tree.
            .clearAndSetSemantics {
                contentDescription =
                    "Network activity: ${summary.first} allowed, " +
                    "${summary.second} blocked in the selected window"
            },
    ) {
        TvWaveRow(
            values = totals,
            color = TvColors.Primary,
            bandHeight = TvSizes.ActivityWaveTotalHeight,
        )
        TvWaveRow(
            values = allowed,
            color = TvColors.Secondary,
            bandHeight = TvSizes.ActivityWaveAllowedHeight,
            modifier = Modifier.offset(y = (-6).dp),
        )
        TvWaveRow(
            values = blocked,
            color = TvColors.Warning,
            bandHeight = TvSizes.ActivityWaveBlockedHeight,
            modifier = Modifier.offset(y = (-6).dp),
        )
    }
}

/**
 * One wave band: a Row of static full-progress wavy segments, one per
 * bucket. `waveSpeed = 0.dp` keeps the wave fully static (no lateral
 * drift); the per-segment amplitude is the bucket's value normalized
 * against the series max, floored so quiet buckets render a calm
 * baseline wave instead of a dead line. Rounded stroke caps blend
 * adjacent segments into one continuous wave.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TvWaveRow(
    values: List<Long>,
    color: Color,
    bandHeight: Dp,
    modifier: Modifier = Modifier,
    stroke: Dp = TvSizes.ActivityWaveStroke,
) {
    val max = values.maxOrNull()?.coerceAtLeast(1L) ?: 1L
    val strokePx = with(LocalDensity.current) { stroke.toPx() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth(),
    ) {
        values.forEach { value ->
            val normalized = when {
                value <= 0L -> 0.05f
                else -> (value.toFloat() / max).coerceIn(0.15f, 1f)
            }
            LinearWavyProgressIndicator(
                progress = { 1f },
                color = color,
                trackColor = Color.Transparent,
                stroke = Stroke(width = strokePx, cap = StrokeCap.Round),
                gapSize = 0.dp,
                stopSize = 0.dp,
                amplitude = { normalized },
                wavelength = TvSizes.ActivityWaveWavelength,
                waveSpeed = 0.dp,
                modifier = Modifier
                    .weight(1f)
                    .height(bandHeight),
            )
        }
    }
}

/**
 * Compact one-row legend for [TvActivityWave]: colored dot + label
 * per series. Deliberately lightweight — no cards, no chrome.
 */
@Composable
fun TvWaveLegend(modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TvSpacing.CardGap),
        modifier = modifier,
    ) {
        TvWaveLegendItem(label = "Network", color = TvColors.Primary)
        TvWaveLegendItem(label = "Allowed", color = TvColors.Secondary)
        TvWaveLegendItem(label = "Blocked", color = TvColors.Warning)
    }
}

@Composable
private fun TvWaveLegendItem(label: String, color: Color) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TvStatusDot(color = color)
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

/**
 * Hero state wrapper: cross-fades (with the shared quick spring)
 * between protection states so a start/stop reads as a deliberate
 * state change rather than an abrupt text swap. No perpetual
 * animation — [AnimatedContent] only runs while [targetState] moves.
 */
@Composable
fun AnimatedProtectionState(
    targetState: String,
    modifier: Modifier = Modifier,
    content: @Composable (String) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        transitionSpec = {
            (fadeIn(animationSpec = tween(180)) + scaleIn(
                initialScale = 0.96f,
                animationSpec = tween(180),
            )) togetherWith fadeOut(animationSpec = tween(120))
        },
        label = "protectionState",
        modifier = modifier,
    ) { state ->
        content(state)
    }
}
