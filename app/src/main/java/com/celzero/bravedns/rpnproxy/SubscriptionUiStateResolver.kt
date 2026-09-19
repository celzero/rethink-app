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
package com.celzero.bravedns.rpnproxy

import com.celzero.bravedns.database.SubscriptionStatus

/**
 * Resolves the raw [SubscriptionStateMachineV2.SubscriptionState] plus the persisted
 * [SubscriptionStatus] row into a single UI-facing model shared by
 * [com.celzero.bravedns.ui.fragment.RethinkPlusDashboardFragment] and
 * [com.celzero.bravedns.ui.fragment.RethinkPlusManagePurchaseFragment] so that both
 * screens render identical "No Purchase" behaviour.
 */
object SubscriptionUiStateResolver {

    sealed class PurchaseUiModel {
        /** State not yet resolved (Uninitialized / ServerAckPending); suppress first paint. */
        object Loading : PurchaseUiModel()

        /** Never purchased: Initial state with no persisted SubscriptionStatus. */
        object NoPurchase : PurchaseUiModel()

        /** Entitlement gone (Expired / Revoked); historical purchase data may exist. */
        data class Lapsed(val sub: SubscriptionStatus?) : PurchaseUiModel()

        /**
         * Valid or transient states (Active, Grace, Paused, OnHold, Cancelled,
         * PurchaseInitiated, PurchasePending, Error, or Initial with a stale DB row);
         * render normal UI with status-chip styling driven by the raw state.
         */
        data class Valid(
            val state: SubscriptionStateMachineV2.SubscriptionState,
            val sub: SubscriptionStatus?
        ) : PurchaseUiModel()
    }

    fun resolve(
        state: SubscriptionStateMachineV2.SubscriptionState,
        sub: SubscriptionStatus?
    ): PurchaseUiModel {
        return when (state) {
            is SubscriptionStateMachineV2.SubscriptionState.Uninitialized,
            is SubscriptionStateMachineV2.SubscriptionState.ServerAckPending ->
                PurchaseUiModel.Loading

            is SubscriptionStateMachineV2.SubscriptionState.Initial ->
                if (sub == null) PurchaseUiModel.NoPurchase else valid(state, sub)

            is SubscriptionStateMachineV2.SubscriptionState.Expired,
            is SubscriptionStateMachineV2.SubscriptionState.Revoked ->
                PurchaseUiModel.Lapsed(sub)

            else -> valid(state, sub)
        }
    }

    private fun valid(
        state: SubscriptionStateMachineV2.SubscriptionState,
        sub: SubscriptionStatus?
    ): PurchaseUiModel = PurchaseUiModel.Valid(state, sub)
}
