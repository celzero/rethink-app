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
import android.content.Context
import android.text.format.DateUtils
import com.celzero.bravedns.backup.BackupHelper.Companion.TEMP_WG_DIR
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.database.WgConfigFilesRepository
import com.celzero.bravedns.service.EncryptionException
import com.celzero.bravedns.service.ProxyManager.ID_NONE
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.util.Constants.Companion.WIREGUARD_FOLDER_NAME
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.Peer
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.bravedns.wireguard.WgInterface
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.RouterStats
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.concurrent.CopyOnWriteArraySet

object WireguardManager : KoinComponent {

    private val db: WgConfigFilesRepository by inject()
    private val applicationContext: Context by inject()
    private val appConfig: AppConfig by inject()
    private val persistentState: PersistentState by inject()

    // 120 sec + 5 sec buffer
    const val WG_HANDSHAKE_TIMEOUT = 125 * DateUtils.SECOND_IN_MILLIS
    const val WG_UPTIME_THRESHOLD = 5 * DateUtils.SECOND_IN_MILLIS

    const val NOTIF_CHANNEL_ID_WIREGUARD_ALERTS = "WireGuard_Alerts"

    // contains db values of wg configs (db stores path of the config file)
    private var mappings: CopyOnWriteArraySet<WgConfigFilesImmutable> = CopyOnWriteArraySet()

    // contains parsed wg configs
    private var configs: CopyOnWriteArraySet<Config> = CopyOnWriteArraySet()

    // retrieve last added config id
    private var lastAddedConfigId = 0

    // let the error code be a string, so that it can be concatenated with the error message
    const val ERR_CODE_VPN_NOT_ACTIVE = "1"
    const val ERR_CODE_VPN_NOT_FULL = "2"
    const val ERR_CODE_OTHER_WG_ACTIVE = "3"
    const val ERR_CODE_WG_INVALID = "4"

    // invalid config id
    const val INVALID_CONF_ID = -1

    const val WARP_ID = 1
    const val WARP_NAME = "WARP"

    const val SEC_WARP_ID = 0
    const val SEC_WARP_NAME = "SEC_WARP"

    init {
        io { load(forceRefresh = false) }
    }

    suspend fun load(forceRefresh: Boolean): Int {
        if (!forceRefresh && configs.isNotEmpty()) {
            Logger.i(LOG_TAG_PROXY, "configs already loaded; returning...")
            return configs.size
        }
        // go through all files in the wireguard directory and load them
        // parse the files as those are encrypted
        // increment the id by 1, as the first config id is 0
        lastAddedConfigId = db.getLastAddedConfigId()
        val m = db.getWgConfigs().map { it.toImmutable() }
        mappings = CopyOnWriteArraySet(m)
        mappings.forEach {
            val path = it.configPath
            val config = try {
                EncryptedFileManager.readWireguardConfig(applicationContext, path)
            } catch (e: EncryptionException) {
                // Critical encryption failure - config is unreadable
                Logger.e(LOG_TAG_PROXY, "Critical encryption failure for wg config: $path, deleting config", e)
                return@forEach
            }
            if (config == null) {
                Logger.e(LOG_TAG_PROXY, "err loading wg config: $path, invalid config")
                // TODO: delete the warp config from the wireguard directory, now part of rpn proxy
                // below code should be removed post v055o
                if ((it.id == WARP_ID && it.name == WARP_NAME) || (it.id == SEC_WARP_ID && it.name == SEC_WARP_NAME)) {
                    deleteConfig(it.id)
                }
                return@forEach
            }
            if (configs.none { i -> i.getId() == it.id }) {
                val c =
                    Config.Builder()
                        .setId(it.id)
                        .setName(it.name)
                        .setInterface(config.getInterface())
                        .addPeers(config.getPeers())
                        .build()
                Logger.d(LOG_TAG_PROXY, "read wg config: ${it.id}, ${it.name}")
                configs.add(c)
            }
        }
        return configs.size
    }

    // remove this post v055o,  sometimes the db update does not delete the entry, so adding this
    // as precaution.
    suspend fun deleteResidueWgs() {
        val wgs = db.getWarpSecWarpConfig()
        if (wgs.isEmpty()) {
            Logger.i(LOG_TAG_PROXY, "no residue wg configs to delete")
            return
        }
        wgs.forEach {
            if (it.name == SEC_WARP_NAME || it.name == WARP_NAME) {
                Logger.i(LOG_TAG_PROXY, "deleting residue wg config: ${it.id}, ${it.name}")
                deleteConfig(it.id)
            }
        }
    }

    private fun clearLoadedConfigs() {
        configs.clear()
        mappings.clear()
    }

