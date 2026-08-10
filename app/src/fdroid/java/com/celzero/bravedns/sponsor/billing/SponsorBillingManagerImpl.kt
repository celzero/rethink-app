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
package com.celzero.bravedns.sponsor.billing

import android.app.Activity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

class SponsorBillingManagerImpl : SponsorBillingManager {

    private val _products = MutableStateFlow<List<SponsorProduct>>(emptyList())
    override val products: Flow<List<SponsorProduct>> = _products.asStateFlow()

    private val _purchaseResult = MutableSharedFlow<SponsorPurchaseResult>(extraBufferCapacity = 3)
    override val purchaseResult: Flow<SponsorPurchaseResult> = _purchaseResult.asSharedFlow()

    private val _isBillingReady = MutableStateFlow(false)
    override val isBillingReady: Flow<Boolean> = _isBillingReady.asStateFlow()

    override fun initialize() {}
    override fun queryProducts() {}
    override fun launchBillingFlow(activity: Activity, amount: Int) {}
    override fun consumePurchase(purchaseToken: String) {}
    override fun destroy() {}
}
