/*
 * Copyright 2026 RethinkDNS and its authors
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
package com.celzero.bravedns.iab

import Logger
import Logger.LOG_IAB
import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.android.billingclient.api.BillingClient
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.iab.InAppBillingHandler.isListenerRegistered
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.RpnProxyManager.extractWsObject
import com.celzero.bravedns.rpnproxy.RpnProxyManager.getExpiryFromPayload
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class SubscriptionCheckWorker(
    val context: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams), KoinComponent {

    private val persistentState by inject<PersistentState>()
    private val billingBackendClient by inject<BillingBackendClient>()

    private var attempts = 0

    companion object {
        private const val TAG = "SubscriptionCheckWorker"
        const val WORK_NAME = "SubscriptionCheckWorker"
        private const val REGISTRATION_REFRESH_INTERVAL_MS = 24 * 60 * 60 * 1000 //  1 day once
    }

    override suspend fun doWork(): Result {
        return withContext(Dispatchers.IO) {
            try {
                if (!RpnProxyManager.isRpnActive()) {
                    Logger.i(LOG_IAB, "$TAG; doWork: RPN not active, skipping")
                    return@withContext Result.success()
                }
                checkAndRegisterDeviceIfNeeded()

                // billing fetch
                initiateAndFetchPurchases()

                // validate one-time purchases and consume expired ones
                validateAndConsumeExpiredOneTimePurchases()

                Result.success()
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG; doWork failed: ${e.message}", e)
                Result.retry()
            }
        }
    }

    /**
     * Verifies the device is registered with the server (accountId + deviceId).
     */
    private suspend fun checkAndRegisterDeviceIfNeeded() {
        val name = "checkAndRegisterDeviceIfNeeded"
        try {
            val storedAccountId = billingBackendClient.getAccountId()
            val storedDeviceId = billingBackendClient.getDeviceId()
            val isStale = (System.currentTimeMillis() - persistentState.deviceRegistrationTimestamp) >
                                    REGISTRATION_REFRESH_INTERVAL_MS

            val purchase = InAppBillingHandler.getActivePurchasesSnapshot().firstOrNull()

            val entitlementCid = resolveEntitlementCid()
            // purchase accountId is an equivalent fallback
            val purchaseCid = purchase?.accountId?.takeIf { it.isNotBlank() }
            val authoritativeCid = entitlementCid ?: purchaseCid ?: storedAccountId
            // for logging purpose
            val cidSource = when {
                entitlementCid != null -> "entitlement"
                purchaseCid != null -> "purchase"
                else -> "stored"
            }

            if (authoritativeCid != storedAccountId) {
                Logger.w(LOG_IAB, "$TAG; $name: cid mismatch (source=$cidSource)," +
                    "authoritativeCid=${authoritativeCid.take(8)}, storedCid=${storedAccountId.take(8)}")
                val reconciledDeviceId = billingBackendClient.getDeviceId(authoritativeCid)
                if (reconciledDeviceId.isBlank()) {
                    Logger.e(LOG_IAB, "$TAG; $name: did not returned for cid, " +
                        "posting device not registered error")
                    val error = ServerApiError.DeviceNotRegistered(
                        entitlementCid = authoritativeCid,
                        storedCid      = storedAccountId,
                        deviceIdPrefix = storedDeviceId.take(8)
                    )
                    withContext(Dispatchers.Main) {
                        InAppBillingHandler.serverApiErrorLiveData.value = error
                        // when no UI observer is active (app backgrounded), post a
                        // system notification so the user is alerted
                        if (!InAppBillingHandler.serverApiErrorLiveData.hasActiveObservers()) {
                            val ctx = context.applicationContext
                            DeviceNotRegisteredNotifier.notify(ctx, error, persistentState.theme)
                        }
                    }
                    return // do not call register device with stale IDs
                }
                Logger.i(LOG_IAB, "$TAG; $name: did reconciled under cid " +
                    "(didLen=${reconciledDeviceId.length}); calling register device")
                dispatchDeviceRegistration(authoritativeCid, reconciledDeviceId, purchase)
                return
            } else {
                Logger.d(LOG_IAB, "$TAG; $name: cid matches, skipping registration, (source=$cidSource), authoritativeCid=${authoritativeCid.take(8)}, storedCid=${storedAccountId.take(8)}")
            }

            if (storedDeviceId.isBlank()) {
                val freshDeviceId = billingBackendClient.getDeviceId()
                Logger.i(LOG_IAB, "$TAG; $name: no stored did, calling reg device" +
                    "(freshDeviceLen=${freshDeviceId.length})")
                dispatchDeviceRegistration(storedAccountId, freshDeviceId, purchase)
                return
            } else {
                Logger.d(LOG_IAB, "$TAG; $name: stored did=${storedDeviceId.take(4)}, stored cid=${storedAccountId.take(8)}")
            }

            if (!isStale) {
                Logger.d(LOG_IAB, "$TAG; $name: device already registered within window, skipping")
                return
            }
            Logger.i(LOG_IAB, "$TAG; $name: register device with cid: ${storedAccountId.take(8)}, did: ${storedDeviceId.take(4)}")
            dispatchDeviceRegistration(storedAccountId, storedDeviceId, purchase)

        } catch (e: Exception) {
            Logger.w(LOG_IAB, "$TAG; $name: error reg dev: ${e.message}")
        }
    }

    private suspend fun dispatchDeviceRegistration(
        accountId: String,
        deviceId:  String,
        purchase:  PurchaseDetail?
    ) {
        if (purchase != null) {
            callDeviceRegistrationWithMeta(accountId, deviceId, purchase)
        } else {
            callBareDeviceRegistration(accountId, deviceId)
        }
    }

    /**
     * Reads the cid from the entitlement payload.
     *
     * Uses [VpnController.getEntitlementDetails] to resolve the entitlement cid.
     *
     * Returns null when:
     * - the VPN tunnel is not active / [VpnController.getEntitlementDetails] returns null
     * - the entitlement carries a blank cid
     * - any exception is thrown
     */
    private suspend fun resolveEntitlementCid(): String? {
        val mname = "resolveEntitlementCid"
        return try {
            val winEntitlement = RpnProxyManager.getWinEntitlement()
            val entitlement = VpnController.getEntitlementDetails(
                winEntitlement, billingBackendClient.getDeviceId()
            )
            val cid = entitlement?.cid()
            if (!cid.isNullOrBlank()) {
                Logger.d(LOG_IAB, "$TAG; $mname: entitlement cid len=${cid.length}")
                cid
            } else {
                Logger.d(LOG_IAB, "$TAG; $mname: entitlement cid unavailable (null or blank)")
                null
            }
        } catch (e: Exception) {
            Logger.w(LOG_IAB, "$TAG; $mname: could not resolve entitlement cid: ${e.message}")
            null
        }
    }

    /**
     * Calls register device without a meta body (bare registration).
     * Delegates to [BillingBackendClient.registerDeviceWithDeviceMeta].
     *
     * On HTTP 401 the [ServerApiError.Unauthorized401] is posted to
     * [InAppBillingHandler.serverApiErrorLiveData] so it is surfaced the next
     * time the user opens the Manage Subscription screen.
     */
    private suspend fun callBareDeviceRegistration(accountId: String, deviceId: String) {
        val name = "callBareDeviceRegistration"
        when (val result = billingBackendClient.registerDeviceWithDeviceMeta(accountId, deviceId)) {
            is RegisterDeviceResult.Success -> {
                Logger.i(LOG_IAB, "$TAG; $name: bare registration succeeded")
            }
            is RegisterDeviceResult.Unauthorized -> {
                Logger.e(LOG_IAB, "$TAG; $name: 401 unauthorized; posting DeviceAuthError to UI")
                withContext(Dispatchers.Main) {
                    val error = ServerApiError.Unauthorized401(
                        operation      = ServerApiError.Operation.DEVICE,
                        accountId      = accountId,
                        deviceIdPrefix = deviceId.take(6)
                    )
                    InAppBillingHandler.serverApiErrorLiveData.value = error
                }
            }
            is RegisterDeviceResult.Conflict -> {
                Logger.w(LOG_IAB, "$TAG; $name: 409 conflict, device already registered")
            }
            is RegisterDeviceResult.Failure -> {
                Logger.w(LOG_IAB, "$TAG; $name: bare registration failed: code=${result.httpCode}")
            }
        }
    }

    /**
     * Calls register device with a meta body built from [purchaseDetail].
     */
    private suspend fun callDeviceRegistrationWithMeta(
        accountId: String,
        deviceId: String,
        purchaseDetail: PurchaseDetail
    ) {
        val name = "callDeviceRegistrationWithMeta"
        Logger.i(LOG_IAB, "$TAG; $name: initiate registration, productId=${purchaseDetail.productId}")
        val meta = billingBackendClient.buildDeviceMeta(purchaseDetail.productId)
        InAppBillingHandler.registerDevice(accountId, deviceId, meta)
    }

    /**
     * Validates all active one-time (INAPP) purchases and calls the server-side
     * consume API for any that satisfy ALL the following conditions:
     *
     * 1. [PurchaseDetail.productType] == ProductType.INAPP (one-time purchase)
     * 2. Purchase exists locally
     * 3. Server entitlement is expired
     *
     * ### Idempotency
     * The server `/g/con` endpoint is idempotent: calling it for an already-consumed
     * purchase returns an `"already consumed"` error which is logged and not retried.
     *
     */
    private suspend fun validateAndConsumeExpiredOneTimePurchases() {
        val mname = "validateAndConsumeExpiredOneTimePurchases"
        try {
            val purchases = InAppBillingHandler.getActivePurchasesSnapshot()
            Logger.i(LOG_IAB, "$TAG; $mname: snapshot size=${purchases.size}")

            val inAppPurchases = purchases.filter { it.productType == BillingClient.ProductType.INAPP }
            if (inAppPurchases.isEmpty()) {
                Logger.d(LOG_IAB, "$TAG; $mname: no INAPP purchases in snapshot")
                return
            }

            inAppPurchases.forEach { purchase ->
                try {
                    processSingleInAppPurchaseForConsume(purchase)
                } catch (e: Exception) {
                    Logger.e(LOG_IAB, "$TAG; $mname: error processing purchase " +
                        "token=${purchase.purchaseToken.take(8)}: ${e.message}", e)
                }
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG; $mname: ${e.message}", e)
        }
    }

    /**
     * Processes a single INAPP [purchase] for server-side consume eligibility.
     */
    private suspend fun processSingleInAppPurchaseForConsume(purchase: PurchaseDetail) {
        val mname = "processSingleInAppPurchaseForConsume"

        // purchase exists locally (present in snapshot = persisted in DB)
        Logger.d(LOG_IAB, "$TAG; $mname: evaluating token=${purchase.purchaseToken.take(8)}, " +
            "productId=${purchase.productId}, expiryTime=${purchase.expiryTime}")

        // determine entitlement expiry from the best available source
        val entitlementExpiry = resolveEntitlementExpiry(purchase)

        if (entitlementExpiry == null) {
            Logger.d(LOG_IAB, "$TAG; $mname: entitlement expiry unavailable for " +
                "token=${purchase.purchaseToken.take(8)}, skipping consume")
            return
        }

        // entitlement is expired
        val now = System.currentTimeMillis()
        val isExpired = entitlementExpiry in 1..<now

        if (!isExpired) {
            Logger.d(LOG_IAB, "$TAG; $mname: entitlement NOT expired for " +
                "token=${purchase.purchaseToken.take(8)}, expiry=$entitlementExpiry, now=$now, skipping")
            return
        }

        Logger.i(LOG_IAB, "$TAG; $mname: ALL conditions satisfied for " +
            "token=${purchase.purchaseToken.take(8)}, productId=${purchase.productId}, " +
            "expiry=$entitlementExpiry, calling consume API")

        callConsumeApi(purchase)
    }

    /**
     * Resolves the entitlement expiry for [purchase] using a four-tier fallback:
     *
     * **Tier 1 – server ack (billingExpiry)**
     * The `billingExpiry` field on [PurchaseDetail] is populated by the server ack
     * response (via [InAppBillingHandler.acknowledgePurchaseFromServer]).  If it is
     * non-zero and not [Long.MAX_VALUE], it is the most authoritative value.
     *
     * **Tier 2 – payload ws.expiry**
     * If billingExpiry is unavailable, parse `ws.expiry` from [PurchaseDetail.payload]
     * using [RpnProxyManager.getExpiryFromPayload] the same helper used everywhere else
     * in the codebase to extract the server-side expiry timestamp.
     *
     * **Tier 3 – GoVpnAdapter.getEntitlementDetails (tunnel)**
     * If the VPN tunnel is active, GoVpnAdapter.getEntitlementDetails returns
     * RpnEntitlement which carries the expiry directly from the WireGuard key exchange.
     * This path is only reached when both Tier 1 and Tier 2 produce no usable value.
     *
     * **Tier 4 – BillingBackendClient.queryEntitlement (server fallback)**
     * When GoVpnAdapter.getEntitlementDetails returns null (tunnel not connected or
     * unavailable), query the billing server directly via [BillingBackendClient.queryEntitlement].
     * The returned [PurchaseDetail.payload] is stored as the new entitlement via
     * [RpnProxyManager.storeWinEntitlement] so subsequent calls benefit from the refresh.
     * The returned [PurchaseDetail.expiryTime] is used as the expiry if it is valid.
     *
     * Returns null when no expiry can be determined from any source.
     */
    private suspend fun resolveEntitlementExpiry(purchase: PurchaseDetail): Long? {
        val mname = "resolveEntitlementExpiry"

        // GoVpnAdapter.getEntitlementDetails via VpnController (tunnel fallback)
        // RpnEntitlement.expiry() returns an ISO 8601 String (e.g. "2025-08-11T00:00:00.000Z").
        // Convert to epoch-millis via Instant.parse, same approach as RpnProxyManager.getExpiryFromPayload.
        try {
            val tunnelExpiry = getExpiryFromPayload(purchase.payload)
            if (tunnelExpiry != null) {
                if (tunnelExpiry > 0L) {
                    Logger.d(LOG_IAB, "$TAG; $mname: using tunnel entitlement expiry" +
                            " epochMs=$tunnelExpiry" +
                            " for token=${purchase.purchaseToken.take(8)}")
                    return tunnelExpiry
                }
            } else {
                Logger.d(LOG_IAB, "$TAG; $mname: tunnel entitlement expiry unavailable from payload")
            }
        } catch (e: Exception) {
            Logger.w(LOG_IAB, "$TAG; $mname: tunnel entitlement unavailable: ${e.message}")
        }

        // BillingBackendClient.queryEntitlement (server fallback)
        // getEntitlementDetails() returned null (tunnel not connected / unavailable).
        // Query the billing server directly for a fresh entitlement, store the returned
        // payload so subsequent calls can use it, and use the returned expiryTime if valid.
        try {
            val accountId = purchase.accountId
            val deviceId  = billingBackendClient.getDeviceId()
            if (accountId.isNotEmpty() && purchase.purchaseToken.isNotEmpty()) {
                Logger.d(LOG_IAB, "$TAG; $mname: tunnel unavailable, querying server entitlement " +
                    "for token=${purchase.purchaseToken.take(8)}")
                when (val result = billingBackendClient.queryEntitlement(
                    accountId, deviceId, purchase, purchase.purchaseToken
                )) {
                    is QueryEntitlementResult.Success -> {
                        val updated = result.purchase
                        if (updated.payload.isNotEmpty()) {
                            Logger.i(LOG_IAB, "$TAG; $mname: server entitlement received, storing " +
                                "for token=${purchase.purchaseToken.take(8)}")
                            RpnProxyManager.storeWinEntitlement(updated.payload)
                        }
                        val serverExpiry = updated.expiryTime
                        if (serverExpiry > 0L && serverExpiry != Long.MAX_VALUE) {
                            Logger.d(LOG_IAB, "$TAG; $mname: using server-queried expiry=$serverExpiry " +
                                "for token=${purchase.purchaseToken.take(8)}")
                            return serverExpiry
                        }
                    }
                    is QueryEntitlementResult.Unauthorized -> {
                        Logger.e(LOG_IAB, "$TAG; $mname: 401 on entitlement query; posting auth error to UI")
                        withContext(Dispatchers.Main) {
                            val error = ServerApiError.Unauthorized401(
                                operation      = ServerApiError.Operation.ACKNOWLEDGE,
                                accountId      = accountId,
                                deviceIdPrefix = deviceId.take(6)
                            )
                            InAppBillingHandler.serverApiErrorLiveData.value = error
                        }
                    }
                    is QueryEntitlementResult.Conflict -> {
                        Logger.w(LOG_IAB, "$TAG; $mname: 409 conflict on entitlement query " +
                            "for token=${purchase.purchaseToken.take(8)}; skipping expiry resolution")
                    }
                    is QueryEntitlementResult.Failure -> {
                        Logger.w(LOG_IAB, "$TAG; $mname: server business error on entitlement query " +
                            "for token=${purchase.purchaseToken.take(8)}; skipping expiry resolution (local billing expiry is authority)")
                        // If the server included a linkedPurchaseId, the revoked purchase may have been
                        // superseded by an older one that is still valid. Attempt to reactivate it so
                        // the user is not left in a broken state.
                        val linked = result.linkedPurchaseId
                        if (!linked.isNullOrBlank()) {
                            Logger.i(LOG_IAB, "$TAG; $mname: linkedPurchaseId present for " +
                                "token=${purchase.purchaseToken.take(8)}; attempting reactivation of " +
                                "linkedToken=${linked.take(8)}")
                            try {
                                RpnProxyManager.tryReactivateLinkedPurchase(accountId, deviceId, linked)
                            } catch (e: Exception) {
                                Logger.e(LOG_IAB, "$TAG; $mname: tryReactivateLinkedPurchase threw for " +
                                    "linkedToken=${linked.take(8)}: ${e.message}", e)
                            }
                        }
                    }
                    is QueryEntitlementResult.Transient -> {
                        // Network/transient failure — server was not reached.
                        // Fall through without an expiry; the worker will retry on the next cycle.
                        Logger.w(LOG_IAB, "$TAG; $mname: transient failure (network/timeout) on entitlement query " +
                            "for token=${purchase.purchaseToken.take(8)}; will retry on next cycle")
                    }
                }
            } else {
                Logger.w(LOG_IAB, "$TAG; $mname: accountId or purchaseToken empty, skipping server query")
            }
        } catch (e: Exception) {
            Logger.w(LOG_IAB, "$TAG; $mname: server entitlement query failed: ${e.message}")
        }

        Logger.w(LOG_IAB, "$TAG; $mname: no expiry source available for token=${purchase.purchaseToken.take(8)}")
        return null
    }

    /**
     * Calls the server-side `/g/consume` API for [purchase] via [BillingBackendClient].
     *
     * Idempotent: "already consumed" responses are treated as success.
     * Network failures are caught and logged; the worker will retry on the next cycle.
     */
    private suspend fun callConsumeApi(purchase: PurchaseDetail) {
        val mname = "callConsumeApi"
        if (purchase.accountId.isBlank()) {
            Logger.e(LOG_IAB, "$TAG; $mname: accountId blank for token=${purchase.purchaseToken.take(8)}, skipping")
            return
        }
        val sku = purchase.productId.ifBlank { InAppBillingHandler.ONE_TIME_PRODUCT_ID }
        Logger.i(LOG_IAB, "$TAG; $mname: delegating to BillingServerRepository for " +
            "token=${purchase.purchaseToken.take(8)}, sku=$sku, accLen=${purchase.accountId.length}")
        // purchase.deviceId holds only the indicator, always fetch from SecureIdentityStore
        val deviceId = billingBackendClient.getDeviceId()
        val success = billingBackendClient.consumePurchase(purchase.accountId, deviceId, sku, purchase.purchaseToken)
        if (success) {
            Logger.i(LOG_IAB, "$TAG; $mname: consume succeeded for token=${purchase.purchaseToken.take(8)}")
        } else {
            Logger.w(LOG_IAB, "$TAG; $mname: consume failed for token=${purchase.purchaseToken.take(8)} (will retry next cycle)")
        }
    }

    private fun initiateAndFetchPurchases() {
        if (InAppBillingHandler.isBillingClientSetup() && isListenerRegistered(listener)) {
            Logger.i(LOG_IAB, "$TAG; initBilling: billing client already setup")
            InAppBillingHandler.fetchPurchases(listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP))
            return
        }
        if (InAppBillingHandler.isBillingClientSetup() && !isListenerRegistered(listener)) {
            InAppBillingHandler.registerListener(listener)
            InAppBillingHandler.fetchPurchases(listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP))
            Logger.i(LOG_IAB, "$TAG; initBilling: billing listener registered")
            return
        }
        // initiate the billing client, which will also register the listener and fetch purchases
        InAppBillingHandler.initiate(context, listener)
    }

    private fun reinitiate(attempt: Int = 0) {
        if (attempt > 3) {
            Logger.e(LOG_IAB, "$TAG; reinitiate failed after 3 attempts")
            return
        }
        initiateAndFetchPurchases()
    }

    private val listener = object : BillingListener {
        override fun onConnectionResult(isSuccess: Boolean, message: String) {
            Logger.d(LOG_IAB, "$TAG; onConnectionResult: isSuccess: $isSuccess, message: $message")
            if (!isSuccess) {
                Logger.e(LOG_IAB, "$TAG; billing connection failed: $message")
                reinitiate(attempts++)
                return
            }
            val productType = listOf(BillingClient.ProductType.SUBS, BillingClient.ProductType.INAPP)
            InAppBillingHandler.fetchPurchases(productType)
        }

        override fun purchasesResult(isSuccess: Boolean, purchaseDetailList: List<PurchaseDetail>) {
            Logger.v(LOG_IAB, "$TAG; purchasesResult: isSuccess: $isSuccess, purchaseDetailList: $purchaseDetailList")
            if (!isSuccess) {
                Logger.e(LOG_IAB, "$TAG; failed to fetch purchases")
                reinitiate(attempts++)
                return
            }
            if (purchaseDetailList.isEmpty()) {
                Logger.i(LOG_IAB, "$TAG; no active subscriptions found")
                return
            }
            Logger.i(LOG_IAB, "$TAG; active subscriptions found: $purchaseDetailList")
        }

        override fun productResult(isSuccess: Boolean, productList: List<ProductDetail>) {
            Logger.v(LOG_IAB, "$TAG; productResult: isSuccess: $isSuccess, productList: $productList")
        }
    }
}
