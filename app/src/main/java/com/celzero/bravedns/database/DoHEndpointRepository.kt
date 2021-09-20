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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class DoHEndpointRepository(private val doHEndpointDAO: DoHEndpointDAO) {

    @Transaction
    suspend fun update(doHEndpoint: DoHEndpoint) {
        ioCtx {
            doHEndpointDAO.removeConnectionStatus()
            doHEndpointDAO.update(doHEndpoint)
        }
    }

    suspend fun insertAsync(doHEndpoint: DoHEndpoint) {
        ioCtx {
            doHEndpointDAO.insert(doHEndpoint)
        }
    }

    suspend fun insertWithReplaceAsync(doHEndpoint: DoHEndpoint) {
        ioCtx {
            doHEndpointDAO.insertReplace(doHEndpoint)
        }
    }

    suspend fun getDoHEndpointLiveData(): LiveData<PagedList<DoHEndpoint>> {
        return doHEndpointDAO.getDoHEndpointLiveData().toLiveData(pageSize = LIVEDATA_PAGE_SIZE)
    }

    suspend fun deleteOlderData(date: Long) {
        ioCtx {
            doHEndpointDAO.deleteOlderData(date)
        }
    }

    suspend fun getDoHEndpointLiveDataByName(query: String): LiveData<PagedList<DoHEndpoint>> {
        return doHEndpointDAO.getDoHEndpointLiveDataByName(query).toLiveData(
            pageSize = LIVEDATA_PAGE_SIZE)
    }

    suspend fun deleteDoHEndpoint(id: Int) {
        ioCtx {
            doHEndpointDAO.deleteDoHEndpoint(id)
        }
    }

    suspend fun removeConnectionStatus() {
        ioCtx {
            doHEndpointDAO.removeConnectionStatus()
        }
    }

    suspend fun getConnectedDoH(): DoHEndpoint? {
        return doHEndpointDAO.getConnectedDoH()
    }

    suspend fun updateConnectionURL(url: String) {
        ioCtx {
            doHEndpointDAO.updateConnectionURL(url)
        }
    }

    suspend fun getConnectionURL(id: Int): String {
        return doHEndpointDAO.getConnectionURL(id)
    }

    suspend fun getCount(): Int {
        return doHEndpointDAO.getCount()
    }

    suspend fun updateConnectionDefault() {
        ioCtx {
            doHEndpointDAO.updateConnectionDefault()
        }
    }

    suspend fun getRethinkDnsEndpoint(): DoHEndpoint {
        return doHEndpointDAO.getRethinkDnsEndpoint()
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) {
            f()
        }
    }

}
