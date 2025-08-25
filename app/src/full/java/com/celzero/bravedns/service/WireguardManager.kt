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
import com.celzero.firestack.backend.Backend
import com.celzero.bravedns.backup.BackupHelper.Companion.TEMP_WG_DIR
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.database.WgConfigFilesRepository
import com.celzero.bravedns.service.ProxyManager.ID_NONE
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.util.Constants.Companion.WIREGUARD_FOLDER_NAME
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.Peer
import com.celzero.bravedns.wireguard.WgHopManager
import com.celzero.bravedns.wireguard.WgInterface
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

    // 120 sec + 5 sec buffer
    const val WG_HANDSHAKE_TIMEOUT = 125 * DateUtils.SECOND_IN_MILLIS
    const val WG_UPTIME_THRESHOLD = 5 * DateUtils.SECOND_IN_MILLIS

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
            val config =
                EncryptedFileManager.readWireguardConfig(applicationContext, path)
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

        // enable the config, update to db, cache and tunnel
        mappings.remove(map)
        val newMap =
            WgConfigFilesImmutable(
                map.id,
                map.name,
                map.configPath,
                map.serverResponse,
                true, // also update mappings: https://pl.kotl.in/g0mVapn4x
                map.isCatchAll,
                map.isLockdown,
                map.oneWireGuard,
                map.useOnlyOnMetered,
                map.isDeletable
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
                m.isLockdown,
                false, // confirms with db.disableConfig query
                m.useOnlyOnMetered,
                m.isDeletable
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
    private suspend fun canUseConfig(idStr: String, type: String, usesMtrdNw: Boolean): Pair<String, Boolean> {
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

        if (config.isLockdown && checkEligibilityBasedOnNw(id, usesMtrdNw)) {
            Logger.d(LOG_TAG_PROXY, "lockdown wg for $type => return $idStr")
            return Pair(idStr, false) // no need to proceed further for lockdown
        }

        // in case of lockdown and not metered network, we need to return block as the
        // lockdown should not leak the connections via WiFi
        if (config.isLockdown) {
            // add IpnBlock instead of the config id, let the connection be blocked in WiFi
            // regardless of config is active or not
            Logger.d(LOG_TAG_PROXY, "lockdown wg for $type => return $block")
            return Pair(block, false) // no need to proceed further for lockdown
        }

        // check if the config is active and if it can be used on this network
        if (config.isActive && checkEligibilityBasedOnNw(id, usesMtrdNw)) {
            Logger.d(LOG_TAG_PROXY, "active wg for $type => add $idStr")
            return Pair(idStr, true)
        }

        return Pair("", true)
    }

    // no need to check for app excluded from proxy here, expected to call this fn after that
    suspend fun getAllPossibleConfigIdsForApp(uid: Int, ip: String, port: Int, domain: String, usesMobileNw: Boolean, default: String): List<String> {
        val block = Backend.Block
        val proxyIds: MutableList<String> = mutableListOf()
        if (oneWireGuardEnabled()) {
            val id = getOneWireGuardProxyId()
            if (id == null || id == INVALID_CONF_ID) {
                Logger.e(LOG_TAG_PROXY, "canAdd: one-wg not found, id: $id, return empty")
                return emptyList<String>()
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
            if (default.isNotEmpty()) proxyIds.add(default)
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
        val appProxyPair = canUseConfig(acid, "app($uid)", usesMobileNw)
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
            if (checkEligibilityBasedOnNw(it.id, usesMobileNw)) {
                proxyIds.add(ID_WG_BASE + it.id)
                Logger.i(
                    LOG_TAG_PROXY,
                    "catch-all config is active: ${it.id}, ${it.name} => add ${ID_WG_BASE + it.id}"
                )
            }
        }

        if (proxyIds.isEmpty()) {
            Logger.i(LOG_TAG_PROXY, "no proxy ids found for $uid, $ip, $port, $domain; returning empty list")
            return emptyList()
        }
        // see if any id is part of lockdown, if so, then return the list, cac's can have lockdown
        val isAnyIdLockdown = proxyIds.any { id ->
            val confId = convertStringIdToId(id)
            val conf = mappings.find { it.id == confId }
            conf?.isLockdown ?: false
        }

        // add the default proxy to the end, will not be true for lockdown but lockdown is handled
        // above, so no need to check here
        if (default.isNotEmpty() && !isAnyIdLockdown) proxyIds.add(default)

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

    private fun convertStringIdToId(id: String): Int {
        return try {
            val configId = id.substring(ID_WG_BASE.length)
            configId.toIntOrNull() ?: INVALID_CONF_ID
        } catch (ignored: Exception) {
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

        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "deleteConfig: wg not found, id: $id, ${configs.size}")
            io {
                db.deleteConfig(id)
                mappings.remove(mappings.find { it.id == id })
            }
            return
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
            configs.remove(config)
            WgHopManager.handleWgDelete(id)
        }
    }

    suspend fun updateLockdownConfig(id: Int, isLockdown: Boolean) {
        val config = configs.find { it.getId() == id }
        val map = mappings.find { it.id == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "updateLockdownConfig: wg not found, id: $id, ${configs.size}")
            return
        }
        Logger.i(LOG_TAG_PROXY, "updating lockdown for config: $id, ${config.getPeers()}")
        db.updateLockdownConfig(id, isLockdown)
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
                isLockdown, // just updating lockdown field
                m.oneWireGuard,
                m.useOnlyOnMetered,
                m.isDeletable
            )
        )
        if (map?.isActive == true) {
            VpnController.addWireGuardProxy(id = ID_WG_BASE + config.getId())
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
                m.isLockdown,
                m.oneWireGuard,
                m.useOnlyOnMetered,
                m.isDeletable
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
                m.isLockdown,
                owg, // updating just one wireguard field
                m.useOnlyOnMetered,
                m.isDeletable
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
                m.isLockdown,
                m.oneWireGuard,
                useMobileNw, // just updating useOnMetered field
                m.isDeletable
            )
        mappings.add(newMap)
        if (m.isActive) {
            VpnController.addWireGuardProxy(id = ID_WG_BASE + id)
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

    private suspend fun writeConfigAndUpdateDb(cfg: Config, serverResponse: String = "") {
        // write the contents to the encrypted file
        val parsedCfg = cfg.toWgQuickString()
        val fileName = getConfigFileName(cfg.getId())
        EncryptedFileManager.writeWireguardConfig(applicationContext, parsedCfg, fileName)
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
                    isLockdown = false,
                    oneWireGuard = false,
                    useOnlyOnMetered = false
                )
            db.insert(wgf)
        } else {
            file.name = cfg.getName()
            file.configPath = path
            file.serverResponse = serverResponse
            db.update(file)
        }
        addOrUpdateConfigFileMapping(cfg, file?.toImmutable(), path, serverResponse)
        addOrUpdateConfig(cfg)
        if (file?.isActive == true) {
            VpnController.addWireGuardProxy(id = ID_WG_BASE + cfg.getId(), force = true)
        }
    }

    private fun addOrUpdateConfig(cfg: Config) {
        val config = configs.find { it.getId() == cfg.getId() }
        if (config == null) {
            configs.add(cfg)
        } else {
            configs.remove(config)
            configs.add(cfg)
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
                    isLockdown = false,
                    oneWireGuard = false,
                    isDeletable = true,
                    useOnlyOnMetered = false
                )
            mappings.add(wgf)
        } else {
            val configFile = mappings.find { it.id == cfg.getId() }
            mappings.remove(configFile)
            mappings.add(file)
        }
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
            if (!encryptFile.exists()) {
                encryptFile.parentFile?.mkdirs()
                encryptFile.createNewFile()
            }
            val res = EncryptedFileManager.write(applicationContext, bytes, encryptFile)
            if (res) {
                Logger.i(LOG_TAG_PROXY, "restored wg config: ${c.id}, ${c.name}")
            } else {
                Logger.e(LOG_TAG_PROXY, "err restoring wg config: ${c.id}, ${c.name}")
                // in case of error, delete the entry from the database
                db.deleteConfig(c.id)
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
