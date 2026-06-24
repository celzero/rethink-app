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
package com.celzero.bravedns.ui.compose.events

import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.EventCard
import com.celzero.bravedns.adapter.EventCardPosition
import com.celzero.bravedns.adapter.copyEventToClipboard
import com.celzero.bravedns.database.Event
import com.celzero.bravedns.database.EventDao
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkFilterChip
import com.celzero.bravedns.ui.compose.theme.RethinkSearchField
import com.celzero.bravedns.ui.compose.theme.RethinkTopBar
import com.celzero.bravedns.viewmodel.EventsViewModel
import com.celzero.bravedns.viewmodel.EventsViewModel.TopLevelFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun EventsScreen(
    viewModel: EventsViewModel,
    eventDao: EventDao,
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf(viewModel.getCurrentQuery()) }
    var filterSources by remember { mutableStateOf(viewModel.getCurrentSources()) }
    var filterSeverity by remember { mutableStateOf(viewModel.getCurrentSeverity()) }
    var filterType by remember { mutableStateOf(viewModel.getFilterType()) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    val items = viewModel.eventsFlow.collectAsLazyPagingItems()
    val isLoading = items.loadState.refresh is LoadState.Loading && items.itemCount == 0
    val showEmpty = items.itemCount == 0 && items.loadState.refresh is LoadState.NotLoading

    fun applyFilter(
        sources: Set<EventSource> = filterSources,
        severity: Severity? = filterSeverity,
        type: TopLevelFilter = filterType
    ) {
        filterSources = sources
        filterSeverity = severity
        filterType = type
        viewModel.setFilterType(type)
        viewModel.setFilter(query, sources, severity)
    }

    LaunchedEffect(Unit) {
        snapshotFlow { query }
            .debounce(QUERY_TEXT_DELAY)
            .distinctUntilChanged()
            .collect { value ->
                viewModel.setFilter(value, filterSources, filterSeverity)
            }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        topBar = {
            RethinkTopBar(
                title = stringResource(id = R.string.event_logs_title),
                onBackClick = onBackClick,
                actions = {
                    IconButton(onClick = { items.refresh() }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(id = R.string.cd_refresh)
                        )
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            imageVector = Icons.Rounded.Delete,
                            contentDescription = stringResource(id = R.string.lbl_delete)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                EventControlsCard(
                    query = query,
                    onQueryChange = { query = it },
                    filterType = filterType,
                    filterSeverity = filterSeverity,
                    filterSources = filterSources,
                    onClearQuery = { query = "" },
                    onAllClick = { applyFilter(emptySet(), null, TopLevelFilter.ALL) },
                    onSeverityModeClick = { applyFilter(emptySet(), filterSeverity, TopLevelFilter.SEVERITY) },
                    onSourceModeClick = { applyFilter(filterSources, null, TopLevelFilter.SOURCE) },
                    onSeverityClick = { applyFilter(emptySet(), it, TopLevelFilter.SEVERITY) },
                    onSourceToggle = { source ->
                        val updated =
                            if (filterSources.contains(source)) {
                                filterSources - source
                            } else {
                                filterSources + source
                            }
                        applyFilter(updated, null, TopLevelFilter.SOURCE)
                    }
                )

                EventsList(
                    modifier = Modifier.weight(1f),
                    items = items,
                    query = query,
                    onCopy = { copyEventToClipboard(context, it) }
                )
            }

            when {
                isLoading -> LoadingState()
                showEmpty -> EmptyState()
            }
        }
    }

    if (showDeleteDialog) {
        RethinkConfirmDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = stringResource(id = R.string.ada_delete_logs_dialog_title),
            message = stringResource(id = R.string.ada_delete_logs_dialog_desc),
            confirmText = stringResource(id = R.string.lbl_delete),
            dismissText = stringResource(id = R.string.lbl_cancel),
            onConfirm = {
                showDeleteDialog = false
                scope.launch(Dispatchers.IO) { eventDao.deleteAll() }
                items.refresh()
            },
            onDismiss = { showDeleteDialog = false },
            isConfirmDestructive = true
        )
    }
}

