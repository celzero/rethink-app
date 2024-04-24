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
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.CustomIpAdapter
import com.celzero.bravedns.databinding.DialogAddCustomIpBinding
import com.celzero.bravedns.databinding.FragmentCustomIpBinding
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.ui.activity.CustomRulesActivity
import com.celzero.bravedns.util.Constants.Companion.INTENT_UID
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import inet.ipaddr.IPAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.viewmodel.ext.android.viewModel

class CustomIpFragment : Fragment(R.layout.fragment_custom_ip), SearchView.OnQueryTextListener {

    private var layoutManager: RecyclerView.LayoutManager? = null
    private val b by viewBinding(FragmentCustomIpBinding::bind)
    private val viewModel: CustomIpViewModel by viewModel()
    private var uid = UID_EVERYBODY
    private var rules = CustomRulesActivity.RULES.APP_SPECIFIC_RULES

    companion object {
        fun newInstance(uid: Int, rules: CustomRulesActivity.RULES): CustomIpFragment {
            val args = Bundle()
            args.putInt(INTENT_UID, uid)
            args.putInt(CustomRulesActivity.INTENT_RULES, rules.type)
            val fragment = CustomIpFragment()
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
        rules =
            arguments?.getInt(CustomRulesActivity.INTENT_RULES)?.let {
                CustomRulesActivity.RULES.getType(it)
            } ?: CustomRulesActivity.RULES.APP_SPECIFIC_RULES
        b.cipSearchView.setOnQueryTextListener(this)
        setupRecyclerView()
        setupClickListeners()

        b.cipRecycler.requestFocus()
    }

    private fun observeAppSpecificRules() {
        viewModel.ipRulesCount(uid).observe(viewLifecycleOwner) {
            if (it <= 0) {
                showNoRulesUi()
                hideRulesUi()
                return@observe
            }

            hideNoRulesUi()
            showRulesUi()
        }
    }

    private fun observeAllAppsRules() {
        viewModel.allIpRulesCount().observe(viewLifecycleOwner) {
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
        b.cipShowRulesRl.visibility = View.GONE
    }

    private fun showRulesUi() {
        b.cipShowRulesRl.visibility = View.VISIBLE
    }

    private fun hideNoRulesUi() {
        b.cipNoRulesRl.visibility = View.GONE
    }

    private fun showNoRulesUi() {
        b.cipNoRulesRl.visibility = View.VISIBLE
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        viewModel.setFilter(query)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        viewModel.setFilter(query)
        return true
    }

    private fun setupRecyclerView() {
        layoutManager = CustomLinearLayoutManager(requireContext())
        b.cipRecycler.layoutManager = layoutManager
        b.cipRecycler.setHasFixedSize(true)
        if (rules == CustomRulesActivity.RULES.APP_SPECIFIC_RULES) {
            b.cipAddFab.visibility = View.VISIBLE
            setupAdapterForApp()
        } else {
            b.cipAddFab.visibility = View.GONE
            setupAdapterForAllApps()
        }
    }

    private fun setupAdapterForApp() {
        observeAppSpecificRules()
        val adapter =
            CustomIpAdapter(requireContext(), CustomRulesActivity.RULES.APP_SPECIFIC_RULES)
        viewModel.setUid(uid)
        viewModel.customIpDetails.observe(viewLifecycleOwner) {
            adapter.submitData(this.lifecycle, it)
        }
        b.cipRecycler.adapter = adapter
    }

    private fun setupAdapterForAllApps() {
        observeAllAppsRules()
        val adapter = CustomIpAdapter(requireContext(), CustomRulesActivity.RULES.ALL_RULES)
        viewModel.allIpRules.observe(viewLifecycleOwner) { adapter.submitData(this.lifecycle, it) }
        b.cipRecycler.adapter = adapter
    }

    private fun setupClickListeners() {
        // make fab to bring to front of the view as click listener is not working
        // on some device
        b.cipAddFab.bringToFront()
        b.cipAddFab.setOnClickListener { showAddIpDialog() }

        b.cipSearchDeleteIcon.setOnClickListener { showIpRulesDeleteDialog() }
    }

    /**
     * Shows dialog to add custom IP. Provides user option to user to add ips. validates the entered
     * input, if valid then will add it to the custom ip database table.
     */
    private fun showAddIpDialog() {
        val dBind = DialogAddCustomIpBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext()).setView(dBind.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        dialog.show()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(true)
        dialog.window?.attributes = lp

        dBind.daciIpTitle.text = getString(R.string.ci_dialog_title)

        if (uid == UID_EVERYBODY) {
            dBind.daciTrustBtn.text = getString(R.string.bypass_universal)
        } else {
            dBind.daciTrustBtn.text = getString(R.string.ci_trust_rule)
        }

        dBind.daciIpEditText.addTextChangedListener {
            if (dBind.daciFailureTextView.isVisible) {
                dBind.daciFailureTextView.visibility = View.GONE
            }
        }

        dBind.daciBlockBtn.setOnClickListener {
            handleInsertIp(dBind, IpRulesManager.IpRuleStatus.BLOCK)
        }

        dBind.daciTrustBtn.setOnClickListener {
            if (uid == UID_EVERYBODY) {
                handleInsertIp(dBind, IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL)
            } else {
                handleInsertIp(dBind, IpRulesManager.IpRuleStatus.TRUST)
            }
        }

        dBind.daciCancelBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun handleInsertIp(
        dBind: DialogAddCustomIpBinding,
        status: IpRulesManager.IpRuleStatus
    ) {
        ui {
            val input = dBind.daciIpEditText.text.toString()
            val ipString = Utilities.removeLeadingAndTrailingDots(input)
            var ip: IPAddress? = null
            var port: Int = 0

            // chances of creating NetworkOnMainThread exception, handling with io operation
            ioCtx {
                val ipPair = IpRulesManager.getIpNetPort(ipString)
                ip = ipPair.first
                port = ipPair.second
            }

            if (ip == null || ipString.isEmpty()) {
                dBind.daciFailureTextView.text = getString(R.string.ci_dialog_error_invalid_ip)
                dBind.daciFailureTextView.visibility = View.VISIBLE
                return@ui
            }

            dBind.daciIpEditText.text.clear()
            insertCustomIp(ip, port, status)
        }
    }

    private fun insertCustomIp(ip: IPAddress?, port: Int?, status: IpRulesManager.IpRuleStatus) {
        if (ip == null) return

        io { IpRulesManager.addIpRule(uid, ip, port, status) }
        Utilities.showToastUiCentered(
            requireContext(),
            getString(R.string.ci_dialog_added_success),
            Toast.LENGTH_SHORT
        )
    }

    private fun showIpRulesDeleteDialog() {
        val builder = MaterialAlertDialogBuilder(requireContext())
        builder.setTitle(R.string.univ_delete_firewall_dialog_title)
        builder.setMessage(R.string.univ_delete_firewall_dialog_message)
        builder.setPositiveButton(getString(R.string.univ_ip_delete_dialog_positive)) { _, _ ->
            io {
                if (rules == CustomRulesActivity.RULES.APP_SPECIFIC_RULES) {
                    IpRulesManager.deleteRulesByUid(uid)
                } else {
                    IpRulesManager.deleteAllAppsRules()
                }
            }
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

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.Main) { f() }
    }
}
