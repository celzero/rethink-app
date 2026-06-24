/*
 * Copyright 2023 RethinkDNS and its authors
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
package com.celzero.bravedns.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celzero.bravedns.database.ProxyApplicationMappingDAO
import com.celzero.bravedns.database.ProxyApplicationMapping
import com.celzero.bravedns.ui.dialog.TopLevelFilter
import java.util.Locale
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn

@OptIn(FlowPreview::class)
class ProxyAppsMappingViewModel(private val mappingDAO: ProxyApplicationMappingDAO) : ViewModel() {

    private data class ProxyAppsFilterState(
        val searchQuery: String,
        val filterType: TopLevelFilter,
        val proxyId: String
    )

    private val filterState =
        MutableStateFlow(
            ProxyAppsFilterState(
                searchQuery = "",
                filterType = TopLevelFilter.ALL_APPS,
                proxyId = ""
            )
        )

    val apps =
        combine(
            mappingDAO.getWgAppMappingFlow(),
            filterState
                .debounce(200)
                .distinctUntilChanged()
        ) { apps, state ->
            filterAndSortApps(apps, state)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList()
        )

    // Unfiltered list for operations that must apply globally (for example, "Select all").
    val allApps: StateFlow<List<ProxyApplicationMapping>> =
        mappingDAO
            .getWgAppMappingFlow()
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                emptyList()
            )

    fun setFilter(filter: String, type: TopLevelFilter, pid: String) {
        filterState.value =
            ProxyAppsFilterState(
                searchQuery = filter.trim(),
                filterType = type,
                proxyId = pid
            )
    }

    fun getAppCountById(configId: String): LiveData<Int> {
        return mappingDAO.getAppCountByIdLiveData(configId)
    }

    private fun filterAndSortApps(
        apps: List<ProxyApplicationMapping>,
        state: ProxyAppsFilterState
    ): List<ProxyApplicationMapping> {
        val query = state.searchQuery.lowercase(Locale.getDefault())
        val hasQuery = query.isNotBlank()

        return apps
            .asSequence()
            .filter { app ->
                when (state.filterType) {
                    TopLevelFilter.ALL_APPS -> true
                    TopLevelFilter.SELECTED_APPS -> app.proxyId == state.proxyId
                    TopLevelFilter.UNSELECTED_APPS -> app.proxyId != state.proxyId
                }
            }
            .filter { app ->
                if (!hasQuery) return@filter true
                app.appName.contains(query, ignoreCase = true)
            }
            .sortedWith(
                compareBy<ProxyApplicationMapping>(
                    { it.appName.ifBlank { it.packageName }.lowercase(Locale.getDefault()) },
                    { it.packageName.lowercase(Locale.getDefault()) },
                    { it.uid }
                )
            )
            .toList()
    }
}
