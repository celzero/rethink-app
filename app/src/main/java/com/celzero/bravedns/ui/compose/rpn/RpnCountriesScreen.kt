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
package com.celzero.bravedns.ui.compose.rpn

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.CountryRow
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.ui.compose.theme.Dimensions
import com.celzero.bravedns.ui.compose.theme.RethinkConfirmDialog
import com.celzero.bravedns.ui.compose.theme.RethinkLargeTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RpnCountriesScreen(onBackClick: () -> Unit) {
    var countries by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedCountries by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showNoCountriesDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val result =
            withContext(Dispatchers.IO) {
                val servers = runCatching { RpnProxyManager.getWinServers() }.getOrDefault(emptyList())
                val selected = runCatching { RpnProxyManager.getSelectedCCs() }.getOrDefault(emptySet())
                val serverCountries =
                    servers
                        .map { it.countryCode.uppercase() }
                        .filter { it.isNotBlank() }
                        .distinct()
                        .sorted()
                Pair(serverCountries, selected.map { it.uppercase() }.toSet())
            }

        countries = result.first
        selectedCountries = result.second
        if (countries.isEmpty()) {
            showNoCountriesDialog = true
        }
    }

    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            RethinkLargeTopBar(
                title = stringResource(id = R.string.lbl_countries),
                onBackClick = onBackClick,
                scrollBehavior = scrollBehavior
            )
        }
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
        ) {
            if (showNoCountriesDialog) {
                RethinkConfirmDialog(
                    onDismissRequest = {},
                    title = stringResource(id = R.string.rpn_no_countries_title),
                    message = stringResource(id = R.string.rpn_no_countries_desc),
                    confirmText = stringResource(id = R.string.dns_info_positive),
                    onConfirm = onBackClick
                )
            }
            androidx.compose.material3.Surface(
                modifier = Modifier.padding(
                    horizontal = Dimensions.screenPaddingHorizontal,
                    vertical = Dimensions.spacingSm
                ),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(Dimensions.cardCornerRadiusLarge),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                tonalElevation = 1.dp
            ) {
                Column(modifier = Modifier.padding(Dimensions.spacingLg)) {
                    Text(
                        text = stringResource(id = R.string.lbl_countries),
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = stringResource(id = R.string.rpn_availability_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            CountriesList(
                countries = countries,
                selectedCountries = selectedCountries,
                modifier = Modifier.padding(horizontal = Dimensions.screenPaddingHorizontal)
            )
        }
    }
}

@Composable
private fun CountriesList(
    countries: List<String>,
    selectedCountries: Set<String>,
    modifier: Modifier = Modifier
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(countries.size) { index ->
            val country = countries[index]
            CountryRow(country, selectedCountries.contains(country))
        }
    }
}
