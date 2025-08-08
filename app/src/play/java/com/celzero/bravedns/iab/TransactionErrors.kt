/*
 * Copyright 2025 RethinkDNS and its authors
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

import androidx.annotation.DrawableRes

/**
 * A data class that represents a transactional error.
 * @param title The title of the error.
 * @param message The message of the error.
 * @param image The image to be displayed with the error.
 */
data class TransactionError(
    val title: String,
    val message: String,
    @DrawableRes val image: Int? = null
)

/**
 * Returns a [TransactionError] for a given [billingResult].
 * @param billingResult The billing result to get the error for.
 * @return The [TransactionError] for the given [billingResult].
 */
/*fun getTransactionError(billingResult: com.android.billingclient.api.BillingResult): TransactionError {
    return when (billingResult.responseCode) {
        com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE,
        com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_DISCONNECTED,
        com.android.billingclient.api.BillingClient.BillingResponseCode.SERVICE_TIMEOUT -> {
            TransactionError(
                "Service Unavailable",
                "The connection to the Play Store is currently unavailable. Please try again later.",
                androidx.biometric.R.drawable.fingerprint_dialog_error
            )
        }
        com.android.billingclient.api.BillingClient.BillingResponseCode.BILLING_UNAVAILABLE -> {
            TransactionError(
                "Billing Unavailable",
                "Billing is not available on this device. Please make sure you have a valid Google account and payment method.",
                androidx.biometric.R.drawable.fingerprint_dialog_error
            )
        }
        com.android.billingclient.api.BillingClient.BillingResponseCode.ITEM_UNAVAILABLE -> {
            TransactionError(
                "Item Unavailable",
                "The item you are trying to purchase is not available.",
                androidx.biometric.R.drawable.fingerprint_dialog_error
            )
        }
        com.android.billingclient.api.BillingClient.BillingResponseCode.DEVELOPER_ERROR -> {
            TransactionError(
                "Developer Error",
                "An error occurred while processing your request. Please contact support.",
                androidx.biometric.R.drawable.fingerprint_dialog_error
            )
        }
        com.android.billingclient.api.BillingClient.BillingResponseCode.ERROR -> {
            TransactionError(
                "An Error Occurred",
                "An unexpected error occurred. Please try again.",
                androidx.biometric.R.drawable.fingerprint_dialog_error
            )
        }
        com.android.billingclient.api.BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
            TransactionError(
                "Already Owned",
                "You already own this item.",
                androidx.biometric.R.drawable.fingerprint_dialog_error
            )
        }
        com.android.billingclient.api.BillingClient.BillingResponseCode.ITEM_NOT_OWNED -> {
            TransactionError(
                "Not Owned",
                "You do not own this item.",
                androidx.biometric.R.drawable.fingerprint_dialog_error
            )
        }
        else -> {
            TransactionError(
                "Unknown Error",
                "An unknown error occurred. Please try again.",
                androidx.biometric.R.drawable.fingerprint_dialog_error
            )
        }
    }
}*/

