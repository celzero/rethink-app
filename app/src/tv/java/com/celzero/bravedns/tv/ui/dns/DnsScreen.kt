/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.dns

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvTabRow
import androidx.tv.material3.Text
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.database.DoHEndpointDAO
import com.celzero.bravedns.database.DoTEndpoint
import com.celzero.bravedns.database.DoTEndpointDAO
import com.celzero.bravedns.database.DnsCryptEndpoint
import com.celzero.bravedns.database.DnsCryptEndpointDAO
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.database.DnsProxyEndpointDAO
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.database.ODoHEndpointDAO
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.database.RethinkDnsEndpointRepository
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import com.celzero.bravedns.tv.ui.common.rememberAsImmutableState
import com.celzero.bravedns.tv.ui.theme.TvColors
import com.celzero.bravedns.tv.ui.theme.TvShapes
import com.celzero.bravedns.tv.ui.theme.TvSpacing
import com.celzero.bravedns.tv.ui.theme.TvSurfaceStyles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Generic, type-erased row we render in the DNS picker. Each
 * concrete endpoint type (RDNS / DoH / DoT / DNSCrypt / DNS Proxy /
 * ODoH) is converted to one of these so the row composable is
 * type-blind.
 */
private data class DnsRow(
    val key: String,
    val name: String,
    val url: String,
    val isSelected: Boolean,
    val isCustom: Boolean,
)

/**
 * DNS protocol tabs — RDNS first, then the encrypted protocols, in
 * the same order as the phone's six-option list. [addRoute] is null
 * for RDNS: its presets are seeded, no custom endpoints.
 */
private enum class DnsTab(
    val label: String,
    val addRoute: String?,
) {
    RDNS("RDNS", null),
    DOH("DoH", "dns/doh/add"),
    DOT("DoT", "dns/dot/add"),
    DNSCRYPT("DNSCrypt", "dns/dnscrypt/add"),
    DNS_PROXY("DNS Proxy", "dns/proxy/add"),
    ODOH("ODoH", "dns/odoh/add"),
}

