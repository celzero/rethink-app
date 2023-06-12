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
import com.celzero.bravedns.database.WgApplicationMapping
import com.celzero.bravedns.database.WgApplicationMappingRepository
import com.celzero.bravedns.database.WgConfigFiles
import com.celzero.bravedns.database.WgConfigFilesRepository
import com.celzero.bravedns.util.Constants.Companion.WIREGUARD_FOLDER_NAME
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_WIREGUARD
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import retrofit2.converter.gson.GsonConverterFactory

object WireguardManager : KoinComponent {

    private val wgConfigFilesRepository: WgConfigFilesRepository by inject()
    private val wgApplicationMappingRepository: WgApplicationMappingRepository by inject()
    private val applicationContext: Context by inject()

    private var configFileMappings = mutableSetOf<WgConfigFiles>()
    private var configs = mutableSetOf<Config>()
    private var appConfigMappings = mutableSetOf<WgApplicationMapping>()

    private const val JSON_RESPONSE_WORKS = "works"
    private const val JSON_RESPONSE_REASON = "reason"
    private const val JSON_RESPONSE_QUOTA = "quota"
    private const val WARP_NAME = "WARP"
    const val WARP_FILE_NAME = "wg0.conf"

    suspend fun load() {
        if (configs.isNotEmpty()) {
            Log.i(LOG_TAG_WIREGUARD, "configs already loaded")
            return
        }
        // go through all files in the wireguard directory and load them
        // parse the files as those are encrypted
        configFileMappings = wgConfigFilesRepository.getWgConfigs().toMutableSet()
        configFileMappings.forEach {
            val path = it.configPath
            val config = EncryptedFileManager.read(applicationContext, path) ?: return@forEach
            config.setId(it.id)
            config.setName(it.name)
            if (DEBUG)
                Log.d(
                    LOG_TAG_WIREGUARD,
                    "read wg config: ${it.id}, ${it.name}, ${config.toWgQuickString()}"
                )
            configs.add(config)
        }

        appConfigMappings = wgApplicationMappingRepository.getWgAppMapping().toMutableSet()
        Log.i(LOG_TAG_WIREGUARD, "Loaded wg configs: ${configs.size}")
    }

    fun getConfigById(id: Int): Config? {
        val config = configs.find { it.getId() == id }
        if (config == null) {
            Log.e(LOG_TAG_WIREGUARD, "Config not found for id: $id, ${configs.size}")
        }
        return config
    }

    fun getActiveConfigs(): List<Config> {
        val configFiles = configFileMappings.filter { it.isActive }
        val configList = mutableListOf<Config>()
        configs.forEach {
            if (configFiles.find { it1 -> it1.id == it.getId() } != null) {
                configList.add(it)
            }
        }
        return configList
    }

    fun getWarpConfig(): Config? {
        // warp config will always be the first config in the list
        return configs.firstOrNull { it.getId() == 0 }
    }

    fun enableConfig(configFiles: WgConfigFiles) {
        val config = configs.find { it.getId() == configFiles.id }
        if (config == null) {
            Log.e(
                LOG_TAG_WIREGUARD,
                "wg config not found for id: ${configFiles.id}, ${configs.size}"
            )
            return
        }
        configFiles.isActive = true
        io { wgConfigFilesRepository.update(configFiles) }
        configFileMappings.find { it.id == configFiles.id }?.isActive = true
        Log.d(LOG_TAG_WIREGUARD, "enable wg config: ${configFiles.id}, ${configFiles.name}")
        return
    }

    fun disableConfig(configFiles: WgConfigFiles) {
        val config = configs.find { it.getId() == configFiles.id }
        if (config == null) {
            Log.e(
                LOG_TAG_WIREGUARD,
                "wg config not found for id: ${configFiles.id}, ${configs.size}"
            )
            return
        }
        configFiles.isActive = false
        io { wgConfigFilesRepository.update(configFiles) }
        configFileMappings.find { it.id == configFiles.id }?.isActive = false
        Log.d(LOG_TAG_WIREGUARD, "disable wg config: ${configFiles.id}, ${configFiles.name}")
        return
    }

