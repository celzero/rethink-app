package com.celzero.bravedns.rpnproxy

import Logger
import Logger.LOG_TAG_PROXY
import android.content.Context
import android.text.format.DateUtils
import backend.Backend
import backend.WgKey
import com.celzero.bravedns.customdownloader.IWireguardWarp
import com.celzero.bravedns.customdownloader.RetrofitManager
import com.celzero.bravedns.database.RpnProxy
import com.celzero.bravedns.database.RpnProxyRepository
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EncryptedFileManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants.Companion.RPN_PROXY_FOLDER_NAME
import com.celzero.bravedns.wireguard.BadConfigException
import com.celzero.bravedns.wireguard.Config
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import retrofit2.converter.gson.GsonConverterFactory
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

object RpnProxyManager : KoinComponent {

    private val applicationContext: Context by inject()

    private const val PROTON_CONFIG_FILE_NAME = "proton.conf"
    private const val TAG = "RpnProxyMgr"

    private val db: RpnProxyRepository by inject()

    // warp response json keys
    private const val JSON_RESPONSE_WORKS = "works"
    private const val JSON_RESPONSE_REASON = "reason"

    // warp primary and secondary config names, ids and file names
    const val SEC_WARP_NAME = "SEC_WARP"
    const val SEC_WARP_ID = 0
    const val SEC_WARP_FILE_NAME = "wg0.conf"
    const val WARP_NAME = "WARP"
    const val WARP_ID = 1
    const val WARP_FILE_NAME = "wg1.conf"
    const val RPN_AMZ_NAME = "AMNEZIA_RPN"
    const val RPN_AMZ_ID = 2
    const val RPN_AMZ_FILE_NAME = "wg2.conf"
    const val PROTON = "PROTON"
    const val PROTON_ID = 3

    private const val WARP_CHECK_INTERVAL = 4 * DateUtils.HOUR_IN_MILLIS
    private var lastWarpCheckTime = 0L
    private var warpWorks: Pair<Boolean, String> = Pair(false, "")

    private var warpConfig: Config? = null
    private var amneziaConfig: Config? = null
    private var secWarpConfig: Config? = null
    private var protonConfig: ProtonConfig? = null

    private val selectedCountries = mutableSetOf<String>()

    suspend fun load() {
        // Load the RPN proxies from separate database
        // need to read the filepath from database and load the file
        // there will be an entry in the database for each RPN proxy

        try {
            val rpnProxies = db.getAllProxies()
            Logger.i(LOG_TAG_PROXY, "$TAG: rpn proxies loaded: ${rpnProxies.size}")
            rpnProxies.forEach {
                val cfgFile = File(it.configPath)
                if (cfgFile.exists()) {
                    if (it.id == PROTON_ID) {
                        val json = EncryptedFileManager.read(applicationContext, cfgFile)
                        protonConfig = stringToProtonConfig(json)
                        Logger.i(
                            LOG_TAG_PROXY,
                            "$TAG: proton config loaded ${protonConfig?.regionalWgConfs?.size}"
                        )
                    } else if (it.id == WARP_ID || it.id == SEC_WARP_ID || it.id == RPN_AMZ_ID) {
                        val cfg = EncryptedFileManager.read(applicationContext, cfgFile)
                        val config = Config.parse(ByteArrayInputStream(cfg.toByteArray(StandardCharsets.UTF_8)))

                        if (it.id == WARP_ID) {
                            warpConfig = config
                        } else if (it.id == SEC_WARP_ID) {
                            secWarpConfig = config
                        } else if (it.id == RPN_AMZ_ID) {
                            amneziaConfig = config
                        }
                        Logger.i(
                            LOG_TAG_PROXY,
                            "$TAG: wg config loaded: ${config.getName()}, ${config.getId()}"
                        )
                    } else {
                        // no need to handle the other configs
                    }
                }
            }
            selectedCountries.clear()
            val dcc = DomainRulesManager.getAllUniqueCCs()
            val icc = IpRulesManager.getAllUniqueCCs()
            selectedCountries.addAll(dcc)
            selectedCountries.addAll(icc)
            Logger.d(LOG_TAG_PROXY, "$TAG: cc: ${selectedCountries.size}, $selectedCountries")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG: err loading rpn proxies: ${e.message}", e)
        }
    }

