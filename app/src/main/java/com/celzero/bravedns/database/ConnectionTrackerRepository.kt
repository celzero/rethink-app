package com.celzero.bravedns.database

import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import androidx.paging.toLiveData


class ConnectionTrackerRepository  (private val connectionTrackerDAO: ConnectionTrackerDAO){

    fun updateAsync(connectionTracker: ConnectionTracker){
        connectionTrackerDAO.update(connectionTracker)
    }

    fun deleteAsync(connectionTracker: ConnectionTracker){
        connectionTrackerDAO.delete(connectionTracker)
    }

    fun insertAsync(connectionTracker: ConnectionTracker){
        connectionTrackerDAO.insert(connectionTracker)
    }

    fun getConnectionTrackerLiveData(): LiveData<PagedList<ConnectionTracker>> {
        return connectionTrackerDAO.getConnectionTrackerLiveData().toLiveData(pageSize = 50)
    }



    fun deleteOlderData(date : Long){
        connectionTrackerDAO.deleteOlderData(date)
    }
}