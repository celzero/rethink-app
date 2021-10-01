/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.viewmodel

import androidx.arch.core.util.Function
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.paging.PagedList
import androidx.paging.toLiveData
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.ui.ConnectionTrackerFragment
import com.celzero.bravedns.util.Constants.Companion.DNS_LIVEDATA_PAGE_SIZE


class ConnectionTrackerViewModel(private val connectionTrackerDAO: ConnectionTrackerDAO) :
        ViewModel() {

    private var filterString: MutableLiveData<String> = MutableLiveData()
    private var filterRules: MutableSet<String> = mutableSetOf()
    private var filterType: ConnectionTrackerFragment.TopLevelFilter = ConnectionTrackerFragment.TopLevelFilter.ALL

    init {
        filterString.value = ""
    }

    val connectionTrackerList = Transformations.switchMap(filterString,
                                                          (Function<String, LiveData<PagedList<ConnectionTracker>>> { input ->
                                                              fetchNetworkLogs(input)
                                                          }))

    fun setFilter(searchString: String?, filter: Set<String>,
                  type: ConnectionTrackerFragment.TopLevelFilter) {
        filterRules.clear()

        filterRules.addAll(filter)
        filterType = type

        if (!searchString.isNullOrBlank()) filterString.value = searchString
        else filterString.value = ""
    }

    private fun fetchNetworkLogs(input: String): LiveData<PagedList<ConnectionTracker>> {
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

    private fun getBlockedNetworkLogs(input: String): LiveData<PagedList<ConnectionTracker>> {
        return if (filterRules.count() > 0) {
            connectionTrackerDAO.getBlockedConnectionsFiltered("%$input%", filterRules).toLiveData(
                pageSize = DNS_LIVEDATA_PAGE_SIZE)
        } else {
            connectionTrackerDAO.getBlockedConnections("%$input%").toLiveData(
                pageSize = DNS_LIVEDATA_PAGE_SIZE)
        }
    }

    private fun getAllowedNetworkLogs(input: String): LiveData<PagedList<ConnectionTracker>> {
        return if (filterRules.count() > 0) {
            connectionTrackerDAO.getAllowedConnectionsFiltered("%$input%", filterRules).toLiveData(
                pageSize = DNS_LIVEDATA_PAGE_SIZE)
        } else {
            connectionTrackerDAO.getAllowedConnections("%$input%").toLiveData(
                pageSize = DNS_LIVEDATA_PAGE_SIZE)
        }
    }

    private fun getAllNetworkLogs(input: String): LiveData<PagedList<ConnectionTracker>> {
        return connectionTrackerDAO.getConnectionTrackerByName("%$input%").toLiveData(
            DNS_LIVEDATA_PAGE_SIZE)
    }
}
