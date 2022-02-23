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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch


class CustomIpRepository(private val customIpDao: CustomIpDao) {

    fun update(customIp: CustomIp) {
        customIpDao.update(customIp)
    }

    fun clearFirewallRules(uid: Int) {
        customIpDao.clearIpRuleByUid(uid)
    }

    fun insert(customIp: CustomIp) {
        customIpDao.insert(customIp)
    }

    fun getIpRules(): List<CustomIp> {
        return customIpDao.getFirewallRules()
    }

    fun getCustomIpDetail(uid: Int, ipAddress: String): CustomIp? {
        return customIpDao.getCustomIpDetail(uid, ipAddress)
    }

    fun deleteIPRulesForUID(uid: Int, ipAddress: String) {
        customIpDao.deleteIPRulesForUID(uid, ipAddress)
    }

    fun deleteIpRule(customIp: CustomIp) {
        customIpDao.delete(customIp)
    }

    fun deleteAllIPRulesUniversal(coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.IO)) {
        coroutineScope.launch {
            customIpDao.deleteAllIPRulesUniversal()
        }
    }

    fun getBlockedConnectionsCount(): Int {
        return customIpDao.getBlockedConnectionsCount()
    }

    fun getBlockedConnectionCountLiveData(): LiveData<Int> {
        return customIpDao.getBlockedConnectionCountLiveData()
    }
}
