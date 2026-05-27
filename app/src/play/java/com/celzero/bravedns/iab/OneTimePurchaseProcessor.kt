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
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.celzero.bravedns.database.SubscriptionStatus
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Handles all Google Play **one-time / in-app** (INAPP) purchase processing.
 * Android billingclient/api/BillingClient.ProductType refers one-time as in-app purchases
 *
 * - Resolve [ProductDetails.OneTimePurchaseOfferDetails] for a purchase
 * - Calculate access-window expiry based on product ID (plan duration)
 * - Build a [PurchaseDetail] from an INAPP [Purchase]
 * - Drive the state machine for acknowledged and unacknowledged one-time purchases
 * - Guard expired in-app purchases: if the local clock has passed the access window,
 *   the purchase is treated as expired and [buildPurchaseDetail] returns null.
 *
 * - For INAPP purchases, Google Play does not provide expiry, it is computed locally
 *   from the product ID.
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
    /**
     * Returns the stable per-device identifier.
     * Invoked lazily from [buildPurchaseDetail] so the value is current at call time.
     */
    private val getDeviceId: suspend () -> String = { "" },
    /**
     * Called after a successful server acknowledgement, just before [activateRpn].
     * Used to trigger registerDevice on every successful ack, including retries.
     * Nullable, callers that do not need this hook pass null.
     */
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
    }

    private fun logd(m: String, msg: String) = Logger.d(LOG_IAB, "$TAG $m: $msg")
    private fun loge(m: String, msg: String, e: Exception? = null) =
        Logger.e(LOG_IAB, "$TAG $m: $msg", e)


    /**
     * Finds the [ProductDetails.OneTimePurchaseOfferDetails] for [purchase].
     *
     * One-time products may have multiple purchase options (e.g. "legacy-base" = 2yr,
     * "legacy-max" = 5yr). This method returns the first matching entry from the cache.
     *
     * Returns null when no matching entry is cached in [storeProductDetails].
     * Returning null is safe, [buildPurchaseDetail] falls back to product-ID-based
     * duration inference and still produces a correct [PurchaseDetail].
     */
    fun resolveOneTimePurchaseOfferDetails(purchase: Purchase): ProductDetails.OneTimePurchaseOfferDetails? {
        val mname = "resolveOneTimePurchaseOfferDetails"
        val productId = purchase.products.firstOrNull() ?: run {
            loge(mname, "purchase has no products")
            return null
        }

        val candidates = storeProductDetails.filter {
            it.productDetail.productId == productId &&
                    it.productDetail.productType == ProductType.INAPP &&
                    it.oneTimeOfferDetails != null
        }

        if (candidates.isEmpty()) {
            logd(mname, "no one-time offer candidates for productId=$productId; product not yet cached")
            return null
        }

        // For one-time purchases, the offer details are ordered by the purchase option ID.
        val best = candidates.firstOrNull()
        logd(mname, "resolved one-time offer: offerId=${best?.oneTimeOfferDetails?.offerId}, purchaseOptionId=${best?.oneTimeOfferDetails?.purchaseOptionId}")
        return best?.oneTimeOfferDetails
    }


    /**
     * Computes the **access-window expiry** for an INAPP purchase.
     *
     * Unlike subscriptions, Google Play has no server-side expiry for one-time products.
     * The duration is encoded in the **product ID** itself
     *
     */
    fun calculateExpiryTime(
        purchase: Purchase,
        @Suppress("UNUSED_PARAMETER") oneTimeOfferDetails: ProductDetails.OneTimePurchaseOfferDetails?
    ): Long {
        val mname = "calculateExpiryTime"
        val productId = purchase.products.firstOrNull() ?: ""
        val cal = Calendar.getInstance().apply { timeInMillis = purchase.purchaseTime }

        return when (productId) {
            InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> {
                cal.add(Calendar.YEAR, 2)
                logd(mname, "productId=$productId → 2 years, expiry=${cal.timeInMillis}")
                cal.timeInMillis
            }
            InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> {
                cal.add(Calendar.YEAR, 5)
                logd(mname, "productId=$productId → 5 years, expiry=${cal.timeInMillis}")
                cal.timeInMillis
            }
            InAppBillingHandler.ONE_TIME_TEST_PRODUCT_ID -> {
                // Test product: 1 day access window for QA
                cal.add(Calendar.DAY_OF_YEAR, 1)
                logd(mname, "productId=$productId (test) → 1 day, expiry=${cal.timeInMillis}")
                cal.timeInMillis
            }
            InAppBillingHandler.ONE_TIME_PRODUCT_ID -> {
                // Legacy one-time product: default to 2 years
                cal.add(Calendar.YEAR, 2)
                logd(mname, "productId=$productId (legacy) → 2 years, expiry=${cal.timeInMillis}")
                cal.timeInMillis
            }
            else -> {
                // Unknown product ID: conservative 2-year safe default.
                // Log at error level, a new product ID should be added to the when() above.
                loge(mname, "unknown productId=$productId, using 2-year fallback, update product ID mapping")
                cal.add(Calendar.YEAR, 2)
                cal.timeInMillis
            }
        }
    }

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
        val mname = "buildPurchaseDetail"

        if (getProductType(purchase) != ProductType.INAPP) {
            loge(mname, "called with non-INAPP purchase error")
            return null
        }

        val oneTimeOfferDetails = resolveOneTimePurchaseOfferDetails(purchase)
        val storeEntry = storeProductDetails.find {
            it.productDetail.productId == purchase.products.firstOrNull() &&
                    it.productDetail.productType == ProductType.INAPP
        }
        val productDetail = storeEntry?.productDetail

        val productId    = purchase.products.firstOrNull() ?: ""
        // for INAPP, planId == purchaseOptionId from the offer (e.g. "legacy-base", "legacy-max").
        // if unavailable, fall back to productId itself as a stable identifier.
        val planId       = oneTimeOfferDetails?.purchaseOptionId
            ?: oneTimeOfferDetails?.offerId
            ?: productDetail?.planId
            ?: productId
        val productTitle = productDetail?.productTitle ?: QueryUtils.getPlanTitle(getProductBillingPeriod(productId))
        val planTitle    = productTitle  // For INAPP, planTitle == productTitle
        val expiryTime   = calculateExpiryTime(purchase, oneTimeOfferDetails)
        val accountId    = purchase.accountIdentifiers?.obfuscatedAccountId.orEmpty()

        if (accountId.isEmpty()) {
            loge(mname, "accountId empty for purchase token ${purchase.purchaseToken.take(8)}")
        } else {
            reconcileCidDid(accountId)
        }

        // access-window check: if this purchase has passed its expiry, return null.
        // The caller must fire subscriptionExpired() on the state machine.
        val now = System.currentTimeMillis()
        if (expiryTime < now) {
            logd(mname, "INAPP purchase expired: productId=$productId, purchaseTime=${purchase.purchaseTime}, expiryTime=$expiryTime, now=$now; returning null")
            return null
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
            deviceId = if (getDeviceId().isNotBlank()) SubscriptionStatus.DEVICE_ID_INDICATOR else "",
            payload = purchase.developerPayload,
            expiryTime = expiryTime,
            status = purchase.purchaseState.toSubscriptionStatusId(),
            // resolveRevokeDays must receive the PRODUCT ID (not planId).
            // planId for INAPP is the purchaseOptionId (e.g. "legacy-base", "legacy-max")
            // which never matches the product-ID constants (e.g. "proxy-yearly-5").
            // Passing planId would cause proxy-yearly-5 to fall to the else-branch,
            // returning 14 days (2yr window) instead of the correct 35 days (5yr window).
            windowDays = resolveRevokeDays(productId),
            orderId = purchase.orderId.orEmpty()
        )

        val purchaseDetail = validatePayloadAndFetchIfRequired(purchaseDtl)
        return purchaseDetail
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
                    subscriptionStateMachine.subscriptionExpired()
                    return
                }
                subscriptionStateMachine.paymentSuccessful(pd)
                withContext(Dispatchers.IO) { activateRpn(pd) }
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
                    subscriptionStateMachine.subscriptionExpired()
                }
            } catch (e: Exception) {
                loge(mname, "error recording pending INAPP state: ${e.message}", e)
            }
            return
        }

        // threshold crossed server acknowledgement required.
        logd(mname, "INAPP token=${purchase.purchaseToken.take(8)} unacknowledged after " +
                "threshold reached, calling server ack")
        val accountId = purchase.accountIdentifiers?.obfuscatedAccountId.orEmpty()
        val deviceId = getDeviceId()
        if (accountId.isEmpty()) {
            loge(mname, "accountId empty, acknowledgement failed")
        }

        val ackResult = if (purchase.developerPayload.isEmpty()) {
            acknowledgeWithRetry(accountId, deviceId, purchase)
        } else {
            AckResult.Success(purchase.developerPayload)
        }

        val pd = buildPurchaseDetail(purchase)
        if (pd == null) {
            logd(mname, "INAPP purchase access window expired during ack; marking expired")
            subscriptionStateMachine.subscriptionExpired()
            return
        }

        when (ackResult) {
            is AckResult.Success -> {
                val pdWithPayload = pd.copy(
                    payload = ackResult.developerPayload.ifEmpty { pd.payload }
                )
                subscriptionStateMachine.completePurchase(pdWithPayload)
                subscriptionStateMachine.paymentSuccessful(pdWithPayload)
                try { onAckSuccess?.invoke(purchase) } catch (e: Exception) {
                    loge(mname, "onAckSuccess callback failed (non-fatal): ${e.message}", e)
                }
                withContext(Dispatchers.IO) { activateRpn(pdWithPayload) }
            }

            is AckResult.TransientFailure -> {
                loge(mname, "server ack transient failure after retries; staying PurchasePending: ${ackResult.lastError}")
                subscriptionStateMachine.completePurchase(pd)
            }

            is AckResult.PermanentFailure -> {
                loge(mname, "server ack permanent failure: ${ackResult.reason}")
                subscriptionStateMachine.purchaseFailed(
                    "Server acknowledgement failed: ${ackResult.reason}", null
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
    private fun getProductBillingPeriod(productId: String): String = when (productId) {
        InAppBillingHandler.ONE_TIME_PRODUCT_2YRS -> "P2Y"
        InAppBillingHandler.ONE_TIME_PRODUCT_5YRS -> "P5Y"
        InAppBillingHandler.ONE_TIME_TEST_PRODUCT_ID -> "P1D"
        InAppBillingHandler.ONE_TIME_PRODUCT_ID -> "P2Y"
        else -> "P2Y"
    }

    /** Maps a one-time product ID to its revoke-window in days. */
    private fun resolveRevokeDays(productId: String): Int = when (productId) {
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
