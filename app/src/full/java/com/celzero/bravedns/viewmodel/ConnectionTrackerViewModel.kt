/*
 * Copyright 2020 RethinkDNS and its authors
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
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.liveData
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.ui.fragment.ConnectionTrackerFragment
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE

class ConnectionTrackerViewModel(private val connectionTrackerDAO: ConnectionTrackerDAO) :
    ViewModel() {

    private var filterString: MutableLiveData<String> = MutableLiveData()
    private var filterRules: MutableSet<String> = mutableSetOf()
    private var filterType: TopLevelFilter = TopLevelFilter.ALL

    enum class TopLevelFilter(val id: Int) {
        ALL(0),
        ALLOWED(1),
        BLOCKED(2)
    }

    private val pagingConfig: PagingConfig

    init {
        filterString.value = ""
        pagingConfig =
            PagingConfig(
                enablePlaceholders = true,
                prefetchDistance = 3,
                initialLoadSize = LIVEDATA_PAGE_SIZE * 2,
                maxSize = LIVEDATA_PAGE_SIZE * 3,
                pageSize = LIVEDATA_PAGE_SIZE * 2,
                jumpThreshold = 5
            )
    }

    val connectionTrackerList = filterString.switchMap { input -> fetchNetworkLogs(input) }

    fun setFilter(searchString: String, filter: Set<String>, type: TopLevelFilter) {
        filterRules.clear()

        filterRules.addAll(filter)
        filterType = type

        if (searchString.isNotBlank()) filterString.value = searchString
        else filterString.value = ""
    }

    private fun fetchNetworkLogs(input: String): LiveData<PagingData<ConnectionTracker>> {
        // spl case: treat input with P:UDP, P:TCP, P:ICMP as protocol filter
        val protocolPrefix = ConnectionTrackerFragment.PROTOCOL_FILTER_PREFIX.lowercase()
        val s = input.trim().lowercase()
        if (s.startsWith(protocolPrefix)) {
            val protocol = s.substringAfter(protocolPrefix)
            return if (filterRules.isNotEmpty()) {
                Pager(pagingConfig) {
                        connectionTrackerDAO.getProtocolFilteredConnections(protocol, filterRules)
                    }
                    .liveData
                    .cachedIn(viewModelScope)
            } else {
                Pager(pagingConfig) {
                        connectionTrackerDAO.getProtocolFilteredConnections(protocol)
                    }
                    .liveData
                    .cachedIn(viewModelScope)
            }
        }

        return when (filterType) {
            TopLevelFilter.ALL -> {
                getAllNetworkLogs(input)
            }
            TopLevelFilter.ALLOWED -> {
                getAllowedNetworkLogs(input)
            }
            TopLevelFilter.BLOCKED -> {
                getBlockedNetworkLogs(input)
            }
        }
    }

    private fun getBlockedNetworkLogs(input: String): LiveData<PagingData<ConnectionTracker>> {
        return if (filterRules.isNotEmpty()) {
            Pager(pagingConfig) {
                    if (input.isBlank())
                        connectionTrackerDAO.getBlockedConnectionsFiltered(filterRules)
                    else connectionTrackerDAO.getBlockedConnectionsFiltered("%$input%", filterRules)
                }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(pagingConfig) {
                    if (input.isBlank()) connectionTrackerDAO.getBlockedConnections()
                    else connectionTrackerDAO.getBlockedConnections("%$input%")
                }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    private fun getAllowedNetworkLogs(input: String): LiveData<PagingData<ConnectionTracker>> {
        return if (filterRules.isNotEmpty()) {
            Pager(pagingConfig) {
                    if (input.isBlank())
                        connectionTrackerDAO.getAllowedConnectionsFiltered(filterRules)
                    else connectionTrackerDAO.getAllowedConnectionsFiltered("%$input%", filterRules)
                }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(pagingConfig) {
                    if (input.isBlank()) connectionTrackerDAO.getAllowedConnections()
                    else connectionTrackerDAO.getAllowedConnections("%$input%")
                }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    private fun getAllNetworkLogs(input: String): LiveData<PagingData<ConnectionTracker>> {
        return Pager(pagingConfig) {
                if (input.isBlank()) connectionTrackerDAO.getConnectionTrackerByName()
                else connectionTrackerDAO.getConnectionTrackerByName("%$input%")
            }
            .liveData
            .cachedIn(viewModelScope)
    }
}
