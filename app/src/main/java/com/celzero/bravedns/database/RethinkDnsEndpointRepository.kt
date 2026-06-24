/*
 * Copyright 2022 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.celzero.bravedns.database

import androidx.room.Transaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RethinkDnsEndpointRepository(private val rethinkDnsEndpointDao: RethinkDnsEndpointDao) {

    @Transaction
    suspend fun update(rethinkDnsEndpoint: RethinkDnsEndpoint) {
        withContext(Dispatchers.IO) {
            rethinkDnsEndpointDao.removeConnectionStatus()
            rethinkDnsEndpointDao.update(rethinkDnsEndpoint)
        }
    }

    suspend fun insertWithReplace(rethinkDnsEndpoint: RethinkDnsEndpoint) {
        withContext(Dispatchers.IO) { rethinkDnsEndpointDao.insertReplace(rethinkDnsEndpoint) }
    }

    suspend fun removeConnectionStatus() {
        withContext(Dispatchers.IO) { rethinkDnsEndpointDao.removeConnectionStatus() }
    }

    suspend fun removeAppWiseDns(uid: Int) {
        withContext(Dispatchers.IO) { rethinkDnsEndpointDao.removeAppWiseDns(uid) }
    }

    suspend fun isAppWiseDnsEnabled(uid: Int): Boolean {
        return withContext(Dispatchers.IO) { rethinkDnsEndpointDao.isAppWiseDnsEnabled(uid) ?: false }
    }

    suspend fun getConnectedEndpoint(): RethinkDnsEndpoint? {
        return withContext(Dispatchers.IO) { rethinkDnsEndpointDao.getConnectedEndpoint() }
    }

    suspend fun getDefaultRethinkEndpoint(): RethinkDnsEndpoint? {
        return withContext(Dispatchers.IO) { rethinkDnsEndpointDao.getDefaultRethinkEndpoint() }
    }

    suspend fun updateConnectionDefault() {
        withContext(Dispatchers.IO) {
            rethinkDnsEndpointDao.removeConnectionStatus()
            rethinkDnsEndpointDao.updateConnectionDefault()
        }
    }

    suspend fun setRethinkPlus() {
        withContext(Dispatchers.IO) {
            rethinkDnsEndpointDao.removeConnectionStatus()
            rethinkDnsEndpointDao.setRethinkPlus()
        }
    }

    suspend fun getCount(): Int {
        return withContext(Dispatchers.IO) { rethinkDnsEndpointDao.getCount() }
    }

    suspend fun updatePlusBlocklistCount(count: Int) {
        withContext(Dispatchers.IO) { rethinkDnsEndpointDao.updatePlusBlocklistCount(count) }
    }

    suspend fun updateEndpoint(name: String, url: String, count: Int) {
        withContext(Dispatchers.IO) { rethinkDnsEndpointDao.updateEndpoint(name, url, count) }
    }

    suspend fun getRethinkPlusEndpoint(): RethinkDnsEndpoint? {
        return withContext(Dispatchers.IO) { rethinkDnsEndpointDao.getRethinkPlusEndpoint() }
    }

    suspend fun switchToMax() {
        withContext(Dispatchers.IO) { rethinkDnsEndpointDao.switchToMax() }
    }

    suspend fun switchToSky() {
        withContext(Dispatchers.IO) { rethinkDnsEndpointDao.switchToSky() }
    }
}
