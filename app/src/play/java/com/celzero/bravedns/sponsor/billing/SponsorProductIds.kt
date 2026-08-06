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

object SponsorProductIds {
    /**
     * Sponsorship is exposed as a SINGLE one-time INAPP product ([PRODUCT_ID]) with
     * several fixed-price "purchase options" (offers). Each offer id encodes its
     * contribution amount (e.g. "sponsor-5" -> $5).
     *
     * In Play Console this is one product ("sponsor.tier.prod") with one offer per
     * amount. The Billing Library surfaces these offers as
     * `ProductDetails.oneTimePurchaseOfferDetailsList`, each carrying its own price
     * and offerToken; the client selects the desired amount by passing that offer's
     * offerToken to the billing flow.
     */
    const val PRODUCT_ID = "sponsor.tier.prod"

    /** Offer (purchase-option) id for each contribution level. */
    private val AMOUNT_TO_OFFER = mapOf(
        1 to "sponsor-1",
        5 to "sponsor-5",
        10 to "sponsor-10",
        15 to "sponsor-15",
        25 to "sponsor-25",
        50 to "sponsor-50",
        100 to "sponsor-100"
    )

    /** Reverse lookup: offer id -> amount, used to label fetched offer prices. */
    private val OFFER_TO_AMOUNT: Map<String, Int> =
        AMOUNT_TO_OFFER.entries.associate { (amount, offerId) -> offerId to amount }

    /** The single sponsor product id, used to query its offers from the store. */
    val ALL_PRODUCT_IDS: List<String> = listOf(PRODUCT_ID)

    /** The offer (purchase-option) id for the given [amount], falling back to the default tier. */
    fun offerIdFor(amount: Int): String =
        AMOUNT_TO_OFFER[amount] ?: AMOUNT_TO_OFFER.getValue(DEFAULT_AMOUNT)

    /** The contribution amount for a given offer id, or null if it is not a known sponsor tier. */
    fun amountForOffer(offerId: String?): Int? = offerId?.let { OFFER_TO_AMOUNT[it] }

    const val PRODUCT_TYPE = "inapp"

    // Must mirror the UI's selectable amounts (see SponsorUiState.SUPPORTED_AMOUNTS).
    private const val DEFAULT_AMOUNT = 5
}
