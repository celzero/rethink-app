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
 * Stub: mirrors the play-flavour [PurchaseDetail] data class so that shared
 * `main` source-set code (`SubscriptionStateMachineV2`, `RpnProxyManager`, etc.)
 * compiles on the F-Droid build without modification.
 *
 * There are no real purchases on the F-Droid build; this class is never
 * instantiated at runtime with meaningful data.
 */
data class PurchaseDetail(
    val productId: String,
    var planId: String,
    var productTitle: String,
    var state: Int,
    var planTitle: String,
    val purchaseToken: String,
    val productType: String,
    val purchaseTime: String,
    val purchaseTimeMillis: Long,
    val isAutoRenewing: Boolean,
    val accountId: String,
    val deviceId: String = "",
    val payload: String,
    val expiryTime: Long,
    val status: Int,
    val windowDays: Int,
    val orderId: String = "",
    /** True only when the one-time option came from an exact purchase-token `/g/tx` row. */
    val isPlanIdServerConfirmed: Boolean = false
)
