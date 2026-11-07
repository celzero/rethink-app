/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
package com.celzero.bravedns.ui.compose.trafficlogs

import androidx.compose.ui.platform.LocalLocale
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.database.*
import com.celzero.bravedns.ui.compose.themeColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventsState {
    val logs = LogScreenState()
    var severityChip by mutableStateOf("ALL")
    // Query scope and hidden chip checks are distinct in the baseline.
    var sourceChips by mutableStateOf(emptySet<EventSource>())
    var refreshing by mutableStateOf(false)
}
internal fun eventSeverityColor(severity: Severity) = when (severity) { Severity.LOW -> 0xFF4CAF50.toInt(); Severity.MEDIUM -> 0xFFFFC107.toInt(); Severity.HIGH -> 0xFFFF9800.toInt(); Severity.CRITICAL -> 0xFFF44336.toInt() }
internal val LogAccelerateDecelerate = Easing { fraction -> ((kotlin.math.cos((fraction + 1f) * Math.PI) / 2.0) + .5).toFloat() }
@Composable fun EventLogRow(event: Event, onCopy: (String) -> Unit) {
    var expanded by remember(event) { mutableStateOf(false) }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(300, easing = LogAccelerateDecelerate), label = "event-details")
    val color = Color(eventSeverityColor(event.severity))
    val hasDetails = !event.details.isNullOrBlank()
    val icon = when (event.severity) { Severity.LOW -> R.drawable.ic_tick_normal; Severity.MEDIUM -> R.drawable.ic_app_info_accent; Severity.HIGH -> R.drawable.ic_block_accent; Severity.CRITICAL -> R.drawable.ic_block }
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).combinedClickable(onClick = { if (hasDetails) expanded = !expanded }, onLongClick = { onCopy(event.message) }).testTag("event-${event.id}"), shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = themeColor(androidx.cardview.R.attr.cardBackgroundColor))) {
        // Measure the content normally, then stretch the severity stripe to that height.
        // IntrinsicSize queries crash when the label uses a subcomposed control.
        Box(Modifier.padding(12.dp)) {
            Box(Modifier.matchParentSize()) {
                Box(Modifier.width(2.dp).fillMaxHeight().background(color))
            }
            Column(Modifier.fillMaxWidth().padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.Center) {
                        Icon(painterResource(icon), event.severity.name, Modifier.size(24.dp), tint = color)
                        com.celzero.bravedns.ui.compose.controls.OriginalLabel("item_event:event_type_chip") { Text(event.eventType.name.replace("_", " ")) }
                        Text(event.severity.name, Modifier.background(color).padding(horizontal = 10.dp, vertical = 4.dp), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall)
                    }
                    if (hasDetails) Icon(painterResource(R.drawable.ic_arrow_down), null, Modifier.size(24.dp).rotate(rotation), tint = themeColor(R.attr.svgStrokeColor))
                }
                Text(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", LocalLocale.current.platformLocale).format(Date(event.timestamp)), style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) { Text(event.source.name, style = MaterialTheme.typography.labelSmall); if (event.userAction) Icon(painterResource(R.drawable.ic_person), null, Modifier.padding(start = 18.dp).size(16.dp), tint = themeColor(R.attr.accentGood)) }
                Text(event.message, color = themeColor(R.attr.primaryLightColorText), style = MaterialTheme.typography.bodyMedium)
                AnimatedVisibility(expanded, enter = fadeIn(tween(300, easing = LogAccelerateDecelerate)), exit = fadeOut(tween(300, easing = LogAccelerateDecelerate))) { SelectionContainer { Text(event.details.orEmpty(), Modifier.padding(12.dp).padding(top = 4.dp), color = themeColor(R.attr.primaryLightColorText), style = MaterialTheme.typography.bodySmall) } }
            }
        }
    }
}
@Composable fun EventsScreen(state: EventsState, presenter: LogPagingPresenter<Event>, onSearchFocus: () -> Unit, onToggleFilters: () -> Unit, onSeverity: (String) -> Unit, onSource: (EventSource, Boolean) -> Unit, onRefresh: () -> Unit, onDelete: () -> Unit) {
    Column(Modifier.fillMaxSize().background(themeColor(R.attr.background)).safeDrawingPadding()) {
        LogSearchBar(state.logs, stringResource(R.string.search_event_logs), onSearchFocus, filters = {
            if (state.logs.showParentFilters) {
                val choices = listOf("ALL" to stringResource(R.string.lbl_all).lowercase().replaceFirstChar(Char::titlecase), "LOW" to "Low", "MEDIUM" to "Medium", "HIGH" to "High", "CRITICAL" to "Critical", "SOURCE" to "Source")
                LogFilterChips(choices.map { LogFilterChip(it.first, it.second, state.severityChip == it.first) }) { if (!it.selected) { state.severityChip = it.key; onSeverity(it.key) } }
            }
            if (state.logs.showChildFilters) LogFilterChips(EventSource.entries.map { LogFilterChip(it.name, if (it in setOf(EventSource.UI, EventSource.VPN, EventSource.DNS)) it.name else it.name.lowercase().replaceFirstChar(Char::titlecase), it in state.sourceChips) }, singleLine = true) {
                val source = EventSource.valueOf(it.key); state.sourceChips = if (it.selected) state.sourceChips - source else state.sourceChips + source; onSource(source, !it.selected)
            }
        }, actions = {
            LogAction(R.drawable.ic_filter, stringResource(R.string.fapps_filter_filter_heading), onToggleFilters)
            val angle = if (state.refreshing) {
                val transition = rememberInfiniteTransition(label = "log-refresh")
                val value by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(750, easing = LogAccelerateDecelerate)), label = "log-refresh-angle")
                value
            } else 0f
            LogAction(R.drawable.ic_refresh_white, stringResource(R.string.refresh_complete), onRefresh, enabled = !state.refreshing, modifier = Modifier.rotate(angle).testTag("events-refresh"))
            LogAction(R.drawable.ic_delete, stringResource(R.string.lbl_delete), onDelete)
        })
        LogList(state.logs, presenter, Modifier.weight(1f), timestamp = { it.timestamp }, thumbColorAttribute = R.attr.homeScreenHeaderTextColor, empty = {
            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(painterResource(R.drawable.ic_event_note), null, Modifier.size(120.dp).alpha(.5f), tint = themeColor(R.attr.homeScreenHeaderTextColor)); Text(stringResource(R.string.no_events_recorded), Modifier.padding(top = 16.dp), style = MaterialTheme.typography.headlineSmall); Text(stringResource(R.string.no_events_desc), Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium) }
        })
    }
}