    suspend fun isProtonConfigAvailable(): Boolean {
        return protonConfig != null
    }

    suspend fun getNewProtonConfig(): ProtonConfig? {
        // Get the new proton config from the server
        // This will be called when the user changes the proton config
        val byteArray = VpnController.registerProton()
        if (byteArray != null) {
            saveProtonConfig(byteArray)
            // TODO: Update the proton config in the database
            protonConfig = byteArrayToProtonWgConfig(byteArray)
            Logger.i(
                LOG_TAG_PROXY,
                "$TAG, p: ${protonConfig?.uid}, ${protonConfig?.certRefreshTime}, ${protonConfig?.sessionAccessToken}"
            )
            Logger.i(
                LOG_TAG_PROXY,
                "$TAG: proton config updated ${protonConfig?.regionalWgConfs?.size}"
            )
        } else {
            // Handle the error
            Logger.e(LOG_TAG_PROXY, "$TAG: err in getting the proton config")
        }
        return protonConfig
    }

    suspend fun onProtonConfigUpdated(byteArray: ByteArray?) {
        if (byteArray == null) {
            Logger.e(LOG_TAG_PROXY, "$TAG: err in getting the proton config")
            return
        }

        try {
            val p = byteArrayToProtonWgConfig(byteArray)
            if (p != null) {
                protonConfig = p
                Logger.i(
                    LOG_TAG_PROXY,
                    "$TAG: proton config updated ${protonConfig?.regionalWgConfs?.size}"
                )
                saveProtonConfig(byteArray)
            } else {
                Logger.e(LOG_TAG_PROXY, "$TAG: err parsing proton config")
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG: err updating proton config: ${e.message}", e)
        }
    }

    suspend fun onWarpConfigUpdated(byteArray: ByteArray?) {
        if (byteArray == null) {
            Logger.e(LOG_TAG_PROXY, "$TAG: err in getting the warp config")
            return
        }

        try {
            // convert the bytearray to json object
            val jsonString = byteArray.toString(StandardCharsets.UTF_8)
            val jsonObject = JSONObject(jsonString)
            val pvtKey = warpConfig?.getInterface()?.getKeyPair()?.getPrivateKey()
            if (pvtKey == null) {
                Logger.e(LOG_TAG_PROXY, "$TAG: warp private key is null")
                return
            }
            val config = parseNewConfigJsonResponse(pvtKey, jsonObject)
            if (config != null) {
                val c =
                    Config.Builder()
                        .setId(WARP_ID)
                        .setName(WARP_NAME)
                        .setInterface(config.getInterface())
                        .addPeers(config.getPeers())
                        .build()
                warpConfig = c

                Logger.i(LOG_TAG_PROXY, "warp updated: ${c.getName()}, ${c.getId()}")
                writeConfigAndUpdateDb(c, jsonObject.toString())
            } else {
                Logger.e(LOG_TAG_PROXY, "$TAG: err parsing warp config")
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG: err updating warp config: ${e.message}", e)
        }
    }

    suspend fun onAmzConfigUpdated(byteArray: ByteArray?) {
        if (byteArray == null) {
            Logger.e(LOG_TAG_PROXY, "$TAG: err in getting the amnezia config")
            return
        }

        try {
            // convert the bytearray to json object
            val jsonString = byteArray.toString(StandardCharsets.UTF_8)
            val jsonObject = JSONObject(jsonString)
            val pvtKey = amneziaConfig?.getInterface()?.getKeyPair()?.getPrivateKey()
            if (pvtKey == null) {
                Logger.e(LOG_TAG_PROXY, "$TAG: amnezia private key is null")
                return
            }
            val config = parseNewConfigJsonResponse(pvtKey, jsonObject)
            if (config != null) {
                val c =
                    Config.Builder()
                        .setId(RPN_AMZ_ID)
                        .setName(RPN_AMZ_NAME)
                        .setInterface(config.getInterface())
                        .addPeers(config.getPeers())
                        .build()
                amneziaConfig = c

                Logger.i(LOG_TAG_PROXY, "amz updated: ${c.getName()}, ${c.getId()}")
                writeConfigAndUpdateDb(c, jsonObject.toString())
            } else {
                Logger.e(LOG_TAG_PROXY, "$TAG: err parsing amnezia config")
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG: err updating amnezia config: ${e.message}", e)
        }
    }

    fun getWarpConfig(): Pair<Config?, Boolean> {
        // warp config will always be the first config in the list
        return Pair(warpConfig, false) // config, isActive
    }

    fun getSecWarpConfig(): Config? {
        return secWarpConfig
    }

    fun getAmneziaConfig(): Pair<Config?, Boolean> {
        return Pair(amneziaConfig, false) // config, isActive
    }

    suspend fun getWarpExistingData(): ByteArray? {
        try {
            val db = db.getProxyById(WARP_ID)
            if (db != null) {
                val cfgFile = File(db.serverResPath)
                if (cfgFile.exists()) {
                    val s = EncryptedFileManager.read(applicationContext, cfgFile)
                    return s.toByteArray(StandardCharsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG: err getting warp existing data: ${e.message}", e)
        }
        return null
    }

    suspend fun getAmneziaExistingData(): ByteArray? {
        try {
            val db = db.getProxyById(RPN_AMZ_ID)
            if (db != null) {
                val cfgFile = File(db.serverResPath)
                if (cfgFile.exists()) {
                    val s = EncryptedFileManager.read(applicationContext, cfgFile)
                    return s.toByteArray(StandardCharsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG: err getting amz existing data: ${e.message}", e)
        }
        return null
    }

    suspend fun getProtonExistingData(): ByteArray? {
        try {
            val db = db.getProxyById(PROTON_ID)
            if (db != null) {
                val cfgFile = File(db.serverResPath)
                if (cfgFile.exists()) {
                    val s = EncryptedFileManager.read(applicationContext, cfgFile)
                    return s.toByteArray(StandardCharsets.UTF_8)
                }
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG: err getting proton existing data: ${e.message}", e)
        }
        return null
    }

    fun isSecWarpAvailable(): Boolean {
        return secWarpConfig != null
    }

    suspend fun getProtonConfigByCC(cc: String): List<Config> {
        val lst =  listOf(protonConfig?.regionalWgConfs?.first()?.wgConf ?: "")
        Logger.d(LOG_TAG_PROXY, "$TAG: proton config by cc: ${lst.size}")
        val c = mutableListOf<Config>()
        lst.forEach {
            val config = Config.parse(ByteArrayInputStream(it.toByteArray(StandardCharsets.UTF_8)))
            c.add(config)
        }
        Logger.d(LOG_TAG_PROXY, "$TAG: proton config by cc size: ${c.size}")
        return c
    }

    suspend fun getProtonUniqueCC(): List<RegionalWgConf> {
        return protonConfig?.regionalWgConfs?.distinctBy { it.cc }?.sortedBy { it.cc } ?: emptyList()
    }

    private suspend fun saveProtonConfig(byteArray: ByteArray) {
        try {
            // write the byte array to the file
            val filePath = getProtonConfigFilePath()
            val protonConfigFile = File(filePath)
            EncryptedFileManager.write(applicationContext, byteArray, protonConfigFile)
            val serverResPath = getJsonResponseFileName(PROTON_ID)
            val serverRes = File(
                applicationContext.filesDir.absolutePath +
                        File.separator +
                        RPN_PROXY_FOLDER_NAME + File.separator + serverResPath
            )
            EncryptedFileManager.write(applicationContext, byteArray, serverRes)
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
            db.insert(rpnProxy)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG: err saving proton config: ${e.message}", e)
        }
    }

    private fun byteArrayToProtonWgConfig(byteArray: ByteArray): ProtonConfig? {
        try {
            val jsonString = byteArray.toString(StandardCharsets.UTF_8)
            val p = Gson().fromJson(jsonString, ProtonConfig::class.java)
            Logger.i(
                LOG_TAG_PROXY,
                "$TAG: proton config: ${p.uid}, ${p.certRefreshTime}, ${p.sessionAccessToken}"
            )
            return p
        } catch (e: JsonSyntaxException) {
            Logger.e(LOG_TAG_PROXY, "$TAG: err parsing proton config: ${e.message}", e)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG: err parsing proton config: ${e.message}", e)
        }
        return null
    }

    private fun stringToProtonConfig(jsonString: String): ProtonConfig? {
        try {
            val p = Gson().fromJson(jsonString, ProtonConfig::class.java)
            Logger.i(
                LOG_TAG_PROXY,
                "$TAG: proton config: ${p.uid}, ${p.certRefreshTime}, ${p.sessionAccessToken}"
            )
            return p
        } catch (e: JsonSyntaxException) {
            Logger.e(LOG_TAG_PROXY, "$TAG: err parsing proton config: ${e.message}", e)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG: err parsing proton config: ${e.message}", e)
        }
        return null
    }

    private fun getProtonConfigFilePath(): String {
        // Get the proton config file path from the database
        return applicationContext.filesDir.absolutePath +
                File.separator +
                RPN_PROXY_FOLDER_NAME +
                File.separator +
                PROTON_CONFIG_FILE_NAME
    }

    suspend fun getNewAmneziaConfig(id: Int): Config? {
        try {
            val privateKey = Backend.newWgPrivateKey()
            val publicKey = privateKey.mult().base64()

            val jsonObject = VpnController.registerAndFetchAmneziaConfig(publicKey)
            if (jsonObject == null) {
                Logger.e(LOG_TAG_PROXY, "new wg(amz) config json object is null")
                return null
            }
            val config = parseNewConfigJsonResponse(privateKey, jsonObject)
            if (config != null) {
                val name = RPN_AMZ_NAME
                val c = Config.Builder()
                    .setId(id)
                    .setName(name)
                    .setInterface(config.getInterface())
                    .addPeers(config.getPeers())
                    .build()
                amneziaConfig = c
                Logger.i(LOG_TAG_PROXY, "New wg(amz) config: ${c.getName()}, ${c.getId()}")
                writeConfigAndUpdateDb(c, jsonObject.toString())
                return c
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "err: new amz config: ${e.message}")
        }
        return null
    }

    suspend fun getNewWarpConfig(type: Boolean, id: Int, retryCount: Int): Config? {
        try {
            val privateKey = Backend.newWgPrivateKey()
            val publicKey = privateKey.mult().base64()
            val deviceName = android.os.Build.MODEL
            val locale = Locale.getDefault().toString()

            if (type) {
                val jsonObject = VpnController.registerAndFetchWarpConfig(publicKey)
                if (jsonObject == null) {
                    Logger.e(LOG_TAG_PROXY, "new wg(warp) config json object is null")
                    return null
                }
                val config = parseNewConfigJsonResponse(privateKey, jsonObject)
                if (config != null) {
                    val c =
                        Config.Builder()
                            .setId(id)
                            .setName(if (id == WARP_ID) WARP_NAME else SEC_WARP_NAME)
                            .setInterface(config.getInterface())
                            .addPeers(config.getPeers())
                            .build()
                    if (id == WARP_ID) {
                        warpConfig = c
                    } else {
                        secWarpConfig = c
                    }

                    Logger.i(LOG_TAG_PROXY, "new warp config: ${c.getName()}, ${c.getId()}")
                    writeConfigAndUpdateDb(c, jsonObject.toString())
                    return c
                }
            } else {
                val retrofit =
                    RetrofitManager.getWarpBaseBuilder(retryCount)
                        .addConverterFactory(GsonConverterFactory.create())
                        .build()
                val retrofitInterface = retrofit.create(IWireguardWarp::class.java)

                val response = retrofitInterface.getNewWarpConfig(publicKey, deviceName, locale)
                Logger.d(LOG_TAG_PROXY, "New wg(warp) config: ${response?.body()}")
                Logger.d(
                    LOG_TAG_PROXY,
                    "New wg(warp) config: public: $publicKey, device: $deviceName, locale: $locale"
                )

                if (response?.isSuccessful == true) {
                    val jsonObject = JSONObject(response.body().toString())
                    val config = parseNewConfigJsonResponse(privateKey, jsonObject)
                    if (config != null) {
                        val c =
                            Config.Builder()
                                .setId(id)
                                .setName(if (id == WARP_ID) WARP_NAME else SEC_WARP_NAME)
                                .setInterface(config.getInterface())
                                .addPeers(config.getPeers())
                                .build()
                        if (id == WARP_ID) {
                            warpConfig = c
                        } else {
                            secWarpConfig = c
                        }

                        writeConfigAndUpdateDb(c, jsonObject.toString())
                        return c
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "err: new wg(warp) config: ${e.message}")
        }
        return if (isRetryRequired(retryCount)) {
            Logger.i(Logger.LOG_TAG_DOWNLOAD, "retrying to getNewWarpConfig")
            getNewWarpConfig(true, id, retryCount + 1)
        } else {
            Logger.i(LOG_TAG_PROXY, "retry count exceeded(getNewWarpConfig), returning null")
            null
        }
    }

    private suspend fun writeConfigAndUpdateDb(cfg: Config, json: String) {
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
                dir.mkdirs()
            }

            val id = cfg.getId()
            val cfgFileName = getConfigFileName(id)
            val cfgFile = File(dir.absolutePath + File.separator + cfgFileName)
            val parsedCfg = cfg.toWgQuickString()
            EncryptedFileManager.write(applicationContext, parsedCfg, cfgFile)

            Logger.i(LOG_TAG_PROXY, "$TAG writing wg config to file: ${cfgFile.absolutePath}")

            val resFileName = getJsonResponseFileName(id)
            val resFile = File(dir.absolutePath + File.separator + resFileName)
            EncryptedFileManager.write(applicationContext, json, resFile)

            Logger.i(LOG_TAG_PROXY, "$TAG writing server response to file: ${resFile.absolutePath}")

            val prev = db.getProxyById(id)

            val rpnProxy = RpnProxy(
                id = id,
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
            db.insert(rpnProxy)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG: err writing config to file: ${e.message}", e)
        }
    }

    private fun getConfigFileName(id: Int): String {
        return when (id) {
            WARP_ID -> WARP_FILE_NAME
            SEC_WARP_ID -> SEC_WARP_FILE_NAME
            RPN_AMZ_ID -> RPN_AMZ_FILE_NAME
            PROTON_ID -> PROTON_CONFIG_FILE_NAME
            else -> ""
        }
    }

    private fun getJsonResponseFileName(id: Int): String {
        return when (id) {
            WARP_ID -> "warp_ser_res"
            SEC_WARP_ID -> "sec_warp_ser_res"
            RPN_AMZ_ID -> "amnezia_ser_res"
            PROTON_ID -> "proton_ser_res"
            else -> ""
        }
    }

    private fun isRetryRequired(retryCount: Int): Boolean {
        return retryCount < RetrofitManager.Companion.OkHttpDnsType.entries.size - 1
    }

    private fun shouldPerformWarpCheck(): Boolean {
        // check if the second value of warpWorks is empty, if so, perform the check
        // in case of actual failure, the second value should have the reason
        if (!warpWorks.first && warpWorks.second.isEmpty()) {
            Logger.i(LOG_TAG_PROXY, "warpWorks is false, performing check")
            return true
        }
        return System.currentTimeMillis() - lastWarpCheckTime > WARP_CHECK_INTERVAL
    }

    suspend fun isWarpWorking(retryCount: Int = 0): Pair<Boolean, String> {
        if (!shouldPerformWarpCheck()) {
            Logger.i(LOG_TAG_PROXY, "warp check interval not reached, returning last result")
            return warpWorks
        }

        // create okhttp client with base url
        var works = false
        var reason = ""
        try {
            val retrofit =
                RetrofitManager.getWarpBaseBuilder(retryCount)
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
            val retrofitInterface = retrofit.create(IWireguardWarp::class.java)

            val response = retrofitInterface.isWarpConfigWorking()
            Logger.d(
                LOG_TAG_PROXY,
                "new wg(warp) config: ${response?.headers()}, ${response?.message()}, ${response?.raw()?.request?.url}"
            )

            if (response?.isSuccessful == true) {
                val jsonObject = JSONObject(response.body().toString())
                works = jsonObject.optBoolean(JSON_RESPONSE_WORKS, false)
                reason = jsonObject.optString(JSON_RESPONSE_REASON, "")
                Logger.i(
                    LOG_TAG_PROXY,
                    "warp response for ${response.raw().request.url}, works? $works, reason: $reason"
                )
            } else {
                Logger.w(
                    LOG_TAG_PROXY,
                    "unsuccessful response for ${response?.raw()?.request?.url}"
                )
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "err checking warp(works): ${e.message}")
        }

        return if (isRetryRequired(retryCount) && !works) {
            Logger.i(Logger.LOG_TAG_DOWNLOAD, "retrying to getNewWarpConfig")
            isWarpWorking(retryCount + 1)
        } else {
            Logger.i(LOG_TAG_PROXY, "retry count exceeded(getNewWarpConfig), returning null")
            warpWorks = Pair(works, reason)
            warpWorks
        }
    }

    private fun parseNewConfigJsonResponse(privateKey: WgKey, jsonObject: JSONObject?): Config? {
        // get the json tag "wgconf" from the response
        if (jsonObject == null) {
            Logger.e(LOG_TAG_PROXY, "new warp config json object is null")
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
                Logger.e(
                    LOG_TAG_PROXY,
                    "err parsing config: ${e.message}, ${e.reason}, ${e.text}, ${e.location}, ${e.section}, ${e.stackTrace}, ${e.cause}"
                )
                null
            }
        Logger.i(LOG_TAG_PROXY, "parse complete for: ${cfg?.getName()}, ${cfg?.getId()}")
        return cfg
    }

    suspend fun enableConfig(id: Int) {
        val proxy = db.getProxyById(id)
        if (proxy != null) {
            val updatedProxy = proxy.copy(isActive = true)
            db.update(updatedProxy)
            VpnController.addWireGuardProxy(id = ProxyManager.ID_WG_BASE + id)
        }
    }

    fun canSelectCountryCode(cc: String): Pair<Boolean, String> {
        // Check if the country code is available in the proton config
        // and see if only max of 5 countries can be selected
        val isAvailable = protonConfig?.regionalWgConfs?.any { it.cc == cc } ?: false
        if (!isAvailable) {
            return Pair(false, "Country code not available in the proton config")
        }
        return if (selectedCountries.size >= 5) {
            Logger.d(LOG_TAG_PROXY, "$TAG: cc limit reached, selected: ${selectedCountries.size}, $selectedCountries")
            Pair(false, "Country code limit reached for the selected endpoint")
        } else {
            selectedCountries.add(cc)
            Pair(true, "")
        }
    }

}
