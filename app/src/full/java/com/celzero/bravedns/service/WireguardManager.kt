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

import android.content.Context
import android.util.Log
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.customdownloader.IWireguardWarp
import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.database.WgConfigFilesRepository
import com.celzero.bravedns.util.Constants.Companion.WIREGUARD_FOLDER_NAME
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_PROXY
import com.celzero.bravedns.wireguard.BadConfigException
import com.celzero.bravedns.wireguard.Config
import com.celzero.bravedns.wireguard.Peer
import com.celzero.bravedns.wireguard.WgInterface
import ipn.Ipn
import ipn.Key
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.write
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import retrofit2.converter.gson.GsonConverterFactory

object WireGuardManager : KoinComponent {

    private val wgConfigFilesRepository: WgConfigFilesRepository by inject()
    private val applicationContext: Context by inject()
    private val appConfig: AppConfig by inject()
    private val persistentState: PersistentState by inject()

    private val lock = ReentrantReadWriteLock()

    // contains db values of wg configs (db stores path of the config file)
    private var mappings = mutableSetOf<WgConfigFiles>()
    // contains parsed wg configs
    private var configs = mutableSetOf<Config>()
    // retrieve last added config id
    private var lastAddedConfigId = 2

    // warp response json keys
    private const val JSON_RESPONSE_WORKS = "works"
    private const val JSON_RESPONSE_REASON = "reason"
    private const val JSON_RESPONSE_QUOTA = "quota"
    // warp primary and secondary config names, ids and file names
    const val SEC_WARP_NAME = "SEC_WARP"
    const val SEC_WARP_ID = 0
    const val SEC_WARP_FILE_NAME = "wg0.conf"
    const val WARP_NAME = "WARP"
    const val WARP_ID = 1
    const val WARP_FILE_NAME = "wg1.conf"
    // invalid config id
    const val INVALID_CONF_ID = -1

    init {
        io { load() }
    }

    suspend fun load() {
        // go through all files in the wireguard directory and load them
        // parse the files as those are encrypted
        // increment the id by 1, as the first config id is 0
        lastAddedConfigId = wgConfigFilesRepository.getLastAddedConfigId() + 1
        lock.write {
            if (configs.isNotEmpty()) {
                Log.i(LOG_TAG_PROXY, "configs already loaded")
                return
            }
            mappings = wgConfigFilesRepository.getWgConfigs().toMutableSet()
            mappings.forEach {
                val path = it.configPath
                val config =
                    EncryptedFileManager.readWireguardConfig(applicationContext, path)
                        ?: return@forEach
                config.setId(it.id)
                config.setName(it.name)
                if (DEBUG) Log.d(LOG_TAG_PROXY, "read wg config: ${it.id}, ${it.name}")
                configs.add(config)
            }
        }

        Log.i(LOG_TAG_PROXY, "Loaded wg configs: ${configs.size}")
    }

    private fun clearLoadedConfigs() {
        lock.write {
            configs.clear()
            mappings.clear()
        }
    }

