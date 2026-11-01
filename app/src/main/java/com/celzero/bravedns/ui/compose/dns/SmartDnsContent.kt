/* Copyright 2026 RethinkDNS and its authors. Licensed under Apache-2.0. */
package com.celzero.bravedns.ui.compose.dns

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.database.SmartDnsEndpoint

@Composable internal fun SmartDnsContent(items: List<SmartDnsEndpoint>?, active: Boolean, onSelect: (SmartDnsEndpoint) -> Unit, onInfo: (SmartDnsEndpoint) -> Unit) {
    Box(Modifier.fillMaxSize().navigationBarsPadding()) {
        if (items?.isEmpty() == true) Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(painterResource(R.drawable.illustrations_no_record), null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            Text(stringResource(R.string.doh_empty_state_title), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium)
        }
        val selectedLabel = stringResource(R.string.rt_filter_parent_selected)
        val select by rememberUpdatedState(onSelect)
        val info by rememberUpdatedState(onInfo)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 80.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(items.orEmpty(), key = { it.id }) { endpoint ->
                val selected = endpoint.isSelected && active
                val row = remember(endpoint, selected, selectedLabel) {
                    DnsEndpointRowState(endpoint.id, endpoint.dnsName, selected, false, "",
                        description = null, flag = { null },
                        onSelect = { select(endpoint) }, onInfo = { info(endpoint) },
                        initialDescription = if (selected) selectedLabel else null)
                }
                DnsEndpointRow(row)
            }
        }
    }
}
