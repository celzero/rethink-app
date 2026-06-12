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

import Logger
import Logger.LOG_IAB
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomsheetDeviceAuthErrorBinding
import com.celzero.bravedns.iab.ServerApiError
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import androidx.core.net.toUri
import org.koin.android.ext.android.inject

/**
 * Bottom sheet shown when a `/g/acc` or `/reg` API call returns HTTP 401.
 */
class DeviceAuthErrorBottomSheet : BottomSheetDialogFragment() {

    private var _binding: BottomsheetDeviceAuthErrorBinding? = null
    private val binding
        get() = checkNotNull(_binding)
        { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()

    companion object {
        private const val TAG = "DeviceAuthErrorBS"

        private const val ARG_ACCOUNT_ID = "account_id"
        private const val ARG_DEVICE_ID_PREFIX = "device_id_prefix"
        private const val ARG_OPERATION = "operation"

        private const val SUPPORT_EMAIL = "hello@celzero.com"
        private const val EMAIL_SUBJECT = "Device Authorization Issue - RPN"

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
        val accountId = args.getString(ARG_ACCOUNT_ID, "")
        val deviceIdPrefix = args.getString(ARG_DEVICE_ID_PREFIX, "")

        populateDetails(accountId, deviceIdPrefix)
        setupButtons(accountId, deviceIdPrefix)
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
            openEmailClient(accountId, deviceIdPrefix)
        }

        binding.btnDismiss.setOnClickListener {
            dismissAllowingStateLoss()
        }
    }

    /**
     * Opens the device's email client pre-filled with the support address,
     * subject, and a body that includes the account/device IDs so the customer
     * does not have to type them manually.
     */
    private fun openEmailClient(accountId: String, deviceIdPrefix: String) {
        try {
            val body = buildString {
                appendLine(getString(R.string.device_auth_error_email_body_greeting))
                appendLine()
                appendLine(getString(R.string.device_auth_error_email_body_details))
                appendLine("  • ${getString(R.string.device_auth_error_account_id_label)}: $accountId")
                appendLine("  • ${getString(R.string.device_auth_error_device_id_label)}: $deviceIdPrefix")
                appendLine()
                appendLine(getString(R.string.device_auth_error_email_body_closing))
            }

            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = "mailto:".toUri()
                putExtra(Intent.EXTRA_EMAIL,   arrayOf(SUPPORT_EMAIL))
                putExtra(Intent.EXTRA_SUBJECT, EMAIL_SUBJECT)
                putExtra(Intent.EXTRA_TEXT,    body)
            }

            if (intent.resolveActivity(requireContext().packageManager) != null) {
                startActivity(intent)
            } else {
                // Fallback: open mail URI directly so Android can prompt the user
                // to choose or install an email app.
                val fallback = Intent(Intent.ACTION_VIEW,
                    "mailto:$SUPPORT_EMAIL?subject=${Uri.encode(EMAIL_SUBJECT)}".toUri())
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