    fun getConfigById(id: Int): Config? {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "getConfigById: wg not found: $id, ${configs.size}")
        }
        return config
    }

    fun getConfigFilesById(id: Int): WgConfigFilesImmutable? {
        val config = mappings.find { it.id == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "getConfigFilesById: wg not found: $id, ${configs.size}")
        }
        return config
    }

    fun isAnyWgActive(): Boolean {
        return mappings.any { it.isActive }
    }

    fun isAdvancedWgActive(): Boolean {
        return mappings.any { it.isActive && !it.oneWireGuard }
    }

    fun getAllMappings(): List<WgConfigFilesImmutable> {
        return mappings.toList()
    }

    fun getNumberOfMappings(): Int {
        return mappings.size
    }

    fun getActiveWgCount() = mappings.count { it.isActive }

    fun getActiveConfigs(): List<Config> {
        val m = mappings.filter { it.isActive }
        val l = mutableListOf<Config>()
        m.forEach {
            val config = configs.find { it1 -> it1.getId() == it.id }
            if (config != null) {
                l.add(config)
            }
        }
        return l
    }

    fun isConfigActive(configId: String): Boolean {
        try {
            val id = configId.split(ID_WG_BASE).last().toIntOrNull() ?: return false
            val mapping = mappings.find { it.id == id }
            if (mapping != null) {
                return mapping.isActive
            }
            return false
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "err while checking active config: ${e.message}")
        }
        return false
    }

    suspend fun enableConfig(unmapped: WgConfigFilesImmutable) {
        val map = mappings.find { it.id == unmapped.id }
        if (map == null) {
            Logger.e(
                LOG_TAG_PROXY,
                "enableConfig: wg not found, id: ${unmapped.id}, ${mappings.size}"
            )
            return
        }

        val config = configs.find { it.getId() == map.id }
        if (config == null) {
            Logger.w(LOG_TAG_PROXY, "config not found: ${map.id}")
            return
        }

        mappings.remove(map)
        val newMap =
            WgConfigFilesImmutable(
                map.id,
                map.name,
                map.configPath,
                map.serverResponse,
                true,
                map.isCatchAll,
                map.oneWireGuard,
                map.useOnlyOnMetered,
                map.isDeletable,
                map.ssidEnabled,
                map.ssids
            )
        mappings.add(newMap)
        val dbMap = WgConfigFiles.fromImmutable(newMap)
        db.update(dbMap)
        val proxyType = AppConfig.ProxyType.WIREGUARD
        val proxyProvider = AppConfig.ProxyProvider.WIREGUARD
        appConfig.addProxy(proxyType, proxyProvider)
        VpnController.addWireGuardProxy(ID_WG_BASE + map.id)
        Logger.i(LOG_TAG_PROXY, "enable wg config: ${map.id}, ${map.name}")
        return
    }

    fun canEnableProxy(): Boolean {
        return appConfig.canEnableProxy()
    }

    fun isValidConfig(id: Int): Boolean {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "canEnableConfig: wg not found, id: ${id}, ${configs.size}")
            return false
        }
        return true
    }

    fun isAnyOtherOneWgEnabled(id: Int): Boolean {
        return mappings.any { it.oneWireGuard && it.isActive && it.id != id }
    }

    fun canDisableConfig(map: WgConfigFilesImmutable): Boolean {
        return when {
            map.isCatchAll -> false // cannot disable catch-all
            WgHopManager.isWgEitherHopOrSrc(map.id) -> false // cannot disable hop/via
            else -> true // safe to disable
        }
    }

    fun canDisableAllActiveConfigs(): Boolean {
        mappings.forEach {
            if (it.isActive && it.isCatchAll) {
                return false
            }
        }
        return true
    }

    fun getConfigName(id: Int): String {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "getConfigName: wg not found, id: ${id}, ${configs.size}")
            return ""
        }
        return config.getName()
    }

    suspend fun disableAllActiveConfigs() {
        val activeConfigs = mappings.filter { it.isActive }
        activeConfigs.forEach {
            disableConfig(it)
            updateOneWireGuardConfig(it.id, false)
        }
    }

    fun disableConfig(unmapped: WgConfigFilesImmutable) {
        val m = mappings.find { it.id == unmapped.id }
        if (m == null) {
            Logger.e(
                LOG_TAG_PROXY,
                "disableConfig: wg not found, id: ${unmapped.id}, ${mappings.size}"
            )
            return
        }

        val config = configs.find { it.getId() == unmapped.id }
        if (config == null) {
            Logger.w(LOG_TAG_PROXY, "config not found: ${unmapped.id}")
            return
        }

        // disable the config, update to db, cache and tunnel
        // also update mappings https://pl.kotl.in/g0mVapn4x
        mappings.remove(m)
        val newMap =
            WgConfigFilesImmutable(
                m.id,
                m.name,
                m.configPath,
                m.serverResponse,
                false, // confirms with db.disableConfig query
                m.isCatchAll,
                false, // confirms with db.disableConfig query
                m.useOnlyOnMetered,
                m.isDeletable,
                m.ssidEnabled,
                m.ssids
            )
        mappings.add(newMap)

        io { db.disableConfig(newMap.id) }
        if (mappings.none { it.isActive }) {
            val proxyType = AppConfig.ProxyType.WIREGUARD
            val proxyProvider = AppConfig.ProxyProvider.WIREGUARD
            appConfig.removeProxy(proxyType, proxyProvider)
        }
        // directly remove the proxy from the tunnel, instead of calling updateTun
        io { VpnController.removeWireGuardProxy(newMap.id) }
        Logger.i(LOG_TAG_PROXY, "disable wg config: ${newMap.id}, ${newMap.name}")
        return
    }

    // pair - first: proxyId, second - can proceed for next check
    private fun canUseConfig(idStr: String, type: String, usesMtrdNw: Boolean, ssid: String): Pair<String, Boolean> {
        val lockdown = persistentState.wgGlobalLockdown
        val block = Backend.Block
        if (idStr.isEmpty()) {
            return Pair("", true)
        }
        val id = convertStringIdToId(idStr)
        val config = if (id == INVALID_CONF_ID) null else mappings.find { it.id == id }

        if (config == null) {
            Logger.d(LOG_TAG_PROXY, "config null($idStr) no need to proceed, return empty")
            return Pair("", true)
        }

        if (lockdown && (checkEligibilityBasedOnNw(id, usesMtrdNw) && checkEligibilityBasedOnSsid(id, ssid))) {
            Logger.d(LOG_TAG_PROXY, "lockdown wg for $type => return $idStr")
            return Pair(idStr, false) // no need to proceed further for lockdown
        }

        // in case of lockdown and not metered network, we need to return block as the
        // lockdown should not leak the connections via WiFi
        if (lockdown) {
            // add IpnBlock instead of the config id, let the connection be blocked in WiFi
            // regardless of config is active or not
            Logger.d(LOG_TAG_PROXY, "lockdown wg for $type => return $block")
            return Pair(block, false) // no need to proceed further for lockdown
        }

        // check if the config is active and if it can be used on this network
        if (config.isActive && (checkEligibilityBasedOnNw(id, usesMtrdNw) && checkEligibilityBasedOnSsid(id, ssid))) {
            Logger.d(LOG_TAG_PROXY, "active wg for $type => add $idStr")
            return Pair(idStr, true)
        }

        Logger.v(LOG_TAG_PROXY, "wg for $type not active or not eligible nw, return empty, for id: $idStr, usesMtrdNw: $usesMtrdNw, ssid: $ssid")
        return Pair("", true)
    }

    private fun isDnsRequest(defaultTid: String): Boolean {
        return defaultTid == Backend.System || defaultTid == Backend.Plus || defaultTid == Backend.Preferred
    }

    // no need to check for app excluded from proxy here, expected to call this fn after that
    fun getAllPossibleConfigIdsForApp(uid: Int, ip: String, port: Int, domain: String, usesMobileNw: Boolean, ssid: String, default: String): List<String> {
        val lockdown = persistentState.wgGlobalLockdown
        val block = Backend.Block
        val proxyIds: MutableList<String> = mutableListOf()
        if (oneWireGuardEnabled()) {
            val id = getOneWireGuardProxyId()
            if (id == null || id == INVALID_CONF_ID) {
                Logger.e(LOG_TAG_PROXY, "canAdd: one-wg not found, id: $id, return empty")
                return emptyList()
            }

            // commenting this as the one-wg is enabled for all networks no need to check for
            // mobile network, uncomment this when the one-wg can have mobile only option
            /*if (checkEligibilityBasedOnNw(id, usesMeteredNw)) {
                proxyIds.add(ID_WG_BASE + id)
                // add default to the list, can route check is done in go-tun
                if (default.isNotEmpty()) proxyIds.add(default)
                Logger.i(LOG_TAG_PROXY, "one-wg enabled, return $proxyIds")
                return proxyIds
            } else {
                // fall-through as one-wg is enabled only for metered networks
                // for now the setting doesn't allow user to set the one-wg to mobile networks
                // so this case is not expected
            }*/
            proxyIds.add(ID_WG_BASE + id)
            // add default to the list, can route check is done in go-tun
            // let one-wg use wg-dns no need to add the default to the list
            // as go-tun will not prioritize wg id if default is fast / has less-errors
            if (default.isNotEmpty() && !isDnsRequest(default)) proxyIds.add(default)
            Logger.i(LOG_TAG_PROXY, "one-wg enabled, return $proxyIds")
            return proxyIds
        }

        /* TODO: commenting the code as v055o doesn't use ip-app specific and domain-app specific
        // rules
        // check for ip-app specific config first
        // returns Pair<String, String> - first is ProxyId, second is CC
        val ipc = IpRulesManager.hasProxy(uid, ip, port)
        // return Pair<String, Boolean> - first is ProxyId, second is can proceed for next check
        // one case where second parameter is true when the config is in lockdown mode
        val ipcProxyPair = canUseConfig(ipc.first, "ip($ip:$port)", usesMeteredNw)
        if (!ipcProxyPair.second) { // false denotes first is not empty
            if (ipcProxyPair.first == block) {
                proxyIds.clear()
                proxyIds.add(block)
            } else {
                proxyIds.add(ipcProxyPair.first)
            }
            Logger.i(LOG_TAG_PROXY, "lockdown wg for ip($ip:$port) => return $proxyIds")
            return proxyIds
        }
        // add the ip-app specific config to the list
        if (ipc.first.isNotEmpty()) proxyIds.add(ipc.first) // ip-app specific

        // check for domain-app specific config
        val dc = DomainRulesManager.getProxyForDomain(uid, domain)
        val dcProxyPair = canUseConfig(dc.first, "domain($domain)", usesMeteredNw)
        if (!dcProxyPair.second) {
            if (ipcProxyPair.first == block) {
                proxyIds.clear()
                proxyIds.add(block)
            } else {
                proxyIds.add(ipcProxyPair.first)
            }
            Logger.i(LOG_TAG_PROXY, "lockdown wg for domain($domain) => return $proxyIds")
            return proxyIds
        }
        // add the domain-app specific config to the list
        if (dcProxyPair.first.isNotEmpty()) proxyIds.add(dcProxyPair.first) // domain-app specific
        */

        // check for app specific config
        val ac = ProxyManager.getProxyIdForApp(uid)
        // app-specific config can be empty, if the app is not configured
        // app-specific config id
        val acid = if (ac == ID_NONE) "" else ac // ignore id string if it is ID_NONE
        val appProxyPair = canUseConfig(acid, "app($uid)", usesMobileNw, ssid)
        if (!appProxyPair.second) {
            if (appProxyPair.first == block) {
                proxyIds.clear()
                proxyIds.add(block)
            } else {
                proxyIds.add(appProxyPair.first)
            }
            Logger.i(LOG_TAG_PROXY, "lockdown wg for app($uid) => return $proxyIds")
            return proxyIds
        }

        // add the app specific config to the list
        if (appProxyPair.first.isNotEmpty()) proxyIds.add(appProxyPair.first)

        /* TODO: commenting the code as v055o doesn't use universal ip and domain rules
        // check for universal ip config
        val uipc = IpRulesManager.hasProxy(UID_EVERYBODY, ip, port)
        val uipcProxyPair = canUseConfig(uipc.first, "univ-ip($ip:$port)", usesMeteredNw)
        if (!uipcProxyPair.second) {
            if (ipcProxyPair.first == block) {
                proxyIds.clear()
                proxyIds.add(block)
            } else {
                proxyIds.add(ipcProxyPair.first)
            }
            Logger.i(LOG_TAG_PROXY, "lockdown wg for univ-ip($ip:$port) => return $proxyIds")
            return proxyIds // no need to proceed further for lockdown
        }

        // add the universal ip config to the list
        if (uipcProxyPair.first.isNotEmpty()) proxyIds.add(uipcProxyPair.first) // universal ip

        // check for universal domain config
        val udc = DomainRulesManager.getProxyForDomain(UID_EVERYBODY, domain)
        val udcProxyPair = canUseConfig(udc.first, "univ-dom($domain)", usesMeteredNw)
        if (!udcProxyPair.second) {
            if (ipcProxyPair.first == block) {
                proxyIds.clear()
                proxyIds.add(block)
            } else {
                proxyIds.add(ipcProxyPair.first)
            }
            Logger.i(LOG_TAG_PROXY, "lockdown wg for univ-dom($domain) => return $proxyIds")
            return proxyIds // no need to proceed further for lockdown
        }

        // add the universal domain config to the list
        if (udcProxyPair.first.isNotEmpty()) proxyIds.add(udcProxyPair.first)*/

        // once the app-specific config is added, check if any catch-all config is enabled
        // if catch-all config is enabled, then add the config id to the list
        val cac = mappings.filter { it.isActive && it.isCatchAll }
        cac.forEach {
            if ((checkEligibilityBasedOnNw(it.id, usesMobileNw) && checkEligibilityBasedOnSsid(it.id, ssid)) && !proxyIds.contains(ID_WG_BASE + it.id)) {
                proxyIds.add(ID_WG_BASE + it.id)
                Logger.i(
                    LOG_TAG_PROXY,
                    "catch-all config is active: ${it.id}, ${it.name} => add ${ID_WG_BASE + it.id}"
                )
            }
        }

        if (proxyIds.isEmpty()) {
            Logger.i(LOG_TAG_PROXY, "no proxy ids found for $uid, $ip, $port, $domain; returning $default")
            return listOf(default)
        }

        // add the default proxy to the end, will not be true for lockdown but lockdown is handled
        // above, so no need to check here
        if (default.isNotEmpty() && !lockdown) proxyIds.add(default)

        // the proxyIds list will contain the ip-app specific, domain-app specific, app specific,
        // universal ip, universal domain, catch-all and default configs in the order of priority
        // the go-tun will check the routing based on the order of the list
        Logger.i(LOG_TAG_PROXY, "returning proxy ids for $uid, $ip, $port, $domain: $proxyIds")
        return proxyIds
    }

    // only when config is set to use on mobile network and current network is not mobile
    // then return false, all other cases return true
    private fun checkEligibilityBasedOnNw(id: Int, usesMobileNw: Boolean): Boolean {
        val config = mappings.find { it.id == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "canAdd: wg not found, id: $id, ${mappings.size}")
            return false
        }

        if (config.useOnlyOnMetered && !usesMobileNw) {
            Logger.i(LOG_TAG_PROXY, "canAdd: useOnlyOnMetered is true, but not metered nw")
            return false
        }

        Logger.d(LOG_TAG_PROXY, "canAdd: eligible for metered nw: $usesMobileNw")
        return true
    }

    private fun checkEligibilityBasedOnSsid(id: Int, ssid: String): Boolean {
        val config = mappings.find { it.id == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "canAdd: wg not found, id: $id, ${mappings.size}")
            return false
        }

        if (config.ssidEnabled) {
            val ssidItems = SsidItem.parseStorageList(config.ssids)
            if (ssidItems.isEmpty()) { // treat empty as match all
                Logger.d(LOG_TAG_PROXY, "canAdd: ssidEnabled is true, but ssid list is empty, match all")
                return true
            }

            val notEqualItems = ssidItems.filter { !it.type.isEqual }
            val notEqualMatch = notEqualItems.any { ssidItem ->
                when {
                    ssidItem.type.isExact -> {
                        ssidItem.name.equals(ssid, ignoreCase = true)
                    }

                    else -> { // wildcard
                        matchesWildcard(ssidItem.name, ssid)
                    }
                }
            }

            if (notEqualMatch) {
                Logger.d(LOG_TAG_PROXY, "canAdd: ssid matched in NOT_EQUAL items, return false")
                return false
            }

            val equalItems = ssidItems.filter { it.type.isEqual }
            // If there are only NOT_EQUAL items and none matched, return true
            if (equalItems.isEmpty() && notEqualItems.isNotEmpty()) {
                Logger.d(LOG_TAG_PROXY, "canAdd: only NOT_EQUAL items present and none matched, return true")
                return true
            }

            // Check EQUAL items (exact or wildcard)
            val equalMatch = equalItems.any { ssidItem ->
                when {
                    ssidItem.type.isExact -> {
                        ssidItem.name.equals(ssid, ignoreCase = true)
                    }

                    else -> { // wildcard
                        matchesWildcard(ssidItem.name, ssid)
                    }
                }
            }

            if (!equalMatch) {
                Logger.d(LOG_TAG_PROXY, "canAdd: ssid did not match in EQUAL items, return false")
                return false
            }
        }

        Logger.d(LOG_TAG_PROXY, "canAdd: eligible for ssid: $ssid")
        return true
    }

    private fun matchesWildcard(pattern: String, text: String): Boolean {
        // Convert wildcard pattern to regex
        // * matches any sequence of characters
        // ? matches any single character
        val regexPattern = pattern
            .replace(".", "\\.")  // Escape dots
            .replace("*", ".*")   // Convert * to .*
            .replace("?", ".")    // Convert ? to .

        return try {
            text.matches(Regex(regexPattern, RegexOption.IGNORE_CASE)) || text.contains(pattern, ignoreCase = true)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "Invalid wildcard pattern: $pattern, error: ${e.message}")
            false
        }
    }

    fun matchesSsidList(ssidList: String, ssid: String): Boolean {
        val ssidItems = SsidItem.parseStorageList(ssidList)
        if (ssidItems.isEmpty()) { // treat empty as match all
            return true
        }

        // Separate EQUAL items from NOT_EQUAL items
        val equalItems = ssidItems.filter { it.type.isEqual }
        val notEqualItems = ssidItems.filter { !it.type.isEqual }

        // Check NOT_EQUAL items first - if any match, return false
        val notEqualMatch = notEqualItems.any { ssidItem ->
            when {
                ssidItem.type.isExact -> {
                    ssidItem.name.equals(ssid, ignoreCase = true)
                }
                else -> { // wildcard
                    matchesWildcard(ssidItem.name, ssid)
                }
            }
        }

        if (notEqualMatch) {
            return false
        }

        // If there are only NOT_EQUAL items and none matched, return true
        if (equalItems.isEmpty() && notEqualItems.isNotEmpty()) {
            return true
        }

        // Check EQUAL items (exact or wildcard)
        return equalItems.any { ssidItem ->
            when {
                ssidItem.type.isExact -> {
                    ssidItem.name.equals(ssid, ignoreCase = true)
                }
                else -> { // wildcard
                    matchesWildcard(ssidItem.name, ssid)
                }
            }
        }
    }

    private fun convertStringIdToId(id: String): Int {
        return try {
            val configId = id.substring(ID_WG_BASE.length)
            configId.toIntOrNull() ?: INVALID_CONF_ID
        } catch (_: Exception) {
            Logger.i(LOG_TAG_PROXY, "err converting string id to int: $id")
            INVALID_CONF_ID
        }
    }

    suspend fun addConfig(config: Config?, name: String = ""): Config? {
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "err adding config")
            return null
        }
        // increment the id and add the config
        lastAddedConfigId += 1
        val id = lastAddedConfigId
        val n = name.ifEmpty { "${Backend.WG}$id" }
        val cfg =
            Config.Builder()
                .setId(id)
                .setName(n)
                .setInterface(config.getInterface())
                .addPeers(config.getPeers())
                .build()
        writeConfigAndUpdateDb(cfg)
        Logger.d(LOG_TAG_PROXY, "config added: ${config.getId()}, ${config.getName()}")
        return config
    }

    suspend fun addOrUpdateInterface(
        configId: Int,
        configName: String,
        wgInterface: WgInterface
    ): Config? {
        return if (configId <= 0) {
            addInterface(configName, wgInterface)
        } else {
            updateInterface(configId, configName, wgInterface)
        }
    }

    private suspend fun addInterface(configName: String, wgInterface: WgInterface): Config {
        // create a new config and add the interface
        lastAddedConfigId += 1
        val id = lastAddedConfigId
        val name = configName.ifEmpty { "wg$id" }
        val cfg = Config.Builder().setId(id).setName(name).setInterface(wgInterface).build()
        writeConfigAndUpdateDb(cfg)
        Logger.d(LOG_TAG_PROXY, "interface added for config: $id, $name")
        return cfg
    }

    private suspend fun updateInterface(
        configId: Int,
        configName: String,
        wgInterface: WgInterface
    ): Config? {
        val cfg: Config
        // update the interface for the config
        val config = configs.find { it.getId() == configId }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "updateInterface: wg not found, id: $configId, ${configs.size}")
            return null
        }
        cfg =
            Config.Builder()
                .setId(config.getId())
                .setName(configName)
                .setInterface(wgInterface)
                .addPeers(config.getPeers())
                .build()
        Logger.i(LOG_TAG_PROXY, "updating interface for config: $configId, ${config.getName()}")
        val cfgId = ID_WG_BASE + configId
        if (configName != config.getName()) {
            ProxyManager.updateProxyNameForProxyId(cfgId, configName)
        }
        writeConfigAndUpdateDb(cfg)
        return cfg
    }

    private fun getConfigFileName(id: Int): String {
        return "wg$id.conf"
    }

    fun deleteConfig(id: Int) {
        val cf = mappings.find { it.id == id }
        Logger.i(LOG_TAG_PROXY, "deleteConfig start: $id, ${cf?.name}, ${cf?.configPath}")

        // delete the config file
        val config = configs.find { it.getId() == id }
        if (cf?.isActive == true) {
            Logger.e(LOG_TAG_PROXY, "wg config is active for id: $id")
            disableConfig(cf)
        }

        io {
            val fileName = getConfigFileName(id)
            val file = File(getConfigFilePath(), fileName)
            if (file.exists()) {
                file.delete()
            }
            // delete the config from the database
            db.deleteConfig(id)
            val proxyId = ID_WG_BASE + id
            ProxyManager.removeProxyId(proxyId)
            mappings.remove(mappings.find { it.id == id })
            if (config != null) configs.remove(config)
            WgHopManager.handleWgDelete(id)
        }
    }

    suspend fun updateCatchAllConfig(id: Int, isEnabled: Boolean) {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "updateCatchAllConfig: wg not found, id: $id, ${configs.size}")
            return
        }
        Logger.i(LOG_TAG_PROXY, "updating catch all for config: $id, ${config.getName()}")
        db.updateCatchAllConfig(id, isEnabled)
        val m = mappings.find { it.id == id } ?: return
        mappings.remove(m)
        val newMap =
            WgConfigFilesImmutable(
                id,
                config.getName(),
                m.configPath,
                m.serverResponse,
                m.isActive,
                isEnabled, // just updating catch all field
                m.oneWireGuard,
                m.useOnlyOnMetered,
                m.isDeletable,
                m.ssidEnabled,
                m.ssids
            )
        mappings.add(newMap)

        enableConfig(newMap) // catch all should be always enabled
    }

    suspend fun updateOneWireGuardConfig(id: Int, owg: Boolean) {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "update one wg: id($id) not found, size: ${configs.size}")
            return
        }
        Logger.i(LOG_TAG_PROXY, "update one wg, id: $id, ${config.getName()} to $owg")
        db.updateOneWireGuardConfig(id, owg)
        val m = mappings.find { it.id == id } ?: return
        mappings.remove(m)
        mappings.add(
            WgConfigFilesImmutable(
                id,
                config.getName(),
                m.configPath,
                m.serverResponse,
                m.isActive,
                m.isCatchAll,
                owg, // updating just one wireguard field
                m.useOnlyOnMetered,
                m.isDeletable,
                m.ssidEnabled,
                m.ssids
            )
        )
    }

    suspend fun updateUseOnMobileNetworkConfig(id: Int, useMobileNw: Boolean) {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "update useMobileNw: wg not found, id: $id, ${configs.size}")
            return
        }
        Logger.i(LOG_TAG_PROXY, "updating useMobileNw as $useMobileNw for config: $id, ${config.getName()}")
        db.updateMobileConfig(id, useMobileNw)
        val m = mappings.find { it.id == id } ?: return
        mappings.remove(m)
        val newMap =
            WgConfigFilesImmutable(
                id,
                config.getName(),
                m.configPath,
                m.serverResponse,
                m.isActive,
                m.isCatchAll,
                m.oneWireGuard,
                useMobileNw, // just updating useMobileNw
                m.isDeletable,
                m.ssidEnabled,
                m.ssids
            )
        mappings.add(newMap)
        if (m.isActive) {
            VpnController.addWireGuardProxy(id = ID_WG_BASE + id)
            // Refresh proxies to immediately pause/resume based on new mobile-only setting and current network
            VpnController.refreshOrPauseOrResumeOrReAddProxies()
        }
    }

    suspend fun updateSsidEnabled(id: Int, ssidEnabled: Boolean) {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "update ssid: wg not found, id: $id, ${configs.size}")
            return
        }
        val m = mappings.find { it.id == id } ?: return
        // this can be disabled/enabled multiple times, based on the user permission so check
        // before updating
        if (m.ssidEnabled == ssidEnabled) {
            Logger.i(LOG_TAG_PROXY, "ssidEnabled is already $ssidEnabled for config: $id, ${config.getName()}")
            return
        }

        db.updateSsidEnabled(id, ssidEnabled)
        mappings.remove(m)
        val newMap =
            WgConfigFilesImmutable(
                id,
                config.getName(),
                m.configPath,
                m.serverResponse,
                m.isActive,
                m.isCatchAll,
                m.oneWireGuard,
                m.useOnlyOnMetered,
                m.isDeletable,
                ssidEnabled, // just updating ssidEnabled
                m.ssids
            )
        mappings.add(newMap)
        Logger.i(LOG_TAG_PROXY, "updated ssidEnabled as $ssidEnabled for config: $id, ${config.getName()}")
        if (m.isActive) {
            // trigger connection monitor to update the SSID info
            if (ssidEnabled) VpnController.notifyConnectionMonitor(enforcePolicyChange = true)
            VpnController.addWireGuardProxy(id = ID_WG_BASE + id)
            // Refresh proxies to immediately pause/resume based on new ssid setting and current network
            VpnController.refreshOrPauseOrResumeOrReAddProxies()
        }
    }

    suspend fun updateSsids(id: Int, ssids: String) {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "update ssids: wg not found, id: $id, ${configs.size}")
            return
        }
        Logger.i(LOG_TAG_PROXY, "updating ssids as $ssids for config: $id, ${config.getName()}")
        db.updateSsids(id, ssids)
        val m = mappings.find { it.id == id } ?: return
        mappings.remove(m)
        val newMap =
            WgConfigFilesImmutable(
                id,
                config.getName(),
                m.configPath,
                m.serverResponse,
                m.isActive,
                m.isCatchAll,
                m.oneWireGuard,
                m.useOnlyOnMetered,
                m.isDeletable,
                m.ssidEnabled,
                ssids // just updating ssids
            )
        mappings.add(newMap)
        if (m.isActive) {
            VpnController.addWireGuardProxy(id = ID_WG_BASE + id)
            // Refresh proxies to immediately pause/resume based on new ssid setting and current network
            VpnController.refreshOrPauseOrResumeOrReAddProxies()
        }
    }

    suspend fun addPeer(id: Int, peer: Peer) {
        // add the peer to the config
        val cfg: Config
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "addPeer: wg not found, id: $id, ${configs.size}")
            return
        }
        val peers = config.getPeers() ?: mutableListOf()
        val newPeers = peers.toMutableList()
        newPeers.add(peer)
        cfg =
            Config.Builder()
                .setId(config.getId())
                .setName(config.getName())
                .setInterface(config.getInterface())
                .addPeers(newPeers)
                .build()
        Logger.i(LOG_TAG_PROXY, "adding peer for config: $id, ${cfg.getName()}, ${newPeers.size}")
        writeConfigAndUpdateDb(cfg)
    }

    suspend fun deletePeer(id: Int, peer: Peer) {
        // delete the peer from the config
        val cfg: Config
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "deletePeer: wg not found, id: $id, ${configs.size}")
            return
        }
        val peers = config.getPeers()?.toMutableList()
        if (peers == null) {
            Logger.e(LOG_TAG_PROXY, "peers not found for config: $id")
            return
        }
        val isRemoved =
            peers.removeIf {
                it.getPublicKey() == peer.getPublicKey() &&
                        it.getEndpoint() == peer.getEndpoint() &&
                        it.getAllowedIps() == peer.getAllowedIps() &&
                        it.getPreSharedKey() == peer.getPreSharedKey()
            }
        Logger.d(
            LOG_TAG_PROXY,
            "new peers: ${peers.size}, ${peer.getPublicKey().base64()} is removed? $isRemoved"
        )
        cfg =
            Config.Builder()
                .setId(config.getId())
                .setName(config.getName())
                .setInterface(config.getInterface())
                .addPeers(peers)
                .build()
        Logger.i(LOG_TAG_PROXY, "deleting peer for config: $id, ${cfg.getName()}")
        writeConfigAndUpdateDb(cfg)
    }

    private fun addOrUpdateConfig(cfg: Config) {
        val existing = configs.find { it.getId() == cfg.getId() }
        if (existing != null) configs.remove(existing)
        configs.add(cfg)
    }

    private suspend fun writeConfigAndUpdateDb(cfg: Config, serverResponse: String = "") {
        // write the contents to the encrypted file
        val parsedCfg = cfg.toWgQuickString()
        val fileName = getConfigFileName(cfg.getId())
        try {
            EncryptedFileManager.writeWireguardConfig(applicationContext, parsedCfg, fileName)
        } catch (e: EncryptionException) {
            // Critical encryption failure - cannot save config
            Logger.e(LOG_TAG_PROXY, "Critical encryption failure writing wg config: ${cfg.getId()}", e)
            throw e // Bubble up to caller
        }
        val path = getConfigFilePath() + fileName
        Logger.i(LOG_TAG_PROXY, "writing wg config to file: $path")
        val file = db.isConfigAdded(cfg.getId())
        if (file == null) {
            val wgf =
                WgConfigFiles(
                    cfg.getId(),
                    cfg.getName(),
                    path,
                    serverResponse,
                    isActive = false,
                    isCatchAll = false,
                    oneWireGuard = false,
                    useOnlyOnMetered = false,
                    isDeletable = true,
                    ssidEnabled = false,
                    ssids = ""
                )
            db.insert(wgf)
            addOrUpdateConfigFileMapping(cfg, null, path, serverResponse)
        } else {
            file.name = cfg.getName()
            file.configPath = path
            file.serverResponse = serverResponse
            db.update(file)
            addOrUpdateConfigFileMapping(cfg, file.toImmutable(), path, serverResponse)
        }
        addOrUpdateConfig(cfg)
        if (file?.isActive == true) {
            VpnController.addWireGuardProxy(id = ID_WG_BASE + cfg.getId(), force = true)
        }
    }

    private fun addOrUpdateConfigFileMapping(
        cfg: Config,
        file: WgConfigFilesImmutable?,
        path: String,
        serverResponse: String
    ) {
        if (file == null) {
            val wgf =
                WgConfigFilesImmutable(
                    cfg.getId(),
                    cfg.getName(),
                    path,
                    serverResponse,
                    isActive = false,
                    isCatchAll = false,
                    oneWireGuard = false,
                    isDeletable = true,
                    useOnlyOnMetered = false,
                    ssidEnabled = false,
                    ssids = ""
                )
            mappings.add(wgf)
        } else {
            val configFile = mappings.find { it.id == cfg.getId() }
            mappings.remove(configFile)
            mappings.add(file)
        }
    }

    data class WgStats(val routerStats: RouterStats?, val mtu: Long?, val status: Long?, val ip4: Boolean?, val ip6: Boolean?)
    suspend fun stats(): String {
        val sb = StringBuilder()
        mappings.filter { it.isActive }.forEach {
            val id = ID_WG_BASE + it.id
            val stats = VpnController.getWireGuardStats(id)
            val routerStats = stats?.routerStats
            sb.append("   id: ${it.id}, name: ${it.name}\n")
            sb.append("   addr: ${routerStats?.addrs}").append("\n")
            sb.append("   mtu: ${stats?.mtu}\n")
            sb.append("   status: ${stats?.status}\n")
            sb.append("   ip4: ${stats?.ip4}\n")
            sb.append("   ip6: ${stats?.ip6}\n")
            sb.append("   rx: ${routerStats?.rx}\n")
            sb.append("   tx: ${routerStats?.tx}\n")
            sb.append("   lastRx: ${getRelativeTimeSpan(routerStats?.lastRx)}\n")
            sb.append("   lastTx: ${getRelativeTimeSpan(routerStats?.lastTx)}\n")
            sb.append("   lastGoodRx: ${getRelativeTimeSpan(routerStats?.lastGoodRx)}\n")
            sb.append("   lastGoodTx: ${getRelativeTimeSpan(routerStats?.lastGoodTx)}\n")
            sb.append("   lastOk: ${getRelativeTimeSpan(routerStats?.lastOK)}\n")
            sb.append("   since: ${getRelativeTimeSpan(routerStats?.since)}\n")
            sb.append("   errRx: ${routerStats?.errRx}\n")
            sb.append("   errTx: ${routerStats?.errTx}\n")
            sb.append("   extra: ${routerStats?.extra}\n\n")
        }
        if (sb.isEmpty()) {
            sb.append("   N/A\n\n")
        }
        return sb.toString()
    }

    private fun getRelativeTimeSpan(t: Long?): CharSequence? {
        if (t == null || t <= 0L) return "0"

        val now = System.currentTimeMillis()
        // returns a string describing 'time' as a time relative to 'now'
        return DateUtils.getRelativeTimeSpanString(
            t,
            now,
            DateUtils.SECOND_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }

    private fun getConfigFilePath(): String {
        return applicationContext.filesDir.absolutePath +
                File.separator +
                WIREGUARD_FOLDER_NAME +
                File.separator
    }

    fun getPeers(id: Int): MutableList<Peer> {
        return configs.find { it.getId() == id }?.getPeers()?.toMutableList() ?: mutableListOf()
    }

    suspend fun restoreProcessRetrieveWireGuardConfigs() {
        val count = db.getWgConfigs().size
        Logger.i(LOG_TAG_PROXY, "restored wg entries count: $count")
        clearLoadedConfigs()
        performRestore()
        load(forceRefresh = true)
    }

    suspend fun performRestore() {
        // during restore process, plain text wg configs are present in the temp dir
        // move the files to the wireguard directory and load the configs
        val tempDir = File(applicationContext.filesDir, TEMP_WG_DIR)
        val dbconfs = db.getWgConfigs()
        Logger.v(LOG_TAG_PROXY, "temp dir: ${tempDir.listFiles()?.size}, db size: ${dbconfs.size}")
        dbconfs.forEach { c ->
            // for each database entry, corresponding file with $id.conf is present in the temp dir
            // move the file to the wireguard directory with the name available in the database
            val file = File(tempDir, "${c.id}.conf")
            if (file.exists()) {
                Logger.i(LOG_TAG_PROXY, "file exists: ${file.absolutePath}, proceed restore")
            } else {
                Logger.i(LOG_TAG_PROXY, "no wg file, delete config: ${file.absolutePath}")
                db.deleteConfig(c.id)
                return@forEach
            }
            // read the contents of the file and write it to the EncryptedFileManager
            val bytes = file.readBytes()
            val encryptFile = File(c.configPath)
            val parentDir = encryptFile.parentFile
            if (parentDir == null) {
                Logger.e(LOG_TAG_PROXY, "wg restore failed, invalid path: ${c.configPath}")
                db.deleteConfig(c.id)
                return@forEach
            }
            if (!parentDir.exists() && !parentDir.mkdirs()) {
                Logger.e(LOG_TAG_PROXY, "wg restore failed, unable to create dir: ${parentDir.absolutePath}")
                db.deleteConfig(c.id)
                return@forEach
            }
            val created = runCatching {
                if (!encryptFile.exists()) {
                    encryptFile.createNewFile()
                } else {
                    true
                }
            }.getOrElse { ex ->
                Logger.w(LOG_TAG_PROXY, "wg restore failed, unable to create file: ${encryptFile.absolutePath}, err: ${ex.message}")
                db.deleteConfig(c.id)
                return@forEach
            }
            if (!created) {
                Logger.e(LOG_TAG_PROXY, "wg restore failed, createNewFile returned false: ${encryptFile.absolutePath}")
                db.deleteConfig(c.id)
                return@forEach
            }
            try {
                EncryptedFileManager.write(applicationContext, bytes, encryptFile)
                Logger.i(LOG_TAG_PROXY, "restored wg config: ${c.id}, ${c.name}")
            } catch (e: EncryptionException) {
                Logger.e(LOG_TAG_PROXY, "Critical encryption failure restoring wg config: ${c.id}, ${c.name}", e)
            }
        }

        val isResidueDeleted = Utilities.deleteRecursive(tempDir)
        if (isResidueDeleted) {
            Logger.i(LOG_TAG_PROXY, "deleted residue temp wg files: ${tempDir.absolutePath}")
        } else {
            Logger.w(LOG_TAG_PROXY, "failed to delete residue temp wg files: ${tempDir.absolutePath}")
            tempDir.deleteRecursively()
        }
    }

    fun oneWireGuardEnabled(): Boolean {
        return mappings.any { it.oneWireGuard && it.isActive }
    }

    fun getActiveSsidEnabledConfigs(): List<WgConfigFilesImmutable> {
        return mappings.filter { it.ssidEnabled && it.isActive }
    }

    fun getOneWireGuardProxyId(): Int? {
        return mappings.find { it.oneWireGuard && it.isActive }?.id
    }

    fun getActiveCatchAllConfig(): List<WgConfigFilesImmutable> {
        return mappings.filter { it.isActive && it.isCatchAll }
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
