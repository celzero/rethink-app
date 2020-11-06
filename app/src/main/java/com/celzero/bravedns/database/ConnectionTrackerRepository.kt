/*
Copyright 2020 RethinkDNS developers

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.database

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import androidx.paging.toLiveData
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


class ConnectionTrackerRepository(private val connectionTrackerDAO: ConnectionTrackerDAO) {

    fun updateAsync(connectionTracker: ConnectionTracker, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            connectionTrackerDAO.update(connectionTracker)
        }
    }

    fun deleteAsync(connectionTracker: ConnectionTracker, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            connectionTrackerDAO.delete(connectionTracker)
        }
    }


    fun insertAsync(connectionTracker: ConnectionTracker, coroutineScope: CoroutineScope = GlobalScope) {
        //coroutineScope.launch {
            connectionTrackerDAO.insert(connectionTracker)
            deleteConnectionTrackerCount()
        //}
    }

    fun insertBulkAsync(connTrackerList : ArrayList<ConnectionTracker>){
        if(DEBUG) Log.d(LOG_TAG, "Conn tracker bulk insert: ${connTrackerList.size}")
        connectionTrackerDAO.insertBulk(connTrackerList)
    }

    fun getConnectionTrackerLiveData(): LiveData<PagedList<ConnectionTracker>> {
        return connectionTrackerDAO.getConnectionTrackerLiveData().toLiveData(pageSize = 50)
    }

    fun deleteConnectionTrackerCount(coroutineScope: CoroutineScope = GlobalScope)  {
        //coroutineScope.launch {
            //val count = connectionTrackerDAO.getCountConnectionTracker()
            //if (count > 4000) {
            connectionTrackerDAO.deleteOlderDataCount(Constants.FIREWALL_CONNECTIONS_IN_DB)
            //}
        //}
    }

    fun deleteOlderData(date: Long, coroutineScope: CoroutineScope = GlobalScope) {
        //coroutineScope.launch {
            connectionTrackerDAO.deleteOlderData(date)
        //}
    }

    fun getConnTrackerForAppLiveData(uid: Int): LiveData<List<ConnectionTracker>> {
        return connectionTrackerDAO.getConnTrackerForAppLiveData(uid)
    }


}