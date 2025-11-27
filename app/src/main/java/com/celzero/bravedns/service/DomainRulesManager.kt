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
import Logger.LOG_TAG_FIREWALL
import android.content.Context
import android.util.Patterns
import androidx.lifecycle.LiveData
import com.celzero.firestack.backend.Backend
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.CustomDomain
import com.celzero.bravedns.database.CustomDomainRepository
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities.togs
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.firestack.backend.Gostr
import com.celzero.firestack.backend.RadixTree
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.net.MalformedURLException
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern

object DomainRulesManager : KoinComponent {

    private val db by inject<CustomDomainRepository>()

    private var trie: RadixTree = Backend.newRadixTree()
    // fixme: find a better way to handle trusted domains without using two data structures
    // map to store the trusted domains with set of uids
    private val trustedMap = ConcurrentHashMap<String, Set<Int>>()
    // even though we have trustedMap, we need to keep the trie for wildcard matching
    private var trustedTrie: RadixTree = Backend.newRadixTree()

    // regex to check if url is valid wildcard domain
    // valid wildcard domain: *.example.com, *.example.co.in, *.do-main.com
    // RFC 1035: https://tools.ietf.org/html/rfc1035#section-2.3.4
    private val wcRegex = Pattern.compile("^(\\*\\.)?([a-zA-Z0-9-]+\\.)+[a-zA-Z0-9-]+$")

    private val selectedCCs: MutableSet<String> = mutableSetOf()

    private const val KV_SEP = ":"

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

    suspend fun getObj(uid: Int, domain: String): CustomDomain? {
        return db.getCustomDomain(uid, domain)
    }

    // update the cache with the domain and its status based on the domain type
    fun updateTrie(cd: CustomDomain) {
        val key = mkTrieKey(cd.domain, cd.uid)
        val value = mkTrieValue(cd.status.toString(), cd.proxyId, cd.proxyCC)
        trie.set(key, value)
    }

    private fun mkTrieKey(d: String, uid: Int): Gostr? {
        // *.google.co.uk -> .google.co.uk,<uid>
        // not supported by IpTrie: google.* -> google.,<uid>
        val domain = d.removePrefix("*")
        return (domain.lowercase(Locale.ROOT) + Backend.Ksep + uid).togs()
    }

    private fun mkTrieKeyForTrustedMap(d: String): Gostr? {
        // *.google.co.uk -> .google.co.uk
        val domain = d.removePrefix("*")
        return domain.lowercase(Locale.ROOT).togs()
    }

    private fun mkTrieValue(status: String, proxyId: String, proxyCC: String): Gostr? {
        return ("${status}$KV_SEP${proxyId}$KV_SEP${proxyCC}").togs()
    }

    suspend fun load(): Long {
        trie.clear()
        trustedTrie.clear()
        trustedMap.clear()
        db.getAllCustomDomains().forEach { cd ->
            // adding as part of defensive programming, even adding these rules to cache will
            // not cause any issues, but to avoid unnecessary entries in the trie, skipping these
            // entries
            if (cd.uid < 0 && cd.uid != Constants.UID_EVERYBODY) {
                Logger.w(LOG_TAG_DNS, "skipping domain rule for uid: ${cd.uid}")
                return@forEach
            }
            val key = mkTrieKey(cd.domain, cd.uid)
            val value = mkTrieValue(cd.status.toString(), cd.proxyId, cd.proxyCC)
            trie.set(key, value)
            maybeAddToTrustedMap(cd)
            if (cd.proxyCC.isNotEmpty()) selectedCCs.add(cd.proxyCC)
        }
        val trieLen = trie.len()
        Logger.i(LOG_TAG_DNS, "DomainRulesManager: loaded $trieLen rules from db")
        return trieLen
    }

    fun getAllUniqueCCs(): List<String> {
        Logger.v(LOG_TAG_DNS, "getAllUniqueCCs: $selectedCCs")
        return selectedCCs.toList()
    }

