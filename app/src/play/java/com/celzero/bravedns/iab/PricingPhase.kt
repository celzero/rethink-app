package com.celzero.bravedns.iab

import com.celzero.bravedns.iab.InAppBillingHandler.RecurringMode

data class PricingPhase(
    var recurringMode: RecurringMode,
    var price: String,
    var currencyCode: String,
    var planTitle: String,
    var billingCycleCount: Int,
    var billingPeriod: String,
    var priceAmountMicros: Long,
    var freeTrialPeriod: Int,
) {
    constructor() : this(
        recurringMode = RecurringMode.ORIGINAL,
        price = "",
        currencyCode = "",
        planTitle = "",
        billingCycleCount = 0,
        billingPeriod = "",
        priceAmountMicros = 0,
        freeTrialPeriod = 0,
    )
}
