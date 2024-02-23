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

import android.database.Cursor
import androidx.lifecycle.LiveData
import androidx.room.Transaction
import com.celzero.bravedns.util.Constants

class CustomDomainRepository(private val customDomainDAO: CustomDomainDAO) {
    suspend fun update(customDomain: CustomDomain) {
        customDomainDAO.update(customDomain)
    }

    suspend fun insert(customDomain: CustomDomain) {
        customDomainDAO.insert(customDomain)
    }

    suspend fun delete(customDomain: CustomDomain) {
        customDomainDAO.delete(customDomain)
    }

    @Transaction
    suspend fun update(prevDomain: CustomDomain, newDomain: CustomDomain) {
        customDomainDAO.delete(prevDomain)
        customDomainDAO.insert(newDomain)
    }

    fun getAllCustomDomains(): List<CustomDomain> {
        return customDomainDAO.getAllDomains()
    }

    fun getDomainsByUID(uid: Int): List<CustomDomain> {
        return customDomainDAO.getDomainsByUID(uid)
    }

    suspend fun deleteRulesByUid(uid: Int) {
        customDomainDAO.deleteRulesByUid(uid)
    }

    fun deleteAllRules() {
        customDomainDAO.deleteAllRules()
    }

    fun getUniversalCustomDomainCount(): LiveData<Int> {
        // get the count of the universal rules
        return customDomainDAO.getAppWiseDomainRulesCount(Constants.UID_EVERYBODY)
    }

    fun updateUid(uid: Int, newUid: Int) {
        customDomainDAO.updateUid(uid, newUid)
    }

    fun cpInsert(customDomain: CustomDomain): Long {
        return customDomainDAO.insert(customDomain)
    }

    fun cpDelete(domain: String, uid: Int): Int {
        return customDomainDAO.deleteDomain(domain, uid)
    }

    fun cpUpdate(customDomain: CustomDomain): Int {
        return customDomainDAO.update(customDomain)
    }

    fun cpUpdate(customDomain: CustomDomain, clause: String): Int {
        // update only status of the domain
        return customDomainDAO.cpUpdate(customDomain.status, clause)
    }

    fun getRulesCursor(): Cursor {
        return customDomainDAO.getRulesCursor()
    }
}
