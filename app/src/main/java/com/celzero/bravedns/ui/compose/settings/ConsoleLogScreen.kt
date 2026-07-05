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
package com.celzero.bravedns.ui.compose.settings

import Logger
import Logger.LOG_TAG_BUG_REPORT
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ConsoleLogRow
import com.celzero.bravedns.database.ConsoleLogRepository
import com.celzero.bravedns.net.go.GoVpnAdapter
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.ConsoleLogViewModel
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FilterList
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import kotlinx.coroutines.launch

private const val QUERY_TEXT_DELAY: Long = 1000

@OptIn(ExperimentalMaterial3Api::class, FlowPreview::class)
@Composable
fun ConsoleLogScreen(
    viewModel: ConsoleLogViewModel,
    consoleLogRepository: ConsoleLogRepository,
    persistentState: PersistentState,
    onShareClick: () -> Unit,
    onDeleteComplete: () -> Unit,
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val consoleLogDesc = stringResource(R.string.console_log_desc)
    val logsCardDuration = stringResource(R.string.logs_card_duration)
    val twoArgumentSpace = stringResource(R.string.two_argument_space)

    var query by remember { mutableStateOf("") }
    var infoText by remember { mutableStateOf("") }
    var progressVisible by remember { mutableStateOf(false) }
    var showFilterDialog by remember { mutableStateOf(false) }
    var selectedLogLevel by remember { mutableIntStateOf(Logger.uiLogLevel.toInt()) }

    val filterOptions = listOf(
        stringResource(R.string.settings_gologger_dialog_option_0),
        stringResource(R.string.settings_gologger_dialog_option_1),
        stringResource(R.string.settings_gologger_dialog_option_2),
        stringResource(R.string.settings_gologger_dialog_option_3),
        stringResource(R.string.settings_gologger_dialog_option_4),
        stringResource(R.string.settings_gologger_dialog_option_5),
        stringResource(R.string.settings_gologger_dialog_option_6),
        stringResource(R.string.settings_gologger_dialog_option_7)
    )

    // Initialize info text
    LaunchedEffect(Unit) {
        scope.launch(Dispatchers.IO) {
            val sinceTime = viewModel.sinceTime()
            if (sinceTime != 0L) {
                val since = Utilities.convertLongToTime(sinceTime, Constants.TIME_FORMAT_3)
                val sinceTxt = String.format(logsCardDuration, since)
                infoText = String.format(twoArgumentSpace, consoleLogDesc, sinceTxt)
            }
        }
    }

    // Set up log level and query filtering
    LaunchedEffect(Unit) {
        viewModel.setLogLevel(Logger.uiLogLevel)
        snapshotFlow { query }
            .debounce(QUERY_TEXT_DELAY)
            .distinctUntilChanged()
            .collect { value ->
                viewModel.setFilter(value)
            }
    }

    if (showFilterDialog) {
        RethinkConfirmDialog(
            onDismissRequest = { showFilterDialog = false },
            title = stringResource(R.string.console_log_title),
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filterOptions.forEachIndexed { index, label ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedLogLevel == index,
                                onClick = {
                                    selectedLogLevel = index
                                    Logger.uiLogLevel = index.toLong()
                                    GoVpnAdapter.setLogLevel(
                                        persistentState.goLoggerLevel.toInt(),
                                        Logger.uiLogLevel.toInt()
                                    )
                                    viewModel.setLogLevel(index.toLong())
                                    if (index < Logger.LoggerLevel.ERROR.id) {
                                        consoleLogRepository.setStartTimestamp(System.currentTimeMillis())
                                    }
                                    Logger.i(LOG_TAG_BUG_REPORT, "Log level set to $label")
                                }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(text = label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmText = stringResource(R.string.fapps_info_dialog_positive_btn),
            dismissText = stringResource(R.string.lbl_cancel),
            onConfirm = { showFilterDialog = false },
            onDismiss = { showFilterDialog = false }
        )
    }

    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()

    Scaffold(
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(R.string.console_log_title),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                text = { Text(text = stringResource(R.string.about_bug_report_desc)) },
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null
                    )
                },
                onClick = onShareClick
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SearchRow(
                query = query,
                onQueryChange = { query = it },
                onFilterClick = {
                    selectedLogLevel = Logger.uiLogLevel.toInt()
                    showFilterDialog = true
                },
                onShareClick = onShareClick,
                onDeleteClick = {
                    scope.launch(Dispatchers.IO) {
                        Logger.i(LOG_TAG_BUG_REPORT, "deleting all console logs")
                        consoleLogRepository.deleteAllLogs()
                        onDeleteComplete()
                    }
                }
            )

            if (progressVisible) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Dimensions.screenPaddingHorizontal, vertical = 4.dp)
                )
            }

            Box(modifier = Modifier.weight(1f)) {
                ConsoleLogList(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun SearchRow(
    query: String,
    onQueryChange: (String) -> Unit,
    onFilterClick: () -> Unit,
    onShareClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    androidx.compose.material3.Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Dimensions.screenPaddingHorizontal, vertical = Dimensions.spacingMd),
        shape = RoundedCornerShape(Dimensions.cardCornerRadiusLarge),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = Dimensions.spacingSm, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = Dimensions.spacingSm)
                    .size(Dimensions.iconSizeMd)
            )

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = {
                    Text(
                        text = stringResource(R.string.lbl_search),
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedBorderColor = androidx.compose.ui.graphics.Color.Transparent,
                    focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                    unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent
                )
            )

            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                IconButton(onClick = onFilterClick) {
                    Icon(
                        imageVector = Icons.Rounded.FilterList,
                        contentDescription = stringResource(R.string.cd_filter),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onShareClick) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onDeleteClick) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = stringResource(R.string.lbl_delete),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ConsoleLogList(viewModel: ConsoleLogViewModel) {
    val items = viewModel.logs.asFlow().collectAsLazyPagingItems()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        items(count = items.itemCount) { index ->
            val item = items[index] ?: return@items
            ConsoleLogRow(item)
        }
    }
}
