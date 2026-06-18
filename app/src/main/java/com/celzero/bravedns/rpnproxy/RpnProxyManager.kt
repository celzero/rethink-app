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
import Logger.LOG_IAB
import Logger.LOG_TAG_PROXY
import android.content.Context
import android.text.format.DateUtils
import com.android.billingclient.api.BillingClient
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.SsidItem
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.database.CountryConfigRepository
import com.celzero.bravedns.database.RpnProxy
import com.celzero.bravedns.database.RpnProxyRepository
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.database.SubscriptionStatusRepository
import com.celzero.bravedns.iab.BillingBackendClient
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.rpnproxy.RpnProxyManager.DnsMode.Companion.setFromCsv
import com.celzero.bravedns.rpnproxy.RpnProxyManager.DnsMode.Companion.tunTypesFromSet
import com.celzero.bravedns.rpnproxy.RpnProxyManager.activateRpn
import com.celzero.bravedns.rpnproxy.RpnProxyManager.deactivateRpn
import com.celzero.bravedns.rpnproxy.RpnProxyManager.disableWinServer
import com.celzero.bravedns.rpnproxy.RpnProxyManager.enableWinServer
import com.celzero.bravedns.rpnproxy.RpnProxyManager.ensureAutoServerExists
import com.celzero.bravedns.rpnproxy.RpnProxyManager.fetchAndConstructWinLocations
import com.celzero.bravedns.rpnproxy.RpnProxyManager.load
import com.celzero.bravedns.rpnproxy.RpnProxyManager.registerProxy
import com.celzero.bravedns.rpnproxy.RpnProxyManager.serverRemovedEvent
import com.celzero.bravedns.rpnproxy.RpnProxyManager.stopProxy
import com.celzero.bravedns.rpnproxy.RpnProxyManager.syncWinServers
import com.celzero.bravedns.rpnproxy.RpnProxyManager.updateWinConfigState
import com.celzero.bravedns.rpnproxy.RpnProxyManager.updateWinProxy
import com.celzero.bravedns.service.EncryptedFileManager
import com.celzero.bravedns.service.EncryptionException
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.RPN_PROXY_FOLDER_NAME
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import com.celzero.firestack.backend.RpnEntitlement
import com.celzero.firestack.backend.RpnServers
import com.celzero.firestack.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

object RpnProxyManager : KoinComponent {
    private val applicationContext: Context by inject()

    private const val TAG = "RpnMgr"

    private val db: RpnProxyRepository by inject()
    private val countryConfigRepo: CountryConfigRepository by inject()
    private val persistentState by inject<PersistentState>()
    private val billingBackendClient by inject<BillingBackendClient>()
    private val subscriptionStatusRepository: SubscriptionStatusRepository by inject()

    private const val WIN_ID = 4
    private const val WIN_NAME = "WIN"
    private const val WIN_ENTITLEMENT_FILE_NAME = "win_response.json"
    private const val WIN_STATE_FILE_NAME = "win_state.json"
    const val MAX_WIN_SERVERS = 5
    const val AUTO_SERVER_ID   = "AUTO"
    const val AUTO_COUNTRY_CODE = "AUTO"

    // In-memory cache for WIN servers (CountryConfig as unified model).
    private val winServersCache = mutableListOf<CountryConfig>()
    private val winCacheMutex = Mutex()

    private val winRegistrationMutex = Mutex()

    /**
     * Emits a list of [CountryConfig] objects that were selected by the user but are
     * no longer present in the server list returned by the tunnel after an update.
     */
    private val _serverRemovedEvent = MutableSharedFlow<List<CountryConfig>>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val serverRemovedEvent: SharedFlow<List<CountryConfig>> = _serverRemovedEvent.asSharedFlow()

    data class ServerKeyMeta(
        val selectedAt: Long
    )

    // Tracks per-server-key metadata (selection time, last tunnel-start ts, cached client IPs).
    // Populated from CountryConfig.lastModified on startup; overwritten on every runtime
    // enable/disable; sinceTs / ip[46]Meta updated by RpnConfigDetailActivity after each poll.
    private val serverKeyMeta = ConcurrentHashMap<String, ServerKeyMeta>()

    private val subscriptionStateMachine: SubscriptionStateMachineV2 by inject()
    private val stateObserverJob = SupervisorJob()
    private val stateObserverScope = CoroutineScope(Dispatchers.IO + stateObserverJob)
    private const val CD_DEFAULT_DNS = "tls://p0.freedns.controld.com"
    private const val CD_PRIVACY_DNS = "tls://p2.freedns.controld.com"
    private const val CD_PARENTAL_FILTER_DNS = "tls://family.freedns.controld.com"
    private const val CD_SECURITY_FILTER_DNS = "tls://p1.freedns.controld.com"

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

    /**
     * Server-side DNS filter options. [id] is stable (persisted); do not reorder.
     *
     * The Go tun SetDNSConfig accepts a CSV of filter presets:
     *   "family", "security", "privacy", "default"
     * Use [tunTypesFromSet] to build the CSV for a multi-select combination and
     * [setFromCsv] to restore a persisted CSV back to a [Set] of modes.
     */
    enum class DnsMode(val id: Int, val url: String, val tunType: String) {
        // presets from go-tun, see below
        // SetDNSConfig sets the DNS filter preset configuration. v is a csv of filter presets:
        // "family", "security", "social", "privacy", "default".
        DEFAULT(0,  CD_DEFAULT_DNS, "default"),
        PRIVACY(1,  CD_PRIVACY_DNS, "privacy"),
        PARENTAL(2, CD_PARENTAL_FILTER_DNS, "family"),
        SECURITY(3, CD_SECURITY_FILTER_DNS, "security");

        companion object {
            fun fromId(id: Int): DnsMode  = entries.firstOrNull { it.id == id }  ?: DEFAULT
            fun fromUrl(url: String): DnsMode = entries.firstOrNull { it.url == url } ?: DEFAULT

            /**
             * Parses a comma-separated string of [DnsMode.tunType] values (e.g.`"privacy,family"`)
             * back into a [Set] of [DnsMode] entries. Unknown tokens are silently ignored.
             * Returns a set containing [DEFAULT] if [csv] is blank or yields no valid entries.
             */
            fun setFromCsv(csv: String): Set<DnsMode> {
                if (csv.isBlank()) return setOf(DEFAULT)
                val result = csv.split(',')
                    .mapNotNull { token -> entries.firstOrNull { it.tunType == token.trim() } }
                    .toSet()
                return result.ifEmpty { setOf(DEFAULT) }
            }

            /**
             * Converts a [Set] of [DnsMode] entries to a comma-separated string of their
             * [tunType] values suitable for [PersistentState.rpnDnsTunTypes] and for passing
             * to the Go tunnels SetDNSConfig.
             *
             * The entries are sorted by [id] so the string is deterministic.
             *
             * An empty [modes] set is treated as [DEFAULT].
             */
            fun tunTypesFromSet(modes: Set<DnsMode>): String {
                val effective = modes.ifEmpty { setOf(DEFAULT) }
                return effective.sortedBy { it.id }.joinToString(",") { it.tunType }
            }
        }
    }

    fun isRpnActive(): Boolean {
        val isEnabled = rpnState().isEnabled()
        val isActive = !rpnMode().isNone()
        return isEnabled && isActive
    }

    fun isRpnEnabled() = RpnState.fromId(persistentState.rpnState).isEnabled()

    fun rpnMode() = RpnMode.fromId(persistentState.rpnMode)

    fun rpnState() = RpnState.fromId(persistentState.rpnState)

    fun setRpnMode(mode: RpnMode) {
        if (rpnMode() == mode) {
            Logger.i(LOG_TAG_PROXY, "$TAG; rpn mode already set to ${mode.name}, skipping")
            return
        }
        persistentState.rpnMode = mode.id
        Logger.i(LOG_TAG_PROXY, "$TAG; rpn mode set to ${mode.name}")
    }

    fun setRpnState(state: RpnState) {
        if (rpnState() == state) {
            Logger.i(LOG_TAG_PROXY, "$TAG; rpn state already set to ${state.name}, skipping")
            return
        }
        persistentState.rpnState = state.id
        Logger.i(LOG_TAG_PROXY, "$TAG; rpn state set to ${state.name}")
    }

    fun deactivateRpn(reason: String = "manual deactivation") {
        Logger.i(LOG_TAG_PROXY, "$TAG; deactivating RPN, reason: $reason, current state: ${rpnState().name}")
        if (persistentState.rpnState == RpnState.DISABLED.id) {
            Logger.i(LOG_TAG_PROXY, "$TAG; rpn already deactivated, skipping")
            return
        }

        // no need to check the state, as user can manually deactivate RPN
        persistentState.rpnState = RpnState.DISABLED.id
        serverKeyMeta.clear()
    }

    /**
     * Stops proxying by setting the RPN mode to [RpnMode.NONE].
     *
     * This is a soft-stop: the RPN remains in [RpnState.ENABLED] so it can be
     * restarted without re-authentication. Use [deactivateRpn] for a full
     * tear-down when the subscription is revoked.
     */
    suspend fun stopProxy() {
        Logger.i(LOG_TAG_PROXY, "$TAG; stopProxy: current mode=${rpnMode()}, state=${rpnState()}")
        if (!isRpnActive()) {
            Logger.i(LOG_TAG_PROXY, "$TAG; stopProxy: already stopped, skipping")
            return
        }
        setRpnMode(RpnMode.NONE)
        deactivateRpn("user request")
        VpnController.unregisterWin()
        Logger.i(LOG_TAG_PROXY, "$TAG; stopProxy: proxy routing stopped (mode=NONE)")
    }

    /**
     * Restarts proxying after a [stopProxy] call.
     *
     * Returns `true` if the proxy was started, `false` if there is no valid
     * subscription (caller should surface an appropriate error).
     *
     * The mode is restored to [RpnMode.ANTI_CENSORSHIP] (the default active
     * routing mode).  This mirrors what [activateRpn] sets on first activation.
     */
    suspend fun startProxy() {
        Logger.i(LOG_TAG_PROXY, "$TAG; startProxy: current mode=${rpnMode()}, state=${rpnState()}")
        if (isRpnActive()) {
            Logger.i(LOG_TAG_PROXY, "$TAG; startProxy: proxy already running (mode=${rpnMode()})")
            VpnController.handleRpnProxies()
            return
        }
        setRpnMode(RpnMode.ANTI_CENSORSHIP)
        setRpnState(RpnState.ENABLED)
        VpnController.handleRpnProxies()
        Logger.i(LOG_TAG_PROXY, "$TAG; startProxy: proxy routing started (mode=ANTI_CENSORSHIP)")
    }


    /**
     * Activates the RPN proxy for the given [purchase].
     *
     * @param purchase    Purchase from Play Billing that carries the developer payload.
     * @param newPayload  Optional override payload (e.g. from a fresh server entitlement query).
     */
    suspend fun activateRpn(purchase: PurchaseDetail, newPayload: String? = null) {

        val alreadyEnabled = persistentState.rpnState == RpnState.ENABLED.id

        // Priority: explicit newPayload > purchase.payload > DB developerPayload
        val candidatePayload = when {
            !newPayload.isNullOrEmpty() -> {
                Logger.i(LOG_TAG_PROXY, "$TAG; activateRpn: using explicit newPayload")
                newPayload
            }
            purchase.payload.isNotEmpty() -> {
                Logger.i(LOG_TAG_PROXY, "$TAG; activateRpn: using purchase.payload")
                if (DEBUG) Logger.d(LOG_TAG_PROXY, "$TAG; activateRpn: purchase.payload content: ${purchase.payload}")
                purchase.payload
            }
            else -> {
                // Last resort: pull from DB developerPayload stored by the state machine
                Logger.w(LOG_TAG_PROXY, "$TAG; activateRpn: purchase.payload is empty, trying DB developerPayload")
                try {
                    subscriptionStatusRepository.getCurrentSubscription()?.developerPayload.orEmpty()
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; activateRpn: failed to read DB developerPayload: ${e.message}", e)
                    ""
                }
            }
        }

        // Even if rpnState is already ENABLED, the entitlement file or in-memory
        // winConfig may be absent (app restart, storage cleared, first login on new device).
        // storeWinEntitlement is idempotent; calling it again with the same data is safe.
        if (candidatePayload.isNotEmpty()) {
            try {
                storeWinEntitlement(candidatePayload)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; activateRpn: storeWinEntitlement threw: ${e.message}", e)
                // Non-fatal: continue activation; registerProxy will use getWinEntitlement as fallback
            }
        } else {
            Logger.w(LOG_TAG_PROXY, "$TAG; activateRpn: no payload available, entitlement file will NOT be updated")
        }

        if (alreadyEnabled) {
            // RPN is already enabled; entitlement refresh was the only needed action.
            Logger.i(LOG_TAG_PROXY, "$TAG; activateRpn: rpn already activated, entitlement refreshed, skipping state change")
            return
        }

        // Check if current state allows RPN activation
        if (!subscriptionStateMachine.hasValidSubscription()) {
            val currentState = subscriptionStateMachine.getCurrentState()
            Logger.w(LOG_TAG_PROXY, "$TAG; activateRpn: cannot activate RPN - no valid subscription, current state: ${currentState.name}")
            return
        }

        // Additional validation, ensure purchase has required fields
        if (purchase.productId.isEmpty()) {
            Logger.e(LOG_TAG_PROXY, "$TAG; activateRpn: cannot activate RPN - purchase has empty productId")
            return
        }

        try {
            persistentState.rpnState = RpnState.ENABLED.id
            Logger.i(
                LOG_TAG_PROXY,
                "$TAG; activateRpn: rpn activated, mode: ${rpnMode()}, product: ${purchase.productId}"
            )
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; activateRpn: error activating RPN: ${e.message}", e)
            // Rollback on error
            persistentState.rpnState = RpnState.DISABLED.id
            throw e
        }
    }

