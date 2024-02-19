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
import dnsx.Dnsx
import java.net.MalformedURLException
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object DomainRulesManager : KoinComponent {

    private val db by inject<CustomDomainRepository>()

    private var trie: dnsx.RadixTree = Dnsx.newRadixTree()

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
    private fun updateTrie(cd: CustomDomain) {
        val key = mkTrieKey(cd.domain.lowercase(Locale.ROOT), cd.uid)
        trie.set(key, cd.status.toString())
    }

    private fun mkTrieKey(_domain: String, uid: Int): String {
        // *.google.co.uk -> .google.co.uk,<uid>
        // not supported by IpTrie: google.* -> google.,<uid>
        val domain = _domain.removePrefix("*")
        return domain.lowercase(Locale.ROOT) + "," + uid
    }

    suspend fun load(): Long {
        trie.clear()
        db.getAllCustomDomains().forEach { cd ->
            val key = mkTrieKey(cd.domain.lowercase(Locale.ROOT), cd.uid)
            trie.set(key, cd.status.toString())
        }
        return trie.len()
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
        val match = matchesWildcard(domain, uid)
        return match
    }

    private fun matchesWildcard(domain: String, uid: Int): Status {
        val key = mkTrieKey(domain.lowercase(Locale.ROOT), uid)
        val match = trie.getAny(key) // matches the longest prefix

        if (match.isNullOrEmpty()) {
            return Status.NONE
        }

        return Status.getStatus(match.toIntOrNull())
    }

    fun getDomainRule(domain: String, uid: Int): Status {
        val key = mkTrieKey(domain.lowercase(Locale.ROOT), uid)
        val match = trie.get(key)
        if (match.isNullOrEmpty()) {
            return Status.NONE
        }
        return Status.getStatus(match.toIntOrNull())
    }

    fun isDomainTrusted(_domain: String?): Boolean {
        if (_domain.isNullOrEmpty()) {
            return false
        }

        val domain = _domain.lowercase(Locale.ROOT)
        val key = mkTrieKey(domain, Constants.UID_EVERYBODY)
        val match = trie.get(key)
        if (match.isNullOrEmpty()) {
            return false
        }
        return Status.TRUST.id == match.toIntOrNull()
    }

    suspend fun whitelist(cd: CustomDomain) {
        cd.status = Status.TRUST.id
        cd.modifiedTs = Calendar.getInstance().timeInMillis
        dbInsertOrUpdate(cd)
        updateTrie(cd)
    }

    suspend fun changeStatus(
        domain: String,
        uid: Int,
        ips: String,
        type: DomainType,
        status: Status
    ) {
        val cd = constructObject(domain, uid, ips, type, status.id)
        dbInsertOrUpdate(cd)
        updateTrie(cd)
    }

    suspend fun block(domain: String, uid: Int, ips: String = "", type: DomainType) {
        val cd = constructObject(domain, uid, ips, type, Status.BLOCK.id)
        dbInsertOrUpdate(cd)
        updateTrie(cd)
    }

    suspend fun block(cd: CustomDomain) {
        cd.status = Status.BLOCK.id
        cd.modifiedTs = Calendar.getInstance().timeInMillis
        dbInsertOrUpdate(cd)
        updateTrie(cd)
    }

    suspend fun noRule(cd: CustomDomain) {
        cd.status = Status.NONE.id
        cd.modifiedTs = Calendar.getInstance().timeInMillis
        dbInsertOrUpdate(cd)
        updateTrie(cd)
    }

    suspend fun addDomainRule(d: String, status: Status, type: DomainType, uid: Int) {
        val cd = constructObject(d, uid, "", type, status.id)
        dbInsertOrUpdate(cd)
        updateTrie(cd)
    }

    suspend fun updateDomainRule(
        d: String,
        status: Status,
        type: DomainType,
        prevDomain: CustomDomain
    ) {
        val cd = constructObject(d, prevDomain.uid, "", type, status.id)
        dbUpdate(prevDomain, cd)
        removeFromTrie(prevDomain)
        updateTrie(cd)
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
    }

    suspend fun deleteRulesByUid(uid: Int) {
        db.deleteRulesByUid(uid)
        val rulesDeleted = trie.delAll(uid.toString())
        Log.i(LOG_TAG_DNS, "rules deleted from trie for $uid: $rulesDeleted")
    }

    suspend fun deleteAllRules() {
        db.deleteAllRules()
        trie.clear()
    }

    private fun removeFromTrie(cd: CustomDomain) {
        val key = mkTrieKey(cd.domain.lowercase(Locale.ROOT), cd.uid)
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
        db.updateUid(uid, newUid)
        rehydrateFromDB(newUid)
    }

    private suspend fun rehydrateFromDB(uid: Int) {
        val doms = db.getDomainsByUID(uid)
        if (doms.isEmpty()) {
            Log.w(LOG_TAG_DNS, "rehydrate: zero domains for uid: $uid in db")
            return
        }

        Log.i(LOG_TAG_DNS, "rehydrate: rehydrating ${doms.size} domains for uid: $uid")
        // process longer domains first
        val selector: (String) -> Int = { str -> str.length }
        val desc = doms.sortedByDescending { selector(it.domain) }
        desc.forEach { cd ->
            val key = mkTrieKey(cd.domain.lowercase(Locale.ROOT), cd.uid)
            trie.set(key, cd.status.toString())
        }
    }

    private fun clearTrie(uid: Int) {
        trie.delAll(uid.toString())
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
}
