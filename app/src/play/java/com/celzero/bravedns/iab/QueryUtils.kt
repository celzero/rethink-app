package com.celzero.bravedns.iab

import Logger.LOG_IAB
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.celzero.bravedns.iab.InAppBillingHandler.BestPlan
import com.celzero.bravedns.iab.InAppBillingHandler.fetchPurchases
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

internal class QueryUtils(private val billingClient: BillingClient) {
    companion object {
        private const val TAG = LOG_IAB
    }

    private fun logd(methodName: String, msg: String) {
        Logger.d(TAG, "$methodName: $msg")
    }

    private fun logv(methodName: String, msg: String) {
        Logger.v(TAG, "$methodName: $msg")
    }

    private fun loge(methodName: String, msg: String, e: Exception? = null) {
        Logger.e(TAG, "$methodName: $msg", e)
    }

    private fun log(methodName: String, msg: String) {
        Logger.i(TAG, "$methodName: $msg")
    }

    fun getPurchaseParams(
        productType: String,
        productIdList: List<String>
    ): List<QueryProductDetailsParams.Product> {
        return productIdList.map { productId ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(productId)
                .setProductType(productType)
                .build()
        }
    }

    fun getProductParams(userQueryList: List<Pair<String, String>>): List<QueryProductDetailsParams.Product> {
        return userQueryList.map {
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(it.second)
                .setProductType(it.first)
                .build()
        }
    }

    suspend fun queryProductDetailsAsync(params: List<QueryProductDetailsParams.Product>): List<ProductDetails> {
        val mname = this::queryProductDetailsAsync.name
        if (billingClient.isReady.not()) {
            return emptyList()
        }
        if (params.isEmpty()) {
            return emptyList()
        }
        val queryParams = QueryProductDetailsParams.newBuilder().setProductList(params).build()
        return suspendCancellableCoroutine { continuation ->
            billingClient.queryProductDetailsAsync(queryParams) { billingResult, productDetailsList ->
                if (BillingResponse(billingResult.responseCode).isOk) {
                    if (continuation.isActive) {
                        try {
                            continuation.resume(productDetailsList)
                        } catch (ignore: Exception) {
                        }
                    }
                } else {
                    loge(mname, "err, response code not ok. code: ${billingResult.responseCode}")
                    if (continuation.isActive) {
                        try {
                            val list = ArrayList<ProductDetails>()
                            continuation.resume(list)
                        } catch (ignore: Exception) {
                        }
                    }
                }
            }
        }
    }

    fun getPlanId(subscriptionOfferDetailList: MutableList<ProductDetails.SubscriptionOfferDetails>?): String {
        val mname = this::getPlanId.name
        return try {
            subscriptionOfferDetailList?.let { offerList ->
                val offer = offerList.find { it.basePlanId.isNotEmpty() }
                offer?.basePlanId
                    ?: throw NullPointerException("Not a valid SubscriptionOfferDetails list")
            } ?: throw NullPointerException("SubscriptionOfferDetails list is null")
        } catch (ex: Exception) {
            loge(mname, "err(planId): returning empty", ex)
            ""
        }
    }

