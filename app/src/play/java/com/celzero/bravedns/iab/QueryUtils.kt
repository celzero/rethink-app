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

import Logger
import Logger.LOG_IAB
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.celzero.bravedns.iab.InAppBillingHandler.BestPlan
import com.celzero.bravedns.iab.InAppBillingHandler.fetchPurchases
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.collections.filter
import kotlin.collections.find
import kotlin.coroutines.resume

internal class QueryUtils(private val billingClient: BillingClient) {
    companion object {
        private const val TAG = LOG_IAB

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
            "P2Y" -> "2 years"
            "P5Y" -> "5 years"
            else -> ""
        }

        fun getBillingPeriodDays(billingPeriod: String): Int = when (billingPeriod) {
            "P1W" -> 7
            "P4W" -> 28
            "P1M" -> 30
            "P2M" -> 60
            "P3M" -> 90
            "P4M" -> 120
            "P6M" -> 180
            "P8M" -> 240
            "P1Y" -> 365
            "P2Y" -> 730
            "P5Y" -> 1825
            else -> 0
        }
    }

    private fun logd(methodName: String, msg: String) = Logger.d(TAG, "$methodName: $msg")
    private fun logv(methodName: String, msg: String) = Logger.v(TAG, "$methodName: $msg")
    private fun loge(methodName: String, msg: String, e: Exception? = null) =
        Logger.e(TAG, "$methodName: $msg", e)

    private fun log(methodName: String, msg: String) = Logger.i(TAG, "$methodName: $msg")

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

    fun getTrialDay(offer: ProductDetails.SubscriptionOfferDetails): Int =
        when (getPricingOffer(offer)?.billingPeriod) {
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

    fun getOfferToken(
        offerList: List<ProductDetails.SubscriptionOfferDetails>?,
        planId: String
    ): String {
        val eligibleOffers = offerList?.filter { it.offerTags.contains(planId) } ?: return ""

        val leastPricedOffer = eligibleOffers.minByOrNull {
            it.pricingPhases.pricingPhaseList.minOfOrNull { phase -> phase.priceAmountMicros }
                ?: Long.MAX_VALUE
        }

        return leastPricedOffer?.offerToken ?: ""
    }

    private fun consumeProductPurchase(
        purchase: Purchase,
        map: MutableMap<Int, Boolean>,
        index: Int,
        callback: (Boolean) -> Unit
    ) {
        val mname = ::consumeProductPurchase.name
        val params = ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        billingClient.consumeAsync(params) { result, _ ->
            logd(mname, "consumed: code: ${result.responseCode}; msg: ${result.debugMessage}")
            map[index] = true
            if (map.values.all { it }) callback(BillingResponse(result.responseCode).isOk)
        }
    }
}
