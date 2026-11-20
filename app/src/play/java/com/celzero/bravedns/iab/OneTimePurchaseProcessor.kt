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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_IAB
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.Purchase
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.iab.OneTimePurchaseProcessor.Companion.ACK_RETRY_DELAYS_MS
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Handles all Google Play **one-time / in-app** (INAPP) purchase processing.
 * Android billingclient/api/BillingClient.ProductType refers one-time as in-app purchases
 *
 * - Calculate access-window expiry based on product ID (plan duration)
 * - Build a [PurchaseDetail] from an INAPP [Purchase]
 * - Drive the state machine for acknowledged and unacknowledged one-time purchases
 * - Guard expired in-app purchases: if the local clock has passed the access window,
 *   the purchase is treated as expired and [buildPurchaseDetail] returns null.
 *
 * - For INAPP purchases, Google Play does not provide expiry; it is computed locally
 *   from a product ID or the exact purchase option returned by `/g/tx`.
 * - A purchase whose expiry time is past System.currentTimeMillis() is considered expired
 *   and the caller must notify the state machine via subscriptionExpired().
 * - Unlike SUBS, expiry time IS used to gate access for INAPP (since Play has no
 *   server-side concept of "expiry" for one-time products).
 */
internal class OneTimePurchaseProcessor(
    private val storeProductDetails: CopyOnWriteArrayList<QueryProductDetail>,
    private val subscriptionStateMachine: SubscriptionStateMachineV2,
    private val acknowledgePurchase: suspend (accountId: String, deviceId: String, purchaseToken: String, productType: String) -> Pair<Boolean, String>,
    private val activateRpn: suspend (purchaseDetail: PurchaseDetail) -> Unit,
    /** Returns the product type string (ProductType.SUBS or INAPP) for a purchase. */
    private val getProductType: (Purchase) -> String,
    /** Returns the DID bound to the account, or blank if unavailable. */
    private val getDeviceId: suspend (accountId: String) -> String = { "" },
    /** Resolves and, if needed, mints the DID for the server-ack path. */
    private val mintDeviceId: suspend (accountId: String) -> String = { "" },
    /** Returns the exact one-time order from server history, or null. */
    private val fetchRecentOneTimeOrder: suspend (accountId: String, purchaseToken: String) -> ServerOrderEntry? = { _, _ -> null },
    /** Called after a successful server acknowledgement. */
    private val onAckSuccess: (suspend (purchase: Purchase) -> Unit)? = null,
    private val reconcileCidDid: suspend (accountId: String) -> Unit,
    private val validatePayloadAndFetchIfRequired: suspend (purchaseDtl: PurchaseDetail) -> PurchaseDetail
) {

    companion object {
        private const val TAG = "InAppPurchaseProcessor"

        /**
         * Backoff delays (ms) between server-ack retries for transient 5xx errors.
         *
         * Attempt 0 fires immediately (no delay).
         * Attempt 1 waits ACK_RETRY_DELAYS_MS[0] before firing.
         * Attempt 2 waits ACK_RETRY_DELAYS_MS[1] before firing.
         * Total attempts = ACK_RETRY_DELAYS_MS.size + 1.
         *
         * After all retries are exhausted the purchase stays in PurchasePending
         * so the next fetchPurchases cycle can retry automatically.
         */
        private val ACK_RETRY_DELAYS_MS = longArrayOf(5_000L, 15_000L)

        /** Min interval between order-history retries for a token after a failed fetch. */
        private const val ORDER_HISTORY_RETRY_MS = 10 * 60_000L
    }

    // Guards duplicate concurrent order-history requests and their retry timestamps.
    private val orderHistoryMutex = Mutex()
    private val orderHistoryAttempts = ConcurrentHashMap<String, Long>()
    // Reapplies exact server corrections during this process; cold starts re-fetch by token.
    private val orderHistoryCorrections = ConcurrentHashMap<String, String>()

    private data class PurchaseCidResolution(
        val storedDetail: PurchaseDetail?,
        val accountId: String
    )

    private fun logd(m: String, msg: String) = Logger.d(LOG_IAB, "$TAG $m: $msg")
    private fun loge(m: String, msg: String, e: Exception? = null) =
        Logger.e(LOG_IAB, "$TAG $m: $msg", e)


    /**
     * Builds a [PurchaseDetail] from an INAPP [Purchase].
     *
     * **Returns null** if the purchase has passed its local access window
     * (expiryTime < System.currentTimeMillis).  The caller must then notify the
     * state machine via [SubscriptionStateMachineV2.subscriptionExpired] and skip
     * further processing.
     *
     * This null-return contract allows [process] to treat expiry cleanly without
     * duplicating the clock-check logic.
     */
    suspend fun buildPurchaseDetail(purchase: Purchase): PurchaseDetail? {
        return buildPurchaseDetail(purchase, null)
    }

    private suspend fun buildPurchaseDetail(
        purchase: Purchase,
        identity: PurchaseCidResolution?
    ): PurchaseDetail? {
        val mname = "buildPurchaseDetail"

        if (getProductType(purchase) != ProductType.INAPP) {
            loge(mname, "called with non-INAPP purchase error")
            return null
        }

        val resolvedIdentity = identity ?: resolvePurchaseCid(purchase)
        val storedDetail = resolvedIdentity.storedDetail
        val storeEntry = storeProductDetails.find {
            it.productDetail.productId == purchase.products.firstOrNull() &&
                    it.productDetail.productType == ProductType.INAPP
        }
        val productDetail = storeEntry?.productDetail

        val productId    = purchase.products.firstOrNull() ?: ""
        // A Play Purchase does not identify which cached option was selected.
        val planId       = productId
        val productTitle = if (productId == InAppBillingHandler.ONE_TIME_PRODUCT_ID) {
            storeEntry?.productDetails?.title ?: productId
        } else {
            productDetail?.productTitle ?: QueryUtils.getPlanTitle(getProductBillingPeriod(productId) ?: "P2Y")
        }
        val planTitle    = productTitle  // For INAPP, planTitle == productTitle
        val expiryTime   = expiryForPeriod(
            purchase.purchaseTime,
            resolveOneTimeBillingPeriod(planId, productId) ?: "P2Y"
        )
        val accountId = resolvedIdentity.accountId

        if (accountId.isEmpty()) {
            loge(mname, "accountId empty for purchase token ${purchase.purchaseToken.take(8)}")
        } else {
            reconcileCidDid(accountId)
        }

        val purchaseDtl = PurchaseDetail(
            productId = productId,
            planId = planId,
            productTitle = productTitle,
            planTitle = planTitle,
            state = purchase.purchaseState,
            purchaseToken = purchase.purchaseToken,
            productType = ProductType.INAPP,
            purchaseTime = purchase.purchaseTime.toFormattedDate(),
            purchaseTimeMillis = purchase.purchaseTime,
            isAutoRenewing = false,  // INAPP purchases never auto-renew
            accountId = accountId,
            // Never store the real device ID in PurchaseDetail; use sentinel only.
            deviceId = if (getDeviceId(accountId).isNotBlank()) SubscriptionStatus.DEVICE_ID_INDICATOR else "",
            payload = purchase.developerPayload,
            expiryTime = expiryTime,
            status = purchase.purchaseState.toSubscriptionStatusId(),
            // windowDays resolves via the plan first (a resolved 5-year option must not
            // inherit the 2-year product's window) and falls back to the product ID.
            windowDays = resolveRevokeDays(planId, productId),
            orderId = purchase.orderId.orEmpty()
        )

        val withStoredIdentity = preserveStoredPlanAndExpiry(purchaseDtl, storedDetail)
        val orderHistoryDetail = enrichFromOrderHistory(withStoredIdentity)
        val now = System.currentTimeMillis()
        if (orderHistoryDetail.expiryTime < now) {
            logd(mname, "INAPP purchase expired: productId=$productId, " +
                "purchaseTime=${purchase.purchaseTime}, expiryTime=${orderHistoryDetail.expiryTime}, now=$now; returning null")
            return null
        }

        val validatedDetail = validatePayloadAndFetchIfRequired(orderHistoryDetail)
        val enriched = preservePlanAndExpiry(validatedDetail, orderHistoryDetail)
        Logger.i(LOG_IAB, "$TAG $mname: final detail: planId=${enriched.planId}, title=${enriched.productTitle}, " +
            "expiry=${enriched.expiryTime}, token=${enriched.purchaseToken.take(8)}")
        return enriched
    }

    private suspend fun resolvePurchaseCid(purchase: Purchase): PurchaseCidResolution {
        val storedDetail = subscriptionStateMachine.getPersistedInAppPurchaseDetail(purchase.purchaseToken)
        val playAccountId = purchase.accountIdentifiers?.obfuscatedAccountId.orEmpty()
        val storedAccountId = storedDetail?.accountId.orEmpty()
        if (playAccountId.isNotBlank() && storedAccountId.isNotBlank() &&
            playAccountId != storedAccountId
        ) {
            Logger.w(
                LOG_IAB,
                "$TAG resolvePurchaseCid: preserving same-token stored cid=${storedAccountId.take(8)} " +
                    "over differing Play cid=${playAccountId.take(8)}"
            )
        }
        return PurchaseCidResolution(storedDetail, storedAccountId.ifBlank { playAccountId })
    }

    private fun preserveStoredPlanAndExpiry(
        purchaseDetail: PurchaseDetail,
        storedDetail: PurchaseDetail?
    ): PurchaseDetail {
        if (purchaseDetail.isPlanIdServerConfirmed || storedDetail == null) return purchaseDetail
        return purchaseDetail.copy(
            planId = storedDetail.planId.ifBlank { purchaseDetail.planId },
            productTitle = storedDetail.productTitle.ifBlank { purchaseDetail.productTitle },
            planTitle = storedDetail.planTitle.ifBlank { purchaseDetail.planTitle },
            accountId = storedDetail.accountId.ifBlank { purchaseDetail.accountId },
            deviceId = if (storedDetail.deviceId.isNotBlank()) {
                SubscriptionStatus.DEVICE_ID_INDICATOR
            } else {
                purchaseDetail.deviceId
            },
            expiryTime = if (storedDetail.expiryTime > 0L) storedDetail.expiryTime else purchaseDetail.expiryTime,
            windowDays = storedDetail.windowDays
        )
    }

    private fun preservePlanAndExpiry(
        validatedDetail: PurchaseDetail,
        planDetail: PurchaseDetail
    ): PurchaseDetail = validatedDetail.copy(
        planId = planDetail.planId,
        productTitle = planDetail.productTitle,
        planTitle = planDetail.planTitle,
        expiryTime = planDetail.expiryTime,
        windowDays = planDetail.windowDays,
        accountId = planDetail.accountId.ifBlank { validatedDetail.accountId },
        deviceId = planDetail.deviceId.ifBlank { validatedDetail.deviceId },
        isPlanIdServerConfirmed = planDetail.isPlanIdServerConfirmed
    )

    /**
     * Corrects planId/title/expiry from the server order history's purchaseOptionId.
     * Skip paths reapply the recorded correction so later reconstructions keep it.
     */
    private suspend fun enrichFromOrderHistory(purchaseDtl: PurchaseDetail): PurchaseDetail =
        orderHistoryMutex.withLock {
            val mname = "enrichFromOrderHistory"
            val token = purchaseDtl.purchaseToken
            orderHistoryCorrections[token]?.let { return@withLock correctedDetail(purchaseDtl, it) }
            if (purchaseDtl.isPlanIdServerConfirmed) return@withLock purchaseDtl

            val now = System.currentTimeMillis()
            val last = orderHistoryAttempts[token] ?: 0L
            if (now - last < ORDER_HISTORY_RETRY_MS) return@withLock purchaseDtl
            orderHistoryAttempts[token] = now

            val entry = try {
                fetchRecentOneTimeOrder(purchaseDtl.accountId, token)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                loge(mname, "order history fetch failed (non-fatal): ${e.message}", e)
                return@withLock purchaseDtl
            } ?: return@withLock purchaseDtl

            if (entry.purchaseToken != token || entry.cid != purchaseDtl.accountId ||
                entry.productId != purchaseDtl.productId || entry.isSubscription ||
                entry.purchaseState != ServerOrderEntry.PURCHASE_STATE_PURCHASED
            ) {
                logd(mname, "order history returned no matching purchased one-time row for ${token.take(8)}")
                return@withLock purchaseDtl
            }

            val optionId = entry.planId.trim()
            if (optionId.isBlank()) {
                logd(mname, "order history has no purchase option for ${token.take(8)}")
                return@withLock purchaseDtl
            }

            Logger.i(LOG_IAB, "$TAG $mname: confirming planId ${purchaseDtl.planId} → $optionId from exact order history")
            orderHistoryCorrections[token] = optionId
            correctedDetail(purchaseDtl, optionId)
        }

    /** Builds the corrected detail for a token whose plan was fixed from order history. */
    private fun correctedDetail(purchaseDtl: PurchaseDetail, optionId: String): PurchaseDetail {
        val billingPeriod = resolveOneTimeBillingPeriod(optionId, purchaseDtl.productId)
        val title = billingPeriod?.let { QueryUtils.getPlanTitle(it) }.orEmpty()
        val confirmedExpiry = if (purchaseDtl.purchaseTimeMillis > 0L) {
            billingPeriod?.let { expiryForPeriod(purchaseDtl.purchaseTimeMillis, it) }
        } else {
            null
        }
        return purchaseDtl.copy(
            planId = optionId,
            productTitle = title.ifBlank { purchaseDtl.productTitle },
            planTitle = title.ifBlank { purchaseDtl.planTitle },
            expiryTime = confirmedExpiry ?: purchaseDtl.expiryTime,
            windowDays = oneTimeWindowDaysForPlan(optionId) ?: purchaseDtl.windowDays,
            isPlanIdServerConfirmed = true
        )
    }

    /** Access-window expiry for a resolved billing period (P1D/P2Y/P5Y/...). */
    private fun expiryForPeriod(purchaseTimeMillis: Long, billingPeriod: String): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = purchaseTimeMillis }
        when (billingPeriod) {
            "P1D" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            "P5Y" -> cal.add(Calendar.YEAR, 5)
            else -> cal.add(Calendar.YEAR, 2)
        }
        return cal.timeInMillis
    }


    /**
     * Drives the state machine for a single INAPP [Purchase].
     *
     * @param purchase              The Play purchase to process.
     * @param shouldEscalateToServer
     *   `true`  → this token has been seen unacknowledged enough times that we must now call
     *             the server to acknowledge it ourselves.
     *   `false` → first/second sighting; Play may still propagate the ack, stay in
     *             PurchasePending and wait for the next query cycle.
     */
    suspend fun process(purchase: Purchase, shouldEscalateToServer: Boolean = false) {
        val mname = "process"
        logd(mname, "INAPP token=${purchase.purchaseToken.take(8)}, " +
                "productId=${purchase.products.firstOrNull()}, " +
                "state=${purchase.purchaseState}, ack=${purchase.isAcknowledged}, " +
                "escalate=$shouldEscalateToServer")

        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> processInAppPurchased(purchase, shouldEscalateToServer)

            Purchase.PurchaseState.PENDING -> {
                logd(mname, "INAPP pending for token=${purchase.purchaseToken.take(8)}")
                try {
                    val pd = buildPurchaseDetail(purchase)
                    if (pd != null) {
                        subscriptionStateMachine.completePurchase(pd)
                    } else {
                        logd(mname, "INAPP pending purchase already past expiry; ignoring")
                    }
                } catch (e: Exception) {
                    loge(mname, "error handling INAPP pending: ${e.message}", e)
                }
            }

            Purchase.PurchaseState.UNSPECIFIED_STATE -> {
                loge(mname, "INAPP state UNSPECIFIED for token=${purchase.purchaseToken.take(8)}")
                safeNotifyFailed("INAPP purchase state unspecified", null)
            }

            else -> {
                loge(mname, "INAPP unknown state ${purchase.purchaseState} for token=${purchase.purchaseToken.take(8)}")
                safeNotifyFailed("Unknown INAPP purchase state: ${purchase.purchaseState}", null)
            }
        }
    }

    private suspend fun processInAppPurchased(purchase: Purchase, shouldEscalateToServer: Boolean) {
        val mname = "processInAppPurchased"

        if (purchase.isAcknowledged) {
            logd(mname, "INAPP already acknowledged; checking expiry")
            try {
                val pd = buildPurchaseDetail(purchase)
                if (pd == null) {
                    logd(mname, "INAPP purchase access window expired; firing subscriptionExpired")
                    subscriptionStateMachine.subscriptionExpired(purchase.purchaseToken)
                    return
                }
                // paymentSuccessful -> handlePaymentSuccessful handles DB upsert
                // and RPN activation (including its dedup guard).
                subscriptionStateMachine.paymentSuccessful(pd)
            } catch (e: Exception) {
                loge(mname, "error processing acknowledged INAPP: ${e.message}", e)
                safeNotifyFailed("Error processing acknowledged INAPP: ${e.message}", null)
            }
            return
        }

        // purchase is not yet acknowledged by Play.
        if (!shouldEscalateToServer) {
            // below the escalation threshold: Play is still the source of truth.
            logd(mname, "INAPP token=${purchase.purchaseToken.take(8)} unacknowledged " +
                    "but below escalation threshold, staying PurchasePending, " +
                    "waiting for next Play query cycle")
            try {
                val pd = buildPurchaseDetail(purchase)
                if (pd != null) {
                    subscriptionStateMachine.completePurchase(pd) // stays PurchasePending
                } else {
                    logd(mname, "INAPP purchase access window expired before ack; firing subscriptionExpired")
                    subscriptionStateMachine.subscriptionExpired(purchase.purchaseToken)
                }
            } catch (e: Exception) {
                loge(mname, "error recording pending INAPP state: ${e.message}", e)
            }
            return
        }

        // threshold crossed server acknowledgement required.
        logd(mname, "INAPP token=${purchase.purchaseToken.take(8)} unacknowledged after " +
                "threshold reached, calling server ack")
        val purchaseCid = resolvePurchaseCid(purchase)
        val accountId = purchaseCid.accountId
        // resolve the DID only when a server acknowledgement is required.
        val deviceId = if (purchase.developerPayload.isEmpty()) mintDeviceId(accountId) else ""
        if (accountId.isEmpty()) {
            loge(mname, "accountId empty, acknowledgement failed")
        }

        val ackResult = when {
            purchase.developerPayload.isNotEmpty() -> AckResult.Success(purchase.developerPayload)
            accountId.isBlank() || deviceId.isBlank() -> {
                loge(mname, "identity unavailable for server ack (accountId=${accountId.length}, deviceId=${deviceId.length}); keeping purchase pending")
                AckResult.TransientFailure("Account/device identity unavailable")
            }
            else -> acknowledgeWithRetry(accountId, deviceId, purchase)
        }

        val pd = buildPurchaseDetail(purchase, purchaseCid)
        if (pd == null) {
            logd(mname, "INAPP purchase access window expired during ack; marking expired")
            subscriptionStateMachine.subscriptionExpired(purchase.purchaseToken)
            return
        }

        when (ackResult) {
            is AckResult.Success -> {
                val pdWithPayload = pd.copy(
                    payload = ackResult.developerPayload.ifEmpty { pd.payload }
                )
                // paymentSuccessful -> handlePaymentSuccessful handles the full DB
                // upsert, state-machine transition to Active, and RPN activation.
                // Skip completePurchase to prevent a redundant DB write + the
                // transient PurchasePending state between the two calls.
                subscriptionStateMachine.paymentSuccessful(pdWithPayload)
                try { onAckSuccess?.invoke(purchase) } catch (e: Exception) {
                    loge(mname, "onAckSuccess callback failed (non-fatal): ${e.message}", e)
                }
            }

            is AckResult.TransientFailure -> {
                loge(mname, "server ack transient failure after retries; staying PurchasePending: ${ackResult.lastError}")
                subscriptionStateMachine.completePurchase(pd)
            }

            is AckResult.PermanentFailure -> {
                loge(mname, "server ack permanent failure: ${ackResult.reason}")
                subscriptionStateMachine.serverAckFailed(
                    "Server acknowledgement failed: ${ackResult.reason}"
                )
            }
        }
    }

    /**
     * Sealed result of [acknowledgeWithRetry].
     */
    private sealed class AckResult {
        data class Success(val developerPayload: String) : AckResult()
        data class TransientFailure(val lastError: String) : AckResult()
        data class PermanentFailure(val reason: String) : AckResult()
    }

    /**
     * Calls [acknowledgePurchase] with exponential backoff for transient 5xx errors.
     *
     * - Attempt 0: immediate.
     * - Attempt N (N > 0): waits [ACK_RETRY_DELAYS_MS][N-1] ms before firing.
     * - Returns [AckResult.Success] on first success.
     * - Returns [AckResult.TransientFailure] if all retries exhausted on 5xx.
     * - Returns [AckResult.PermanentFailure] immediately on 4xx or non-retryable error.
     *
     * The total number of attempts is [ACK_RETRY_DELAYS_MS].size + 1.
     */
    private suspend fun acknowledgeWithRetry(accountId: String, deviceId: String, purchase: Purchase): AckResult {
        val mname = "acknowledgeWithRetry"
        val maxAttempts = ACK_RETRY_DELAYS_MS.size + 1
        var lastTransientError = ""

        for (attempt in 0 until maxAttempts) {
            if (attempt > 0) {
                val delayMs = ACK_RETRY_DELAYS_MS[attempt - 1]
                logd(mname, "attempt $attempt/$maxAttempts, waiting ${delayMs}ms before retry")
                delay(delayMs)
            }

            val (ackOk, payload) = try {
                acknowledgePurchase(accountId, deviceId, purchase.purchaseToken, ProductType.INAPP)
            } catch (e: Exception) {
                loge(mname, "exception during ack attempt $attempt: ${e.message}", e)
                // Network exception
                lastTransientError = e.message ?: "network exception"
                continue
            }

            if (ackOk) {
                logd(mname, "ack succeeded on attempt $attempt")
                return AckResult.Success(payload)
            }

            // ack failed, determine if it is retryable.
            // payload here contains the error string from acknowledgePurchaseFromServer,
            // e.g. "Server error: PlayErr(http=500, ...)"
            val isRetryable = isAckErrorRetryable(payload)
            logd(mname, "ack failed on attempt $attempt, retryable=$isRetryable, error='$payload'")

            if (!isRetryable) {
                return AckResult.PermanentFailure(payload)
            }

            lastTransientError = payload
            // continue to next attempt
        }

        return AckResult.TransientFailure(lastTransientError)
    }

    /**
     * Returns true if the ack error string represents a transient server-side failure
     * (HTTP 5xx) that is safe to retry.
     *
     * The error string is produced by [InAppBillingHandler.acknowledgePurchaseFromServer]:
     * "Server error: PlayErr(http=500, ...)" for HTTP errors,
     * "No response from server" for null responses,
     * "Err: ..." for exceptions.
     *
     * 4xx responses are NOT retryable, they indicate a client/auth problem that
     * retrying will not fix (e.g. invalid accountId, purchase token already consumed).
     */
    private fun isAckErrorRetryable(errorPayload: String): Boolean {
        // "No response from server" can be a transient network.
        if (errorPayload.contains("No response", ignoreCase = true)) return true
        // Exception-level errors (network I/O), retryable.
        if (errorPayload.startsWith("Err:")) return true
        // Parse the HTTP code from "PlayErr(http=NNN, ...)" pattern.
        val httpCode = Regex("""http=(\d{3})""").find(errorPayload)
            ?.groupValues?.getOrNull(1)?.toIntOrNull() ?: return false
        return httpCode in 500..599
    }

    private suspend fun safeNotifyFailed(reason: String, billingResult: com.android.billingclient.api.BillingResult?) {
        try { subscriptionStateMachine.purchaseFailed(reason, billingResult?.responseCode) }
        catch (e: Exception) { loge("safeNotifyFailed", "state machine error: ${e.message}", e) }
    }

    /**
     * Returns the ISO-8601 billing period string for a given one-time product ID.
     * Used to derive [ProductDetail.productTitle] when the store hasn't cached the title yet.
     *
     */
    private fun getProductBillingPeriod(productId: String): String? = when (productId) {
        InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> "P2Y"
        InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> "P5Y"
        InAppBillingHandler.ONE_TIME_TEST_PRODUCT_ID -> "P1D"
        InAppBillingHandler.ONE_TIME_PRODUCT_ID -> "P2Y"
        else -> null
    }

    /**
     * Unknown server options keep the current expiry; product IDs retain known periods.
     */
    private fun resolveOneTimeBillingPeriod(planId: String, productId: String): String? =
        getProductBillingPeriod(planId)
            ?: if (planId == productId) getProductBillingPeriod(productId) else null

    /** Revoke-window days for a known one-time plan/option id, else null. */
    private fun oneTimeWindowDaysForPlan(planId: String): Int? = when (planId) {
        InAppBillingHandler.ONE_TIME_PRODUCT_5YRS ->
            InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_5YRS_DAYS
        InAppBillingHandler.ONE_TIME_PRODUCT_2YRS, InAppBillingHandler.ONE_TIME_PRODUCT_ID ->
            InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
        InAppBillingHandler.ONE_TIME_TEST_PRODUCT_ID -> InAppBillingHandler.REVOKE_WINDOW_SUBS_MONTHLY_DAYS
        else -> null
    }

    /**
     * Maps the resolved plan (purchase option) — falling back to the product ID — to its
     * revoke window, so a resolved 5-year option gets the 5-year window even when the
     * product hosts multiple options.
     */
    private fun resolveRevokeDays(planId: String, productId: String): Int {
        oneTimeWindowDaysForPlan(planId)?.let { return it }
        return resolveRevokeDaysForProduct(productId)
    }

    /** Maps a one-time product ID to its revoke-window in days. */
    private fun resolveRevokeDaysForProduct(productId: String): Int = when (productId) {
        InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
        InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_5YRS_DAYS
        // Legacy one-time product has same 2-year access window → use the same revoke window.
        InAppBillingHandler.ONE_TIME_PRODUCT_ID -> InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS
        InAppBillingHandler.ONE_TIME_TEST_PRODUCT_ID -> InAppBillingHandler.REVOKE_WINDOW_SUBS_MONTHLY_DAYS
        else -> InAppBillingHandler.REVOKE_WINDOW_ONE_TIME_2YRS_DAYS // conservative default
    }

    private fun Int.toSubscriptionStatusId(): Int = when (this) {
        Purchase.PurchaseState.PURCHASED -> SubscriptionStatus.SubscriptionState.STATE_ACTIVE.id
        Purchase.PurchaseState.PENDING -> SubscriptionStatus.SubscriptionState.STATE_ACK_PENDING.id
        else -> SubscriptionStatus.SubscriptionState.STATE_UNKNOWN.id
    }

    private fun Long.toFormattedDate(
        pattern: String = "MMM dd, yyyy HH:mm:ss",
        locale: Locale = Locale.getDefault()
    ): String = SimpleDateFormat(pattern, locale).format(Date(this))
}
