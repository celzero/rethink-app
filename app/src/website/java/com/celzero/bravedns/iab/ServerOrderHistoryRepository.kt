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
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.time.Instant

/**
 * Fetches the purchase/order history for the current user from the billing
 * server's `GET /g/tx` endpoint and maps the server's Google Play purchase
 * objects into [ServerOrderEntry] domain values.
 *
 * ### Parsing strategy
 *
 * The outer `/g/tx` envelope (`{ success, cid, tx: [...] }`) is navigated via the
 * Gson [JsonObject] that Retrofit already produces.  The `meta` field inside each
 * `tx` row is also a Gson [JsonObject] and is parsed directly using safe field
 * accessors rather than typed model classes.  This avoids failures caused by
 * proto3 int64 string-encoding (e.g. `"units": "210"`) or nullable sub-objects
 * that specific library versions may reject, making the parser robust to server-side
 * schema evolution.
 *
 * The `kind` discriminator determines whether the row is a subscription or OTP:
 *
 * | `kind` value | Parsed as |
 * |---|---|
 * | `"androidpublisher#subscriptionPurchaseV2"` | [mapSubscriptionPurchase] |
 * | anything else | [mapProductPurchase] |
 */
class ServerOrderHistoryRepository(private val billingBackendClient: BillingBackendClient) {

    companion object {
        private const val TAG = "ServerOrderRepo"
        private const val MAX_ORDERS = 20
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
     * Returns the string value of this element, or `null` if it is absent,
     * JSON-null, or not a primitive.  Never throws.
     */
    private fun JsonElement?.safeString(): String? {
        if (this == null || this.isJsonNull) return null
        return try { this.asString } catch (_: Exception) { null }
    }

    /**
     * Returns the boolean value of this element, or `null` on any failure.
     * Never throws.
     */
    private fun JsonElement?.safeBool(): Boolean? {
        if (this == null || this.isJsonNull) return null
        return try { this.asBoolean } catch (_: Exception) { null }
    }

    /**
     * Returns the child [JsonObject] for [key], or `null` if absent, JSON-null,
     * or not an object.  Never throws.
     */
    private fun JsonObject?.obj(key: String): JsonObject? {
        val elem = this?.get(key) ?: return null
        return if (elem.isJsonObject) elem.asJsonObject else null
    }

    /**
     * Returns the first element of the child JSON array for [key] as a
     * [JsonObject], or `null` if absent, not an array, empty, or if the first
     * element is not an object.  Never throws.
     */
    private fun JsonObject?.firstArrayObj(key: String): JsonObject? {
        val elem = this?.get(key) ?: return null
        if (!elem.isJsonArray) return null
        val first = elem.asJsonArray.firstOrNull() ?: return null
        return if (first.isJsonObject) first.asJsonObject else null
    }

    /**
     * Iterates the `tx` array in the `/g/tx` response body and maps each row
     * to a [ServerOrderEntry], deduplicating by purchase token.
     *
     * Rows that cannot be parsed are individually skipped and logged so that a
     * single malformed entry does not suppress the rest.
     */
    private fun mapTxBody(body: JsonObject): List<ServerOrderEntry> {
        // If the server explicitly signals failure, bail out early.
        val successElem = body.get("success")
        if (successElem != null && !successElem.isJsonNull && successElem.safeBool() == false) {
            Logger.w(LOG_TAG_UI, "$TAG mapTxBody: server returned success=false")
            return emptyList()
        }

        val txElem = body.get("tx")
        if (txElem == null || txElem.isJsonNull || !txElem.isJsonArray) {
            Logger.w(LOG_TAG_UI, "$TAG mapTxBody: 'tx' array absent or not an array")
            return emptyList()
        }

        val results = mutableListOf<ServerOrderEntry>()
        txElem.asJsonArray.forEach { elem ->
            val row = elem?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            try {
                val entry = mapRow(row) ?: return@forEach
                // Deduplicate by purchaseToken
                if (results.none { it.purchaseToken == entry.purchaseToken }) {
                    results.add(entry)
                }
            } catch (e: Exception) {
                // Log full stacktrace so the root cause is visible in the log.
                Logger.e(LOG_TAG_UI, "$TAG mapRow error: ${e.message}", e)
            }
        }
        return results
    }

    /**
     * Maps a single `tx` row to a [ServerOrderEntry].
     *
     * All fields are extracted directly from the [JsonObject] using safe
     * accessors, so no model-class library or strict JSON parser is involved.
     * This tolerates unexpected nulls, missing fields, and proto3 int64
     * string-encoding without throwing.
     *
     * Returns `null` when mandatory fields (purchase token or metaobject) are
     * absent, which causes the row to be skipped.
     */
    private fun mapRow(row: JsonObject): ServerOrderEntry? {
        // Server field is all-lowercase "purchasetoken"
        val token = row.get("purchasetoken").safeString()?.takeIf { it.isNotBlank() }
            ?: return null
        val cid = row.get("cid").safeString() ?: ""
        // mtime is an ISO-8601 string, e.g. "2026-04-13T22:15:15.976Z"
        val mtime = isoToMillis(row.get("mtime").safeString())
        val meta = row.obj("meta") ?: return null
        val kind = meta.get("kind").safeString() ?: ""

        return if (kind == ServerOrderEntry.KIND_SUBSCRIPTION) {
            mapSubscriptionPurchase(token, cid, mtime, meta)
        } else {
            mapProductPurchase(token, cid, mtime, meta)
        }
    }

    /**
     * Maps a subscription (`androidpublisher#subscriptionPurchaseV2`) meta
     * [JsonObject] to [ServerOrderEntry].
     *
     * Fields are read directly from the [JsonObject] tree:
     * - `orderId`           ← `latestOrderId`
     * - `startTimeMs`       ← `startTime` (ISO-8601)
     * - `subscriptionState` ← `subscriptionState`
     * - `isTestPurchase`    ← `testPurchase` non-null presence
     * - `productId`         ← `lineItems[0].productId`
     * - `expiryTimeMs`      ← `lineItems[0].expiryTime` (ISO-8601)
     * - `planId`            ← `lineItems[0].offerDetails.basePlanId`
     * - `autoRenewEnabled`  ← `lineItems[0].autoRenewingPlan.autoRenewEnabled`
     */
    private fun mapSubscriptionPurchase(
        token: String,
        cid: String,
        mtime: Long,
        meta: JsonObject,
    ): ServerOrderEntry {
        val orderId = meta.get("latestOrderId").safeString()
        val startTime = isoToMillis(meta.get("startTime").safeString())
        val subscriptionState = meta.get("subscriptionState").safeString()
        // testPurchase is null in JSON when not a test; non-null (even {}) indicates test.
        val isTest = meta.get("testPurchase").let { it != null && !it.isJsonNull }

        val lineItem = meta.firstArrayObj("lineItems")
        val productId = lineItem?.get("productId").safeString() ?: ""
        val expiryTime = isoToMillis(lineItem?.get("expiryTime").safeString())
        val planId = lineItem.obj("offerDetails")?.get("basePlanId").safeString()
        val autoRenew = lineItem.obj("autoRenewingPlan")?.get("autoRenewEnabled").safeBool() ?: false

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
            subscriptionState = subscriptionState,
            purchaseState = null,
            autoRenewEnabled = autoRenew,
            isTestPurchase = isTest,
            productId = productId,
            planId = planId ?: productId
        )
    }

