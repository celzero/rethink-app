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

import Logger
import Logger.LOG_TAG_UI
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.androidpublisher.model.ProductPurchaseV2
import com.google.api.services.androidpublisher.model.SubscriptionPurchaseV2
import java.time.Instant

/**
 * Fetches the purchase/order history for the current user from the billing
 * server's `GET /g/tx` endpoint and maps the server's Google Play purchase
 * objects into [ServerOrderEntry] domain values.
 *
 * ### Parsing strategy
 *
 * The outer `/g/tx` envelope (`{ success, cid, tx: [...] }`) is navigated via the
 * Gson [com.google.gson.JsonObject] that Retrofit already produces.  The `meta`
 * field inside each `tx` row is a verbatim Google Play purchase object whose
 * concrete type is identified by its `kind` discriminator:
 *
 * | `kind` value | Java model class |
 * |---|---|
 * | `"androidpublisher#productPurchaseV2"` | [ProductPurchaseV2] |
 * | `"androidpublisher#subscriptionPurchaseV2"` | [SubscriptionPurchaseV2] |
 *
 * Both model classes extend [com.google.api.client.json.GenericJson] (itself an
 * `AbstractMap` subclass), so plain Gson cannot deserialize them correctly.
 * [GsonFactory] — the JSON factory bundled with `google-http-client-gson` — is
 * used instead; it knows how to populate `@Key`-annotated fields properly.
 *
 * Artifact:
 * ```
 * implementation("com.google.apis:google-api-services-androidpublisher:v3-rev20260318-2.0.0")
 * ```
 * Reference: https://developer.android.com/google/play/developer-api
 */
class ServerOrderHistoryRepository(private val billingBackendClient: BillingBackendClient) {

    companion object {
        private const val TAG = "ServerOrderRepo"
        private const val MAX_ORDERS = 20

        /**
         * Shared [GsonFactory] used to deserialize [ProductPurchaseV2] and
         * [SubscriptionPurchaseV2].  These model classes extend
         * [com.google.api.client.json.GenericJson] (an `AbstractMap` subclass), so
         * plain Gson would try to fill the map instead of the declared fields;
         * [GsonFactory] handles the `@Key`-annotation mapping correctly.
         */
        private val jsonFactory: GsonFactory = GsonFactory.getDefaultInstance()
    }

    /** Sealed result type returned to the ViewModel. */
    sealed class Result {
        data class Success(val orders: List<ServerOrderEntry>) : Result()
        data class Error(val message: String) : Result()
        object NoCredentials : Result()
    }

