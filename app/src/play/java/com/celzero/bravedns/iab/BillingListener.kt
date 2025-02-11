package com.celzero.bravedns.iab

interface BillingListener {
    fun onConnectionResult(isSuccess: Boolean, message: String)
    fun purchasesResult(isSuccess: Boolean, purchaseDetailList: List<PurchaseDetail>)
    fun productResult(isSuccess: Boolean,productList: List<ProductDetail>)
}
