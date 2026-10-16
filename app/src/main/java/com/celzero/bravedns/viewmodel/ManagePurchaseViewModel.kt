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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.celzero.bravedns.R
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.PurchaseDetail
import com.celzero.bravedns.iab.PurchaseMutationResult
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.rpnproxy.SubscriptionStateMachineV2
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

class ManagePurchaseViewModel : ViewModel() {

    companion object {
        private const val TAG = "ManSubVM"

        /** reconcile refresh window; shorter than the pipeline default so
         * the user is not left waiting on a screen that just reported a failure. */
        private const val RECONCILE_REFRESH_TIMEOUT_MS = 20_000L
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
        data class Success(val isCancel: Boolean, @param:StringRes val messageRes: Int) : OperationState()


        data class Failure(
            val isCancel: Boolean,
            @param:StringRes val messageRes: Int,
            val serverMessage: String? = null
        ) : OperationState()

        /**
         * Operation failed, but reconciliation confirmed the purchase is already
         * in a terminal state (expired, canceled, or revoked).
         */
        data class AlreadyEnded(val isCancel: Boolean) : OperationState()
    }

    private val _operationState = MutableStateFlow<OperationState>(OperationState.Idle)
    val operationState: StateFlow<OperationState> = _operationState.asStateFlow()

    /** Guards against duplicate taps; cleared in the operation's finally block. */
    private val operationRunning = AtomicBoolean(false)

    fun cancelSubscription() = launchOperation(isCancel = true)
    fun revokeSubscription() = launchOperation(isCancel = false)

    /**
     * Reset to [OperationState.Idle] after the fragment has consumed a terminal state
     * ([Success] or [Failure]).
     */
    fun resetOperationState() {
        _operationState.value = OperationState.Idle
    }

