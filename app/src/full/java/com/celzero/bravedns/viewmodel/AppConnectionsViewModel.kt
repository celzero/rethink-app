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
    private var timeCategory: TimeCategory = TimeCategory.ONE_HOUR
    private var startTime: MutableLiveData<Long> = MutableLiveData()

    companion object {
        private const val ONE_HOUR_MILLIS = 1 * 60 * 60 * 1000L
        private const val ONE_DAY_MILLIS = 24 * ONE_HOUR_MILLIS
        private const val ONE_WEEK_MILLIS = 7 * ONE_DAY_MILLIS
    }

    enum class TimeCategory(val value: Int) {
        ONE_HOUR(0),
        TWENTY_FOUR_HOUR(1),
        SEVEN_DAYS(2);

        companion object {
            fun fromValue(value: Int) = entries.firstOrNull { it.value == value }
        }
    }

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

    fun timeCategoryChanged(tc: TimeCategory, isDomain: Boolean) {
        timeCategory = tc
        when (tc) {
            TimeCategory.ONE_HOUR -> {
                startTime.value =
                    System.currentTimeMillis() - ONE_HOUR_MILLIS
            }
            TimeCategory.TWENTY_FOUR_HOUR -> {
                startTime.value =
                    System.currentTimeMillis() - ONE_DAY_MILLIS
            }
            TimeCategory.SEVEN_DAYS -> {
                startTime.value =
                    System.currentTimeMillis() - ONE_WEEK_MILLIS
            }
        }
        if (isDomain) {
            domainFilter.value = ""
        } else {
            ipFilter.value = ""
        }
    }

    enum class FilterType {
        IP,
        DOMAIN
    }

    val appIpLogs = ipFilter.switchMap { input -> fetchIpLogs(uid, input) }
    val appDomainLogs = domainFilter.switchMap { input -> fetchAppDomainLogs(uid, input) }

    private fun fetchIpLogs(uid: Int, input: String): LiveData<PagingData<AppConnection>> {
        val to = getStartTime()
        return if (input.isEmpty()) {
                Pager(pagingConfig) { nwlogDao.getAppIpLogs(uid, to) }
            } else {
                Pager(pagingConfig) { nwlogDao.getAppIpLogsFiltered(uid, to, "%$input%") }
            }
            .liveData
            .cachedIn(viewModelScope)
    }

    private fun fetchAppDomainLogs(uid: Int, input: String): LiveData<PagingData<AppConnection>> {
        val to = getStartTime()
        return if (input.isEmpty()) {
                Pager(pagingConfig) { nwlogDao.getAppDomainLogs(uid, to) }
            } else {
                Pager(pagingConfig) { nwlogDao.getAppDomainLogsFiltered(uid, to, "%$input%") }
            }
            .liveData
            .cachedIn(viewModelScope)
    }

    private fun getStartTime(): Long {
        return startTime.value ?: (System.currentTimeMillis() - ONE_HOUR_MILLIS)
    }

    fun getConnectionsCount(uid: Int): LiveData<Int> {
        return nwlogDao.getAppConnectionsCount(uid)
    }

    fun getAppDomainConnectionsCount(uid: Int): LiveData<Int> {
        return nwlogDao.getAppDomainConnectionsCount(uid)
    }

    fun getDomainLogsLimited(uid: Int): LiveData<PagingData<AppConnection>> {
        return Pager(pagingConfig) { nwlogDao.getAppDomainLogsLimited(uid) }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getIpLogsLimited(uid: Int): LiveData<PagingData<AppConnection>> {
        return Pager(pagingConfig) { nwlogDao.getAppIpLogsLimited(uid) }
            .liveData
            .cachedIn(viewModelScope)
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
