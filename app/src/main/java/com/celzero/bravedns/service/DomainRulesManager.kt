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

import Logger
import Logger.LOG_TAG_DNS
import android.content.Context
import android.util.Patterns
import androidx.lifecycle.LiveData
import backend.Backend
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.util.Constants
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.MalformedURLException
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object DomainRulesManager : KoinComponent {

    private val db by inject<CustomDomainRepository>()

    private var trie: backend.RadixTree = Backend.newRadixTree()
    // fixme: find a better way to handle trusted domains without using two data structures
    // map to store the trusted domains with set of uids
    private val trustedMap: MutableMap<String, Set<Int>> = ConcurrentHashMap()
    // even though we have trustedMap, we need to keep the trie for wildcard matching
    private var trustedTrie: backend.RadixTree = Backend.newRadixTree()

    // regex to check if url is valid wildcard domain
    // valid wildcard domain: *.example.com, *.example.co.in, *.do-main.com
    // RFC 1035: https://tools.ietf.org/html/rfc1035#section-2.3.4
    private val wcRegex = Pattern.compile("^(\\*\\.)?([a-zA-Z0-9-]+\\.)+[a-zA-Z0-9-]+$")

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

            fun getStatus(statusId: Int?): Status {
                if (statusId == null) {
                    return NONE
                }

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
    fun updateTrie(cd: CustomDomain) {
        val key = mkTrieKey(cd.domain, cd.uid)
        trie.set(key, cd.status.toString())
    }

    private fun mkTrieKey(d: String, uid: Int): String {
        // *.google.co.uk -> .google.co.uk,<uid>
        // not supported by IpTrie: google.* -> google.,<uid>
        val domain = d.removePrefix("*")
        return domain.lowercase(Locale.ROOT) + "," + uid
    }

    private fun mkTrieKey(d: String): String {
        // *.google.co.uk -> .google.co.uk
        val domain = d.removePrefix("*")
        return domain.lowercase(Locale.ROOT)
    }

    suspend fun load(): Long {
        trie.clear()
        trustedTrie.clear()
        trustedMap.clear()
        db.getAllCustomDomains().forEach { cd ->
            val key = mkTrieKey(cd.domain, cd.uid)
            trie.set(key, cd.status.toString())
            maybeAddToTrustedMap(cd)
        }
        return trie.len()
    }

    private fun maybeAddToTrustedMap(cd: CustomDomain) {
        if (cd.status == Status.TRUST.id) {
            val domain = cd.domain.lowercase(Locale.ROOT)
            val key = mkTrieKey(domain)
            trustedTrie.set(key, cd.status.toString())
            trustedMap[cd.domain] = trustedMap.getOrDefault(domain, emptySet()).plus(cd.uid)
        }
    }

    fun status(d: String, uid: Int): Status {
        val domain = d.lowercase(Locale.ROOT)
        // check if the domain is added in custom domain list
        when (getDomainRule(domain, uid)) {
            Status.TRUST -> {
                return Status.TRUST
            }
            Status.BLOCK -> {
                return Status.BLOCK
            }
            Status.NONE -> {
                // fall-through
            }
        }

        // check if the received domain is matching with the custom wildcard
        return matchesWildcard(domain, uid)
    }

    private fun matchesWildcard(domain: String, uid: Int): Status {
        val key = mkTrieKey(domain, uid)
        val match = trie.getAny(key) // matches the longest prefix

        if (match.isNullOrEmpty()) {
            return Status.NONE
        }

        return Status.getStatus(match.toIntOrNull())
    }

    fun getDomainRule(domain: String, uid: Int): Status {
        val key = mkTrieKey(domain, uid)
        val match = trie.get(key)
        if (match.isNullOrEmpty()) {
            return Status.NONE
        }
        return Status.getStatus(match.toIntOrNull())
    }

    fun isDomainTrusted(d: String?): Boolean {
        if (d.isNullOrEmpty()) {
            return false
        }
        val domain = d.lowercase(Locale.ROOT)
        return trustedTrie.hasAny(domain)
    }

    suspend fun trust(cd: CustomDomain) {
        cd.status = Status.TRUST.id
        cd.modifiedTs = Calendar.getInstance().timeInMillis
        dbInsertOrUpdate(cd)
        updateTrie(cd)
        maybeUpdateTrustedMap(cd.uid, cd.domain, Status.TRUST)
    }

    suspend fun changeStatus(
        domain: String,
        uid: Int,
        ips: String,
        type: DomainType,
        status: Status
    ) {
        val cd = mkCustomDomain(domain, uid, ips, type, status.id)
        dbInsertOrUpdate(cd)
        updateTrie(cd)
        maybeUpdateTrustedMap(uid, domain, status)
    }

    suspend fun block(domain: String, uid: Int, ips: String = "", type: DomainType) {
        val cd = mkCustomDomain(domain, uid, ips, type, Status.BLOCK.id)
        dbInsertOrUpdate(cd)
        updateTrie(cd)
        maybeUpdateTrustedMap(uid, domain, Status.BLOCK)
    }

    suspend fun block(cd: CustomDomain) {
        cd.status = Status.BLOCK.id
        cd.modifiedTs = Calendar.getInstance().timeInMillis
        dbInsertOrUpdate(cd)
        updateTrie(cd)
        maybeUpdateTrustedMap(cd.uid, cd.domain, Status.BLOCK)
    }

    suspend fun noRule(cd: CustomDomain) {
        cd.status = Status.NONE.id
        cd.modifiedTs = Calendar.getInstance().timeInMillis
        dbInsertOrUpdate(cd)
        updateTrie(cd)
        maybeUpdateTrustedMap(cd.uid, cd.domain, Status.NONE)
    }

    suspend fun addDomainRule(d: String, status: Status, type: DomainType, uid: Int) {
        val cd = mkCustomDomain(d, uid, "", type, status.id)
        dbInsertOrUpdate(cd)
        updateTrie(cd)
        maybeUpdateTrustedMap(uid, d, status)
    }

    private fun maybeUpdateTrustedMap(uid: Int, domain: String, status: Status) {
        val d = domain.lowercase(Locale.ROOT)
        if (status == Status.TRUST) {
            trustedMap[d] = trustedMap.getOrDefault(d, emptySet()).plus(uid)
        } else {
            trustedMap[d] = trustedMap.getOrDefault(d, emptySet()).minus(uid)
        }
        if (trustedMap[d] == null) {
            val key = mkTrieKey(d)
            trustedTrie.del(key)
        } else {
            val key = mkTrieKey(d)
            trustedTrie.set(key, status.toString())
        }
    }

    suspend fun updateDomainRule(
        d: String,
        status: Status,
        type: DomainType,
        prevDomain: CustomDomain
    ) {
        val cd = mkCustomDomain(d, prevDomain.uid, "", type, status.id)
        dbUpdate(prevDomain, cd)
        removeFromTrie(prevDomain)
        removeIfInTrustedMap(prevDomain.uid, prevDomain.domain)
        updateTrie(cd)
        maybeUpdateTrustedMap(cd.uid, cd.domain, Status.BLOCK)
    }

    private suspend fun dbInsertOrUpdate(cd: CustomDomain) {
        db.insert(cd)
    }

    private suspend fun dbUpdate(prevDomain: CustomDomain, cd: CustomDomain) {
        db.update(prevDomain, cd)
    }

    private suspend fun dbDelete(cd: CustomDomain) {
        db.delete(cd)
    }

    suspend fun deleteDomain(cd: CustomDomain) {
        dbDelete(cd)
        removeFromTrie(cd)
        removeIfInTrustedMap(cd.uid, cd.domain)
    }

    private fun removeIfInTrustedMap(uid: Int, domain: String) {
        val d = domain.lowercase(Locale.ROOT)
        val trustedUids = trustedMap.getOrDefault(d, emptySet()).minus(uid)
        if (trustedUids.isEmpty()) {
            trustedMap.remove(d)
            val key = mkTrieKey(d)
            trustedTrie.del(key)
        } else {
            trustedMap[d] = trustedUids
        }
    }

    private fun clearTrustedMap(uid: Int) {
        trustedMap.forEach { (domain, uids) ->
            val newUids = uids.minus(uid)
            if (newUids.isEmpty()) {
                trustedMap.remove(domain)
                val key = mkTrieKey(domain)
                trustedTrie.del(key)
            } else {
                trustedMap[domain] = newUids
            }
        }
    }

    suspend fun deleteRulesByUid(uid: Int) {
        db.deleteRulesByUid(uid)
        val rulesDeleted = trie.delAll(uid.toString())
        Logger.i(LOG_TAG_DNS, "rules deleted from trie for $uid: $rulesDeleted")
        clearTrustedMap(uid)
    }

    suspend fun deleteAllRules() {
        db.deleteAllRules()
        trie.clear()
        trustedMap.clear()
        trustedTrie.clear()
    }

    private fun removeFromTrie(cd: CustomDomain) {
        val key = mkTrieKey(cd.domain, cd.uid)
        trie.del(key)
    }

    fun getUniversalCustomDomainCount(): LiveData<Int> {
        return db.getUniversalCustomDomainCount()
    }

    fun isValidDomain(url: String): Boolean {
        return try {
            Patterns.WEB_URL.matcher(url).matches() || Patterns.DOMAIN_NAME.matcher(url).matches()
        } catch (ignored: MalformedURLException) { // ignored
            false
        }
    }

    suspend fun updateUids(uids: List<Int>, newUids: List<Int>) {
        for (i in uids.indices) {
            val uid = uids[i]
            val newUid = newUids[i]
            updateUid(uid, newUid)
        }
    }

    suspend fun updateUid(uid: Int, newUid: Int) {
        clearTrie(uid)
        clearTrustedMap(uid)
        db.updateUid(uid, newUid)
        rehydrateFromDB(newUid)
    }

    private suspend fun rehydrateFromDB(uid: Int) {
        val doms = db.getDomainsByUID(uid)
        if (doms.isEmpty()) {
            Logger.w(LOG_TAG_DNS, "rehydrate: zero domains for uid: $uid in db")
            return
        }

        Logger.i(LOG_TAG_DNS, "rehydrate: rehydrating ${doms.size} domains for uid: $uid")
        // process longer domains first
        val selector: (String) -> Int = { str -> str.length }
        val desc = doms.sortedByDescending { selector(it.domain) }
        desc.forEach { cd ->
            val key = mkTrieKey(cd.domain, cd.uid)
            trie.set(key, cd.status.toString())
            maybeAddToTrustedMap(cd)
        }
    }

    private fun clearTrie(uid: Int) {
        trie.delAll(uid.toString())
    }

    fun isWildCardEntry(url: String): Boolean {
        return wcRegex.matcher(url).matches()
    }

    private fun mkCustomDomain(
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
}
