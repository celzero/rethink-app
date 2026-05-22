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

    suspend fun getDeviceId(@Suppress("UNUSED_PARAMETER") recvCid: String = ""): String = ""

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
    ): PurchaseDetail = purchase

    suspend fun cancelPurchase(
        @Suppress("UNUSED_PARAMETER") accountId: String,
        @Suppress("UNUSED_PARAMETER") deviceId: String,
        @Suppress("UNUSED_PARAMETER") sku: String,
        @Suppress("UNUSED_PARAMETER") purchaseToken: String
    ): Pair<Boolean, String> = Pair(false, "Not supported in F-Droid build")

    suspend fun revokePurchase(
        @Suppress("UNUSED_PARAMETER") accountId: String,
        @Suppress("UNUSED_PARAMETER") deviceId: String,
        @Suppress("UNUSED_PARAMETER") sku: String,
        @Suppress("UNUSED_PARAMETER") purchaseToken: String
    ): Pair<Boolean, String> = Pair(false, "Not supported in F-Droid build")

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

