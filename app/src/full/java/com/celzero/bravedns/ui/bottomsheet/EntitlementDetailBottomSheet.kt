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

import android.content.res.Configuration
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.BottomsheetEntitlementDetailBinding
import com.celzero.bravedns.databinding.LayoutEntitlementRowBinding
import com.celzero.bravedns.rpnproxy.RpnProxyManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Themes.Companion.getBottomSheetCurrentTheme
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities.showToastUiCentered
import com.celzero.bravedns.viewmodel.EntitlementDetailViewModel
import com.celzero.firestack.backend.RpnEntitlement
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class EntitlementDetailBottomSheet : BottomSheetDialogFragment() {
    private var _b: BottomsheetEntitlementDetailBinding? = null
    private val b get() = checkNotNull(_b) { "Binding accessed outside of view lifecycle" }

    private val persistentState by inject<PersistentState>()
    private val viewModel: EntitlementDetailViewModel by activityViewModel()

    override fun getTheme(): Int =
        getBottomSheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

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
        dialog?.window?.let { window ->
            Themes.applyBottomSheetSystemBarAppearance(window, isDarkThemeOn(), persistentState.theme)
        }
        initView()
        observeResetState()
        observeEntitlementState()
    }

    override fun dismiss() {
        if (isAdded && !isStateSaved) super.dismiss()
    }

    override fun dismissAllowingStateLoss() {
        if (isAdded) super.dismissAllowingStateLoss()
    }

    private fun initView() {
        b.btnDismiss.setOnClickListener { dismiss() }
        b.btnRestore.setOnClickListener { viewModel.reset() }

        // Fetch happens on the ViewModel scope (survives sheet dismissal); the
        // sheet only observes the state. Reset first so a Done from a previous
        // session is never replayed as stale data.
        viewModel.resetEntitlement()
        viewModel.loadEntitlement()
    }

    private fun observeEntitlementState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.entitlementState.collect { state ->
                when (state) {
                    is EntitlementDetailViewModel.EntitlementState.Idle,
                    is EntitlementDetailViewModel.EntitlementState.Loading -> {
                        // rows are rendered only once Done arrives
                    }
                    is EntitlementDetailViewModel.EntitlementState.Done -> {
                        val entitlement = state.entitlement
                        if (entitlement == null) {
                            showToastUiCentered(
                                requireContext(),
                                getString(R.string.blocklist_update_check_failure),
                                android.widget.Toast.LENGTH_SHORT
                            )
                            dismiss()
                        } else {
                            displayEntitlement(entitlement, state.activeEntitlement, state.who)
                        }
                    }
                }
            }
        }
    }

    private fun observeResetState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.resetState.collect { state ->
                when (state) {
                    is EntitlementDetailViewModel.ResetState.InProgress -> {
                        b.btnRestore.isEnabled = false
                        b.btnRestore.alpha = 0.55f
                        b.btnRestore.text = getString(R.string.rpn_restore_in_progress_btn)
                    }
                    is EntitlementDetailViewModel.ResetState.Done -> {
                        b.btnRestore.isEnabled = true
                        b.btnRestore.alpha = 1f
                        b.btnRestore.text = getString(R.string.rpn_restore_confirm_title)
                        handleResetResult(state.result)
                        viewModel.onResetConsumed()
                    }
                    is EntitlementDetailViewModel.ResetState.NoTunnel -> {
                        b.btnRestore.isEnabled = true
                        b.btnRestore.alpha = 1f
                        b.btnRestore.text = getString(R.string.rpn_restore_confirm_title)
                        showToastUiCentered(requireContext(), getString(R.string.ssv_toast_start_rethink), android.widget.Toast.LENGTH_SHORT)
                        viewModel.onResetConsumed()
                    }
                    is EntitlementDetailViewModel.ResetState.Idle -> {
                        // no-op
                    }
                }
            }
        }
    }

    private fun handleResetResult(result: RpnProxyManager.ResetResult) {
        when (result) {
            is RpnProxyManager.ResetResult.Success -> {
                showToastUiCentered(requireContext(), getString(R.string.rpn_restore_success), android.widget.Toast.LENGTH_SHORT)
                dismiss()
            }
            is RpnProxyManager.ResetResult.Failure -> {
                showToastUiCentered(requireContext(), getString(R.string.rpn_restore_failure, result.reason), android.widget.Toast.LENGTH_SHORT)
            }
        }
    }

    private fun displayEntitlement(entitlement: RpnEntitlement, activeEntitlement: RpnEntitlement?, who: String?) {
        var everythingSame = true
        var canRestore = false

        setupRow(b.rowRpnActive, "RPN Active Status", RpnProxyManager.isRpnActive().toString().capitalizeWords())

        fun compareAndSet(row: LayoutEntitlementRowBinding, label: String, val1: String?, val2: String?, same: Boolean, triggerRestore: Boolean = false, showDivider: Boolean = true) {
            val v1 = if (val1.isNullOrBlank()) "N/A" else val1
            val v2 = if (val2.isNullOrBlank()) "N/A" else val2
            if (activeEntitlement != null && !same) {
                everythingSame = false
                if (triggerRestore) canRestore = true
                setupRow(row, label, "$v1\n$v2", showDivider)
            } else {
                setupRow(row, label, v1, showDivider)
            }
        }

        // Status
        val status1 = entitlement.status()
        val status2 = activeEntitlement?.status()
        compareAndSet(b.rowStatus, "Status", status1.capitalizeWords(), status2?.capitalizeWords(), valuesEqual(status1, status2))

        // CID: show only the first 12 digits of both entitlements. A mismatch here
        // warrants a restore.
        val cid1 = entitlement.cid().take(12)
        val cid2 = activeEntitlement?.cid()?.take(12)
        compareAndSet(b.rowCid, "Client ID (CID)", cid1, cid2, valuesEqual(cid1, cid2), triggerRestore = true)

        // DID: show only the first 4 digits of both entitlements. A mismatch here
        // warrants a restore.
        val did1 = entitlement.did().take(4)
        val did2 = activeEntitlement?.did()?.take(4)
        compareAndSet(b.rowDid, "Device ID (DID)", did1, did2, valuesEqual(did1, did2), triggerRestore = true)

        setupRow(b.rowWho, "Identifier (WHO)", who)

        // Expiry: compare the raw timestamps, format only for display. Comparing the
        // formatted relative strings would miss small but real differences (both would
        // render as e.g. "in 2 days"). A mismatch here warrants a restore.
        val activeExpiry = activeEntitlement?.expiry()
        val exp1 = UIUtils.formatToRelativeTime(requireContext(), entitlement.expiry())
        val exp2 = activeExpiry?.let { UIUtils.formatToRelativeTime(requireContext(), it) }
        val expSame = activeExpiry == null || entitlement.expiry() == activeExpiry
        compareAndSet(b.rowExpiry, "Expiry", exp1, exp2, expSame, triggerRestore = true)

        // Provider ID
        val provider1 = entitlement.providerID()
        val provider2 = activeEntitlement?.providerID()
        compareAndSet(b.rowProvider, "Provider ID", provider1, provider2, valuesEqual(provider1, provider2))

        // Token: expected to differ after a refresh, so it is excluded from the
        // comparison; show only the (masked) stored value.
        val token = entitlement.token()
        val maskedToken = if (token.length > 8) token.take(4) + "..." + token.takeLast(4) else token
        setupRow(b.rowToken, "Token", maskedToken)

        // Allow Restore
        val allowRestore1 = entitlement.allowRestore().toString()
        val allowRestore2 = activeEntitlement?.allowRestore()?.toString()
        compareAndSet(b.rowAllowRestore, "Allow Restore", allowRestore1.capitalizeWords(), allowRestore2?.capitalizeWords(), valuesEqual(allowRestore1, allowRestore2))

        // Is Test
        val test1 = entitlement.test().toString()
        val test2 = activeEntitlement?.test()?.toString()
        compareAndSet(b.rowTest, "Is Test", test1.capitalizeWords(), test2?.capitalizeWords(), valuesEqual(test1, test2), showDivider = false)

        b.btnRestore.visibility = if (canRestore) View.VISIBLE else View.GONE

        if (everythingSame && activeEntitlement != null) {
            b.tvComparisonInfo.text = getString(R.string.unicode_check_sign)
            b.tvComparisonInfo.visibility = View.VISIBLE
        } else {
            b.tvComparisonInfo.visibility = View.GONE
        }
    }

    /**
     * Compares two values treating blank values as "N/A" placeholders: two blanks are
     * equal, one blank and one non-blank are not, otherwise plain equality. This mirrors
     * the pre-normalisation semantics of [displayEntitlement]'s row rendering.
     */
    private fun valuesEqual(a: String?, b: String?): Boolean {
        val aBlank = a.isNullOrBlank()
        val bBlank = b.isNullOrBlank()
        return when {
            aBlank && bBlank -> true
            aBlank || bBlank -> false
            else -> a == b
        }
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
}
