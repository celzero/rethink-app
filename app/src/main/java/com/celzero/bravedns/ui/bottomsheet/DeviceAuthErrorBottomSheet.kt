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
package com.celzero.bravedns.ui.bottomsheet
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import com.celzero.bravedns.ui.compose.RethinkTheme
import com.celzero.bravedns.ui.compose.purchase.*

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_IAB
import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.ui.activity.CustomerSupportActivity
import com.celzero.bravedns.iab.DeviceAuthErrorNotifier
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Bottom sheet shown when a `/g/acc` or `/reg` API call returns HTTP 401.
 */
class DeviceAuthErrorBottomSheet : BaseBottomSheetDialogFragment() {

    private var supportAction: (() -> Unit)? = null
    private var presentation: DeviceErrorState? = null
    private val ui get() = checkNotNull(presentation) { "Presentation accessed outside of view lifecycle" }

    private var entitlementCid: String = ""

    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAG = "DeviceAuthErrorBS"

        private const val ARG_ACCOUNT_ID = "account_id"
        private const val ARG_DEVICE_ID_PREFIX = "device_id_prefix"
        private const val ARG_OPERATION = "operation"

        /**
         * All data is passed via [Bundle] args so the fragment survives
         * configuration changes without holding a live reference to the error object.
         */
        fun newInstance(error: ServerApiError.Unauthorized401): DeviceAuthErrorBottomSheet {
            return DeviceAuthErrorBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ACCOUNT_ID, error.accountId)
                    putString(ARG_DEVICE_ID_PREFIX, error.deviceIdPrefix)
                    putString(ARG_OPERATION, error.operation.name)
                }
            }
        }
    }

    override fun getTheme(): Int =
        getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isCancelable = true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        presentation = DeviceErrorState()
        return ComposeView(inflater.context).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { RethinkTheme { DeviceErrorContent(ui, unregistered = false, onSupport = { supportAction?.invoke() }, onReset = { resetIdentity() }, onDismiss = { dismissAllowingStateLoss() }) } }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }

        val args = requireArguments()
        entitlementCid = args.getString(ARG_ACCOUNT_ID, "")
        val deviceIdPrefix = args.getString(ARG_DEVICE_ID_PREFIX, "")

        populateDetails(entitlementCid, deviceIdPrefix)
        setupButtons(entitlementCid, deviceIdPrefix)
        ui.resetVisible = true
    }

    override fun onDestroyView() {
        super.onDestroyView()
        presentation = null
        supportAction = null
    }

    private fun populateDetails(accountId: String, deviceIdPrefix: String) {
        // Show masked account ID (first 12 + last 4 chars); fall back to a placeholder
        // if blank so the layout never appears empty.
        ui.accountId = maskCid(accountId).ifBlank {
            getString(R.string.device_auth_error_id_unavailable)
        }

        // Show first 8 chars of device ID followed by "…" to indicate truncation.
        ui.deviceId = if (deviceIdPrefix.isNotBlank()) {
            deviceIdPrefix
        } else {
            getString(R.string.device_auth_error_id_unavailable)
        }
    }

    /**
     * Masks a CID/accountId to show only the first 12
     *
     * Example: "abcdefghijklmnopqrstuvwx" → "abcdefghijkl"
     */
    private fun maskCid(id: String): String {
        if (id.length <= 16) return id
        return id.take(12)
    }

    private fun setupButtons(accountId: String, deviceIdPrefix: String) {
        supportAction = { openCustomerSupport(accountId, deviceIdPrefix) }
    }

    private fun resetIdentity() {
        if (ui.resetInFlight || !ui.resetVisible) return
        ui.resetInFlight = true
        isCancelable = false
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val claimCid = try {
                InAppBillingHandler.primaryPurchaseCid().ifBlank { entitlementCid }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG: primary purchase cid resolution failed: ${e.message}", e)
                ""
            }
            val cleared = try {
                if (claimCid.isBlank()) {
                    Logger.w(LOG_IAB, "$TAG: no claimable cid available; skipping identity reset")
                    false
                } else {
                    InAppBillingHandler.resetIdentityForRebind(claimCid)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG: identity reset failed: ${e.message}", e)
                false
            }
            withContext(Dispatchers.Main) {
                val ctx = context ?: return@withContext
                if (cleared) {
                    DeviceAuthErrorNotifier.cancel(ctx)
                    InAppBillingHandler.serverApiErrorLiveData.value = null
                    Toast.makeText(ctx, R.string.device_not_registered_reset_success,
                        Toast.LENGTH_LONG).show()
                    dismissAllowingStateLoss()
                } else {
                    ui.resetInFlight = false
                    isCancelable = true
                    Toast.makeText(ctx, R.string.device_not_registered_reset_failed,
                        Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * Routes the user to [CustomerSupportActivity], where the support email is
     * composed with a list of optional diagnostic attachments (subscription
     * status, state history, entitlement stats, wirelogs, etc.). The account
     * and device IDs are passed along so the description is pre-filled.
     */
    private fun openCustomerSupport(accountId: String, deviceIdPrefix: String) {
        try {
            CustomerSupportActivity.start(requireContext(), accountId, deviceIdPrefix)
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: failed to open customer support: ${e.message}", e)
        }
    }

    override fun dismiss() {
        if (isAdded && !isStateSaved) super.dismiss()
    }

    override fun dismissAllowingStateLoss() {
        if (isAdded) super.dismissAllowingStateLoss()
    }
}

