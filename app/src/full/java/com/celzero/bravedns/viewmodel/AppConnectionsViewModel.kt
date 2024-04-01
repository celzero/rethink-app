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
    private var filter: MutableLiveData<String> = MutableLiveData()
    private var allLogsFilter: MutableLiveData<String> = MutableLiveData()
    private var uid: Int = Constants.INVALID_UID
    private val pagingConfig: PagingConfig

    init {
        filter.value = ""
        allLogsFilter.value = ""

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
        OFFSET,
        ALL
    }

    val allAppNetworkLogs = allLogsFilter.switchMap { input -> fetchAllNetworkLogs(uid, input) }

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
