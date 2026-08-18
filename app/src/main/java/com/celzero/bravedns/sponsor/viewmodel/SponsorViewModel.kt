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
package com.celzero.bravedns.sponsor.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.celzero.bravedns.sponsor.billing.SponsorBillingManager
import com.celzero.bravedns.sponsor.billing.SponsorProduct
import com.celzero.bravedns.sponsor.billing.SponsorPurchaseResult
import com.celzero.bravedns.sponsor.repository.SponsorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * UI state for the sponsor screen.
 *
 * Pricing model: sponsorship is a single one-time product with several
 * fixed-price purchase options (offers), one per amount. [amounts] lists the
 * dollar values (and their slider order); [selectedIndex] points at the
 * currently highlighted level. The dollar amount doubles as the purchase key
 * passed to the billing layer, which resolves it to the matching offer (e.g.
 * $5 -> "sponsor-5" on the "sponsor.tier.prod" product).
 */
data class SponsorUiState(
    val products: List<SponsorProduct> = emptyList(),
    val amounts: List<Int> = SUPPORTED_AMOUNTS,
    val selectedIndex: Int = DEFAULT_INDEX,
    val isPurchasing: Boolean = false,
    val purchaseResult: SponsorPurchaseResult? = null,
    val isBillingReady: Boolean = false,
    val errorMessage: String? = null
) {
    /** The currently selected dollar amount. */
    val selectedAmount: Int
        get() = amounts.getOrElse(selectedIndex) { SUPPORTED_AMOUNTS[DEFAULT_INDEX] }

    companion object {
        // Selectable contribution levels, in slider order.
        // Range: $1 (min) -> $100 (max); default $5.
        val SUPPORTED_AMOUNTS = listOf(1, 5, 10, 15, 25, 50, 100)

        const val DEFAULT_AMOUNT = 5
        private val DEFAULT_INDEX = SUPPORTED_AMOUNTS.indexOf(DEFAULT_AMOUNT)
    }
}

class SponsorViewModel(
    application: Application,
    private val billingManager: SponsorBillingManager,
    private val sponsorRepository: SponsorRepository
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(SponsorUiState())
    val uiState: StateFlow<SponsorUiState> = _uiState.asStateFlow()

    val isSponsored: StateFlow<Boolean> = sponsorRepository.isSponsored
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val sponsorInfo = sponsorRepository.sponsorInfo
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch {
            billingManager.isBillingReady.collect { ready ->
                _uiState.update { it.copy(isBillingReady = ready) }
            }
        }
        viewModelScope.launch {
            billingManager.products.collect { products ->
                _uiState.update { it.copy(products = products) }
            }
        }
        viewModelScope.launch {
            billingManager.purchaseResult.collect { result ->
                when (result) {
                    is SponsorPurchaseResult.Success -> {
                        _uiState.update { it.copy(isPurchasing = false, purchaseResult = result) }
                        recordSponsorship(
                            purchaseTime = result.purchaseTime,
                            purchaseToken = result.purchaseToken,
                            productId = result.productId
                        )
                    }
                    is SponsorPurchaseResult.Cancelled -> {
                        _uiState.update { it.copy(isPurchasing = false, purchaseResult = result) }
                    }
                    is SponsorPurchaseResult.Pending -> {
                        _uiState.update { it.copy(isPurchasing = true, purchaseResult = result) }
                    }
                    is SponsorPurchaseResult.Error -> {
                        _uiState.update { it.copy(isPurchasing = false, purchaseResult = result, errorMessage = result.message) }
                    }
                    is SponsorPurchaseResult.BillingUnavailable -> {
                        _uiState.update { it.copy(isPurchasing = false, purchaseResult = result, errorMessage = "Billing unavailable") }
                    }
                    is SponsorPurchaseResult.AlreadyOwned -> {
                        _uiState.update { it.copy(isPurchasing = false, purchaseResult = result) }
                        // Do not fabricate a record here: ITEM_ALREADY_OWNED causes the
                        // billing impl to call queryExistingPurchases(), which re-emits
                        // Success with the authoritative purchaseTime/token/productId.
                    }
                }
            }
        }

        billingManager.initialize()
    }

    /** Selects a contribution level by its index in [SponsorUiState.amounts]. */
    fun selectIndex(index: Int) {
        val clamped = index.coerceIn(0, _uiState.value.amounts.lastIndex)
        if (_uiState.value.selectedIndex != clamped) {
            _uiState.update { it.copy(selectedIndex = clamped) }
        }
    }

    fun purchase(activity: androidx.fragment.app.FragmentActivity) {
        if (_uiState.value.isPurchasing) return
        val amount = _uiState.value.selectedAmount
        _uiState.update { it.copy(isPurchasing = true, purchaseResult = null, errorMessage = null) }
        billingManager.launchBillingFlow(activity, amount = amount)
    }

    fun dismissResult() {
        _uiState.update { it.copy(purchaseResult = null) }
    }

    /**
     * Persists the sponsorship using the authoritative values reported by the
     * billing client. The real [purchaseTime] is stored (and surfaced via
     * `sponsorSince` in About), instead of the wall-clock time at which this
     * row is written. Defensive fallbacks cover the rare case where the billing
     * client omits the token/productId or reports a non-positive purchaseTime.
     */
    private fun recordSponsorship(purchaseTime: Long, purchaseToken: String, productId: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val amount = _uiState.value.selectedAmount
            val effectiveTime = if (purchaseTime > 0) purchaseTime else now
            val safeToken = purchaseToken.ifBlank { "sponsor_${effectiveTime}_$amount" }
            val safeProductId = productId.ifBlank { "sponsor.$amount" }
            sponsorRepository.recordSponsorship(
                purchaseToken = safeToken,
                productId = safeProductId,
                purchaseTime = effectiveTime
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        billingManager.destroy()
    }
}
