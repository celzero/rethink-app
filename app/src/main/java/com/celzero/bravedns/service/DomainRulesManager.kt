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
package com.celzero.bravedns.service

import android.util.Log
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import com.google.common.net.InternetDomainName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.regex.Pattern
import kotlin.concurrent.write

object DomainRulesManager : KoinComponent {

    private val customDomainsRepository by inject<CustomDomainRepository>()
    private val lock = ReentrantReadWriteLock()

    // max size of ip request look-up cache
    private const val CACHE_MAX_SIZE = 10000L

    var domains: HashMap<String, CustomDomain> = HashMap()
    var wildcards: HashMap<String, CustomDomain> = HashMap()
    var tlds: HashMap<String, CustomDomain> = HashMap()

    // stores all the previous response sent
    private val domainLookupCache: Cache<String, DomainStatus> = CacheBuilder.newBuilder().maximumSize(
        CACHE_MAX_SIZE
    ).build()

    enum class DomainStatus(val id: Int) {
        NONE(0), BLOCK(1), WHITELIST(2);

        companion object {
            fun getStatus(statusId: Int): DomainStatus {
                return when (statusId) {
                    NONE.id -> NONE
                    WHITELIST.id -> WHITELIST
                    BLOCK.id -> BLOCK
                    else -> NONE
                }
            }
        }
    }

    enum class DomainType(val id: Int) {
        DOMAIN(0), WILDCARD(1), TLD(2);

        companion object {
            fun getAllDomainTypes(): Array<String> {
                return arrayOf("Domain", "Wildcard", "TLD")
            }

            fun getType(id: Int): DomainType {
                return when (id) {
                    TLD.id -> TLD
                    DOMAIN.id -> DOMAIN
                    WILDCARD.id -> WILDCARD
                    else -> DOMAIN
                }
            }
        }
    }

    // three diff lists are maintained for the custom domains
    // add to the appropriate list based on the type
    private fun updateCache(cd: CustomDomain) {
        when (DomainType.getType(cd.type)) {
            DomainType.DOMAIN -> {
                lock.write {
                    domains[cd.domain] = cd
                }
            }
            DomainType.TLD -> {
                lock.write {
                    tlds[cd.domain] = cd
                }
            }
            DomainType.WILDCARD -> {
                lock.write {
                    wildcards[cd.domain] = cd
                }
            }
        }
        domainLookupCache.invalidateAll()
    }

    fun load() {
        val cd = customDomainsRepository.getAllCustomDomains()
        if (cd.isEmpty()) {
            Log.w(LOG_TAG_DNS, "no custom domains found in db")
            return
        }

        cd.forEach {
            updateCache(it)
        }
    }

    data class Test(val type: String, val domain: DomainStatus)

    fun status(domain: String): Test {
        // return if the cache has the domain
        domainLookupCache.getIfPresent(domain)?.let {
            return Test("Cache", DomainStatus.getStatus(it.id))
        }

        // check if the domain is added in custom domain list
        when (matchesDomain(domain)) {
            DomainStatus.WHITELIST -> {
                updateLookupCache(domain, DomainStatus.WHITELIST)
                return Test("Domain", DomainStatus.WHITELIST)
            }
            DomainStatus.BLOCK -> {
                updateLookupCache(domain, DomainStatus.BLOCK)
                return Test("Domain", DomainStatus.BLOCK)
            }
            DomainStatus.NONE -> {
                // fall-through
            }
        }

        // extract the TLD of the received domain and check with the custom tld's list
        when (matchesTld(domain)) {
            DomainStatus.BLOCK -> {
                updateLookupCache(domain, DomainStatus.BLOCK)
                return Test("TLD", DomainStatus.BLOCK)
            }
            DomainStatus.WHITELIST -> {
                updateLookupCache(domain, DomainStatus.WHITELIST)
                return Test("TLD", DomainStatus.WHITELIST)
            }
            DomainStatus.NONE -> {
                // fall-through
            }
        }

        // check if the received domain is matching with the custom wildcard
        val match = matchesWildcard(domain)
        updateLookupCache(domain, match)
        return Test("Wildcard", match)
    }

