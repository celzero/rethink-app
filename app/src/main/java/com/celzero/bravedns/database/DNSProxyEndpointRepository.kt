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


class DNSProxyEndpointRepository(private val dnsProxyEndpointDAO: DNSProxyEndpointDAO) {

    companion object {
        const val INTERNAL = "Internal"
        const val EXTERNAL = "External"
    }

    @Transaction
    fun update(dnsProxyEndpoint: DNSProxyEndpoint) {
        dnsProxyEndpointDAO.removeConnectionStatus()
        dnsProxyEndpointDAO.update(dnsProxyEndpoint)
    }

    fun deleteAsync(dnsProxyEndpoint: DNSProxyEndpoint,
                    coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            dnsProxyEndpointDAO.delete(dnsProxyEndpoint)
        }
    }

    fun insertAsync(dnsCryptEndpoint: DNSProxyEndpoint,
                    coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            dnsProxyEndpointDAO.insert(dnsCryptEndpoint)
        }
    }

    fun insertWithReplace(dnsProxyEndpoint: DNSProxyEndpoint,
                          coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            dnsProxyEndpointDAO.insertWithReplace(dnsProxyEndpoint)
        }
    }

    fun getDNSProxyEndpointLiveData(): LiveData<PagedList<DNSProxyEndpoint>> {
        return dnsProxyEndpointDAO.getDNSProxyEndpointLiveData().toLiveData(
            pageSize = LIVEDATA_PAGE_SIZE)
    }

    fun deleteOlderData(date: Long, coroutineScope: CoroutineScope = GlobalScope) {
        coroutineScope.launch {
            dnsProxyEndpointDAO.deleteOlderData(date)
        }
    }

    fun getDNSProxyEndpointLiveDataByType(query: String): LiveData<PagedList<DNSProxyEndpoint>> {
        return dnsProxyEndpointDAO.getDNSProxyEndpointLiveDataByType(query).toLiveData(
            pageSize = LIVEDATA_PAGE_SIZE)
    }

    fun deleteDNSProxyEndpoint(id: Int) {
        dnsProxyEndpointDAO.deleteDNSProxyEndpoint(id)
    }

    fun removeConnectionStatus() {
        dnsProxyEndpointDAO.removeConnectionStatus()
    }

    fun getCount(): Int {
        return dnsProxyEndpointDAO.getCount()
    }

    fun getConnectedProxy(): DNSProxyEndpoint {
        return dnsProxyEndpointDAO.getConnectedProxy()
    }

}
