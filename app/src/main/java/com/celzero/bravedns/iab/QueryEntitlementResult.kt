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
 * Typed result for [BillingBackendClient.queryEntitlement].
 *
 * Callers pattern-match on this to decide whether to surface a UI error (401 / 409)
 * or proceed with the updated [PurchaseDetail] on success.
 *
 * Shared across all flavors (fdroid stub + play/website real implementations).
 */
sealed class QueryEntitlementResult {
    /**
     * HTTP 2xx: entitlement queried successfully.
     * [purchase] is the original [PurchaseDetail] updated with server-returned
     * payload, expiry, and windowDays.
     */
    data class Success(val purchase: PurchaseDetail) : QueryEntitlementResult()

    /**
     * HTTP 401 Unauthorized: the server refused to authorize this request.
     * Callers should surface [ServerApiError.Unauthorized401] to the UI.
     */
    data class Unauthorized(val accountId: String, val deviceId: String) : QueryEntitlementResult()

    /**
     * HTTP 409 Conflict: e.g. the purchase token was already consumed or
     * the account/device state conflicts with the operation.
     * Callers should surface [ServerApiError.Conflict409] to the UI.
     */
    object Conflict : QueryEntitlementResult()

    /**
u     * Server responded with a business-level error (e.g. [RpnPurchaseAckServerResponse.Err]:
     * invalid token, purchase revoked/refunded per server records, etc.).
     * The server was reachable but explicitly said the purchase is not valid.
     * [purchase] is the *original* [PurchaseDetail] unchanged (fail-safe: preserve it).
     * The local billing expiry is still the authoritative gate for INAPP purchases.
     */
    data class Failure(val purchase: PurchaseDetail) : QueryEntitlementResult()

    /**
     * Transient / infrastructure failure: network error, server unreachable, timeout,
     * null HTTP response, or any unexpected exception.
     * The server was **not reached**; [purchase] is the *original* [PurchaseDetail] unchanged.
     * Callers must fail-safe and preserve the token; the local billing expiry is authoritative.
     * Do NOT expire a locally-valid purchase based on this result.
     */
    data class Transient(val purchase: PurchaseDetail) : QueryEntitlementResult()
}
