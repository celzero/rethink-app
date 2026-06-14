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

import Logger
import Logger.LOG_TAG_UI
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ManagePurchaseViewModel : ViewModel() {

    companion object {
        private const val TAG = "ManSubVM"
    }

    /** Represents each named step shown in the progress UI. */
    enum class Step { VALIDATING, CONTACTING_SERVER, UPDATING_LOCAL, REFRESHING, DONE }

    sealed class OperationState {
        /** No operation running, the default idle state. */
        object Idle : OperationState()

        /**
         * An operation is running.
         * @param isCancel `true` for cancel, `false` for revoke.
         * @param step     The current step being executed (drives the step indicator UI).
         */
        data class InProgress(val isCancel: Boolean, val step: Step = Step.VALIDATING) : OperationState()

        /** The operation completed successfully. */
        data class Success(val isCancel: Boolean, val message: String) : OperationState()

        /** The operation failed. */
        data class Failure(val isCancel: Boolean, val message: String) : OperationState()
    }

    private val _operationState = MutableStateFlow<OperationState>(OperationState.Idle)
    val operationState: StateFlow<OperationState> = _operationState.asStateFlow()

    fun cancelSubscription() = launchOperation(isCancel = true)
    fun revokeSubscription() = launchOperation(isCancel = false)

    /**
     * Reset to [OperationState.Idle] after the fragment has consumed a terminal state
     * ([Success] or [Failure]).  Must be called from the main thread.
     */
    fun resetOperationState() {
        _operationState.value = OperationState.Idle
    }

    /**
     * Launches the cancel/revoke pipeline inside [viewModelScope].
     *
     * [viewModelScope] is scoped to the ViewModel, not the fragment,so even if the
     * fragment is destroyed mid-flight (back press, rotation) the coroutine keeps
     * running until the server call and local state update finish.  Only when the
     * entire activity is finished (and the ViewModel is cleared) will the coroutine
     * be cancelled, but by then no UI exists to update anyway.
     */
    private fun launchOperation(isCancel: Boolean) {
        // Ignore duplicate taps while already running.
        if (_operationState.value is OperationState.InProgress) {
            Logger.w(LOG_TAG_UI, "$TAG: operation already in progress, ignoring duplicate tap")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                emit(OperationState.InProgress(isCancel, Step.VALIDATING))

                val state = RpnProxyManager.getSubscriptionState()
                if (!state.hasValidSubscription) {
                    Logger.w(LOG_TAG_UI, "$TAG: no valid subscription")
                    emit(OperationState.Failure(isCancel, "No active subscription found."))
                    return@launch
                }

                val purchaseDetail = RpnProxyManager.getEffectivePurchaseDetail()
                if (purchaseDetail == null) {
                    Logger.w(LOG_TAG_UI, "$TAG: purchase detail unavailable")
                    emit(OperationState.Failure(isCancel, "Purchase detail unavailable. Please try again."))
                    return@launch
                }

                val accountId = purchaseDetail.accountId
                // purchaseDetail.deviceId holds only the sentinel indicator, never the real device ID.
                // Always fetch the actual ID from SecureIdentityStore via InAppBillingHandler.
                val deviceId = InAppBillingHandler.getObfuscatedDeviceId()
                val purchaseToken = purchaseDetail.purchaseToken
                val productType = purchaseDetail.productType
                val productId = purchaseDetail.productId

                if (accountId.isEmpty() || purchaseToken.isEmpty()) {
                    Logger.w(LOG_TAG_UI, "$TAG: accountId or purchaseToken empty")
                    emit(OperationState.Failure(isCancel, "Account information incomplete. Please try again."))
                    return@launch
                }

                emit(OperationState.InProgress(isCancel, Step.CONTACTING_SERVER))

                val res = performServerCall(isCancel, productType, accountId, deviceId, purchaseToken, productId)

                if (!res.first) {
                    Logger.w(LOG_TAG_UI, "$TAG: server call failed: ${res.second}")
                    emit(OperationState.Failure(isCancel, res.second))
                    return@launch
                }

                emit(OperationState.InProgress(isCancel, Step.UPDATING_LOCAL))

                if (isCancel) {
                    RpnProxyManager.handleUserCancellation()
                } else {
                    RpnProxyManager.handleSubscriptionRevoked()
                }

                emit(OperationState.InProgress(isCancel, Step.REFRESHING))
                try {
                    InAppBillingHandler.fetchPurchases(
                        listOf(InAppBillingHandler.PRODUCT_TYPE_SUBS, InAppBillingHandler.PRODUCT_TYPE_INAPP)
                    )
                } catch (e: Exception) {
                    // Non-fatal: server call already succeeded; Play will reconcile on next launch.
                    Logger.w(LOG_TAG_UI, "$TAG: fetchPurchases failed after ${if (isCancel) "cancel" else "revoke"}: ${e.message}")
                }

                emit(OperationState.InProgress(isCancel, Step.DONE))

                val successMsg = if (isCancel)
                    "Subscription cancelled successfully."
                else
                    "Subscription revoked. Refund will be processed within a few days."

                Logger.i(LOG_TAG_UI, "$TAG: ${if (isCancel) "cancel" else "revoke"} succeeded")
                emit(OperationState.Success(isCancel, successMsg))

            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "$TAG: operation failed: ${e.message}", e)
                emit(OperationState.Failure(isCancel, "Something went wrong. Please try again."))
            }
        }
    }

    private suspend fun performServerCall(
        isCancel: Boolean,
        productType: String,
        accountId: String,
        deviceId: String,
        purchaseToken: String,
        productId: String
    ): Pair<Boolean, String> {
        return if (isCancel) {
            if (productType == InAppBillingHandler.PRODUCT_TYPE_INAPP) {
                InAppBillingHandler.cancelOneTimePurchase(accountId, deviceId, purchaseToken, productId)
            } else {
                InAppBillingHandler.cancelPlaySubscription(accountId, deviceId, purchaseToken, productId)
            }
        } else {
            if (productType == InAppBillingHandler.PRODUCT_TYPE_INAPP) {
                InAppBillingHandler.revokeOneTimePurchase(accountId, deviceId, purchaseToken, productId)
            } else {
                InAppBillingHandler.revokeSubscription(accountId, deviceId, purchaseToken, productId)
            }
        }
    }

    /** Emit on the current coroutine dispatcher (already IO); safe from any thread. */
    private fun emit(state: OperationState) {
        _operationState.value = state
    }
}


