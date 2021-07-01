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

import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import androidx.paging.toLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch


class DNSCryptEndpointRepository(private val dnsCryptEndpointDAO: DNSCryptEndpointDAO) {

    fun updateAsync(dnsCryptEndpoint: DNSCryptEndpoint,
                    coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            dnsCryptEndpointDAO.update(dnsCryptEndpoint)
        }
    }

    fun deleteAsync(dnsCryptEndpoint: DNSCryptEndpoint,
                    coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            dnsCryptEndpointDAO.delete(dnsCryptEndpoint)
        }
    }


    fun insertAsync(dnsCryptEndpoint: DNSCryptEndpoint,
                    coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            dnsCryptEndpointDAO.insert(dnsCryptEndpoint)
        }
    }

    fun getDNSCryptEndpointLiveData(): LiveData<PagedList<DNSCryptEndpoint>> {
        return dnsCryptEndpointDAO.getDNSCryptEndpointLiveData().toLiveData(pageSize = 50)
    }

    fun deleteOlderData(date: Long, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            dnsCryptEndpointDAO.deleteOlderData(date)
        }
    }

    fun getDNSCryptEndpointLiveDataByName(query: String): LiveData<PagedList<DNSCryptEndpoint>> {
        return dnsCryptEndpointDAO.getDNSCryptEndpointLiveDataByName(query).toLiveData(
            pageSize = 50)
    }

    fun deleteDNSCryptEndpoint(url: String) {
        dnsCryptEndpointDAO.deleteDNSCryptEndpoint(url)
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

    fun getCount(): Int {
        return dnsCryptEndpointDAO.getCount()
    }

    fun updateConnectionStatus(liveServersID: String) {
        val listServer = liveServersID.split(",")
        removeConnectionStatus()
        listServer.forEach {
            val listServerID = it.toInt()
            dnsCryptEndpointDAO.updateConnectionStatus(listServerID)
        }
    }

    fun updateFailingConnections() {
        dnsCryptEndpointDAO.updateFailingConnections()
    }
}
