package com.celzero.bravedns.iab


data class PurchaseDetail(
    val productId: String,
    var planId: String,
    var productTitle: String,
    var state: Int,
    var planTitle: String,
    val purchaseToken: String,
    val productType: String,
    val purchaseTime: String,
    val purchaseTimeMillis: Long,
    val isAutoRenewing: Boolean,
    val accountId: String,
    val payload: String,
    val expiryTime: Long,
    val status: Int
)
