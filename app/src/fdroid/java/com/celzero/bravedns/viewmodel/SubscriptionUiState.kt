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
package com.celzero.bravedns.viewmodel

/**
 * Stub: mirrors the play-flavour [SubscriptionUiState] sealed class so that shared
 * `main` source-set code ([com.celzero.bravedns.rpnproxy.PipKeyManager]) compiles on
 * the F-Droid build without modification.
 *
 * Only [Available] is referenced from `main` code; all other subclasses are present for
 * completeness but are never instantiated in this build.
 */
sealed class SubscriptionUiState {
    object Loading : SubscriptionUiState()

    data class Available(
        val vcode: String,
        val minVcode: String,
        val canSell: Boolean,
        val ip: String,
        val country: String,
        val asorg: String,
        val city: String,
        val colo: String,
        val region: String,
        val postalCode: String,
        val addr: String
    ) : SubscriptionUiState()

    data class Processing(val message: String) : SubscriptionUiState()

    object PendingPurchase : SubscriptionUiState()

    data class Success(val productId: String) : SubscriptionUiState()

    data class Error(
        val title: String,
        val message: String,
        val isRetryable: Boolean
    ) : SubscriptionUiState()

    data class ServerAckPending(val message: String = "") : SubscriptionUiState()

    data class AlreadySubscribed(val productId: String) : SubscriptionUiState()
}