    suspend fun getSessionTokenFromPayload(payload: String): String {
        // "developerPayload":"{\"ws\":{\"cid\":\"aa95f04efcb19a54c7605a02e5dd0b435906b993d12bec031a60f3f1272f4f0e\",\"sessiontoken\":\"22695:4:1752256088:524537c17**c146ba3404af:023f958b6c194**fe6885d3e57322\",\"expiry\":\"2025-08-11T00:00:00.000Z\",\"status\":\"valid\"}}"
        try {
            val ws = extractWsObject(payload) ?: return ""
            val ent = VpnController.getEntitlementDetails(ws, billingBackendClient.getDeviceId())
            val sessionToken = ent?.token()
            Logger.i(LOG_TAG_PROXY, "$TAG; session token parsed from payload? ${sessionToken?.isNotEmpty()}")
            return sessionToken ?: ""
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error parsing session token from payload($payload): ${e.message}", e)
        }
        return ""
    }

    suspend fun getExpiryFromPayload(payload: String): Long? {
        if (payload.isEmpty()) return null
        try {
            val ws = extractWsObject(payload) ?: return null
            val ent = VpnController.getEntitlementDetails(ws, billingBackendClient.getDeviceId())
            val timestamp = ent?.expiry()
            Logger.i(LOG_TAG_PROXY, "$TAG; expiry parsed from payload: $timestamp")
            return timestamp
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; error parsing expiry from payload: ${e.message}")
        }
        return null
    }

    suspend fun getEntitlementDetails(): RpnEntitlement? {
        val entitlement = getWinEntitlement()
        if (entitlement == null) {
            Logger.d(LOG_TAG_PROXY, "$TAG; getEntitlementDetails: null entitlement, querying server for refresh")
            return null
        }

        return try {
            val entitlement = VpnController.getEntitlementDetails(getWinEntitlement(), billingBackendClient.getDeviceId())
            return entitlement
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; getEntitlementDetails: ${e.message}")
            null
        }
    }

    suspend fun getIsTestEntitlement(retryAttempt: Int = 0): Boolean {
        return try {
            val entitlement = VpnController.getEntitlementDetails(getWinEntitlement(), billingBackendClient.getDeviceId())
            // RpnEntitlement.test() returns Boolean: true = test, false = production
            if (entitlement == null) {
                Logger.d(LOG_TAG_PROXY, "$TAG; getIsTestEntitlement: null entitlement, querying server for refresh, attempt: $retryAttempt")
                return persistentState.appTestMode
            }
            entitlement.test()
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; getIsTestEntitlement: ${e.message}")
            persistentState.appTestMode
        }
    }


    suspend fun consumePurchaseIfTest() {
        val mname = "consumePurchaseInTest"
        val purchase = subscriptionStateMachine.getSubscriptionData()?.purchaseDetail
        if (purchase == null) {
            Logger.w(LOG_IAB, "$TAG $mname: no purchase to consume")
            return
        }
        Logger.i(LOG_IAB, "$TAG $mname: consuming purchase token=${purchase.purchaseToken.take(8)}")
        if (!getIsTestEntitlement()) {
            Logger.i(LOG_IAB, "$TAG; $mname: not test entitlement - skipping")
            return
        }

        callConsumeApi(purchase)
    }

    private suspend fun callConsumeApi(purchase: PurchaseDetail) {
        val mname = "callConsumeApi"
        if (purchase.accountId.isBlank()) {
            Logger.e(LOG_IAB, "$TAG; $mname: accountId blank for token=${purchase.purchaseToken.take(8)}, skipping")
            return
        }
        val sku = purchase.productId
        Logger.i(LOG_IAB, "$TAG; $mname: delegating to BillingServerRepository for " +
                "token=${purchase.purchaseToken.take(8)}, sku=$sku, accLen=${purchase.accountId.length}")
        // purchase.deviceId holds only the sentinel indicator, always fetch the real ID from SecureIdentityStore.
        val deviceId = billingBackendClient.getDeviceId()
        val success = billingBackendClient.consumePurchase(purchase.accountId, deviceId, sku, purchase.purchaseToken)
        if (success) {
            Logger.i(LOG_IAB, "$TAG; $mname: consume succeeded for token=${purchase.purchaseToken.take(8)}")
        } else {
            Logger.w(LOG_IAB, "$TAG; $mname: consume failed for token=${purchase.purchaseToken.take(8)} (will retry next cycle)")
        }
    }

    /**
     * Represents the outcome of a full RPN reset operation.
     */
    sealed class ResetResult {
        /** All steps completed successfully. */
        object Success : ResetResult()
        /** One of the required steps failed; [reason] is a human-readable description. */
        data class Failure(val reason: String) : ResetResult()
    }