    fun getPlanTitle(subscriptionOfferDetailList: ProductDetails.SubscriptionOfferDetails): String {
        val pricingPhase = getPricingOffer(subscriptionOfferDetailList)
        return when (pricingPhase?.billingPeriod) {
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
    }

    fun getPlanTitle(billingPeriod: String): String {
        return when (billingPeriod) {
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
    }

    fun getTrialDay(subscriptionOfferDetailList: ProductDetails.SubscriptionOfferDetails): Int {
        val pricingPhase = getPricingOffer(subscriptionOfferDetailList)
        return when (pricingPhase?.billingPeriod) {
            "P1D" -> 1
            "P2D" -> 2
            "P3D" -> 3
            "P4D" -> 4
            "P5D" -> 5
            "P6D" -> 6
            "P7D" -> 7
            "P1W" -> 7
            "P2W" -> 14
            "P3W" -> 21
            "P4W" -> 28
            "P1M" -> 30
            else -> 0
        }
    }

    fun getBestPlan(subscriptionOfferDetailList: MutableList<ProductDetails.SubscriptionOfferDetails>): BestPlan {
        if (subscriptionOfferDetailList.isEmpty()) {
            return BestPlan(0, null)
        }
        if (subscriptionOfferDetailList.size == 1) {
            val leastPricingPhase: ProductDetails.PricingPhase? =
                getPricingOffer(subscriptionOfferDetailList[0])
            return BestPlan(0, leastPricingPhase)
        }
        // Offers available
        var trialDays = 0
        val trialPricingPhase = getPricingOffer(subscriptionOfferDetailList[0])
        val regularPricingPhase: ProductDetails.PricingPhase? =
            getPricingOffer(subscriptionOfferDetailList[1])

        if (trialPricingPhase?.priceAmountMicros == 0L) {
            trialDays = when (trialPricingPhase.billingPeriod) {
                "P1D" -> 1
                "P2D" -> 2
                "P3D" -> 3
                "P4D" -> 4
                "P5D" -> 5
                "P6D" -> 6
                "P7D" -> 7
                "P1W" -> 7
                "P2W" -> 14
                "P3W" -> 21
                "P4W" -> 28
                "P1M" -> 30
                else -> 0
            }
        }
        return BestPlan(trialDays, regularPricingPhase)
    }

    private fun getPricingOffer(offer: ProductDetails.SubscriptionOfferDetails): ProductDetails.PricingPhase? {
        if (offer.pricingPhases.pricingPhaseList.size == 1) {
            return offer.pricingPhases.pricingPhaseList[0]
        }
        var leastPricingPhase: ProductDetails.PricingPhase? = null
        var lowestPrice = Int.MAX_VALUE
        offer.pricingPhases.pricingPhaseList.forEach { pricingPhase ->
            if (pricingPhase.priceAmountMicros < lowestPrice) {
                lowestPrice = pricingPhase.priceAmountMicros.toInt()
                leastPricingPhase = pricingPhase
            }
        }
        return leastPricingPhase
    }

    fun getOfferToken(
        subscriptionOfferDetails: List<ProductDetails.SubscriptionOfferDetails>?,
        planId: String
    ): String {
        val eligibleOffers = arrayListOf<ProductDetails.SubscriptionOfferDetails>()
        subscriptionOfferDetails?.forEach { offerDetail ->
            if (offerDetail.offerTags.contains(planId)) {
                eligibleOffers.add(offerDetail)
            }
        }

        var offerToken = String()
        var leastPricedOffer: ProductDetails.SubscriptionOfferDetails
        var lowestPrice = Int.MAX_VALUE

        eligibleOffers.forEach { offer ->
            for (price in offer.pricingPhases.pricingPhaseList) {
                if (price.priceAmountMicros < lowestPrice) {
                    lowestPrice = price.priceAmountMicros.toInt()
                    leastPricedOffer = offer
                    offerToken = leastPricedOffer.offerToken
                }
            }
        }

        return offerToken
    }

    fun checkForAcknowledgements(purchases: List<Purchase>) {
        val mname = "checkForAcknowledgements(1)"
        val count = purchases.count { it.isAcknowledged.not() }
        log(mname, "$count purchase(s) needs to be acknowledge")

        // Start acknowledging...
        purchases.forEach { purchase ->
            if (purchase.isAcknowledged.not()) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                billingClient.acknowledgePurchase(acknowledgePurchaseParams.build()) { billingResult ->
                    logd(mname, "acknowledging purchase for ${purchase.products}, code: ${billingResult.responseCode}")
                    when (BillingResponse(billingResult.responseCode).isOk) {
                        true -> {
                            logd(mname, "payment acknowledged for ${purchase.products}")
                            fetchPurchases(listOf(BillingClient.ProductType.SUBS))
                        }

                        false -> loge(mname, "acknowledgement failed for ${purchase.products}")
                    }
                }
            }
        }
    }

    fun checkForAcknowledgements(purchases: List<Purchase>, consumableList: List<String>) {
        val mname = "checkForAcknowledgements(2)"
        val count = purchases.count { it.isAcknowledged.not() }
        log(mname, "$count purchase(s) needs to be acknowledge")

        // Start acknowledging...
        purchases.forEach { purchase ->
            if (purchase.isAcknowledged.not()) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                billingClient.acknowledgePurchase(acknowledgePurchaseParams.build()) { billingResult ->
                    logd(mname, "acknowledging purchase for ${purchase.products}, code: ${billingResult.responseCode}")
                    consumeProduct(purchase, consumableList)
                    when (BillingResponse(billingResult.responseCode).isOk) {
                        true -> {
                            logd(mname, "payment acknowledged for ${purchase.products}")
                            fetchPurchases(listOf(BillingClient.ProductType.SUBS))
                        }

                        false -> loge(mname, "acknowledgement failed for ${purchase.products}")
                    }
                }
            } else {
                consumeProduct(purchase, consumableList)
            }
        }
    }

