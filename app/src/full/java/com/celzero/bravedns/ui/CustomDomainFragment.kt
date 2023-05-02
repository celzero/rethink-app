/*
 * Copyright 2021 RethinkDNS and its authors
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
package com.celzero.bravedns.ui

import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.CustomDomainAdapter
import com.celzero.bravedns.databinding.DialogAddCustomDomainBinding
import com.celzero.bravedns.databinding.FragmentCustomDomainBinding
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.DomainRulesManager.isValidDomain
import com.celzero.bravedns.service.DomainRulesManager.isWildCardEntry
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.util.Constants.Companion.INTENT_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.removeLeadingAndTrailingDots
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import org.koin.android.ext.android.inject

class CustomDomainFragment :
    Fragment(R.layout.fragment_custom_domain), SearchView.OnQueryTextListener {

    private val b by viewBinding(FragmentCustomDomainBinding::bind)
    private var layoutManager: RecyclerView.LayoutManager? = null

    private val viewModel by inject<CustomDomainViewModel>()

    private var uid = UID_EVERYBODY

    companion object {
        fun newInstance(uid: Int): CustomDomainFragment {
            val args = Bundle()
            args.putInt(INTENT_UID, uid)
            val fragment = CustomDomainFragment()
            fragment.arguments = args
            return fragment
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
        uid = arguments?.getInt(INTENT_UID, UID_EVERYBODY) ?: UID_EVERYBODY
        b.cdaHeading.text = getString(R.string.cd_dialog_header, getAppName())

        b.cdaSearchView.setOnQueryTextListener(this)
        observeCustomRules()
        setupRecyclerView()
        setupClickListeners()

        b.cdaRecycler.requestFocus()
    }

    private fun getAppName(): String {
        if (uid == UID_EVERYBODY) {
            return getString(R.string.firewall_act_universal_tab)
        }

        val appNames = FirewallManager.getAppNamesByUid(uid)

        val packageCount = appNames.count()
        return if (packageCount >= 2) {
            getString(R.string.ctbs_app_other_apps, appNames[0], packageCount.minus(1).toString())
        } else {
            appNames[0]
        }
    }

    private fun setupRecyclerView() {
        layoutManager = LinearLayoutManager(requireContext())
        val adapter = CustomDomainAdapter(requireContext())
        b.cdaRecycler.layoutManager = layoutManager
        b.cdaRecycler.adapter = adapter

        viewModel.setUid(uid)
        viewModel.customDomains.observe(this as LifecycleOwner) {
            adapter.submitData(this.lifecycle, it)
        }
    }

    private fun setupClickListeners() {
        b.cdaAddFab.setOnClickListener { showAddDomainDialog() }

        b.cdaSearchDeleteIcon.setOnClickListener { showDomainRulesDeleteDialog() }
    }

    private fun observeCustomRules() {
        viewModel.domainRulesCount(uid).observe(viewLifecycleOwner) {
            if (it <= 0) {
                showNoRulesUi()
                hideRulesUi()
                return@observe
            }

            hideNoRulesUi()
            showRulesUi()
        }
    }

    private fun hideRulesUi() {
        b.cdaShowRulesRl.visibility = View.GONE
    }

    private fun showRulesUi() {
        b.cdaShowRulesRl.visibility = View.VISIBLE
    }

    private fun hideNoRulesUi() {
        b.cdaNoRulesRl.visibility = View.GONE
    }

    private fun showNoRulesUi() {
        b.cdaNoRulesRl.visibility = View.VISIBLE
    }

    /**
     * Shows dialog to add custom domain. Provides user option to user to add DOMAIN, TLD and
     * WILDCARD. If entered option and text-input is valid, then the dns requests will be filtered
     * based on it. User can either select the entered domain to be added in whitelist or blocklist.
     */
    private fun showAddDomainDialog() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(getString(R.string.cd_dialog_title))
        val dBind = DialogAddCustomDomainBinding.inflate(layoutInflater)
        dialog.setContentView(dBind.root)

        var selectedType: DomainRulesManager.DomainType = DomainRulesManager.DomainType.DOMAIN

        dBind.dacdDomainEditText.addTextChangedListener {
            if (it?.contains("*") == true) {
                dBind.dacdWildcardChip.isChecked = true
            }
        }

        dBind.dacdDomainChip.setOnCheckedChangeListener { _, isSelected ->
            if (isSelected) {
                selectedType = DomainRulesManager.DomainType.DOMAIN
                dBind.dacdDomainEditText.hint =
                    resources.getString(
                        R.string.cd_dialog_edittext_hint,
                        getString(R.string.lbl_domain)
                    )
                dBind.dacdTextInputLayout.hint =
                    resources.getString(
                        R.string.cd_dialog_edittext_hint,
                        getString(R.string.lbl_domain)
                    )
            }
        }

        dBind.dacdWildcardChip.setOnCheckedChangeListener { _, isSelected ->
            if (isSelected) {
                selectedType = DomainRulesManager.DomainType.WILDCARD
                dBind.dacdDomainEditText.hint =
                    resources.getString(
                        R.string.cd_dialog_edittext_hint,
                        getString(R.string.lbl_wildcard)
                    )
                dBind.dacdTextInputLayout.hint =
                    resources.getString(
                        R.string.cd_dialog_edittext_hint,
                        getString(R.string.lbl_wildcard)
                    )
            }
        }

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.attributes = lp

        dBind.dacdUrlTitle.text = getString(R.string.cd_dialog_title)
        dBind.dacdDomainEditText.hint =
            resources.getString(R.string.cd_dialog_edittext_hint, getString(R.string.lbl_domain))
        dBind.dacdTextInputLayout.hint =
            resources.getString(R.string.cd_dialog_edittext_hint, getString(R.string.lbl_domain))

        dBind.dacdBlockBtn.setOnClickListener {
            handleDomain(dBind, selectedType, DomainRulesManager.Status.BLOCK)
        }

        dBind.dacdTrustBtn.setOnClickListener {
            handleDomain(dBind, selectedType, DomainRulesManager.Status.TRUST)
        }

        dBind.dacdCancelBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun handleDomain(
        dBind: DialogAddCustomDomainBinding,
        selectedType: DomainRulesManager.DomainType,
        status: DomainRulesManager.Status
    ) {
        dBind.dacdFailureText.visibility = View.GONE
        val url = dBind.dacdDomainEditText.text.toString()
        when (selectedType) {
            DomainRulesManager.DomainType.WILDCARD -> {
                if (!isWildCardEntry(url)) {
                    dBind.dacdFailureText.text =
                        getString(R.string.cd_dialog_error_invalid_wildcard)
                    dBind.dacdFailureText.visibility = View.VISIBLE
                    return
                }
            }
            DomainRulesManager.DomainType.DOMAIN -> {
                if (!isValidDomain(url)) {
                    dBind.dacdFailureText.text = getString(R.string.cd_dialog_error_invalid_domain)
                    dBind.dacdFailureText.visibility = View.VISIBLE
                    return
                }
            }
        }

        insertDomain(removeLeadingAndTrailingDots(url), selectedType, status)
    }

    private fun insertDomain(
        domain: String,
        type: DomainRulesManager.DomainType,
        status: DomainRulesManager.Status
    ) {
        DomainRulesManager.addDomainRule(domain, status, type, uid = uid)
        Utilities.showToastUiCentered(
            requireContext(),
            resources.getString(R.string.cd_toast_added),
            Toast.LENGTH_SHORT
        )
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        viewModel.setFilter(query)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        viewModel.setFilter(query)
        return true
    }

    private fun showDomainRulesDeleteDialog() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.univ_delete_firewall_dialog_title)
        builder.setMessage(R.string.univ_delete_firewall_dialog_message)
        builder.setPositiveButton(getString(R.string.univ_ip_delete_dialog_positive)) { _, _ ->
            DomainRulesManager.deleteIpRulesByUid(uid)
            Utilities.showToastUiCentered(
                requireContext(),
                getString(R.string.univ_ip_delete_toast_success),
                Toast.LENGTH_SHORT
            )
        }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }
}
