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
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.database.ConnectionTrackerDAO
import com.celzero.bravedns.util.Constants
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest

class AppConnectionsViewModel(private val nwlogDao: ConnectionTrackerDAO) : ViewModel() {
    private var filter: MutableLiveData<String> = MutableLiveData()
    private var uid: Int = Constants.MISSING_UID
    private val pagingConfig: PagingConfig
    var f: StateFlow<String>

    init {
        filter.value = ""
        f = filter.value?.let { MutableStateFlow(it) } ?: MutableStateFlow("")

        pagingConfig =
            PagingConfig(
                pageSize = Constants.LIVEDATA_PAGE_SIZE,
                prefetchDistance = Constants.LIVEDATA_PAGE_SIZE / 2,
                enablePlaceholders = false
            )
    }

    val appNetworkLogs = filter.switchMap { input -> fetchNetworkLogs(uid, input) }

    private fun fetchNetworkLogs(uid: Int, input: String): LiveData<PagingData<AppConnection>> {
        val pager =
            if (input.isEmpty()) {
                Pager(config = pagingConfig, pagingSourceFactory = { nwlogDao.getLogsForApp(uid) })
                    .flow
                    .cachedIn(viewModelScope)
            } else {
                Pager(
                        config = pagingConfig,
                        pagingSourceFactory = { nwlogDao.getLogsForAppFiltered(uid, "%$input%") }
                    )
                    .flow
                    .cachedIn(viewModelScope)
            }

        return pager.asLiveData()
    }

    fun getConnectionsCount(uid: Int): LiveData<Int> {
        return nwlogDao.getAppConnectionsCount(uid)
    }

    fun setFilter(input: String) {
        this.filter.postValue(input)
        this.f = MutableStateFlow(input)
    }

    fun setUid(uid: Int) {
        this.uid = uid
    }
}
