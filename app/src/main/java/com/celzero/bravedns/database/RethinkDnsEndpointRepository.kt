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


class RethinkDnsEndpointRepository(private val rethinkDnsEndpointDao: RethinkDnsEndpointDao) {

    @Transaction
    fun update(rethinkDnsEndpoint: RethinkDnsEndpoint) {
        rethinkDnsEndpointDao.removeConnectionStatus()
        rethinkDnsEndpointDao.update(rethinkDnsEndpoint)
    }

    fun insertWithReplace(rethinkDnsEndpoint: RethinkDnsEndpoint) {
        rethinkDnsEndpointDao.insertReplace(rethinkDnsEndpoint)
    }

    fun removeConnectionStatus() {
        rethinkDnsEndpointDao.removeConnectionStatus()
    }

    fun removeAppWiseDns(uid: Int) {
        rethinkDnsEndpointDao.removeAppWiseDns(uid)
    }

    fun isAppWiseDnsEnabled(uid: Int): Boolean {
        return rethinkDnsEndpointDao.isAppWiseDnsEnabled(uid) ?: false
    }

    fun getConnectedEndpoint(): RethinkDnsEndpoint? {
        return rethinkDnsEndpointDao.getConnectedEndpoint()
    }

    fun updateConnectionDefault() {
        rethinkDnsEndpointDao.removeConnectionStatus()
        rethinkDnsEndpointDao.updateConnectionDefault()
    }

    fun setRethinkPlus() {
        rethinkDnsEndpointDao.removeConnectionStatus()
        rethinkDnsEndpointDao.setRethinkPlus()
    }

    fun getCount(): Int {
        return rethinkDnsEndpointDao.getCount()
    }

    fun updatePlusBlocklistCount(count: Int) {
        rethinkDnsEndpointDao.updatePlusBlocklistCount(count)
    }

    fun updateEndpoint(name: String, url: String, count: Int) {
        rethinkDnsEndpointDao.updateEndpoint(name, url, count)
    }

    fun getRethinkPlusEndpoint(): RethinkDnsEndpoint {
        return rethinkDnsEndpointDao.getRethinkPlusEndpoint()
    }

    fun switchToMax() {
        rethinkDnsEndpointDao.switchToMax()
    }

    fun switchToSky() {
        rethinkDnsEndpointDao.switchToSky()
    }
}
