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
import dnsx.Dnsx
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.MalformedURLException
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.regex.Pattern
import kotlin.concurrent.write

object DomainRulesManager : KoinComponent {

    private val customDomainsRepository by inject<CustomDomainRepository>()
    private val lock = ReentrantReadWriteLock()

    // max size of ip request look-up cache
    private const val CACHE_MAX_SIZE = 10000L

    data class CacheKey(val domain: String, val uid: Int)

    var domains: MutableMap<CacheKey, CustomDomain> = hashMapOf()
    var trustedDomains: MutableSet<String> = hashSetOf()
    var trie: dnsx.CritBit = Dnsx.newCritBit()
    private val trustedTrie: dnsx.CritBit = Dnsx.newCritBit()

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
    fun updateCache(cd: CustomDomain) {
        when (DomainType.getType(cd.type)) {
            DomainType.DOMAIN -> {
                val d = cd.domain.lowercase(Locale.ROOT)
                val key = CacheKey(d, cd.uid)
                lock.write { domains[key] = cd }
                if (cd.status == Status.TRUST.id) {
                    lock.write { trustedDomains.add(d) }
                }
            }
            DomainType.WILDCARD -> {
                // key is combination of domain and uid as String separated by a delimiter ,
                val key = getTrieKey(cd.domain.lowercase(Locale.ROOT), cd.uid)
                trie.set(key, cd.status.toString())
                if (cd.status == Status.TRUST.id) {
                    trustedTrie.set(key, cd.status.toString())
                }
            }
        }
        domainLookupCache.invalidateAll()
    }

    private fun getTrieKey(_domain: String, uid: Int): String {
        val domain = _domain.removePrefix("*").removeSuffix("*")
        return domain.lowercase(Locale.ROOT) + "," + uid
    }

    suspend fun load() {
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
                    if (cd.status == Status.TRUST.id) {
                        trustedDomains.add(cd.domain.lowercase(Locale.ROOT))
                    }
                }
                DomainType.WILDCARD -> {
                    val key = getTrieKey(cd.domain.lowercase(Locale.ROOT), cd.uid)
                    trie.set(key, cd.status.toString())
                    if (cd.status == Status.TRUST.id) {
                        trustedTrie.set(key, cd.status.toString())
                    }
                }
            }
        }
    }

    fun status(d: String, uid: Int): Status {
        val domain = d.lowercase(Locale.ROOT)
        // return if the cache has the domain
        val key = CacheKey(domain, uid)
        domainLookupCache.getIfPresent(key)?.let {
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
        val key = getTrieKey(domain.lowercase(Locale.ROOT), uid)
        val match = trie.getAny(key)

        if (match.isNullOrEmpty()) {
            return Status.NONE
        }

        return Status.getStatus(match.toInt())
    }

    fun getDomainRule(domain: String, uid: Int): Status {
        val key = CacheKey(domain.lowercase(Locale.ROOT), uid)
        val d =
            domains.getOrElse(key) {
                return Status.NONE
            }
        return Status.getStatus(d.status)
    }

    fun isDomainTrusted(_domain: String?): Boolean {
        if (_domain.isNullOrEmpty()) {
            return false
        }

        val domain = _domain.lowercase(Locale.ROOT)
        if (trustedDomains.contains(domain)) {
            return true
        }

        if (
            domainLookupCache.getIfPresent(CacheKey(domain, Constants.UID_EVERYBODY))?.id ==
                Status.TRUST.id
        ) {
            return true
        }

        val key = getTrieKey(domain, Constants.UID_EVERYBODY)
        return trustedTrie.hasAny(key)
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
            // find the domains that are for the uid and remove them from domains
            val domainsToDelete = domains.filterKeys { it.uid == uid }.toMutableMap()
            // find the domains that are in delete list and remove them from trusted domains
            val trustedDomainsToDelete = domainsToDelete.filterValues { it.status == Status.TRUST.id }

            customDomainsRepository.deleteRulesByUid(uid)
            domains.entries.removeAll(domainsToDelete.entries)
            trustedDomains.removeAll(trustedDomainsToDelete.keys.map { it.domain.lowercase(Locale.ROOT) }
                .toSet())
            val rulesDeleted = trie.delAll(uid.toString())
            val trustedRulesDeleted = trustedTrie.delAll(uid.toString())
            Log.i(LOG_TAG_DNS, "Deleted $rulesDeleted rules from trie and $trustedRulesDeleted rules from trustedTrie")
            domainLookupCache.invalidateAll()
        }
    }

    private fun removeFromCache(cd: CustomDomain) {
        when (DomainType.getType(cd.type)) {
            DomainType.DOMAIN -> {
                val d = cd.domain.lowercase(Locale.ROOT)
                val key = CacheKey(d, cd.uid)
                lock.write { domains.remove(key) }
                if (cd.status == Status.TRUST.id) {
                    lock.write { trustedDomains.remove(d) }
                }
            }
            DomainType.WILDCARD -> {
                val key = getTrieKey(cd.domain.lowercase(Locale.ROOT), cd.uid)
                trie.del(key)
                if (cd.status == Status.TRUST.id) {
                    trustedTrie.del(key)
                }
            }
        }
        domainLookupCache.invalidateAll()
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
