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
package com.celzero.bravedns.automaton

import android.util.Log
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.sql.Date
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.collections.HashMap
import kotlin.concurrent.read
import kotlin.concurrent.write

object CustomDomainManager : KoinComponent {

    private val customDomainsRepository by inject<CustomDomainRepository>()
    private val lock = ReentrantReadWriteLock()

    enum class CustomDomainStatus(val statusId: Int) {
        NONE(0), WHITELIST(1), BLOCKLIST(2);

        companion object {
            fun getStatus(statusId: Int): CustomDomainStatus {
                return when (statusId) {
                    NONE.statusId -> NONE
                    WHITELIST.statusId -> WHITELIST
                    BLOCKLIST.statusId -> BLOCKLIST
                    else -> NONE
                }
            }
        }
    }

    var domains: HashMap<String, CustomDomain> = HashMap()

    private fun updateCache(cd: CustomDomain) {
        lock.write {
            domains.put(cd.domain, cd)
        }
    }

    fun load() {
        val cd = customDomainsRepository.getAllCustomDomains()
        if (cd.isEmpty()) {
            Log.w(LOG_TAG_DNS, "no custom domains found in db")
            return
        }

        lock.write {
            domains.clear()
            cd.forEach {
                domains[it.domain] = it
            }
        }
    }

    private fun hasDomain(domain: String): Boolean {
        lock.read {
            return domains.containsKey(domain)
        }
    }

    private fun domainStatus(domain: String): CustomDomainStatus {
        lock.read {
            val d = domains.getOrElse(domain) { return CustomDomainStatus.NONE }
            return CustomDomainStatus.getStatus(d.status)
        }
    }

    fun isDomainWhitelisted(domain: String): Boolean {
        lock.read {
            val d = domains.getOrElse(domain) { return false }
            return d.isWhitelisted()
        }
    }

    fun isDomainBlocked(domain: String): Boolean {
        lock.read {
            val d = domains.getOrElse(domain) { return false }
            return d.isBlocked()
        }
    }

    fun whitelist(domain: String, ips: String) {
        val cd = constructObject(domain, ips, CustomDomainStatus.WHITELIST.statusId)
        io {
            insertOrUpdate(cd)
        }
    }

    fun toggleStatus(cd: CustomDomain, state: CustomDomainStatus) {
        if (cd.isWhitelisted() && state == CustomDomainStatus.WHITELIST) {
            cd.status = CustomDomainStatus.NONE.statusId
        } else if (cd.isBlocked() && state == CustomDomainStatus.BLOCKLIST) {
            cd.status = CustomDomainStatus.NONE.statusId
        } else {
            cd.status = state.statusId
        }
        io {
            insertOrUpdate(cd)
        }
    }

    fun removeStatus(domain: String, ips: String) {
        val cd = constructObject(domain, ips, CustomDomainStatus.NONE.statusId)
        io {
            insertOrUpdate(cd)
        }
    }

    fun blocklist(domain: String, ips: String) {
        val cd = constructObject(domain, ips, CustomDomainStatus.BLOCKLIST.statusId)
        io {
            insertOrUpdate(cd)
        }
    }

    private suspend fun insertOrUpdate(cd: CustomDomain) {
        if (hasDomain(cd.domain)) {
            customDomainsRepository.update(cd)
        } else {
            customDomainsRepository.insert(cd)
        }
        updateCache(cd)
    }

    fun deleteDomain(cd: CustomDomain) {
        removeFromCache(cd)
        io {
            customDomainsRepository.delete(cd)
        }
    }

    private fun removeFromCache(cd: CustomDomain) {
        lock.write {
            domains.remove(cd.domain)
        }
    }

    private fun constructObject(domain: String, ips: String, status: Int): CustomDomain {
        return CustomDomain(domain, ips, status, Date(Calendar.getInstance().timeInMillis),
                            Date(Constants.INIT_TIME_MS), CustomDomain.getCurrentVersion())
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            f()
        }
    }
}