@Composable
private fun EventControlsCard(
    query: String,
    onQueryChange: (String) -> Unit,
    filterType: TopLevelFilter,
    filterSeverity: Severity?,
    filterSources: Set<EventSource>,
    onClearQuery: () -> Unit,
    onAllClick: () -> Unit,
    onSeverityModeClick: () -> Unit,
    onSourceModeClick: () -> Unit,
    onSeverityClick: (Severity) -> Unit,
    onSourceToggle: (EventSource) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Dimensions.screenPaddingHorizontal,
                end = Dimensions.screenPaddingHorizontal,
                top = Dimensions.spacingSm
            )
    ) {
        RethinkSearchField(
            query = query,
            onQueryChange = onQueryChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = stringResource(id = R.string.search_event_logs),
            onClearQuery = onClearQuery,
            clearQueryContentDescription = stringResource(id = R.string.cd_clear_search),
            shape = RoundedCornerShape(Dimensions.cornerRadiusLg),
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
        )

        Spacer(modifier = Modifier.height(Dimensions.spacingSm))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            EventFilterChip(
                label = stringResource(id = R.string.lbl_all),
                selected = filterType == TopLevelFilter.ALL,
                onClick = onAllClick
            )
            EventFilterChip(
                label = "Severity",
                selected = filterType == TopLevelFilter.SEVERITY,
                onClick = onSeverityModeClick
            )
            EventFilterChip(
                label = stringResource(id = R.string.events_filter_source),
                selected = filterType == TopLevelFilter.SOURCE,
                onClick = onSourceModeClick
            )
        }

        when (filterType) {
            TopLevelFilter.SEVERITY -> {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EventFilterChip(
                        label = stringResource(id = R.string.events_severity_low),
                        selected = filterSeverity == Severity.LOW,
                        onClick = { onSeverityClick(Severity.LOW) }
                    )
                    EventFilterChip(
                        label = stringResource(id = R.string.events_severity_medium),
                        selected = filterSeverity == Severity.MEDIUM,
                        onClick = { onSeverityClick(Severity.MEDIUM) }
                    )
                    EventFilterChip(
                        label = stringResource(id = R.string.events_severity_high),
                        selected = filterSeverity == Severity.HIGH,
                        onClick = { onSeverityClick(Severity.HIGH) }
                    )
                    EventFilterChip(
                        label = stringResource(id = R.string.events_severity_critical),
                        selected = filterSeverity == Severity.CRITICAL,
                        onClick = { onSeverityClick(Severity.CRITICAL) }
                    )
                }
            }

            TopLevelFilter.SOURCE -> {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    EventSource.entries.forEach { source ->
                        EventFilterChip(
                            label = source.name.lowercase().replace('_', ' ').replaceFirstChar { it.titlecase() },
                            selected = filterSources.contains(source),
                            onClick = { onSourceToggle(source) }
                        )
                    }
                }
            }

            TopLevelFilter.ALL -> Unit
        }
    }
}

@Composable
private fun EventFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    RethinkFilterChip(
        label = label,
        selected = selected,
        onClick = onClick,
        selectedLabelWeight = FontWeight.Medium,
        defaultLabelWeight = FontWeight.Medium
    )
}

@Composable
private fun EventsList(
    modifier: Modifier = Modifier,
    items: androidx.paging.compose.LazyPagingItems<Event>,
    query: String,
    onCopy: (String) -> Unit
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Dimensions.screenPaddingHorizontal,
            end = Dimensions.screenPaddingHorizontal,
            top = Dimensions.spacingSm,
            bottom = Dimensions.spacing3xl
        ),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        items(count = items.itemCount, key = { index -> items[index]?.id ?: index }) { index ->
            val item = items[index] ?: return@items
            val position = when {
                items.itemCount == 1 -> EventCardPosition.Single
                index == 0 -> EventCardPosition.First
                index == items.itemCount - 1 -> EventCardPosition.Last
                else -> EventCardPosition.Middle
            }
            EventCard(event = item, onCopy = onCopy, query = query, position = position)
        }
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(
            color = MaterialTheme.colorScheme.primary,
            strokeWidth = 3.dp
        )
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = RoundedCornerShape(Dimensions.cornerRadius4xl),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                Image(
                    painter = painterResource(id = R.drawable.ic_event_note),
                    contentDescription = null,
                    modifier = Modifier.size(52.dp)
                )
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(id = R.string.no_events_recorded),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(id = R.string.no_events_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

private const val QUERY_TEXT_DELAY: Long = 350
