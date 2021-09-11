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
import androidx.room.Transaction
import com.celzero.bravedns.util.Constants.Companion.LIVEDATA_PAGE_SIZE
import com.celzero.bravedns.util.LoggerConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class DNSCryptEndpointRepository(private val dnsCryptEndpointDAO: DNSCryptEndpointDAO) {

    @Transaction
    fun update(dnsCryptEndpoint: DNSCryptEndpoint) {
        dnsCryptEndpointDAO.update(dnsCryptEndpoint)
    }


    fun insertAsync(dnsCryptEndpoint: DNSCryptEndpoint,
                    coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        coroutineScope.launch {
            dnsCryptEndpointDAO.insert(dnsCryptEndpoint)
        }
    }

    fun getDNSCryptEndpointLiveData(): LiveData<PagedList<DNSCryptEndpoint>> {
        return dnsCryptEndpointDAO.getDNSCryptEndpointLiveData().toLiveData(
            pageSize = LIVEDATA_PAGE_SIZE)
    }

    fun deleteOlderData(date: Long,
                        coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        coroutineScope.launch {
            dnsCryptEndpointDAO.deleteOlderData(date)
        }
    }

    fun getDNSCryptEndpointLiveDataByName(query: String): LiveData<PagedList<DNSCryptEndpoint>> {
        return dnsCryptEndpointDAO.getDNSCryptEndpointLiveDataByName(query).toLiveData(
            pageSize = LIVEDATA_PAGE_SIZE)
    }

    fun deleteDNSCryptEndpoint(id: Int) {
        dnsCryptEndpointDAO.deleteDNSCryptEndpoint(id)
    }

    fun removeConnectionStatus() {
        dnsCryptEndpointDAO.removeConnectionStatus()
    }

    fun getConnectedDNSCrypt(): List<DNSCryptEndpoint> {
        return dnsCryptEndpointDAO.getConnectedDNSCrypt()
    }

    fun getConnectedCount(): Int {
        return dnsCryptEndpointDAO.getConnectedCount()
    }

    fun getConnectedCountLiveData(): LiveData<Int> {
        return dnsCryptEndpointDAO.getConnectedCountLiveData()
    }

    fun getCount(): Int {
        return dnsCryptEndpointDAO.getCount()
    }

    fun updateConnectionStatus(liveServersID: String?) {
        removeConnectionStatus()
        liveServersID?.split(",")?.forEach {
            dnsCryptEndpointDAO.updateConnectionStatus(it.trim().toInt())
        }
    }

    fun updateFailingConnections() {
        dnsCryptEndpointDAO.updateFailingConnections()
    }

    fun getServersToAdd(): String {
        val servers = getConnectedDNSCrypt().joinToString(separator = ",") {
            "${it.id}#${it.dnsCryptURL}"
        }
        Log.i(LoggerConstants.LOG_TAG_APP_MODE, "Crypt Server: $servers")
        return servers
    }

    fun getServersToRemove(): String {
        return getConnectedDNSCrypt().joinToString(separator = ",") { "${it.id}" }
    }
}
