/*
 * Copyright 2021 RethinkDNS and its authors
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

import androidx.lifecycle.LiveData

class CustomIpRepository(private val customIpDao: CustomIpDao) {

    suspend fun update(customIp: CustomIp) {
        customIpDao.update(customIp)
    }

    suspend fun insert(customIp: CustomIp) {
        customIpDao.insert(customIp)
    }

    suspend fun updateUid(uid: Int, newUid: Int) {
        customIpDao.updateUid(uid, newUid)
    }

    suspend fun getIpRules(): List<CustomIp> {
        return customIpDao.getCustomIpRules()
    }

    suspend fun getCustomIpDetail(uid: Int, ipAddress: String, port: Int): CustomIp? {
        return customIpDao.getCustomIpDetail(uid, ipAddress, port)
    }

    suspend fun deleteRule(uid: Int, ipAddress: String, port: Int): Int {
        return customIpDao.deleteRule(uid, ipAddress, port)
    }

    suspend fun deleteRulesByUid(uid: Int) {
        customIpDao.deleteRulesByUid(uid)
    }

    suspend fun getRulesByUid(uid: Int): List<CustomIp> {
        return customIpDao.getRulesByUid(uid)
    }

    fun getCustomIpsLiveData(): LiveData<Int> {
        return customIpDao.getCustomIpsLiveData()
    }

    suspend fun deleteAllAppsRules() {
        customIpDao.deleteAllAppsRules()
    }
}
