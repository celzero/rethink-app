/*
 * Copyright 2023 RethinkDNS and its authors
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
import Logger.LOG_TAG_PROXY
import backend.Backend
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ProxyAppMappingRepository
import com.celzero.bravedns.database.ProxyApplicationMapping
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.CopyOnWriteArraySet

object ProxyManager : KoinComponent {

    private val db: ProxyAppMappingRepository by inject()

    const val ID_ORBOT_BASE = "ORBOT"
    const val ID_WG_BASE = "wg"
    const val ID_TCP_BASE = "TCP"
    const val ID_S5_BASE = "S5"
    const val ID_HTTP_BASE = "HTTP"
    const val ID_NONE = "SYSTEM"

    const val TCP_PROXY_NAME = "Rethink-Proxy"
    const val ORBOT_PROXY_NAME = "Orbot"

    // we are using ProxyAppMapTuple instead of ProxyApplicationMapping for the pamSet as the equals
    // and hash method implementation is overridden and cannot be used for the pamSet
    data class ProxyAppMapTuple(val uid: Int, val packageName: String, val proxyId: String)

    // TODO: consider adding other proxy modes (e.g, Wireguard, Rethink, etc.)
    enum class ProxyMode(val value: Int) {
        SOCKS5(0),
        HTTP(1),
        ORBOT_SOCKS5(2),
        ORBOT_HTTP(3);

        companion object {
            fun get(v: Int?): ProxyMode? {
                if (v == null) return null
                return when (v) {
                    SOCKS5.value -> SOCKS5
                    HTTP.value -> HTTP
                    ORBOT_SOCKS5.value -> ORBOT_SOCKS5
                    ORBOT_HTTP.value -> ORBOT_HTTP
                    else -> null
                }
            }
        }

        fun isCustomSocks5(): Boolean {
            return this == SOCKS5
        }

        fun isCustomHttp(): Boolean {
            return this == HTTP
        }

        fun isOrbotSocks5(): Boolean {
            return this == ORBOT_SOCKS5
        }

        fun isOrbotHttp(): Boolean {
            return this == ORBOT_HTTP
        }
    }

    private val pamSet = CopyOnWriteArraySet<ProxyAppMapTuple>()

    suspend fun load(): Int {
        val a = db.getApps()
        val entries = a.map { ProxyAppMapTuple(it.uid, it.packageName, it.proxyId) }
        pamSet.clear()
        pamSet.addAll(entries)
        return a.size
    }

    fun getProxyIdForApp(uid: Int): String {
        val m = pamSet.find { it.uid == uid }
        return m?.proxyId ?: ID_NONE
    }

    // get the proxy id for the app, if not found return the default proxy id.
    // proxyId cannot be empty.
    suspend fun updateProxyIdForApp(uid: Int, nonEmptyProxyId: String, proxyName: String) {
        if (!isValidProxyPrefix(nonEmptyProxyId)) {
            Logger.e(LOG_TAG_PROXY, "cannot update $nonEmptyProxyId; setNoProxyForApp instead?")
            return
        }

        val m = pamSet.filter { it.uid == uid } // returns a reference to underlying data-class
        if (m.isNotEmpty()) {
            val n = m.map { ProxyAppMapTuple(it.uid, it.packageName, nonEmptyProxyId) }
            // in-place updates in Set does not remove dups on conflicts: pl.kotl.in/hEHOgk3V0
            // that is, m.forEach { it.proxyName = nonEmptyProxyId } will not de-dup an existing
            // entry with the same uid+package-name+proxy-id, and instead will retain both entries.
            pamSet.removeAll(m.toSet())
            pamSet.addAll(n)
            db.updateProxyIdForApp(uid, nonEmptyProxyId, proxyName)
        } else {
            Logger.e(LOG_TAG_PROXY, "updateProxyIdForApp: map not found for uid $uid")
        }
    }

    fun trackedApps(): MutableSet<FirewallManager.AppInfoTuple> {
        return pamSet.map { FirewallManager.AppInfoTuple(it.uid, it.packageName) }.toMutableSet()
    }

    suspend fun setProxyIdForAllApps(proxyId: String, proxyName: String) {
        // ID_NONE or empty proxy-id is not allowed; see removeProxyForAllApps()
        if (!isValidProxyPrefix(proxyId)) {
            Logger.e(LOG_TAG_PROXY, "Invalid proxy id: $proxyId")
            return
        }
        val m = pamSet.map { ProxyAppMapTuple(it.uid, it.packageName, proxyId) }
        pamSet.clear()
        pamSet.addAll(m)
        db.updateProxyForAllApps(proxyId, proxyName)
        Logger.i(LOG_TAG_PROXY, "added all apps to proxy: $proxyId")
    }

    suspend fun updateProxyNameForProxyId(proxyId: String, proxyName: String) {
        // pamSet does not store proxy-name, so nothing to update there
        db.updateProxyNameForProxyId(proxyId, proxyName)
    }

    suspend fun setProxyIdForUnselectedApps(proxyId: String, proxyName: String) {
        // ID_NONE or empty proxy-id is not allowed
        if (!isValidProxyPrefix(proxyId)) {
            Logger.e(LOG_TAG_PROXY, "Invalid proxy id: $proxyId")
            return
        }
        val m = pamSet.filter { it.proxyId == "" }.toSet()
        val n = m.map { ProxyAppMapTuple(it.uid, it.packageName, proxyId) }
        pamSet.removeAll(m)
        pamSet.addAll(n)
        db.updateProxyForUnselectedApps(proxyId, proxyName)
        Logger.i(LOG_TAG_PROXY, "added unselected apps to interface: $proxyId")
    }

    suspend fun setNoProxyForApp(uid: Int) {
        val noProxy = ""
        val m = pamSet.filter { it.uid == uid }.toSet()
        if (m.isNotEmpty()) {
            val n = m.map { ProxyAppMapTuple(it.uid, it.packageName, noProxy) }
            pamSet.removeAll(m)
            pamSet.addAll(n)
            // update the id as empty string to remove the proxy
            db.updateProxyIdForApp(uid, noProxy, noProxy)
        } else {
            Logger.e(LOG_TAG_PROXY, "app config mapping is null for uid $uid on setNoProxyForApp")
        }
    }

    suspend fun setNoProxyForAllApps() {
        val noProxy = ""
        Logger.i(LOG_TAG_PROXY, "Removing all apps from proxy")
        val m = pamSet.filter { it.proxyId != noProxy }.toSet()
        val n = m.map { ProxyAppMapTuple(it.uid, it.packageName, noProxy) }
        pamSet.removeAll(m)
        pamSet.addAll(n)
        db.updateProxyForAllApps(noProxy, noProxy)
    }

    suspend fun removeProxyId(proxyId: String) {
        Logger.i(LOG_TAG_PROXY, "Removing all apps from proxy with id: $proxyId")
        val noProxy = ""
        val m = pamSet.filter { it.proxyId == proxyId }.toSet()
        val n = m.map { ProxyAppMapTuple(it.uid, it.packageName, noProxy) }
        pamSet.removeAll(m)
        pamSet.addAll(n)
        db.removeAllAppsForProxy(proxyId)
    }

    suspend fun deleteApps(m: Collection<FirewallManager.AppInfoTuple>) {
        m.forEach { deleteApp(it.uid, it.packageName) }
    }

    suspend fun addApps(m: Collection<AppInfo?>) {
        m.forEach { addNewApp(it) }
    }

    suspend fun purgeDupsBeforeRefresh() {
        val visited = mutableSetOf<FirewallManager.AppInfoTuple>()
        val dups = mutableSetOf<FirewallManager.AppInfoTuple>()
        pamSet
            .map { FirewallManager.AppInfoTuple(it.uid, it.packageName) }
            .forEach { if (visited.contains(it)) dups.add(it) else visited.add(it) }
        // duplicates are unexpected; but since refreshDatabase only deals in uid+package-name
        // and proxy-mapper primary keys on uid+package-name+proxy-id, there have been cases
        // of duplicate entries in the proxy-mapper. Purge all entries that have same
        // uid+package-name pair. Note that, doing so also removes entry for an app even if it is
        // currently installed.
        // This is okay, given we do not expect any dups. Also: This fn must be called before
        // refreshDatabase so that any entries removed are added back as "new mappings" via
        // addNewApp
        if (dups.size > 0) {
            Logger.w(LOG_TAG_PROXY, "delete dup pxms: $dups")
            deleteApps(dups)
        } else {
            // no dups found
            Logger.i(LOG_TAG_PROXY, "no dups found")
        }
    }

    suspend fun addNewApp(appInfo: AppInfo?, proxyId: String = "", proxyName: String = "") {
        if (appInfo == null) {
            Logger.e(LOG_TAG_PROXY, "AppInfo is null, cannot add to proxy")
            return
        }
        val pam =
            ProxyApplicationMapping(
                appInfo.uid,
                appInfo.packageName,
                appInfo.appName,
                proxyId,
                true,
                proxyName
            )
        val pamTuple = ProxyAppMapTuple(appInfo.uid, appInfo.packageName, proxyId)
        pamSet.add(pamTuple)
        db.insert(pam)
        Logger.i(LOG_TAG_PROXY, "Adding app for mapping: ${pam.appName}, ${pam.uid}")
    }

    private fun deleteFromCache(pam: ProxyApplicationMapping) {
        pamSet.forEach {
            if (it.uid == pam.uid && it.packageName == pam.packageName) {
                pamSet.remove(it)
            }
        }
    }

    suspend fun deleteAppMappingsByUid(uid: Int) {
        val m = pamSet.filter { it.uid == uid }
        m.forEach { deleteApp(it.uid, it.packageName) }
    }

    private suspend fun deleteApp(uid: Int, packageName: String) {
        val pam = ProxyApplicationMapping(uid, packageName, "", "", false, "")
        deleteFromCache(pam)
        db.deleteApp(pam)
        Logger.d(LOG_TAG_PROXY, "Deleting app for mapping: ${pam.appName}, ${pam.uid}")
    }

    suspend fun clear() {
        pamSet.clear()
        db.deleteAll()
        Logger.d(LOG_TAG_PROXY, "Deleting all apps for mapping")
    }

    fun isAnyAppSelected(proxyId: String): Boolean {
        return pamSet.any { it.proxyId == proxyId }
    }

    private fun isValidProxyPrefix(pid: String): Boolean {
        if (pid == ID_NONE || pid == "") return false
        return pid.startsWith(ID_ORBOT_BASE) ||
            pid.startsWith(ID_WG_BASE) ||
            pid.startsWith(ID_TCP_BASE) ||
            pid.startsWith(ID_S5_BASE) ||
            pid.startsWith(ID_HTTP_BASE)
    }

    fun getAppCountForProxy(proxyId: String): Int {
        return pamSet.count { it.proxyId == proxyId }
    }

    suspend fun removeWgProxies() {
        // remove all the wg proxies from the app config mappings, during restore process
        val noProxy = ""
        val m = pamSet.filter { it.proxyId.startsWith(ID_WG_BASE) }.toSet()
        val n = m.map { ProxyAppMapTuple(it.uid, it.packageName, noProxy) }
        pamSet.removeAll(m)
        pamSet.addAll(n)
        db.removeAllWgProxies()
    }

    fun isIpnProxy(ipnProxyId: String): Boolean {
        if (ipnProxyId.isEmpty()) return false
        // if id is not Ipn.Base, Ipn.Block, Ipn.Exit then it is proxied
        return ipnProxyId != Backend.Base &&
            ipnProxyId != Backend.Block &&
            ipnProxyId != Backend.Exit
    }
}