    private fun consumeProduct(purchase: Purchase, consumableList: List<String>) {
        val mname = this::consumeProduct.name
        CoroutineScope(Dispatchers.IO).launch {
            var isConsumable = false
            purchase.products.forEach inner@{
                if (consumableList.contains(it)) {
                    isConsumable = true
                    return@inner
                }
            }
            if (isConsumable.not()) return@launch

            // Consume only consumable products given by developers
            val consumeParams =
                ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
            billingClient.consumeAsync(consumeParams) { billingResult, _ ->
                logd(mname, "consumed: code: ${billingResult.responseCode}; msg: ${billingResult.debugMessage}")
            }
        }
    }

    fun checkForAcknowledgementsAndConsumable(
        purchases: List<Purchase>,
        callback: (Boolean) -> Unit
    ) {
        val mname = this::checkForAcknowledgementsAndConsumable.name
        val count = purchases.count { it.isAcknowledged.not() }
        log(mname, "$count purchase(s) needs to be acknowledge")

        val hashMap = HashMap<Int, Boolean>()

        // Start acknowledging...
        purchases.forEachIndexed { index, purchase ->
            if (Utilities.isAtleastN()) {
                hashMap.putIfAbsent(index, false)
            } else {
                if (hashMap.containsKey(index).not()) {
                    hashMap[index] = false
                }
            }
            if (purchase.isAcknowledged.not()) {
                val acknowledgePurchaseParams = AcknowledgePurchaseParams.newBuilder()
                    .setPurchaseToken(purchase.purchaseToken)
                billingClient.acknowledgePurchase(acknowledgePurchaseParams.build()) { billingResult ->
                    consumeProductPurchase(purchase, hashMap, index, callback)
                    when (BillingResponse(billingResult.responseCode).isOk) {
                        true -> logd(mname, "payment acknowledged for ${purchase.products}")
                        false -> loge(mname, "payment not acknowledged for ${purchase.products}")
                    }
                }
            } else {
                consumeProductPurchase(purchase, hashMap, index, callback)
            }
        }
    }

    private fun consumeProductPurchase(
        purchase: Purchase,
        hashMap: HashMap<Int, Boolean>,
        index: Int,
        callback: (Boolean) -> Unit
    ) {
        val mname = this::consumeProductPurchase.name
        val consumeParams =
            ConsumeParams.newBuilder().setPurchaseToken(purchase.purchaseToken).build()
        billingClient.consumeAsync(consumeParams) { billingResult, _ ->
            logd(mname, "consumed: code: ${billingResult.responseCode}; msg: ${billingResult.debugMessage}")
            hashMap[index] = true
            hashMap.forEach {
                if (!it.value) {
                    return@consumeAsync
                }
            }
            callback.invoke(BillingResponse(billingResult.responseCode).isOk)
        }
    }
}
