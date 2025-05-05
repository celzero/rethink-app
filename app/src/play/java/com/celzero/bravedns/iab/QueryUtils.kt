/*
 * Copyright 2024 RethinkDNS and its authors
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

import Logger.LOG_IAB
import com.android.billingclient.api.*
import com.celzero.bravedns.iab.InAppBillingHandler.BestPlan
import com.celzero.bravedns.iab.InAppBillingHandler.fetchPurchases
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class QueryUtils(private val billingClient: BillingClient) {
    companion object {
        private const val TAG = LOG_IAB
    }

    private fun logd(methodName: String, msg: String) = Logger.d(TAG, "$methodName: $msg")
    private fun logv(methodName: String, msg: String) = Logger.v(TAG, "$methodName: $msg")
    private fun loge(methodName: String, msg: String, e: Exception? = null) = Logger.e(TAG, "$methodName: $msg", e)
    private fun log(methodName: String, msg: String) = Logger.i(TAG, "$methodName: $msg")

    fun getPurchaseParams(productType: String, productIdList: List<String>): List<QueryProductDetailsParams.Product> =
        productIdList.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(productType)
                .build()
        }

    fun getProductParams(userQueryList: List<Pair<String, String>>): List<QueryProductDetailsParams.Product> =
        userQueryList.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it.second)
                .setProductType(it.first)
                .build()
        }

    suspend fun queryProductDetailsAsync(params: List<QueryProductDetailsParams.Product>): List<ProductDetails> {
        val mname = ::queryProductDetailsAsync.name
        if (!billingClient.isReady) {
            loge(mname, "billing client not ready")
            return emptyList()
        }
        if (params.isEmpty()) return emptyList()

        val queryParams = QueryProductDetailsParams.newBuilder().setProductList(params).build()

        return suspendCancellableCoroutine { continuation ->
            billingClient.queryProductDetailsAsync(queryParams) { billingResult, productDetailsList ->
                if (BillingResponse(billingResult.responseCode).isOk) {
                    if (continuation.isActive) continuation.resume(productDetailsList)
                } else {
                    loge(mname, "err, response code not ok. code: ${billingResult.responseCode}")
                    if (continuation.isActive) continuation.resume(emptyList())
                }
            }
        }
    }

    fun getPlanId(subscriptionOfferDetailList: MutableList<ProductDetails.SubscriptionOfferDetails>?): String {
        val mname = ::getPlanId.name
        return try {
            subscriptionOfferDetailList?.find { it.basePlanId.isNotEmpty() }?.basePlanId
                ?: throw NullPointerException("Not a valid SubscriptionOfferDetails list")
        } catch (ex: Exception) {
            loge(mname, "err(planId): returning empty", ex)
            ""
        }
    }

    fun getPlanTitle(subscriptionOfferDetail: ProductDetails.SubscriptionOfferDetails): String =
        getPlanTitle(getPricingOffer(subscriptionOfferDetail)?.billingPeriod ?: "")

    fun getPlanTitle(billingPeriod: String): String = when (billingPeriod) {
        "P1W" -> "Weekly"
        "P4W" -> "Four weeks"
        "P1M" -> "Monthly"
        "P2M" -> "2 months"
        "P3M" -> "3 months"
        "P4M" -> "4 months"
        "P6M" -> "6 months"
        "P8M" -> "8 months"
        "P1Y" -> "Yearly"
        else -> ""
    }

    fun getTrialDay(offer: ProductDetails.SubscriptionOfferDetails): Int = when (getPricingOffer(offer)?.billingPeriod) {
        "P1D" -> 1
        "P2D" -> 2
        "P3D" -> 3
        "P4D" -> 4
        "P5D" -> 5
        "P6D" -> 6
        "P7D", "P1W" -> 7
        "P2W" -> 14
        "P3W" -> 21
        "P4W" -> 28
        "P1M" -> 30
        else -> 0
    }

    fun getBestPlan(offers: MutableList<ProductDetails.SubscriptionOfferDetails>): BestPlan {
        if (offers.isEmpty()) return BestPlan(0, null)
        if (offers.size == 1) return BestPlan(0, getPricingOffer(offers[0]))

        val trialPricingPhase = getPricingOffer(offers[0])
        val regularPricingPhase = getPricingOffer(offers[1])

        val trialDays = when (trialPricingPhase?.priceAmountMicros) {
            0L -> getTrialDay(offers[0])
            else -> 0
        }

        return BestPlan(trialDays, regularPricingPhase)
    }

    private fun getPricingOffer(offer: ProductDetails.SubscriptionOfferDetails): ProductDetails.PricingPhase? =
        offer.pricingPhases.pricingPhaseList.minByOrNull { it.priceAmountMicros }

    fun getOfferToken(offerList: List<ProductDetails.SubscriptionOfferDetails>?, planId: String): String {
        val eligibleOffers = offerList?.filter { it.offerTags.contains(planId) } ?: return ""

        val leastPricedOffer = eligibleOffers.minByOrNull {
            it.pricingPhases.pricingPhaseList.minOfOrNull { phase -> phase.priceAmountMicros } ?: Long.MAX_VALUE
        }

        return leastPricedOffer?.offerToken ?: ""
    }

    fun checkForAcknowledgements(purchases: List<Purchase>) {
        val mname = "checkForAcknowledgements"
        log(mname, "${purchases.count { !it.isAcknowledged }} purchase(s) need acknowledgement")
        purchases.filter { !it.isAcknowledged }.forEach { acknowledge(mname, it) }
    }

    private fun acknowledge(mname: String, purchase: Purchase, attempt: Int = 0) {
        if (attempt > 5) {
            loge(mname, "acknowledge failed for ${purchase.products}, after 5 attempts")
            return
        }

        val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        billingClient.acknowledgePurchase(params) { result ->
            logd(mname, "acknowledging purchase for ${purchase.products}, code: ${result.responseCode}")
            if (BillingResponse(result.responseCode).isOk) {
                logd(mname, "payment acknowledged for ${purchase.products}")
                fetchPurchases(listOf(BillingClient.ProductType.SUBS))
            } else if (BillingResponse(result.responseCode).serviceDisconnected) {
                acknowledge(mname, purchase, attempt + 1)
            } else {
                loge(mname, "acknowledgement failed for ${purchase.products}")
            }
        }
    }

    fun checkForAcknowledgementsAndConsumable(purchases: List<Purchase>, callback: (Boolean) -> Unit) {
        val mname = ::checkForAcknowledgementsAndConsumable.name
        val needed = purchases.count { !it.isAcknowledged }
        log(mname, "$needed purchase(s) needs acknowledgement")

        val completionMap = mutableMapOf<Int, Boolean>()
        purchases.forEachIndexed { index, purchase ->
            completionMap[index] = false

            if (!purchase.isAcknowledged) {
                val params = AcknowledgePurchaseParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
                billingClient.acknowledgePurchase(params) {
                    consumeProductPurchase(purchase, completionMap, index, callback)
                    if (BillingResponse(it.responseCode).isOk) {
                        logd(mname, "payment acknowledged for ${purchase.products}")
                    } else {
                        loge(mname, "payment not acknowledged for ${purchase.products}")
                    }
                }
            } else {
                consumeProductPurchase(purchase, completionMap, index, callback)
            }
        }
    }

    private fun consumeProductPurchase(purchase: Purchase, map: MutableMap<Int, Boolean>, index: Int, callback: (Boolean) -> Unit) {
        val mname = ::consumeProductPurchase.name
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        billingClient.consumeAsync(params) { result, _ ->
            logd(mname, "consumed: code: ${result.responseCode}; msg: ${result.debugMessage}")
            map[index] = true
            if (map.values.all { it }) callback(BillingResponse(result.responseCode).isOk)
        }
    }
}
