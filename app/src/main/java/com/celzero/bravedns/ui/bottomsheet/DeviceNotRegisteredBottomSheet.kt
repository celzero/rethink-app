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
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomsheetDeviceNotRegisteredBinding
import com.celzero.bravedns.iab.DeviceNotRegisteredNotifier
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
 * Bottom sheet shown when the CID embedded in the active entitlement payload
 * (the authoritative Google Play CID) differs from the locally stored CID and
 * the server cannot return a DID for the entitlement CID.
 *
 * This means the device is not registered under the subscription account.
 * When the stored cid is a foreign one (never rebindable), the sheet offers a
 * guarded identity reset that discards the stored identity so the active
 * purchase can claim the store; otherwise the user is guided to contact
 * support with their account details.
 */
class DeviceNotRegisteredBottomSheet : BaseBottomSheetDialogFragment() {

    private var _binding: BottomsheetDeviceNotRegisteredBinding? = null
    private val binding
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private var entitlementCid: String = ""

    private var resetInFlight: Boolean = false

    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAG = "DeviceNotRegisteredBS"

        private const val ARG_ENTITLEMENT_CID = "entitlement_cid"
        private const val ARG_STORED_CID = "stored_cid"
        private const val ARG_DEVICE_ID_PREFIX = "device_id_prefix"

        private const val SUPPORT_EMAIL = "hello@celzero.com"

        /**
         * All data is passed via [Bundle] args so the fragment survives
         * configuration changes without holding a live reference to the error object.
         */
        fun newInstance(error: ServerApiError.DeviceNotRegistered): DeviceNotRegisteredBottomSheet {
            return DeviceNotRegisteredBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_ENTITLEMENT_CID,  error.entitlementCid)
                    putString(ARG_STORED_CID,        error.storedCid)
                    putString(ARG_DEVICE_ID_PREFIX,  error.deviceIdPrefix)
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
        _binding = BottomsheetDeviceNotRegisteredBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }

        val args          = requireArguments()
        entitlementCid = args.getString(ARG_ENTITLEMENT_CID, "")
        val storedCid      = args.getString(ARG_STORED_CID, "")
        val deviceIdPrefix = args.getString(ARG_DEVICE_ID_PREFIX, "")

        populateDetails(entitlementCid, deviceIdPrefix)
        setupButtons(entitlementCid, storedCid, deviceIdPrefix)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun populateDetails(entitlementCid: String, deviceIdPrefix: String) {
        binding.tvEntitlementCid.text = maskCid(entitlementCid)
            .ifBlank { getString(R.string.device_auth_error_id_unavailable) }

        binding.tvDeviceIdPrefix.text = deviceIdPrefix.ifBlank {
            getString(R.string.device_auth_error_id_unavailable)
        }
    }

    /**
     * Masks a CID to the first 12 characters; shorter CIDs are shown in full.
     *
     * Example: "abcdefghijklmnopqrstuvwx" → "abcdefghijkl"
     */
    private fun maskCid(cid: String): String {
        if (cid.length <= 16) return cid
        return cid.take(12)
    }

    private fun setupButtons(entitlementCid: String, storedCid: String, deviceIdPrefix: String) {
        binding.btnEmailSupport.setOnClickListener {
            openEmailClient(entitlementCid, deviceIdPrefix)
        }
        binding.btnDismiss.setOnClickListener {
            dismissAllowingStateLoss()
        }
        // The stored identity can never be rebound to the entitlement cid while the
        // store holds a foreign cid, so offer to discard it and let the active
        // purchase claim the empty store.
        val resettable = entitlementCid.isNotBlank() && storedCid.isNotBlank() &&
            entitlementCid != storedCid
        if (resettable) {
            binding.btnResetIdentity.visibility = View.VISIBLE
            binding.btnResetIdentity.setOnClickListener {
                confirmIdentityReset()
            }
        }
    }

    private fun confirmIdentityReset() {
        MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim)
            .setTitle(R.string.server_selection_error_retry)
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
            val cleared = try {
                InAppBillingHandler.resetIdentityForRebind(entitlementCid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Logger.e(LOG_IAB, "$TAG: identity reset failed: ${e.message}", e)
                false
            }
            withContext(Dispatchers.Main) {
                val ctx = context ?: return@withContext
                if (cleared) {
                    DeviceNotRegisteredNotifier.cancel(ctx)
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
     * Opens the device's email client pre-filled with support address, subject,
     * and a body that includes the entitlement CID and device ID prefix so the
     * user does not have to type them manually.
     */
    private fun openEmailClient(entitlementCid: String, deviceIdPrefix: String) {
        try {
            val subject = getString(R.string.device_not_registered_email_subject)
            val body = buildString {
                appendLine()
                appendLine(getString(R.string.device_not_registered_email_body_details))
                appendLine("  • ${getString(R.string.device_not_registered_entitlement_cid_label)}: $entitlementCid")
                appendLine("  • ${getString(R.string.device_auth_error_device_id_label)}: $deviceIdPrefix")
                appendLine()
            }

            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = "mailto:".toUri()
                putExtra(Intent.EXTRA_EMAIL,   arrayOf(SUPPORT_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TEXT,    body)
            }

            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(intent)
            } else {
                val fallback = Intent(Intent.ACTION_VIEW,
                    "mailto:$SUPPORT_EMAIL?subject=${Uri.encode(subject)}".toUri())
                startActivity(fallback)
            }
        } catch (e: Exception) {
            Logger.e(LOG_IAB, "$TAG: failed to open email client: ${e.message}", e)
        }
    }

    override fun dismiss() {
        if (isAdded && !isStateSaved) super.dismiss()
    }

    override fun dismissAllowingStateLoss() {
        if (isAdded) super.dismissAllowingStateLoss()
    }
}


