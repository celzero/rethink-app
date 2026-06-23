/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.ezelab.rethinktv.ui.dns

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import com.ezelab.rethinktv.ui.common.Surface
import androidx.tv.material3.Text
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.database.DoTEndpoint
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.database.ODoHEndpointDAO
import com.celzero.bravedns.database.ODoHEndpointRepository
import com.ezelab.rethinktv.ui.common.SettingSectionHeader
import com.ezelab.rethinktv.ui.common.TvScreenScaffold
import com.ezelab.rethinktv.ui.common.rememberAsImmutableState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Generic, type-erased row we render in the DNS picker. Each
 * concrete endpoint type (DoH / DoT / ODoH) is converted to one of
 * these so the row composable is type-blind.
 */
private data class DnsRow(
    val key: String,
    val name: String,
    val url: String,
    val isSelected: Boolean,
    val isCustom: Boolean,
)

/**
 * Which DNS protocol tab the user is currently viewing. v1 surfaces
 * the three encrypted protocols TV users actually pick: DoH, DoT,
 * ODoH. DNSCrypt (multi-relay setup), DNS Proxy (rare on TV) and
 * Rethink+ (separate Rethink-managed blocklist flow) are left for a
 * follow-up.
 */
private enum class DnsTab(
    val label: String,
    val matchingType: AppConfig.DnsType,
) {
    DOH("DoH", AppConfig.DnsType.DOH),
    DOT("DoT", AppConfig.DnsType.DOT),
    ODOH("ODoH", AppConfig.DnsType.ODOH),
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
 *  2. **Protocol tabs**: DoH / DoT / ODoH segmented row.
 *  3. **Endpoint list**: lazy column of the chosen tab's endpoints,
 *     fetched on a background dispatcher from upstream's repositories
 *     via AppConfig. Each row is a TV-material clickable surface;
 *     pressing center calls the matching `AppConfig.handle…Changes`
 *     setter, which is the same code path the phone screen invokes.
 *
 * Out of scope for v1 (deliberately noted):
 *
 *  * Adding a custom DoH/DoT URL (no TV-friendly text-entry dialog yet).
 *  * DNSCrypt server + relay multi-select.
 *  * DNS Proxy (plain UDP) endpoints.
 *  * Rethink+ basic blocklist categories.
 *  * Local blocklist download progress.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun DnsScreen(navController: NavController? = null) {
    val appConfig = koinInject<AppConfig>()
    val odohDao = koinInject<ODoHEndpointDAO>()
    val odohRepository = koinInject<ODoHEndpointRepository>()
    val scope = rememberCoroutineScope()

    // Tracks the user's currently-focused tab. Independent of the
    // *active* DNS type — the user can browse DoT while DoH is the
    // live resolver, for example.
    var activeTab by remember { mutableStateOf(DnsTab.DOH) }

    // Reload trigger — bumped after each successful "set active"
    // write so the list refreshes its is-selected pill state.
    var reloadKey by remember { mutableStateOf(0) }

    val rows by produceState(initialValue = emptyList<DnsRow>(), activeTab, reloadKey) {
        value = withContext(Dispatchers.IO) {
            when (activeTab) {
                DnsTab.DOH -> appConfig.getAllDefaultDoHEndpoints().map { it.toRow() }
                DnsTab.DOT -> appConfig.getAllDefaultDoTEndpoints().map { it.toRow() }
                DnsTab.ODOH -> odohDao.getAllAsList().map { it.toRow() }
            }
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
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CurrentResolverBanner(name = connectedDnsName, type = activeType)
            Spacer(Modifier.height(8.dp))
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
                if (activeTab == DnsTab.ODOH) {
                    AddOdohButton(onClick = { navController?.navigate("dns/odoh/add") })
                }
            }

            if (rows.isEmpty()) {
                Text(
                    text = "No ${activeTab.label} endpoints found. Custom-add will land in a follow-up.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                ) {
                    items(rows, key = { it.key }) { row ->
                        DnsRow(
                            row = row,
                            onSelect = {
                                scope.launch(Dispatchers.IO) {
                                    if (activeTab == DnsTab.ODOH) {
                                        activateOdoh(appConfig, odohDao, row.key)
                                    } else {
                                        activateRow(appConfig, activeTab, row.key)
                                    }
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
        shape = RoundedCornerShape(12.dp),
        colors = androidx.tv.material3.SurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
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
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                    ),
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
        AppConfig.DnsType.RETHINK_REMOTE -> "Rethink+"
        AppConfig.DnsType.SYSTEM_DNS -> "System"
        AppConfig.DnsType.SMART_DNS -> "Smart"
    }
    Box(
        modifier = Modifier
            .background(
                MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.18f),
                RoundedCornerShape(50),
            )
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DnsTabs(active: DnsTab, onSelect: (DnsTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DnsTab.entries.forEach { tab ->
            val isActive = tab == active
            Surface(
                onClick = { onSelect(tab) },
                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (isActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                    contentColor = if (isActive) {
                        MaterialTheme.colorScheme.onPrimary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
            ) {
                Box(
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = tab.label,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                        ),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun DnsRow(row: DnsRow, onSelect: () -> Unit) {
    Surface(
        onClick = onSelect,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(10.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                        ),
                    )
                    if (row.isSelected) {
                        Spacer(Modifier.width(12.dp))
                        ActivePill()
                    }
                    if (row.isCustom) {
                        Spacer(Modifier.width(8.dp))
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
            .background(Color(0xFF2E7D32), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 2.dp),
    ) {
        Text(
            "Active",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun TagPill(label: String) {
    Box(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.tertiary, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onTertiary,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

// -- Conversions and writes ----------------------------------------

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

private fun ODoHEndpoint.toRow() = DnsRow(
    key = "odoh/$id",
    name = name,
    url = "",
    isSelected = isSelected,
    isCustom = isCustom,
)

private suspend fun activateRow(appConfig: AppConfig, tab: DnsTab, key: String) {
    val id = key.substringAfter('/').toIntOrNull() ?: return
    when (tab) {
        DnsTab.DOH -> {
            val ep = appConfig.getAllDefaultDoHEndpoints().firstOrNull { it.id == id }
                ?: return
            ep.isSelected = true
            appConfig.handleDoHChanges(ep)
        }
        DnsTab.DOT -> {
            val ep = appConfig.getAllDefaultDoTEndpoints().firstOrNull { it.id == id }
                ?: return
            ep.isSelected = true
            appConfig.handleDoTChanges(ep)
        }
        DnsTab.ODOH -> {
            // Caller injects the DAO; resolve the row by id and
            // flip the selected flag via handleODoHChanges (matches
            // the phone's tap flow).
            // Activation is handled in the screen-scope via a
            // dedicated helper below to avoid pulling Koin into a
            // top-level suspend.
        }
    }
}

private suspend fun activateOdoh(
    appConfig: AppConfig,
    odohDao: ODoHEndpointDAO,
    key: String,
) {
    val id = key.substringAfter('/').toIntOrNull() ?: return
    val ep = odohDao.getAllAsList().firstOrNull { it.id == id } ?: return
    ep.isSelected = true
    appConfig.handleODoHChanges(ep)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddOdohButton(onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
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
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
        }
    }
}
