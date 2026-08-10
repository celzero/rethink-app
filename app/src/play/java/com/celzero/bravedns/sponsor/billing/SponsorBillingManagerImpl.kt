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
package com.celzero.bravedns.sponsor.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.ProductType
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.celzero.bravedns.util.Logger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class SponsorBillingManagerImpl(context: Context) : SponsorBillingManager {

    companion object {
        private const val TAG = "SponsorBilling"
    }

    private val appContext: Context = context.applicationContext

    private var billingClient: BillingClient? = null

    private val _products = MutableStateFlow<List<SponsorProduct>>(emptyList())
    override val products: Flow<List<SponsorProduct>> = _products.asStateFlow()

    private val _purchaseResult = MutableSharedFlow<SponsorPurchaseResult>(extraBufferCapacity = 3)
    override val purchaseResult: Flow<SponsorPurchaseResult> = _purchaseResult.asSharedFlow()

    private val _isBillingReady = MutableStateFlow(false)
    override val isBillingReady: Flow<Boolean> = _isBillingReady.asStateFlow()

    private var isInitialized = false

    private val purchaseListener = PurchasesUpdatedListener { billingResult, purchases ->
        onPurchasesUpdated(billingResult, purchases)
    }

    override fun initialize() {
        if (isInitialized) return
        isInitialized = true
        setupBillingClient()
    }

    private fun setupBillingClient() {
        try {
            billingClient = BillingClient.newBuilder(appContext)
                .setListener(purchaseListener)
                .enablePendingPurchases(
                    PendingPurchasesParams.newBuilder().enableOneTimeProducts().enablePrepaidPlans()
                        .build())
                .build()

            billingClient?.startConnection(object : com.android.billingclient.api.BillingClientStateListener {
                override fun onBillingSetupFinished(billingResult: BillingResult) {
                    if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                        _isBillingReady.value = true
                        queryExistingPurchases()
                        queryProducts()
                    } else {
                        _isBillingReady.value = false
                    }
                }
                override fun onBillingServiceDisconnected() {
                    _isBillingReady.value = false
                }
            })
        } catch (e: Exception) {
            Logger.e(TAG, "Failed to setup billing client: ${e.message}", e)
            _purchaseResult.tryEmit(SponsorPurchaseResult.BillingUnavailable)
        }
    }

    override fun queryProducts() {
        val client = billingClient ?: return
        if (!client.isReady) return

        // Sponsorship is a single INAPP product exposed through several fixed-price
        // purchase options (offers). One ProductDetails is returned, but it carries
        // one offer per contribution level (ProductDetails.oneTimePurchaseOfferDetailsList);
        // flatten it into one SponsorProduct per known tier so the UI can show
        // localized prices for every amount.
        val productList = SponsorProductIds.ALL_PRODUCT_IDS.map { id ->
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(id)
                .setProductType(ProductType.INAPP)
                .build()
        }

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        client.queryProductDetailsAsync(params) { _: BillingResult, result ->
            val products = result.productDetailsList.flatMap { pd ->
                pd.oneTimePurchaseOfferDetailsList.orEmpty()
                    // Keep only the offers whose id maps to a known tier.
                    .filter { SponsorProductIds.amountForOffer(it.purchaseOptionId ?: it.offerId) != null }
                    .map { it.toSponsorProduct(pd) }
            }
            _products.value = products
        }
    }

    override fun launchBillingFlow(activity: Activity, amount: Int) {
        val client = billingClient
        if (client == null || !client.isReady) {
            _purchaseResult.tryEmit(SponsorPurchaseResult.BillingUnavailable)
            return
        }

        // Sponsorship is a single product; the requested [amount] selects one of its
        // purchase options (offers). The offer id encodes the dollar value (e.g.
        // "sponsor-5"), so resolve it and pass the matching offerToken to the flow.
        val targetOfferId = SponsorProductIds.offerIdFor(amount)

        val prodParam = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(SponsorProductIds.PRODUCT_ID)
            .setProductType(ProductType.INAPP)
            .build()

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(prodParam))
            .build()

        client.queryProductDetailsAsync(params) { _: BillingResult, detailsResult ->
            val productDetails = detailsResult.productDetailsList.firstOrNull()
            if (productDetails == null) {
                _purchaseResult.tryEmit(SponsorPurchaseResult.Error("Product not found"))
                return@queryProductDetailsAsync
            }

            // match the offer by id (purchaseOptionId first, then offerId), falling
            // back to the first available offer if the exact tier is somehow missing.
            val offers = productDetails.oneTimePurchaseOfferDetailsList.orEmpty()
            val offer = offers.firstOrNull { (it.purchaseOptionId ?: it.offerId) == targetOfferId }
                ?: offers.firstOrNull()
            if (offer == null) {
                _purchaseResult.tryEmit(SponsorPurchaseResult.Error("No sponsor offer available"))
                return@queryProductDetailsAsync
            }

            // The offerToken is what actually selects the purchase option in the flow.
            val offerToken = offer.offerToken
            if (offerToken.isNullOrEmpty()) {
                _purchaseResult.tryEmit(SponsorPurchaseResult.Error("Sponsor offer token unavailable"))
                return@queryProductDetailsAsync
            }

            val productParams = BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .setOfferToken(offerToken)
                .build()

            val flowParams = BillingFlowParams.newBuilder()
                .setProductDetailsParamsList(listOf(productParams))
                .build()

            val billingResult = client.launchBillingFlow(activity, flowParams)

            if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                val error = when (billingResult.responseCode) {
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> SponsorPurchaseResult.AlreadyOwned
                    BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> SponsorPurchaseResult.BillingUnavailable
                    BillingClient.BillingResponseCode.USER_CANCELED -> SponsorPurchaseResult.Cancelled
                    else -> SponsorPurchaseResult.Error(billingResult.debugMessage, billingResult.responseCode)
                }
                _purchaseResult.tryEmit(error)
            }
        }
    }

    override fun consumePurchase(purchaseToken: String) {
        val client = billingClient ?: return
        if (!client.isReady) return

        val consumeParams = ConsumeParams.newBuilder().setPurchaseToken(purchaseToken).build()
        client.consumeAsync(consumeParams) { result, _ ->
            if (result.responseCode != BillingClient.BillingResponseCode.OK) {
                Logger.e(TAG, "Consume failed: ${result.debugMessage}")
            }
        }
    }

    override fun destroy() {
        try { billingClient?.endConnection() } catch (_: Exception) { }
        billingClient = null
        isInitialized = false
    }

    private fun onPurchasesUpdated(billingResult: BillingResult, purchases: MutableList<Purchase>?) {
        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                if (!purchases.isNullOrEmpty()) handlePurchases(purchases)
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> _purchaseResult.tryEmit(SponsorPurchaseResult.Cancelled)
            BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> queryExistingPurchases()
            else -> _purchaseResult.tryEmit(SponsorPurchaseResult.Error(billingResult.debugMessage, billingResult.responseCode))
        }
    }

    private fun handlePurchases(purchases: List<Purchase>) {
        purchases.forEach { purchase ->
            when (purchase.purchaseState) {
                Purchase.PurchaseState.PURCHASED -> {
                    // Forward the authoritative purchaseTime/token/productId so the
                    // repository records the real purchase time, not wall-clock now.
                    _purchaseResult.tryEmit(
                        SponsorPurchaseResult.Success(
                            purchaseTime = purchase.purchaseTime,
                            purchaseToken = purchase.purchaseToken,
                            productId = purchase.products.firstOrNull().orEmpty()
                        )
                    )
                    if (!purchase.isAcknowledged) {
                        val ackParams = AcknowledgePurchaseParams.newBuilder()
                            .setPurchaseToken(purchase.purchaseToken).build()
                        billingClient?.acknowledgePurchase(ackParams) { _ -> }
                    }
                    // Sponsorship is a one-time INAPP product. Consume it immediately on
                    // success so the SKU is re-purchasable (contributors can give again),
                    // and so the purchase doesn't linger as an un-consumed entitlement.
                    consumePurchase(purchase.purchaseToken)
                }
                Purchase.PurchaseState.PENDING -> _purchaseResult.tryEmit(SponsorPurchaseResult.Pending)
                else -> {}
            }
        }
    }

    private fun queryExistingPurchases() {
        val client = billingClient ?: return
        if (!client.isReady) return

        val params = QueryPurchasesParams.newBuilder().setProductType(ProductType.INAPP).build()
        client.queryPurchasesAsync(params) { _, purchaseResult ->
            handlePurchases(purchaseResult)
        }
    }

    private fun ProductDetails.OneTimePurchaseOfferDetails.toSponsorProduct(pd: ProductDetails): SponsorProduct {
        return SponsorProduct(
            productId = pd.productId,
            title = pd.title,
            description = pd.description,
            price = formattedPrice,
            priceMicros = priceAmountMicros
        )
    }
}
