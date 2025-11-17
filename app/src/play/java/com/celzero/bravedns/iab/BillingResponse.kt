package com.celzero.bravedns.iab

import com.android.billingclient.api.BillingClient

@JvmInline
value class BillingResponse(private val code: Int) {
    val isOk: Boolean
        get() = code == BillingClient.BillingResponseCode.OK

    val isUserCancelled: Boolean
        get() = code == BillingClient.BillingResponseCode.USER_CANCELED

    val isAlreadyOwned: Boolean
        get() = code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED

    val isRecoverableError: Boolean
        get() = code in setOf(
            BillingClient.BillingResponseCode.ERROR,
            BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        )

    val serviceDisconnected: Boolean
        get() = code == BillingClient.BillingResponseCode.SERVICE_DISCONNECTED

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
