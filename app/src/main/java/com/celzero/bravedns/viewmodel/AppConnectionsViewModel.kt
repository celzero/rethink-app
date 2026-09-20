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
import androidx.lifecycle.liveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.liveData
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.BlocklistStatsAggregator
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.database.RethinkLogDao
import com.celzero.bravedns.database.StatsSummaryDao
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppConnectionsViewModel(
    private val nwlogDao: ConnectionTrackerDAO,
    private val rinrDao: RethinkLogDao,
    private val statsDao: StatsSummaryDao
) : ViewModel() {
    private val ipFilter: MutableLiveData<String> = MutableLiveData()
    private val domainFilter: MutableLiveData<String> = MutableLiveData()
    private val asnFilter: MutableLiveData<String> = MutableLiveData()
    private val activeConnsFilter: MutableLiveData<String> = MutableLiveData()
    private val countryFilter: MutableLiveData<String> = MutableLiveData()
    private val blocklists: MutableLiveData<Long> = MutableLiveData()

    private var uid: Int = Constants.INVALID_UID

    // when non-null, the ip/asn/domain log screens show only allowed (false)
    // or blocked (true) entries; null keeps the unfiltered behavior. set once
    // by the show-all screens before their adapters start observing
    private var blockedFilter: Boolean? = null
    private val pagingConfig: PagingConfig
    private var timeCategory: TimeCategory = TimeCategory.SEVEN_DAYS
    private val startTime: MutableLiveData<Long> = MutableLiveData()
    var filterQuery: String = ""

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
        asnFilter.value = ""
        activeConnsFilter.value = ""
        countryFilter.value = ""
        // fixed 7-day window, matching the other "Limited" section queries
        blocklists.value = System.currentTimeMillis() - ONE_WEEK_MILLIS
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
            asnFilter.value = ""
            countryFilter.value = ""
        }
    }

    enum class FilterType {
        IP,
        DOMAIN,
        ASN,
        ACTIVE_CONNECTIONS
    }

    val appIpLogs = ipFilter.switchMap { input -> fetchIpLogs(uid, input) }
    val appDomainLogs = domainFilter.switchMap { input ->
        fetchAppDomainLogs(uid, input)
    }
    val asnLogs = asnFilter.switchMap { input ->
        fetchAllAsnLogs(uid, input)
    }

    val countryLogs = countryFilter.switchMap { _ ->
        fetchAllCountriesLogs(uid)
    }
    val activeConnections = activeConnsFilter.switchMap { input ->
        fetchAllActiveConnections(uid, input)
    }

    val rinrIpLogs = ipFilter.switchMap { input -> fetchRinrIpLogs(input) }
    val rinrDomainLogs = domainFilter.switchMap { input -> fetchRinrDomainLogs(input) }

    private fun fetchRinrIpLogs(input: String): LiveData<PagingData<AppConnection>> {
        val to = getStartTime()
        return if (input.isEmpty() && blockedFilter == null) {
            Pager(pagingConfig) { rinrDao.getIpLogs(to) }
        } else {
            Pager(pagingConfig) { rinrDao.getIpLogsFiltered(to, "%$input%", blockedFilter) }
        }
            .liveData
            .cachedIn(viewModelScope)
    }

    private fun fetchRinrDomainLogs(input: String): LiveData<PagingData<AppConnection>> {
        val to = getStartTime()
        return if (input.isEmpty() && blockedFilter == null) {
            Pager(pagingConfig) { rinrDao.getDomainLogs(to) }
        } else {
            Pager(pagingConfig) { rinrDao.getDomainLogsFiltered(to, "%$input%", blockedFilter) }
        }
            .liveData
            .cachedIn(viewModelScope)
    }

    private fun fetchIpLogs(uid: Int, input: String): LiveData<PagingData<AppConnection>> {
        val to = getStartTime()
        return if (input.isEmpty() && blockedFilter == null) {
            Pager(pagingConfig) { nwlogDao.getAppIpLogs(uid, to) }
        } else {
            Pager(pagingConfig) { nwlogDao.getAppIpLogsFiltered(uid, to, "%$input%", blockedFilter) }
        }
            .liveData
            .cachedIn(viewModelScope)
    }

    private fun fetchAppDomainLogs(uid: Int, input: String): LiveData<PagingData<AppConnection>> {
        val to = getStartTime()
        val query = "%$input%"
        return Pager(pagingConfig) {
            when (blockedFilter) {
                null -> {
                    if (input.isEmpty()) {
                        statsDao.getAllDomainsByUid(uid, to)
                    } else {
                        statsDao.getAllDomainsByUid(uid, to, query)
                    }
                }
                false -> statsDao.getAllowedDomainsByUid(uid, to, query)
                true -> statsDao.getBlockedDomainsByUidFiltered(uid, to, query)
            }
        }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun fetchTopActiveConnections(uid: Int, uptime: Long): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - uptime
        return Pager(pagingConfig) { statsDao.getTopActiveConns(uid, to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    private fun fetchAllActiveConnections(uid: Int, input: String): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - VpnController.uptimeMs()
        val query = "%$input%"
        return Pager(pagingConfig) { statsDao.getAllActiveConns(uid, to, query) }
            .liveData
            .cachedIn(viewModelScope)
    }

    private fun fetchAllAsnLogs(uid: Int, input: String): LiveData<PagingData<AppConnection>> {
        val to = getStartTime()
        val query = "%$input%"
        return Pager(pagingConfig) { statsDao.getAllAsnLogs(uid, to, query, blockedFilter) }
            .liveData
            .cachedIn(viewModelScope)
    }

    private fun fetchAllCountriesLogs(uid: Int): LiveData<PagingData<AppConnection>> {
        // fixed 7-day window: must match getMostContactedCountriesLimited so
        // the top-3 section and the "see all" screen always agree (the screen's
        // time toggle must not shrink this window)
        val to = System.currentTimeMillis() - ONE_WEEK_MILLIS
        // rethink's own logs live in RethinkLog, not ConnectionTracker
        return Pager(pagingConfig) {
            if (android.os.Process.myUid() == uid) {
                rinrDao.getAllCountryLogs(to)
            } else {
                statsDao.getAllContactedCountriesByUid(uid, to)
            }
        }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun deleteLogs(uid: Int) {
        // delete based on the time category
        when (timeCategory) {
            TimeCategory.ONE_HOUR -> {
                nwlogDao.clearLogsByTime(uid, System.currentTimeMillis() - ONE_HOUR_MILLIS)
            }

            TimeCategory.TWENTY_FOUR_HOUR -> {
                nwlogDao.clearLogsByTime(uid, System.currentTimeMillis() - ONE_DAY_MILLIS)
            }

            TimeCategory.SEVEN_DAYS -> {
                nwlogDao.clearLogsByUid(uid) // similar to clearing logs for uid
            }
        }
    }

    private fun getStartTime(): Long {
        return startTime.value ?: (System.currentTimeMillis() - ONE_WEEK_MILLIS)
    }

    fun getDomainLogsLimited(uid: Int): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - ONE_WEEK_MILLIS
        return Pager(pagingConfig) {
            statsDao.getMostDomainsByUid(uid, to)
        }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getRethinkActiveConnsLimited(uptime: Long): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - uptime
        return Pager(pagingConfig) { statsDao.getRethinkTopActiveConns(to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getRethinkAllActiveConns(uptime: Long): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - uptime
        return Pager(pagingConfig) { statsDao.getRethinkAllActiveConns(to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getRethinkDomainLogsLimited(): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - ONE_WEEK_MILLIS
        return Pager(pagingConfig) { rinrDao.getDomainLogsLimited(to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getRethinkIpLogsLimited(): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - ONE_WEEK_MILLIS
        return Pager(pagingConfig) { rinrDao.getIpLogsLimited(to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getAsnLogsLimited(uid: Int): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - ONE_WEEK_MILLIS
        return Pager(pagingConfig) { statsDao.getAsnLogsLimited(uid, to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getMostContactedCountriesLimited(uid: Int): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - ONE_WEEK_MILLIS
        return Pager(pagingConfig) { statsDao.getMostContactedCountriesByUid(uid, to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getRethinkCountriesLimited(): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - ONE_WEEK_MILLIS
        return Pager(pagingConfig) { rinrDao.getCountryLogsLimited(to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getIpLogsLimited(uid: Int): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - ONE_WEEK_MILLIS
        return Pager(pagingConfig) { nwlogDao.getAppIpLogsLimited(uid, to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getBlockedDomainLogsLimited(uid: Int): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - ONE_WEEK_MILLIS
        return Pager(pagingConfig) { statsDao.getBlockedDomainsByUid(uid, to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getRethinkBlockedDomainLogsLimited(): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - ONE_WEEK_MILLIS
        return Pager(pagingConfig) { rinrDao.getBlockedDomainLogsLimited(to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getBlockedIpLogsLimited(uid: Int): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - ONE_WEEK_MILLIS
        return Pager(pagingConfig) { nwlogDao.getBlockedAppIpLogsLimited(uid, to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getRethinkBlockedIpLogsLimited(): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - ONE_WEEK_MILLIS
        return Pager(pagingConfig) { rinrDao.getBlockedIpLogsLimited(to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    fun getBlockedAsnLogsLimited(uid: Int): LiveData<PagingData<AppConnection>> {
        val to = System.currentTimeMillis() - ONE_WEEK_MILLIS
        return Pager(pagingConfig) { statsDao.getBlockedAsnByUid(uid, to) }
            .liveData
            .cachedIn(viewModelScope)
    }

    /**
     * Per-blocklist blocked totals for this uid over a fixed 7-day window. The
     * CSV token split happens in [BlocklistStatsAggregator], so this section is
     * a static list instead of a Room-paged flow.
     */
    val mostBlockedBlocklists: LiveData<List<AppConnection>> =
        blocklists.switchMap { to ->
            liveData { emit(fetchBlockedBlocklists(to)) }
        }

    private suspend fun fetchBlockedBlocklists(to: Long): List<AppConnection> {
        return withContext(Dispatchers.IO) {
            // DnsLogs is the single source of truth for DNS-level blocks;
            // ConnectionTracker rows echo the same block and would double-count
            BlocklistStatsAggregator.toAppConnections(
                BlocklistStatsAggregator.aggregate(statsDao.getBlockedBlocklistCombosByUid(uid, to))
            )
        }
    }

    fun setFilter(input: String, filterType: FilterType) {
        filterQuery = input
        when (filterType) {
            FilterType.IP -> {
                ipFilter.value = input
            }

            FilterType.DOMAIN -> {
                domainFilter.value = input
            }

            FilterType.ASN -> {
                asnFilter.value = input
            }

            FilterType.ACTIVE_CONNECTIONS -> {
                activeConnsFilter.value = input
            }
        }
    }

    fun setUid(uid: Int) {
        this.uid = uid
    }

    fun setBlockedFilter(isBlocked: Boolean?) {
        this.blockedFilter = isBlocked
    }
}
