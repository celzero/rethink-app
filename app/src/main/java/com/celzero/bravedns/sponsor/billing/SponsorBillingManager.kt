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
import kotlinx.coroutines.flow.Flow

data class SponsorProduct(
    val productId: String,
    val title: String?,
    val description: String?,
    val price: String?,
    val priceMicros: Long
)

sealed class SponsorPurchaseResult {
    /**
     * Successful purchase. Carries the authoritative values reported by the
     * billing client (Play Billing `Purchase`), so the real `purchaseTime` is
     * preserved end-to-end instead of being replaced with the wall-clock time
     * at which the DB row happens to be written.
     *
     * - [purchaseTime]: epoch millis from `Purchase.purchaseTime`.
     * - [purchaseToken]: the opaque token used to acknowledge/consume.
     * - [productId]: the purchased SKU (first of `Purchase.products`).
     */
    data class Success(
        val purchaseTime: Long,
        val purchaseToken: String,
        val productId: String
    ) : SponsorPurchaseResult()
    data class Error(val message: String, val code: Int = -1) : SponsorPurchaseResult()
    data object Cancelled : SponsorPurchaseResult()
    data object Pending : SponsorPurchaseResult()
    data object BillingUnavailable : SponsorPurchaseResult()
    data object AlreadyOwned : SponsorPurchaseResult()
}

interface SponsorBillingManager {
    val products: Flow<List<SponsorProduct>>
    val purchaseResult: Flow<SponsorPurchaseResult>
    val isBillingReady: Flow<Boolean>

    fun initialize()
    fun queryProducts()

    /**
     * Launches the purchase flow for the sponsor product matching [amount].
     *
     * Sponsorship is a single one-time INAPP product ("sponsor.tier.prod") exposed
     * through several fixed-price purchase options (offers), one per contribution
     * level (e.g. $5 maps to the "sponsor-5" offer). The concrete product id and
     * offer id are resolved by the flavor implementation, so the channel-agnostic
     * UI only needs to express the desired contribution amount. The matching
     * offer's offerToken is passed to the Play billing flow.
     */
    fun launchBillingFlow(activity: Activity, amount: Int)
    fun consumePurchase(purchaseToken: String)
    fun destroy()
}
