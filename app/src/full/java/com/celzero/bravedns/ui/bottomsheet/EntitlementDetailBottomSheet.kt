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

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomsheetEntitlementDetailBinding
import com.celzero.bravedns.databinding.LayoutEntitlementRowBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.firestack.backend.RpnEntitlement
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class EntitlementDetailBottomSheet : BottomSheetDialogFragment() {
    private var _b: BottomsheetEntitlementDetailBinding? = null
    private val b get() = checkNotNull(_b) { "Binding accessed outside of view lifecycle" }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _b = BottomsheetEntitlementDetailBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setStyle(STYLE_NORMAL, R.style.BottomSheetDialogTheme)
        initView()
    }

    override fun dismiss() {
        if (isAdded && !isStateSaved) super.dismiss()
    }

    override fun dismissAllowingStateLoss() {
        if (isAdded) super.dismissAllowingStateLoss()
    }

    private fun initView() {
        b.btnDismiss.setOnClickListener { dismiss() }

        io {
            val entitlement = RpnProxyManager.getEntitlementDetails()
            val who = VpnController.getWinIdentifier()

            uiCtx {
                if (entitlement == null) {
                    showToastUiCentered(
                        requireContext(),
                        getString(com.celzero.bravedns.R.string.blocklist_update_check_failure),
                        android.widget.Toast.LENGTH_SHORT
                    )
                    dismiss()
                    return@uiCtx
                }
                displayEntitlement(entitlement, who)
            }
        }
    }

    private fun displayEntitlement(entitlement: RpnEntitlement, who: String?) {
        setupRow(b.rowStatus, "Status", entitlement.status().capitalizeWords())
        setupRow(b.rowCid, "Client ID (CID)", entitlement.cid().take(12))
        setupRow(b.rowDid, "Device ID (DID)", entitlement.did().take(4))
        setupRow(b.rowWho, "Identifier (WHO)", who)
        setupRow(b.rowExpiry, "Expiry", entitlement.expiry())
        setupRow(b.rowProvider, "Provider ID", entitlement.providerID())
        setupRow(b.rowToken, "Token", entitlement.token())
        setupRow(b.rowAllowRestore, "Allow Restore", entitlement.allowRestore().toString().capitalizeWords())
        setupRow(b.rowTest, "Is Test", entitlement.test().toString().capitalizeWords(), showDivider = false)
    }

    private fun setupRow(
        rowBinding: LayoutEntitlementRowBinding,
        label: String,
        value: String?,
        showDivider: Boolean = true
    ) {
        rowBinding.tvLabel.text = label
        rowBinding.tvValue.text = if (value.isNullOrBlank()) "N/A" else value
        rowBinding.divider.visibility = if (showDivider) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _b = null
    }

    companion object {
        fun newInstance(): EntitlementDetailBottomSheet {
            return EntitlementDetailBottomSheet()
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) = withContext(Dispatchers.Main) { f() }
    private fun io(f: suspend () -> Unit) = lifecycleScope.launch(Dispatchers.IO) { f() }
}
