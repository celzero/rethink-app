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
package com.celzero.bravedns.iab

/**
 * A single purchase / order entry as returned by the billing server's
 * `GET /g/tx` endpoint. The [meta] field contains the raw Google Play
 * purchase object (either a SubscriptionPurchaseV2 or a ProductPurchase),
 * already decoded into typed fields here.
 *
 * @param purchaseToken  The Google Play purchase token.
 * @param cid            The customer/account ID on the server.
 * @param sku            The product SKU (e.g. "standard.monthly").
 * @param mtime          Server-side modification time in epoch-millis.
 * @param kind           Google Play API kind string:
 *                       "androidpublisher#subscriptionPurchaseV2" or
 *                       "androidpublisher#productPurchase".
 * @param isSubscription `true` when this is a recurring subscription.
 * @param orderId        Google Play order ID (may be null for very old records).
 * @param startTimeMs    Subscription start time in epoch-millis (subs only).
 * @param expiryTimeMs   Subscription expiry / line-item expiry in epoch-millis (subs only).
 * @param purchaseTimeMs One-time purchase time in epoch-millis (OTP only).
 * @param subscriptionState  Google Play subscription state string (subs only),
 *                           e.g. "SUBSCRIPTION_STATE_ACTIVE".
 * @param purchaseState  Google Play purchase state integer (OTP only): 0=purchased, 2=pending.
 * @param autoRenewEnabled Whether the subscription auto-renews (subs only).
 * @param isTestPurchase   Whether this was a test / sandbox purchase.
 * @param productId      The product/plan ID (may differ from sku; taken from lineItems[0] for subs).
 */
data class ServerOrderEntry(
    val purchaseToken: String,
    val cid: String,
    val sku: String,
    val mtime: Long,
    val kind: String,
    val isSubscription: Boolean,
    val orderId: String?,
    val startTimeMs: Long,
    val expiryTimeMs: Long,
    val purchaseTimeMs: Long,
    val subscriptionState: String?,
    val purchaseState: Int?,
    val autoRenewEnabled: Boolean,
    val isTestPurchase: Boolean,
    val productId: String,
    val planId: String
) {
    companion object {
        const val KIND_SUBSCRIPTION = "androidpublisher#subscriptionPurchaseV2"
        /** Covers both legacy "productPurchase" and the newer "productPurchaseV2" kind. */
        const val KIND_OTP         = "androidpublisher#productPurchaseV2"
        const val KIND_OTP_LEGACY  = "androidpublisher#productPurchase"

        // Subscription state constants (Google Play SubscriptionPurchaseV2)
        const val STATE_ACTIVE    = "SUBSCRIPTION_STATE_ACTIVE"
        const val STATE_CANCELLED = "SUBSCRIPTION_STATE_CANCELED"
        const val STATE_EXPIRED   = "SUBSCRIPTION_STATE_EXPIRED"
        const val STATE_PAUSED    = "SUBSCRIPTION_STATE_PAUSED"
        const val STATE_ON_HOLD   = "SUBSCRIPTION_STATE_IN_ACCOUNT_HOLD"
        const val STATE_PENDING   = "SUBSCRIPTION_STATE_PENDING"

        // One-time purchase state integers (Google Play ProductPurchaseV2 / ProductPurchase)
        const val PURCHASE_STATE_PURCHASED = 0
        const val PURCHASE_STATE_CANCELLED = 1
        const val PURCHASE_STATE_PENDING   = 2
    }
}