    /**
     * Launches the cancel/revoke pipeline inside [viewModelScope].
     *
     * [viewModelScope] is scoped to the ViewModel, not the fragment, so even if the
     * fragment is destroyed mid-flight (back press, rotation) the coroutine keeps
     * running until the server call and local state update finish.  Only when the
     * entire activity is finished (and the ViewModel is cleared) will the coroutine
     * be cancelled, but by then no UI exists to update anyway.
     */
    private fun launchOperation(isCancel: Boolean) {
        if (!operationRunning.compareAndSet(false, true)) {
            Logger.w(LOG_TAG_UI, "$TAG: operation already in progress, ignoring duplicate tap")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                emit(OperationState.InProgress(isCancel, Step.VALIDATING))

                val state = RpnProxyManager.getSubscriptionState()
                if (!state.hasValidSubscription) {
                    Logger.w(LOG_TAG_UI, "$TAG: no valid subscription")
                    emit(OperationState.Failure(isCancel, R.string.manage_purchase_err_no_subscription))
                    return@launch
                }

                val purchaseDetail = RpnProxyManager.getEffectivePurchaseDetail()
                if (purchaseDetail == null) {
                    Logger.w(LOG_TAG_UI, "$TAG: purchase detail unavailable")
                    emit(OperationState.Failure(isCancel, R.string.manage_purchase_err_purchase_unavailable))
                    return@launch
                }

                val accountId = purchaseDetail.accountId
                // purchaseDetail.deviceId holds only the sentinel indicator, never the real device ID.
                val deviceId = InAppBillingHandler.getObfuscatedDeviceId(accountId)
                val purchaseToken = purchaseDetail.purchaseToken
                val productType = purchaseDetail.productType
                val productId = purchaseDetail.productId

                if (accountId.isEmpty() || purchaseToken.isEmpty()) {
                    Logger.w(LOG_TAG_UI, "$TAG: accountId or purchaseToken empty")
                    emit(OperationState.Failure(isCancel, R.string.manage_purchase_err_account_incomplete))
                    return@launch
                }

                if (deviceId.isBlank()) {
                    Logger.w(LOG_TAG_UI, "$TAG: deviceId unavailable for cid=${accountId.take(8)}; aborting")
                    emit(OperationState.Failure(isCancel, R.string.manage_purchase_err_device_identity))
                    return@launch
                }

                emit(OperationState.InProgress(isCancel, Step.CONTACTING_SERVER))

                val res = performServerCall(isCancel, productType, accountId, deviceId, purchaseToken, productId)

                when (res) {
                    // 401/409 surface their own dedicated flows (auth-error notification /
                    // conflict bottom sheet)
                    is PurchaseMutationResult.Unauthorized -> {
                        Logger.w(LOG_TAG_UI, "$TAG: server call rejected: ${res.text}")
                        emit(OperationState.Failure(isCancel, R.string.manage_purchase_err_auth))
                        return@launch
                    }
                    is PurchaseMutationResult.Conflict -> {
                        Logger.w(LOG_TAG_UI, "$TAG: server call rejected: ${res.text}")
                        emit(OperationState.Failure(isCancel, R.string.manage_purchase_err_conflict))
                        return@launch
                    }
                    is PurchaseMutationResult.Failure -> {
                        Logger.w(LOG_TAG_UI, "$TAG: server call failed: ${res.message}")
                        emit(OperationState.InProgress(isCancel, Step.REFRESHING))
                        val alreadyEnded = reconcileAfterServerFailure(isCancel, accountId, deviceId, purchaseDetail)
                        if (alreadyEnded) {
                            Logger.i(LOG_TAG_UI, "$TAG: post-failure reconcile concluded purchase already ended")
                            emit(OperationState.AlreadyEnded(isCancel))
                        } else {
                            emit(OperationState.Failure(isCancel, R.string.manage_purchase_err_generic, serverMessage = res.message))
                        }
                        return@launch
                    }
                    is PurchaseMutationResult.Success -> { /* proceed to refresh below */ }
                }

                emit(OperationState.InProgress(isCancel, Step.UPDATING_LOCAL))

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

                val successRes = if (isCancel)
                    R.string.manage_purchase_cancel_success
                else
                    R.string.manage_purchase_revoke_success

                Logger.i(LOG_TAG_UI, "$TAG: ${if (isCancel) "cancel" else "revoke"} succeeded")
                emit(OperationState.Success(isCancel, successRes))

            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(LOG_TAG_UI, "$TAG: operation failed: ${e.message}", e)
                emit(OperationState.Failure(isCancel, R.string.manage_purchase_err_generic))
            } finally {
                operationRunning.set(false)
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
    ): PurchaseMutationResult {
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

    /**
     * Reconciles the purchase after a failed cancel/revoke call, in case it
     * has already ended on the server while local state still shows Active.
     * Checks Play and the billing server to update the purchase state.
     */
    private suspend fun reconcileAfterServerFailure(
        isCancel: Boolean,
        accountId: String,
        deviceId: String,
        purchaseDetail: PurchaseDetail
    ): Boolean {
        return try {
            val playRefreshed = try {
                InAppBillingHandler.refreshPurchasesAwait(
                    listOf(InAppBillingHandler.PRODUCT_TYPE_SUBS, InAppBillingHandler.PRODUCT_TYPE_INAPP),
                    timeoutMs = RECONCILE_REFRESH_TIMEOUT_MS
                )
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "$TAG: post-failure Play refresh failed: ${e.message}")
                false
            }
            try {
                InAppBillingHandler.queryEntitlementFromServer(accountId, deviceId, purchaseDetail)
            } catch (e: Exception) {
                Logger.w(LOG_TAG_UI, "$TAG: post-failure entitlement query failed: ${e.message}")
            }
            Logger.d(LOG_TAG_UI, "$TAG: post-failure reconcile done (playRefreshed=$playRefreshed)")
            // State is global, so this may reflect another purchase. The screen
            // reloads the reconciled state, and the dialog is informational.
            val state = RpnProxyManager.getSubscriptionState()
            val terminalState: Boolean = when (state) {
                is SubscriptionStateMachineV2.SubscriptionState.Expired,
                is SubscriptionStateMachineV2.SubscriptionState.Revoked -> true
                is SubscriptionStateMachineV2.SubscriptionState.Cancelled -> isCancel
                else -> false
            }
            val ended = !state.hasValidSubscription || terminalState
            Logger.i(LOG_TAG_UI, "$TAG: post-failure reconcile state=${state::class.simpleName}, ended=$ended")
            ended
        } catch (e: Exception) {
            Logger.e(LOG_TAG_UI, "$TAG: post-failure reconcile error: ${e.message}", e)
            false
        }
    }

    /** Emit on the current coroutine dispatcher (already IO); safe from any thread. */
    private fun emit(state: OperationState) {
        _operationState.value = state
    }
}

