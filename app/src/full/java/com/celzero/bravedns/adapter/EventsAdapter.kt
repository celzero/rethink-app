/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.adapter

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.database.Event
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.Severity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun copyEventToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("Event Message", text)
    clipboard.setPrimaryClip(clip)
    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
}

@Composable
fun EventCard(
    event: Event,
    onCopy: (String) -> Unit,
    query: String = "",
    position: EventCardPosition = EventCardPosition.Single
) {
    val hasDetails = !event.details.isNullOrBlank()
    var expanded by remember(event.id) { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.985f else 1f,
        animationSpec = tween(durationMillis = 120),
        label = "event_card_scale"
    )

    val accent = event.severity.toAccentColor()
    val timestampText = remember(event.timestamp) { formatEventTimestamp(event.timestamp) }
    val highlightColor = MaterialTheme.colorScheme.primary
    val highlightedMessage = remember(event.message, query, highlightColor) {
        buildHighlightedText(event.message, query, highlightColor)
    }
    val highlightedDetails = remember(event.details, query, highlightColor) {
        buildHighlightedText(event.details.orEmpty(), query, highlightColor)
    }

    val shape = eventShapeFor(position)

    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = {
                    if (hasDetails) {
                        expanded = !expanded
                    }
                },
                onLongClick = { onCopy(event.toClipboardText()) }
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EventSeverityIcon(
                severity = event.severity,
                accentColor = accent,
                modifier = Modifier.size(40.dp)
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = highlightedMessage,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = timestampText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CompactEventBadge(
                        text = event.severity.name,
                        containerColor = accent.copy(alpha = 0.14f),
                        contentColor = accent
                    )
                    CompactEventBadge(
                        text = event.source.toDisplayLabel(),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f),
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    CompactEventBadge(
                        text = event.eventType.name.toDisplayLabel(),
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    if (event.userAction) {
                        CompactEventBadge(
                            text = "User",
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                            contentColor = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                AnimatedVisibility(visible = expanded && hasDetails) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        HorizontalDivider(
                            thickness = 1.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.24f),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        Text(
                            text = highlightedDetails,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 8,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            IconButton(
                onClick = { onCopy(event.toClipboardText()) },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_copy),
                    contentDescription = "Copy",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

enum class EventCardPosition {
    First,
    Middle,
    Last,
    Single
}

private fun eventShapeFor(position: EventCardPosition): RoundedCornerShape {
    return when (position) {
        EventCardPosition.First ->
            RoundedCornerShape(
                topStart = 18.dp,
                topEnd = 18.dp,
                bottomStart = 10.dp,
                bottomEnd = 10.dp
            )
        EventCardPosition.Middle -> RoundedCornerShape(10.dp)
        EventCardPosition.Last ->
            RoundedCornerShape(
                topStart = 10.dp,
                topEnd = 10.dp,
                bottomStart = 18.dp,
                bottomEnd = 18.dp
            )
        EventCardPosition.Single -> RoundedCornerShape(18.dp)
    }
}

@Composable
private fun EventSeverityIcon(
    severity: Severity,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    val icon = when (severity) {
        Severity.LOW -> Icons.Filled.CheckCircle
        Severity.MEDIUM -> Icons.Filled.Info
        Severity.HIGH -> Icons.Filled.Warning
        Severity.CRITICAL -> Icons.Filled.Error
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = accentColor.copy(alpha = 0.12f),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = severity.name,
                tint = accentColor,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

@Composable
private fun CompactEventBadge(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun EventBadge(
    text: String,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = containerColor
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            maxLines = 1
        )
    }
}

private fun Event.toClipboardText(): String {
    return buildString {
        append(message)
        details?.takeIf { it.isNotBlank() }?.let {
            append("\n\n")
            append(it)
        }
    }
}

private fun EventSource.toDisplayLabel(): String {
    return name.toDisplayLabel()
}

private fun String.toDisplayLabel(): String {
    return lowercase(Locale.getDefault())
        .replace('_', ' ')
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
}

private fun Severity.toAccentColor(): Color {
    return when (this) {
        Severity.LOW -> Color(0xFF2E7D32)
        Severity.MEDIUM -> Color(0xFFAD7F00)
        Severity.HIGH -> Color(0xFFB85C00)
        Severity.CRITICAL -> Color(0xFFB3261E)
    }
}

private fun formatEventTimestamp(timestamp: Long): String {
    val formatter = SimpleDateFormat("dd MMM, HH:mm:ss", Locale.getDefault())
    return formatter.format(Date(timestamp))
}

private fun buildHighlightedText(
    text: String,
    query: String,
    highlightColor: Color
): AnnotatedString {
    if (text.isBlank() || query.isBlank()) return AnnotatedString(text)

    val ranges = mutableListOf<Pair<Int, Int>>()
    val tokens = query.split(Regex("\\s+")).map { it.trim() }.filter { it.isNotBlank() }.distinct()

    tokens.forEach { token ->
        var startIndex = 0
        while (startIndex < text.length) {
            val index = text.indexOf(token, startIndex = startIndex, ignoreCase = true)
            if (index < 0) break
            ranges += index to (index + token.length)
            startIndex = index + token.length
        }
    }

    if (ranges.isEmpty()) return AnnotatedString(text)

    val merged = ranges
        .sortedBy { it.first }
        .fold(mutableListOf<Pair<Int, Int>>()) { acc, range ->
            if (acc.isEmpty()) {
                acc += range
                return@fold acc
            }
            val last = acc.last()
            if (range.first <= last.second) {
                acc[acc.lastIndex] = last.first to maxOf(last.second, range.second)
            } else {
                acc += range
            }
            acc
        }

    return buildAnnotatedString {
        append(text)
        merged.forEach { (start, end) ->
            addStyle(
                style = SpanStyle(color = highlightColor, fontWeight = FontWeight.Bold),
                start = start,
                end = end
            )
        }
    }
}
