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

import android.util.Log
import androidx.arch.core.util.Function
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.paging.PagedList
import androidx.paging.toLiveData
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.util.Constants.Companion.DNS_LIVEDATA_PAGE_SIZE


class ConnectionTrackerViewModel(private val connectionTrackerDAO: ConnectionTrackerDAO) :
        ViewModel() {

    private var filterString: MutableLiveData<String> = MutableLiveData()
    private var filterRules: MutableList<String> = ArrayList()
    private var filterType: FilterType = FilterType.ALL

    enum class FilterType {
        ALL, ALLOWED, BLOCKED
    }

    init {
        filterString.value = ""
    }

    var connectionTrackerList = Transformations.switchMap(filterString,
                                                          (Function<String, LiveData<PagedList<ConnectionTracker>>> { input ->
                                                              fetchNetworkLogs(input)
                                                          }))

    fun setFilter(searchString: String?, filter: List<String>, type: FilterType) {
        filterRules.clear()

        filterRules.addAll(filter)
        filterType = type

        if (!searchString.isNullOrBlank()) filterString.value = searchString
        else filterString.value = ""
    }

    private fun fetchNetworkLogs(input: String): LiveData<PagedList<ConnectionTracker>> {
        return when (filterType) {
            FilterType.ALL -> {
                getAllNetworkLogs(input)
            }
            FilterType.ALLOWED -> {
                getAllowedNetworkLogs(input)
            }
            FilterType.BLOCKED -> {
                getBlockedNetworkLogs(input)
            }
        }
    }

    private fun getBlockedNetworkLogs(input: String): LiveData<PagedList<ConnectionTracker>> {
        return if (filterRules.size > 0) {
            connectionTrackerDAO.getBlockedConnectionsFiltered("%$input%", filterRules).toLiveData(
                pageSize = DNS_LIVEDATA_PAGE_SIZE)
        } else {
            connectionTrackerDAO.getBlockedConnections("%$input%").toLiveData(
                pageSize = DNS_LIVEDATA_PAGE_SIZE)
        }
    }

    private fun getAllowedNetworkLogs(input: String): LiveData<PagedList<ConnectionTracker>> {
        return if (filterRules.size > 0) {
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