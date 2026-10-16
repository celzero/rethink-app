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
 * Selects the purchase that may claim or rebind the stored identity.
 *
 * Only the primary purchase may interact with the stored (cid, did) identity
 * to prevent identity switching between multiple purchases. The state machine's
 * current purchase is preferred; if unavailable, the newest purchase is used.
 */
object PrimaryPurchaseResolver {

    /**
     * Returns the primary purchase from [purchases]:
     * - The purchase matching [machineToken], if available.
     * 2. else the newest purchase by [PurchaseDetail.purchaseTimeMillis].
     *
     * Returns null when [purchases] is empty.
     */
    fun primaryPurchase(purchases: List<PurchaseDetail>, machineToken: String?): PurchaseDetail? {
        if (purchases.isEmpty()) return null
        if (!machineToken.isNullOrBlank()) {
            purchases.firstOrNull { it.purchaseToken == machineToken }?.let { return it }
        }
        return purchases.maxByOrNull { it.purchaseTimeMillis }
    }
}
