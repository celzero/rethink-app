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
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.liveData
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.ui.DnsLogFragment
import com.celzero.bravedns.util.Constants

class DnsLogViewModel(private val dnsLogDAO: DnsLogDAO) : ViewModel() {

    private var filteredList: MutableLiveData<String> = MutableLiveData()
    private var filterType = DnsLogFragment.DnsLogFilter.ALL

    init {
        filteredList.value = ""
    }

    val dnsLogsList = Transformations.switchMap(filteredList) { input ->
        fetchDnsLogs(input)
    }

    private fun fetchDnsLogs(filter: String): LiveData<PagingData<DnsLog>> {
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

    private fun getAllDnsLogs(filter: String): LiveData<PagingData<DnsLog>> {
        return Pager(PagingConfig(Constants.DNS_LIVEDATA_PAGE_SIZE)) {
            dnsLogDAO.getDnsLogsByName("%$filter%")
        }.liveData
    }

    private fun getAllowedDnsLogs(filter: String): LiveData<PagingData<DnsLog>> {
        return Pager(PagingConfig(Constants.DNS_LIVEDATA_PAGE_SIZE)) {
            dnsLogDAO.getAllowedDnsLogsByName("%$filter%")
        }.liveData

    }

    private fun getBlockedDnsLogs(filter: String): LiveData<PagingData<DnsLog>> {
        return Pager(PagingConfig(Constants.DNS_LIVEDATA_PAGE_SIZE)) {
            dnsLogDAO.getBlockedDnsLogsByName("%$filter%")
        }.liveData

    }

    fun setFilter(searchString: String, type: DnsLogFragment.DnsLogFilter) {
        filterType = type

        if (searchString.isNotBlank()) filteredList.value = searchString
        else filteredList.value = ""
    }

}
