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

import Logger.LOG_IAB
import org.json.JSONObject

// ref: github.com/celzero/redir: PlayErr / PlayOk classes in src/d.js

/**
 * Sealed result returned by every Rethink server billing endpoint.
 *
 * Use [RpnPurchaseAckServerResponse.from] to deserialise raw HTTP response bodies.
 * A 2xx response with `"success" != false` is treated as [Ok]; everything
 * else (non-2xx, `"error"` key present, or `"success" == false`) is [Err].
 */
sealed class RpnPurchaseAckServerResponse {
    data class Ok(val payload: ResponseOk) : RpnPurchaseAckServerResponse()
    data class Err(val payload: ResponseErr) : RpnPurchaseAckServerResponse()

    companion object {
        /**
         * Parse a raw response body string + HTTP status code into a [RpnPurchaseAckServerResponse].
         *
         * @param body       Raw response body string (may be null/empty on network errors).
         * @param httpCode   HTTP status code from the response (e.g. 200, 400, 500).
         */
        fun from(body: String?, httpCode: Int): RpnPurchaseAckServerResponse {
            // Non-2xx → always an error, even if the body looks OK-ish
            if (httpCode !in 200..<300) {
                val json = body?.trimmedJson() ?: JSONObject()
                val payload = ResponseErr.fromJson(json, httpCode)
                Logger.d(LOG_IAB, "PlayErr: $payload")
                return Err(payload)
            }

            val json = body?.trimmedJson() ?: JSONObject()

            // If "error" key is present, or "success" is explicitly false → error
            return if (json.has("error") || !json.optBoolean("success", true)) {
                val payload = ResponseErr.fromJson(json, httpCode)
                Logger.d(LOG_IAB, "PlayErr: $payload")
                Err(payload)
            } else {
                Logger.d(LOG_IAB, "PlayOk: $json")
                val payload = ResponseOk.fromJson(json)
                Logger.d(LOG_IAB, "PlayOk: $payload")
                Ok(payload)
            }
        }

        private fun String.trimmedJson(): JSONObject = try {
            JSONObject(this.trim())
        } catch (_: Exception) {
            JSONObject()
        }
    }
}

/**
 * Success payload returned by the Rethink Play billing server.
 *
 * All fields are optional except [success] (defaults to `true`) and [ray].
 * Null fields indicate the server did not include that key in the response.
 */
data class ResponseOk(
    /** True if the operation succeeded; may be false for soft/partial failures. */
    val success: Boolean = true,
    /** Human-readable success or failure (non-error) message. */
    val message: String? = null,
    /** Customer identifier (obfuscated account ID). */
    val cid: String? = null,
    /** Product / SKU identifier. */
    val sku: String? = null,
    /** Obfuscated purchase identifier. */
    val purchaseId: String? = null,
    /** Google Play order identifier. */
    val orderId: String? = null,
    /** Purchase state string (e.g. "purchased", "pending"). */
    val state: String? = null,
    /** Account status (e.g. "active", "canceled"). */
    val status: String? = null,
    /** True if this is a test-mode transaction. */
    val test: Boolean? = null,
    /** Subscription or entitlement expiry as ISO 8601 string. */
    val expiry: String? = null,
    /** Subscription or purchase start date as ISO 8601 string. */
    val start: String? = null,
    /** Refund window in calendar days. */
    val windowDays: Int? = null,
    /** Cancellation context string, if any. */
    val cancelCtx: String? = null,
    /** All product identifiers covered by this purchase. */
    val allProducts: List<String>? = null,
    /** Product identifiers not yet consumed. */
    val unconsumedProducts: List<String>? = null,
    /**
     * Developer payload returned by the server after a successful acknowledgement.
     * Contains the session token and other entitlement data.
     */
    val developerPayload: String? = null,
    /** True if the account previously had an entitlement. */
    val hadEntitlement: Boolean? = null,
    /** True if the entitlement was deleted by this operation. */
    val deletedEntitlement: Boolean? = null,
    /** True if the account was already fully refunded before this call. */
    val wasAlreadyFullyRefunded: Boolean? = null,
    /** Cloudflare Ray ID for server-side log correlation. */
    val ray: String? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): ResponseOk = ResponseOk(
            success = json.optBoolean("success", true),
            message = json.optStringOrNull("message"),
            cid = json.optStringOrNull("cid"),
            sku = json.optStringOrNull("sku"),
            purchaseId = json.optStringOrNull("purchaseId"),
            orderId = json.optStringOrNull("orderId"),
            state = json.optStringOrNull("state"),
            status = json.optStringOrNull("status"),
            test = json.optBooleanOrNull("test"),
            expiry = json.optStringOrNull("expiry"),
            start = json.optStringOrNull("start"),
            windowDays = json.optIntOrNull("windowDays"),
            cancelCtx = json.optStringOrNull("cancelCtx"),
            allProducts = json.optStringListOrNull("allProducts"),
            unconsumedProducts = json.optStringListOrNull("unconsumedProducts"),
            developerPayload = json.optStringOrNull("developerPayload"),
            hadEntitlement = json.optBooleanOrNull("hadEntitlement"),
            deletedEntitlement = json.optBooleanOrNull("deletedEntitlement"),
            wasAlreadyFullyRefunded = json.optBooleanOrNull("wasAlreadyFullyRefunded"),
            ray = json.optStringOrNull("ray"),
        )
    }

    /** True if the server confirmed the purchase as active/valid. */
    val isActive: Boolean
        get() = success && (status == "active" || status == "valid")

    /** True if the subscription is in a cancelled (but potentially still usable) state. */
    val isCancelled: Boolean
        get() = status == "canceled" || status == "cancelled"

    /** True if the response carries entitlement data (developerPayload present). */
    val hasEntitlement: Boolean
        get() = !developerPayload.isNullOrEmpty()
}

