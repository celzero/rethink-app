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

import com.android.billingclient.api.BillingClient

@JvmInline
value class BillingResponse(private val code: Int) {
    /** The raw [BillingClient.BillingResponseCode], exposed for UI message mapping. */
    val rawCode: Int
        get() = code

    val isOk: Boolean
        get() = code == BillingClient.BillingResponseCode.OK

    val isUserCancelled: Boolean
        get() = code == BillingClient.BillingResponseCode.USER_CANCELED

    val isAlreadyOwned: Boolean
        get() = code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED

    val isRecoverableError: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.ERROR,
            BillingClient.BillingResponseCode.SERVICE_TIMEOUT,
        )

    val serviceDisconnected: Boolean
        get() = code == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED

    val isServiceTimeout: Boolean
        get() = code == BillingClient.BillingResponseCode.SERVICE_TIMEOUT

    val isNonrecoverableError: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingClient.BillingResponseCode.BILLING_UNAVAILABLE,
            BillingClient.BillingResponseCode.DEVELOPER_ERROR,
        )

    val isTerribleFailure: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.ITEM_UNAVAILABLE,
            BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED,
            BillingClient.BillingResponseCode.ITEM_NOT_OWNED,
        )
}
