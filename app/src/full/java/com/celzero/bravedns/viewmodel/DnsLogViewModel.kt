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
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.ui.fragment.DnsLogFragment
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE
import com.celzero.bravedns.util.ResourceRecordTypes.Companion.getHandledTypes

class DnsLogViewModel(private val dnsLogDAO: DnsLogDAO) : ViewModel() {

    private var filteredList: MutableLiveData<String> = MutableLiveData()
    private var filterType = DnsLogFragment.DnsLogFilter.ALL
    private val pagingConfig: PagingConfig

    init {
        filteredList.value = ""
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

    val dnsLogsList = filteredList.switchMap { input -> fetchDnsLogs(input) }

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
            DnsLogFragment.DnsLogFilter.MAYBE_BLOCKED -> {
                getMaybeBlockedDnsLogs(filter)
            }
            DnsLogFragment.DnsLogFilter.UNKNOWN_RECORDS -> {
                getUnknownRecordDnsLogs(filter)
            }
        }
    }

    private fun getAllDnsLogs(filter: String): LiveData<PagingData<DnsLog>> {
        return Pager(pagingConfig) {
                if (filter.isEmpty()) {
                    dnsLogDAO.getAllDnsLogs()
                } else {
                    dnsLogDAO.getDnsLogsByName("%$filter%")
                }
            }
            .liveData
            .cachedIn(viewModelScope)
    }

    private fun getAllowedDnsLogs(filter: String): LiveData<PagingData<DnsLog>> {
        return Pager(pagingConfig) {
                if (filter.isEmpty()) {
                    dnsLogDAO.getAllowedDnsLogs()
                } else {
                    dnsLogDAO.getAllowedDnsLogsByName("%$filter%")
                }
            }
            .liveData
            .cachedIn(viewModelScope)
    }

    private fun getBlockedDnsLogs(filter: String): LiveData<PagingData<DnsLog>> {
        return Pager(pagingConfig) {
                if (filter.isEmpty()) {
                    dnsLogDAO.getBlockedDnsLogs()
                } else {
                    dnsLogDAO.getBlockedDnsLogsByName("%$filter%")
                }
            }
            .liveData
            .cachedIn(viewModelScope)
    }

    private fun getMaybeBlockedDnsLogs(filter: String): LiveData<PagingData<DnsLog>> {
        return Pager(pagingConfig) {
                if (filter.isEmpty()) {
                    dnsLogDAO.getMaybeBlockedDnsLogs()
                } else {
                    dnsLogDAO.getMaybeBlockedDnsLogsByName("%$filter%")
                }
            }
            .liveData
            .cachedIn(viewModelScope)
    }

    private fun getUnknownRecordDnsLogs(filter: String): LiveData<PagingData<DnsLog>> {
        val handledTypes = getHandledTypes()
        return Pager(pagingConfig) {
                if (filter.isEmpty()) {
                    dnsLogDAO.getUnknownRecordDnsLogs(handledTypes)
                } else {
                    dnsLogDAO.getUnknownRecordDnsLogsByName("%$filter%", handledTypes)
                }
            }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun setFilter(searchString: String, type: DnsLogFragment.DnsLogFilter) {
        filterType = type

        if (searchString.isNotBlank()) filteredList.value = searchString
        else filteredList.value = ""
    }
}
