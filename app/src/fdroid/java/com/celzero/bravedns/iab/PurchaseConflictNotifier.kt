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

import android.content.Context

/** Stub: purchase-conflict notifications are not supported in this build. */
object PurchaseConflictNotifier {
    const val EXTRA_ENDPOINT       = "conflict_endpoint"
    const val EXTRA_OPERATION      = "conflict_operation"
    const val EXTRA_SERVER_MSG     = "conflict_server_msg"
    const val EXTRA_ACCOUNT_ID     = "conflict_account_id"
    const val EXTRA_DEVICE_ID      = "conflict_device_id"
    const val EXTRA_PURCHASE_TOKEN = "conflict_purchase_token"
    const val EXTRA_SKU            = "conflict_sku"

    @Suppress("UNUSED_PARAMETER")
@Suppress("UNUSED_PARAMETER")    fun cancel(context: Context) { /* no-op */ }
}

