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

import android.util.Log
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.database.ProxyAppMappingRepository
import com.celzero.bravedns.database.ProxyApplicationMapping
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_PROXY
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object ProxyManager : KoinComponent {

    private val proxyAppMappingRepository: ProxyAppMappingRepository by inject()
    private val appConfig: AppConfig by inject()

    const val ID_ORBOT_BASE = "ORBOT"
    const val ID_WG_BASE = "wg"
    const val ID_TCP_BASE = "TCP"
    const val ID_S5_BASE = "S5"
    const val ID_HTTP_BASE = "HTTP"
    const val ID_SYSTEM = "SYSTEM"

    const val TCP_PROXY_NAME = "Rethink-Proxy"
    const val ORBOT_PROXY_NAME = "Orbot"

    private var appConfigMappings = mutableSetOf<ProxyApplicationMapping>()

    suspend fun load() {
        WireguardManager.load()
        appConfigMappings = proxyAppMappingRepository.getApps().toMutableSet()
    }

    fun getProxyIdForApp(uid: Int): String {
        val appConfigMapping = appConfigMappings.find { it.uid == uid }
        return appConfigMapping?.proxyId ?: ID_SYSTEM
    }

    fun updateProxyIdForApp(uid: Int, proxyId: String, proxyName: String) {
        val appConfigMapping = appConfigMappings.find { it.uid == uid }
        if (!isValidProxyId(proxyId)) {
            Log.e(LOG_TAG_PROXY, "Invalid config id: $proxyId")
            return
        }

        if (appConfigMapping != null) {
            appConfigMapping.proxyId = proxyId
            appConfigMapping.proxyName = proxyName
        } else {
            Log.e(LOG_TAG_PROXY, "updateProxyIdForApp - appConfigMapping is null for uid $uid")
        }
        io { proxyAppMappingRepository.updateProxyIdForApp(uid, proxyId, proxyName) }
    }

    fun isProxyActive(proxyId: String): Boolean {
        return if (proxyId.contains(ID_SYSTEM)) {
            false
        } else if (proxyId.contains(ID_ORBOT_BASE)) {
            appConfig.isOrbotProxyEnabled()
        } else if (proxyId.contains(ID_WG_BASE)) {
            WireguardManager.isConfigActive(proxyId)
        } else if (proxyId.contains(ID_TCP_BASE)) {
            TcpProxyHelper.isTcpProxyEnabled()
        } else if (proxyId.contains(ID_S5_BASE)) {
            appConfig.isCustomSocks5Enabled()
        } else if (proxyId.contains(ID_HTTP_BASE)) {
            appConfig.isCustomHttpProxyEnabled()
        } else {
            false
        }
    }

    fun getProxyMapping(): MutableSet<FirewallManager.AppInfoTuple> {
        return appConfigMappings
            .map { FirewallManager.AppInfoTuple(it.uid, it.packageName) }
            .toMutableSet()
    }

    fun updateProxyIdForAllApps(proxyId: String, proxyName: String) {
        if (proxyId == "" || !isValidProxyId(proxyId)) {
            Log.e(LOG_TAG_PROXY, "Invalid proxy id: $proxyId")
            return
        }
        Log.i(LOG_TAG_PROXY, "Adding all apps to interface: $proxyId")
        appConfigMappings.forEach { it.proxyId = proxyId }
        io { proxyAppMappingRepository.updateProxyForAllApps(proxyId, proxyName) }
    }

    fun removeProxyIdForApp(uid: Int) {
        val appConfigMapping = appConfigMappings.find { it.uid == uid }
        if (appConfigMapping != null) {
            appConfigMappings.remove(appConfigMapping)
        } else {
            Log.e(LOG_TAG_PROXY, "app config mapping is null for uid $uid on removeProxyIdForApp")
        }
        // update the id as empty string to remove the proxy
        io { proxyAppMappingRepository.updateProxyIdForApp(uid, "", "") }
    }

    fun removeProxyForAllApps() {
        Log.i(LOG_TAG_PROXY, "Removing all apps from proxy")
        appConfigMappings.forEach { it.proxyId = "" }
        io { proxyAppMappingRepository.updateProxyForAllApps("", "") }
    }

    fun removeProxyForAllApps(proxyId: String) {
        Log.i(LOG_TAG_PROXY, "Removing all apps from proxy with id: $proxyId")
        appConfigMappings.filter { it.proxyId == proxyId }.forEach { it.proxyId = "" }
        io { proxyAppMappingRepository.removeAllAppsForProxy(proxyId) }
    }

    fun addNewApp(appInfo: AppInfo, proxyId: String = "", proxyName: String = "") {
        val pam =
            ProxyApplicationMapping(
                appInfo.uid,
                appInfo.packageName,
                appInfo.appName,
                proxyId,
                true,
                proxyName
            )
        appConfigMappings.add(pam)
        if (DEBUG) Log.d(LOG_TAG_PROXY, "Adding new app for mapping: ${pam.appName}, ${pam.uid}")
        io { proxyAppMappingRepository.insert(pam) }
    }

    fun deleteApp(appInfo: AppInfo, proxyId: String = "", proxyName: String = "") {
        val pam =
            ProxyApplicationMapping(
                appInfo.uid,
                appInfo.packageName,
                appInfo.appName,
                proxyName,
                false,
                proxyId
            )
        appConfigMappings.remove(pam)
        if (DEBUG) Log.d(LOG_TAG_PROXY, "Deleting app for mapping: ${pam.appName}, ${pam.uid}")
        io { proxyAppMappingRepository.delete(pam) }
    }

    fun deleteApp(appInfoTuple: FirewallManager.AppInfoTuple) {
        val pam =
            ProxyApplicationMapping(appInfoTuple.uid, appInfoTuple.packageName, "", "", false, "")
        appConfigMappings.remove(pam)
        if (DEBUG) Log.d(LOG_TAG_PROXY, "Deleting app for mapping: ${pam.appName}, ${pam.uid}")
        io { proxyAppMappingRepository.delete(pam) }
    }

    fun getProxyIdForApp(appInfo: AppInfo): String {
        val appConfigMapping = appConfigMappings.find { it.uid == appInfo.uid }
        return appConfigMapping?.proxyId ?: ID_SYSTEM
    }

    fun isAnyAppSelected(proxyId: String): Boolean {
        return appConfigMappings.any { it.proxyId == proxyId }
    }

    private fun isValidProxyId(proxyId: String): Boolean {
        return proxyId.contains(ID_ORBOT_BASE) ||
            proxyId.contains(ID_WG_BASE) ||
            proxyId.contains(ID_TCP_BASE) ||
            proxyId.contains(ID_S5_BASE) ||
            proxyId.contains(ID_HTTP_BASE)
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
