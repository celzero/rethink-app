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
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.database.CountryConfigRepository
import com.celzero.bravedns.database.RpnProxy
import com.celzero.bravedns.database.RpnProxyRepository
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EncryptedFileManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.ProxyManager.ID_RPN_WIN
import com.celzero.bravedns.service.ProxyManager.ID_WG_BASE
import com.celzero.bravedns.service.VpnController
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
import kotlinx.coroutines.delay
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
    private val countryConfigRepo: CountryConfigRepository by inject()
    private val persistentState by inject<PersistentState>()

    private const val WIN_ID = 4
    private const val WIN_NAME = "WIN"
    private const val WIN_ENTITLEMENT_FILE_NAME = "win_response.json"
    private const val WIN_STATE_FILE_NAME = "win_state.json"
    const val MAX_WIN_SERVERS = 5

    private var winConfig: ByteArray? = null
    // In-memory cache for WIN servers (CountryConfig as unified model)
    private val winServersCache = mutableListOf<CountryConfig>()

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

        // Populate WIN servers cache from DB at startup
        try {
            val dbServers = countryConfigRepo.getAllConfigs()
            synchronized(winServersCache) {
                winServersCache.clear()
                winServersCache.addAll(dbServers)
            }
            Logger.i(LOG_TAG_PROXY, "$TAG; load: cached ${dbServers.size} WIN servers from DB")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; load: failed to cache WIN servers: ${e.message}")
        }

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
                // Fetch servers from API and sync to database and cache
                val (servers, removedSelectedIds) = fetchAndConstructWinLocations()
                if (servers.isEmpty()) {
                    Logger.w(LOG_TAG_PROXY, "$TAG; no win servers found, retry")
                    retryLocationFetch()
                } else {
                    syncWinServers(servers)
                    // Notify about removed servers if any were selected
                    if (removedSelectedIds.isNotEmpty()) {
                        Logger.w(LOG_TAG_PROXY, "$TAG; ${removedSelectedIds.size} selected servers were removed from the list")
                        // Notification will be shown by ServerSelectionFragment when it detects the change
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
        for (i in 1..15) {
            Logger.i(LOG_TAG_PROXY, "$TAG; retrying to fetch win properties, attempt: $i")
            val (servers, removedSelectedIds) = fetchAndConstructWinLocations()
            if (servers.isNotEmpty()) {
                Logger.i(LOG_TAG_PROXY, "$TAG; win servers found after retry, attempt: $i, size: ${servers.size}")
                syncWinServers(servers)
                // Notify about removed servers if any were selected
                if (removedSelectedIds.isNotEmpty()) {
                    Logger.w(LOG_TAG_PROXY, "$TAG; retryLocationFetch: ${removedSelectedIds.size} selected servers were removed")
                }
                break
            }
            delay(1000L)
        }
    }

    /**
     * Get all WIN servers.
     * Prefers in-memory cache; falls back to DB; if empty, fetches from API, syncs DB, and updates cache.
     */
    suspend fun getWinServers(): List<CountryConfig> {
        // Return cached list if available
        synchronized(winServersCache) {
            if (winServersCache.isNotEmpty()) {
                Logger.v(LOG_TAG_PROXY, "$TAG; returning cached win servers, size: ${winServersCache.size}")
                return winServersCache.toList()
            }
        }

        // Try DB next
        val dbServers = countryConfigRepo.getAllConfigs()
        if (dbServers.isNotEmpty()) {
            synchronized(winServersCache) {
                winServersCache.clear()
                winServersCache.addAll(dbServers)
            }
            Logger.v(LOG_TAG_PROXY, "$TAG; loaded ${dbServers.size} win servers from DB into cache")
            return dbServers
        }

        // Database is empty, fetch from API and sync
        Logger.w(LOG_TAG_PROXY, "$TAG; database is empty, fetching win servers from tun")
        val (apiServers, removedSelectedIds) = fetchAndConstructWinLocations()
        if (apiServers.isNotEmpty()) {
            syncWinServers(apiServers)
            if (removedSelectedIds.isNotEmpty()) {
                Logger.w(LOG_TAG_PROXY, "$TAG; getWinServers: ${removedSelectedIds.size} selected servers were removed")
            }
        }
        return apiServers.toList()
    }

    // ===== WIN Server Database Operations (delegated to CountryConfigRepository) =====
    // These methods are now available through countryConfigRepo if needed elsewhere

    suspend fun getWinEntitlement(): ByteArray? {
        val proxy = db.getProxyById(WIN_ID) ?: return null
        val file = File(proxy.serverResPath)
        if (!file.exists()) return null
        val bytes = EncryptedFileManager.readByteArray(applicationContext, file)
        return if (bytes.isNotEmpty()) bytes else null
    }

    /**
     * Get current subscription data including purchase details and status.
     * Used by UI fragments to display subscription information.
     */
    fun getSubscriptionData(): SubscriptionStateMachineV2.SubscriptionData? {
        return try {
            subscriptionStateMachine.getSubscriptionData()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error getting subscription data: ${e.message}", e)
            null
        }
    }

    /**
     * Get current subscription state.
     * Used by UI to display the current state of the subscription.
     */
    fun getSubscriptionState(): SubscriptionStateMachineV2.SubscriptionState {
        return subscriptionStateMachine.getCurrentState()
    }

    suspend fun getCurrentSubscription(): SubscriptionStateMachineV2.SubscriptionData? {
        return subscriptionStateMachine.getSubscriptionData()
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

    suspend fun updateRevokedSubscription(accountId: String, purchaseToken: String): Boolean {
        if (purchaseToken.isEmpty() || accountId.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; purchaseToken is empty")
            return false
        }
        handleSubscriptionRevoked()
        return true
    }

    suspend fun processRpnPurchase(purchase: PurchaseDetail?, existingSubs: SubscriptionStatus): Boolean {
        if (purchase == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; no purchases to process")
            try {
                subscriptionStateMachine.subscriptionExpired()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; error notifying state machine of expiration: ${e.message}", e)
            }
            return false
        }

        if (purchase.productId.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; err; productId is empty for purchase: $purchase")
            try {
                subscriptionStateMachine.purchaseFailed("Empty product ID", null)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; error notifying state machine of purchase failure: ${e.message}", e)
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

        val oneDay = 24 * 60 * 60 * 1000
        if (accExpiry < billingExpiry + oneDay) {
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
                Logger.i(LOG_TAG_PROXY, "$TAG; subscription activated, ensuring RPN is enabled if configured")
                if (!isRpnEnabled()) {
                    val subs = subscriptionStateMachine.getSubscriptionData()
                    val purchaseDetail = subs?.purchaseDetail
                    if (purchaseDetail == null) {
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
            }
            else -> {
                Logger.d(LOG_TAG_PROXY, "$TAG; subscription state: ${state.name}")
            }
        }
    }

    fun collectSubscriptionState(): Flow<SubscriptionStateMachineV2.SubscriptionState> {
        return subscriptionStateMachine.currentState
    }

    fun hasValidSubscription(): Boolean {
        /*if (DEBUG) {
            persistentState.rpnState = RpnState.ENABLED.id
            return true
        }*/
        val valid = subscriptionStateMachine.hasValidSubscription()
        Logger.i(LOG_TAG_PROXY, "$TAG; using state machine for subscription check, valid: $valid")
        return valid
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
            deactivateRpn()
            Logger.i(LOG_TAG_PROXY, "$TAG; RPN deactivated due to revocation")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error handling subscription revocation: ${e.message}", e)
        }
    }

    fun isRpnValidForCurrentSubscription(): Boolean {
        if (!isRpnEnabled()) {
            return false
        }

        val currentState = subscriptionStateMachine.getCurrentState()
        when (currentState) {
            is SubscriptionStateMachineV2.SubscriptionState.Active,
            is SubscriptionStateMachineV2.SubscriptionState.Cancelled -> {
                Logger.i(LOG_TAG_PROXY, "$TAG; RPN is valid for current subscription state: ${currentState.name}")
                return true
            }
            is SubscriptionStateMachineV2.SubscriptionState.Revoked,
            is SubscriptionStateMachineV2.SubscriptionState.Expired -> {
                Logger.w(LOG_TAG_PROXY, "$TAG; RPN should be disabled - subscription ${currentState.name}")
                return false
            }
            else -> {
                Logger.d(LOG_TAG_PROXY, "$TAG; RPN validity uncertain for state: ${currentState.name}")
                return true
            }
        }
    }

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

    fun canSelectCountryCode(cc: String): Pair<Boolean, String> {
        // TODO: get country code from win config
        val isAvailable = false
        if (!isAvailable) {
            Logger.i(LOG_TAG_PROXY, "$TAG; cc not available in config: $cc")
            return Pair(false, "Country code not available in the config")
        }
        return if (selectedCountries.size >= 5) {
            Logger.i(
                LOG_TAG_PROXY,
                "$TAG; cc limit reached, selected: ${selectedCountries.size}, $selectedCountries"
            )
            Pair(false, "Country code limit reached for the selected endpoint")
        } else {
            selectedCountries.add(cc)
            Logger.d(LOG_TAG_PROXY, "$TAG; cc added to selected list: $cc")
            Pair(true, "")
        }
    }

    suspend fun getSelectedCCs(): Set<String> {
        return selectedCountries
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

    private fun io(f: suspend () -> Unit) {
        CoroutineScope(Dispatchers.IO).launch { f() }
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

    suspend fun getWinExistingData(): ByteArray? {
        winConfig?.let { if (it.isNotEmpty()) return it }
        val proxy = db.getProxyById(WIN_ID) ?: return null
        val cfg = proxy.configPath
        if (cfg.isEmpty()) return null
        val file = File(cfg)
        if (!file.exists()) return null
        return EncryptedFileManager.readByteArray(applicationContext, file)
    }


    suspend fun updateWinConfigState(byteArray: ByteArray?): Boolean {
        if (byteArray == null || byteArray.isEmpty()) return false
        return try {
            val fileName = getConfigFileName(WIN_ID)
            val file = File(applicationContext.getExternalFilesDir(RPN_PROXY_FOLDER_NAME), fileName)
            val ok = EncryptedFileManager.write(applicationContext, byteArray, file)
            if (!ok) return false
            val proxy = db.getProxyById(WIN_ID)
            if (proxy != null) {
                proxy.configPath = file.absolutePath
                proxy.modifiedTs = System.currentTimeMillis()
                db.update(proxy)
            } else {
                val newProxy = RpnProxy(
                    id = WIN_ID,
                    name = WIN_NAME,
                    configPath = file.absolutePath,
                    serverResPath = "",
                    isActive = true,
                    isLockdown = false,
                    createdTs = System.currentTimeMillis(),
                    modifiedTs = System.currentTimeMillis(),
                    misc = "",
                    tunId = "",
                    latency = 0,
                    lastRefreshTime = System.currentTimeMillis()
                )
                db.insert(newProxy)
            }
            winConfig = byteArray
            true
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; err updating win config: ${e.message}", e)
            false
        }
    }

    /**
     * Fetches WIN server locations from the API and syncs with database and cache.
     * Returns a Pair of:
     * - First: Set of new/updated servers from API
     * - Second: List of removed server IDs that were in the selected list (for notifications)
     */
    suspend fun fetchAndConstructWinLocations(): Pair<Set<CountryConfig>, List<String>> {
         // Fetch from API
         val winProps = VpnController.getRpnProps(RpnType.WIN).first
         if (winProps == null) {
             Logger.w(LOG_TAG_PROXY, "$TAG; err; win props is null")
             return Pair(emptySet(), emptyList())
         }
         val count = winProps.locations.len()
         if (count == 0L) {
             Logger.w(LOG_TAG_PROXY, "$TAG; err; no locations found in win props")
             return Pair(emptySet(), emptyList())
         }

         val newServers = mutableSetOf<CountryConfig>()
         for(i in 0 until count) {
             val loc = winProps.locations.get(i)
             if (loc == null) {
                 Logger.w(LOG_TAG_PROXY, "$TAG; err; location is null at index $i")
                 continue
             }
             val id = "${loc.cc}-${loc.name}-${loc.key}"
             val cfg = CountryConfig(
                 id = id,
                 cc = loc.cc,
                 name = loc.name,
                 address = loc.addrs,
                 city = loc.name,
                 key = loc.key,
                 load = 0,
                 link = 0,
                 count = 1,
                 isActive = true
             )
             newServers.add(cfg)
         }

         // Get existing servers from DB to identify removed ones
         val existingServers = countryConfigRepo.getAllConfigs()
         val existingIds = existingServers.map { it.id }.toSet()
         val newIds = newServers.map { it.id }.toSet()
         val removedIds = existingIds - newIds

         // Check if any removed servers were in the selected list
         val removedSelectedIds = mutableListOf<String>()
         if (removedIds.isNotEmpty()) {
             for (removedId in removedIds) {
                 val removed = existingServers.firstOrNull { it.id == removedId }
                 if (removed != null && selectedCountries.contains(removed.cc)) {
                     removedSelectedIds.add(removedId)
                     // Remove from selected countries
                     selectedCountries.remove(removed.cc)
                     Logger.w(LOG_TAG_PROXY, "$TAG; removed server $removedId (${removed.cc}) was in selected list")
                 }
             }
         }

         Logger.i(LOG_TAG_PROXY, "$TAG; fetchAndConstructWinLocations: new=${newServers.size}, existing=${existingIds.size}, removed=${removedIds.size}, removedSelected=${removedSelectedIds.size}")

         return Pair(newServers, removedSelectedIds)
    }

    /**
     * Enables a WIN server by its key.
     * Updates the database and in-memory cache upon successful activation.
     */
    suspend fun enableWinServer(key: String): Pair<Boolean, String> {
        val config = winServersCache.find { it.key == key }

        if (config != null) {
            val res = VpnController.addNewWinServer(config.key)
            if (res.first) {
                countryConfigRepo.update(config)
                config.isActive = true
                Logger.i(LOG_TAG_PROXY, "$TAG; enableWinServer: enabled rpn: $key")
            } else {
                Logger.w(LOG_TAG_PROXY, "$TAG; enableWinServer: failed to enable server with key $key, error: ${res.second}")
            }
            return res
        } else {
            Logger.w(LOG_TAG_PROXY, "$TAG; enableWinServer: server with key $key not found")
            return Pair(false, "Server with key $key not found")
        }
    }

    suspend fun setCatchAllForWinServer(key: String, catchAll: Boolean) {
        winServersCache.find { it.key == key }?.let {
            if (!it.isActive && catchAll) {
                Logger.w(LOG_TAG_PROXY, "$TAG; setCatchAllForWinServer: enabling inactive server $key for catch-all")
                val res = VpnController.addNewWinServer(it.key)
                if (res.first) {
                    it.isActive = true
                    Logger.i(LOG_TAG_PROXY, "$TAG; setCatchAllForWinServer: enabled server $key for catch-all")
                } else {
                    Logger.w(LOG_TAG_PROXY, "$TAG; setCatchAllForWinServer: failed to enable server $key for catch-all, error: ${res.second}")
                    return@let
                }
            }
            it.catchAll = catchAll
            countryConfigRepo.update(it)
            Logger.i(LOG_TAG_PROXY, "$TAG; setCatchAllForWinServer: set catchAll=$catchAll for server: $key")
        } ?: Logger.w(LOG_TAG_PROXY, "$TAG; setCatchAllForWinServer: server with key $key not found")
    }

    suspend fun setLockdownForWinServer(key: String, lockdown: Boolean) {
        winServersCache.find { it.key == key }?.let {
            it.lockdown = lockdown
            countryConfigRepo.update(it)
            Logger.i(LOG_TAG_PROXY, "$TAG; setLockdownForWinServer: set lockdown=$lockdown for server: $key")
        } ?: Logger.w(LOG_TAG_PROXY, "$TAG; setLockdownForWinServer: server with key $key not found")
    }

    suspend fun disableWinServer(key: String): Pair<Boolean, String> {
        val config = winServersCache.find { it.key == key }
        if (config != null) {
            val res = VpnController.removeWinServer(config.key)
            if (res.first) {
                countryConfigRepo.update(config)
                config.isActive = false
                Logger.i(LOG_TAG_PROXY, "$TAG; disableWinServer: disabled rpn: $key")
            } else {
                Logger.w(LOG_TAG_PROXY, "$TAG; disableWinServer: failed to disable server with key $key, error: ${res.second}")
            }
            return res
        } else {
            Logger.w(LOG_TAG_PROXY, "$TAG; disableWinServer: server with key $key not found")
            return Pair(false, "Server with key $key not found")
        }
    }

     /**
      * Syncs fetched servers with database and cache.
      * This should be called after fetchAndConstructWinLocations.
      */
     suspend fun syncWinServers(servers: Set<CountryConfig>) {
         try {
             // Sync to database (this handles insertions, updates, and deletions)
             val syncServerList = if (servers.isEmpty()) {
                 Logger.w(LOG_TAG_PROXY, "$TAG; syncWinServers: empty server list, clearing DB")
                    emptyList<CountryConfig>()
             } else {
                 servers.toList()
             }
             countryConfigRepo.syncServers(syncServerList)

             // Update cache - clear and refill with new data
             synchronized(winServersCache) {
                 winServersCache.clear()
                 winServersCache.addAll(servers)
             }

             Logger.i(LOG_TAG_PROXY, "$TAG; syncWinServers: synced ${servers.size} servers to DB & cache")
         } catch (e: Exception) {
             Logger.e(LOG_TAG_PROXY, "$TAG; syncWinServers: error - ${e.message}", e)
         }
     }

     /**
      * Refreshes WIN servers from API and returns information about removed servers.
      * This is useful for UI to detect and notify users about removed selected servers.
      * Returns a Pair of:
      * - First: List of all current servers after refresh
      * - Second: List of CountryConfig objects that were removed and were in the selected list
      */
     suspend fun refreshWinServers(): Pair<List<CountryConfig>, List<CountryConfig>> {
         try {
             val existingServers = countryConfigRepo.getAllConfigs()
             val (newServers, removedSelectedIds) = fetchAndConstructWinLocations()

             // Sync to DB and cache
             syncWinServers(newServers)

             // Find the actual removed server objects for notification
             val removedServers = existingServers.filter { removedSelectedIds.contains(it.id) }

             Logger.i(LOG_TAG_PROXY, "$TAG; refreshWinServers: refreshed ${newServers.size} servers, ${removedServers.size} selected servers removed")
             return Pair(newServers.toList(), removedServers)
         } catch (e: Exception) {
             Logger.e(LOG_TAG_PROXY, "$TAG; refreshWinServers: error - ${e.message}", e)
             return Pair(emptyList(), emptyList())
         }
     }

    suspend fun getAllPossibleConfigIdsForApp(
        uid: Int,
        ip: String,
        port: Int,
        domain: String,
        usesMobileNw: Boolean,
        ssid: String
    ): List<String> {
        val block = Backend.Block
        val proxyIds: MutableList<String> = mutableListOf()

        // --- App-specific WireGuard configs (multi-proxy aware) ---
        // collect all proxy-ids for this uid and keep only WireGuard ones (wgX)
        val allProxyIdsForApp = ProxyManager.getProxyIdsForApp(uid)
        val rpnProxyIdsForApp = allProxyIdsForApp.filter { it.startsWith(ID_RPN_WIN) }

        // app-specific configs may be empty if the app is not configured
        if (rpnProxyIdsForApp.isNotEmpty()) {
            for (pid in rpnProxyIdsForApp) {
                val appProxyPair = canUseConfig(pid, "app($uid)", usesMobileNw, ssid)
                if (!appProxyPair.second) {
                    // lockdown or block; honor it and stop further processing
                    proxyIds.clear()
                    if (appProxyPair.first == block) {
                        proxyIds.add(block)
                    } else if (appProxyPair.first.isNotEmpty()) {
                        proxyIds.add(appProxyPair.first)
                    }
                    Logger.i(LOG_TAG_PROXY, "lockdown wg for app($uid) => return $proxyIds")
                    return proxyIds
                }
                if (appProxyPair.first.isNotEmpty()) {
                    // add eligible app-specific config in the order we see them
                    proxyIds.add(appProxyPair.first)
                }
            }
        }

        // once the app-specific config is added, check if any catch-all config is enabled
        // if catch-all config is enabled, then add the config id to the list
        val cac = winServersCache.filter { it.isActive && it.catchAll }
        cac.forEach {
            if ((checkEligibilityBasedOnNw(
                    it.id,
                    usesMobileNw
                ) && checkEligibilityBasedOnSsid(it.id, ssid)) &&
                !proxyIds.contains(ID_WG_BASE + it.id)
            ) {
                proxyIds.add(ID_WG_BASE + it.id)
                Logger.i(
                    LOG_TAG_PROXY,
                    "catch-all config is active: ${it.id}, ${it.name} => add ${ID_WG_BASE + it.id}"
                )
            }
        }

        // the proxyIds list will contain the ip-app specific, domain-app specific, app specific,
        // universal ip, universal domain, catch-all and default configs in the order of priority
        // the go-tun will check the routing based on the order of the list
        Logger.i(LOG_TAG_PROXY, "returning proxy ids for $uid, $ip, $port, $domain: $proxyIds")
        return proxyIds
    }

    private fun isDnsRequest(defaultTid: String): Boolean {
        return defaultTid == Backend.System || defaultTid == Backend.Plus || defaultTid == Backend.Preferred
    }

    private suspend fun canUseConfig(
        id: String,
        type: String,
        usesMtrdNw: Boolean,
        ssid: String
    ): Pair<String, Boolean> {
        if (id.isEmpty()) {
            return Pair("", true)
        }

        val config = winServersCache.find { it.id == id }

        if (config == null) {
            Logger.d(LOG_TAG_PROXY, "config null($id) no need to proceed, return empty")
            winServersCache.forEach {
                Logger.d(LOG_TAG_PROXY, "cached wg: ${it.id}, active: ${it.isActive}, lockdown: ${it.lockdown}")
            }
            return Pair("", true)
        }

        if (config.lockdown && (checkEligibilityBasedOnNw(
                id,
                usesMtrdNw
            ) && checkEligibilityBasedOnSsid(id, ssid))
        ) {
            Logger.d(LOG_TAG_PROXY, "lockdown wg for $type => return $id")
            return Pair(id, false) // no need to proceed further for lockdown
        }

        // check if the config is active and if it can be used on this network
        if (config.isActive && (checkEligibilityBasedOnNw(
                id,
                usesMtrdNw
            ) && checkEligibilityBasedOnSsid(id, ssid))
        ) {
            Logger.d(LOG_TAG_PROXY, "active wg for $type => add $id")
            return Pair(id, true)
        }

        Logger.v(
            LOG_TAG_PROXY,
            "wg for $type not active or not eligible nw, return empty, for id: $id, usesMtrdNw: $usesMtrdNw, ssid: $ssid"
        )
        return Pair("", true)
    }

    // only when config is set to use on mobile network and current network is not mobile
    // then return false, all other cases return true
    private fun checkEligibilityBasedOnNw(id: String, usesMobileNw: Boolean): Boolean {
        val config = winServersCache.find { it.id == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "canAdd: wg not found, id: $id, ${winServersCache.size}")
            return false
        }

        if (config.mobileOnly && !usesMobileNw) {
            Logger.i(LOG_TAG_PROXY, "canAdd: useOnlyOnMetered is true, but not metered nw")
            return false
        }

        Logger.d(LOG_TAG_PROXY, "canAdd: eligible for metered nw: $usesMobileNw")
        return true
    }

    private fun checkEligibilityBasedOnSsid(id: String, ssid: String): Boolean {
        val config = winServersCache.find { it.id == id }
        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "canAdd: wg not found, id: $id, ${winServersCache.size}")
            return false
        }

        if (config.ssidBased) {
            val ssids = "" //config.ssidList
            val ssidItems = SsidItem.parseStorageList(ssids)
            if (ssidItems.isEmpty()) { // treat empty as match all
                Logger.d(
                    LOG_TAG_PROXY,
                    "canAdd: ssidEnabled is true, but ssid list is empty, match all"
                )
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
                Logger.d(
                    LOG_TAG_PROXY,
                    "canAdd: only NOT_EQUAL items present and none matched, return true"
                )
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
            text.matches(Regex(regexPattern, RegexOption.IGNORE_CASE)) || text.contains(
                pattern,
                ignoreCase = true
            )
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

    // ===== SSID-based connection management for Country Configs =====

    /**
     * Update SSID based status for a country
     * @param cc Country code
     * @param ssidBased Whether SSID-based connection is enabled
     */
    suspend fun updateSsidBased(cc: String, ssidBased: Boolean) {
        try {
            countryConfigRepo.updateSsidBased(cc, ssidBased)
            
            // Update cache
            synchronized(winServersCache) {
                val config = winServersCache.find { it.cc == cc }
                if (config != null) {
                    winServersCache.remove(config)
                    winServersCache.add(config.copy(ssidBased = ssidBased, lastModified = System.currentTimeMillis()))
                }
            }
            
            Logger.i(LOG_TAG_PROXY, "$TAG; updateSsidBased: $cc = $ssidBased")
            
            // Trigger connection monitor to update SSID info if country is selected
            if (selectedCountries.contains(cc)) {
                if (ssidBased) {
                    VpnController.notifyConnectionMonitor(enforcePolicyChange = true)
                }
                // Refresh proxies to immediately pause/resume based on new SSID setting
                VpnController.refreshOrPauseOrResumeOrReAddProxies()
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error updating SSID based for $cc: ${e.message}", e)
        }
    }

    /**
     * Update SSID list for a country
     * @param cc Country code
     * @param ssids JSON string of SSID items
     */
    suspend fun updateSsids(cc: String, ssids: String) {
        try {
            countryConfigRepo.updateSsids(cc, ssids)
            
            // Update cache
            synchronized(winServersCache) {
                val config = winServersCache.find { it.cc == cc }
                if (config != null) {
                    winServersCache.remove(config)
                    winServersCache.add(config.copy(ssids = ssids, lastModified = System.currentTimeMillis()))
                }
            }
            
            Logger.i(LOG_TAG_PROXY, "$TAG; updateSsids: $cc, ssids length=${ssids.length}")
            
            // If country is selected and SSID based enabled, refresh proxies
            if (selectedCountries.contains(cc)) {
                val config = winServersCache.find { it.cc == cc }
                if (config?.ssidBased == true) {
                    VpnController.notifyConnectionMonitor(enforcePolicyChange = true)
                    VpnController.refreshOrPauseOrResumeOrReAddProxies()
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error updating SSIDs for $cc: ${e.message}", e)
        }
    }

    /**
     * Get all country configs with SSID enabled
     */
    suspend fun getSsidEnabledCountries(): List<CountryConfig> {
        return try {
            countryConfigRepo.getSsidEnabledConfigs()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error getting SSID enabled countries: ${e.message}", e)
            emptyList()
        }
    }

    /**
     * Get country config by country code
     */
    suspend fun getCountryConfig(cc: String): CountryConfig? {
        return try {
            synchronized(winServersCache) {
                winServersCache.find { it.cc == cc }
            } ?: countryConfigRepo.getConfig(cc)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error getting country config for $cc: ${e.message}", e)
            null
        }
    }

}
