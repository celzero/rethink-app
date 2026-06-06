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

import android.app.Activity
import android.content.Context
import androidx.lifecycle.MutableLiveData
import com.google.gson.JsonObject

/**
 * Stub: in-app billing is not available on the F-Droid build.
 *
 * All constants mirror the play-flavor values so that shared `full` source-set
 * code (fragments, view-models, activities) compiles without modification. All
 * methods are no-ops or return safe default values.
 */
object InAppBillingHandler {

    const val STD_PRODUCT_ID = "standard.tier"
    const val ONE_TIME_PRODUCT_ID = "onetime.tier"
    const val ONE_TIME_PRODUCT_2YRS = "proxy-yearly-2"
    const val ONE_TIME_PRODUCT_5YRS = "proxy-yearly-5"
    const val ONE_TIME_TEST_PRODUCT_ID = "test_product"
    const val SUBS_PRODUCT_MONTHLY = "proxy-monthly"
    const val SUBS_PRODUCT_YEARLY = "proxy-yearly"

    const val REVOKE_WINDOW_SUBS_MONTHLY_DAYS = 3
    const val REVOKE_WINDOW_SUBS_YEARLY_DAYS = 7
    const val REVOKE_WINDOW_ONE_TIME_2YRS_DAYS = 14  // 2 * 7
    const val REVOKE_WINDOW_ONE_TIME_5YRS_DAYS = 35  // 5 * 7

    const val PLAY_SUBS_LINK = "https://play.google.com/store/account/subscriptions?sku=\$1&package=\$2"
    const val HISTORY_LINK = ""
    const val LINK = "https://play.google.com/store/account/subscriptions?sku=\$1&package=\$2"
    const val PRODUCT_ID_TEST = "test_subs"

    // Product type string constants (replaces BillingClient.ProductType.*)
    const val PRODUCT_TYPE_SUBS = "subs"
    const val PRODUCT_TYPE_INAPP = "inapp"

    const val UNACK_ESCALATION_THRESHOLD = 1

    // LiveData shared by NotificationHandlerActivity / ManagePurchaseFragment
    val serverApiErrorLiveData: MutableLiveData<ServerApiError?> = MutableLiveData(null)

    @Suppress("UNUSED_PARAMETER")
    fun initiate(context: Context, billingListener: Any? = null) { /* no-op */ }

    fun isBillingClientSetup(): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun enableInAppMessaging(activity: Activity) { /* no-op */ }

    /** Accepts a list of product-type strings ("subs", "inapp"). No-op in this build. */
    @Suppress("UNUSED_PARAMETER")
    fun fetchPurchases(productType: List<String>) { /* no-op */ }

    /** Convenience overload: fetches all purchase types. No-op in this build. */
    fun fetchAllPurchases() { /* no-op */ }

    suspend fun getObfuscatedDeviceId(): String = ""

    fun getRemainingDaysForInApp(): Long? = null

    suspend fun getRemainingDaysForInAppSuspend(): Long? = null

    @Suppress("UNUSED_PARAMETER")
    suspend fun cancelOneTimePurchase(
        accountId: String,
        deviceId: String,
        purchaseToken: String,
        productId: String
    ): Pair<Boolean, String> = Pair(false, "Not supported in this build")

    @Suppress("UNUSED_PARAMETER")
    suspend fun cancelPlaySubscription(
        accountId: String,
        deviceId: String,
        purchaseToken: String,
        productId: String
    ): Pair<Boolean, String> = Pair(false, "Not supported in this build")

    @Suppress("UNUSED_PARAMETER")
    suspend fun revokeOneTimePurchase(
        accountId: String,
        deviceId: String,
        purchaseToken: String,
        productId: String
    ): Pair<Boolean, String> = Pair(false, "Not supported in this build")

    @Suppress("UNUSED_PARAMETER")
    suspend fun revokeSubscription(
        accountId: String,
        deviceId: String,
        purchaseToken: String,
        productId: String
    ): Pair<Boolean, String> = Pair(false, "Not supported in this build")

    @Suppress("UNUSED_PARAMETER")
    suspend fun purchaseSubs(
        activity: Activity,
        productId: String,
        planId: String,
        forceResubscribe: Boolean = false
    ) { /* no-op */ }

    /** No-op: F-Droid build has no account/device IDs to reconcile. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun reconcileCidDidFromPurchase(cid: String) { /* no-op */ }

    /** Returns an empty list, F-Droid build has no active Play purchases. */
    fun getActivePurchasesSnapshot(): List<PurchaseDetail> = emptyList()

    /** No-op: F-Droid build does not register devices with the billing server. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun registerDevice(
        accountId: String,
        deviceId: String,
        meta: JsonObject? = null
    ) { /* no-op */ }

    /** No-op: F-Droid build has no billing server to return 401 errors. */
    @Suppress("UNUSED_PARAMETER")
    internal suspend fun handleUnauthorized401(
        operation: ServerApiError.Operation,
        accountId: String,
        deviceId: String
    ) { /* no-op */ }

    /** Returns the unchanged [purchase]: F-Droid build has no server entitlements. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun queryEntitlementFromServer(
        accountId: String,
        deviceId: String,
        purchase: PurchaseDetail
    ): PurchaseDetail = purchase

    fun getProductType(purchase: com.android.billingclient.api.Purchase, pt: String? = null): String {
        return PRODUCT_TYPE_SUBS
    }

    /** Returns an empty byte array: F-Droid build has no test purchase payload. */
    fun getTestPurchasePayloadBytes(): ByteArray = ByteArray(0)
}