/**
 * Error payload returned by the Rethink Play billing server.
 *
 * The server may return error details on both 4xx and 5xx responses, or when
 * a 2xx response carries `"success": false` or an `"error"` key.
 */
data class ResponseErr(
    /** Primary error message (mapped from "error" or "message" field). */
    val error: String = "",
    /** Extended error details, if provided by the server. */
    val details: String? = null,
    /** Customer identifier (obfuscated account ID). */
    val cid: String? = null,
    /** Product / SKU identifier. */
    val sku: String? = null,
    /** Obfuscated purchase identifier. */
    val purchaseId: String? = null,
    /** Google Play order identifier. */
    val orderId: String? = null,
    /** Purchase state string at the time of the error. */
    val state: String? = null,
    /** Account status at the time of the error. */
    val status: String? = null,
    /** True if this was a test-mode transaction. */
    val test: Boolean? = null,
    /** Entitlement expiry as ISO 8601 string, if known. */
    val expiry: String? = null,
    /** Subscription start date as ISO 8601 string, if known. */
    val start: String? = null,
    /** Refund window in calendar days, if applicable. */
    val windowDays: Int? = null,
    /** All product identifiers covered by the purchase, if known. */
    val allProducts: List<String>? = null,
    /** Unconsumed product identifiers, if known. */
    val unconsumedProducts: List<String>? = null,
    /** Cloudflare Ray ID for server-side log correlation. */
    val ray: String? = null,
    /** HTTP status code from the server response; 0 if not from an HTTP layer. */
    val httpCode: Int = 0,
) {
    companion object {
        fun fromJson(json: JSONObject, httpCode: Int = 0): ResponseErr = ResponseErr(
            // "error" takes precedence; fall back to "message" (mirrors the JS constructor)
            error = json.optString("error").ifEmpty { json.optString("message", "") },
            details = json.optStringOrNull("details"),
            cid = json.optStringOrNull("cid"),
            sku = json.optStringOrNull("sku"),
            purchaseId = json.optStringOrNull("purchaseId"),
            orderId = json.optStringOrNull("orderId"),
            state = json.optStringOrNull("state"),
            status = json.optStringOrNull("status"),
            test = json.optBooleanOrNull("test"),
            expiry = json.optStringOrNull("expiry"),
            start = json.optStringOrNull("start"),
            windowDays = json.optIntOrNull("windowDays"),
            allProducts = json.optStringListOrNull("allProducts"),
            unconsumedProducts = json.optStringListOrNull("unconsumedProducts"),
            ray = json.optStringOrNull("ray"),
            httpCode = httpCode,
        )
    }



    override fun toString(): String =
        "PlayErr(http=$httpCode, error='$error', status=$status, sku=$sku, ray=$ray)"
}

/** Returns the string value for [key], or null if missing or empty. */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (has(key)) optString(key).takeUnless { it.isEmpty() } else null

/** Returns the boolean value for [key], or null if the key is absent. */
private fun JSONObject.optBooleanOrNull(key: String): Boolean? =
    if (has(key)) optBoolean(key) else null

/** Returns the int value for [key], or null if the key is absent. */
private fun JSONObject.optIntOrNull(key: String): Int? =
    if (has(key)) optInt(key) else null

/** Returns the list of strings for a JSON array at [key], or null if absent. */
private fun JSONObject.optStringListOrNull(key: String): List<String>? {
    val arr = optJSONArray(key) ?: return null
    return (0 until arr.length()).mapNotNull { arr.optString(it).takeUnless { s -> s.isEmpty() } }
        .takeIf { it.isNotEmpty() }
}
