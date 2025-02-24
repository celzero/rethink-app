/*
 * Copyright 2025 RethinkDNS and its authors
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
package com.celzero.bravedns.rpnproxy

import Logger
import Logger.LOG_TAG_PROXY
import android.content.Context
import com.celzero.bravedns.database.RpnProxy
import com.celzero.bravedns.database.RpnProxyRepository
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EncryptedFileManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants.Companion.RPN_PROXY_FOLDER_NAME
import com.celzero.bravedns.wireguard.BadConfigException
import com.celzero.bravedns.wireguard.Config
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.StandardCharsets

object RpnProxyManager : KoinComponent {

    private val applicationContext: Context by inject()

    private const val TAG = "RpnMgr"

    private val db: RpnProxyRepository by inject()

    // warp primary and secondary config names, ids and file names
    const val WARP_ID = 1
    const val WARP_NAME = "WARP"
    const val WARP_FILE_NAME = "wg1.conf"
    private const val WARP_RESPONSE_FILE_NAME = "warp_response.json"

    private const val AMZ_ID = 2
    private const val AMZ_NAME = "AMZ"
    private const val AMZ_FILE_NAME = "amz.conf"
    private const val AMZ_RESPONSE_FILE_NAME = "amz_response.json"

    private const val PROTON_ID = 3
    private const val PROTON = "PROTON"
    private const val PROTON_FILE_NAME = "proton.conf"
    private const val PROTON_RESPONSE_FILE_NAME = "proton_response.json"

    private var warpConfig: Config? = null
    private var amzConfig: Config? = null
    private var protonConfig: ProtonConfig? = null

    private val selectedCountries = mutableSetOf<String>()

    enum class RpnType(val id: Int) {
        WARP(1),
        AMZ(2),
        PROTON(3),
        SE(4),
        EXIT_64(5),
        EXIT(6)
    }

    suspend fun load() {
        // need to read the filepath from database and load the file
        // there will be an entry in the database for each RPN proxy
        try {
            selectedCountries.clear()
            val rpnProxies = db.getAllProxies()
            Logger.i(LOG_TAG_PROXY, "$TAG; init load, db size: ${rpnProxies.size}")
            rpnProxies.forEach {
                val cfgFile = File(it.configPath)
                if (!cfgFile.exists()) {
                    Logger.w(LOG_TAG_PROXY, "$TAG; load, file not found: ${it.configPath}")
                    return@forEach
                }
                when (it.id) {
                    PROTON_ID -> {
                        val json = EncryptedFileManager.read(applicationContext, cfgFile)
                        protonConfig = stringToProtonConfig(json)
                        Logger.i(LOG_TAG_PROXY, "$TAG; proton config loaded, ${protonConfig != null}")
                    }
                    WARP_ID, AMZ_ID -> {
                        val cfg = EncryptedFileManager.read(applicationContext, cfgFile)
                        val inputStream = ByteArrayInputStream(cfg.toByteArray(StandardCharsets.UTF_8))
                        val c = Config.parse(inputStream)
                        // set name and id to the config
                        val config = Config.Builder().setId(it.id).setName(it.name).setInterface(c.getInterface())
                            .addPeers(c.getPeers()).build()
                        if (it.id == WARP_ID) {
                            warpConfig = config
                        } else if (it.id == AMZ_ID) {
                            amzConfig = config
                        }
                        Logger.i(LOG_TAG_PROXY, "$TAG; config loaded: ${it.name}")
                    }
                }
            }

            selectedCountries.addAll(DomainRulesManager.getAllUniqueCCs()) // add the domain rules cc
            selectedCountries.addAll(IpRulesManager.getAllUniqueCCs()) // add the ip rules cc
            Logger.d(LOG_TAG_PROXY, "$TAG; total selected countries: ${selectedCountries.size}, $selectedCountries")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err loading rpn proxies: ${e.message}", e)
        }
    }

    suspend fun getNewProtonConfig(): ProtonConfig? {
        // pass null to get the new proton config
        val byteArray = VpnController.registerAndFetchProtonIfNeeded(null)
        val res = updateProtonConfig(byteArray)
        return if (res) {
            Logger.i(LOG_TAG_PROXY, "new proton config updated")
            protonConfig
        } else {
            Logger.e(LOG_TAG_PROXY, "err: new proton cfg, returning old cfg if available")
            protonConfig
        }
    }

    suspend fun updateProtonConfig(byteArray: ByteArray?): Boolean {
        if (byteArray == null) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err in getting the proton config")
            return false
        }

        try {
            val p = byteArrayToProtonWgConfig(byteArray)
            return if (p != null) {
                val res = saveProtonConfig(byteArray)
                Logger.d(LOG_TAG_PROXY, "$TAG; proton config saved? $res")
                if (res) {
                    protonConfig = p
                    Logger.d(LOG_TAG_PROXY, "$TAG; proton config updated")
                }
                res
            } else {
                Logger.e(LOG_TAG_PROXY, "$TAG; err parsing proton config")
                false
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err updating proton config: ${e.message}", e)
        }
        return false
    }

    suspend fun updateWarpConfig(byteArray: ByteArray?): Boolean {
        if (byteArray == null) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err: byte array is null for warp config")
            return false
        }

        try {
            // convert the bytearray to json object
            val b2s = byteArray.toString(StandardCharsets.UTF_8)
            val jo = JSONObject(b2s)
            // TODO: remove the below log
            Logger.vv(LOG_TAG_PROXY, "$TAG; init warp config update")
            val cfg = parseNewConfigJsonResponse(jo)
            return if (cfg != null) {
                val c =
                    Config.Builder()
                        .setId(WARP_ID)
                        .setName(WARP_NAME.lowercase())
                        .setInterface(cfg.getInterface())
                        .addPeers(cfg.getPeers())
                        .build()
                val res = writeConfigAndUpdateDb(c, jo.toString())
                Logger.i(LOG_TAG_PROXY, "$TAG; warp config saved? $res")
                if (res) {
                    warpConfig = c
                    Logger.d(LOG_TAG_PROXY, "$TAG; warp config updated")
                }
                res
            } else {
                Logger.e(LOG_TAG_PROXY, "$TAG; err parsing warp config")
                false
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err updating warp config: ${e.message}", e)
        }
        return false
    }

    suspend fun updateAmzConfig(byteArray: ByteArray?): Boolean {
        if (byteArray == null) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err; byte array is null for amz config")
            return false
        }

        try {
            // convert the bytearray to json object
            val b2s = byteArray.toString(StandardCharsets.UTF_8)
            val jo = JSONObject(b2s)
            val cfg = parseNewConfigJsonResponse(jo)
            return if (cfg != null) {
                val c =
                    Config.Builder()
                        .setId(AMZ_ID)
                        .setName(AMZ_NAME.lowercase())
                        .setInterface(cfg.getInterface())
                        .addPeers(cfg.getPeers())
                        .build()
                val res = writeConfigAndUpdateDb(c, jo.toString())
                Logger.i(LOG_TAG_PROXY, "$TAG; amz config saved? $res")
                if (res) {
                    amzConfig = c
                    Logger.d(LOG_TAG_PROXY, "$TAG; amz config updated")
                }
                res
            } else {
                Logger.e(LOG_TAG_PROXY, "$TAG; err parsing amz config")
                false
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err updating amz config: ${e.message}", e)
        }
        return false
    }

    fun getWarpConfig(): Pair<Config?, Boolean> {
        // warp config will always be the first config in the list
        return Pair(warpConfig, false) // config, isActive
    }

    suspend fun getWarpExistingData(): ByteArray? {
        return getExistingData(WARP_ID)
    }

    suspend fun getAmzExistingData(): ByteArray? {
        return getExistingData(AMZ_ID)
    }

    suspend fun getProtonExistingData(): ByteArray? {
        return getExistingData(PROTON_ID)
    }

    private suspend fun getExistingData(id: Int): ByteArray? {
        try {
            val db = db.getProxyById(id)
            if (db == null) {
                Logger.w(LOG_TAG_PROXY, "$TAG; db is null for id: $id")
                return null
            }
            val cfgFile = File(db.serverResPath)
            if (cfgFile.exists()) {
                Logger.d(LOG_TAG_PROXY, "$TAG; config for $id exists, reading the file")
                val d = EncryptedFileManager.read(applicationContext, cfgFile)
                val bytes = d.toByteArray(StandardCharsets.UTF_8)
                Logger.d(LOG_TAG_PROXY, "$TAG; existing data for $id: ${bytes.size}")
                return bytes
            } else {
                Logger.e(LOG_TAG_PROXY, "$TAG; err; config for $id not found, ${cfgFile.absolutePath}")
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err getting existing data for $id: ${e.message}")
        }
        return null
    }

    suspend fun getProtonUniqueCC(): List<RegionalWgConf> {
        return protonConfig?.regionalWgConfs?.distinctBy { it.cc }?.sortedBy { it.cc } ?: emptyList()
    }

    suspend fun getSelectedCCs(): Set<String> {
        return selectedCountries
    }

    private suspend fun saveProtonConfig(byteArray: ByteArray): Boolean {
        try {
            val dir = getProtonConfigDir()
            if (!File(dir).exists()) {
                Logger.d(LOG_TAG_PROXY, "$TAG; creating dir: $dir")
                File(dir).mkdirs()
            }

            // write the byte array to the file
            val protonConfigFile = File(dir, getConfigFileName(PROTON_ID))
            val res = EncryptedFileManager.write(applicationContext, byteArray, protonConfigFile)
            Logger.d(LOG_TAG_PROXY, "$TAG; proton config saved? $res")

            val serverRes = File(dir, getJsonResponseFileName(PROTON_ID))
            val serRes = EncryptedFileManager.write(applicationContext, byteArray, serverRes)
            Logger.d(LOG_TAG_PROXY, "$TAG; proton server response saved? $serRes")

            val rpnpro = db.getProxyById(PROTON_ID)
            val rpnProxy = RpnProxy(
                id = PROTON_ID,
                name = PROTON,
                configPath = protonConfigFile.absolutePath,
                serverResPath = serverRes.absolutePath ?: "",
                isActive = rpnpro?.isActive ?: false,
                isLockdown = rpnpro?.isLockdown ?: false,
                createdTs = rpnpro?.createdTs ?: System.currentTimeMillis(),
                modifiedTs = System.currentTimeMillis(),
                misc = rpnpro?.misc ?: "",
                tunId = rpnpro?.tunId ?: "",
                latency = rpnpro?.latency ?: 0
            )
            val l = db.insert(rpnProxy)
            Logger.d(LOG_TAG_PROXY, "$TAG; proton config saved in db? ${l > 0}")
            return l > 0
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err saving proton config: ${e.message}", e)
        }
        return false
    }

    private fun byteArrayToProtonWgConfig(byteArray: ByteArray): ProtonConfig? {
        try {
            val jsonString = byteArray.toString(StandardCharsets.UTF_8)
            val p = Gson().fromJson(jsonString, ProtonConfig::class.java)
            return p
        } catch (e: JsonSyntaxException) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err parsing proton config: ${e.message}", e)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err parsing proton config: ${e.message}", e)
        }
        return null
    }

    private fun stringToProtonConfig(jsonString: String): ProtonConfig? {
        try {
            return Gson().fromJson(jsonString, ProtonConfig::class.java)
        } catch (e: JsonSyntaxException) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err parsing proton config: ${e.message}", e)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err parsing proton config: ${e.message}", e)
        }
        return null
    }

    private fun getProtonConfigDir(): String {
        // Get the proton config file path from the database
        return applicationContext.filesDir.absolutePath +
                File.separator +
                RPN_PROXY_FOLDER_NAME +
                File.separator +
                PROTON.lowercase() +
                File.separator
    }

    suspend fun getNewAmzConfig(): Config? {
        try {
            val bytes = VpnController.registerAndFetchAmneziaConfig()
            val res = updateAmzConfig(bytes)
            return if (res) {
                Logger.i(LOG_TAG_PROXY, "$TAG; new amz config updated")
                amzConfig
            } else {
                Logger.e(LOG_TAG_PROXY, "$TAG; err: new amz config, returning old config")
                amzConfig
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err: new amz config: ${e.message}")
        }
        return null
    }

    suspend fun getNewWarpConfig(): Config? {
        try {
            val bytes = VpnController.registerAndFetchWarpConfig()
            val res = updateWarpConfig(bytes)
            return if (res) {
                Logger.i(LOG_TAG_PROXY, "$TAG; new warp config updated")
                 warpConfig
            } else {
                Logger.e(LOG_TAG_PROXY, "$TAG; err: new warp config, returning old config")
                warpConfig
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err: new wg(warp) config: ${e.message}")
        }
        return null
    }

    private suspend fun writeConfigAndUpdateDb(cfg: Config, json: String): Boolean {
        try {
            // write the config to the file
            val dir = File(
                applicationContext.filesDir.absolutePath +
                        File.separator +
                        RPN_PROXY_FOLDER_NAME +
                        File.separator +
                        cfg.getName() +
                        File.separator
            )
            if (!dir.exists()) {
                Logger.d(LOG_TAG_PROXY, "$TAG; creating dir: ${dir.absolutePath}")
                dir.mkdirs()
            }

            val cfgFile = File(dir, getConfigFileName(cfg.getId()))
            val cfgRes = EncryptedFileManager.write(applicationContext, cfg.toWgQuickString(), cfgFile)
            Logger.i(LOG_TAG_PROXY, "$TAG writing wg config to file: ${cfgFile.absolutePath}")

            val resFile = File(dir, getJsonResponseFileName(cfg.getId()))
            val res = EncryptedFileManager.write(applicationContext, json, resFile)
            Logger.i(LOG_TAG_PROXY, "$TAG writing server response to file: ${resFile.absolutePath}")

            Logger.d(LOG_TAG_PROXY, "$TAG parse write?$cfgRes, response write?$res")

            val prev = db.getProxyById(cfg.getId())

            val rpnProxy = RpnProxy(
                id = cfg.getId(),
                name = cfg.getName(),
                configPath = cfgFile.absolutePath,
                serverResPath = resFile.absolutePath,
                isActive = prev?.isActive ?: false,
                isLockdown = prev?.isLockdown ?: false,
                createdTs = prev?.createdTs ?: System.currentTimeMillis(),
                modifiedTs = System.currentTimeMillis(),
                misc = prev?.misc ?: "",
                tunId = prev?.tunId ?: "",
                latency = prev?.latency ?: 0
            )
            val l = db.insert(rpnProxy)
            Logger.d(LOG_TAG_PROXY, "$TAG; config saved in db? ${l > 0}")
            return l > 0
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err writing config to file: ${e.message}", e)
        }
        return false
    }

    private fun getConfigFileName(id: Int): String {
        return when (id) {
            WARP_ID -> WARP_FILE_NAME
            AMZ_ID -> AMZ_FILE_NAME
            PROTON_ID -> PROTON_FILE_NAME
            else -> ""
        }
    }

    private fun getJsonResponseFileName(id: Int): String {
        return when (id) {
            WARP_ID -> WARP_RESPONSE_FILE_NAME
            AMZ_ID -> AMZ_RESPONSE_FILE_NAME
            PROTON_ID -> PROTON_RESPONSE_FILE_NAME
            else -> ""
        }
    }

    private fun parseNewConfigJsonResponse(jsonObject: JSONObject?): Config? {
        if (jsonObject == null) {
            Logger.e(LOG_TAG_PROXY, "$TAG; new warp config json object is null")
            return null
        }

        // get the json tag "wgconf" from the response
        val o2s = jsonObject.optString("wgconf")
        // convert string to input stream
        val configStream = ByteArrayInputStream(o2s.toByteArray(StandardCharsets.UTF_8))

        val cfg =
            try {
                Config.parse(configStream)
            } catch (e: BadConfigException) {
                Logger.e(
                    LOG_TAG_PROXY,
                    "$TAG err parsing config: ${e.message}, ${e.reason}, ${e.text}, ${e.location}, ${e.section}, ${e.stackTrace}, ${e.cause}"
                )
                null
            }
        Logger.i(LOG_TAG_PROXY, "$TAG json parse complete? ${cfg != null}")
        return cfg
    }

    fun canSelectCountryCode(cc: String): Pair<Boolean, String> {
        // Check if the country code is available in the proton config
        // and see if only max of 5 countries can be selected
        val isAvailable = protonConfig?.regionalWgConfs?.any { it.cc == cc } ?: false
        if (!isAvailable) {
            Logger.i(LOG_TAG_PROXY, "$TAG; cc not available in proton config: $cc")
            return Pair(false, "Country code not available in the proton config")
        }
        return if (selectedCountries.size >= 5) {
            Logger.i(LOG_TAG_PROXY, "$TAG; cc limit reached, selected: ${selectedCountries.size}, $selectedCountries")
            Pair(false, "Country code limit reached for the selected endpoint")
        } else {
            selectedCountries.add(cc)
            Logger.d(LOG_TAG_PROXY, "$TAG; cc added to selected list: $cc")
            Pair(true, "")
        }
    }

}
