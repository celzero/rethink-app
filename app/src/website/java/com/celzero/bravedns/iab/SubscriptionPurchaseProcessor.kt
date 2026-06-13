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
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Handles all Google Play **subscription** (SUBS) purchase processing.
 *
 * - Resolve [ProductDetails.SubscriptionOfferDetails] for a purchase
 * - Calculate estimated billing expiry (display only, Play is master for active/expired)
 * - Build a [PurchaseDetail] from a SUBS [Purchase]
 * - Drive the state machine for acknowledged and unacknowledged subscription purchases
 */
internal class SubscriptionPurchaseProcessor(
    private val storeProductDetails: CopyOnWriteArrayList<QueryProductDetail>,
    private val subscriptionStateMachine: SubscriptionStateMachineV2,
    private val queryUtils: () -> QueryUtils,
    private val acknowledgePurchase: suspend (accountId: String, deviceId: String, purchaseToken: String, productType: String) -> Pair<Boolean, String>,
    private val activateRpn: suspend (purchaseDetail: PurchaseDetail) -> Unit,
    /** Returns the product type string (ProductType.SUBS or INAPP) for a purchase. */
    private val getProductType: (Purchase) -> String,
    private val getDeviceId: suspend () -> String = { "" },
    private val reconcileCidDid: suspend (accountId: String) -> Unit,
    private val validatePayloadAndFetchIfRequired: suspend (purchaseDtl: PurchaseDetail) -> PurchaseDetail
) {

    companion object {
        private const val TAG = "SubsPurchaseProcessor"
    }

    private fun logd(m: String, msg: String) = Logger.d(LOG_IAB, "$TAG $m: $msg")
    private fun loge(m: String, msg: String, e: Exception? = null) =
        Logger.e(LOG_IAB, "$TAG $m: $msg", e)


    /**
     * Finds the best matching [ProductDetails.SubscriptionOfferDetails] for [purchase].
     *
     * Resolution order:
     * 1. Match by [ProductDetails.SubscriptionOfferDetails.basePlanId] == product-id (exact)
     * 2. Base plan entry (offerId == null; the steady-state plan, no promotional offer)
     * 3. First available offer as fallback
     *
     * Returns null when no offer is cached in [storeProductDetails] for this product,
     * which is expected if [InAppBillingHandler.queryProductDetails] hasn't run yet.
     */
    fun resolveOfferDetails(purchase: Purchase): ProductDetails.SubscriptionOfferDetails? {
        val mname = "resolveOfferDetails"
        val productId = purchase.products.firstOrNull() ?: run {
            loge(mname, "purchase has no products")
            return null
        }

        // All cached entries for this productId that carry offer details
        val candidates = storeProductDetails.filter {
            it.productDetail.productId == productId && it.offerDetails != null
        }

        if (candidates.isEmpty()) {
            logd(mname, "no offer candidates for productId=$productId, product not yet cached")
            return null
        }

        val basePlanEntry = candidates.firstOrNull { it.offerDetails?.offerId == null }
        if (basePlanEntry?.offerDetails != null) {
            logd(mname, "resolved base plan: basePlanId=${basePlanEntry.offerDetails.basePlanId}")
            return basePlanEntry.offerDetails
        }
        val fallback = candidates.first().offerDetails
        logd(mname, "resolved first offer fallback: basePlanId=${fallback?.basePlanId}")
        return fallback
    }

    /**
     * Computes an *estimated* billing expiry for a SUBS purchase.
     *
     * **This value is for display only and MUST NOT gate subscription access.**
     * Google Play is the authoritative source for active/expired status.
     *
     */
    fun calculateExpiryTime(
        purchase: Purchase,
        offerDetails: ProductDetails.SubscriptionOfferDetails?
    ): Long {
        val mname = "calculateExpiryTime"

        if (offerDetails == null) {
            // No offer details cached yet.
            logd(mname, "offerDetails=null → MAX_VALUE (expiry unknown; Play is master)")
            return Long.MAX_VALUE
        }

        val pricingPhases = offerDetails.pricingPhases.pricingPhaseList

        val recurringPhase = pricingPhases
            .lastOrNull { it.recurrenceMode == ProductDetails.RecurrenceMode.INFINITE_RECURRING }
            ?: pricingPhases.lastOrNull()

        val billingPeriod = recurringPhase?.billingPeriod
        if (billingPeriod.isNullOrEmpty()) {
            logd(mname, "billingPeriod empty → MAX_VALUE (unknown; Play is master)")
            return Long.MAX_VALUE
        }

        val cal = Calendar.getInstance().apply { timeInMillis = purchase.purchaseTime }

        val added = addIso8601Period(cal, billingPeriod)
        return if (added) {
            logd(mname, "billingPeriod=$billingPeriod (ISO parsed), expiry=${cal.timeInMillis}")
            cal.timeInMillis
        } else {
            val billingDays = QueryUtils.getBillingPeriodDays(billingPeriod)
            if (billingDays > 0) {
                cal.add(Calendar.DAY_OF_YEAR, billingDays)
                logd(mname, "billingPeriod=$billingPeriod (lookup=${billingDays}d), expiry=${cal.timeInMillis}")
                cal.timeInMillis
            } else {
                logd(mname, "billingPeriod=$billingPeriod unknown → MAX_VALUE (Play is master)")
                Long.MAX_VALUE
            }
        }
    }

    /**
     * Builds a [PurchaseDetail] from a SUBS [Purchase].
     *
     * - planId comes from the subscription offer's basePlanId.
     * - planTitle is derived from the offer's recurring billing period via [QueryUtils].
     * - expiryTime is estimated; Play is master for active/expired gating.
     * - windowDays is the revoke window for the corresponding plan.
     *
     * Returns null only if called with a non-SUBS purchase.
     */
    suspend fun buildPurchaseDetail(purchase: Purchase): PurchaseDetail? {
        val mname = "buildPurchaseDetail"

        // Guard: must only be called for SUBS
        if (getProductType(purchase) != ProductType.SUBS) {
            loge(mname, "called with non-SUBS purchase, error")
            return null
        }

        val offerDetails = resolveOfferDetails(purchase)
        val storeEntry = storeProductDetails.find {
            it.productDetail.productId == purchase.products.firstOrNull() &&
                    it.productDetail.productType == ProductType.SUBS
        }
        val productDetail = storeEntry?.productDetail

        val productId   = purchase.products.firstOrNull().orEmpty()
        val planId      = offerDetails?.basePlanId ?: productDetail?.planId.orEmpty()
        val productTitle = productDetail?.productTitle.orEmpty()
        val planTitle   = offerDetails?.let { queryUtils().getPlanTitle(it) }.orEmpty()
        val expiryTime  = calculateExpiryTime(purchase, offerDetails)
        val accountId   = purchase.accountIdentifiers?.obfuscatedAccountId.orEmpty()

        if (accountId.isEmpty()) {
            loge(mname, "accountId empty for purchase token ${purchase.purchaseToken.take(8)}")
        } else {
            reconcileCidDid(accountId)
        }

        val purchaseDtl =  PurchaseDetail(
            productId          = productId,
            planId             = planId,
            productTitle       = productTitle,
            planTitle          = planTitle,
            state              = purchase.purchaseState,
            purchaseToken      = purchase.purchaseToken,
            productType        = ProductType.SUBS,
            purchaseTime       = purchase.purchaseTime.toFormattedDate(),
            purchaseTimeMillis = purchase.purchaseTime,
            isAutoRenewing     = purchase.isAutoRenewing,
            accountId          = accountId,
            // Never store the real device ID in PurchaseDetail; use sentinel only.
            deviceId           = if (getDeviceId().isNotBlank()) SubscriptionStatus.DEVICE_ID_INDICATOR else "",
            payload            = purchase.developerPayload,
            expiryTime         = expiryTime,
            status             = purchase.purchaseState.toSubscriptionStatusId(),
            windowDays         = resolveRevokeDays(planId),
            orderId            = purchase.orderId ?: ""
        )

        val purchaseDetail = validatePayloadAndFetchIfRequired(purchaseDtl)
        return purchaseDetail
    }

    /**
     * Drives the state machine for a single SUBS [Purchase].
     *
     * @param purchase The Play purchase to process.
     * @param shouldEscalateToServer
     *   `true`  → this token has been seen unacknowledged enough times that we must now call
     *             the server to acknowledge it ourselves.
     *   `false` → first/second sighting; Play may still propagate the ack stay in
     *             PurchasePending and wait for the next query cycle.
     */
    suspend fun process(purchase: Purchase, shouldEscalateToServer: Boolean = false) {
        val mname = "process"
        logd(mname, "SUBS token=${purchase.purchaseToken.take(8)}, " +
                "state=${purchase.purchaseState}, ack=${purchase.isAcknowledged}, " +
                "escalate=$shouldEscalateToServer")

        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> processSubsPurchased(purchase, shouldEscalateToServer)

            Purchase.PurchaseState.PENDING -> {
                logd(mname, "SUBS pending for token=${purchase.purchaseToken.take(8)}")
                try {
                    val pd = buildPurchaseDetail(purchase) ?: return
                    subscriptionStateMachine.completePurchase(pd)
                } catch (e: Exception) {
                    loge(mname, "error handling SUBS pending: ${e.message}", e)
                }
            }

            Purchase.PurchaseState.UNSPECIFIED_STATE -> {
                loge(mname, "SUBS state UNSPECIFIED for token=${purchase.purchaseToken.take(8)}")
                safeNotifyFailed("SUBS purchase state unspecified", null)
            }

            else -> {
                loge(mname, "SUBS unknown state ${purchase.purchaseState} for token=${purchase.purchaseToken.take(8)}")
                safeNotifyFailed("Unknown SUBS purchase state: ${purchase.purchaseState}", null)
            }
        }
    }

    private suspend fun processSubsPurchased(purchase: Purchase, shouldEscalateToServer: Boolean) {
        val mname = "processSubsPurchased"

        if (purchase.isAcknowledged) {
            // Fully settled, drive state machine directly, no server call needed.
            // paymentSuccessful -> handlePaymentSuccessful handles DB upsert
            // and RPN activation (including its dedup guard).
            logd(mname, "SUBS already acknowledged, notifying state machine")
            try {
                val pd = buildPurchaseDetail(purchase) ?: return
                subscriptionStateMachine.paymentSuccessful(pd)
            } catch (e: Exception) {
                loge(mname, "error processing acknowledged SUBS: ${e.message}", e)
                safeNotifyFailed("Error processing acknowledged SUBS: ${e.message}", null)
            }
            return
        }

        // Purchase is not yet acknowledged by Play.
        if (!shouldEscalateToServer) {
            // Below the escalation threshold: Play is still the source of truth.
            // Record the pending state so the state machine doesn't get stuck, then
            // wait for the next queryPurchasesAsync cycle will re-deliver this purchase
            // and InAppBillingHandler will increment the seen-count until escalation.
            logd(mname, "SUBS token=${purchase.purchaseToken.take(8)} unacknowledged " +
                    "but below escalation threshold, staying PurchasePending, " +
                    "waiting for next Play query cycle")
            try {
                val pd = buildPurchaseDetail(purchase) ?: return
                subscriptionStateMachine.completePurchase(pd) // PurchasePending
            } catch (e: Exception) {
                loge(mname, "error recording pending SUBS state: ${e.message}", e)
            }
            return
        }

        // Threshold crossed: server acknowledgement required.
        logd(mname, "SUBS token=${purchase.purchaseToken.take(8)} unacknowledged after " +
                "threshold reached, calling server ack")
        try {
            val accountId = purchase.accountIdentifiers?.obfuscatedAccountId ?: ""
            val deviceId  = getDeviceId()
            if (accountId.isEmpty() || deviceId.isEmpty()) {
                loge(mname, "accountId(${accountId.length})/deviceId(${deviceId.length}) empty")
            }
            val (ackOk, developerPayload) = if (purchase.developerPayload.isEmpty()) {
                acknowledgePurchase(accountId, deviceId, purchase.purchaseToken, ProductType.SUBS)
            } else {
                Pair(true, purchase.developerPayload)
            }
            val pd = buildPurchaseDetail(purchase) ?: return
            val pdWithPayload = pd.copy(
                payload = if (ackOk && developerPayload.isNotEmpty()) developerPayload
                else pd.payload
            )

            if (ackOk) {
                logd(mname, "SUBS token=${purchase.purchaseToken.take(8)} server ack succeeded")
                // paymentSuccessful -> handlePaymentSuccessful handles the full DB
                // upsert, state-machine transition to Active, and RPN activation.
                // Skip completePurchase to prevent a redundant DB write + the
                // transient PurchasePending state between the two calls.
                subscriptionStateMachine.paymentSuccessful(pdWithPayload)
            } else {
                loge(mname, "SUBS token=${purchase.purchaseToken.take(8)} server ack failed, payload: $developerPayload")
                subscriptionStateMachine.purchaseFailed(
                    "Server acknowledgement failed: $developerPayload", null
                )
            }
        } catch (e: Exception) {
            loge(mname, "error acknowledging SUBS: ${e.message}", e)
            safeNotifyFailed("SUBS acknowledgement error: ${e.message}", null)
        }
    }

    private suspend fun safeNotifyFailed(reason: String, billingResult: com.android.billingclient.api.BillingResult?) {
        try { subscriptionStateMachine.purchaseFailed(reason, billingResult?.responseCode) }
        catch (e: Exception) { loge("safeNotifyFailed", "state machine error: ${e.message}", e) }
    }

    /**
     * Parses an ISO-8601 duration string (e.g. P1M, P1Y, P2W, P30D) and adds it
     * to [cal] in-place.  Returns true on success, false if the string is unrecognised.
     */
    private fun addIso8601Period(cal: Calendar, period: String): Boolean {
        val regex = Regex("""^P(?:(\d+)Y)?(?:(\d+)M)?(?:(\d+)W)?(?:(\d+)D)?$""")
        val match = regex.matchEntire(period) ?: return false
        val years  = match.groupValues[1].toIntOrNull() ?: 0
        val months = match.groupValues[2].toIntOrNull() ?: 0
        val weeks  = match.groupValues[3].toIntOrNull() ?: 0
        val days   = match.groupValues[4].toIntOrNull() ?: 0
        if (years == 0 && months == 0 && weeks == 0 && days == 0) return false
        if (years  > 0) cal.add(Calendar.YEAR,         years)
        if (months > 0) cal.add(Calendar.MONTH,        months)
        if (weeks  > 0) cal.add(Calendar.WEEK_OF_YEAR, weeks)
        if (days   > 0) cal.add(Calendar.DAY_OF_YEAR,  days)
        return true
    }

    private fun resolveRevokeDays(planId: String): Int = when {
        planId == InAppBillingHandler.SUBS_PRODUCT_YEARLY -> InAppBillingHandler.REVOKE_WINDOW_SUBS_YEARLY_DAYS
        planId.contains("yearly", ignoreCase = true) -> InAppBillingHandler.REVOKE_WINDOW_SUBS_YEARLY_DAYS
        planId.contains("annual", ignoreCase = true) -> InAppBillingHandler.REVOKE_WINDOW_SUBS_YEARLY_DAYS
        else -> InAppBillingHandler.REVOKE_WINDOW_SUBS_MONTHLY_DAYS
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
