package com.celzero.bravedns.iab

import com.android.billingclient.api.ProductDetails

data class QueryProductDetail(
    val productDetail: ProductDetail,
    val productDetails: ProductDetails,
    val offerDetails: ProductDetails.SubscriptionOfferDetails?,
    val oneTimeOfferDetails: ProductDetails.OneTimePurchaseOfferDetails?
)
