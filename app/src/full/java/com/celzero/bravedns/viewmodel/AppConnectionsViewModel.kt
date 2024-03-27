package com.celzero.bravedns.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
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
    private var filter: MutableLiveData<String> = MutableLiveData()
    private var allLogsFilter: MutableLiveData<String> = MutableLiveData()
    private var uid: Int = Constants.INVALID_UID
    private val pagingConfig: PagingConfig

    init {
        filter.value = ""
        allLogsFilter.value = ""

        pagingConfig =
            PagingConfig(
                enablePlaceholders = false,
                prefetchDistance = 10,
                initialLoadSize = Constants.LIVEDATA_PAGE_SIZE,
                pageSize = Constants.LIVEDATA_PAGE_SIZE
            )
    }

    enum class FilterType {
        OFFSET,
        ALL
    }

    val appNetworkLogs = filter.switchMap { input -> fetchNetworkLogs(uid, input) }

    val allAppNetworkLogs = allLogsFilter.switchMap { input -> fetchAllNetworkLogs(uid, input) }

    private fun fetchNetworkLogs(uid: Int, input: String): LiveData<PagingData<AppConnection>> {
        val pager =
            if (input.isEmpty()) {
                Pager(config = pagingConfig, pagingSourceFactory = { nwlogDao.getLogsForAppWithLimit(uid) })
                    .flow
                    .cachedIn(viewModelScope)
            } else {
                Pager(
                        config = pagingConfig,
                        pagingSourceFactory = { nwlogDao.getLogsForAppFilteredWithLimit(uid, "%$input%") }
                    )
                    .flow
                    .cachedIn(viewModelScope)
            }

        return pager.asLiveData()

        /*return if (input.isEmpty()) {
            Pager(pagingConfig) { nwlogDao.getLogsForAppWithLimit(uid) }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(pagingConfig) { nwlogDao.getLogsForAppFilteredWithLimit(uid, "%$input%") }
                .liveData
                .cachedIn(viewModelScope)
        }*/
    }

    private fun fetchAllNetworkLogs(uid: Int, input: String): LiveData<PagingData<AppConnection>> {
        return if (input.isEmpty()) {
            Pager(pagingConfig) { nwlogDao.getAllLogs(uid) }.liveData.cachedIn(viewModelScope)
        } else {
            Pager(pagingConfig) { nwlogDao.getAllLogsFiltered(uid, "%$input%") }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    fun getConnectionsCount(uid: Int): LiveData<Int> {
        return nwlogDao.getAppConnectionsCount(uid)
    }

    fun setFilter(input: String, filterType: FilterType) {
        if (filterType == FilterType.OFFSET) {
            this.filter.postValue(input)
        } else {
            this.allLogsFilter.postValue(input)
        }
    }

    fun setUid(uid: Int) {
        this.uid = uid
    }
}
