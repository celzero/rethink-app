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

class AppConnectionsViewModel(private val connectionTrackerDAO: ConnectionTrackerDAO) :
    ViewModel() {
    private var filter: MutableLiveData<String> = MutableLiveData()
    private var uid: Int = Constants.MISSING_UID

    init {
        filter.value = ""
    }

    val appNetworkLogs = filter.switchMap { input -> fetchNetworkLogs(uid, input) }

    private fun fetchNetworkLogs(uid: Int, input: String): LiveData<PagingData<AppConnection>> {
        return if (input.isEmpty()) {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    connectionTrackerDAO.getLogsForApp(uid)
                }
                .liveData
                .cachedIn(viewModelScope)
        } else {
            Pager(PagingConfig(Constants.LIVEDATA_PAGE_SIZE)) {
                    connectionTrackerDAO.getLogsForAppFiltered(uid, "%$input%")
                }
                .liveData
                .cachedIn(viewModelScope)
        }
    }

    fun getConnectionsCount(uid: Int): LiveData<Int> {
        return connectionTrackerDAO.getAppConnectionsCount(uid)
    }

    fun setFilter(input: String) {
        this.filter.postValue(input)
    }

    fun setUid(uid: Int) {
        this.uid = uid
    }
}
