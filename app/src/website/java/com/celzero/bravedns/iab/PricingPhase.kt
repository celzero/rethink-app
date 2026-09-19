/*
 * Copyright 2025 RethinkDNS and its authors
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
    /**
     * Offer discount percentage for one-time (INAPP) purchase options, derived by
     * [InAppBillingHandler] from either Play offer type: percentage offers
     * (DiscountDisplayInfo.percentageDiscount) or absolute/fixed-amount offers
     * (DiscountDisplayInfo.discountAmount), with fullPriceMicros as fallback.
     * 0 means no offer/discount. Always 0 for SUBS phases (their discounts are
     * expressed via DISCOUNTED phases).
     */
    var discountPercent: Int = 0,
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
        discountPercent = 0,
    )
}