    suspend fun getNewWarpConfig(): Config? {
        try {
            val privateKey = Ipn.newPrivateKey()
            val publicKey = privateKey.mult().base64()
            val deviceName = android.os.Build.MODEL
            val locale = Locale.getDefault().toString()
            // create okhttp client with base url
            val retrofit =
                RetrofitManager.getWarpBaseBuilder(RetrofitManager.Companion.OkHttpDnsType.DEFAULT)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            val retrofitInterface = retrofit.create(IWireguardWarp::class.java)

            val response = retrofitInterface.getNewWarpConfig(publicKey, deviceName, locale)
            if (DEBUG) Log.d(LOG_TAG_WIREGUARD, "New wg(warp) config: ${response?.body()}")

            return if (response?.isSuccessful == true) {
                val jsonObject = JSONObject(response.body().toString())
                val config = parseNewConfigJsonResponse(privateKey, jsonObject)
                if (config != null) {
                    configs.find { it.getId() == 0 }?.let { configs.remove(it) }
                    configs.add(config)
                    writeConfigAndUpdateDb(config, jsonObject.toString())
                }
                config
            } else {
                Log.w(
                    LOG_TAG_WIREGUARD,
                    "Error getting new wg(warp) config: ${response?.message()}, ${response?.errorBody()}, ${response?.code()}"
                )
                null
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG_WIREGUARD, "Error getting new wg(warp) config: ${e.message}")
            return null
        }
    }

    suspend fun isWarpWorking(): Boolean {
        // create okhttp client with base url
        var works = false
        val retrofit =
            RetrofitManager.getWarpBaseBuilder(RetrofitManager.Companion.OkHttpDnsType.DEFAULT)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
        val retrofitInterface = retrofit.create(IWireguardWarp::class.java)

        val response = retrofitInterface.isWarpConfigWorking()
        if (DEBUG)
            Log.d(
                LOG_TAG_WIREGUARD,
                "new wg(warp) config: ${response?.headers()}, ${response?.message()}, ${response?.raw()?.request?.url}"
            )

        if (response?.isSuccessful == true) {
            val jsonObject = JSONObject(response.body().toString())
            works = jsonObject.optBoolean(JSON_RESPONSE_WORKS, false)
            val reason = jsonObject.optString(JSON_RESPONSE_REASON, "")
            Log.i(
                LOG_TAG_WIREGUARD,
                "warp response for ${response.raw().request.url}, works? $works, reason: $reason"
            )
        } else {
            Log.w(LOG_TAG_WIREGUARD, "unsuccessful response for ${response?.raw()?.request?.url}")
        }

        return works
    }

    fun isAnyWgConfigActive(): Boolean {
        return configFileMappings.any { it.isActive }
    }

