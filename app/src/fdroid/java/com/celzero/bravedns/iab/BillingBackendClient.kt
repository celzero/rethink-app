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

import com.google.gson.JsonObject


/**
 * Stub: F-Droid flavor does not interact with the Rethink billing server.
 *
 * All methods return empty/default values. The class is registered in Koin via
 * [BillingModule] so that injection points in `main` source-set code
 * (`RpnProxyUpdateWorker`, `RpnProxyManager`) resolve without errors at runtime.
 */
class BillingBackendClient(
    @Suppress("UNUSED_PARAMETER", "UNUSED_PRIVATE_PROPERTY") private val identityStore: SecureIdentityStore
) {
    companion object {
        @Suppress("UNUSED_PRIVATE_PROPERTY")
        private const val TAG = "BillingBackendClient(stub)"
    }

    suspend fun getAccountId(): String = ""

    suspend fun getDeviceIdForCid(@Suppress("UNUSED_PARAMETER") cid: String): String = ""

    /** Stub: F-Droid has no billing server; no DID is ever available. */
    suspend fun storedIdentityDidForCurrentEnv(): String = ""

    /** Stub: F-Droid stores no billing identity, so there is no DID to peek. */
    suspend fun peekStoredDid(): String = ""

    /**
     * Stub mirroring the play/website signature; F-Droid performs no
     * device registration.
     */
    suspend fun ensureDeviceRegistration(
        @Suppress("UNUSED_PARAMETER") primaryPurchase: PurchaseDetail?,
        @Suppress("UNUSED_PARAMETER") refreshIntervalMs: Long,
        @Suppress("UNUSED_PARAMETER") onDeviceNotRegistered: suspend (String) -> Unit,
        @Suppress("UNUSED_PARAMETER") register: suspend (String, String, PurchaseDetail?) -> Unit
    ) { /* no-op */ }

    /** Stub: F-Droid has no billing server; identity can never be resolved. */
    suspend fun resolveIdentity():
        RefreshIdentityResult = RefreshIdentityResult.Failure

    /** Stub: F-Droid has no billing server; no DID can ever be bound to a cid. */
    suspend fun didForCid(
        @Suppress("UNUSED_PARAMETER") cid: String,
        @Suppress("UNUSED_PARAMETER") mintIfMissing: Boolean
    ): DidResult = DidResult.error(501)

    /** Stub: F-Droid has no billing server; identity can never be reconciled. */
    suspend fun reconcileDidForCid(@Suppress("UNUSED_PARAMETER") cid: String): DidResult =
        DidResult.error(501)

    /** Stub: F-Droid has no billing server; there is no stored identity to recover. */
    suspend fun recoverIdentityAfterAuthFailure(@Suppress("UNUSED_PARAMETER") accountId: String): IdentityRecoveryOutcome =
        IdentityRecoveryOutcome.SKIPPED

    /** Stub: F-Droid stores no billing identity, so no cid can ever block a rebind. */
    suspend fun peekStoredCid(): String = ""

    suspend fun registerDevice(
        @Suppress("UNUSED_PARAMETER") accountId: String,
        @Suppress("UNUSED_PARAMETER") deviceId: String,
        @Suppress("UNUSED_PARAMETER") meta: JsonObject? = null
    ): RegisterDeviceResult = RegisterDeviceResult.Failure(0, "Not supported in F-Droid build")

    suspend fun registerDeviceWithDeviceMeta(
        @Suppress("UNUSED_PARAMETER") accountId: String,
        @Suppress("UNUSED_PARAMETER") deviceId: String
    ): RegisterDeviceResult = RegisterDeviceResult.Failure(0, "Not supported in F-Droid build")

    suspend fun queryEntitlement(
        @Suppress("UNUSED_PARAMETER") accountId: String,
        @Suppress("UNUSED_PARAMETER") deviceId: String,
        @Suppress("UNUSED_PARAMETER") purchase: PurchaseDetail,
        @Suppress("UNUSED_PARAMETER") purchaseToken: String
    ): QueryEntitlementResult = QueryEntitlementResult.Transient(purchase)

    suspend fun cancelPurchase(
        @Suppress("UNUSED_PARAMETER") accountId: String,
        @Suppress("UNUSED_PARAMETER") deviceId: String,
        @Suppress("UNUSED_PARAMETER") sku: String,
        @Suppress("UNUSED_PARAMETER") purchaseToken: String
    ): PurchaseMutationResult = PurchaseMutationResult.Failure("Not supported in F-Droid build")

    suspend fun revokePurchase(
        @Suppress("UNUSED_PARAMETER") accountId: String,
        @Suppress("UNUSED_PARAMETER") deviceId: String,
        @Suppress("UNUSED_PARAMETER") sku: String,
        @Suppress("UNUSED_PARAMETER") purchaseToken: String
    ): PurchaseMutationResult = PurchaseMutationResult.Failure("Not supported in F-Droid build")

    suspend fun consumePurchase(
        @Suppress("UNUSED_PARAMETER") accountId: String,
        @Suppress("UNUSED_PARAMETER") deviceId: String,
        @Suppress("UNUSED_PARAMETER") sku: String,
        @Suppress("UNUSED_PARAMETER") purchaseToken: String
    ): Boolean = false

    fun buildDeviceMeta(@Suppress("UNUSED_PARAMETER") prodId: String = ""): JsonObject = JsonObject()

    fun buildCustomerMeta(): JsonObject = JsonObject()

    suspend fun fetchPurchaseHistory(
        @Suppress("UNUSED_PARAMETER") accountId: String,
        @Suppress("UNUSED_PARAMETER") deviceId: String,
        @Suppress("UNUSED_PARAMETER") purchaseToken: String,
        @Suppress("UNUSED_PARAMETER") total: Int,
    ): FetchOrdersRawResult = FetchOrdersRawResult.NoCredentials
}

