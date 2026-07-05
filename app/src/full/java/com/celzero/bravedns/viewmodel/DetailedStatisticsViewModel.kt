/*
 * Copyright 2022 RethinkDNS and its authors
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

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.SummaryStatisticsType
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.StatsSummaryDao
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*

class DetailedStatisticsViewModel(
    private val connectionTrackerDAO: ConnectionTrackerDAO,
    private val statsDao: StatsSummaryDao
) : ViewModel() {
    private val _allActiveConns: MutableStateFlow<Long> = MutableStateFlow(0L)
    private val _allowedNetworkActivity: MutableStateFlow<String> = MutableStateFlow("")
    private val _blockedNetworkActivity: MutableStateFlow<String> = MutableStateFlow("")
    private val _allowedAsn: MutableStateFlow<String> = MutableStateFlow("")
    private val _blockedAsn: MutableStateFlow<String> = MutableStateFlow("")
    private val _allowedDomains: MutableStateFlow<String> = MutableStateFlow("")
    private val _blockedDomains: MutableStateFlow<String> = MutableStateFlow("")
    private val _allowedIps: MutableStateFlow<String> = MutableStateFlow("")
    private val _blockedIps: MutableStateFlow<String> = MutableStateFlow("")
    private val _allowedCountries: MutableStateFlow<String> = MutableStateFlow("")
    private val _startTime: MutableStateFlow<Long> = MutableStateFlow(0L)

    companion object {
        private const val ONE_HOUR_MILLIS = 1 * 60 * 60 * 1000L
        private const val ONE_DAY_MILLIS = 24 * ONE_HOUR_MILLIS
        private const val ONE_WEEK_MILLIS = 7 * ONE_DAY_MILLIS
    }

    fun setData(type: SummaryStatisticsType) {
        when (type) {
            SummaryStatisticsType.TOP_ACTIVE_CONNS -> {
                _allActiveConns.value = VpnController.uptimeMs()
            }
            SummaryStatisticsType.MOST_CONNECTED_APPS -> {
                _allowedNetworkActivity.value = ""
            }
            SummaryStatisticsType.MOST_BLOCKED_APPS -> {
                _blockedNetworkActivity.value = ""
            }
            SummaryStatisticsType.MOST_CONNECTED_ASN -> {
                _allowedAsn.value = ""
            }
            SummaryStatisticsType.MOST_BLOCKED_ASN -> {
                _blockedAsn.value = ""
            }
            SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> {
                _allowedDomains.value = ""
            }
            SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> {
                _blockedDomains.value = ""
            }
            SummaryStatisticsType.MOST_CONTACTED_IPS -> {
                _allowedIps.value = ""
            }
            SummaryStatisticsType.MOST_BLOCKED_IPS -> {
                _blockedIps.value = ""
            }
            SummaryStatisticsType.MOST_CONTACTED_COUNTRIES -> {
                _allowedCountries.value = ""
            }
        }
    }

    fun timeCategoryChanged(timeCategory: SummaryStatisticsViewModel.TimeCategory) {
        when (timeCategory) {
            SummaryStatisticsViewModel.TimeCategory.ONE_HOUR -> {
                _startTime.value = System.currentTimeMillis() - ONE_HOUR_MILLIS
            }
            SummaryStatisticsViewModel.TimeCategory.TWENTY_FOUR_HOUR -> {
                _startTime.value = System.currentTimeMillis() - ONE_DAY_MILLIS
            }
            SummaryStatisticsViewModel.TimeCategory.SEVEN_DAYS -> {
                _startTime.value = System.currentTimeMillis() - ONE_WEEK_MILLIS
            }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getAllActiveConns: Flow<PagingData<AppConnection>> =
        _allActiveConns.flatMapLatest { uptime ->
            val to = System.currentTimeMillis() - uptime
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getAllActiveConns(to)
            }.flow.cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getAllAllowedAppNetworkActivity: Flow<PagingData<AppConnection>> =
        combine(_allowedNetworkActivity, _startTime) { _, start ->
            start
        }.flatMapLatest { start ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getAllAllowedApps(start)
            }.flow.cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getAllAllowedAsn: Flow<PagingData<AppConnection>> =
        combine(_allowedAsn, _startTime) { _, start ->
            start
        }.flatMapLatest { start ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getAllConnectedASN(start)
            }.flow.cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getAllBlockedAsn: Flow<PagingData<AppConnection>> =
        combine(_blockedAsn, _startTime) { _, start ->
            start
        }.flatMapLatest { start ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getAllBlockedASN(start)
            }.flow.cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getAllBlockedAppNetworkActivity: Flow<PagingData<AppConnection>> =
        combine(_blockedNetworkActivity, _startTime) { _, start ->
            start
        }.flatMapLatest { start ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getAllBlockedApps(start)
            }.flow.cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getAllBlockedDomains: Flow<PagingData<AppConnection>> =
        combine(_blockedDomains, _startTime) { _, start ->
            start
        }.flatMapLatest { start ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getAllBlockedDomains(start)
            }.flow.cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getAllContactedDomains: Flow<PagingData<AppConnection>> =
        combine(_allowedDomains, _startTime) { _, start ->
            start
        }.flatMapLatest { start ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getAllContactedDomains(start)
            }.flow.cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getAllContactedIps: Flow<PagingData<AppConnection>> =
        combine(_allowedIps, _startTime) { _, start ->
            start
        }.flatMapLatest { start ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                connectionTrackerDAO.getAllContactedIps(start)
            }.flow.cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getAllBlockedIps: Flow<PagingData<AppConnection>> =
        combine(_blockedIps, _startTime) { _, start ->
            start
        }.flatMapLatest { start ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                connectionTrackerDAO.getAllBlockedIps(start)
            }.flow.cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getAllContactedCountries: Flow<PagingData<AppConnection>> =
        combine(_allowedCountries, _startTime) { _, start ->
            start
        }.flatMapLatest { start ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getAllContactedCountries(start)
            }.flow.cachedIn(viewModelScope)
        }
}