    /**
     * Fetches up to [MAX_ORDERS] purchase records from the server for the
     * current user.  Must be called from a background coroutine.
     */
    suspend fun fetchOrders(): Result {
        val mname = "fetchOrders"
        return try {
            val purchase = RpnProxyManager.getEffectivePurchaseDetail()
            if (purchase == null) {
                Logger.w(LOG_TAG_UI, "$TAG $mname: no purchase detail available")
                return Result.NoCredentials
            }
            val accountId = purchase.accountId
            val deviceId = InAppBillingHandler.getObfuscatedDeviceId()
            val purchaseToken = purchase.purchaseToken
            if (accountId.isBlank() || purchaseToken.isBlank()) {
                Logger.w(LOG_TAG_UI, "$TAG $mname: accountId or purchaseToken blank")
                return Result.NoCredentials
            }

            when (val raw = billingBackendClient.fetchPurchaseHistory(
                accountId = accountId,
                deviceId = deviceId,
                purchaseToken = purchaseToken,
                total = MAX_ORDERS,
            )) {
                is FetchOrdersRawResult.Success -> {
                    val orders = mapTxBody(raw.body)
                    Logger.i(LOG_TAG_UI, "$TAG $mname: parsed ${orders.size} orders")
                    Result.Success(orders)
                }
                is FetchOrdersRawResult.Error -> {
                    Logger.w(LOG_TAG_UI, "$TAG $mname: ${raw.message}")
                    Result.Error(raw.message)
                }
                is FetchOrdersRawResult.NoCredentials -> Result.NoCredentials
            }
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG $mname: exception: ${e.message}", e)
            Result.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Iterates the `tx` array in the `/g/tx` response body and maps each row
     * to a [ServerOrderEntry], deduplicating by purchase token.
     */
    private fun mapTxBody(body: com.google.gson.JsonObject): List<ServerOrderEntry> {
        val results = mutableListOf<ServerOrderEntry>()
        body.getAsJsonArray("tx")?.forEach { elem ->
            val row = elem?.asJsonObject ?: return@forEach
            try {
                val entry = mapRow(row) ?: return@forEach
                // Deduplicate by purchaseToken
                if (results.none { it.purchaseToken == entry.purchaseToken }) {
                    results.add(entry)
                }
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "$TAG mapRow: ${e.message}")
            }
        }
        return results
    }

    /**
     * Maps a single `tx` row to a [ServerOrderEntry].
     *
     * The `meta` JSON object is converted to a string and then deserialized by
     * [GsonFactory] into the appropriate official Google API model class based
     * on the `kind` field.
     */
    private fun mapRow(row: com.google.gson.JsonObject): ServerOrderEntry? {
        // Server field is all-lowercase "purchasetoken"
        val token = row.get("purchasetoken")?.asString?.takeIf { it.isNotBlank() }
            ?: return null
        val cid = row.get("cid")?.asString ?: ""
        // mtime is an ISO-8601 string, e.g. "2026-04-13T22:15:15.976Z"
        val mtime = isoToMillis(row.get("mtime")?.asString)
        val meta = row.getAsJsonObject("meta") ?: return null

        val kind = meta.get("kind")?.asString ?: ""
        // meta.toString() re-serialises the already-parsed Gson JsonObject back to
        // a JSON string so that GsonFactory can deserialise it into the typed model.
        val metaJson = meta.toString()

        return if (kind == ServerOrderEntry.KIND_SUBSCRIPTION) {
            val sub = jsonFactory.fromString(metaJson, SubscriptionPurchaseV2::class.java)
            mapSubscriptionPurchase(token, cid, mtime, sub)
        } else {
            val otp = jsonFactory.fromString(metaJson, ProductPurchaseV2::class.java)
            mapProductPurchase(token, cid, mtime, otp)
        }
    }

    /**
     * Maps a [ProductPurchaseV2] (one-time purchase) to [ServerOrderEntry].
     *
     * Field sources ([ProductPurchaseV2] API reference):
     * - `productId`      ← [ProductPurchaseV2.getProductLineItem]\[0\].productId
     * - `purchaseTimeMs` ← [ProductPurchaseV2.getPurchaseCompletionTime] (ISO-8601)
     * - `purchaseState`  ← [ProductPurchaseV2.getPurchaseStateContext].purchaseState
     *                      (`"PURCHASED"` → 0, `"CANCELLED"` → 1, `"PENDING"` → 2)
     * - `isTestPurchase` ← [ProductPurchaseV2.getTestPurchaseContext] != null
     * - `orderId`        ← [ProductPurchaseV2.getOrderId]
     *
     * Reference: https://developer.android.com/google/play/developer-api#ProductPurchaseV2
     */
    private fun mapProductPurchase(
        token: String,
        cid: String,
        mtime: Long,
        meta: ProductPurchaseV2,
    ): ServerOrderEntry {
        val lineItem   = meta.productLineItem?.firstOrNull()
        val productId  = lineItem?.productId ?: ""
        val purchaseMs = isoToMillis(meta.purchaseCompletionTime).takeIf { it > 0L } ?: mtime
        val planId = lineItem?.productOfferDetails?.purchaseOptionId ?: productId

        // purchaseStateContext.purchaseState is a String in ProductPurchaseV2;
        // mapped to the legacy Int in ServerOrderEntry (0=purchased, 1=cancelled, 2=pending).
        val purchaseState: Int? = when (meta.purchaseStateContext?.purchaseState) {
            "PURCHASED" -> 0
            "PENDING" -> 2
            "CANCELLED" -> 1
            else -> null
        }

        // testPurchaseContext is non-null when this is a sandbox / test purchase
        val isTest = meta.testPurchaseContext != null

        return ServerOrderEntry(
            purchaseToken = token,
            cid = cid,
            sku = productId,
            mtime = mtime,
            kind = meta.kind ?: "",
            isSubscription = false,
            orderId = meta.orderId,
            startTimeMs = purchaseMs,
            expiryTimeMs = 0L,
            purchaseTimeMs = purchaseMs,
            subscriptionState = null,
            purchaseState = purchaseState,
            autoRenewEnabled = false,
            isTestPurchase = isTest,
            productId = productId,
            planId = planId
        )
    }

    /**
     * Maps a [SubscriptionPurchaseV2] (recurring subscription) to [ServerOrderEntry].
     *
     * Field sources ([SubscriptionPurchaseV2] API reference):
     * - `productId`         ← [SubscriptionPurchaseV2.getLineItems]\[0\].productId
     * - `startTimeMs`       ← [SubscriptionPurchaseV2.getStartTime] (ISO-8601)
     * - `expiryTimeMs`      ← lineItems\[0\].expiryTime (ISO-8601)
     * - `subscriptionState` ← [SubscriptionPurchaseV2.getSubscriptionState]
     * - `autoRenewEnabled`  ← lineItems\[0\].autoRenewingPlan.autoRenewEnabled
     * - `orderId`           ← [SubscriptionPurchaseV2.getLatestOrderId] (falls back to orderId)
     * - `isTestPurchase`    ← [SubscriptionPurchaseV2.getTestPurchase] != null
     *
     * Reference: https://developer.android.com/google/play/developer-api#SubscriptionPurchaseV2
     */
    private fun mapSubscriptionPurchase(
        token: String,
        cid: String,
        mtime: Long,
        meta: SubscriptionPurchaseV2,
    ): ServerOrderEntry {
        val orderId = meta.latestOrderId
        val startTime = isoToMillis(meta.startTime)
        val lineItem = meta.lineItems?.firstOrNull()
        val productId = lineItem?.productId ?: ""
        val planId = lineItem?.offerDetails?.basePlanId
        val expiryTime = isoToMillis(lineItem?.expiryTime)
        val autoRenew = lineItem?.autoRenewingPlan?.autoRenewEnabled ?: false
        val isTest = meta.testPurchase != null

        return ServerOrderEntry(
            purchaseToken = token,
            cid = cid,
            sku = productId,
            mtime = mtime,
            kind = ServerOrderEntry.KIND_SUBSCRIPTION,
            isSubscription = true,
            orderId = orderId,
            startTimeMs = startTime,
            expiryTimeMs = expiryTime,
            purchaseTimeMs = startTime,
            subscriptionState = meta.subscriptionState,
            purchaseState = null,
            autoRenewEnabled = autoRenew,
            isTestPurchase = isTest,
            productId = productId,
            planId = planId ?: productId
        )
    }

    private fun isoToMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}

