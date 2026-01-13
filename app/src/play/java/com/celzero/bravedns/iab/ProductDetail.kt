package com.celzero.bravedns.iab

import com.android.billingclient.api.BillingClient


data class ProductDetail(
    var productId: String,
    var planId: String,
    var productTitle: String,
    var productType: String,
    var pricingDetails: List<PricingPhase>

) {
    constructor() : this(
        productId = "",
        planId = "",
        productTitle = "",
        productType = BillingClient.ProductType.SUBS,
        pricingDetails = listOf(),
    )
}
