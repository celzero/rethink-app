/*
 * Copyright 2026 ezelab
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 */
package com.celzero.bravedns.tv.ui.rpn

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.database.CountryConfigRepository
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.tv.ui.common.SettingSectionHeader
import com.celzero.bravedns.tv.ui.common.Surface
import com.celzero.bravedns.tv.ui.common.TvScreenScaffold
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Add-location picker — TV counterpart of the phone's add-location
 * flow (country list with search, favourites, and frequent-country
 * chips; load-tier filters are simplified to a sorted list because
 * the ten-foot list already shows load per row).
 *
 * Selection enforces the phone's limit of [RpnProxyManager]
 * non-AUTO locations and fires the same `enableWinServer` write;
 * the pending "Connecting…" hand-off is shown as a toast here and
 * rendered by the home screen's tunnel watch.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun RpnAddLocationScreen(navController: NavController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val countryRepo: CountryConfigRepository = koinInject()

    var reloadKey by remember { mutableStateOf(0) }
    var query by remember { mutableStateOf("") }
    var favouritesOnly by remember { mutableStateOf(false) }

    val servers by produceState<List<CountryConfig>>(emptyList(), reloadKey) {
        value = withContext(Dispatchers.IO) {
            runCatching { RpnProxyManager.getWinServers() }.getOrDefault(emptyList())
        }
    }
    val enabledKeys by produceState<Set<String>>(emptySet(), reloadKey) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                RpnProxyManager.getEnabledConfigs().map { it.key }.toSet()
            }.getOrDefault(emptySet())
        }
    }
    val frequent by produceState<List<String>>(emptyList(), reloadKey) {
        value = withContext(Dispatchers.IO) {
            runCatching { countryRepo.getTopFrequentCcs(5) }.getOrDefault(emptyList())
        }
    }

    // Distinct countries with their best (lowest-load) server row.
    val countries = remember(servers, enabledKeys, favouritesOnly, query) {
        servers
            .filter { it.cc.isNotBlank() && it.cc != RpnProxyManager.AUTO_COUNTRY_CODE }
            .groupBy { it.cc }
            .map { (_, rows) -> rows.minBy { it.load } }
            .filter { config ->
                val matchesQuery = query.isBlank() ||
                    config.countryName.contains(query, ignoreCase = true) ||
                    config.serverLocation.contains(query, ignoreCase = true) ||
                    config.cc.equals(query.trim(), ignoreCase = true)
                val matchesFav = !favouritesOnly || config.isFavourite
                matchesQuery && matchesFav
            }
            .sortedWith(compareByDescending<CountryConfig> { it.isFavourite }.thenBy { it.countryName })
    }

    fun select(config: CountryConfig) {
        if (config.key in enabledKeys) {
            Toast.makeText(context, "${config.serverLocation} is already active", Toast.LENGTH_SHORT).show()
            return
        }
        val selectedCount = enabledKeys.size
        if (selectedCount >= MAX_SELECTIONS) {
            Toast.makeText(
                context,
                "Up to $MAX_SELECTIONS locations can be active — remove one first",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        scope.launch(Dispatchers.IO) {
            val result = runCatching { RpnProxyManager.enableWinServer(config.key) }
                .getOrDefault(false to "failed")
            withContext(Dispatchers.Main) {
                Toast.makeText(
                    context,
                    if (result.first) {
                        "Connecting to ${config.serverLocation}…"
                    } else {
                        "Could not add location: ${result.second}"
                    },
                    Toast.LENGTH_LONG,
                ).show()
                reloadKey++
                if (result.first) navController.popBackStack()
            }
        }
    }

    fun toggleFavourite(config: CountryConfig) {
        scope.launch(Dispatchers.IO) {
            runCatching { countryRepo.updateFavourite(config.cc, !config.isFavourite) }
            withContext(Dispatchers.Main) { reloadKey++ }
        }
    }

    TvScreenScaffold(
        title = "Add location",
        subtitle = "Pin up to $MAX_SELECTIONS exit locations; AUTO stays on regardless.",
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search country or city") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Chip(
                    label = if (favouritesOnly) "★ Favourites" else "☆ Favourites",
                    selected = favouritesOnly,
                    onClick = { favouritesOnly = !favouritesOnly },
                )
            }

            if (frequent.isNotEmpty()) {
                SettingSectionHeader("Frequent")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    frequent.forEach { cc ->
                        Chip(
                            label = cc,
                            selected = false,
                            onClick = {
                                countries.firstOrNull { it.cc == cc }?.let { select(it) }
                            },
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            SettingSectionHeader("Countries (${countries.size})")
            if (servers.isEmpty()) {
                Text(
                    text = "No servers yet — start Rethink and refresh to fetch the list.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(countries, key = { it.id }) { config ->
                    val isEnabled = config.key in enabledKeys
                    Surface(
                        onClick = { select(config) },
                        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(12.dp)),
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
                            Text(
                                text = config.flagEmoji,
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "${config.countryName} · ${config.serverLocation}",
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = if (isEnabled) {
                                        "Active — press to manage from the home screen"
                                    } else {
                                        "Load ${config.load}%"
                                    },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            Surface(
                                onClick = { toggleFavourite(config) },
                                shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
                                colors = ClickableSurfaceDefaults.colors(
                                    containerColor = MaterialTheme.colorScheme.surface,
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            ) {
                                Text(
                                    text = if (config.isFavourite) "★" else "☆",
                                    style = MaterialTheme.typography.titleLarge,
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            focusedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            pressedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            pressedContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge.copy(
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                ),
            )
        }
    }
}
