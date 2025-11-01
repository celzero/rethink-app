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
/*
import Logger.LOG_TAG_PROXY
import android.content.Context
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.RpnProxy
import com.celzero.bravedns.database.RpnProxyRepository
import kotlin.collections.find

import com.celzero.bravedns.database.SubscriptionStateHistoryDao
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.scheduler.WorkScheduler
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EncryptedFileManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.subscription.StateMachineStatistics
import com.celzero.bravedns.subscription.SubscriptionStateMachineV2
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.RPN_PROXY_FOLDER_NAME
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.RpnServers
import com.celzero.firestack.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArraySet

object RpnProxyManager : KoinComponent {
    private val applicationContext: Context by inject()

    private const val TAG = "RpnMgr"

    private var preferredId = Backend.Auto

    private val db: RpnProxyRepository by inject()
    private val subsDb: SubscriptionStatusRepository by inject()
    private val subsHistoryDb: SubscriptionStateHistoryDao by inject()
    private val workScheduler by inject<WorkScheduler>()
    private val persistentState by inject<PersistentState>()

    private const val WIN_ID = 4
    private const val WIN_NAME = "WIN"
    private const val WIN_ENTITLEMENT_FILE_NAME = "win_response.json"
    private const val WIN_STATE_FILE_NAME = "win_state.json"
    const val MAX_WIN_SERVERS = 5

    private var winConfig: ByteArray? = null
    private var winServers: Set<RpnWinServer> = emptySet()

    private val rpnProxies = CopyOnWriteArraySet<RpnProxy>()

    private val selectedCountries = mutableSetOf<String>()

    private val subscriptionStateMachine: SubscriptionStateMachineV2 by inject()
    private val stateObserverJob = SupervisorJob()
    private val stateObserverScope = CoroutineScope(Dispatchers.IO + stateObserverJob)

    init {
        io {
            try {
                load()
                startStateObserver()
                Logger.i(
                    LOG_TAG_PROXY,
                    "$TAG; RpnProxyManager initialized with state machine integration"
                )
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; error during initialization: ${e.message}", e)
                // Continue without state machine if initialization fails
            }
        }
    }

    enum class RpnTunMode(val id: Int) {
        NONE(Settings.AutoModeLocal),
        ANTI_CENSORSHIP(Settings.AutoModeHybrid),
        HIDE_IP(Settings.AutoModeRemote);

        companion object {
            fun fromId(id: Int) = entries.first { it.id == id }

            fun getTunModeForAuto(): Int {
                return when (rpnMode()) {
                    RpnMode.NONE -> NONE.id
                    RpnMode.ANTI_CENSORSHIP -> ANTI_CENSORSHIP.id
                    RpnMode.HIDE_IP -> HIDE_IP.id
                }
            }
        }
    }

    enum class RpnMode(val id: Int) {
        NONE(0),
        ANTI_CENSORSHIP(1),
        HIDE_IP(2);

        companion object {
            fun fromId(id: Int) = RpnMode.entries.first { it.id == id }

            fun getPreferredId(id: Int): String {
                val mode = fromId(id)
                return when (mode) {
                    NONE -> ""
                    ANTI_CENSORSHIP -> getPreferredId()
                    HIDE_IP -> getPreferredId()
                }
            }
        }

        fun isAntiCensorship() = this == ANTI_CENSORSHIP

        fun isHideIp() = this == HIDE_IP

        fun isNone() = this == NONE
    }

    enum class RpnState(val id: Int) {
        DISABLED(0),
        PAUSED(1), // not used in the app, but kept for future use
        ENABLED(2);

        companion object {
            fun fromId(id: Int) = RpnState.entries.first { it.id == id }
        }

        fun isEnabled() = this == ENABLED
        fun isPaused() = this == PAUSED
        fun isInactive() = this == DISABLED
    }

    data class RpnWinServer(val names: String, val countryCode: String, val address: String, val isActive: Boolean)

    fun isRpnActive(): Boolean {
        val isEnabled = RpnState.fromId(persistentState.rpnState).isEnabled()
        val isActive = !rpnMode().isNone()
        return isEnabled && isActive
    }

    fun isRpnEnabled() = RpnState.fromId(persistentState.rpnState).isEnabled()

    fun rpnMode() = RpnMode.fromId(persistentState.rpnMode)

    fun rpnState() = RpnState.fromId(persistentState.rpnState)

    fun setRpnMode(mode: RpnMode) {
        if (rpnMode() == mode) {
            Logger.i(LOG_TAG_PROXY, "$TAG; rpn mode already set to ${RpnMode.getPreferredId(mode.id)}, skipping")
            return
        }
        persistentState.rpnMode = mode.id
        Logger.i(LOG_TAG_PROXY, "$TAG; rpn mode set to $${RpnMode.getPreferredId(mode.id)}")
    }

    fun deactivateRpn(reason: String = "manual deactivation") {
        Logger.i(LOG_TAG_PROXY, "$TAG; deactivating RPN, reason: $reason, current state: ${rpnState().name}")
        if (persistentState.rpnState == RpnState.DISABLED.id) {
            Logger.i(LOG_TAG_PROXY, "$TAG; rpn already deactivated, skipping")
            return
        }

        // no need to check the state, as user can manually deactivate RPN
        persistentState.rpnState = RpnState.DISABLED.id
    }


    fun activateRpn(purchase: PurchaseDetail, newPayload: String? = null) {

        if (persistentState.rpnState == RpnState.ENABLED.id) {
            Logger.i(LOG_TAG_PROXY, "$TAG; rpn already activated, skipping")
            return
        }

        val currentState = subscriptionStateMachine.getCurrentState()

        // Check if current state allows RPN activation
        if (!subscriptionStateMachine.hasValidSubscription()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; cannot activate RPN - no valid subscription, current state: ${currentState.name}")
            return
        }

        try {
            persistentState.rpnState = RpnState.ENABLED.id
            setRpnProductId(purchase.productId)
            persistentState.showConfettiOnRPlus = true
            io {
                val payload = if (newPayload != null && newPayload.isNotEmpty()) {
                    // If new payload is provided, use it to update the purchase payload
                    Logger.i(LOG_TAG_PROXY, "$TAG; updating purchase payload with new data")
                    newPayload
                } else {
                    // Otherwise, use the existing payload from the purchase
                    purchase.payload
                }
                storeWinEntitlement(payload)
            }
            Logger.i(
                LOG_TAG_PROXY,
                "$TAG; rpn activated, mode: ${rpnMode()}, product: ${purchase.productId}"
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error activating RPN: ${e.message}", e)
            // Rollback on error
            persistentState.rpnState = RpnState.DISABLED.id
            throw e
        }
    }

    fun getSessionTokenFromPayload(payload: String): String {
        // "developerPayload":"{\"ws\":{\"cid\":\"aa95f04efcb19a54c7605a02e5dd0b435906b993d12bec031a60f3f1272f4f0e\",\"sessiontoken\":\"22695:4:1752256088:524537c17ba103463ba1d330efaf05c146ba3404af:023f958b6c1949568f55078e3c58fe6885d3e57322\",\"expiry\":\"2025-08-11T00:00:00.000Z\",\"status\":\"valid\"}}"
        try {
            val json = JSONObject(payload)
            val ws = json.getJSONObject("ws")
            val sessionToken = ws.getString("sessiontoken")
            Logger.i(LOG_TAG_PROXY, "$TAG; session token parsed from payload? ${sessionToken.isNotEmpty()}")
            return sessionToken
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error parsing session token from payload: ${e.message}", e)
        }
        return ""
    }

    fun getExpiryFromPayload(payload: String): Long? {
        // "developerPayload":"{\"ws\":{\"cid\":\"aa95f04efcb19a54c7605a02e5dd0b435906b993d12bec031a60f3f1272f4f0e\",\"sessiontoken\":\"22695:4:1752256088:524537c17ba103463ba1d330efaf05c146ba3404af:023f958b6c1949568f55078e3c58fe6885d3e57322\",\"expiry\":\"2025-08-11T00:00:00.000Z\",\"status\":\"valid\"}}"
        try {
            val json = JSONObject(payload)
            val ws = json.getJSONObject("ws")
            val expiryStr = ws.getString("expiry")
            val timestamp: Long = Instant.parse(expiryStr).toEpochMilli()
            Logger.i(LOG_TAG_PROXY, "$TAG; expiry parsed from payload: $timestamp")
            return timestamp
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; error parsing expiry from payload: ${e.message}")
        }
        return null
    }

    suspend fun storeWinEntitlement(payload: String) {
        // Store the win entitlement in a file and the path in serverResponsePath
        try {
            // "developerPayload":"{\"ws\":{\"cid\":\"aa95f04efcb19a54c7605a02e5dd0b435906b993d12bec031a60f3f1272f4f0e\",\"sessiontoken\":\"22695:4:1752256088:524537c17ba103463ba1d330efaf05c146ba3404af:023f958b6c1949568f55078e3c58fe6885d3e57322\",\"expiry\":\"2025-08-11T00:00:00.000Z\",\"status\":\"valid\"}}"
            val json = JSONObject(payload)
            val ws = json.getJSONObject("ws")
            val fileName = getJsonResponseFileName(WIN_ID)
            val file = File(applicationContext.getExternalFilesDir(RPN_PROXY_FOLDER_NAME), fileName)
            val res = EncryptedFileManager.write(applicationContext, ws.toString(), file)
            // update the winConfig with the file path
            winConfig = ws.toString().toByteArray()
            val winProxy = db.getProxyById(WIN_ID)
            val ll = if (winProxy != null) {
                winProxy.serverResPath = file.absolutePath
                db.update(winProxy)
            } else {
                // insert a new proxy entry if it doesn't exist
                val newWinProxy = RpnProxy(
                    id = WIN_ID,
                    name = WIN_NAME,
                    configPath = "",
                    serverResPath = file.absolutePath,
                    isActive = true,
                    isLockdown = false,
                    createdTs = System.currentTimeMillis(),
                    modifiedTs = System.currentTimeMillis(),
                    misc = "",
                    tunId = "",
                    latency = 0,
                    lastRefreshTime = System.currentTimeMillis()
                )
                db.insert(newWinProxy).toInt()
            }
            if (ll < 0) {
                Logger.w(LOG_TAG_PROXY, "$TAG; error updating win proxy in db, result: $ll")
            } else {
                Logger.i(LOG_TAG_PROXY, "$TAG; win proxy updated in db, result: $ll")
            }
            Logger.i(LOG_TAG_PROXY, "$TAG; win entitlement stored in file: $fileName, result: $res")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error storing win configs: ${e.message}", e)
        }
    }


    fun changePreferredId(id: String) {
        if (id.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; preferred id is empty, resetting to default")
            preferredId = Backend.Auto
            return
        }
        preferredId = id
        Logger.i(LOG_TAG_PROXY, "$TAG; preferred id changed to $preferredId")
    }

    fun getPreferredId(): String {
        Logger.v(LOG_TAG_PROXY, "$TAG; getPreferredId: $preferredId")
        return preferredId
    }

    fun getRpnProductId(): String {
        return persistentState.rpnProductId
    }

    fun setRpnProductId(productId: String) {
        persistentState.rpnProductId = productId
    }

    enum class RpnType(val id: Int) {
        EXIT(0),
        WIN(1);

        companion object {
            fun fromId(id: Int) = entries.first { it.id == id }
        }
    }

    data class RpnProps(val id: String, val status: Long, val type: String, val kids: String, val addr: String, val created: Long, val expires: Long, val who: String, val locations: RpnServers) {
        override fun toString(): String {
            val cts = getTime(created)
            val ets = getTime(expires)
            val s = applicationContext.getString(UIUtils.getProxyStatusStringRes(status))
            return "id = $id\nstatus = $s\ntype = $type\nkids = $kids\naddr = $addr\ncreated = $cts\nexpires = $ets\nwho = $who\nlocations = $locations"
        }
    }

    private fun getTime(time: Long): String {
        return  Utilities.convertLongToTime(time, Constants.TIME_FORMAT_4)
    }

    suspend fun load(): Int {
        // need to read the filepath from database and load the file
        // there will be an entry in the database for each RPN proxy
        selectedCountries.clear()
        val rp = db.getAllProxies()
        Logger.i(LOG_TAG_PROXY, "$TAG; init load, db size: ${rp.size}")
        rpnProxies.addAll(rp)
        rp.forEach {
            try {
                val cfgFile = File(it.configPath)
                if (!cfgFile.exists() && it.id != WIN_ID) { // win proxy is handled differently
                    Logger.w(LOG_TAG_PROXY, "$TAG; load, file not found: ${it.configPath} for ${it.name}")
                    return@forEach
                }
                when (it.id) {
                    WIN_ID -> {
                        // read the win entitlement file
                        val entitlementFile = File(it.serverResPath)
                        val entitlement = EncryptedFileManager.readByteArray(applicationContext, entitlementFile)
                        val state = EncryptedFileManager.readByteArray(applicationContext, cfgFile)
                        if (state.isEmpty()) {
                            Logger.d(LOG_TAG_PROXY, "$TAG; win state file is empty (path: ${cfgFile.absolutePath}, using entitlement")
                            winConfig = entitlement
                        } else {
                            winConfig = state
                        }
                        Logger.i(LOG_TAG_PROXY, "$TAG; win config loaded, ${winConfig?.isNotEmpty()}")
                    }
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_PROXY, "$TAG; err loading rpn proxy: ${it.name}, ${e.message}")
            }
        }

        selectedCountries.addAll(DomainRulesManager.getAllUniqueCCs()) // add the domain rules cc
        selectedCountries.addAll(IpRulesManager.getAllUniqueCCs()) // add the ip rules cc
        Logger.d(
            LOG_TAG_PROXY,
            "$TAG; total selected countries: ${selectedCountries.size}, $selectedCountries"
        )
        // subsDb.deleteAll()
        // subsHistoryDb.clearHistory()
        return rp.size
    }

    fun getProxy(type: RpnType): RpnProxy? {
        return when (type) {
            RpnType.WIN -> {
                rpnProxies.find { it.name == WIN_NAME || it.name == WIN_NAME.lowercase() }
            }
            else -> null
        }
    }

    // This function is called from RpnProxiesUpdateWorker
    suspend fun registerNewProxy(type: RpnType): Boolean {
        // in case of update failure, call register with null
        when (type) {
            RpnType.WIN -> {
                // check if state is there if not, fetch the entitlement
                var bytes = getWinExistingData() // fetch existing win state
                if (bytes == null) {
                    Logger.i(LOG_TAG_PROXY, "$TAG; win state is null, fetching entitlement")
                    bytes = getWinEntitlement()
                }
                if (bytes == null || bytes.isEmpty()) {
                    Logger.w(LOG_TAG_PROXY, "$TAG; win entitlement is null or empty, cannot register")
                    return false
                }
                val currBytes = VpnController.registerAndFetchWinConfig(bytes) ?: return false
                val ok = updateWinConfigState(currBytes)
                winServers = fetchAndConstructWinLocations()
                if (winServers.isEmpty()) {
                    Logger.w(LOG_TAG_PROXY, "$TAG; no win servers found, retry")
                    io {
                        retryLocationFetch()
                    }
                }
                return ok
            }
            else -> {
                Logger.e(LOG_TAG_PROXY, "$TAG; err; invalid type for register: $type")
                return false
            }
        }
    }

    suspend fun retryLocationFetch() {
        // keep retrying to fetch win properties for next 15 sec and see
        // if the locations are available
        for (i in 1..15) {
            Logger.i(LOG_TAG_PROXY, "$TAG; retrying to fetch win properties, attempt: $i")
            winServers = fetchAndConstructWinLocations()
            if (winServers.isNotEmpty()) {
                Logger.i(LOG_TAG_PROXY, "$TAG; win servers found after retry, attempt: $i, size: ${winServers.size}")
                break
            }
            Thread.sleep(1000L) // wait for 1 second before next retry
        }
    }

    suspend fun getWinServers(): List<RpnWinServer> {
        if (winServers.isNotEmpty()) {
            return winServers.toList()
        }

        Logger.w(LOG_TAG_PROXY, "$TAG; win servers are empty, fetching from tun")
        winServers = fetchAndConstructWinLocations()
        return winServers.toList()
    }

    suspend fun getWinEntitlement(): ByteArray? {
        if (winConfig == null || winConfig!!.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; win config is null or empty, returning empty byte array")
            // read from database if available
            val winProxy = db.getProxyById(WIN_ID)
            if (winProxy != null) {
                val file = File(winProxy.serverResPath)
                val bytes = EncryptedFileManager.readByteArray(applicationContext, file)
                if (bytes.isNotEmpty()) {
                    Logger.i(LOG_TAG_PROXY, "$TAG; win proxy found in db, returning bytes")
                    return bytes
                } else {
                    Logger.w(LOG_TAG_PROXY, "$TAG; win proxy file is empty, returning null")
                    return null
                }
            } else {
                Logger.w(LOG_TAG_PROXY, "$TAG; win proxy not found in db, returning null")
                return null
            }
        } else {
            Logger.i(LOG_TAG_PROXY, "$TAG; win config is not null, returning bytes")
            return winConfig
        }
    }

    suspend fun updateWinConfigState(byteArray: ByteArray?): Boolean {
        if (byteArray == null || byteArray.isEmpty()) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err; byte array is null for win config")
            return false
        }

        try {
            val res = updateWinConfigToFileAndDb(byteArray)
            Logger.i(LOG_TAG_PROXY, "$TAG; win config saved? $res")
            if (res) {
                winConfig = byteArray
                Logger.i(LOG_TAG_PROXY, "$TAG; win config updated")
            }
            return res
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err updating win config: ${e.message}", e)
        }
        return false
    }

    suspend fun getWinExistingData(): ByteArray? {
        return getExistingData(WIN_ID)
    }

    private suspend fun getExistingData(id: Int): ByteArray? {
        try {
            val db = db.getProxyById(id)
            if (db == null) {
                Logger.w(LOG_TAG_PROXY, "$TAG; db is null for id: $id")
                return null
            }
            val cfgFile = File(db.configPath)
            if (cfgFile.exists()) {
                Logger.d(LOG_TAG_PROXY, "$TAG; config for $id exists, reading the file")
                val bytes = EncryptedFileManager.readByteArray(applicationContext, cfgFile)
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

    suspend fun getSelectedCCs(): Set<String> {
        return selectedCountries
    }

    private suspend fun updateWinConfigToFileAndDb(state: ByteArray): Boolean {
        // write the win config to the file and update the database
        // store entitlement in serverResponse column and state in config path column
        val dir = File(
            applicationContext.filesDir.absolutePath +
                    File.separator +
                    RPN_PROXY_FOLDER_NAME +
                    File.separator +
                    WIN_NAME.lowercase() +
                    File.separator
        )
        if (!dir.exists()) {
            Logger.d(LOG_TAG_PROXY, "$TAG; creating dir: ${dir.absolutePath}")
            dir.mkdirs()
        }
        val cfgFile = File(dir, getConfigFileName(WIN_ID))
        try {
            // write the entitlement to the config file
            val cfgRes = EncryptedFileManager.write(applicationContext, state, cfgFile)
            Logger.i(LOG_TAG_PROXY, "$TAG writing win config to file: ${cfgFile.absolutePath}")
            val existingDb = db.getProxyById(WIN_ID)
            val l = if (existingDb != null) {
                // if the proxy already exists, update it
                existingDb.configPath = cfgFile.absolutePath
                db.update(existingDb)
            } else {
                // if the proxy does not exist, insert it
                val rpnProxy = RpnProxy(
                    id = WIN_ID,
                    name = WIN_NAME,
                    configPath = cfgFile.absolutePath,
                    serverResPath = "", // serverResPath is used to store the entitlement
                    isActive = true,
                    isLockdown = false,
                    createdTs = System.currentTimeMillis(),
                    modifiedTs = System.currentTimeMillis(),
                    misc = "",
                    tunId = "",
                    latency = 0,
                    lastRefreshTime = System.currentTimeMillis()
                )
                db.insert(rpnProxy).toInt()
            }
            Logger.d(LOG_TAG_PROXY, "$TAG; win config saved in db? ${l > 0}")
            if (l > 0 && cfgRes) {
                winConfig = state
                Logger.i(LOG_TAG_PROXY, "$TAG; win config updated")
                return true
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err writing win config to file: ${e.message}", e)
        }
        return false
    }

    private fun getConfigFileName(id: Int): String {
        return when (id) {
            WIN_ID -> WIN_STATE_FILE_NAME
            else -> ""
        }
    }

    private fun getJsonResponseFileName(id: Int): String {
        return when (id) {
            WIN_ID -> WIN_ENTITLEMENT_FILE_NAME
            else -> ""
        }
    }

    fun canSelectCountryCode(cc: String): Pair<Boolean, String> {
        // TODO: get country code from win config
        val isAvailable = false
        if (!isAvailable) {
            Logger.i(LOG_TAG_PROXY, "$TAG; cc not available in config: $cc")
            return Pair(false, "Country code not available in the config")
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

    fun stats(): String {
        val sb = StringBuilder()
        sb.append("   rpnState: ${rpnState().name}\n")
        sb.append("   rpnMode: ${rpnMode().name}\n")
        sb.append("   win config? ${winConfig != null}\n")
        //sb.append("   subscription stats: ${getSubscriptionStatistics()}\n")
        //sb.append("   current subscription: ${getDetailedSubscriptionInfo()}\n")
        sb.append("   selected countries: ${selectedCountries.size}, $selectedCountries\n")
        sb.append("   state machine stats: ${InAppBillingHandler.getConnectionStatusWithStateMachine()}\n")
        return sb.toString()
    }


    /**
     * Validate the payload received from Play Billing.
     * The payload is expected to be in the format: "accountId:session_token"
     * where accountId is the account ID from PipKeyManager and hashkey represents the user
     * session_token created during the purchase by server
     */

    suspend fun isValidPayload(payload: String): Boolean {
        if (payload.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; payload is empty")
            return false
        }
        val keyState = PipKeyManager.getToken(applicationContext)
        val keyFromPlayBilling = getCidFromPayload(payload)
        if (keyState.isEmpty() || keyFromPlayBilling.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; key state or key from play billing is empty")
            return false
        }
        if (keyState != keyFromPlayBilling) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; key state and key from play billing do not match")
            return false
        }
        Logger.i(LOG_TAG_PROXY, "$TAG; key state and key from play billing match, processing payment")
        return true
    }

    private fun getCidFromPayload(payload: String): String {
        // sample payload: payload={"ws":{"cid":"aa95f04efcb19a54c7605a02e5dd0b435906b993d12bec031a60f3f1272f4f0e","sessiontoken":"22605:4:1752145272:1da0c248e6cf32ca071a96e477bdf0033368599b4b:307dfd06996672f735409fec4807fcf40a0677e2ef","status":"valid"}}
        val payloadJson = try {
            JSONObject(payload)
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err parsing payload json: ${e.message}")
            return ""
        }
        val ws = payloadJson.optJSONObject("ws")
        if (ws == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; ws object is null in payload")
            return ""
        }
        return ws.optString("cid", "")
    }

    suspend fun isValidAccountId(accountId: String): Boolean {
        if (accountId.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; accountId is empty")
            return false
        }
        val keyState = PipKeyManager.getToken(applicationContext)
        if (keyState.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; key state is empty")
            return false
        }
        if (keyState != accountId) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; key state and accountId do not match")
            return false
        }
        Logger.i(LOG_TAG_PROXY, "$TAG; key state and accountId match, processing payment")
        return true
    }

    suspend fun updateCancelledSubscription(accountId: String, purchaseToken: String): Boolean {
        if (purchaseToken.isEmpty() || accountId.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; purchaseToken is empty")
            return false
        }
        // TODO: perform the validation of purchaseToken and accountId with the subscription data
        handleUserCancellation()
        return true
    }

    suspend fun getCurrentSubscription(): SubscriptionStateMachineV2.SubscriptionData? {
        return subscriptionStateMachine.getSubscriptionData()
    }

    suspend fun updateRevokedSubscription(accountId: String, purchaseToken: String): Boolean {
        if (purchaseToken.isEmpty() || accountId.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; purchaseToken is empty")
            return false
        }
        handleSubscriptionRevoked()
        return true
    }

    suspend fun fetchAndConstructWinLocations(): Set<RpnWinServer> {
        // there will be multiple location names for single country code
        // construct the RpnWinServer object
        // contains pair RpnProps and errorMsg
        val winProps = VpnController.getRpnProps(RpnType.WIN).first
        if (winProps == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; win props is null")
            return emptySet()
        }
        val count = winProps.locations.len()
        if (count == 0L) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; no locations found in win props")
            return emptySet()
        }
        val servers = mutableSetOf<RpnWinServer>()
        for( i in 0 until count) {
            val loc = winProps.locations.get(i)
            if (loc == null) {
                Logger.w(LOG_TAG_PROXY, "$TAG; err; location is null at index $i")
                continue
            }
            val prevNames = servers.filter { it.countryCode == loc.cc }.map { it.names }.toMutableList()
            prevNames.add(loc.name)
            val newNames = prevNames.distinct().sorted().joinToString { "," }
            // each cc will have multiple locations
            // add that to the list of servers
            val s = RpnWinServer(newNames, loc.cc, loc.addrs, true)
            servers.add(s)
        }
        // assign it to winServers
        return servers
    }

    suspend fun processRpnPurchase(purchase: PurchaseDetail?, existingSubs: SubscriptionStatus): Boolean {
        if (purchase == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; no purchases to process")
            try {
                subscriptionStateMachine.subscriptionExpired()
            } catch (e: Exception) {
                Logger.e(
                    LOG_TAG_PROXY,
                    "$TAG; error notifying state machine of expiration: ${e.message}",
                    e
                )
            }
            return false
        }

        if (purchase.productId.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; productId is empty for purchase: $purchase")
            try {
                subscriptionStateMachine.purchaseFailed("Empty product ID", null)
            } catch (e: Exception) {
                Logger.e(
                    LOG_TAG_PROXY,
                    "$TAG; error notifying state machine of purchase failure: ${e.message}",
                    e
                )
            }
            return false
        }

        // Enhanced validation
        if (!isValidPayload(purchase.payload) && !isValidAccountId(purchase.accountId)) {
            Logger.w(
                LOG_TAG_PROXY,
                "$TAG; err; invalid payload or account ID for purchase: $purchase"
            )
            try {
                subscriptionStateMachine.purchaseFailed(
                    "Invalid payload or account ID",
                    null
                )
            } catch (e: Exception) {
                Logger.e(
                    LOG_TAG_PROXY,
                    "$TAG; error notifying state machine of validation failure: ${e.message}",
                    e
                )
            }
            return false
        }

        val accExpiry = existingSubs.accountExpiry
        val billingExpiry = existingSubs.billingExpiry
        val currTs = System.currentTimeMillis()

        if (billingExpiry > currTs) {
            Logger.d(LOG_TAG_PROXY, "$TAG; existing subscription is still valid, no immediate action needed")
        }


        if (accExpiry > currTs) {
            Logger.d(LOG_TAG_PROXY, "$TAG; existing account is still valid, no immediate action needed")
        }

        // in case if the account expiry is less than the billing expiry, query the entitlement
        // from server and update the subscription state machine
        // Check if the billing expiry + 1 day is greater than the account expiry.
        // there is always a delay so just add 1 more day to the billing expiry
        val oneDay = 24 * 60 * 60 * 1000 // 1 day in milliseconds
        if (accExpiry < billingExpiry + oneDay ) {
            Logger.d(LOG_TAG_PROXY, "$TAG; account expiry is less than billing expiry, querying entitlement")
            try {
                val developerPayload = InAppBillingHandler.queryEntitlementFromServer(purchase.accountId)
                if (developerPayload != null && developerPayload.isNotEmpty()) {
                    Logger.i(LOG_TAG_PROXY, "$TAG; developer payload received for ${purchase.productId}")
                    activateRpn(purchase, developerPayload)
                    val newPurchase = purchase.copy(payload = developerPayload)
                    val subsData = subscriptionStateMachine.getSubscriptionData()
                    if (subsData != null) {
                        subsData.subscriptionStatus.developerPayload = newPurchase.payload
                        subsData.purchaseDetail?.copy(payload = developerPayload)
                        subscriptionStateMachine.stateMachine.updateData(subsData)
                    }
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_PROXY, "$TAG; error querying entitlement: ${e.message}")
            }
        }

        // activate the RPN
        activateRpn(purchase)
        return true
    }

    private fun startStateObserver() {
        stateObserverScope.launch {
            try {
                subscriptionStateMachine.currentState.collect { state ->
                    Logger.d(LOG_TAG_PROXY, "$TAG; collect; initial subscription state: ${state.name}")
                    io { handleStateChange(state) }
                 }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; collect; error in state observer: ${e.message}", e)
            }
        }
    }

    private suspend fun handleStateChange(state: SubscriptionStateMachineV2.SubscriptionState) {
        when (state) {
            is SubscriptionStateMachineV2.SubscriptionState.Active -> {
                Logger.i(
                    LOG_TAG_PROXY,
                    "$TAG; subscription activated, ensuring RPN is enabled if configured"
                )
                // Could potentially auto-enable RPN if conditions are met
                if (!isRpnEnabled()) {
                    val subs = subscriptionStateMachine.getSubscriptionData()
                    val purchaseDetail = subs?.purchaseDetail
                    if (purchaseDetail == null) { // this should not happen
                        Logger.w(LOG_TAG_PROXY, "$TAG; no purchase detail available for activation, but state is active")
                        return
                    }
                    activateRpn(purchaseDetail)
                }
            }

            is SubscriptionStateMachineV2.SubscriptionState.Cancelled -> {
                Logger.i(LOG_TAG_PROXY, "$TAG; subscription cancelled, disabling RPN if active")
                val subs = subscriptionStateMachine.getSubscriptionData()
                val status = subs?.subscriptionStatus
                val currTs = System.currentTimeMillis()
                if ((status != null && status.billingExpiry > currTs) || DEBUG) {
                    deactivateRpn("Subscription cancelled")
                } else {
                    Logger.w(LOG_TAG_PROXY, "$TAG; subscription cancelled but still valid, not deactivating RPN")
                }
            }

            is SubscriptionStateMachineV2.SubscriptionState.Expired -> {
                Logger.w(LOG_TAG_PROXY, "$TAG; subscription expired, disabling RPN")
                deactivateRpn("Subscription expired")
            }

            is SubscriptionStateMachineV2.SubscriptionState.Revoked -> {
                Logger.w(LOG_TAG_PROXY, "$TAG; subscription revoked, immediately disabling RPN")
                deactivateRpn("Subscription revoked")
            }

            is SubscriptionStateMachineV2.SubscriptionState.Error -> {
                Logger.e(LOG_TAG_PROXY, "$TAG; subscription state machine in error state")
                // Could implement error recovery logic here
            }

            else -> {
                Logger.d(LOG_TAG_PROXY, "$TAG; subscription state: ${state.name}")
            }
        }
    }

    fun collectSubscriptionState(): Flow<SubscriptionStateMachineV2.SubscriptionState> {
        return subscriptionStateMachine.currentState
    }

    fun getSubscriptionState(): SubscriptionStateMachineV2.SubscriptionState {
        return subscriptionStateMachine.getCurrentState()
    }

    fun getSubscriptionData(): SubscriptionStateMachineV2.SubscriptionData? {
        return subscriptionStateMachine.getSubscriptionData()
    }

    fun canMakePurchase(): Boolean {
        return subscriptionStateMachine.canMakePurchase()
    }

    fun hasValidSubscription(): Boolean {
        if (DEBUG) {
            persistentState.rpnState = RpnState.ENABLED.id
            return true // temporarily always return true
        }
        val valid = subscriptionStateMachine.hasValidSubscription()
        Logger.i(LOG_TAG_PROXY, "$TAG; using state machine for subscription check, valid: $valid")
        return valid
    }

    fun isSubscriptionActiveInStateMachine(): Boolean {
        return subscriptionStateMachine.isSubscriptionActive()
    }

    suspend fun handleSubscriptionRestored(purchaseDetail: PurchaseDetail) {
        try {
            subscriptionStateMachine.restoreSubscription(purchaseDetail)
            Logger.i(LOG_TAG_PROXY, "$TAG; subscription restored: ${purchaseDetail.productId}")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error restoring subscription: ${e.message}", e)
        }
    }

    suspend fun handleUserCancellation() {
        try {
            subscriptionStateMachine.userCancelled()
            Logger.i(LOG_TAG_PROXY, "$TAG; user cancellation handled")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error handling user cancellation: ${e.message}", e)
        }
    }

    suspend fun handleSubscriptionRevoked() {
        try {
            subscriptionStateMachine.subscriptionRevoked()
            Logger.w(LOG_TAG_PROXY, "$TAG; subscription revocation handled")

            // Immediately deactivate RPN
            deactivateRpn()
            Logger.i(LOG_TAG_PROXY, "$TAG; RPN deactivated due to revocation")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error handling subscription revocation: ${e.message}", e)
        }
    }

    suspend fun performSystemCheck() {
        try {
            subscriptionStateMachine.systemCheck()
            Logger.d(LOG_TAG_PROXY, "$TAG; system check performed")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error during system check: ${e.message}", e)
        }
    }

    fun getSubscriptionStatistics(): StateMachineStatistics? {
        return subscriptionStateMachine.getStatistics()
    }

    fun cleanup() {
        try {
            stateObserverJob.cancel()
            Logger.i(LOG_TAG_PROXY, "$TAG; subscription state machine integration cleaned up")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error during cleanup: ${e.message}", e)
        }
    }

    private fun isValidForRpnActivation(purchase: PurchaseDetail): Boolean {
        // Check basic purchase validity
        if (purchase.productId.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; invalid purchase - empty product ID")
            return false
        }

        // Check if purchase is revoked
        if (purchase.status == SubscriptionStatus.SubscriptionState.STATE_REVOKED.id) {
            Logger.w(LOG_TAG_PROXY, "$TAG; invalid purchase - revoked status")
            return false
        }

        // Check if purchase is expired
        if (purchase.expiryTime > 0 && System.currentTimeMillis() > purchase.expiryTime) {
            Logger.w(LOG_TAG_PROXY, "$TAG; invalid purchase - expired")
            return false
        }

        // Check state machine state if available
        val currentState = subscriptionStateMachine.getCurrentState()
        when (currentState) {
            is SubscriptionStateMachineV2.SubscriptionState.Revoked,
            is SubscriptionStateMachineV2.SubscriptionState.Expired -> {
                Logger.w(
                    LOG_TAG_PROXY,
                    "$TAG; invalid for activation - state machine in ${currentState.name}"
                )
                return false
            }

            else -> {
                // Other states are potentially valid
            }
        }

        return true
    }

    fun isRpnValidForCurrentSubscription(): Boolean {
        if (!isRpnEnabled()) {
            return false
        }

        val currentState = subscriptionStateMachine.getCurrentState()
        when (currentState) {
            is SubscriptionStateMachineV2.SubscriptionState.Active,
            is SubscriptionStateMachineV2.SubscriptionState.Cancelled -> {
                Logger.i(
                    LOG_TAG_PROXY,
                    "$TAG; RPN is valid for current subscription state: ${currentState.name}"
                )
                return true
            }

            is SubscriptionStateMachineV2.SubscriptionState.Revoked,
            is SubscriptionStateMachineV2.SubscriptionState.Expired -> {
                Logger.w(
                    LOG_TAG_PROXY,
                    "$TAG; RPN should be disabled - subscription ${currentState.name}"
                )
                return false
            }

            else -> {
                Logger.d(
                    LOG_TAG_PROXY,
                    "$TAG; RPN validity uncertain for state: ${currentState.name}"
                )
                return true // Allow by default for uncertain states
            }
        }
    }

    fun getDetailedSubscriptionInfo(): String {
        val statistics = subscriptionStateMachine.getStatistics()
        val subscriptionData = subscriptionStateMachine.getSubscriptionData()
        val currentState = subscriptionStateMachine.getCurrentState()

        return """
            Current State: ${currentState.name}
            RPN Enabled: ${isRpnEnabled()}
            RPN Valid: ${isRpnValidForCurrentSubscription()}
            Total Transitions: ${statistics.totalTransitions}
            Success Rate: ${String.format("%.2f", statistics.successRate * 100)}%
            Product ID: ${subscriptionData?.subscriptionStatus?.productId ?: "None"}
            Subscription Status: ${subscriptionData?.subscriptionStatus?.status?.let { SubscriptionStatus.SubscriptionState.fromId(it).name } ?: "Unknown"}
            Billing Expiry: ${subscriptionData?.subscriptionStatus?.billingExpiry?.let { if (it > 0) java.util.Date(it) else "N/A" } ?: "N/A"}
            Account Expiry: ${subscriptionData?.subscriptionStatus?.accountExpiry?.let { if (it > 0) java.util.Date(it) else "N/A" } ?: "N/A"}
        """.trimIndent()
    }

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
    }


}
*/