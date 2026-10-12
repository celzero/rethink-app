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
import com.celzero.bravedns.databinding.BottomsheetDeviceAuthErrorBinding
import com.celzero.bravedns.iab.DeviceAuthErrorNotifier
import com.celzero.bravedns.iab.InAppBillingHandler
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject

/**
 * Bottom sheet shown when a `/g/acc` or `/reg` API call returns HTTP 401.
 */
class DeviceAuthErrorBottomSheet : BaseBottomSheetDialogFragment() {

    private var _binding: BottomsheetDeviceAuthErrorBinding? = null
    private val binding
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private var entitlementCid: String = ""

    private var resetInFlight: Boolean = false

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

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomsheetDeviceAuthErrorBinding.inflate(inflater, container, false)
        return binding.root
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
        binding.btnResetIdentity.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun populateDetails(accountId: String, deviceIdPrefix: String) {
        // Show masked account ID (first 12 + last 4 chars); fall back to a placeholder
        // if blank so the layout never appears empty.
        binding.tvAccountId.text = maskCid(accountId).ifBlank {
            getString(R.string.device_auth_error_id_unavailable)
        }

        // Show first 8 chars of device ID followed by "…" to indicate truncation.
        binding.tvDeviceIdPrefix.text = if (deviceIdPrefix.isNotBlank()) {
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
        binding.btnEmailSupport.setOnClickListener {
            openCustomerSupport(accountId, deviceIdPrefix)
        }

        binding.btnResetIdentity.setOnClickListener {
            confirmIdentityReset()
        }

        binding.btnDismiss.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    private fun confirmIdentityReset() {
        MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(R.string.device_auth_error_btn_reset)
            .setMessage(R.string.device_not_registered_reset_confirm_msg)
            .setPositiveButton(R.string.lbl_proceed) { _, _ ->
                resetIdentity()
            }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    private fun resetIdentity() {
        if (resetInFlight) return
        resetInFlight = true
        isCancelable = false
        binding.btnResetIdentity.isEnabled = false
        binding.btnEmailSupport.isEnabled = false
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
                    resetInFlight = false
                    isCancelable = true
                    binding.btnResetIdentity.isEnabled = true
                    binding.btnEmailSupport.isEnabled = true
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