    fun getConfigById(id: Int): Config? {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Log.e(LOG_TAG_PROXY, "getConfigById: wg not found: $id, ${configs.size}")
        }
        return config
    }

    fun getConfigFilesById(id: Int): WgConfigFiles? {
        val config = mappings.find { it.id == id }
        if (config == null) {
            Log.e(LOG_TAG_PROXY, "getConfigFilesById: wg not found: $id, ${configs.size}")
        }
        return config
    }

    fun getActiveConfigs(): List<Config> {
        val m = mappings.filter { it.isActive }
        val l = mutableListOf<Config>()
        m.forEach {
            val config = configs.find { it1 -> it1.getId() == it.id }
            if (config != null && !isWarp(config)) {
                l.add(config)
            }
        }
        return l
    }

    private fun isWarp(config: Config): Boolean {
        return config.getId() == WARP_ID || config.getId() == SEC_WARP_ID
    }

    fun isConfigActive(configId: String): Boolean {
        try {
            val id = configId.split(ProxyManager.ID_WG_BASE).last().toInt()
            val mapping = mappings.find { it.id == id }
            if (mapping != null) {
                return mapping.isActive
            }
            return false
        } catch (e: Exception) {
            Log.w(LOG_TAG_PROXY, "Exception while checking config active: ${e.message}")
        }
        return false
    }

    fun getWarpConfig(): Config? {
        // warp config will always be the first config in the list
        return configs.firstOrNull { it.getId() == WARP_ID }
    }

    fun getSecWarpConfig(): Config? {
        return configs.firstOrNull { it.getId() == SEC_WARP_ID }
    }

    fun isSecWarpAvailable(): Boolean {
        return configs.any { it.getId() == SEC_WARP_ID }
    }

    fun enableConfig(map: WgConfigFiles) {
        val config = configs.find { it.getId() == map.id }
        // no need to enable config if it is sec warp
        if (config == null || config.getId() == SEC_WARP_ID) {
            Log.w(LOG_TAG_PROXY, "Config not found or is SEC_WARP: ${map.id}")
            return
        }

        // enable the config, update to db, cache and tunnel
        map.isActive = true
        io { wgConfigFilesRepository.update(map) }
        mappings.find { it.id == map.id }?.isActive = true

        val proxyType = AppConfig.ProxyType.WIREGUARD
        val proxyProvider = AppConfig.ProxyProvider.WIREGUARD
        appConfig.addProxy(proxyType, proxyProvider)
        // in case of multiple wg configs, first proxy will be added with appConfig proxy pref
        // value, for the rest instead of calling updateTun, directly add the proxy to the
        // tunnel
        if (mappings.filter { it.isActive }.size > 1) {
            Log.w(LOG_TAG_PROXY, "More than one wg config is active")
            VpnController.addWireGuardProxy(ProxyManager.ID_WG_BASE + map.id)
        }
        Log.i(LOG_TAG_PROXY, "enable wg config: ${map.id}, ${map.name}")
        return
    }

    fun canEnableConfig(map: WgConfigFiles): Boolean {
        val canEnable = appConfig.canEnableProxy() && appConfig.canEnableWireguardProxy()
        if (!canEnable) {
            return false
        }
        val config = configs.find { it.getId() == map.id }
        if (config == null) {
            Log.e(LOG_TAG_PROXY, "canEnableConfig: wg not found, id: ${map.id}, ${configs.size}")
            return false
        }
        return true
    }

    fun getConfigName(id: Int): String {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Log.e(LOG_TAG_PROXY, "getConfigName: wg not found, id: ${id}, ${configs.size}")
            return ""
        }
        return config.getName()
    }

    fun disableConfig(id: String) {
        if (isConfigActive(id)) {
            val s = convertStringIdToId(id)
            val config = mappings.find { it.id == s }
            if (config != null) {
                disableConfig(config)
            }
        } else {
            Log.w(LOG_TAG_PROXY, "Config not active: $id")
        }
    }

    fun disableConfig(map: WgConfigFiles) {
        val config = configs.find { it.getId() == map.id }
        // no need to enable config if it is sec warp
        if (config == null || config.getId() == SEC_WARP_ID) {
            Log.w(LOG_TAG_PROXY, "Config not found or is SEC_WARP: ${map.id}")
            return
        }

        // disable the config, update to db, cache and tunnel
        map.isActive = false
        io { wgConfigFilesRepository.disableConfig(map.id) }
        mappings.find { it.id == map.id }?.isActive = false
        if (mappings.none { it.isActive }) {
            val proxyType = AppConfig.ProxyType.WIREGUARD
            val proxyProvider = AppConfig.ProxyProvider.WIREGUARD
            appConfig.removeProxy(proxyType, proxyProvider)
        }
        // directly remove the proxy from the tunnel, instead of calling updateTun
        VpnController.removeWireGuardProxy(ProxyManager.ID_WG_BASE + map.id)
        Log.i(LOG_TAG_PROXY, "disable wg config: ${map.id}, ${map.name}")
        return
    }

    suspend fun getNewWarpConfig(id: Int): Config? {
        try {
            val privateKey = Ipn.newPrivateKey()
            val publicKey = privateKey.mult().base64()
            val deviceName = android.os.Build.MODEL
            val locale = Locale.getDefault().toString()

            val retrofit =
                RetrofitManager.getWarpBaseBuilder(RetrofitManager.Companion.OkHttpDnsType.FALLBACK_DNS)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            val retrofitInterface = retrofit.create(IWireguardWarp::class.java)

            val response = retrofitInterface.getNewWarpConfig(publicKey, deviceName, locale)
            if (DEBUG) Log.d(LOG_TAG_PROXY, "New wg(warp) config: ${response?.body()}")

            return if (response?.isSuccessful == true) {
                val jsonObject = JSONObject(response.body().toString())
                val config = parseNewConfigJsonResponse(privateKey, jsonObject)
                if (config != null) {
                    configs
                        .find { it.getId() == WARP_ID || it.getId() == SEC_WARP_ID }
                        ?.let { configs.remove(it) }
                    config.setId(id)
                    if (id == WARP_ID) config.setName(WARP_NAME) else config.setName(SEC_WARP_NAME)
                    configs.add(config)
                    writeConfigAndUpdateDb(config, jsonObject.toString())
                }
                config
            } else {
                Log.w(
                    LOG_TAG_PROXY,
                    "err: new wg(warp) config: ${response?.message()}, ${response?.errorBody()}, ${response?.code()}"
                )
                null
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_PROXY, "err: new wg(warp) config: ${e.message}")
            return null
        }
    }

    suspend fun isWarpWorking(): Boolean {
        // create okhttp client with base url
        var works = false
        try {
            val retrofit =
                RetrofitManager.getWarpBaseBuilder(RetrofitManager.Companion.OkHttpDnsType.FALLBACK_DNS)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            val retrofitInterface = retrofit.create(IWireguardWarp::class.java)

            val response = retrofitInterface.isWarpConfigWorking()
            if (DEBUG)
                Log.d(
                    LOG_TAG_PROXY,
                    "new wg(warp) config: ${response?.headers()}, ${response?.message()}, ${response?.raw()?.request?.url}"
                )

            if (response?.isSuccessful == true) {
                val jsonObject = JSONObject(response.body().toString())
                works = jsonObject.optBoolean(JSON_RESPONSE_WORKS, false)
                val reason = jsonObject.optString(JSON_RESPONSE_REASON, "")
                Log.i(
                    LOG_TAG_PROXY,
                    "warp response for ${response.raw().request.url}, works? $works, reason: $reason"
                )
            } else {
                Log.w(LOG_TAG_PROXY, "unsuccessful response for ${response?.raw()?.request?.url}")
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_PROXY, "err checking warp(works): ${e.message}")
        }

        return works
    }

    fun getActiveConfigIdForApp(uid: Int): Int {
        val configId = ProxyManager.getProxyIdForApp(uid)
        if (configId == "" || !configId.contains(ProxyManager.ID_WG_BASE)) {
            Log.i(LOG_TAG_PROXY, "app config mapping not found for uid: $uid")
            return INVALID_CONF_ID
        }

        val id = convertStringIdToId(configId)
        val config = mappings.find { it.id == id } ?: return INVALID_CONF_ID
        return if (config.isActive) config.id else INVALID_CONF_ID
    }

    private fun convertStringIdToId(id: String): Int {
        return try {
            val configId = id.substring(ProxyManager.ID_WG_BASE.length)
            configId.toInt()
        } catch (e: Exception) {
            Log.e(LOG_TAG_PROXY, "err converting string id to int: $id")
            INVALID_CONF_ID
        }
    }

    private fun parseNewConfigJsonResponse(privateKey: Key, jsonObject: JSONObject?): Config? {
        // get the json tag "wgconf" from the response
        if (jsonObject == null) {
            Log.e(LOG_TAG_PROXY, "new warp config json object is null")
            return null
        }

        val jsonConfObject = jsonObject.optString("wgconf")
        // add the private key to the config after the term [Interface]
        val conf =
            jsonConfObject.replace(
                "[Interface]",
                "[Interface]\nPrivateKey = ${privateKey.base64()}"
            )
        // convert string to inputstream
        val configStream: InputStream =
            ByteArrayInputStream(conf.toByteArray(StandardCharsets.UTF_8))

        val cfg =
            try {
                Config.parse(configStream)
            } catch (e: BadConfigException) {
                Log.e(
                    LOG_TAG_PROXY,
                    "err parsing config: ${e.message}, ${e.reason}, ${e.text}, ${e.location}, ${e.section}, ${e.stackTrace}, ${e.cause}"
                )
                null
            }
        Log.i(LOG_TAG_PROXY, "New wireguard config: ${cfg?.getName()}, ${cfg?.getId()}")
        return cfg
    }

    fun addConfig(config: Config?): Config? {
        if (config == null) {
            Log.e(LOG_TAG_PROXY, "error adding config")
            return null
        }
        // increment the id and add the config
        lastAddedConfigId += 1
        val id = lastAddedConfigId
        val name = config.getName().ifEmpty { "${Ipn.WG}$id" }
        config.setName(name)
        config.setId(id)
        io { writeConfigAndUpdateDb(config) }
        if (DEBUG) Log.d(LOG_TAG_PROXY, "add config: ${config.getId()}, ${config.getName()}")
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
        if (DEBUG) Log.d(LOG_TAG_PROXY, "adding interface for config: $id, $name")
        writeConfigAndUpdateDb(cfg)
        return cfg
    }

    private suspend fun updateInterface(
        configId: Int,
        configName: String,
        wgInterface: WgInterface
    ): Config? {
        // update the interface for the config
        val config = configs.find { it.getId() == configId }
        if (config == null) {
            Log.e(LOG_TAG_PROXY, "updateInterface: wg not found, id: $configId, ${configs.size}")
            return null
        }
        val cfg =
            Config.Builder()
                .setId(config.getId())
                .setName(configName)
                .setInterface(wgInterface)
                .addPeers(config.getPeers())
                .build()
        Log.i(LOG_TAG_PROXY, "updating interface for config: $configId, ${config.getName()}")
        writeConfigAndUpdateDb(cfg)
        return cfg
    }

    private fun getConfigFileName(id: Int): String {
        return "wg$id.conf"
    }

    fun deleteConfig(id: Int) {
        val cf = mappings.find { it.id == id }
        Log.i(LOG_TAG_PROXY, "deleteConfig start: $id, ${cf?.name}, ${cf?.configPath}")
        mappings.forEach {
            Log.i(LOG_TAG_PROXY, "deleteConfig: ${it.id}, ${it.name}, ${it.configPath}")
        }
        val canDelete = cf?.isDeletable ?: false
        if (!canDelete) {
            Log.e(LOG_TAG_PROXY, "wg config not deletable for id: $id")
            return
        }
        // delete the config file
        val config = configs.find { it.getId() == id }
        if (cf?.isActive == true) {
            Log.e(LOG_TAG_PROXY, "wg config is active for id: $id")
            disableConfig(cf)
        }
        if (config == null) {
            Log.e(LOG_TAG_PROXY, "deleteConfig: wg not found, id: $id, ${configs.size}")
            io {
                wgConfigFilesRepository.deleteConfig(id)
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
            wgConfigFilesRepository.deleteConfig(id)
            val proxyId = ProxyManager.ID_WG_BASE + id
            ProxyManager.removeProxyForAllApps(proxyId)
            lock.write {
                mappings.remove(mappings.find { it.id == id })
                configs.remove(config)
            }
        }
    }

    suspend fun addPeer(id: Int, peer: Peer) {
        // add the peer to the config
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Log.e(LOG_TAG_PROXY, "addPeer: wg not found, id: $id, ${configs.size}")
            return
        }
        val peers = config.getPeers() ?: mutableListOf()
        val newPeers = peers.toMutableList()
        newPeers.add(peer)
        val cfg =
            Config.Builder()
                .setId(config.getId())
                .setName(config.getName())
                .setInterface(config.getInterface())
                .addPeers(newPeers)
                .build()
        Log.i(LOG_TAG_PROXY, "adding peer for config: $id, ${cfg.getName()}")
        writeConfigAndUpdateDb(cfg)
    }

    suspend fun deletePeer(id: Int, peer: Peer) {
        // delete the peer from the config
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Log.e(LOG_TAG_PROXY, "deletePeer: wg not found, id: $id, ${configs.size}")
            return
        }
        val peers = config.getPeers()?.toMutableList()
        if (peers == null) {
            Log.e(LOG_TAG_PROXY, "peers not found for config: $id")
            return
        }
        val isRemoved =
            peers.removeIf {
                it.getPublicKey() == peer.getPublicKey() &&
                    it.getEndpoint() == peer.getEndpoint() &&
                    it.getAllowedIps() == peer.getAllowedIps() &&
                    it.getPreSharedKey() == peer.getPreSharedKey()
            }
        if (DEBUG)
            Log.d(
                LOG_TAG_PROXY,
                "new peers: ${peers.size}, ${peer.getPublicKey().base64()} is removed? $isRemoved"
            )
        val cfg =
            Config.Builder()
                .setId(config.getId())
                .setName(config.getName())
                .setInterface(config.getInterface())
                .addPeers(peers)
                .build()
        Log.i(LOG_TAG_PROXY, "deleting peer for config: $id, ${cfg.getName()}")
        writeConfigAndUpdateDb(cfg)
    }

    private suspend fun writeConfigAndUpdateDb(cfg: Config, serverResponse: String = "") {
        // write the contents to the encrypted file
        val parsedCfg = cfg.toWgQuickString()
        val fileName = getConfigFileName(cfg.getId())
        EncryptedFileManager.writeWireguardConfig(applicationContext, parsedCfg, fileName)
        val path = getConfigFilePath() + fileName
        Log.i(LOG_TAG_PROXY, "writing wg config to file: $path")
        // no need to write the config to the database if it is default config / WARP
        if (cfg.getId() == WARP_ID || cfg.getId() == SEC_WARP_ID) {
            return
        }
        val file = wgConfigFilesRepository.isConfigAdded(cfg.getId())
        if (file == null) {
            val wgf = WgConfigFiles(cfg.getId(), cfg.getName(), path, serverResponse, false)
            wgConfigFilesRepository.insert(wgf)
        } else {
            file.name = cfg.getName()
            file.configPath = path
            file.serverResponse = serverResponse
            wgConfigFilesRepository.update(file)
        }
        addOrUpdateConfigFileMapping(cfg, file, path, serverResponse)
        addOrUpdateConfig(cfg)
        if (file?.isActive == true) {
            // updates the vpn if the config is active
            persistentState.wireguardUpdated = true
        }
    }

    private fun addOrUpdateConfig(cfg: Config) {
        val config = configs.find { it.getId() == cfg.getId() }
        lock.write {
            if (config == null) {
                configs.add(cfg)
            } else {
                configs.remove(config)
                configs.add(cfg)
            }
        }
    }

    private fun addOrUpdateConfigFileMapping(
        cfg: Config,
        file: WgConfigFiles?,
        path: String,
        serverResponse: String
    ) {
        lock.write {
            if (file == null) {
                val wgf = WgConfigFiles(cfg.getId(), cfg.getName(), path, serverResponse, false)
                mappings.add(wgf)
            } else {
                val configFile = mappings.find { it.id == cfg.getId() }
                mappings.remove(configFile)
                mappings.add(file)
            }
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
        // delete the WireGuard entries from the database
        io {
            val count = wgConfigFilesRepository.deleteOnAppRestore()
            ProxyManager.removeWgProxies()
            Log.i(LOG_TAG_PROXY, "Deleted wg entries: $count")
            clearLoadedConfigs()
        }
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
