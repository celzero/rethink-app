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
import com.celzero.bravedns.data.DataUsageSummary
import com.celzero.bravedns.database.ConnectionTracker
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.StatsSummaryDao
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SummaryStatisticsViewModel(
    private val connectionTrackerDAO: ConnectionTrackerDAO,
    private val statsDao: StatsSummaryDao
) : ViewModel() {
    private val _uiState = MutableStateFlow(SummaryStatisticsUiState())
    val uiState: StateFlow<SummaryStatisticsUiState> = _uiState.asStateFlow()

    private val startTime = MutableStateFlow(System.currentTimeMillis() - ONE_HOUR_MILLIS)
    private val topActiveConnsTick = MutableStateFlow(VpnController.uptimeMs())
    private val refreshTick = MutableStateFlow(0L)

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

    data class SummaryStatisticsUiState(
        val timeCategory: TimeCategory = TimeCategory.ONE_HOUR,
        val dataUsage: DataUsageSummary = DataUsageSummary(0, 0, 0, 0)
    )


    init {
        updateDataUsage()
    }

    fun timeCategoryChanged(tc: TimeCategory) {
        val st = when (tc) {
            TimeCategory.ONE_HOUR -> System.currentTimeMillis() - ONE_HOUR_MILLIS
            TimeCategory.TWENTY_FOUR_HOUR -> System.currentTimeMillis() - ONE_DAY_MILLIS
            TimeCategory.SEVEN_DAYS -> System.currentTimeMillis() - ONE_WEEK_MILLIS
        }
        startTime.value = st
        _uiState.update { it.copy(timeCategory = tc) }
        refreshTick.update { it + 1 }
        updateDataUsage()
    }

    private fun updateDataUsage() {
        viewModelScope.launch {
            val to = startTime.value
            val usage = withContext(Dispatchers.IO) {
                connectionTrackerDAO.getTotalUsages(to, ConnectionTracker.ConnType.METERED.value)
            }
            _uiState.update { it.copy(dataUsage = usage) }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getTopActiveConns: Flow<PagingData<AppConnection>> =
        topActiveConnsTick.flatMapLatest { it ->
            val to = System.currentTimeMillis() - it
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getTopActiveConns(to)
            }
                .flow
                .cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getAllowedAppNetworkActivity: Flow<PagingData<AppConnection>> =
        refreshTick.flatMapLatest { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getMostAllowedApps(startTime.value)
            }
                .flow
                .cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getBlockedAppNetworkActivity: Flow<PagingData<AppConnection>> =
        refreshTick.flatMapLatest { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getMostBlockedApps(startTime.value)
            }
                .flow
                .cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getMostConnectedASN: Flow<PagingData<AppConnection>> =
        refreshTick.flatMapLatest { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getMostConnectedASN(startTime.value)
            }
                .flow
                .cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getMostBlockedASN: Flow<PagingData<AppConnection>> =
        refreshTick.flatMapLatest { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getMostBlockedASN(startTime.value)
            }
                .flow
                .cachedIn(viewModelScope)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    val mbd: Flow<PagingData<AppConnection>> = refreshTick.flatMapLatest { _ ->
        Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
            statsDao.getMostBlockedDomains(startTime.value)
        }
        .flow
        .cachedIn(viewModelScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val mcd: Flow<PagingData<AppConnection>> = refreshTick.flatMapLatest { _ ->
        Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
            statsDao.getMostContactedDomains(startTime.value)
        }
        .flow
        .cachedIn(viewModelScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getMostContactedIps: Flow<PagingData<AppConnection>> = refreshTick.flatMapLatest { _ ->
        Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
            connectionTrackerDAO.getMostContactedIps(startTime.value)
        }
        .flow
        .cachedIn(viewModelScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getMostBlockedIps: Flow<PagingData<AppConnection>> = refreshTick.flatMapLatest { _ ->
        Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
            connectionTrackerDAO.getMostBlockedIps(startTime.value)
        }
        .flow
        .cachedIn(viewModelScope)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    val getMostContactedCountries: Flow<PagingData<AppConnection>> =
        refreshTick.flatMapLatest { _ ->
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                statsDao.getMostContactedCountries(startTime.value)
            }
                .flow
                .cachedIn(viewModelScope)
        }

    suspend fun getTopAppsForCountry(flag: String, limit: Int = 5): List<AppConnection> {
        if (flag.isBlank()) return emptyList()
        val to = startTime.value
        return withContext(Dispatchers.IO) {
            statsDao.getFlagDetailsLimited(flag = flag, to = to, limit = limit)
        }
    }
}
