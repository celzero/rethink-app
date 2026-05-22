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
 * Typed representation of a server API error returned by any [BillingBackendClient] call.
 *
 * Callers receive a [ServerApiError] instead of a raw HTTP code whenever an API
 * returns a non-2xx response.  UI layers pattern-match on this to decide whether
 * to show an inline toast ([GenericError]) or a dedicated action sheet
 * ([Conflict409]).
 *
 * ### 409 Conflict
 * HTTP 409 means the server state conflicts with the requested operation
 * e.g. a subscription was already canceled, a purchase was already consumed,
 * or a device was already registered. The caller should surface the
 * PurchaseConflictBottomSheet which offers a Refund action and a link to
 * Google Play subscription management.
 */
sealed class ServerApiError {

    /**
     * The API call succeeded (HTTP 2xx).
     * Callers can treat this as a no-op and proceed normally.
     */
    object None : ServerApiError()

    /**
     * HTTP 409 Conflict: the server state conflicts with the requested operation.
     *
     * @param endpoint  The BillingBackendClient endpoint that returned 409 (e.g. "/g/stop").
     * @param operation The human-readable operation name (e.g. "cancel", "revoke").
     * @param serverMessage Optional raw message from the server error body.
     * @param accountId The account ID involved, used to pre-fill the refund call.
     * @param purchaseToken The purchase token involved.
     * @param sku The product SKU involved.
     * Note: deviceId is intentionally absent, it must never leave [SecureIdentityStore].
     * The PurchaseConflictBottomSheet fetches the real ID fresh via
     * [InAppBillingHandler.getObfuscatedDeviceId] when the user initiates a refund.
     */
    data class Conflict409(
        val endpoint: String,
        val operation: Operation,
        val serverMessage: String?,
        val accountId: String,
        val purchaseToken: String,
        val sku: String
    ) : ServerApiError()

    /**
     * HTTP 401 Unauthorized: the device could not be authorized by the server.
     *
     * Shown as a dedicated "Device Authorization Failed" error screen that surfaces
     * the [accountId] in full and the first 8 characters of [deviceIdPrefix] so the
     * user can relay them to customer support.
     *
     * @param operation     The operation that triggered the 401 (e.g. DEVICE, CUSTOMER).
     * @param accountId     The full server-assigned account ID.
     * @param deviceIdPrefix First 8 characters of the device ID, never the full ID.
     */
    data class Unauthorized401(
        val operation: Operation,
        val accountId: String,
        val deviceIdPrefix: String
    ) : ServerApiError()

    /**
     * Any other non-2xx HTTP error (4xx except 401/409, or 5xx).
     *
     * @param httpCode  The raw HTTP response code.
     * @param message   Human-readable error message (from server body or default string).
     */
    data class GenericError(
        val httpCode: Int,
        val message: String
    ) : ServerApiError()

    /**
     * Network or unexpected exception (no HTTP response received).
     *
     * @param message  Exception message.
     */
    data class NetworkError(val message: String?) : ServerApiError()

    /**
     * CID mismatch, the CID embedded in the entitlement payload (authoritative,
     * Google Play side) differs from the CID stored locally, AND the server could
     * not return a valid DID for the entitlement CID.
     *
     * This means the current device is not registered under the account that owns
     * the active subscription.  The user must contact support or re-link their
     * account.
     *
     * @param entitlementCid  The CID extracted from the entitlement payload (master).
     * @param storedCid       The CID that was stored locally before reconciliation.
     * @param deviceIdPrefix  First 8 characters of the stored device ID (never the full ID).
     */
    data class DeviceNotRegistered(
        val entitlementCid: String,
        val storedCid: String,
        val deviceIdPrefix: String
    ) : ServerApiError()

    /**
     * The specific ITcpProxy operation that produced a 409.
     * Used by [PurchaseConflictBottomSheet] to customise title, description,
     * and whether to show the Refund button.
     */
    enum class Operation(val endpoint: String) {
        /** /customer: register or resolve customer (server-driven accountId + deviceId) */
        CUSTOMER("/acc"),
        /** /g/stop: cancel subscription */
        CANCEL("/g/stop"),
        /** /g/refund: revoke / refund subscription */
        REVOKE("/g/refund"),
        /** /g/ack: acknowledge purchase */
        ACKNOWLEDGE("/g/ack"),
        /** /g/consume: consume one-time purchase */
        CONSUME("/g/con"),
        /** /g/device: register device */
        DEVICE("/g/reg");

        /**
         * Returns true when this operation can meaningfully offer a Refund button.
         * CANCEL and REVOKE conflicts often indicate a stale local state where a refund
         * can unblock the user.
         */
        val canRefund: Boolean
            get() = this == CANCEL || this == REVOKE || this == CONSUME || this == DEVICE || this == ACKNOWLEDGE
    }
}