    private fun matchesWildcard(recvDomain: String): DomainStatus {
        wildcards.forEach {
            // replaces the . from the input to [\\.], as regEx will treat . as spl
            val temp = it.key.replace(".", "[\\\\.]")
            // add ^ and $ in the start and end
            // replace * with .* (regEx format)
            val w = "^" + temp.replace("*", ".*") + "$"
            val pattern = Pattern.compile(w)
            if (pattern.matcher(recvDomain).matches()) {
                return DomainStatus.getStatus(it.value.status)
            }
        }

        return DomainStatus.NONE
    }

    private fun matchesTld(recvDomain: String): DomainStatus {
        // ref: https://guava.dev/releases/snapshot-jre/api/docs/com/google/common/net/InternetDomainName.html
        try {
            // Returns the public suffix portion of the domain name, or null if no public suffix is present
            val recvDomainTld = InternetDomainName.from(recvDomain).publicSuffix()
            val domain = recvDomainTld?.let { tlds.getValue(it.toString()) }
            return domain?.let { it -> DomainStatus.getStatus(it.status) } ?: DomainStatus.NONE
        } catch (ignored: NoSuchElementException) {
            // no-op
            // exception if there is no such key in the map (tlds)
        } catch (ignored: IllegalArgumentException) {
            // no-op
            // from(String) will throw IllegalArgumentException if domain is not syntactically valid
            // some of the apps are sending queries which are not valid
            // eg., '_sips._tcp.in.airtel.rcs.telephony.goog' from OPPO phone
        }
        return DomainStatus.NONE
    }

    fun matchesDomain(recvDomain: String): DomainStatus {
        val d = domains.getOrElse(recvDomain) { return DomainStatus.NONE }
        return DomainStatus.getStatus(d.status)
    }

    private fun updateLookupCache(domain: String, status: DomainStatus) {
        domainLookupCache.put(domain, status)
    }

    fun whitelist(cd: CustomDomain) {
        io {
            cd.status = DomainStatus.WHITELIST.id
            dbInsertOrUpdate(cd)
            updateCache(cd)
        }
    }

    fun applyStatus(domain: String, ips: String, type: DomainType, status: DomainStatus) {
        io {
            val cd = constructObject(domain, ips, type, status.id)
            dbInsertOrUpdate(cd)
            updateCache(cd)
        }
    }

    fun block(domain: String, ips: String = "", type: DomainType) {
        io {
            val cd = constructObject(domain, ips, type, DomainStatus.BLOCK.id)
            dbInsertOrUpdate(cd)
            updateCache(cd)
        }
    }

    fun block(cd: CustomDomain) {
        io {
            cd.status = DomainStatus.BLOCK.id
            dbInsertOrUpdate(cd)
            updateCache(cd)
        }
    }

    fun noRule(cd: CustomDomain) {
        io {
            cd.status = DomainStatus.NONE.id
            dbInsertOrUpdate(cd)
            updateCache(cd)
        }
    }

    private suspend fun dbInsertOrUpdate(cd: CustomDomain) {
        customDomainsRepository.insert(cd)
    }

    private suspend fun dbDelte(cd: CustomDomain) {
        customDomainsRepository.delete(cd)
    }

    fun deleteDomain(cd: CustomDomain) {
        io {
            dbDelte(cd)
            removeFromCache(cd)
        }
    }

    private fun removeFromCache(cd: CustomDomain) {
        when (DomainType.getType(cd.type)) {
            DomainType.DOMAIN -> {
                lock.write {
                    domains.remove(cd.domain)
                }
            }
            DomainType.TLD -> {
                lock.write {
                    tlds.remove(cd.domain)
                }
            }
            DomainType.WILDCARD -> {
                lock.write {
                    wildcards.remove(cd.domain)
                }
            }
        }
        domainLookupCache.invalidateAll()
    }

    private fun constructObject(domain: String, ips: String = "", type: DomainType,
                                status: Int): CustomDomain {
        return CustomDomain(domain, ips, type.id, status, Calendar.getInstance().timeInMillis,
                            Constants.INIT_TIME_MS, CustomDomain.getCurrentVersion())
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            f()
        }
    }
}
