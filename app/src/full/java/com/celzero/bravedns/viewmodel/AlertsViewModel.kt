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
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.liveData
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.DnsLogDAO
import com.celzero.bravedns.util.Constants

class AlertsViewModel(
    private val connectionTrackerDao: ConnectionTrackerDAO,
    private val dnsLogDao: DnsLogDAO
) : ViewModel() {
    private var ipLogList: MutableLiveData<String> = MutableLiveData()
    private var domainLogList: MutableLiveData<String> = MutableLiveData()
    private var appLogList: MutableLiveData<String> = MutableLiveData()
    private var fromTime: MutableLiveData<Long> = MutableLiveData()
    private var toTime: MutableLiveData<Long> = MutableLiveData()

    init {
        ipLogList.postValue("")
        domainLogList.postValue("")
        appLogList.postValue("")
        fromTime.value = System.currentTimeMillis() - 1 * 60 * 60 * 1000L
        toTime.value = System.currentTimeMillis()
    }

    fun getBlockedAppsCount(): LiveData<Int> {
        val fromTime = fromTime.value ?: 0L
        val toTime = toTime.value ?: 0L
        return connectionTrackerDao.getBlockedAppsCount(fromTime, toTime)
    }

    fun getBlockedDomainsCount(): LiveData<Int> {
        val fromTime = fromTime.value ?: 0L
        val toTime = toTime.value ?: 0L
        return dnsLogDao.getBlockedDomainsCount(fromTime, toTime)
    }

    fun getBlockedIpCount(): LiveData<Int> {
        val fromTime = fromTime.value ?: 0L
        val toTime = toTime.value ?: 0L
        return connectionTrackerDao.getBlockedIpCount(fromTime, toTime)
    }

    fun getBlockedIpLogList(): LiveData<List<AppConnection>> {
        val fromTime = fromTime.value ?: 0L
        val toTime = toTime.value ?: 0L
        return connectionTrackerDao.getBlockedIpLogList(fromTime, toTime)
    }

    fun getBlockedAppsLogList(): LiveData<List<AppConnection>> {
        val fromTime = fromTime.value ?: 0L
        val toTime = toTime.value ?: 0L
        return connectionTrackerDao.getBlockedAppLogList(fromTime, toTime)
    }

    fun getBlockedDnsLogList(): LiveData<List<AppConnection>> {
        val fromTime = fromTime.value ?: 0L
        val toTime = toTime.value ?: 0L
        return dnsLogDao.getBlockedDnsLogList(fromTime, toTime)
    }
}
