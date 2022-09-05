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

import android.app.Activity
import android.app.Dialog
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.LifecycleOwner
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.CustomIpAdapter
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.databinding.DialogAddCustomIpBinding
import com.celzero.bravedns.databinding.DialogCustomIpBinding
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import inet.ipaddr.IPAddressString

class CustomIpDialog(val activity: Activity, val viewModel: CustomIpViewModel, themeId: Int) :
        Dialog(activity, themeId), SearchView.OnQueryTextListener {
    private var layoutManager: RecyclerView.LayoutManager? = null
    private lateinit var b: DialogCustomIpBinding
    private lateinit var adapter: CustomIpAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(true)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = DialogCustomIpBinding.inflate(layoutInflater)
        setContentView(b.root)

        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                          WindowManager.LayoutParams.MATCH_PARENT)

        b.cipSearchView.setOnQueryTextListener(this)
        observeCustomRules()
        setupRecyclerView()
        setupClickListeners()

        b.cipRecycler.requestFocus()
    }

    private fun observeCustomRules() {
        viewModel.customIpSize.observe(activity as LifecycleOwner) {
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
        b.customDialogShowRulesRl.visibility = View.GONE
    }

    private fun showRulesUi() {
        b.customDialogShowRulesRl.visibility = View.VISIBLE
    }

    private fun hideNoRulesUi() {
        b.customDialogNoRulesRl.visibility = View.GONE
    }

    private fun showNoRulesUi() {
        b.customDialogNoRulesRl.visibility = View.VISIBLE
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
        layoutManager = LinearLayoutManager(activity)
        adapter = CustomIpAdapter(activity)

        b.cipRecycler.layoutManager = layoutManager
        b.cipRecycler.adapter = adapter

        viewModel.customIpDetails.observe(activity as LifecycleOwner) {
            adapter.submitData(activity.lifecycle, it)
        }
    }

    private fun setupClickListeners() {
        b.customDialogAddFab.setOnClickListener {
            showAddIpDialog()
        }

        b.cipSearchDeleteIcon.setOnClickListener {
            showIpRulesDeleteDialog()
        }
    }

    /**
     * Shows dialog to add custom IP. Provides user option to user to add ips.
     * validates the entered input, if valid then will add it to the custom ip
     * database table.
     */
    private fun showAddIpDialog() {
        val dialog = Dialog(activity)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(activity.getString(R.string.ci_dialog_title))
        val dBind = DialogAddCustomIpBinding.inflate(layoutInflater)
        dialog.setContentView(dBind.root)

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.attributes = lp

        dBind.daciIpTitle.text = activity.getString(R.string.ci_dialog_title)

        dBind.daciIpEditText.addTextChangedListener {
            if (dBind.daciFailureTextView.isVisible) {
                dBind.daciFailureTextView.visibility = View.GONE
            }
        }

        dBind.daciAddBtn.setOnClickListener {
            val input = dBind.daciIpEditText.text.toString()

            val ipString = Utilities.removeLeadingAndTrailingDots(input)
            val ip = IPAddressString(ipString).address
            if (ip == null || ipString.isEmpty()) {
                dBind.daciFailureTextView.text = activity.resources.getString(
                    R.string.ci_dialog_error_invalid_ip)
                dBind.daciFailureTextView.visibility = View.VISIBLE
                return@setOnClickListener
            }

            dBind.daciIpEditText.text.clear()
            insertCustomIpv4(input)

        }

        dBind.daciCancelBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun insertCustomIpv4(ipv4: String) {
        IpRulesManager.addIpRule(IpRulesManager.UID_EVERYBODY, ipv4,
                                 IpRulesManager.IpRuleStatus.BLOCK)
        Utilities.showToastUiCentered(activity,
                                      activity.getString(R.string.ci_dialog_added_success),
                                      Toast.LENGTH_SHORT)
    }

    private fun showIpRulesDeleteDialog() {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(R.string.univ_delete_firewall_dialog_title)
        builder.setMessage(R.string.univ_delete_firewall_dialog_message)
        builder.setPositiveButton(
            activity.getString(R.string.univ_ip_delete_dialog_positive)) { _, _ ->
            IpRulesManager.clearAllIpRules()
            Utilities.showToastUiCentered(activity,
                                          activity.getString(R.string.univ_ip_delete_toast_success),
                                          Toast.LENGTH_SHORT)
        }

        builder.setNegativeButton(
            activity.getString(R.string.univ_ip_delete_dialog_negative)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }

}
