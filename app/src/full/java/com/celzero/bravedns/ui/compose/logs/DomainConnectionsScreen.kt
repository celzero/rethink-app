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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.asFlow
import androidx.paging.compose.collectAsLazyPagingItems
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.ConnectionRow
import com.celzero.bravedns.ui.compose.theme.RethinkTopBar
import com.celzero.bravedns.util.UIUtils.getCountryNameFromFlag
import com.celzero.bravedns.viewmodel.DomainConnectionsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DomainConnectionsScreen(
    viewModel: DomainConnectionsViewModel,
    type: DomainConnectionsInputType,
    flag: String,
    domain: String,
    asn: String,
    ip: String,
    isBlocked: Boolean,
    timeCategory: DomainConnectionsViewModel.TimeCategory,
    onBackClick: () -> Unit
) {
    val titleText =
        when (type) {
            DomainConnectionsInputType.DOMAIN -> domain
            DomainConnectionsInputType.FLAG ->
                stringResource(R.string.two_argument_space, flag, getCountryNameFromFlag(flag))
            DomainConnectionsInputType.ASN -> asn
            DomainConnectionsInputType.IP -> ip
        }
    val subtitleText = subtitleFor(timeCategory)

    LaunchedEffect(type, flag, domain, asn, ip, isBlocked) {
        when (type) {
            DomainConnectionsInputType.DOMAIN -> {
                viewModel.setDomain(domain, isBlocked)
            }
            DomainConnectionsInputType.FLAG -> {
                viewModel.setFlag(flag)
            }
            DomainConnectionsInputType.ASN -> {
                viewModel.setAsn(asn, isBlocked)
            }
            DomainConnectionsInputType.IP -> {
                viewModel.setIp(ip, isBlocked)
            }
        }
    }

    LaunchedEffect(timeCategory) {
        viewModel.timeCategoryChanged(timeCategory)
    }

    Scaffold(
        topBar = {
            RethinkTopBar(
                title = stringResource(id = R.string.app_name_small_case),
                onBackClick = onBackClick
            )
        }
    ) { paddingValues ->
        Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            Header(titleText, subtitleText)
            Box(modifier = Modifier.fillMaxSize()) {
                ConnectionsList(viewModel, type)
                if (shouldShowEmpty(viewModel, type)) {
                    EmptyState()
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
    ) {
        Text(
            text = stringResource(id = R.string.app_name_small_case),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.alpha(0.5f)
        )
        Spacer(modifier = Modifier.size(6.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun ConnectionsList(
    viewModel: DomainConnectionsViewModel,
    type: DomainConnectionsInputType
) {
    val liveData =
        when (type) {
            DomainConnectionsInputType.DOMAIN -> viewModel.domainConnectionList
            DomainConnectionsInputType.FLAG -> viewModel.flagConnectionList
            DomainConnectionsInputType.ASN -> viewModel.asnConnectionList
            DomainConnectionsInputType.IP -> viewModel.ipConnectionList
        }
    val items = liveData.asFlow().collectAsLazyPagingItems()

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(count = items.itemCount) { index ->
            val item = items[index] ?: return@items
            ConnectionRow(item)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(id = R.string.blocklist_update_check_failure),
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        Image(
            painter = painterResource(id = R.drawable.illustrations_no_record),
            contentDescription = null,
            modifier = Modifier.size(220.dp)
        )
    }
}

@Composable
private fun subtitleFor(timeCategory: DomainConnectionsViewModel.TimeCategory): String {
    return when (timeCategory) {
        DomainConnectionsViewModel.TimeCategory.ONE_HOUR -> {
            stringResource(
                id = R.string.three_argument,
                stringResource(id = R.string.lbl_last),
                stringResource(id = R.string.numeric_one),
                stringResource(id = R.string.lbl_hour)
            )
        }
        DomainConnectionsViewModel.TimeCategory.TWENTY_FOUR_HOUR -> {
            stringResource(
                id = R.string.three_argument,
                stringResource(id = R.string.lbl_last),
                stringResource(id = R.string.numeric_twenty_four),
                stringResource(id = R.string.lbl_hour)
            )
        }
        DomainConnectionsViewModel.TimeCategory.SEVEN_DAYS -> {
            stringResource(
                id = R.string.three_argument,
                stringResource(id = R.string.lbl_last),
                stringResource(id = R.string.numeric_seven),
                stringResource(id = R.string.lbl_day)
            )
        }
    }
}

@Composable
private fun shouldShowEmpty(
    viewModel: DomainConnectionsViewModel,
    type: DomainConnectionsInputType
): Boolean {
    val liveData =
        when (type) {
            DomainConnectionsInputType.DOMAIN -> viewModel.domainConnectionList
            DomainConnectionsInputType.FLAG -> viewModel.flagConnectionList
            DomainConnectionsInputType.ASN -> viewModel.asnConnectionList
            DomainConnectionsInputType.IP -> viewModel.ipConnectionList
        }
    val items = liveData.asFlow().collectAsLazyPagingItems()
    return items.itemCount == 0 && items.loadState.append.endOfPaginationReached
}

enum class DomainConnectionsInputType(val type: Int) {
    DOMAIN(0),
    FLAG(1),
    ASN(2),
    IP(3);

    companion object {
        fun fromValue(value: Int): DomainConnectionsInputType {
            return entries.firstOrNull { it.type == value } ?: DOMAIN
        }
    }
}
