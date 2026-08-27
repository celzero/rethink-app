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
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2.SubscriptionState
import com.celzero.bravedns.rpnproxy.SubscriptionUiStateResolver.PurchaseUiModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubscriptionUiStateResolverTest {

    private fun sub(): SubscriptionStatus = SubscriptionStatus().apply {
        accountId = "acct-123"
        purchaseToken = "tok"
        purchaseTime = 1_700_000_000_000
    }

    @Test
    fun `Uninitialized resolves to Loading`() {
        assertEquals(PurchaseUiModel.Loading, SubscriptionUiStateResolver.resolve(SubscriptionState.Uninitialized, null))
    }

    @Test
    fun `ServerAckPending resolves to Loading`() {
        assertEquals(PurchaseUiModel.Loading, SubscriptionUiStateResolver.resolve(SubscriptionState.ServerAckPending, sub()))
    }

    @Test
    fun `Initial with no persisted row is NoPurchase`() {
        val model = SubscriptionUiStateResolver.resolve(SubscriptionState.Initial, null)
        assertEquals(PurchaseUiModel.NoPurchase, model)
    }

    @Test
    fun `Initial with a stale persisted row falls back to Valid`() {
        val s = sub()
        val model = SubscriptionUiStateResolver.resolve(SubscriptionState.Initial, s)
        assertTrue(model is PurchaseUiModel.Valid)
        assertEquals(s, (model as PurchaseUiModel.Valid).sub)
    }

    @Test
    fun `Expired resolves to Lapsed preserving row`() {
        val s = sub()
        val model = SubscriptionUiStateResolver.resolve(SubscriptionState.Expired, s)
        assertEquals(PurchaseUiModel.Lapsed(s), model)
    }

    @Test
    fun `Expired without a row still resolves to Lapsed`() {
        val model = SubscriptionUiStateResolver.resolve(SubscriptionState.Expired, null)
        assertEquals(PurchaseUiModel.Lapsed(null), model)
    }

    @Test
    fun `Revoked resolves to Lapsed`() {
        val model = SubscriptionUiStateResolver.resolve(SubscriptionState.Revoked, null)
        assertEquals(PurchaseUiModel.Lapsed(null), model)
    }

    @Test
    fun `Valid states resolve to Valid`() {
        val s = sub()
        val validStates = listOf(
            SubscriptionState.Active,
            SubscriptionState.Grace,
            SubscriptionState.Paused,
            SubscriptionState.OnHold,
            SubscriptionState.Cancelled,
            SubscriptionState.PurchaseInitiated,
            SubscriptionState.PurchasePending,
            SubscriptionState.Error
        )
        validStates.forEach { state ->
            val model = SubscriptionUiStateResolver.resolve(state, s)
            assertTrue("state $state expected Valid", model is PurchaseUiModel.Valid)
            assertEquals(state, (model as PurchaseUiModel.Valid).state)
        }
    }

    @Test
    fun `Valid resolution works without a persisted row`() {
        listOf(
            SubscriptionState.Active,
            SubscriptionState.Cancelled,
            SubscriptionState.Error
        ).forEach { state ->
            val model = SubscriptionUiStateResolver.resolve(state, null)
            assertTrue(model is PurchaseUiModel.Valid)
        }
    }

    @Test
    fun `NoPurchase implies canMakePurchase and not hasValidSubscription`() {
        val state = SubscriptionState.Initial
        assertTrue(state.canMakePurchase)
        assertTrue(!state.hasValidSubscription)
    }

    @Test
    fun `Lapsed states are not active and cannot be re-rendered as Valid`() {
        listOf(SubscriptionState.Expired, SubscriptionState.Revoked).forEach { state ->
            assertTrue(!state.isActive)
            assertTrue(!state.hasValidSubscription)
        }
    }
}
