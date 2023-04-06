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

import androidx.lifecycle.*
import androidx.paging.*
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.ui.ConnectionTrackerFragment
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE

class ConnectionTrackerViewModel(private val connectionTrackerDAO: ConnectionTrackerDAO) :
    ViewModel() {

    private var filterString: MutableLiveData<String> = MutableLiveData()
    private var filterRules: MutableSet<String> = mutableSetOf()
    private var filterType: ConnectionTrackerFragment.TopLevelFilter =
        ConnectionTrackerFragment.TopLevelFilter.ALL

    init {
        filterString.value = ""
    }

    val connectionTrackerList = filterString.switchMap { input -> fetchNetworkLogs(input) }

    fun setFilter(
        searchString: String,
        filter: Set<String>,
        type: ConnectionTrackerFragment.TopLevelFilter
    ) {
        filterRules.clear()

        filterRules.addAll(filter)
        filterType = type

        if (searchString.isNotBlank()) filterString.value = searchString
        else filterString.value = ""
    }

    private fun fetchNetworkLogs(input: String): LiveData<PagingData<ConnectionTracker>> {
        return when (filterType) {
            ConnectionTrackerFragment.TopLevelFilter.ALL -> {
                getAllNetworkLogs(input)
            }
            ConnectionTrackerFragment.TopLevelFilter.ALLOWED -> {
                getAllowedNetworkLogs(input)
            }
            ConnectionTrackerFragment.TopLevelFilter.BLOCKED -> {
                getBlockedNetworkLogs(input)
            }
        }
    }

    private fun getBlockedNetworkLogs(input: String): LiveData<PagingData<ConnectionTracker>> {
        return if (filterRules.isNotEmpty()) {
            Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                    if (input.isBlank())
                        connectionTrackerDAO.getBlockedConnectionsFiltered(filterRules)
                    else connectionTrackerDAO.getBlockedConnectionsFiltered("%$input%", filterRules)
                }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                    if (input.isBlank()) connectionTrackerDAO.getBlockedConnections()
                    else connectionTrackerDAO.getBlockedConnections("%$input%")
                }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    private fun getAllowedNetworkLogs(input: String): LiveData<PagingData<ConnectionTracker>> {
        return if (filterRules.isNotEmpty()) {
            Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                    if (input.isBlank())
                        connectionTrackerDAO.getAllowedConnectionsFiltered(filterRules)
                    else connectionTrackerDAO.getAllowedConnectionsFiltered("%$input%", filterRules)
                }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                    if (input.isBlank()) connectionTrackerDAO.getAllowedConnections()
                    else connectionTrackerDAO.getAllowedConnections("%$input%")
                }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    private fun getAllNetworkLogs(input: String): LiveData<PagingData<ConnectionTracker>> {
        return Pager(PagingConfig(LIVEDATA_PAGE_SIZE)) {
                if (input.isBlank()) connectionTrackerDAO.getConnectionTrackerByName()
                else connectionTrackerDAO.getConnectionTrackerByName("%$input%")
            }
            .liveData
            .cachedIn(viewModelScope)
    }
}
