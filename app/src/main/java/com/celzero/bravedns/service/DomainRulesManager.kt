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

import android.content.Context
import android.util.Log
import android.util.Patterns
import androidx.lifecycle.LiveData
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS
import com.google.common.cache.Cache
import com.google.common.cache.CacheBuilder
import java.net.MalformedURLException
import java.util.*
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.regex.Pattern
import kotlin.concurrent.write
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object DomainRulesManager : KoinComponent {

    private val customDomainsRepository by inject<CustomDomainRepository>()
    private val lock = ReentrantReadWriteLock()

    // max size of ip request look-up cache
    private const val CACHE_MAX_SIZE = 10000L

    data class CacheKey(val domain: String, val uid: Int)

    var domains: MutableMap<CacheKey, CustomDomain> = hashMapOf()
    var wildcards: MutableMap<CacheKey, CustomDomain> = hashMapOf()

    // stores all the previous response sent
    private val domainLookupCache: Cache<CacheKey, Status> =
        CacheBuilder.newBuilder().maximumSize(CACHE_MAX_SIZE).build()

    init {
        io { load() }
    }

    enum class Status(val id: Int) {
        NONE(0),
        BLOCK(1),
        TRUST(2);

        companion object {

            fun getLabel(context: Context): Array<String> {
                return arrayOf(
                    context.getString(R.string.ci_no_rule),
                    context.getString(R.string.ci_block),
                    context.getString(R.string.ci_trust_rule)
                )
            }

            fun getStatus(statusId: Int): Status {
                return when (statusId) {
                    NONE.id -> NONE
                    TRUST.id -> TRUST
                    BLOCK.id -> BLOCK
                    else -> NONE
                }
            }
        }
    }

    enum class DomainType(val id: Int) {
        DOMAIN(0),
        WILDCARD(1);

        companion object {
            fun getType(id: Int): DomainType {
                return when (id) {
                    DOMAIN.id -> DOMAIN
                    WILDCARD.id -> WILDCARD
                    else -> DOMAIN
                }
            }
        }
    }

    // update the cache with the domain and its status based on the domain type
    private fun updateCache(cd: CustomDomain) {
        when (DomainType.getType(cd.type)) {
            DomainType.DOMAIN -> {
                val key = CacheKey(cd.domain.lowercase(Locale.ROOT), cd.uid)
                lock.write { domains[key] = cd }
            }
            DomainType.WILDCARD -> {
                val d = constructWildCardString(cd.domain.lowercase(Locale.ROOT))
                val key = CacheKey(d, cd.uid)
                lock.write { wildcards[key] = cd }
            }
        }
        domainLookupCache.invalidateAll()
    }

    suspend fun load() {
        if (domains.isNotEmpty() || wildcards.isNotEmpty()) {
            return
        }

        val customDomains = customDomainsRepository.getAllCustomDomains()
        if (customDomains.isEmpty()) {
            Log.w(LOG_TAG_DNS, "no custom domains found in db")
            return
        }

        // sort the custom domains based on the length of the domain
        val selector: (String) -> Int = { str -> str.length }
        val sortedDomains = customDomains.sortedByDescending { selector(it.domain) }
        sortedDomains.forEach { cd ->
            when (DomainType.getType(cd.type)) {
                DomainType.DOMAIN -> {
                    val key = CacheKey(cd.domain.lowercase(Locale.ROOT), cd.uid)
                    domains[key] = cd
                }
                DomainType.WILDCARD -> {
                    val d = constructWildCardString(cd.domain.lowercase(Locale.ROOT))
                    val key = CacheKey(d, cd.uid)
                    wildcards[key] = cd
                }
            }
        }
    }

    fun status(d: String, uid: Int): Status {
        val domain = d.lowercase(Locale.ROOT)
        // return if the cache has the domain
        domainLookupCache.getIfPresent(domain)?.let {
            return Status.getStatus(it.id)
        }

        // check if the domain is added in custom domain list
        when (getDomainRule(domain, uid)) {
            Status.TRUST -> {
                updateLookupCache(domain, uid, Status.TRUST)
                return Status.TRUST
            }
            Status.BLOCK -> {
                updateLookupCache(domain, uid, Status.BLOCK)
                return Status.BLOCK
            }
            Status.NONE -> {
                // fall-through
            }
        }

        // check if the received domain is matching with the custom wildcard
        val match = matchesWildcard(domain, uid)
        updateLookupCache(domain, uid, match)
        return match
    }

    private fun matchesWildcard(domain: String, uid: Int): Status {
        wildcards.forEach {
            if (it.key.uid != uid) {
                return@forEach
            }

            val pattern = Pattern.compile(it.key.domain)
            if (pattern.matcher(domain).matches()) {
                return Status.getStatus(it.value.status)
            }
        }

        return Status.NONE
    }

    fun getDomainRule(domain: String, uid: Int): Status {
        val key = CacheKey(domain.lowercase(Locale.ROOT), uid)
        val d =
            domains.getOrElse(key) {
                return Status.NONE
            }
        return Status.getStatus(d.status)
    }

    private fun updateLookupCache(domain: String, uid: Int, status: Status) {
        val key = CacheKey(domain, uid)
        domainLookupCache.put(key, status)
    }

    fun whitelist(cd: CustomDomain) {
        io {
            cd.status = Status.TRUST.id
            dbInsertOrUpdate(cd)
            updateCache(cd)
        }
    }

    fun changeStatus(domain: String, uid: Int, ips: String, type: DomainType, status: Status) {
        io {
            val cd = constructObject(domain, uid, ips, type, status.id)
            dbInsertOrUpdate(cd)
            updateCache(cd)
        }
    }

    fun block(domain: String, uid: Int, ips: String = "", type: DomainType) {
        io {
            val cd = constructObject(domain, uid, ips, type, Status.BLOCK.id)
            dbInsertOrUpdate(cd)
            updateCache(cd)
        }
    }

    fun block(cd: CustomDomain) {
        io {
            cd.status = Status.BLOCK.id
            dbInsertOrUpdate(cd)
            updateCache(cd)
        }
    }

    fun noRule(cd: CustomDomain) {
        io {
            cd.status = Status.NONE.id
            dbInsertOrUpdate(cd)
            updateCache(cd)
        }
    }

    fun addDomainRule(d: String, status: Status, type: DomainType = DomainType.DOMAIN, uid: Int) {
        io {
            val cd = constructObject(d, uid, "", type, status.id)
            dbInsertOrUpdate(cd)
            updateCache(cd)
        }
    }

    fun updateDomainRule(d: String, status: Status, type: DomainType, prevDomain: CustomDomain) {
        io {
            val cd = constructObject(d, prevDomain.uid, "", type, status.id)
            dbUpdate(prevDomain, cd)
            updateCache(cd)
        }
    }

    private suspend fun dbInsertOrUpdate(cd: CustomDomain) {
        customDomainsRepository.insert(cd)
    }

    private suspend fun dbUpdate(prevDomain: CustomDomain, cd: CustomDomain) {
        customDomainsRepository.update(prevDomain, cd)
    }

    private suspend fun dbDelete(cd: CustomDomain) {
        customDomainsRepository.delete(cd)
    }

    fun deleteDomain(cd: CustomDomain) {
        io {
            dbDelete(cd)
            removeFromCache(cd)
        }
    }

    fun deleteIpRulesByUid(uid: Int) {
        io {
            customDomainsRepository.deleteRulesByUid(uid)
            domains = domains.filterKeys { it.uid != uid } as MutableMap<CacheKey, CustomDomain>
            wildcards = wildcards.filterKeys { it.uid != uid } as MutableMap<CacheKey, CustomDomain>
            domainLookupCache.invalidateAll()
        }
    }

    private fun removeFromCache(cd: CustomDomain) {
        when (DomainType.getType(cd.type)) {
            DomainType.DOMAIN -> {
                val key = CacheKey(cd.domain, cd.uid)
                lock.write { domains.remove(key) }
            }
            DomainType.WILDCARD -> {
                val d = constructWildCardString(cd.domain)
                val key = CacheKey(d, cd.uid)
                lock.write { wildcards.remove(key) }
            }
        }
        domainLookupCache.invalidateAll()
    }

    private fun constructWildCardString(d: String): String {
        // replaces the . from the input to [\\.], as regEx will treat . as spl
        val temp = d.replace(".", "[\\\\.]")
        // add ^ and $ in the start and end
        // replace * with .* (regEx format)
        return "^" + temp.replace("*", ".*") + "$"
    }

    fun getUniversalCustomDomainCount(): LiveData<Int> {
        return customDomainsRepository.getUniversalCustomDomainCount()
    }

    fun isValidDomain(url: String): Boolean {
        return try {
            Patterns.WEB_URL.matcher(url).matches() || Patterns.DOMAIN_NAME.matcher(url).matches()
        } catch (ignored: MalformedURLException) { // ignored
            false
        }
    }

    fun isWildCardEntry(url: String): Boolean {
        // regex to check if url is valid wildcard domain
        // valid wildcard domain: *.example.com, *.example.co.in, *.do-main.com
        // RFC 1035: https://tools.ietf.org/html/rfc1035#section-2.3.4
        val p = Pattern.compile("^(\\*\\.)?([a-zA-Z0-9-]+\\.)+[a-zA-Z0-9-]+$")
        return p.matcher(url).matches()
    }

    private fun constructObject(
        domain: String,
        uid: Int,
        ips: String = "",
        type: DomainType,
        status: Int
    ): CustomDomain {
        return CustomDomain(
            domain,
            uid,
            ips,
            type.id,
            status,
            Calendar.getInstance().timeInMillis,
            Constants.INIT_TIME_MS,
            CustomDomain.getCurrentVersion()
        )
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