/**
 * DNS destination — encrypted-DNS resolver picker.
 *
 * Layout:
 *
 *  1. **Current resolver banner**: a Surface at the top showing the
 *     resolver name + protocol pill. This is the same data Home
 *     surfaces; repeating it here makes the destination self-
 *     contained for the user who navigates straight in.
 *  2. **Protocol tabs**: RDNS / DoH / DoT / DNSCrypt / DNS Proxy /
 *     ODoH segmented row.
 *  3. **Endpoint list**: lazy column of the chosen tab's endpoints,
 *     fetched on a background dispatcher from the DAOs. Each row is
 *     a TV-material clickable surface; pressing center calls the
 *     matching `AppConfig.handle…Changes` setter, which is the same
 *     code path the phone screen invokes.
 *  4. **Add custom**: every tab but RDNS shows a pill button that
 *     routes to its save-and-activate add form.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DnsScreen(navController: NavController? = null) {
    val appConfig = koinInject<AppConfig>()
    val rethinkRepository = koinInject<RethinkDnsEndpointRepository>()
    val dohDao = koinInject<DoHEndpointDAO>()
    val dotDao = koinInject<DoTEndpointDAO>()
    val dnscryptDao = koinInject<DnsCryptEndpointDAO>()
    val dnsProxyDao = koinInject<DnsProxyEndpointDAO>()
    val odohDao = koinInject<ODoHEndpointDAO>()
    val scope = rememberCoroutineScope()

    // Tracks the user's currently-focused tab. Independent of the
    // *active* DNS type — the user can browse DoT while DoH is the
    // live resolver, for example.
    var activeTab by remember { mutableStateOf(DnsTab.RDNS) }

    // Reload trigger — bumped after each successful "set active"
    // write so the list refreshes its is-selected pill state.
    var reloadKey by remember { mutableStateOf(0) }

    val rows by produceState(initialValue = emptyList<DnsRow>(), activeTab, reloadKey) {
        value = withContext(Dispatchers.IO) {
            val fetched = when (activeTab) {
                DnsTab.RDNS -> rethinkRepository.getGlobalRethinkEndpoints()
                    .filterNot { it.name == RethinkDnsEndpoint.RETHINK_PLUS }
                    .map { it.toRow() }
                DnsTab.DOH -> dohDao.getAll().map { it.toRow() }
                DnsTab.DOT -> dotDao.getAll().map { it.toRow() }
                DnsTab.DNSCRYPT -> dnscryptDao.getAll().map { it.toRow() }
                DnsTab.DNS_PROXY -> dnsProxyDao.getAll().map { it.toRow() }
                DnsTab.ODOH -> odohDao.getAllAsList().map { it.toRow() }
            }
            // Active row first, seeded before custom, then by name.
            fetched.sortedWith(
                compareBy({ !it.isSelected }, { it.isCustom }, { it.name.lowercase() }),
            )
        }
    }

    // Subscribe to the connected DNS name (string) so the banner is
    // live without us needing to poll.
    val connectedDnsName by rememberAsImmutableState(
        liveData = appConfig.getConnectedDnsObservable(),
        initial = "—",
    ) { it.orEmpty().ifBlank { "—" } }

    // Snapshot the active type at composition; refreshed on reload.
    val activeType by produceState(initialValue = AppConfig.DnsType.DOH, reloadKey) {
        value = appConfig.getDnsType()
    }

    TvScreenScaffold(
        title = "DNS",
        subtitle = "Pick the resolver Rethink sends all DNS through.",
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
        ) {
            CurrentResolverBanner(name = connectedDnsName, type = activeType)
            Spacer(Modifier.height(TvSpacing.RowGap))
            DnsTabs(active = activeTab, onSelect = { activeTab = it })
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SettingSectionHeader(
                    text = "${activeTab.label} endpoints",
                    modifier = Modifier.weight(1f),
                )
                val addRoute = activeTab.addRoute
                if (addRoute != null) {
                    AddCustomButton(onClick = { navController?.navigate(addRoute) })
                }
            }

            if (rows.isEmpty()) {
                Text(
                    text = if (activeTab == DnsTab.RDNS) {
                        "No RDNS endpoints found."
                    } else {
                        "No ${activeTab.label} endpoints. Use + Add custom to add one."
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(TvSpacing.RowGap),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        DnsRow(
                            row = row,
                            onSelect = {
                                scope.launch(Dispatchers.IO) {
                                    activateRow(
                                        appConfig,
                                        activeTab,
                                        row.key,
                                        rethinkRepository,
                                        dohDao,
                                        dotDao,
                                        dnscryptDao,
                                        dnsProxyDao,
                                        odohDao,
                                    )
                                    // Bounce reload back to main thread so
                                    // produceState observes the change.
                                    withContext(Dispatchers.Main) { reloadKey++ }
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun CurrentResolverBanner(name: String, type: AppConfig.DnsType) {
    Surface(
        shape = TvShapes.Small,
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = TvSpacing.ContentHorizontal,
                vertical = TvSpacing.ContentVertical,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Currently connected",
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = name,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            TypePill(type)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TypePill(type: AppConfig.DnsType) {
    val label = when (type) {
        AppConfig.DnsType.DOH -> "DoH"
        AppConfig.DnsType.DOT -> "DoT"
        AppConfig.DnsType.ODOH -> "ODoH"
        AppConfig.DnsType.DNSCRYPT -> "DNSCrypt"
        AppConfig.DnsType.DNS_PROXY -> "DNS Proxy"
        AppConfig.DnsType.RETHINK_REMOTE -> "RDNS"
        AppConfig.DnsType.SYSTEM_DNS -> "System"
        AppConfig.DnsType.SMART_DNS -> "Smart"
    }
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f),
                TvShapes.Pill,
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DnsTabs(active: DnsTab, onSelect: (DnsTab) -> Unit) {
    TvTabRow(
        tabs = DnsTab.entries.toList(),
        selected = active,
        label = { it.label },
        onSelect = onSelect,
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DnsRow(row: DnsRow, onSelect: () -> Unit) {
    Surface(
        onClick = onSelect,
        shape = TvSurfaceStyles.interactiveShape(),
        colors = TvSurfaceStyles.rowColors(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(
                horizontal = TvSpacing.ContentHorizontal,
                vertical = 14.dp,
            ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (row.isSelected) {
                        Spacer(Modifier.width(TvSpacing.GroupGap))
                        ActivePill()
                    }
                    if (row.isCustom) {
                        Spacer(Modifier.width(TvSpacing.RowGap))
                        TagPill("custom")
                    }
                }
                if (row.url.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = row.url,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ActivePill() {
    Box(
        modifier = Modifier
            .background(TvColors.Success, TvShapes.Pill)
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(
            "Active",
            color = TvColors.OnSuccess,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TagPill(label: String) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.secondaryContainer, TvShapes.Pill)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// -- Conversions and writes ----------------------------------------

private fun RethinkDnsEndpoint.toRow() = DnsRow(
    key = "rdns/$name",
    name = name,
    url = url,
    isSelected = isActive,
    isCustom = isCustom,
)

private fun DoHEndpoint.toRow() = DnsRow(
    key = "doh/$id",
    name = dohName,
    url = dohURL,
    isSelected = isSelected,
    isCustom = isCustom,
)

private fun DoTEndpoint.toRow() = DnsRow(
    key = "dot/$id",
    name = name,
    url = url,
    isSelected = isSelected,
    isCustom = isCustom,
)

private fun DnsCryptEndpoint.toRow() = DnsRow(
    key = "dnscrypt/$id",
    name = dnsCryptName,
    url = dnsCryptURL,
    isSelected = isSelected,
    isCustom = isCustom,
)

private fun DnsProxyEndpoint.toRow() = DnsRow(
    key = "dnsproxy/$id",
    name = proxyName,
    url = "$proxyIP:$proxyPort",
    isSelected = isSelected,
    isCustom = isCustom,
)

private fun ODoHEndpoint.toRow() = DnsRow(
    key = "odoh/$id",
    name = name,
    url = "",
    isSelected = isSelected,
    isCustom = isCustom,
)

private suspend fun activateRow(
    appConfig: AppConfig,
    tab: DnsTab,
    key: String,
    rethinkRepository: RethinkDnsEndpointRepository,
    dohDao: DoHEndpointDAO,
    dotDao: DoTEndpointDAO,
    dnscryptDao: DnsCryptEndpointDAO,
    dnsProxyDao: DnsProxyEndpointDAO,
    odohDao: ODoHEndpointDAO,
) {
    when (tab) {
        DnsTab.RDNS -> {
            val name = key.substringAfter('/')
            val ep = rethinkRepository.getGlobalRethinkEndpoints()
                .firstOrNull { it.name == name } ?: return
            ep.isActive = true
            appConfig.handleRethinkChanges(ep)
        }
        DnsTab.DOH -> {
            val id = key.substringAfter('/').toIntOrNull()
            val ep = dohDao.getAll().firstOrNull { it.id == id } ?: return
            ep.isSelected = true
            appConfig.handleDoHChanges(ep)
        }
        DnsTab.DOT -> {
            val id = key.substringAfter('/').toIntOrNull()
            val ep = dotDao.getAll().firstOrNull { it.id == id } ?: return
            ep.isSelected = true
            appConfig.handleDoTChanges(ep)
        }
        DnsTab.DNSCRYPT -> {
            val id = key.substringAfter('/').toIntOrNull()
            val ep = dnscryptDao.getAll().firstOrNull { it.id == id } ?: return
            ep.isSelected = true
            appConfig.handleDnscryptChanges(ep)
        }
        DnsTab.DNS_PROXY -> {
            val id = key.substringAfter('/').toIntOrNull()
            val ep = dnsProxyDao.getAll().firstOrNull { it.id == id } ?: return
            ep.isSelected = true
            appConfig.handleDnsProxyChanges(ep)
        }
        DnsTab.ODOH -> {
            val id = key.substringAfter('/').toIntOrNull()
            val ep = odohDao.getAllAsList().firstOrNull { it.id == id } ?: return
            ep.isSelected = true
            appConfig.handleODoHChanges(ep)
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddCustomButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = TvSurfaceStyles.interactiveShape(shape = TvShapes.Pill),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.width(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                "Add custom",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}
