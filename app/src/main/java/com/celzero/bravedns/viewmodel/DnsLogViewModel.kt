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
import androidx.lifecycle.Transformations
import androidx.lifecycle.ViewModel
import androidx.paging.PagedList
import androidx.paging.toLiveData
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.ui.DnsLogFragment
import com.celzero.bravedns.util.Constants.Companion.DNS_LIVEDATA_PAGE_SIZE

class DnsLogViewModel(private val dnsLogDAO: DnsLogDAO) : ViewModel() {

    private var filteredList: MutableLiveData<String> = MutableLiveData()
    private var filterType = DnsLogFragment.DnsLogFilter.ALL

    init {
        filteredList.value = ""
    }

    val dnsLogsList = Transformations.switchMap(filteredList) { input ->
        fetchDnsLogs(input)
    }

    private fun fetchDnsLogs(filter: String): LiveData<PagedList<DnsLog>> {
        return when (filterType) {
            DnsLogFragment.DnsLogFilter.ALL -> {
                getAllDnsLogs(filter)
            }
            DnsLogFragment.DnsLogFilter.ALLOWED -> {
                getAllowedDnsLogs(filter)
            }
            DnsLogFragment.DnsLogFilter.BLOCKED -> {
                getBlockedDnsLogs(filter)
            }
        }
    }

    private fun getAllDnsLogs(filter: String): LiveData<PagedList<DnsLog>> {
        return dnsLogDAO.getDnsLogsByName("%$filter%").toLiveData(pageSize = DNS_LIVEDATA_PAGE_SIZE)
    }

    private fun getAllowedDnsLogs(filter: String): LiveData<PagedList<DnsLog>> {
        return dnsLogDAO.getAllowedDnsLogsByName("%$filter%").toLiveData(
            pageSize = DNS_LIVEDATA_PAGE_SIZE)
    }

    private fun getBlockedDnsLogs(filter: String): LiveData<PagedList<DnsLog>> {
        return dnsLogDAO.getBlockedDnsLogsByName("%$filter%").toLiveData(
            pageSize = DNS_LIVEDATA_PAGE_SIZE)
    }

    fun setFilter(searchString: String, type: DnsLogFragment.DnsLogFilter) {
        filterType = type

        if (searchString.isNotBlank()) filteredList.value = searchString
        else filteredList.value = ""
    }

}