    private fun maybeAddToTrustedMap(cd: CustomDomain) {
        if (cd.status != Status.TRUST.id) return

        val domain = cd.domain.lowercase(Locale.ROOT)
        val key = mkTrieKeyForTrustedMap(domain)

        trustedTrie.set(key, cd.status.toString().togs())

        trustedMap.compute(domain) { _, old ->
            (old ?: emptySet()) + cd.uid
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
        val match = trie.getAny(key).tos() // matches the longest prefix
        if (match.isNullOrEmpty()) {
            // no match found, return NONE
            if (DEBUG) Logger.vv(LOG_TAG_DNS, "matchesWildcard: $domain($uid), no match found")
            return Status.NONE
        }
        val status = match.split(KV_SEP)[0]
        val res = Status.getStatus(status.toIntOrNull())
        if (DEBUG) Logger.vv(LOG_TAG_DNS, "matchesWildcard: $domain($uid), res: $res")
        return res
    }

    fun getDomainRule(domain: String, uid: Int): Status {
        val key = mkTrieKey(domain, uid)
        val match = trie.get(key).tos()
        if (match.isNullOrEmpty()) {
            // no match found, return NONE
            if (DEBUG) Logger.vv(LOG_TAG_DNS, "domain rule for $key, no match found")
            return Status.NONE
        }
        val status = match.split(KV_SEP)[0]
        val res = Status.getStatus(status.toIntOrNull())
        if (DEBUG) Logger.vv(LOG_TAG_DNS, "domain rule for $key, res: $res")
        return res
    }

    fun getProxyForDomain(uid: Int, domain: String): Pair<String, String> {
        try {
            val key = mkTrieKey(domain, uid)
            var proxyId = ""
            var proxyCC = ""
            val match = trie.get(key).tos()
            if (match.isNullOrEmpty()) {
                return Pair("", "")
            }
            val parts = match.split(KV_SEP)

            if (parts.size <= 2) return Pair("", "")

            // not expecting index out of bounds here, as the value is constructed while inserting
            // still adding try-catch to avoid any crashes
            // status:proxyId:proxyCC
            proxyId = parts[1]
            proxyCC = parts[2]

            // empty proxyId means no proxy rule for the domain, check for wildcard
            if (proxyId.isNotEmpty() || proxyCC.isNotEmpty()) {
                return Pair(proxyId, proxyCC)
            } else {
                val wild = trie.getAny(key).tos()
                if (wild.isNullOrEmpty()) return Pair("", "")

                val wildParts = wild.split(KV_SEP)
                if (wildParts.size <= 2) return Pair("", "")

                proxyId = wildParts[1]
                proxyCC = wildParts[2]
                return Pair(proxyId, proxyCC)
            }
        } catch (_: Exception) {
            return Pair("", "")
        }
    }

    fun isDomainTrusted(d: String?): Boolean {
        if (d.isNullOrEmpty()) {
            return false
        }
        val domain = d.lowercase(Locale.ROOT).togs()
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

        val result = trustedMap.compute(d) { _, old ->
            val updated = if (status == Status.TRUST) {
                (old ?: emptySet()) + uid
            } else {
                (old ?: emptySet()) - uid
            }

            updated.ifEmpty { null }
        }

        val key = mkTrieKeyForTrustedMap(d)

        if (result == null) {
            trustedTrie.del(key)
        } else {
            trustedTrie.set(key, status.id.toString().togs())
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
        val result = trustedMap.compute(d) { _, old ->
            val updated = (old ?: emptySet()) - uid
            updated.ifEmpty { null }
        }
        val key = mkTrieKeyForTrustedMap(d)

        if (result == null) {
            trustedTrie.del(key)
        }
    }

    private fun clearTrustedMap(uid: Int) {
        trustedMap.keys.forEach { domain ->
            val result = trustedMap.compute(domain) { _, old ->
                val updated = (old ?: emptySet()) - uid
                updated.ifEmpty { null }
            }

            val key = mkTrieKeyForTrustedMap(domain)

            if (result == null) {
                trustedTrie.del(key)
            }
        }
    }

    suspend fun deleteRulesByUid(uid: Int) {
        db.deleteRulesByUid(uid)
        val rulesDeleted = trie.delAll(uid.toString().togs())
        Logger.i(LOG_TAG_DNS, "rules deleted from trie for $uid: $rulesDeleted")
        clearTrustedMap(uid)
    }

    suspend fun deleteRules(list: List<CustomDomain>) {
        list.forEach { cd ->
            removeFromTrie(cd)
            removeIfInTrustedMap(cd.uid, cd.domain)
        }
        db.deleteRules(list)
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

    suspend fun getRulesCountByCC(cc: String): Int {
        return db.getRulesCountByCC(cc)
    }

    fun isValidDomain(url: String): Boolean {
        return try {
            Patterns.WEB_URL.matcher(url).matches() || Patterns.DOMAIN_NAME.matcher(url).matches()
        } catch (_: MalformedURLException) {
            false
        }
    }

    suspend fun updateUids(uids: List<Int>, newUids: List<Int>) {
        val dms = db.getAllCustomDomains()
        for (i in uids.indices) {
            val uid = uids[i]
            val newUid = newUids[i]
            if (dms.any { it.uid == uid }) {
                updateUid(uid, newUid)
            }
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
            val value = mkTrieValue(cd.status.toString(), cd.proxyId, cd.proxyCC)
            trie.set(key, value)
            maybeAddToTrustedMap(cd)
        }
    }

    private fun clearTrie(uid: Int) {
        trie.delAll(uid.toString().togs())
    }

    suspend fun setCC(cd: CustomDomain, cc: String) {
        cd.proxyCC = cc
        val newCd = CustomDomain(cd.domain, cd.uid, cd.ips, cd.type, cd.status, cd.proxyId, cc, cd.modifiedTs, 0L, cd.version)
        Logger.d(LOG_TAG_DNS, "setCC: updating domain: ${cd.domain} to cc: $cc")
        db.update(cd, newCd)
        Logger.i(LOG_TAG_DNS, "setCC: updated domain: ${cd.domain} to $cc")
        rehydrateFromDB(cd.uid)
    }

    suspend fun setProxyId(cd: CustomDomain, proxyId: String) {
        cd.proxyId = proxyId
        val newCd = CustomDomain(cd.domain, cd.uid, cd.ips, cd.type, cd.status, proxyId, cd.proxyCC, cd.modifiedTs, 0L, cd.version)
        db.update(cd, newCd)
        Logger.i(LOG_TAG_DNS, "setProxyId: updated domain: ${cd.domain} to $proxyId")
        rehydrateFromDB(cd.uid)
    }

    suspend fun tombstoneRulesByUid(oldUid: Int) {
        Logger.i(LOG_TAG_FIREWALL, "tombstone rules for uid: $oldUid")
        // here tombstone means negating the uid of the rule
        // this is used when the app is uninstalled, so that the rules are not deleted
        // but the uid is set to (-1 * uid), so that the rules are not applied
        val newUid = if (oldUid > 0) -1 * oldUid else oldUid
        if (oldUid == newUid) {
            Logger.w(LOG_TAG_FIREWALL, "tombstone: same uids, old: $oldUid, new: $newUid, no-op")
            return
        }
        db.tombstoneRulesByUid(oldUid, newUid)
        load()
    }


    fun isWildCardEntry(url: String): Boolean {
        return wcRegex.matcher(url).matches()
    }

    // this is to create a custom domain entry where user want to add proxy without any
    // rules set, this is created for the new ui, should be made generic
    fun makeCustomDomain(uid: Int, domain: String): CustomDomain {
        return mkCustomDomain(domain, uid, "", DomainType.DOMAIN, Status.NONE.id)
    }

    private fun mkCustomDomain(
        domain: String,
        uid: Int,
        ips: String = "",
        type: DomainType,
        status: Int,
        proxyId: String = "",
        proxyCC: String = ""
    ): CustomDomain {
        return CustomDomain(
            domain,
            uid,
            ips,
            type.id,
            status,
            proxyId,
            proxyCC,
            Calendar.getInstance().timeInMillis,
            Constants.INIT_TIME_MS,
            CustomDomain.getCurrentVersion()
        )
    }

    suspend fun stats(): String {
        val sb = StringBuilder()
        sb.append("   Trie: ${trie.len()}\n")
        sb.append("   Trusted: ${trustedTrie.len()}\n")
        sb.append("   db: ${db.getCustomDomainCount()}\n")

        return sb.toString()
    }
}
