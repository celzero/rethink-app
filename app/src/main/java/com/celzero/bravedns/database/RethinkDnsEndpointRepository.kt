/*
Copyright 2022 RethinkDNS and its authors

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

import androidx.room.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext


class RethinkDnsEndpointRepository(private val rethinkDnsEndpointDao: RethinkDnsEndpointDao) {

    @Transaction
    suspend fun update(rethinkDnsEndpoint: RethinkDnsEndpoint) {
        rethinkDnsEndpointDao.removeConnectionStatus()
        rethinkDnsEndpointDao.update(rethinkDnsEndpoint)
    }

    suspend fun insertAsync(rethinkDnsEndpoint: RethinkDnsEndpoint) {
        rethinkDnsEndpointDao.insert(rethinkDnsEndpoint)
    }

    suspend fun insertWithReplace(rethinkDnsEndpoint: RethinkDnsEndpoint) {
        rethinkDnsEndpointDao.insertReplace(rethinkDnsEndpoint)
    }

    suspend fun removeConnectionStatus() {
        rethinkDnsEndpointDao.removeConnectionStatus()
    }

    suspend fun removeAppDns(uid: Int){
        rethinkDnsEndpointDao.removeAppDns(uid)
    }

    suspend fun deleteRethinkEndpoint(name: String, url: String, uid: Int) {
        rethinkDnsEndpointDao.deleteDoHEndpoint(name, url, uid)
    }

    suspend fun isAppDnsEnabled(uid: Int): Boolean {
        return rethinkDnsEndpointDao.isAppDnsEnabled(uid) ?: false
    }

    suspend fun getConnectedEndpoint(): RethinkDnsEndpoint? {
        return rethinkDnsEndpointDao.getConnectedEndpoint()
    }

    suspend fun updateConnectionDefault() {
        rethinkDnsEndpointDao.removeConnectionStatus()
        rethinkDnsEndpointDao.updateConnectionDefault()
    }
}