    private fun parseNewConfigJsonResponse(privateKey: Key, jsonObject: JSONObject?): Config? {
        // get the json tag "wgconf" from the response
        if (jsonObject == null) {
            Log.e(LOG_TAG_WIREGUARD, "Json object is null")
            return null
        }

        val jsonConfObject = jsonObject.optString("wgconf")
        Log.d(LOG_TAG_WIREGUARD, "Before replace: $jsonConfObject")
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
                    LOG_TAG_WIREGUARD,
                    "Error parsing config: ${e.message}, ${e.reason}, ${e.text}, ${e.location}, ${e.section}, ${e.stackTrace}, ${e.cause}"
                )
                null
            }
        cfg?.setId(0)
        cfg?.setName(WARP_NAME)
        Log.i(LOG_TAG_WIREGUARD, "New warp config: ${cfg?.toWgQuickString()}")
        return cfg
    }

    fun addConfig(config: Config?): Config? {
        if (config == null) {
            Log.e(LOG_TAG_WIREGUARD, "Error adding config")
            return null
        }
        // increment the id and add the config
        val id = configFileMappings.size + 1
        val name = config.getName().ifEmpty { "${Ipn.WG}$id" }
        config.setName(name)
        config.setId(id)
        writeConfigAndUpdateDb(config)
        configs.add(config)
        if (DEBUG) Log.d(LOG_TAG_WIREGUARD, "Add config: ${config.getId()}, ${config.getName()}")
        return config
    }

    fun addOrUpdateInterface(configId: Int, configName: String, wgInterface: WgInterface): Config? {
        return if (configId <= 0) {
            addInterface(configName, wgInterface)
        } else {
            updateInterface(configId, configName, wgInterface)
        }
    }

    private fun addInterface(configName: String, wgInterface: WgInterface): Config {
        // create a new config and add the interface
        val id = configFileMappings.size + 1
        val name = configName.ifEmpty { "wg$id" }
        val cfg = Config.Builder().setId(id).setName(name).setInterface(wgInterface).build()
        configs.add(cfg)
        if (DEBUG)
            Log.d(
                LOG_TAG_WIREGUARD,
                "Adding interface for config: $id, $name, ${wgInterface.getKeyPair().getPublicKey().base64()}"
            )
        writeConfigAndUpdateDb(cfg)
        return cfg
    }

    private fun updateInterface(
        configId: Int,
        configName: String,
        wgInterface: WgInterface
    ): Config? {
        // update the interface for the config
        val config = configs.find { it.getId() == configId }
        if (config == null) {
            Log.e(LOG_TAG_WIREGUARD, "wg config not found for id: $configId, ${configs.size}")
            return null
        }
        val cfg =
            Config.Builder()
                .setId(config.getId())
                .setName(configName)
                .setInterface(wgInterface)
                .addPeers(config.getPeers())
                .build()
        Log.i(
            LOG_TAG_WIREGUARD,
            "Updating interface for config: $configId, ${config.toWgQuickString()}"
        )
        configs.remove(config)
        configs.add(cfg)
        writeConfigAndUpdateDb(cfg)
        return cfg
    }

    private fun getConfigFileName(cfgId: Int): String {
        return "wg$cfgId.conf"
    }

    fun deleteConfig(configId: Int) {
        val canDelete = configFileMappings.find { it.id == configId }?.isDeletable ?: false
        if (!canDelete) {
            Log.e(LOG_TAG_WIREGUARD, "wg config not deletable for id: $configId")
            return
        }
        // delete the config file
        val config = configs.find { it.getId() == configId }
        if (config == null) {
            Log.e(LOG_TAG_WIREGUARD, "wg config not found for id: $configId, ${configs.size}")
            io {
                wgConfigFilesRepository.deleteConfig(configId)
                configFileMappings.remove(configFileMappings.find { it.id == configId })
            }
            return
        }
        io {
            val fileName = getConfigFileName(configId)
            val file = File(getConfigFilePath(), fileName)
            if (file.exists()) {
                file.delete()
            }
            // delete the config from the database
            wgConfigFilesRepository.deleteConfig(configId)
            configFileMappings.remove(configFileMappings.find { it.id == configId })
            configs.remove(config)
            wgApplicationMappingRepository.removeAllAppsFromInterface(configId)
        }
    }

    fun addPeer(configId: Int, peer: Peer) {
        // add the peer to the config
        val config = configs.find { it.getId() == configId }
        if (config == null) {
            Log.e(LOG_TAG_WIREGUARD, "wg config not found for id: $configId, ${configs.size}")
            return
        }
        val peers = config.getPeers() ?: mutableListOf()
        Log.i(
            LOG_TAG_WIREGUARD,
            "Adding peer for config: $configId, old: ${config.getPeers()}, ${peers.size}"
        )
        val newPeers = peers.toMutableList()
        newPeers.add(peer)
        val cfg =
            Config.Builder()
                .setId(config.getId())
                .setName(config.getName())
                .setInterface(config.getInterface())
                .addPeers(newPeers)
                .build()
        Log.i(
            LOG_TAG_WIREGUARD,
            "Adding peer for config: $configId, ${config.getPeers()}, ${cfg.getPeers()}"
        )
        configs.remove(config)
        configs.add(cfg)
        writeConfigAndUpdateDb(cfg)
    }

    fun deletePeer(configId: Int, peer: Peer) {
        // delete the peer from the config
        val config = configs.find { it.getId() == configId }
        if (config == null) {
            Log.e(LOG_TAG_WIREGUARD, "wg config not found for id: $configId, ${configs.size}")
            return
        }
        val peers = config.getPeers()
        if (peers == null) {
            Log.e(LOG_TAG_WIREGUARD, "Peers not found for config: $configId")
            return
        }
        val newPeers = peers.filter { it.getPublicKey() != peer.getPublicKey() }
        val cfg =
            Config.Builder()
                .setId(config.getId())
                .setName(config.getName())
                .setInterface(config.getInterface())
                .addPeers(newPeers)
                .build()
        Log.i(
            LOG_TAG_WIREGUARD,
            "Deleting peer for config: $configId, ${config.getPeers()}, ${cfg.getPeers()}"
        )
        configs.remove(config)
        configs.add(cfg)
        writeConfigAndUpdateDb(cfg)
    }

    private fun writeConfigAndUpdateDb(cfg: Config, serverResponse: String = "") {
        // write the contents to the encrypted file
        io {
            val parsedCfg = cfg.toWgQuickString()
            val fileName = getConfigFileName(cfg.getId())
            EncryptedFileManager.write(applicationContext, parsedCfg, fileName)
            val path = getConfigFilePath() + fileName
            Log.i(LOG_TAG_WIREGUARD, "Writing wg config to file: $path")
            // no need to write the config to the database if it is default config / WARP
            if (cfg.getId() == 0) {
                return@io
            }
            val wgf = WgConfigFiles(cfg.getId(), cfg.getName(), path, serverResponse, false)
            configFileMappings.add(wgf)
            wgConfigFilesRepository.insert(wgf)
        }
    }

    private fun getConfigFilePath(): String {
        return applicationContext.filesDir.absolutePath +
            File.separator +
            WIREGUARD_FOLDER_NAME +
            File.separator
    }

    fun updateConfigIdForApp(uid: Int, cfgId: Int) {
        Log.i(LOG_TAG_WIREGUARD, "update cfgId($cfgId) for app, uid: $uid")
        appConfigMappings.filter { it.uid == uid }.forEach { it.wgInterfaceId = cfgId }
        val cfgName = configs.find { it.getId() == cfgId }?.getName() ?: ""
        io { wgApplicationMappingRepository.updateInterfaceIdForApp(uid, cfgId, cfgName) }
    }

    fun removeAllAppsFromConfig(cfgId: Int) {
        Log.i(LOG_TAG_WIREGUARD, "removing all apps from id: $cfgId")
        appConfigMappings.filter { it.wgInterfaceId == cfgId }.forEach { it.wgInterfaceId = 0 }
        io { wgApplicationMappingRepository.removeAllAppsFromInterface(cfgId) }
    }

    fun addAllAppsToConfig(cfgId: Int) {
        if (cfgId == -1) {
            Log.e(LOG_TAG_WIREGUARD, "Invalid config id: $cfgId")
            return
        }
        Log.i(LOG_TAG_WIREGUARD, "Adding all apps to interface: $cfgId")
        appConfigMappings.filter { it.wgInterfaceId == 0 }.forEach { it.wgInterfaceId = cfgId }
        val cfgName = configs.find { it.getId() == cfgId }?.getName() ?: ""
        io { wgApplicationMappingRepository.updateAllAppsForInterface(cfgId, cfgName) }
    }

    fun getActiveConfigIdForApp(uid: Int): Int {
        configFileMappings
            .filter { it.isActive }
            .forEach { wgf ->
                val cfg = configs.find { it.getId() == wgf.id }
                appConfigMappings
                    .find { it.uid == uid }
                    ?.let { wgam ->
                        if (cfg != null && cfg.getId() == wgam.wgInterfaceId) {
                            return cfg.getId()
                        }
                    }
            }
        return -1
    }

    fun getPeers(configId: Int): List<Peer> {
        return configs.find { it.getId() == configId }?.getPeers() ?: listOf()
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }
}
