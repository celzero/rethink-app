/*
 * Copyright 2024 RethinkDNS and its authors
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

import android.util.Log
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
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.util.Constants

class AppConnectionsViewModel(private val nwlogDao: ConnectionTrackerDAO) : ViewModel() {
    private var ipFilter: MutableLiveData<String> = MutableLiveData()
    private var domainFilter: MutableLiveData<String> = MutableLiveData()
    private var uid: Int = Constants.INVALID_UID
    private val pagingConfig: PagingConfig

    init {
        ipFilter.value = ""
        domainFilter.value = ""

        pagingConfig =
            PagingConfig(
                enablePlaceholders = true,
                prefetchDistance = 3,
                initialLoadSize = Constants.LIVEDATA_PAGE_SIZE * 2,
                maxSize = Constants.LIVEDATA_PAGE_SIZE * 3,
                pageSize = Constants.LIVEDATA_PAGE_SIZE * 2,
                jumpThreshold = 5
            )
    }

    enum class FilterType {
        IP,
        DOMAIN
    }

    val appIpLogs = ipFilter.switchMap { input -> fetchIpLogs(uid, input) }
    val appDomainLogs = domainFilter.switchMap { input -> fetchAppDomainLogs(uid, input) }

    private fun fetchIpLogs(uid: Int, input: String): LiveData<PagingData<AppConnection>> {
        return if (input.isEmpty()) {
                Pager(pagingConfig) { nwlogDao.getAppIpLogs(uid) }
            } else {
                Pager(pagingConfig) { nwlogDao.getAppIpLogsFiltered(uid, "%$input%") }
            }
            .liveData
            .cachedIn(viewModelScope)
    }

    private fun fetchAppDomainLogs(uid: Int, input: String): LiveData<PagingData<AppConnection>> {
        return if (input.isEmpty()) {
                Pager(pagingConfig) { nwlogDao.getAppDomainLogs(uid) }
            } else {
                Pager(pagingConfig) { nwlogDao.getAppDomainLogsFiltered(uid, "%$input%") }
            }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getConnectionsCount(uid: Int): LiveData<Int> {
        return nwlogDao.getAppConnectionsCount(uid)
    }

    fun getAppDomainConnectionsCount(uid: Int): LiveData<Int> {
        return nwlogDao.getAppDomainConnectionsCount(uid)
    }

    fun setFilter(input: String, filterType: FilterType) {
        if (filterType == FilterType.IP) {
            this.ipFilter.postValue(input)
        } else {
            this.domainFilter.postValue(input)
        }
    }

    fun setUid(uid: Int) {
        this.uid = uid
    }
}
