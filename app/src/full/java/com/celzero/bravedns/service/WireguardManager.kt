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
import backend.Backend
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.database.WgConfigFilesImmutable
import com.celzero.bravedns.database.WgConfigFilesRepository
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Constants.Companion.WIREGUARD_FOLDER_NAME
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.Peer
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

    suspend fun load(): Int {
        // go through all files in the wireguard directory and load them
        // parse the files as those are encrypted
        // increment the id by 1, as the first config id is 0
        lastAddedConfigId = db.getLastAddedConfigId()
        if (configs.isNotEmpty()) {
            Logger.i(LOG_TAG_PROXY, "configs already loaded; refreshing...")
        }
        val m = db.getWgConfigs().map { it.toImmutable() }
        mappings = CopyOnWriteArraySet(m)
        mappings.forEach {
            val path = it.configPath
            val config =
                EncryptedFileManager.readWireguardConfig(applicationContext, path)
            if (config == null) {
                Logger.e(LOG_TAG_PROXY, "err loading wg config: $path, invalid config")
                // TODO: delete the config from the db?
                return@forEach
            }
            // print the config to logcat

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

    fun getAllConfigs(): List<Config> {
        return configs.toList()
    }

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
            val id = configId.split(ProxyManager.ID_WG_BASE).last().toIntOrNull() ?: return false
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

    fun enableConfig(unmapped: WgConfigFilesImmutable) {
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
                map.isDeletable
            )
        mappings.add(newMap)
        val dbMap = WgConfigFiles.fromImmutable(newMap)
        io { db.update(dbMap) }
        val proxyType = AppConfig.ProxyType.WIREGUARD
        val proxyProvider = AppConfig.ProxyProvider.WIREGUARD
        appConfig.addProxy(proxyType, proxyProvider)
        VpnController.addWireGuardProxy(ProxyManager.ID_WG_BASE + map.id)
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

    fun canEnableWg(): Boolean {
        val canEnableProxy = appConfig.canEnableProxy()
        val canEnableWireGuardProxy = appConfig.canEnableWireguardProxy()
        val canEnable = canEnableProxy && canEnableWireGuardProxy
        Logger.i(LOG_TAG_PROXY, "canEnableConfig? $canEnableProxy && $canEnableWireGuardProxy")
        return canEnable
    }

    fun canDisableConfig(map: WgConfigFilesImmutable): Boolean {
        // do not allow to disable the proxy if it is catch-all
        return !map.isCatchAll
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
        VpnController.removeWireGuardProxy(newMap.id)
        Logger.i(LOG_TAG_PROXY, "disable wg config: ${newMap.id}, ${newMap.name}")
        return
    }

    suspend fun getAllPossibleConfigIdsForApp(uid: Int, ip: String, port: Int, domain: String, default: String = ""): List<String> {
        val proxyIds: MutableList<String> = mutableListOf()
        val configId = ProxyManager.getProxyIdForApp(uid)

        val ipConfig = IpRulesManager.hasProxy(uid, ip, port)
        val domainConfig = DomainRulesManager.getProxyForDomain(uid, domain)

        val univIpConfig = IpRulesManager.hasProxy(UID_EVERYBODY, ip, port)
        val univDomainConfig = DomainRulesManager.getProxyForDomain(UID_EVERYBODY, domain)

        if (ipConfig.first.isNotEmpty()) {
            Logger.i(LOG_TAG_PROXY, "wg id for ip $ip:$port => ${ipConfig.first}")
            proxyIds.add(ipConfig.first)
            return proxyIds
        }

        if (domainConfig.first.isNotEmpty()) {
            Logger.i(LOG_TAG_PROXY, "wg id for domain $domain => ${domainConfig.first}")
            proxyIds.add(domainConfig.first)
            return proxyIds
        }

        val id = if (configId.isNotEmpty()) convertStringIdToId(configId) else INVALID_CONF_ID
        val config = if (id == INVALID_CONF_ID) null else mappings.find { it.id == id }

        if (config != null && config.isLockdown) {
            Logger.i(LOG_TAG_PROXY, "lockdown enabled for app: $uid => $configId")
            proxyIds.add(configId)
            return proxyIds
        }

        if (config != null && config.isActive) {
            proxyIds.add(configId)
        }

        if (univIpConfig.first.isNotEmpty()) {
            proxyIds.clear()
            proxyIds.add(univIpConfig.first)
            return proxyIds
        }

        if (univDomainConfig.first.isNotEmpty()) {
            proxyIds.clear()
            proxyIds.add(univDomainConfig.first)
            return proxyIds
        }

        // once the app-specific config is added, check if any catch-all config is enabled
        // if catch-all config is enabled, then add the config id to the list
        val catchAllConfig = mappings.filter { it.isActive && it.isCatchAll }
        catchAllConfig.forEach {
            proxyIds.add(ProxyManager.ID_WG_BASE + it.id)
        }

        // add the default proxy to the end, will not be true for lockdown
        if (default.isNotEmpty()) proxyIds.add(default)

        return proxyIds
    }

    private fun convertStringIdToId(id: String): Int {
        return try {
            val configId = id.substring(ProxyManager.ID_WG_BASE.length)
            configId.toIntOrNull() ?: INVALID_CONF_ID
        } catch (e: Exception) {
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
        val cfgId = ProxyManager.ID_WG_BASE + configId
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
        mappings.forEach {
            Logger.i(LOG_TAG_PROXY, "deleteConfig: ${it.id}, ${it.name}, ${it.configPath}")
        }
        val canDelete = cf?.isDeletable ?: false
        if (!canDelete) {
            Logger.e(LOG_TAG_PROXY, "wg config not deletable for id: $id")
            return
        }
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
            val proxyId = ProxyManager.ID_WG_BASE + id
            ProxyManager.removeProxyId(proxyId)
            mappings.remove(mappings.find { it.id == id })
            configs.remove(config)
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
                m.isDeletable
            )
        )
        if (map?.isActive == true) {
            VpnController.addWireGuardProxy(id = ProxyManager.ID_WG_BASE + config.getId())
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
                m.isDeletable
            )
        )
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
                    oneWireGuard = false
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
            VpnController.addWireGuardProxy(id = ProxyManager.ID_WG_BASE + cfg.getId())
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
                    isDeletable = true
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

    fun restoreProcessDeleteWireGuardEntries() {
        // during a restore, we do not posses the keys to decrypt the wireguard configs
        // so, delete the wireguard configs carried over from the backup
        io {
            val count = db.deleteOnAppRestore()
            ProxyManager.removeWgProxies()
            Logger.i(LOG_TAG_PROXY, "deleted wg entries count: $count")
            clearLoadedConfigs()
            load()
        }
    }

    fun oneWireGuardEnabled(): Boolean {
        return mappings.any { it.oneWireGuard && it.isActive }
    }

    fun getOneWireGuardProxyId(): Int? {
        return mappings.find { it.oneWireGuard && it.isActive }?.id
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
