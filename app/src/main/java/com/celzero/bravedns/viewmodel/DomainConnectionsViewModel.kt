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

import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.cachedIn
import androidx.paging.liveData
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.data.BlocklistStatsAggregator
import com.celzero.bravedns.database.StatsSummaryDao
import com.celzero.bravedns.util.Constants

class DomainConnectionsViewModel(private val statsDao: StatsSummaryDao) : ViewModel() {
    private val domains: MutableLiveData<String> = MutableLiveData()
    private val asn: MutableLiveData<String> = MutableLiveData()
    private val flag: MutableLiveData<String> = MutableLiveData()
    private val ip: MutableLiveData<String> = MutableLiveData()

    // blocklist drill-down is an expandable list instead of a paged flow, so
    // its inputs are kept as plain fields and served through suspend fetchers
    private var blocklistName: String = ""
    private var timeCategory: TimeCategory = TimeCategory.ONE_HOUR
    private val startTime: MutableLiveData<Long> = MutableLiveData()
    private var isBlocked: Boolean = false

    // when set, the blocklist drill-down is scoped to this uid (per-app
    // screen); INVALID_UID (the default, stats screen) keeps it device-wide
    private var scopedUid: Int = Constants.INVALID_UID

    fun setUid(uid: Int) {
        this.scopedUid = uid
    }

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
        // set from and to time to current and 1 hr before
        startTime.value = System.currentTimeMillis() - ONE_HOUR_MILLIS
        domains.postValue("")
        asn.postValue("")
        flag.postValue("")
        ip.postValue("")
    }

    fun setDomain(domain: String, isBlocked: Boolean) {
        this.isBlocked = isBlocked
        domains.postValue(domain)
    }

    fun setFlag(flag: String) {
        this.flag.postValue(flag)
    }

    fun setAsn(asn: String, isBlocked: Boolean) {
        this.isBlocked = isBlocked
        this.asn.postValue(asn)
    }

    fun setIp(ip: String, isBlocked: Boolean) {
        this.isBlocked = isBlocked
        this.ip.postValue(ip)
    }

    fun setBlocklist(name: String) {
        // rows shown for a blocklist are always blocked queries
        this.blocklistName = name
    }

    /**
     * Apps with blocked-query counts attributed to the selected blocklist.
     * [tags] holds the active chip filter: rows are counted for any of those
     * `name:tag` lists (OR). An empty set excludes everything, mirroring the
     * chips where every list starts checked and unchecking narrows results.
     */
    suspend fun getBlocklistApps(tags: Set<String>): List<AppConnection> {
        if (blocklistName.isEmpty()) return emptyList()
        val rows = statsDao.getBlocklistAttributions(
            BlocklistStatsAggregator.escapeForLike(blocklistName),
            scopedUid(),
            startTime.value ?: 0L
        )
        return BlocklistStatsAggregator.appBreakdown(rows, blocklistName, tags)
    }

    /** Domains the blocklist blocked for one app (lazy, on expansion). */
    suspend fun getBlocklistDomainsForApp(uid: Int, tags: Set<String>): List<AppConnection> {
        if (blocklistName.isEmpty() || tags.isEmpty()) return emptyList()
        val rows = statsDao.getBlocklistAttributions(
            BlocklistStatsAggregator.escapeForLike(blocklistName),
            scopedUid(),
            startTime.value ?: 0L
        )
        return BlocklistStatsAggregator.domainBreakdown(rows, blocklistName, tags, uid)
    }

    /**
     * Distinct list tags attributed to the selected blocklist in the selected
     * window, most frequent first; shown as selectable filter chips.
     */
    suspend fun getBlocklistTags(): List<String> {
        if (blocklistName.isEmpty()) return emptyList()
        val combos = statsDao.getBlocklistTagCombos(
            BlocklistStatsAggregator.escapeForLike(blocklistName),
            scopedUid(),
            startTime.value ?: 0L
        )
        return BlocklistStatsAggregator.tagsForBlocklist(combos, blocklistName)
    }

    /** INVALID_UID when unscoped (stats screen); the app uid on per-app screens. */
    private fun scopedUid(): Int? {
        return if (scopedUid == Constants.INVALID_UID) null else scopedUid
    }

    fun timeCategoryChanged(tc: TimeCategory) {
        timeCategory = tc
        when (tc) {
            TimeCategory.ONE_HOUR -> {
                startTime.value = System.currentTimeMillis() - ONE_HOUR_MILLIS
            }
            TimeCategory.TWENTY_FOUR_HOUR -> {
                startTime.value = System.currentTimeMillis() - ONE_DAY_MILLIS
            }
            TimeCategory.SEVEN_DAYS -> {
                startTime.value = System.currentTimeMillis() - ONE_WEEK_MILLIS
            }
        }
        asn.value = ""
        flag.value = ""
        domains.value = ""
        ip.value = ""
    }

    val domainConnectionList = domains.switchMap { input ->
        fetchDomainConnections(input)
    }

    val flagConnectionList = flag.switchMap { input ->
        fetchFlagConnections(input)
    }

    val asnConnectionList = asn.switchMap { input ->
        fetchAsnConnections(input)
    }

    val ipConnectionList = ip.switchMap { input ->
        fetchIpConnections(input)
    }

    private fun fetchDomainConnections(input: String) =
        Pager(PagingConfig(pageSize = Constants.LIVEDATA_PAGE_SIZE)) {
            statsDao.getDomainDetails(input, startTime.value!!, isBlocked)
        }.liveData.cachedIn(viewModelScope)

    private fun fetchFlagConnections(input: String) =
        Pager(PagingConfig(pageSize = Constants.LIVEDATA_PAGE_SIZE)) {
            statsDao.getFlagDetails(input, startTime.value!!)
        }.liveData.cachedIn(viewModelScope)

    private fun fetchAsnConnections(input: String) =
        Pager(PagingConfig(pageSize = Constants.LIVEDATA_PAGE_SIZE)) {
            if (isBlocked) {
                statsDao.getAsnBlockedDetails(input, startTime.value!!)
            } else {
                statsDao.getAsnDetails(input, startTime.value!!)
            }
        }.liveData.cachedIn(viewModelScope)

    private fun fetchIpConnections(input: String) =
        Pager(PagingConfig(pageSize = Constants.LIVEDATA_PAGE_SIZE)) {
            statsDao.getIpDetails(input, startTime.value!!, isBlocked)
        }.liveData.cachedIn(viewModelScope)
}
