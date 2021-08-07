/*
Copyright 2020 RethinkDNS and its authors

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

import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import androidx.paging.toLiveData
import androidx.room.Transaction
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


class DoHEndpointRepository(private val doHEndpointDAO: DoHEndpointDAO) {

    @Transaction
    fun update(doHEndpoint: DoHEndpoint) {
        doHEndpointDAO.removeConnectionStatus()
        doHEndpointDAO.update(doHEndpoint)
    }

    fun deleteAsync(doHEndpoint: DoHEndpoint, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            doHEndpointDAO.delete(doHEndpoint)
        }
    }


    fun insertAsync(doHEndpoint: DoHEndpoint, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            doHEndpointDAO.insert(doHEndpoint)
        }
    }

    fun insertWithReplaceAsync(doHEndpoint: DoHEndpoint,
                               coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            doHEndpointDAO.insertReplace(doHEndpoint)
        }
    }

    fun getDoHEndpointLiveData(): LiveData<PagedList<DoHEndpoint>> {
        return doHEndpointDAO.getDoHEndpointLiveData().toLiveData(pageSize = LIVEDATA_PAGE_SIZE)
    }

    fun deleteOlderData(date: Long, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            doHEndpointDAO.deleteOlderData(date)
        }
    }

    fun getDoHEndpointLiveDataByName(query: String): LiveData<PagedList<DoHEndpoint>> {
        return doHEndpointDAO.getDoHEndpointLiveDataByName(query).toLiveData(
            pageSize = LIVEDATA_PAGE_SIZE)
    }

    fun deleteDoHEndpoint(id: Int) {
        doHEndpointDAO.deleteDoHEndpoint(id)
    }

    fun removeConnectionStatus() {
        doHEndpointDAO.removeConnectionStatus()
    }

    fun getConnectedDoH(): DoHEndpoint? {
        return doHEndpointDAO.getConnectedDoH()
    }

    fun updateConnectionURL(url: String) {
        doHEndpointDAO.updateConnectionURL(url)
    }

    fun getConnectionURL(id: Int): String {
        return doHEndpointDAO.getConnectionURL(id)
    }

    fun getCount(): Int {
        return doHEndpointDAO.getCount()
    }

    fun updateConnectionDefault(): DoHEndpoint? {
        doHEndpointDAO.updateConnectionDefault()
        return doHEndpointDAO.getConnectedDoH()
    }

    fun getRethinkDnsEndpoint(): DoHEndpoint {
        return doHEndpointDAO.getRethinkDnsEndpoint()
    }

}
