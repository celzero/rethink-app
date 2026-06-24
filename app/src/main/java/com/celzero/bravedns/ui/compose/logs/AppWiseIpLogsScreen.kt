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
package com.celzero.bravedns.ui.compose.logs

import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.asFlow
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.IpRow
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.ui.bottomsheet.AppIpRulesSheet
import com.celzero.bravedns.util.Constants.Companion.INVALID_UID
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.removeBeginningTrailingCommas
import com.celzero.bravedns.viewmodel.AppConnectionsViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun AppWiseIpLogsScreen(
    uid: Int,
    isAsn: Boolean,
    viewModel: AppConnectionsViewModel,
    eventLogger: EventLogger,
    onBackClick: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val twoArgumentColonTemplate = stringResource(R.string.two_argument_colon)
    val twoArgumentSpaceTemplate = stringResource(R.string.two_argument_space)
    val appOtherAppsTemplate = stringResource(R.string.ctbs_app_other_apps, "", "")
    val searchLabel = stringResource(R.string.lbl_search)
    val serviceProviderLabel = stringResource(R.string.lbl_service_providers)
    val universalIpLabel = stringResource(R.string.search_universal_ips)
    var appName by remember(uid) { mutableStateOf("") }
    var searchHint by remember { mutableStateOf("") }
    var appIcon by remember { mutableStateOf<Drawable?>(null) }
    val showDeleteIcon = remember { !isAsn }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var selectedCategory by remember { mutableStateOf(AppConnectionsViewModel.TimeCategory.SEVEN_DAYS) }
    var isRethinkApp by remember(uid) { mutableStateOf(false) }

    LaunchedEffect(uid, isAsn) {
        if (uid == INVALID_UID) {
            onBackClick?.invoke()
            return@LaunchedEffect
        }
        viewModel.timeCategoryChanged(selectedCategory, isDomain = false)
        withContext(Dispatchers.IO) {
            val meta = resolveAppWiseLogsHeader(
                context = context,
                uid = uid,
                isAsn = isAsn,
                appOtherAppsTemplate = appOtherAppsTemplate,
                twoArgumentColonTemplate = twoArgumentColonTemplate,
                twoArgumentSpaceTemplate = twoArgumentSpaceTemplate,
                searchLabel = searchLabel,
                serviceProvidersLabel = serviceProviderLabel,
                universalIpsLabel = universalIpLabel
            )
            if (meta == null) {
                withContext(Dispatchers.Main) { onBackClick?.invoke() }
                return@withContext
            }
            withContext(Dispatchers.Main) {
                appName = meta.appName
                searchHint = meta.searchHint
                appIcon = meta.appIcon
                isRethinkApp = meta.isRethinkApp
            }
        }
    }

    AppWiseLogsScaffold(
        title = appName,
        onBackClick = onBackClick
    ) { paddingValues ->
        if (showDeleteDialog) {
            AppWiseLogsDeleteDialog(
                onDismiss = { showDeleteDialog = false },
                onConfirm = { viewModel.deleteLogs(uid) }
            )
        }

        AppWiseLogsScreenContent(
            title = appName.ifBlank { stringResource(R.string.lbl_logs) },
            searchHint = searchHint,
            appIcon = appIcon ?: Utilities.getDefaultIcon(context),
            showToggleGroup = true,
            selectedCategory = selectedCategory,
            onCategorySelected = { category ->
                selectedCategory = category
                viewModel.timeCategoryChanged(category, isDomain = false)
            },
            defaultHintRes = R.string.search_universal_ips,
            showDeleteIcon = showDeleteIcon,
            onDeleteClick = { showDeleteDialog = true },
            onQueryChange = { query ->
                val type =
                    if (isAsn) {
                        AppConnectionsViewModel.FilterType.ASN
                    } else {
                        AppConnectionsViewModel.FilterType.IP
                    }
                viewModel.setFilter(query, type)
            },
            modifier = Modifier.fillMaxSize().padding(paddingValues),
            queryEnabled = !isAsn
        ) {
            AppWiseIpList(
                viewModel = viewModel,
                uid = uid,
                isAsn = isAsn,
                isRethinkApp = isRethinkApp,
                eventLogger = eventLogger
            )
        }
    }
}

@Composable
private fun AppWiseIpList(
    viewModel: AppConnectionsViewModel,
    uid: Int,
    isAsn: Boolean,
    isRethinkApp: Boolean,
    eventLogger: EventLogger
) {
    var showIpRulesSheet by remember { mutableStateOf(false) }
    var selectedIp by remember { mutableStateOf("") }
    var selectedDomains by remember { mutableStateOf("") }
    var refreshToken by remember { mutableStateOf(0) }

    if (showIpRulesSheet && selectedIp.isNotEmpty()) {
        AppIpRulesSheet(
            uid = uid,
            ipAddress = selectedIp,
            domains = selectedDomains,
            eventLogger = eventLogger,
            onDismiss = { showIpRulesSheet = false },
            onUpdated = { refreshToken++ }
        )
    }

    LaunchedEffect(uid, isRethinkApp) {
        if (!isRethinkApp) viewModel.setUid(uid)
    }

    val flow = remember(isRethinkApp, isAsn) {
        if (isRethinkApp) viewModel.rinrIpLogs else if (isAsn) viewModel.asnLogs else viewModel.appIpLogs
    }
    val items = flow.asFlow().collectAsLazyPagingItems()

    AppWiseLogsPagedList(items = items) { item ->
            IpRow(
                conn = item,
                isAsn = isAsn,
                refreshToken = refreshToken,
                onIpClick = { conn ->
                    if (!isAsn) {
                        selectedIp = conn.ipAddress
                        selectedDomains =
                            removeBeginningTrailingCommas(conn.appOrDnsName ?: "").replace(",,", ",").replace(",", ", ")
                        showIpRulesSheet = true
                    }
                }
            )
    }
}