    /**
     * Performs a full RPN reset:
     *  1. Unregisters the current WIN registration from the tunnel.
     *  2. Fetches a fresh entitlement from the server.
     *  3. Stores the new entitlement (replacing the old one).
     *  4. Re-registers WIN in the tunnel with the fresh entitlement.
     *  5. Force-fetches the server list and syncs it to DB/cache.
     *
     * Returns [ResetResult.Success] when all steps complete, or
     * [ResetResult.Failure] with a reason string if a fatal step fails.
     */
    suspend fun resetAndRefetchRpn(): ResetResult {
        Logger.i(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: starting full reset")

        try {
            VpnController.unregisterWin()
            Logger.i(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: unregistered from tunnel")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: unregister failed (non-fatal): ${e.message}")
        }

        val sub = try {
            subscriptionStatusRepository.getCurrentSubscription()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: failed to read subscription: ${e.message}", e)
            return ResetResult.Failure("Failed to read subscription details")
        }

        if (sub == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: no active subscription found")
            return ResetResult.Failure("No active subscription found")
        }

        val accountId = sub.accountId
        val purchaseToken = sub.purchaseToken
        if (accountId.isEmpty() || purchaseToken.isEmpty()) {
            Logger.w(
                LOG_TAG_PROXY,
                "$TAG; resetAndRefetchRpn: incomplete account info " +
                    "(accountEmpty=${accountId.isEmpty()}, tokenEmpty=${purchaseToken.isEmpty()})"
            )
            return ResetResult.Failure("Account information is incomplete")
        }

        val deviceId = billingBackendClient.getDeviceId(accountId)

        val fakePurchase = PurchaseDetail(
            productId = sub.productId,
            planId = sub.planId,
            productTitle = sub.productTitle,
            planTitle = sub.productTitle,
            state = sub.state,
            purchaseToken = purchaseToken,
            productType = if (sub.productId.contains("onetime", ignoreCase = true))
                BillingClient.ProductType.INAPP else BillingClient.ProductType.SUBS,
            purchaseTime = "",
            purchaseTimeMillis = sub.purchaseTime,
            isAutoRenewing = false,
            accountId = accountId,
            deviceId = if (deviceId.isNotBlank()) SubscriptionStatus.DEVICE_ID_INDICATOR else "",
            payload = sub.developerPayload,
            expiryTime = sub.billingExpiry,
            status = sub.status,
            windowDays = sub.windowDays,
            orderId = sub.orderId
        )

        val updatedPurchase = try {
            InAppBillingHandler.queryEntitlementFromServer(accountId, deviceId, fakePurchase)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: entitlement query failed: ${e.message}", e)
            return ResetResult.Failure("Failed to fetch entitlement from server")
        }

        if (updatedPurchase.payload.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: server returned empty payload")
            return ResetResult.Failure("Server returned an empty entitlement")
        }

        try {
            storeWinEntitlement(updatedPurchase.payload)
            Logger.i(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: new entitlement stored")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: storeWinEntitlement failed: ${e.message}", e)
            return ResetResult.Failure("Failed to store new entitlement")
        }

        // Update state machine with fresh payload (non-fatal)
        try {
            val subsData = subscriptionStateMachine.getSubscriptionData()
            if (subsData != null) {
                subsData.subscriptionStatus.developerPayload = updatedPurchase.payload
                val updatedSubsData = subsData.copy(purchaseDetail = updatedPurchase)
                subscriptionStateMachine.stateMachine.updateData(updatedSubsData)
                Logger.i(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: state machine payload updated")
            }
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: state machine update failed (non-fatal): ${e.message}")
        }

        val entitlementBytes = getWinEntitlement()
        val prevRegistrationBytes = getWinExistingData()
        val registrationBytes = try {
            VpnController.registerAndFetchWinConfig(entitlementBytes, billingBackendClient.getDeviceId())
                ?: VpnController.registerAndFetchWinConfig(prevRegistrationBytes, billingBackendClient.getDeviceId())
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: tunnel registration failed: ${e.message}", e)
            return ResetResult.Failure("Failed to register with tunnel")
        }
        updateWinConfigState(registrationBytes)
        Logger.i(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: re-registered with tunnel")

        // Clear all user-specific server state (selections, favourites, selection counts)
        // before syncing so the rebuilt cache starts from a clean slate.
        try {
            countryConfigRepo.resetUserSelections()
            // Mirror the DB reset into the in-memory cache immediately so nothing races
            // between the DB write and the cache rebuild inside syncWinServers().
            winCacheMutex.withLock {
                winServersCache.forEach { server ->
                    if (!server.id.equals(AUTO_SERVER_ID, true)) {
                        server.isEnabled = false
                        server.isFavourite = false
                    }
                }
            }
            Logger.i(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: user selections cleared from DB and cache")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: resetUserSelections failed (non-fatal): ${e.message}")
        }

        // Restore configuration persistent-state values to their defaults.
        persistentState.rpnDnsTunTypes = DnsMode.DEFAULT.tunType
        persistentState.rpnConfigHandlingManual = false
        persistentState.rpnAlwaysChangeIdentity = false
        persistentState.rpnPort = 0
        Logger.i(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: persistent-state config restored to defaults")

        val (servers, _) = fetchAndConstructWinLocations()

        if (servers.isNotEmpty()) {
            syncWinServers(servers)
            Logger.i(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: synced ${servers.size} servers")
        } else {
            Logger.w(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: no servers returned after reset")
        }

        Logger.i(LOG_TAG_PROXY, "$TAG; resetAndRefetchRpn: complete, servers=${servers.size}")
        return ResetResult.Success
    }

    /**
     * Update both billingExpiry and accountExpiry for the current active subscription.
     *
     * Use this after a successful acknowledgement from the server that returns updated
     * expiry values for the billing period and the account-access period.
     *
     * @param newBillingExpiry  New billing-period expiry epoch-millis (0 = no change).
     * @param newAccountExpiry  New account-access expiry epoch-millis (0 = no change).
     * @return `true` if all requested updates succeeded.
     */
    suspend fun updateBillingAndAccountExpiry(newBillingExpiry: Long, newAccountExpiry: Long): Boolean {
        if (newBillingExpiry <= 0L && newAccountExpiry <= 0L) {
            Logger.w(LOG_TAG_PROXY, "$TAG; updateBillingAndAccountExpiry: both values are 0, skipping")
            return false
        }
        return try {
            val current = subscriptionStatusRepository.getCurrentSubscription()
            if (current == null) {
                Logger.w(LOG_TAG_PROXY, "$TAG; updateBillingAndAccountExpiry: no active subscription found")
                return false
            }
            val now = System.currentTimeMillis()
            var ok = true
            if (newBillingExpiry > 0L) {
                val r = subscriptionStatusRepository.updateBillingExpiry(current.id, newBillingExpiry, now)
                if (r <= 0) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; updateBillingAndAccountExpiry: billingExpiry update failed for id=${current.id}")
                    ok = false
                }
            }
            if (newAccountExpiry > 0L) {
                val r = subscriptionStatusRepository.updateAccountExpiry(current.id, newAccountExpiry, now)
                if (r <= 0) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; updateBillingAndAccountExpiry: accountExpiry update failed for id=${current.id}")
                    ok = false
                }
            }
            if (ok) Logger.i(LOG_TAG_PROXY, "$TAG; updateBillingAndAccountExpiry: billing=$newBillingExpiry, account=$newAccountExpiry for id=${current.id}")
            ok
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; updateBillingAndAccountExpiry: error: ${e.message}", e)
            false
        }
    }

    /**
     * Extracts the "ws" JSON object from a payload string.
     *
     * The payload can arrive in two shapes:
     *
     * Shape 1 – full purchase originalJson (from Play Billing's Purchase.getOriginalJson()).
     *   The "ws" object is nested inside the "developerPayload" string value which is itself
     *   a JSON string that must be parsed a second time:
     *   { ..., "developerPayload": "{\"ws\":{...}}", ... }
     *
     * Shape 2 – already-extracted developerPayload string stored in the DB.
     *   The "ws" object is top-level inside this string:
     *   { "ws": { "cid": "...", "sessiontoken": "...", "expiry": "...", ... } }
     *
     * Both shapes are handled: try top-level "ws" first; if absent, look inside
     * "developerPayload" for a nested "ws".
     */
    fun extractWsObject(payload: String): ByteArray? {
        if (payload.isEmpty()) return null
        return try {
            val json = JSONObject(payload)

            // Shape 2: "ws" is top-level (already-extracted developerPayload).
            val ws = json.optJSONObject("ws")
            if (ws != null) return ws.toString().toByteArray()

            // Shape 1: "ws" is embedded inside the "developerPayload" string value.
            val devPayloadStr = json.optString("developerPayload", "")
            if (devPayloadStr.isNotEmpty()) {
                val devPayloadJson = JSONObject(devPayloadStr)
                val wsFromDev = devPayloadJson.optJSONObject("ws")
                if (wsFromDev != null) return wsFromDev.toString().toByteArray()
            }

            Logger.w(LOG_TAG_PROXY, "$TAG; extractWsObject: ws not found in payload")
            null
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; extractWsObject: failed to parse payload ($payload): ${e.message}")
            null
        }
    }

    suspend fun storeWinEntitlement(payload: String) {
        // Store the win entitlement in a file and the path in serverResponsePath
        if (payload.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; storeWinEntitlement: payload is empty, skipping")
            return
        }

        try {
            // Use extractWsObject to handle both payload shapes:
            // Shape 1: full purchase originalJson  → ws nested inside developerPayload
            // Shape 2: already-extracted payload   → ws is top-level
            val ws = extractWsObject(payload)
            if (ws == null) {
                Logger.e(LOG_TAG_PROXY, "$TAG; storeWinEntitlement: ws object not found in payload")
                if (DEBUG) Logger.d(LOG_TAG_PROXY, "$TAG; storeWinEntitlement: payload content: $payload")
                return
            }

            val fileName = getJsonResponseFileName(WIN_ID)
            val folder = applicationContext.getExternalFilesDir(RPN_PROXY_FOLDER_NAME)
            if (folder == null) {
                Logger.e(LOG_TAG_PROXY, "$TAG; storeWinEntitlement: failed to get external files dir")
                return
            }

            if (!folder.exists()) {
                val created = try {
                    folder.mkdirs()
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; storeWinEntitlement: exception creating folder: ${e.message}", e)
                    false
                }
                if (!created) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; storeWinEntitlement: failed to create folder: ${folder.absolutePath}")
                    return
                }
            }

            val file = File(folder, fileName)
            val res = try {
                EncryptedFileManager.write(applicationContext, ws, file)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; storeWinEntitlement: exception writing file: ${e.message}", e)
                false
            }

            if (!res) {
                Logger.e(LOG_TAG_PROXY, "$TAG; storeWinEntitlement: failed to write file: ${file.absolutePath}")
                return
            }

            // update the winConfig with the file path
            val winProxy = try {
                db.getProxyById(WIN_ID)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; storeWinEntitlement: failed to get win proxy from DB: ${e.message}", e)
                null
            }

            val ll = if (winProxy != null) {
                winProxy.serverResPath = file.absolutePath
                try {
                    db.update(winProxy)
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; storeWinEntitlement: failed to update win proxy: ${e.message}", e)
                    -1
                }
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
                try {
                    db.insert(newWinProxy).toInt()
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; storeWinEntitlement: failed to insert win proxy: ${e.message}", e)
                    -1
                }
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

    fun getRpnProductId(): String {
        return subscriptionStateMachine.getSubscriptionData()?.purchaseDetail?.productId.orEmpty()
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
        val rp = try {
            db.getAllProxies()
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; init load, failed to get proxies from DB: ${e.message}", e)
            emptyList()
        }
        Logger.i(LOG_TAG_PROXY, "$TAG; init load, db size: ${rp.size}")
        rp.forEach {
            try {
                if (it.configPath.isEmpty() && it.id != WIN_ID) {
                    Logger.w(LOG_TAG_PROXY, "$TAG; load, config path is empty for ${it.name}")
                    return@forEach
                }

                val cfgFile = File(it.configPath)
                if (!cfgFile.exists() && it.id != WIN_ID) { // win proxy is handled differently
                    Logger.w(LOG_TAG_PROXY, "$TAG; load, file not found: ${it.configPath} for ${it.name}")
                    return@forEach
                }
                when (it.id) {
                    WIN_ID -> {
                        // read the win entitlement file
                        if (it.serverResPath.isEmpty()) {
                            Logger.w(LOG_TAG_PROXY, "$TAG; load, win serverResPath is empty")
                            return@forEach
                        }
                        val entitlementFile = File(it.serverResPath)
                        val entitlement = if (entitlementFile.exists()) {
                            try {
                                EncryptedFileManager.readByteArray(applicationContext, entitlementFile)
                            } catch (e: Exception) {
                                Logger.e(LOG_TAG_PROXY, "$TAG; load, error reading win entitlement file: ${e.message}", e)
                                byteArrayOf()
                            }
                        } else {
                            Logger.w(LOG_TAG_PROXY, "$TAG; load, win entitlement file not found: ${it.serverResPath}")
                            byteArrayOf()
                        }

                        Logger.i(LOG_TAG_PROXY, "$TAG; load, win entitlement loaded, size: ${entitlement.size} bytes")

                        val state = if (cfgFile.exists()) {
                            try {
                                EncryptedFileManager.readByteArray(applicationContext, cfgFile)
                            } catch (e: Exception) {
                                Logger.e(LOG_TAG_PROXY, "$TAG; load, error reading win state file (${cfgFile.absolutePath}): ${e.message}", e)
                                byteArrayOf()
                            }
                        } else {
                            Logger.d(LOG_TAG_PROXY, "$TAG; load, win state file not found: ${it.configPath}")
                            byteArrayOf()
                        }

                        Logger.i(LOG_TAG_PROXY, "$TAG; load, win state loaded, size: ${state.size} bytes")
                    }
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_PROXY, "$TAG; err loading rpn proxy: ${it.name}, ${e.message}")
            }
        }

        // Populate WIN servers cache from DB at startup
        try {
            // Ensure AUTO server exists in database
            ensureAutoServerExists()

            val dbServers = countryConfigRepo.getAllConfigs()
            winCacheMutex.withLock {
                winServersCache.clear()
                winServersCache.addAll(dbServers)
            }
            Logger.i(LOG_TAG_PROXY, "$TAG; load: cached ${dbServers.size}, WIN servers from DB (AUTO server ensured)")
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; load: failed to cache WIN servers: ${e.message}")
        }

        return rp.size
    }

    // This function is called from RpnProxiesUpdateWorker
    suspend fun registerProxy(type: RpnType): Boolean {
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
                    // This handles the case where:
                    //   (a) the entitlement file was never written (race between activateRpn
                    //       and registerProxy on first launch), OR
                    //   (b) external storage was cleared / the file was deleted.
                    // We pull the developerPayload from the DB subscription row (written by
                    // the state machine during handlePaymentSuccessful) and re-store it,
                    // which populates winConfig so the tunnel can be registered.
                    Logger.w(LOG_TAG_PROXY, "$TAG; registerProxy: both state and entitlement files absent, attempting DB payload recovery")
                    val dbPayload = try {
                        subscriptionStatusRepository.getCurrentSubscription()?.developerPayload.orEmpty()
                    } catch (e: Exception) {
                        Logger.e(LOG_TAG_PROXY, "$TAG; registerProxy: failed to read DB developerPayload: ${e.message}", e)
                        ""
                    }
                    if (dbPayload.isNotEmpty()) {
                        Logger.i(LOG_TAG_PROXY, "$TAG; registerProxy: found DB payload (len=${dbPayload.length}), re-storing entitlement")
                        try {
                            storeWinEntitlement(dbPayload)
                            bytes = getWinEntitlement()
                        } catch (e: Exception) {
                            Logger.e(LOG_TAG_PROXY, "$TAG; registerProxy: error re-storing entitlement from DB: ${e.message}", e)
                        }
                    }

                    if (bytes == null || bytes.isEmpty()) {
                        // DB also had no usable payload.  Try a live server query as last resort.
                        Logger.w(LOG_TAG_PROXY, "$TAG; registerProxy: DB payload also empty, querying server entitlement")
                        val sub = try { subscriptionStatusRepository.getCurrentSubscription() } catch (e: Exception) { null }
                        val accountId = sub?.accountId.orEmpty()
                        // sub.deviceId holds only the sentinel indicator "pip/identity.json"
                        val deviceId = billingBackendClient.getDeviceId(accountId)
                        val purchaseToken = sub?.purchaseToken.orEmpty()
                        if (accountId.isNotEmpty() && purchaseToken.isNotEmpty()) {
                            try {
                                val fakePurchase = PurchaseDetail(
                                    productId = sub?.productId.orEmpty(),
                                    planId = sub?.planId.orEmpty(),
                                    productTitle = sub?.productTitle.orEmpty(),
                                    planTitle = sub?.productTitle.orEmpty(),
                                    state = sub?.state ?: 0,
                                    purchaseToken = purchaseToken,
                                    productType = if ((sub?.productId.orEmpty()).contains("onetime", ignoreCase = true)) BillingClient.ProductType.INAPP else BillingClient.ProductType.SUBS,
                                    purchaseTime = "",
                                    purchaseTimeMillis = sub?.purchaseTime ?: 0L,
                                    isAutoRenewing = false,
                                    accountId = accountId,
                                    // Store only the sentinel
                                    // Callers that need the actual ID use billingBackendClient.getDeviceId().
                                    deviceId = if (deviceId.isNotBlank()) SubscriptionStatus.DEVICE_ID_INDICATOR else "",
                                    payload = "",
                                    expiryTime = sub?.billingExpiry ?: 0L,
                                    status = sub?.status ?: 0,
                                    windowDays = sub?.windowDays ?: 0,
                                    orderId = sub?.orderId.orEmpty()
                                )
                                val updated = InAppBillingHandler.queryEntitlementFromServer(accountId, deviceId, fakePurchase)
                                if (updated.payload.isNotEmpty()) {
                                    Logger.i(LOG_TAG_PROXY, "$TAG; registerProxy: server query succeeded, storing entitlement")
                                    storeWinEntitlement(updated.payload)
                                    bytes = getWinEntitlement()
                                }
                            } catch (e: Exception) {
                                Logger.e(LOG_TAG_PROXY, "$TAG; registerProxy: server entitlement query failed: ${e.message}", e)
                            }
                        }
                    }

                    if (bytes == null || bytes.isEmpty()) {
                        Logger.e(LOG_TAG_PROXY, "$TAG; registerProxy: win entitlement unavailable after all recovery attempts, cannot register")
                        return false
                    }
                }

                var wasAlreadyRegisteredByConcurrent = false
                val currBytes: ByteArray? = winRegistrationMutex.withLock {
                    if (VpnController.isWinRegistered()) {
                        Logger.i(LOG_TAG_PROXY, "$TAG; registerProxy: WIN already registered (concurrent-safe check), skipping tunnel call")
                        wasAlreadyRegisteredByConcurrent = true
                        null // early-exit sentinel; not treated as failure because flag is set
                    } else {
                        var regBytes = VpnController.registerAndFetchWinConfig(bytes, billingBackendClient.getDeviceId())
                        if (regBytes == null) {
                            // try registering with prev stored bytes
                            val prevRegistrationBytes = getWinExistingData()
                            Logger.w(LOG_TAG_PROXY, "$TAG; win registration failed with existing bytes, trying with prev bytes")
                            regBytes = VpnController.registerAndFetchWinConfig(prevRegistrationBytes, billingBackendClient.getDeviceId())
                        }
                        regBytes
                    }
                }
                if (wasAlreadyRegisteredByConcurrent) {
                    // A concurrent coroutine finished registration first; this call is a no-op.
                    Logger.i(LOG_TAG_PROXY, "$TAG; registerProxy: WIN registered by concurrent call, returning true")
                    return true
                }
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
        for (i in 1..5) {
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
            delay(1000L.milliseconds)
        }
    }

    /**
     * Get all WIN servers.
     * Prefers in-memory cache; falls back to DB; if empty (or only the synthetic AUTO entry
     * exists), fetches from the tunnel API, syncs DB, and updates cache.
     *
     * **Important:** AUTO is a local synthetic entry created by [ensureAutoServerExists].
     */
    suspend fun getWinServers(): List<CountryConfig> {
        // Return cached list only if it contains real (non-AUTO) servers.
        winCacheMutex.withLock {
            val realCached = winServersCache.filter { !it.id.equals(AUTO_SERVER_ID, true) }
            if (realCached.isNotEmpty()) {
                Logger.v(LOG_TAG_PROXY, "$TAG; returning cached win servers, size: ${winServersCache.size} (real=${realCached.size})")
                return winServersCache.toList()
            }
        }

        // Try DB, same rule: only use it when real (non-AUTO) rows exist.
        val dbServers = countryConfigRepo.getAllConfigs()
        val realDbServers = dbServers.filter { !it.id.equals(AUTO_SERVER_ID, true) }
        if (realDbServers.isNotEmpty()) {
            winCacheMutex.withLock {
                winServersCache.clear()
                winServersCache.addAll(dbServers)
            }
            Logger.v(LOG_TAG_PROXY, "$TAG; loaded ${dbServers.size} win servers from DB into cache (real=${realDbServers.size})")
            return dbServers
        }

        // Cache and DB contain only AUTO (or are completely empty) -  fetch real data from tunnel.
        Logger.w(LOG_TAG_PROXY, "$TAG; no real servers in cache/DB (only AUTO or empty), fetching win servers from tun")
        val (apiServers, removedSelectedIds) = fetchAndConstructWinLocations()
        if (apiServers.isNotEmpty()) {
            syncWinServers(apiServers)
            if (removedSelectedIds.isNotEmpty()) {
                Logger.w(LOG_TAG_PROXY, "$TAG; getWinServers: ${removedSelectedIds.size} selected servers were removed")
            }
        } else {
            Logger.w(LOG_TAG_PROXY, "$TAG; getWinServers: tunnel returned no servers (win props null/empty)")
        }
        // Return whatever is now in the cache (may still be AUTO-only if tunnel failed).
        // The caller (ServerSelectionFragment) will detect the absence of real servers
        // and show an appropriate error to the user.
        return winCacheMutex.withLock { winServersCache.toList() }
    }

    suspend fun getWinEntitlement(): ByteArray? {
        val proxy = db.getProxyById(WIN_ID) ?: return null
        val file = File(proxy.serverResPath)
        if (!file.exists()) return null
        return try {
            val bytes = EncryptedFileManager.readByteArray(applicationContext, file)
            if (bytes.isNotEmpty()) {
                if (DEBUG) Logger.d(LOG_TAG_PROXY, "$TAG; getWinEntitlement: read entitlement bytes from file, bytes: $bytes, len: ${bytes.size}")
                bytes
            } else null
        } catch (e: EncryptionException) {
            // File is corrupted, encrypted with an invalidated key, or unreadable.
            // Return null so the caller falls back to fetching fresh entitlement from the API.
            Logger.w(LOG_TAG_PROXY, "$TAG; getWinEntitlement: encrypted file unreadable (${e::class.simpleName}), returning null", e)
            null
        }
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

    fun getCurrentSubscription(): SubscriptionStateMachineV2.SubscriptionData? {
        return subscriptionStateMachine.getSubscriptionData()
    }

    /**
     * Returns the best available [PurchaseDetail] for the current subscription.
     * Returns `null` only when there is no subscription data at all.
     */
    fun getEffectivePurchaseDetail(): PurchaseDetail? {
        val data = subscriptionStateMachine.getSubscriptionData() ?: return null
        return data.purchaseDetail
            ?: data.subscriptionStatus
                .takeIf { it.purchaseToken.isNotEmpty() }
                ?.let {
                    Logger.i(LOG_TAG_PROXY, "$TAG; getEffectivePurchaseDetail: reconstructing from subscriptionStatus (token=${it.purchaseToken.take(8)})")
                    subscriptionStateMachine.createPurchaseDetailFromSubscription(it)
                }
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

    /**
     * Called when a purchase revocation error from the server includes a [linkedToken]
     * (the Google Play purchase token of an older, superseded purchase).
     *
     * This method:
     * 1. Looks up [linkedToken] in the local DB.
     * 2. Issues a `/g/ack` query for [linkedToken].
     * 3. If the server confirms the linked purchase is valid, reactivates it by driving
     *    [paymentSuccessful] in the state machine and re-enabling RPN access.
     *
     * The caller's original purchase remains unchanged regardless of the outcome; this
     * operation only acts on the linked purchase row.
     *
     * @return `true` if the linked purchase was found and successfully reactivated.
     */
    suspend fun tryReactivateLinkedPurchase(
        accountId: String,
        deviceId: String,
        linkedToken: String,
    ): Boolean {
        val mname = "tryReactivateLinkedPurchase"
        if (linkedToken.isBlank() || accountId.isBlank()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; $mname: blank accountId or linkedToken, skipping")
            return false
        }

        // 1. Look up the linked purchase token in the local DB.
        val linkedSub = try {
            subscriptionStatusRepository.getByPurchaseToken(linkedToken)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; $mname: DB lookup failed for linkedToken=${linkedToken.take(8)}: ${e.message}", e)
            return false
        }

        if (linkedSub == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; $mname: linkedToken=${linkedToken.take(8)} not found in DB, cannot reactivate")
            return false
        }

        // 2. Build a PurchaseDetail from the DB row and query /g/ack for the linked token.
        val linkedPurchaseDetail = subscriptionStateMachine.createPurchaseDetailFromSubscription(linkedSub)
        val result = try {
            billingBackendClient.queryEntitlement(accountId, deviceId, linkedPurchaseDetail, linkedToken)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; $mname: entitlement query failed for linkedToken=${linkedToken.take(8)}: ${e.message}", e)
            return false
        }

        // 3. If the server confirms the linked purchase, reactivate it.
        return when (result) {
            is com.celzero.bravedns.iab.QueryEntitlementResult.Success -> {
                val updatedPurchase = result.purchase
                Logger.i(LOG_TAG_PROXY, "$TAG; $mname: linkedToken=${linkedToken.take(8)} confirmed valid by server; reactivating")
                try {
                    subscriptionStateMachine.paymentSuccessful(updatedPurchase)
                    activateRpn(updatedPurchase)
                    Logger.i(LOG_TAG_PROXY, "$TAG; $mname: reactivation complete for linkedToken=${linkedToken.take(8)}, product=${updatedPurchase.productId}")
                    true
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; $mname: reactivation state update failed for linkedToken=${linkedToken.take(8)}: ${e.message}", e)
                    false
                }
            }
            is com.celzero.bravedns.iab.QueryEntitlementResult.Failure -> {
                Logger.w(LOG_TAG_PROXY, "$TAG; $mname: linkedToken=${linkedToken.take(8)} also invalid per server (no linkedPurchaseId in response)")
                false
            }
            is com.celzero.bravedns.iab.QueryEntitlementResult.Transient -> {
                Logger.w(LOG_TAG_PROXY, "$TAG; $mname: transient failure querying linkedToken=${linkedToken.take(8)}, not reactivating (fail-safe)")
                false
            }
            is com.celzero.bravedns.iab.QueryEntitlementResult.Unauthorized -> {
                Logger.e(LOG_TAG_PROXY, "$TAG; $mname: 401 unauthorized for linkedToken=${linkedToken.take(8)}")
                false
            }
            is com.celzero.bravedns.iab.QueryEntitlementResult.Conflict -> {
                Logger.w(LOG_TAG_PROXY, "$TAG; $mname: 409 conflict for linkedToken=${linkedToken.take(8)}")
                false
            }
            else -> {
                Logger.w(LOG_TAG_PROXY, "$TAG; $mname: unhandled result type for linkedToken=${linkedToken.take(8)}")
                false
            }
        }
    }

    /**
     * Called from [SubscriptionStateMachineV2.handlePaymentSuccessful] after the DB row is
     * written and the state machine has been updated.
     *
     * Responsibilities:
     *  1. Ensure a valid developer payload (entitlement token) is stored on disk so that
     *     [registerProxy] can find it even on subsequent app launches.
     *  2. If the purchase payload is absent or stale, query a fresh one from the server.
     *  3. Always call [activateRpn] to persist the entitlement file and set rpnState.
     */
    suspend fun processRpnPurchase(purchase: PurchaseDetail?, existingSubs: SubscriptionStatus): Boolean {
        if (purchase == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; processRpnPurchase: no purchase to process")
            try { subscriptionStateMachine.subscriptionExpired() } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; processRpnPurchase: error notifying state machine: ${e.message}", e)
            }
            return false
        }

        if (purchase.productId.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; processRpnPurchase: productId empty")
            try { subscriptionStateMachine.purchaseFailed("Empty product ID", null) } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; processRpnPurchase: error notifying state machine: ${e.message}", e)
            }
            return false
        }

        val accExpiry     = existingSubs.accountExpiry
        val billingExpiry = existingSubs.billingExpiry

        Logger.d(LOG_TAG_PROXY, "$TAG; processRpnPurchase: billingExpiry=$billingExpiry, accExpiry=$accExpiry, " +
                "payload?=${purchase.payload.isNotEmpty()}, dbPayload?=${existingSubs.developerPayload.isNotEmpty()}")

        // A valid payload must parse to a JSON object containing a non-empty "sessiontoken"
        // inside the "ws" block.  Empty / unparseable payloads must trigger a server query.
        val existingPayloadValid = isPayloadUsable(purchase.payload)
        val dbPayloadValid       = isPayloadUsable(existingSubs.developerPayload)

        val tenDaysMs = 10L * 24 * 60 * 60 * 1000   // 10 days in ms

        // We need a fresh server entitlement when:
        //   (a) purchase payload is not usable (empty or no sessiontoken), AND
        //   (b) DB developerPayload is also not usable, OR accountExpiry hasn't been confirmed
        //       from the server yet (0 = never queried, or lags > 10 days behind billingExpiry).
        val needsFreshEntitlement = !existingPayloadValid && !dbPayloadValid
            || (accExpiry == 0L && billingExpiry > 0L)
            || (billingExpiry > 0L && billingExpiry != Long.MAX_VALUE && accExpiry < billingExpiry - tenDaysMs)

        var effectivePurchase = purchase

        if (needsFreshEntitlement) {
            Logger.i(LOG_TAG_PROXY, "$TAG; processRpnPurchase: needs fresh entitlement " +
                    "(existingPayloadValid=$existingPayloadValid, dbPayloadValid=$dbPayloadValid, " +
                    "accExpiry=$accExpiry, billingExpiry=$billingExpiry)")

            val accountId = purchase.accountId.ifEmpty { existingSubs.accountId }
            // purchase.deviceId holds only the sentinel indicator (or is empty)
            // Always resolve the actual device ID from SecureIdentityStore via billingBackendClient.
            val deviceId = billingBackendClient.getDeviceId()
            if (accountId.isEmpty()) {
                Logger.w(LOG_TAG_PROXY, "$TAG; processRpnPurchase: accountId empty, cannot query server, using best available payload")
                // Fall through to activateRpn with whatever we have (may also check DB below)
            } else {
                try {
                    val updatedPurchase = InAppBillingHandler.queryEntitlementFromServer(accountId, deviceId, purchase)
                    val serverPayloadValid = isPayloadUsable(updatedPurchase.payload)

                    if (serverPayloadValid) {
                        Logger.i(LOG_TAG_PROXY, "$TAG; processRpnPurchase: server entitlement received for ${updatedPurchase.productId}")
                        effectivePurchase = updatedPurchase

                        // Persist the server-returned expiry
                        val serverExpiry = updatedPurchase.expiryTime
                        if (serverExpiry > 0L && serverExpiry != Long.MAX_VALUE && serverExpiry != accExpiry) {
                            Logger.i(LOG_TAG_PROXY, "$TAG; processRpnPurchase: persisting server expiry=$serverExpiry (was accExpiry=$accExpiry)")
                            updateBillingAndAccountExpiry(
                                newBillingExpiry = serverExpiry,
                                newAccountExpiry = serverExpiry
                            )
                        }

                        // Update state machine and DB with fresh payload
                        val subsData = subscriptionStateMachine.getSubscriptionData()
                        if (subsData != null) {
                            subsData.subscriptionStatus.developerPayload = updatedPurchase.payload
                            val updatedSubsData = subsData.copy(purchaseDetail = updatedPurchase)
                            subscriptionStateMachine.stateMachine.updateData(updatedSubsData)
                            subscriptionStatusRepository.updateDeveloperPayload(
                                subsData.subscriptionStatus.id,
                                updatedPurchase.payload,
                                System.currentTimeMillis()
                            )
                        } else {
                            Logger.w(LOG_TAG_PROXY, "$TAG; processRpnPurchase: subscription data is null, cannot update payload in state machine")
                        }
                    } else {
                        Logger.w(LOG_TAG_PROXY, "$TAG; processRpnPurchase: server entitlement returned but payload unusable, using DB payload as fallback")
                        // Construct a purchase with the DB payload so activateRpn has something to store
                        if (dbPayloadValid) {
                            effectivePurchase = purchase.copy(payload = existingSubs.developerPayload)
                        }
                    }
                } catch (e: Exception) {
                    Logger.w(LOG_TAG_PROXY, "$TAG; processRpnPurchase: server entitlement query failed: ${e.message}")
                    // Fall through, use DB payload if available
                    if (dbPayloadValid && !existingPayloadValid) {
                        Logger.i(LOG_TAG_PROXY, "$TAG; processRpnPurchase: falling back to DB developerPayload")
                        effectivePurchase = purchase.copy(payload = existingSubs.developerPayload)
                    }
                }
            }
        } else if (!existingPayloadValid && dbPayloadValid) {
            // purchase.payload is empty but DB has a valid payload from a previous session
            Logger.i(LOG_TAG_PROXY, "$TAG; processRpnPurchase: purchase.payload empty, using DB developerPayload")
            effectivePurchase = purchase.copy(payload = existingSubs.developerPayload)
        }

        // This is the single point that writes the entitlement file and sets rpnState.
        // Calling it even when already-enabled ensures the file is present after app restart.
        activateRpn(effectivePurchase)

        val storedOk = effectivePurchase.payload.isNotEmpty()
        Logger.i(LOG_TAG_PROXY, "$TAG; processRpnPurchase: complete, payloadStored=$storedOk, product=${purchase.productId}")
        return storedOk
    }

    /**
     * Returns `true` if [payload] contains a valid `ws.sessiontoken` that can be passed to
     * the tunnel for WIN registration.
     *
     * An empty string, a payload that fails JSON parsing, or one that lacks a non-empty
     * `sessiontoken` inside the `ws` block is considered unusable, the caller must obtain
     * a fresh entitlement from the server before proceeding.
     */
    private suspend fun isPayloadUsable(payload: String): Boolean {
        if (payload.isEmpty()) return false
        return try {
            val ws = extractWsObject(payload) ?: return false
            val ent = VpnController.getEntitlementDetails(ws, billingBackendClient.getDeviceId())
            Logger.i(LOG_TAG_PROXY, "$TAG; isPayloadUsable: extracted entitlement from payload, checking session token usability")
            if (DEBUG) {
                Logger.d(LOG_TAG_PROXY, "$TAG; isPayloadUsable: payload ws block: $ws")
                Logger.d(LOG_TAG_PROXY, "$TAG; isPayloadUsable: entitlement details: ${ent?.expiry()}, ${ent?.token()}, ${ent?.status()}, cidLen=${ent?.cid()?.length}, didLen=${ent?.did()?.length}, ${ent?.test()}, ${ent?.json()}")
            }
            val sessionToken = ent?.token() ?: ""
            val isUsable = sessionToken.isNotEmpty()
            if (!isUsable) Logger.d(LOG_TAG_PROXY, "$TAG; isPayloadUsable: ws found but sessiontoken is empty")
            isUsable
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; isPayloadUsable: failed to parse payload: ${e.message}")
            false
        }
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
                Logger.i(LOG_TAG_PROXY, "$TAG; subscription activated, ensuring RPN is enabled and entitlement refreshed")
                val subs = subscriptionStateMachine.getSubscriptionData()
                // Use getEffectivePurchaseDetail() which tries in-memory purchaseDetail first,
                // then falls back to reconstructing from subscriptionStatus (DB-restored cold-start).
                val purchaseDetail = getEffectivePurchaseDetail()
                if (purchaseDetail == null) {
                    Logger.w(LOG_TAG_PROXY, "$TAG; handleStateChange.Active: no purchase detail and no DB subscription, cannot ensure entitlement")
                    return
                }
                if (subs?.purchaseDetail == null) {
                    Logger.i(LOG_TAG_PROXY, "$TAG; handleStateChange.Active: purchaseDetail reconstructed from subscriptionStatus (token=${purchaseDetail.purchaseToken.take(8)})")
                }
                // Always call activateRpn here
                // Even if rpnState is already ENABLED, the entitlement file may be absent
                // after an app restart; activateRpn will re-store it from the DB payload.
                try {
                    activateRpn(purchaseDetail)
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; handleStateChange.Active: error activating RPN: ${e.message}", e)
                }
            }
            is SubscriptionStateMachineV2.SubscriptionState.Cancelled -> {
                Logger.i(LOG_TAG_PROXY, "$TAG; subscription cancelled, checking if should disable RPN")
                try {
                    val subs = subscriptionStateMachine.getSubscriptionData()
                    val status = subs?.subscriptionStatus
                    val currTs = System.currentTimeMillis()
                    if ((status != null && status.billingExpiry > currTs) || DEBUG) {
                        Logger.i(LOG_TAG_PROXY, "$TAG; subscription cancelled but still valid until ${status?.billingExpiry}, not deactivating RPN yet")
                    } else {
                        deactivateRpn("Subscription cancelled and expired")
                    }
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; error handling cancelled state: ${e.message}", e)
                }
            }
            is SubscriptionStateMachineV2.SubscriptionState.Expired -> {
                Logger.w(LOG_TAG_PROXY, "$TAG; subscription expired, disabling RPN")
                try {
                    deactivateRpn("Subscription expired")
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; error deactivating RPN on expiry: ${e.message}", e)
                }
            }
            is SubscriptionStateMachineV2.SubscriptionState.Revoked -> {
                Logger.w(LOG_TAG_PROXY, "$TAG; subscription revoked, immediately disabling RPN")
                try {
                    deactivateRpn("Subscription revoked")
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; error deactivating RPN on revocation: ${e.message}", e)
                }
            }
            is SubscriptionStateMachineV2.SubscriptionState.Error -> {
                Logger.e(LOG_TAG_PROXY, "$TAG; subscription state machine in error state: ${state.name}")
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

    suspend fun setHopForWinServer(key: String, enabled: Boolean) {
        if (key.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; setHopForWinServer: key is empty")
            return
        }

        val config = winCacheMutex.withLock {
            winServersCache.find { it.key == key }
        }

        if (config == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; setHopForWinServer: server with key $key not found")
            return
        }

        val oldValue = config.hopEnabled
        config.hopEnabled = enabled
        winCacheMutex.withLock {
            winServersCache.filter { w -> w.key == key }.forEach { w ->
                w.hopEnabled = enabled
            }
        }

        try {
            countryConfigRepo.update(config)
            Logger.i(LOG_TAG_PROXY, "$TAG; setHopForWinServer: set hopEnabled=$enabled for server: $key")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; setHopForWinServer: failed to update DB for $key: ${e.message}", e)
            // Revert on DB failure
            config.hopEnabled = oldValue
            winCacheMutex.withLock {
                winServersCache.filter { w -> w.key == key }.forEach { w ->
                    w.hopEnabled = oldValue
                }
            }
        }

        try {
            val res = VpnController.handleRpnHop(config.key, oldValue != enabled)
            if (res.first) {
                Logger.i(LOG_TAG_PROXY, "$TAG; setHopForWinServer: tunnel updated hop for server $key successfully")
            } else {
                Logger.w(LOG_TAG_PROXY, "$TAG; setHopForWinServer: tunnel failed to update hop for server $key, ${if (res.second != null) "error: ${res.second}" else "no error message"}")
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; setHopForWinServer: error updating tunnel for $key: ${e.message}", e)
        }
    }

    suspend fun getSelectedCCs(): Set<CountryConfig> {
        winCacheMutex.withLock {
            return winServersCache.filter { it.isEnabled }.toSet()
        }
    }

    /**
     * Returns the epoch-millisecond timestamp at which [key] was last added to the tunnel
     * Returns 0 if the server has not been added to the tunnel since the last process start.
     */
    fun getSelectedSinceTs(key: String): Long {
        return serverKeyMeta[key]?.selectedAt ?: 0L
    }

    /**
     * Records the epoch-ms timestamp at which [key] was last added to the VPN tunnel.
     *
     * Must be called immediately after every successful [VpnController.addNewWinServer] call,
     * regardless of whether it is the user explicitly enabling a server ([enableWinServer]),
     * the tunnel being re-established on a phone reboot / VPN reconnect
     * ([BraveVPNService.handleRpnProxies]), or a periodic refresh ([updateWinProxy]).
     */
    fun notifyServerAddedToTun(key: String) {
        if (key.isEmpty()) return
        val now = System.currentTimeMillis()
        serverKeyMeta[key] = ServerKeyMeta(selectedAt = now)
        if (DEBUG) Logger.d(LOG_TAG_PROXY, "$TAG; notifyServerAddedToTun: key=$key, ts=$now")
    }

    /**
     * Removes the in-memory tunnel timestamp for [key].
     * Called when a server is explicitly removed from the tunnel (e.g. [disableWinServer]).
     */
    private fun clearServerMeta(key: String) {
        serverKeyMeta.remove(key)
    }

    suspend fun getEnabledConfigs(): Set<CountryConfig> {
        return winCacheMutex.withLock {
            val es = winServersCache.filter { it.isEnabled }.toSet()
            es
        }
    }

    /**
     * Full update pipeline:
     *  1. Ask the tunnel to refresh and return updated WIN state bytes
     *     ([VpnController.updateWin]).
     *  2a. If the tunnel returns bytes → persist them with [updateWinConfigState].
     *  2b. If the tunnel returns null → fall back to [registerProxy] (full re-register).
     *     If that also fails return null so callers can show an error.
     *  3. Fetch server locations from the tunnel ([fetchAndConstructWinLocations]).
     *     If empty, retry up to 3 times with 1 s back-off.
     *  4. Sync fetched locations to DB + in-memory cache ([syncWinServers]).
     *  5. Re-add every currently-enabled server key back into the tunnel
     *     ([VpnController.addNewWinServer]) so WireGuard peers are refreshed.
     *  6. If any previously-selected servers are absent from the new location list,
     *     emit them on [serverRemovedEvent] for the UI to surface a notification.
     *
     * Return contract
     *  - `null` → hard failure; caller should show an error / retry.
     *  - empty list → update succeeded but tunnel reported no locations yet; treat as
     *                 success, show retry / await next cycle.
     *  - non-empty → full success; contains the refreshed [CountryConfig] list.
     */
    suspend fun updateWinProxy(): List<CountryConfig>? {
        Logger.i(LOG_TAG_PROXY, "$TAG; updateWinProxy: starting WIN proxy update")


        val isRpnRegistered = VpnController.isWinRegistered()
        if (!isRpnRegistered) {
            Logger.w(LOG_TAG_PROXY, "$TAG; updateWinProxy: WIN not registered, delegating to registerProxy")
            val registered = registerProxy(RpnType.WIN)
            if (!registered) {
                Logger.e(LOG_TAG_PROXY, "$TAG; updateWinProxy: registration attempt failed, cannot proceed with update")
                return null
            }
            Logger.i(LOG_TAG_PROXY, "$TAG; updateWinProxy: WIN registered successfully, falling through to server sync")
            // registerProxy already synced the server list; fall through so enabled
            // servers are re-added to the tunnel by the loop below.
        } else {
            val bytes: ByteArray? = VpnController.updateWin()

            if (bytes == null) {
                Logger.w(LOG_TAG_PROXY, "$TAG; updateWinProxy: updateWin() returned null")
                return null
            }

            val persisted = updateWinConfigState(bytes)
            if (!persisted) {
                // Log the failure but do NOT return null; the in-memory state is already
                // updated by updateWinConfigState before it touches the DB.
                Logger.w(
                    LOG_TAG_PROXY,
                    "$TAG; updateWinProxy: failed to persist WIN config state (continuing)"
                )
            }
        }

        var fetchResult: Pair<Set<CountryConfig>, List<String>> = Pair(emptySet(), emptyList())
        val maxFetchRetries = 3
        for (attempt in 1..maxFetchRetries) {
            fetchResult = try {
                fetchAndConstructWinLocations()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; updateWinProxy: fetchAndConstructWinLocations threw on attempt $attempt: ${e.message}", e)
                Pair(emptySet(), emptyList())
            }

            if (fetchResult.first.isNotEmpty()) {
                Logger.i(LOG_TAG_PROXY, "$TAG; updateWinProxy: fetched ${fetchResult.first.size} locations on attempt $attempt")
                break
            }

            if (attempt < maxFetchRetries) {
                Logger.w(LOG_TAG_PROXY, "$TAG; updateWinProxy: no locations on attempt $attempt, retrying in 1s…")
                delay(2_000L)
            }
        }

        val (newServers, removedSelectedIds) = fetchResult

        if (newServers.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; updateWinProxy: no locations after $maxFetchRetries attempts; cache unchanged")
            // Return empty list (not null) bytes persisted successfully, just no locations yet
            return emptyList()
        }

        try {
            syncWinServers(newServers)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; updateWinProxy: syncWinServers threw: ${e.message}", e)
            // Non-fatal; continue to re-add servers and notify UI
        }

        // Read enabled configs from the freshly-synced cache so we never re-add stale keys.
        val enabledConfigs = winCacheMutex.withLock {
            winServersCache.filter { it.isEnabled && !it.id.equals(AUTO_SERVER_ID, true) }.toList()
        }

        for (config in enabledConfigs) {
            try {
                val result = VpnController.addNewWinServer(config.key)
                if (result.first) {
                    // Update the in-memory timestamp so uptime display shows the last
                    // time this server was (re-)added to the tunnel.
                    notifyServerAddedToTun(config.key)
                    Logger.i(LOG_TAG_PROXY, "$TAG; updateWinProxy: re-added server key=${config.key}")
                } else {
                    Logger.w(LOG_TAG_PROXY, "$TAG; updateWinProxy: failed to re-add server key=${config.key}: ${result.second}")
                    // Mark as disabled in cache + DB so the UI reflects the real state
                    winCacheMutex.withLock {
                        winServersCache.firstOrNull { it.key == config.key }?.isEnabled = false
                    }
                    try {
                        val dbConfig = countryConfigRepo.getById(config.id)
                        if (dbConfig != null) {
                            dbConfig.isEnabled = false
                            countryConfigRepo.update(dbConfig)
                        }
                    } catch (dbErr: Exception) {
                        Logger.w(LOG_TAG_PROXY, "$TAG; updateWinProxy: could not mark server disabled in DB: ${dbErr.message}")
                    }
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; updateWinProxy: exception re-adding server key=${config.key}: ${e.message}", e)
            }
        }

        if (removedSelectedIds.isNotEmpty()) {
            // Resolve full CountryConfig objects for the removed IDs so the UI can
            // display country names / flags in ServerRemovalNotificationBottomSheet.
            val removedConfigs = try {
                countryConfigRepo.getAllConfigs()
                    .filter { removedSelectedIds.contains(it.id) && !it.id.equals(AUTO_SERVER_ID, true) }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_PROXY, "$TAG; updateWinProxy: could not resolve removed configs: ${e.message}")
                emptyList()
            }

            if (removedConfigs.isNotEmpty()) {
                Logger.w(LOG_TAG_PROXY, "$TAG; updateWinProxy: emitting removal event for ${removedConfigs.size} server(s)")
                // Emit on the shared-flow so any active ServerSelectionFragment shows the sheet
                _serverRemovedEvent.tryEmit(removedConfigs)
            }
        }

        val currentServers = winCacheMutex.withLock { winServersCache.toList() }
        Logger.i(LOG_TAG_PROXY, "$TAG; updateWinProxy: complete, ${currentServers.size} servers in cache, " +
                "${enabledConfigs.size} re-added to tunnel, ${removedSelectedIds.size} removed-selected notified")
        return currentServers
    }

    private fun io(f: suspend () -> Unit) {
        stateObserverScope.launch { f() }
    }

    private fun getConfigFileName(id: Int,): String {
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
        val proxy = db.getProxyById(WIN_ID) ?: return null
        val cfg = proxy.configPath
        if (cfg.isEmpty()) return null
        val file = File(cfg)
        if (!file.exists()) return null
        return try {
            val bytes = EncryptedFileManager.readByteArray(applicationContext, file)
            if (bytes.isNotEmpty()) bytes else null
        } catch (e: EncryptionException) {
            Logger.w(LOG_TAG_PROXY, "$TAG; getWinExistingData: encrypted file unreadable (${e::class.simpleName}), returning null", e)
            null
        }
    }

    suspend fun updateWinConfigState(byteArray: ByteArray?): Boolean {
        if (byteArray == null || byteArray.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; updateWinConfigState: byteArray is null or empty")
            return false
        }
        return try {
            val fileName = getConfigFileName(WIN_ID)
            val folder = applicationContext.getExternalFilesDir(RPN_PROXY_FOLDER_NAME)
            if (folder == null) {
                Logger.e(LOG_TAG_PROXY, "$TAG; updateWinConfigState: failed to get external files dir")
                return false
            }

            if (!folder.exists()) {
                val created = try {
                    folder.mkdirs()
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; updateWinConfigState: exception creating folder: ${e.message}", e)
                    false
                }
                if (!created) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; updateWinConfigState: failed to create folder: ${folder.absolutePath}")
                    return false
                }
            }

            val file = File(folder, fileName)
            if (file.exists()) file.delete()
            val ok = try {
                EncryptedFileManager.write(applicationContext, byteArray, file)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; updateWinConfigState: exception writing file: ${e.message}", e)
                false
            }

            if (!ok) {
                Logger.e(LOG_TAG_PROXY, "$TAG; updateWinConfigState: failed to write file: ${file.absolutePath}")
                return false
            }

            val proxy = try {
                db.getProxyById(WIN_ID)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; updateWinConfigState: failed to get proxy from DB: ${e.message}", e)
                null
            }

            val updateResult = if (proxy != null) {
                proxy.configPath = file.absolutePath
                proxy.modifiedTs = System.currentTimeMillis()
                try {
                    db.update(proxy)
                    true
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; updateWinConfigState: failed to update proxy: ${e.message}", e)
                    false
                }
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
                try {
                    db.insert(newProxy)
                    true
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; updateWinConfigState: failed to insert proxy: ${e.message}", e)
                    false
                }
            }

            if (updateResult) {
                Logger.i(LOG_TAG_PROXY, "$TAG; updateWinConfigState: successfully updated config, size: ${byteArray.size}")
            } else {
                Logger.w(LOG_TAG_PROXY, "$TAG; updateWinConfigState: file written, DB update failed")
            }

            updateResult
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
         val winPropsResult = try {
             VpnController.getRpnProps(RpnType.WIN)
         } catch (e: Exception) {
             Logger.e(LOG_TAG_PROXY, "$TAG; fetchAndConstructWinLocations: exception getting RPN props: ${e.message}", e)
             return Pair(emptySet(), emptyList())
         }

         val winProps = winPropsResult.first
         if (winProps == null) {
             Logger.w(LOG_TAG_PROXY, "$TAG; err; win props is null")
             return Pair(emptySet(), emptyList())
         }

         val count = try {
             winProps.locations.len()
         } catch (e: Exception) {
             Logger.e(LOG_TAG_PROXY, "$TAG; fetchAndConstructWinLocations: exception getting locations count: ${e.message}", e)
             return Pair(emptySet(), emptyList())
         }

         if (count == 0L) {
             Logger.w(LOG_TAG_PROXY, "$TAG; err; no locations found in win props")
             return Pair(emptySet(), emptyList())
         }

         val newServers = mutableSetOf<CountryConfig>()
         for(i in 0 until count) {
             try {
                 val loc = winProps.locations.get(i)
                 if (loc == null) {
                     Logger.w(LOG_TAG_PROXY, "$TAG; err; location is null at index $i")
                     continue
                 }

                 // Validate essential fields
                 if (loc.cc.isNullOrEmpty() || loc.name.isNullOrEmpty() || loc.key.isNullOrEmpty()) {
                     Logger.w(LOG_TAG_PROXY, "$TAG; err; location has null or empty essential fields at index $i, cc=${loc.cc}, name=${loc.name}, key=${loc.key}")
                     continue
                 }

                 // Additional validation for address
                 val address = loc.addrs ?: ""
                 if (address.isEmpty()) {
                     Logger.w(LOG_TAG_PROXY, "$TAG; warning; location at index $i has empty address, cc=${loc.cc}, name=${loc.name}")
                 }

                 val id = "${loc.cc}-${loc.name}-${loc.key}"
                 val cfg = CountryConfig(
                     id = id,
                     cc = loc.cc,
                     name = loc.name,
                     address = address,
                     city = loc.city,
                     key = loc.key,
                     load = loc.load,
                     link = loc.link,
                     premium = loc.premium,
                     count = loc.count,
                     isActive = true
                 )
                 newServers.add(cfg)
             } catch (e: Exception) {
                 Logger.w(LOG_TAG_PROXY, "$TAG; err; error processing location at index $i: ${e.message}", e)
             }
         }

         // Get existing servers from DB to identify removed ones
         val existingServers = try {
             countryConfigRepo.getAllConfigs()
         } catch (e: Exception) {
             Logger.e(LOG_TAG_PROXY, "$TAG; err; failed to get existing servers: ${e.message}", e)
             emptyList()
         }

         val existingIds = existingServers.map { it.id }.toSet()
         val auto = existingServers.firstOrNull { it.id.contains(AUTO_SERVER_ID, ignoreCase = true) } ?: createAutoServer()
         newServers.add(auto)
         val newIds = newServers.map { it.id }.toSet()
         val removedIds = existingIds - newIds

         // Check if any removed servers were in the selected list
         val removedSelectedIds = mutableListOf<String>()
         if (removedIds.isNotEmpty()) {
             val keysToRemove = mutableSetOf<String>()
             for (removedId in removedIds) {
                 val removed = existingServers.firstOrNull { it.id == removedId }
                 if (removed != null && serverKeyMeta.containsKey(removed.key)) {
                     removedSelectedIds.add(removedId)
                     keysToRemove.add(removed.key)
                     Logger.w(LOG_TAG_PROXY, "$TAG; removed server $removedId (key=${removed.key}) was in tunnel")
                 }
             }
             if (keysToRemove.isNotEmpty()) {
                 keysToRemove.forEach { serverKeyMeta.remove(it) }
                 Logger.i(LOG_TAG_PROXY, "$TAG; cleared ${keysToRemove.size} server keys from serverKeyMeta")
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
        if (key.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; enableWinServer: key is empty")
            return Pair(false, "Server key is empty")
        }

        val config = winCacheMutex.withLock {
            winServersCache.find { it.key == key }
        }

        if (config == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; enableWinServer: server with key $key not found in cache")
            return Pair(false, "Server with key $key not found")
        }

        // Check if already enabled
        if (config.isEnabled) {
            Logger.i(LOG_TAG_PROXY, "$TAG; enableWinServer: server $key already enabled")
            return Pair(true, "Server already enabled")
        }

        val res = try {
            VpnController.addNewWinServer(config.key)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; enableWinServer: exception calling VpnController for $key: ${e.message}", e)
            return Pair(false, "Failed to add server: ${e.message}")
        }

        if (res.first) {
            winCacheMutex.withLock {
                winServersCache.filter { it.key == key }.forEach { it.isEnabled = true }
            }
            config.isEnabled = true
            try {
                countryConfigRepo.update(config)
                // Record the selection for frequent-country tracking and refresh chips.
                countryConfigRepo.incrementSelectionCount(config.key)
                // Mark when this server was added to the tunnel so the UI can show uptime.
                notifyServerAddedToTun(key)
                Logger.i(LOG_TAG_PROXY, "$TAG; enableWinServer: enabled rpn: $key")
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; enableWinServer: failed to update DB for $key: ${e.message}", e)
                // Revert cache change on DB failure
                winCacheMutex.withLock {
                    winServersCache.filter { it.key == key }.forEach { it.isEnabled = false }
                }
                config.isEnabled = false
                return Pair(false, "Failed to update database: ${e.message}")
            }
        } else {
            Logger.w(LOG_TAG_PROXY, "$TAG; enableWinServer: failed to enable server with key $key, error: ${res.second}")
        }
        return res
    }

    suspend fun setLockdownForWinServer(key: String, lockdown: Boolean) {
        if (key.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; setLockdownForWinServer: key is empty")
            return
        }

        val config = winCacheMutex.withLock {
            winServersCache.find { it.key == key }
        }

        if (config == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; setLockdownForWinServer: server with key $key not found")
            return
        }

        val oldValue = config.lockdown
        config.lockdown = lockdown
        winCacheMutex.withLock {
            winServersCache.filter { w -> w.key == key }.forEach { w ->
                w.lockdown = lockdown
            }
        }

        try {
            countryConfigRepo.update(config)
            Logger.i(LOG_TAG_PROXY, "$TAG; setLockdownForWinServer: set lockdown=$lockdown for server: $key")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; setLockdownForWinServer: failed to update DB for $key: ${e.message}", e)
            // Revert on DB failure
            config.lockdown = oldValue
            winCacheMutex.withLock {
                winServersCache.filter { w -> w.key == key }.forEach { w ->
                    w.lockdown = oldValue
                }
            }
        }
    }

    suspend fun setCatchAllForWinServer(key: String, catchAll: Boolean) {
        if (key.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; setCatchAllForWinServer: key is empty")
            return
        }

        val config = winCacheMutex.withLock {
            winServersCache.find { it.key == key }
        }

        if (config == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; setCatchAllForWinServer: server with key $key not found")
            return
        }

        val oldCatchAll = config.catchAll
        val oldEnabled = config.isEnabled

        if (!config.isEnabled && catchAll) {
            Logger.w(LOG_TAG_PROXY, "$TAG; setCatchAllForWinServer: enabling inactive server $key for catch-all")
            val res = try {
                VpnController.addNewWinServer(config.key)
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; setCatchAllForWinServer: exception enabling server $key: ${e.message}", e)
                return
            }

            if (res.first) {
                config.isEnabled = true
                config.catchAll = true
                Logger.i(LOG_TAG_PROXY, "$TAG; setCatchAllForWinServer: enabled server $key for catch-all")
                winCacheMutex.withLock {
                    winServersCache.filter { w -> w.key == key }.forEach { w ->
                        w.isEnabled = true
                        w.catchAll = true
                    }
                }
            } else {
                Logger.w(LOG_TAG_PROXY, "$TAG; setCatchAllForWinServer: failed to enable server $key for catch-all, error: ${res.second}")
                return
            }
        } else {
            config.catchAll = catchAll
            Logger.i(LOG_TAG_PROXY, "$TAG; setCatchAllForWinServer: set catchAll=$catchAll for server: $key")
            winCacheMutex.withLock {
                winServersCache.filter { w -> w.key == key }.forEach { w ->
                    w.catchAll = catchAll
                }
            }
        }

        try {
            countryConfigRepo.update(config)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; setCatchAllForWinServer: failed to update DB for $key: ${e.message}", e)
            // Revert changes on DB failure
            config.isEnabled = oldEnabled
            config.catchAll = oldCatchAll
            winCacheMutex.withLock {
                winServersCache.filter { w -> w.key == key }.forEach { w ->
                    w.isEnabled = oldEnabled
                    w.catchAll = oldCatchAll
                }
            }
        }
    }

    suspend fun setMobileOnlyForWinServer(key: String, mobileOnly: Boolean) {
        if (key.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; setMobileOnlyForWinServer: key is empty")
            return
        }

        val config = winCacheMutex.withLock {
            winServersCache.find { it.key == key }
        }

        if (config == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; setMobileOnlyForWinServer: server with key $key not found")
            return
        }

        val oldValue = config.mobileOnly
        config.mobileOnly = mobileOnly
        winCacheMutex.withLock {
            winServersCache.filter { w -> w.key == key }.forEach { w ->
                w.mobileOnly = mobileOnly
            }
        }

        try {
            countryConfigRepo.update(config)
            Logger.i(LOG_TAG_PROXY, "$TAG; setMobileOnlyForWinServer: set mobileOnly=$mobileOnly for server: $key")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; setMobileOnlyForWinServer: failed to update DB for $key: ${e.message}", e)
            // Revert on DB failure
            config.mobileOnly = oldValue
            winCacheMutex.withLock {
                winServersCache.filter { w -> w.key == key }.forEach { w ->
                    w.mobileOnly = oldValue
                }
            }
        }

        if (config.isEnabled) {
            VpnController.refreshOrPauseOrResumeOrReAddProxies()
        }
    }

    suspend fun setSsidEnabledForWinServer(key: String, ssidEnabled: Boolean) {
        if (key.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; setSsidEnabledForWinServer: key is empty")
            return
        }

        val config = winCacheMutex.withLock {
            winServersCache.find { it.key == key }
        }

        if (config == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; setSsidEnabledForWinServer: server with key $key not found")
            return
        }

        val oldValue = config.ssidBased
        config.ssidBased = ssidEnabled
        winCacheMutex.withLock {
            winServersCache.filter { w -> w.key == key }.forEach { w ->
                w.ssidBased = ssidEnabled
            }
        }

        try {
            countryConfigRepo.update(config)
            Logger.i(LOG_TAG_PROXY, "$TAG; setSsidEnabledForWinServer: set ssidEnabled=$ssidEnabled for server: $key")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; setSsidEnabledForWinServer: failed to update DB for $key: ${e.message}", e)
            // Revert on DB failure
            config.ssidBased = oldValue
            winCacheMutex.withLock {
                winServersCache.filter { w -> w.key == key }.forEach { w ->
                    w.ssidBased = oldValue
                }
            }
        }

        if (config.isEnabled) {
            VpnController.refreshOrPauseOrResumeOrReAddProxies()
        }
    }

    suspend fun disableWinServer(key: String): Pair<Boolean, String> {
        if (key.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; disableWinServer: key is empty")
            return Pair(false, "Server key is empty")
        }

        val config = winCacheMutex.withLock {
            winServersCache.find { it.key == key }
        }

        if (config == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; disableWinServer: server with key $key not found in cache")
            return Pair(false, "Server with key $key not found")
        }

        // Check if already disabled
        if (!config.isEnabled) {
            Logger.i(LOG_TAG_PROXY, "$TAG; disableWinServer: server $key already disabled")
            return Pair(true, "Server already disabled")
        }

        val res = try {
            VpnController.removeWinServer(config.key)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; disableWinServer: exception calling VpnController for $key: ${e.message}", e)
            return Pair(false, "Failed to remove server: ${e.message}")
        }

        if (res.first) {
            config.isEnabled = false
            winCacheMutex.withLock {
                winServersCache.filter { it.key == key }.forEach { it.isEnabled = false }
            }
                try {
                    countryConfigRepo.update(config)
                    // Clear the in-memory tunnel timestamp for this server.
                    clearServerMeta(key)
                    Logger.i(LOG_TAG_PROXY, "$TAG; disableWinServer: disabled rpn: $key")
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; disableWinServer: failed to update DB for $key: ${e.message}", e)
                    // Revert cache change on DB failure
                    winCacheMutex.withLock {
                        winServersCache.filter { it.key == key }.forEach { it.isEnabled = true }
                    }
                    config.isEnabled = true
                    return Pair(false, "Failed to update database: ${e.message}")
                }
        } else {
            Logger.w(LOG_TAG_PROXY, "$TAG; disableWinServer: failed to disable server with key $key, error: ${res.second}")
        }
        return res
    }

    /**
     * Ensures AUTO server exists in database. Creates it if it doesn't exist.
     * AUTO server is a special server that represents automatic server selection.
     */
    suspend fun ensureAutoServerExists() {
        try {
            val autoServer = countryConfigRepo.getById(AUTO_SERVER_ID) ?: countryConfigRepo.getById(AUTO_SERVER_ID.lowercase())
            if (autoServer == null) {
                Logger.i(LOG_TAG_PROXY, "$TAG; ensureAutoServerExists: AUTO server not found, creating...")
                val newAutoServer = createAutoServer()
                countryConfigRepo.insert(newAutoServer)
                // Add to cache
                winCacheMutex.withLock {
                    winServersCache.add(newAutoServer)
                }
                Logger.i(LOG_TAG_PROXY, "$TAG; ensureAutoServerExists: AUTO server created successfully")
            } else {
                Logger.v(LOG_TAG_PROXY, "$TAG; ensureAutoServerExists: AUTO server already exists")
                // Ensure it's in the cache
                winCacheMutex.withLock {
                    if (winServersCache.none { it.id.equals(AUTO_SERVER_ID, true) }) {
                        winServersCache.add(autoServer)
                    }
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; ensureAutoServerExists: error - ${e.message}", e)
        }
    }

    /**
     * Creates an AUTO server configuration
     */
    private fun createAutoServer(): CountryConfig {
        return CountryConfig(
            id = AUTO_SERVER_ID,
            cc = AUTO_COUNTRY_CODE,
            name = AUTO_SERVER_ID,
            address = AUTO_SERVER_ID,
            city = AUTO_SERVER_ID,
            key = AUTO_SERVER_ID,
            load = 0,
            link = 0,
            count = 1,
            premium = true,
            isActive = true,
            isEnabled = false, // Not enabled by default
            catchAll = true,
            lockdown = false,
            mobileOnly = false,
            ssidBased = false,
            priority = 999, // Highest priority so it appears first
            ssids = "",
            lastModified = System.currentTimeMillis()
        )
    }

    /**
     * Gets AUTO server from database/cache.
     *
     * If the AUTO server is absent from both the in-memory cache and the database (e.g. on first
     * launch or if [load] hasn't been called yet), [ensureAutoServerExists] is invoked to create
     * and persist it, and the newly-created entry is returned.  This makes the function
     * self-healing so that callers never see a spurious null.
     */
    suspend fun getAutoServer(): CountryConfig? {
        return try {
            val cached = winCacheMutex.withLock {
                winServersCache.find { it.id.equals(AUTO_SERVER_ID, true) }
            }
            if (cached != null) return cached

            val fromDb = countryConfigRepo.getById(AUTO_SERVER_ID) ?: countryConfigRepo.getById(AUTO_SERVER_ID.lowercase())
            if (fromDb != null) {
                // Opportunistically warm the cache so subsequent calls hit the fast path.
                winCacheMutex.withLock {
                    if (winServersCache.none { it.id.equals(AUTO_SERVER_ID, true) }) {
                        winServersCache.add(fromDb)
                    }
                }
                return fromDb
            }

            Logger.w(LOG_TAG_PROXY, "$TAG; getAutoServer: AUTO server missing from cache and DB, creating via ensureAutoServerExists")
            ensureAutoServerExists()

            winCacheMutex.withLock {
                winServersCache.find { it.id.equals(AUTO_SERVER_ID, true) }
            } ?: countryConfigRepo.getById(AUTO_SERVER_ID) ?: countryConfigRepo.getById(AUTO_SERVER_ID.lowercase())
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; getAutoServer: err: ${e.message}", e)
            null
        }
    }

    /**
     * Updates AUTO server state in database and cache
     */
    suspend fun updateAutoServerState(autoServer: CountryConfig) {
        try {
            countryConfigRepo.update(autoServer)
            // Update cache
            winCacheMutex.withLock {
                val index = winServersCache.indexOfFirst { it.key == AUTO_SERVER_ID }
                if (index >= 0) {
                    winServersCache[index] = autoServer
                }
            }
            Logger.i(LOG_TAG_PROXY, "$TAG; updateAutoServerState: AUTO server updated, isEnabled=${autoServer.isEnabled}")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; updateAutoServerState: error - ${e.message}", e)
        }
    }

     /**
      * Syncs fetched servers with database and cache.
      * This should be called after fetchAndConstructWinLocations.
      * AUTO server is protected and never deleted.
      */
     suspend fun syncWinServers(servers: Set<CountryConfig>) {
         try {
             // Ensure AUTO server exists before syncing
             ensureAutoServerExists()

             // get the current server list before sync so we can find removed servers
             // and clean up their ProxyApplicationMapping entries.
             val serversBefore = try {
                 countryConfigRepo.getAllConfigs()
             } catch (e: Exception) {
                 Logger.w(LOG_TAG_PROXY, "$TAG; syncWinServers: could not read pre-sync servers: ${e.message}")
                 emptyList()
             }

             // Sync to database (this handles insertions, updates, and deletions)
             val syncServerList = if (servers.isEmpty()) {
                 Logger.w(LOG_TAG_PROXY, "$TAG; syncWinServers: empty server list, clearing DB except AUTO")
                    emptyList()
             } else {
                 servers.toList()
             }

             // Sync to database (AUTO server is protected in the repository method)
             countryConfigRepo.syncServers(syncServerList)

             // clean up proxy-app mappings for every server that was removed from the DB.
             val newServerKeys = servers.map { it.key }.toSet()
             val removedServers = serversBefore.filter {
                 it.id != AUTO_SERVER_ID && !newServerKeys.contains(it.key)
             }
             if (removedServers.isNotEmpty()) {
                 removedServers.forEach { removed ->
                     ProxyManager.removeProxyId(Backend.RpnWin + removed.key)
                     Logger.i(LOG_TAG_PROXY, "$TAG; syncWinServers: removed proxy mapping for stale server key=${removed.key}")
                 }
             }

             // Read back from DB to ensure consistency
             val existingServers = try {
                 countryConfigRepo.getAllConfigs()
             } catch (e: Exception) {
                 Logger.e(LOG_TAG_PROXY, "$TAG; syncWinServers: failed to read from DB after sync: ${e.message}", e)
                 emptyList()
             }

             // Update cache - clear and refill with new data
             winCacheMutex.withLock {
                 winServersCache.clear()
                 if (existingServers.isNotEmpty()) {
                     winServersCache.addAll(existingServers)
                 }
             }

             Logger.i(LOG_TAG_PROXY, "$TAG; syncWinServers: synced ${servers.size} servers to DB, ${existingServers.size} in cache, ${removedServers.size} proxy mappings cleaned")
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

             if (newServers.isEmpty()) {
                 retryLocationFetch()
                 return Pair(emptyList(), emptyList())
             }

             // Sync to DB and cache
             syncWinServers(newServers)

             // Find the actual removed server objects for notification
             val removedServers = existingServers.filter { removedSelectedIds.contains(it.id) && it.id.equals(AUTO_SERVER_ID, true) }

             Logger.i(LOG_TAG_PROXY, "$TAG; refreshWinServers: refreshed ${newServers.size} servers, ${removedServers.size} selected servers removed")
             // Return the cache populated by syncWinServers (read from DB) instead of the
             // freshly-constructed API objects.  API objects are built with default field values
             // (e.g. isFavourite = false), so returning them would silently clear every country's
             // favourite flag in the in-memory allServers list inside ServerSelectionFragment.
             val freshList = winCacheMutex.withLock { winServersCache.toList() }
             return Pair(freshList, removedServers)
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

        Logger.vv(LOG_TAG_PROXY, "$TAG; getAllPossibleConfigIdsForApp: init $uid, $ip, $port, $domain, $usesMobileNw, $ssid")
        // collect all proxy-ids for this uid
        val allProxyIdsForApp = try {
            ProxyManager.getProxyIdsForApp(uid)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error getting proxy ids for app $uid: ${e.message}", e)
            emptyList()
        }

        val rpnProxyIdsForApp = allProxyIdsForApp.filter { it.startsWith(Backend.RpnWin) }

        Logger.v(LOG_TAG_PROXY, "$TAG; allProxyIdsForApp: $allProxyIdsForApp, rpnProxyIdsForApp: $rpnProxyIdsForApp")

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
                        var id = appProxyPair.first
                        if (id.contains(AUTO_SERVER_ID, true)) {
                            id = VpnController.getWinByKey("")?.id() ?: block
                            proxyIds.add(id)
                        } else {
                            proxyIds.add(appProxyPair.first)
                        }
                    }
                    Logger.i(LOG_TAG_PROXY, "$TAG lockdown wg for app($uid) => return $proxyIds")
                    return proxyIds
                }
                if (appProxyPair.first.isNotEmpty()) {
                    // add eligible app-specific config in the order we see them
                    var id = appProxyPair.first
                    if (id.contains(AUTO_SERVER_ID, true)) {
                        id = VpnController.getWinByKey("")?.id() ?: block
                        proxyIds.add(id)
                    } else {
                        proxyIds.add(appProxyPair.first)
                    }
                }
            }
        }

        // once the app-specific config is added, check if any catch-all config is enabled
        // if catch-all config is enabled, then add the config id to the list
        val cac = winCacheMutex.withLock {
            try {
                winServersCache.filter { it.isEnabled && it.catchAll }.toList()
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; error filtering catch-all configs: ${e.message}", e)
                emptyList()
            }
        }

        Logger.v(LOG_TAG_PROXY, "$TAG; cac: $cac")
        cac.forEach {
            try {
                val configId = Backend.RpnWin + it.key
                if ((checkEligibilityBasedOnNw(it.id, usesMobileNw) ||
                            checkEligibilityBasedOnSsid(it.id, ssid, usesMobileNw)) &&
                    !proxyIds.contains(configId)
                ) {
                    val id = if (configId.contains(AUTO_SERVER_ID, true)) {
                        VpnController.getWinByKey("")?.id() ?: block
                    } else {
                        configId
                    }
                    proxyIds.add(id)
                    Logger.i(
                        LOG_TAG_PROXY,
                        "$TAG catch-all config is active: ${it.id}, ${it.name} => add $id"
                    )
                }
            } catch (e: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; error processing catch-all config ${it.id}: ${e.message}", e)
            }
        }

        // the proxyIds list will contain the ip-app specific, domain-app specific, app specific,
        // universal ip, universal domain, catch-all and default configs in the order of priority
        // the go-tun will check the routing based on the order of the list
        Logger.i(LOG_TAG_PROXY, "$TAG returning proxy ids for $uid, $ip, $port, $domain: $proxyIds")
        return proxyIds
    }

    private suspend fun canUseConfig(
        id: String,
        type: String,
        usesMobileNw: Boolean,
        ssid: String
    ): Pair<String, Boolean> {
        val block = Backend.Block
        if (id.isEmpty()) {
            return Pair("", true)
        }
        val actualId = id.substringAfter(Backend.RpnWin)

        val config = winCacheMutex.withLock {
            if (actualId == Backend.RpnWin || actualId.equals(AUTO_SERVER_ID, true)) winServersCache.find { it.id.equals(AUTO_SERVER_ID, true) }
            else winServersCache.find { it.id == id || it.id == actualId || it.key == id || it.key == actualId }
        }

        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "$TAG; config null($actualId) no need to proceed, return empty")
            return Pair("", true)
        }

        Logger.vv(LOG_TAG_PROXY, "$TAG; config-details: $config")

        val lockdown = config.lockdown

        if (lockdown && (checkEligibilityBasedOnNw(id, usesMobileNw) || checkEligibilityBasedOnSsid(id, ssid, usesMobileNw))) {
            Logger.d(LOG_TAG_PROXY, "$TAG; lockdown wg for $type => return $id")
            return Pair(id, false) // no need to proceed further for lockdown
        }

        // in case of lockdown and not metered network, we need to return block as the
        // lockdown should not leak the connections via WiFi
        if (lockdown) {
            // add IpnBlock instead of the config id, let the connection be blocked in WiFi
            // regardless of config is active or not
            Logger.d(LOG_TAG_PROXY, "$TAG; lockdown wg for $type => return $block")
            return Pair(block, false) // no need to proceed further for lockdown
        }

        // check if the config is active and if it can be used on this network
        if (config.isEnabled && (checkEligibilityBasedOnNw(
                id,
                usesMobileNw
            ) || checkEligibilityBasedOnSsid(id, ssid, usesMobileNw))
        ) {
            Logger.d(LOG_TAG_PROXY, "$TAG active wg for $type => add $id")
            return Pair(id, true)
        }

        Logger.v(
            LOG_TAG_PROXY,
            "$TAG wg for $type not active or not eligible nw, return empty, for id: $id, usesMobileNw: $usesMobileNw, ssid: $ssid"
        )
        return Pair("", true)
    }

    // only when config is set to use on mobile network and current network is not mobile
    // then return false, all other cases return true
    private suspend fun checkEligibilityBasedOnNw(id: String, usesMobileNw: Boolean): Boolean {
        if (id.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; checkEligibilityBasedOnNw: id is empty")
            return false
        }

        val actualId = id.substringAfter(Backend.RpnWin)
        val config = winCacheMutex.withLock {
            if (actualId == Backend.RpnWin || actualId.equals(AUTO_SERVER_ID, true)) winServersCache.find { it.id.equals(AUTO_SERVER_ID, true) }
            else winServersCache.find { it.id == id || it.id == actualId || it.key == id || it.key == actualId }
        }

        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "$TAG; checkEligibilityBasedOnNw: wg not found, id: $id, actualId: $actualId, cache size: ${winServersCache.size}")
            return false
        }

        if (config.mobileOnly && usesMobileNw) {
            Logger.i(LOG_TAG_PROXY, "$TAG; checkEligibilityBasedOnNw: mobileOnly is true for ${config.key}, but mobile nw, return true")
            return true
        }

        Logger.d(LOG_TAG_PROXY, "$TAG; checkEligibilityBasedOnNw: not eligible for mobile nw: $usesMobileNw, mobile-only: ${config.mobileOnly}, key: ${config.key}")
        return false
    }

    private suspend fun checkEligibilityBasedOnSsid(id: String, ssid: String, usesMobileNw: Boolean): Boolean {
        if (id.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; checkEligibilityBasedOnSsid: id is empty")
            return false
        }

        val actualId = id.substringAfter(Backend.RpnWin)
        val config = winCacheMutex.withLock {
            if (actualId == Backend.RpnWin || actualId.equals(AUTO_SERVER_ID, true)) winServersCache.find { it.id.equals(AUTO_SERVER_ID, true) }
            winServersCache.find { it.id == id || it.id == actualId || it.key == id || it.key == actualId }
        }

        if (config == null) {
            Logger.e(LOG_TAG_PROXY, "$TAG; checkEligibilityBasedOnSsid: wg not found, id: $id, actualId: $actualId, cache size: ${winServersCache.size}")
            return false
        }

        if (usesMobileNw && ssid.isEmpty()) {
            Logger.i(LOG_TAG_PROXY, "canAdd: mobile nw, return false")
            return false
        }

        if (config.ssidBased) {
            val ssidItems = SsidItem.parseStorageList(config.ssids)
            if (ssidItems.isEmpty() && ssid.isNotEmpty()) { // treat empty as match all
                Logger.d(
                    LOG_TAG_PROXY,
                    "$TAG; checkEligibilityBasedOnSsid: ssidEnabled is true, but ssid list is empty, match all"
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
                Logger.d(LOG_TAG_PROXY, "$TAG; checkEligibilityBasedOnSsid: ssid matched in NOT_EQUAL items, return false")
                return false
            }

            val equalItems = ssidItems.filter { it.type.isEqual }
            // If there are only NOT_EQUAL items and none matched, return true
            if (equalItems.isEmpty() && notEqualItems.isNotEmpty()) {
                Logger.d(
                    LOG_TAG_PROXY,
                    "$TAG; checkEligibilityBasedOnSsid: only NOT_EQUAL items present and none matched, return true"
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
                Logger.d(LOG_TAG_PROXY, "$TAG; checkEligibilityBasedOnSsid: ssid did not match in EQUAL items, return false")
                return false
            }
        }

        Logger.d(LOG_TAG_PROXY, "$TAG; checkEligibilityBasedOnSsid: eligible for ssid: $ssid")
        return true
    }

    private fun matchesWildcard(pattern: String, text: String): Boolean {
        if (pattern.isEmpty() || text.isEmpty()) {
            Logger.v(LOG_TAG_PROXY, "$TAG; matchesWildcard: empty pattern or text")
            return false
        }

        // Convert wildcard pattern to regex
        // * matches any sequence of characters
        // ? matches any single character
        return try {
            val regexPattern = pattern
                .replace(".", "\\.")  // Escape dots
                .replace("*", ".*")   // Convert * to .*
                .replace("?", ".")    // Convert ? to .

            val matches = text.matches(Regex(regexPattern, RegexOption.IGNORE_CASE))
            val contains = text.contains(pattern, ignoreCase = true)

            matches || contains
        } catch (e: Exception) {
            Logger.w(LOG_TAG_PROXY, "$TAG; matchesWildcard: invalid wildcard pattern: $pattern, error: ${e.message}")
            // Fallback to simple contains check
            try {
                text.contains(pattern, ignoreCase = true)
            } catch (e2: Exception) {
                Logger.e(LOG_TAG_PROXY, "$TAG; matchesWildcard: fallback also failed: ${e2.message}", e2)
                false
            }
        }
    }

    fun matchesSsidList(ssidList: String, ssid: String): Boolean {
        if (ssidList.isEmpty()) {
            // Empty list means match all
            Logger.v(LOG_TAG_PROXY, "$TAG; matchesSsidList: empty ssidList, match all")
            return true
        }

        if (ssid.isEmpty()) {
            Logger.v(LOG_TAG_PROXY, "$TAG; matchesSsidList: empty ssid, no match")
            return false
        }

        val ssidItems = try {
            SsidItem.parseStorageList(ssidList)
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; matchesSsidList: failed to parse ssidList: ${e.message}", e)
            return true // Fail open
        }

        if (ssidItems.isEmpty()) { // treat empty as match all
            return true
        }

        // Separate EQUAL items from NOT_EQUAL items
        val equalItems = ssidItems.filter { it.type.isEqual }
        val notEqualItems = ssidItems.filter { !it.type.isEqual }

        // Check NOT_EQUAL items first - if any match, return false
        val notEqualMatch = notEqualItems.any { ssidItem ->
            try {
                when {
                    ssidItem.type.isExact -> {
                        ssidItem.name.equals(ssid, ignoreCase = true)
                    }
                    else -> { // wildcard
                        matchesWildcard(ssidItem.name, ssid)
                    }
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_PROXY, "$TAG; matchesSsidList: error matching NOT_EQUAL item: ${e.message}")
                false
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
            try {
                when {
                    ssidItem.type.isExact -> {
                        ssidItem.name.equals(ssid, ignoreCase = true)
                    }
                    else -> { // wildcard
                        matchesWildcard(ssidItem.name, ssid)
                    }
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_PROXY, "$TAG; matchesSsidList: error matching EQUAL item: ${e.message}")
                false
            }
        }
    }

    /**
     * Update SSID based status for a country
     * @param cc Country code
     * @param ssidBased Whether SSID-based connection is enabled
     */
    suspend fun updateSsidBased(key: String, ssidBased: Boolean) {
        if (key.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; updateSsidBased: cc is empty")
            return
        }

        val config = winCacheMutex.withLock {
            winServersCache.find { it.key == key }
        }

        if (config == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; updateSsidBased: config not found in cache for key: $key")
            return
        }

        val oldValue = config.ssidBased

        try {
            countryConfigRepo.updateSsidBased(key, ssidBased)

            // Update cache
            winCacheMutex.withLock {
                val cachedConfig = winServersCache.find { it.key == key }
                if (cachedConfig != null) {
                    winServersCache.remove(cachedConfig)
                    winServersCache.add(cachedConfig.copy(ssidBased = ssidBased, lastModified = System.currentTimeMillis()))
                } else {
                    Logger.w(LOG_TAG_PROXY, "$TAG; updateSsidBased: config disappeared from cache for key: $key")
                }
            }

            Logger.i(LOG_TAG_PROXY, "$TAG; updateSsidBased: $key = $ssidBased")

            // Trigger connection monitor to update SSID info if country is selected
            val keyExists = winCacheMutex.withLock { winServersCache.any { it.key == key } }
            if (keyExists) {
                try {
                    if (oldValue != ssidBased) {
                        VpnController.notifyConnectionMonitor(enforcePolicyChange = true)
                    }
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; updateSsidBased: failed to refresh proxies: ${e.message}", e)
                    // Don't revert DB change if only the VPN controller refresh failed
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error updating SSID based for $key: ${e.message}", e)
            // Revert cache on DB failure
            winCacheMutex.withLock {
                val cachedConfig = winServersCache.find { it.key == key }
                if (cachedConfig != null) {
                    winServersCache.remove(cachedConfig)
                    winServersCache.add(cachedConfig.copy(ssidBased = oldValue))
                }
            }
        }

        if (config.isEnabled) {
            // Refresh proxies to immediately pause/resume based on new SSID setting
            VpnController.refreshOrPauseOrResumeOrReAddProxies()
        }
    }

    /**
     * Update SSID list for a country
     * @param cc Country code
     * @param ssids JSON string of SSID items
     */
    suspend fun updateSsids(key: String, ssids: String) {
        if (key.isEmpty()) {
            Logger.w(LOG_TAG_PROXY, "$TAG; updateSsids: cc is empty")
            return
        }

        val config = winCacheMutex.withLock {
            winServersCache.find { it.key == key }
        }

        if (config == null) {
            Logger.w(LOG_TAG_PROXY, "$TAG; updateSsids: config not found in cache for key: $key")
            return
        }

        val oldSsids = config.ssids

        try {
            countryConfigRepo.updateSsids(key, ssids)

            // Update cache
            winCacheMutex.withLock {
                val cachedConfig = winServersCache.find { it.key == key }
                if (cachedConfig != null) {
                    winServersCache.remove(cachedConfig)
                    winServersCache.add(cachedConfig.copy(ssids = ssids, lastModified = System.currentTimeMillis()))
                } else {
                    Logger.w(LOG_TAG_PROXY, "$TAG; updateSsids: config disappeared from cache for key: $key")
                }
            }

            Logger.i(LOG_TAG_PROXY, "$TAG; updateSsids: $key, ssids length=${ssids.length}")

            // If country is selected and SSID based enabled, refresh proxies.
            // Read both pieces of state in one lock to avoid a bare read.
            val (keyExists, isSsidBased) = winCacheMutex.withLock {
                val c = winServersCache.find { it.key == key }
                Pair(c != null, c?.ssidBased == true)
            }
            if (keyExists && isSsidBased) {
                try {
                    VpnController.notifyConnectionMonitor(enforcePolicyChange = true)
                } catch (e: Exception) {
                    Logger.e(LOG_TAG_PROXY, "$TAG; updateSsids: failed to refresh proxies: ${e.message}", e)
                    // Don't revert DB change if only the VPN controller refresh failed
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error updating SSIDs for $key: ${e.message}", e)
            // Revert cache on DB failure
            winCacheMutex.withLock {
                val cachedConfig = winServersCache.find { it.key == key }
                if (cachedConfig != null) {
                    winServersCache.remove(cachedConfig)
                    winServersCache.add(cachedConfig.copy(ssids = oldSsids))
                }
            }
        }

        if (config.isEnabled) {
            // refresh proxies to immediately pause/resume based on new SSID setting
            VpnController.refreshOrPauseOrResumeOrReAddProxies()
        }
    }

    suspend fun getCountryConfigByKey(key: String): CountryConfig? {
        return try {
            winCacheMutex.withLock {
                winServersCache.find { it.key == key }
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; error getting country config for $key: ${e.message}", e)
            null
        }
    }

    /**
     * Cleanup method to cancel observer job and release resources.
     * Should be called when the manager is no longer needed.
     */
    fun cleanup() {
        try {
            stateObserverJob.cancel()
            Logger.i(LOG_TAG_PROXY, "$TAG; cleanup: observer job cancelled")
        } catch (e: Exception) {
            Logger.e(LOG_TAG_PROXY, "$TAG; cleanup: error cancelling observer job: ${e.message}", e)
        }
    }

    suspend fun stats(): String {
        val sb = StringBuilder()
        sb.append("   Rpn active: ${isRpnActive()}\n\n")

        if (!isRpnActive()) return sb.toString()

        val enabledServers = winCacheMutex.withLock { winServersCache.filter { it.isEnabled } }
        enabledServers.forEach {
            val id = if (it.key.equals(AUTO_SERVER_ID, true)) {
                Backend.RpnWin
            } else {
                it.key
            }
            val stats = VpnController.getRpnStats(id)
            val routerStats = stats?.routerStats
            sb.append("   id: ${it.id}, name: ${it.name}\n")
            sb.append("   addr: ${routerStats?.addrs}").append("\n")
            sb.append("   mtu: ${stats?.mtu}\n")
            sb.append("   status: ${routerStats?.status}\n")
            sb.append("   statusReason: ${routerStats?.statusReason}\n")
            sb.append("   ip4: ${stats?.ip4}\n")
            sb.append("   ip6: ${stats?.ip6}\n")
            sb.append("   rx: ${routerStats?.rx}\n")
            sb.append("   tx: ${routerStats?.tx}\n")
            sb.append("   lastRx: ${getRelativeTimeSpan(routerStats?.lastRx)}\n")
            sb.append("   lastTx: ${getRelativeTimeSpan(routerStats?.lastTx)}\n")
            sb.append("   lastGoodRx: ${getRelativeTimeSpan(routerStats?.lastGoodRx)}\n")
            sb.append("   lastGoodTx: ${getRelativeTimeSpan(routerStats?.lastGoodTx)}\n")
            sb.append("   lastRxErr: ${routerStats?.lastRxErr}\n")
            sb.append("   lastTxErr: ${routerStats?.lastTxErr}\n")
            sb.append("   lastOk: ${getRelativeTimeSpan(routerStats?.lastOK)}\n")
            sb.append("   since: ${getRelativeTimeSpan(routerStats?.since)}\n")
            sb.append("   errRx: ${routerStats?.errRx}\n")
            sb.append("   errTx: ${routerStats?.errTx}\n")
            sb.append("   extra: ${routerStats?.extra}\n")
            sb.append("   client4: ${stats?.clientV4}\n")
            sb.append("   client6: ${stats?.clientV6}\n\n")
            val s = sb.toString()
            Logger.d(LOG_TAG_PROXY, "$TAG; id: $id stats:\n$s")
        }
        if (sb.isEmpty()) {
            sb.append("   N/A\n\n")
        }
        return sb.toString()
    }

    private fun getRelativeTimeSpan(t: Long?): CharSequence? {
        if (t == null) return "0"

        if (t < 0) return "-1"

        val now = System.currentTimeMillis()
        // returns a string describing 'time' as a time relative to 'now'
        return DateUtils.getRelativeTimeSpanString(
            t,
            now,
            DateUtils.SECOND_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE
        )
    }

}
