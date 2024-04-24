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
package com.celzero.bravedns.ui.fragment

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.widget.SearchView
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
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
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.util.Constants.Companion.INTENT_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.removeLeadingAndTrailingDots
import com.celzero.bravedns.viewmodel.CustomDomainViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class CustomDomainFragment :
    Fragment(R.layout.fragment_custom_domain), SearchView.OnQueryTextListener {

    private val b by viewBinding(FragmentCustomDomainBinding::bind)
    private var layoutManager: RecyclerView.LayoutManager? = null

    private val viewModel by inject<CustomDomainViewModel>()

    private var uid = UID_EVERYBODY
    private var rule = CustomRulesActivity.RULES.APP_SPECIFIC_RULES

    companion object {
        fun newInstance(uid: Int, rules: CustomRulesActivity.RULES): CustomDomainFragment {
            val args = Bundle()
            args.putInt(INTENT_UID, uid)
            args.putInt(CustomRulesActivity.INTENT_RULES, rules.type)
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
        rule =
            arguments?.getInt(CustomRulesActivity.INTENT_RULES)?.let {
                CustomRulesActivity.RULES.getType(it)
            } ?: CustomRulesActivity.RULES.APP_SPECIFIC_RULES

        b.cdaSearchView.setOnQueryTextListener(this)
        setupRecyclerView()
        setupClickListeners()

        b.cdaRecycler.requestFocus()
    }

    private fun setupRecyclerView() {
        layoutManager = LinearLayoutManager(requireContext())
        b.cdaRecycler.layoutManager = layoutManager
        b.cdaRecycler.setHasFixedSize(true)
        if (rule == CustomRulesActivity.RULES.APP_SPECIFIC_RULES) {
            b.cdaAddFab.visibility = View.VISIBLE
            setupAppSpecificRules(rule)
        } else {
            b.cdaAddFab.visibility = View.GONE
            setupAllRules(rule)
        }
    }

    private fun setupAppSpecificRules(rule: CustomRulesActivity.RULES) {
        observeCustomRules()
        val adapter = CustomDomainAdapter(requireContext(), rule)
        b.cdaRecycler.adapter = adapter
        viewModel.setUid(uid)
        viewModel.customDomains.observe(this as LifecycleOwner) {
            adapter.submitData(this.lifecycle, it)
        }
    }

    private fun setupAllRules(rule: CustomRulesActivity.RULES) {
        observeAllRules()
        val adapter = CustomDomainAdapter(requireContext(), rule)
        b.cdaRecycler.adapter = adapter
        viewModel.allDomainRules.observe(this as LifecycleOwner) {
            adapter.submitData(this.lifecycle, it)
        }
    }

    private fun setupClickListeners() {
        // see CustomIpFragment#setupClickListeners#bringToFront()
        b.cdaAddFab.bringToFront()
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

    private fun observeAllRules() {
        viewModel.allDomainRulesCount().observe(viewLifecycleOwner) {
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
        val dBind = DialogAddCustomDomainBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(dBind.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        dialog.show()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(true)
        dialog.window?.attributes = lp

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
        io { DomainRulesManager.addDomainRule(domain, status, type, uid = uid) }
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
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(R.string.univ_delete_firewall_dialog_title)
        builder.setMessage(R.string.univ_delete_firewall_dialog_message)
        builder.setPositiveButton(getString(R.string.univ_ip_delete_dialog_positive)) { _, _ ->
            io {
                if (rule == CustomRulesActivity.RULES.APP_SPECIFIC_RULES) {
                    DomainRulesManager.deleteRulesByUid(uid)
                } else {
                    DomainRulesManager.deleteAllRules()
                }
            }
            Utilities.showToastUiCentered(
                requireContext(),
                getString(R.string.cd_deleted_toast),
                Toast.LENGTH_SHORT
            )
        }

        builder.setNegativeButton(getString(R.string.lbl_cancel)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }
}