    /**
     * Maps a one-time-purchase (`androidpublisher#productPurchaseV2` or
     * `androidpublisher#productPurchase`) meta [JsonObject] to [ServerOrderEntry].
     *
     * Fields are read directly from the [JsonObject] tree:
     * - `orderId`       ← `orderId`
     * - `purchaseTimeMs`← `purchaseCompletionTime` (ISO-8601); falls back to row `mtime`
     * - `productId`     ← `productLineItem[0].productId`
     * - `planId`        ← `productLineItem[0].productOfferDetails.purchaseOptionId`
     * - `purchaseState` ← `purchaseStateContext.purchaseState`
     *                    (`"PURCHASED"` → 0, `"CANCELLED"` → 1, `"PENDING"` → 2)
     * - `isTestPurchase`← `testPurchaseContext` non-null presence
     */
    private fun mapProductPurchase(
        token: String,
        cid: String,
        mtime: Long,
        meta: JsonObject,
    ): ServerOrderEntry {
        val kind = meta.get("kind").safeString() ?: ""
        val orderId = meta.get("orderId").safeString()
        val purchaseMs = isoToMillis(meta.get("purchaseCompletionTime").safeString())
            .takeIf { it > 0L } ?: mtime
        // testPurchaseContext is non-null when this is a sandbox/test purchase
        val isTest = meta.get("testPurchaseContext").let { it != null && !it.isJsonNull }

        val lineItem = meta.firstArrayObj("productLineItem")
        val productId = lineItem?.get("productId").safeString() ?: ""
        val planId = lineItem.obj("productOfferDetails")
            ?.get("purchaseOptionId").safeString() ?: productId

        val purchaseState: Int? = when (
            meta.obj("purchaseStateContext")?.get("purchaseState").safeString()
        ) {
            "PURCHASED" -> 0
            "CANCELLED" -> 1
            "PENDING"   -> 2
            else        -> null
        }

        return ServerOrderEntry(
            purchaseToken = token,
            cid = cid,
            sku = productId,
            mtime = mtime,
            kind = kind,
            isSubscription = false,
            orderId = orderId,
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

    private fun isoToMillis(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        return try {
            Instant.parse(iso).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }
}
