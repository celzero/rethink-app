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
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
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

class CustomIpDialog(val activity: Activity, val viewModel: CustomIpViewModel, themeId: Int) :
        Dialog(activity, themeId), SearchView.OnQueryTextListener {
    private var layoutManager: RecyclerView.LayoutManager? = null
    private lateinit var b: DialogCustomIpBinding
    private lateinit var adapter: CustomIpAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        b = DialogCustomIpBinding.inflate(layoutInflater)
        setContentView(b.root)

        window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                          WindowManager.LayoutParams.MATCH_PARENT)

        setupRecyclerView()
        setupClickListeners()
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

        viewModel.customIpDetails.observe(activity as LifecycleOwner,
                                          androidx.lifecycle.Observer(adapter::submitList))
    }

    private fun setupClickListeners() {
        b.cipAddIp.setOnClickListener {
            showAddIpDialog()
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
        dialog.setTitle(activity.getString(R.string.cd_dialog_title))
        val dBind = DialogAddCustomIpBinding.inflate(layoutInflater)
        dialog.setContentView(dBind.root)

        val types = IpRulesManager.IPRuleType.getInputTypes()
        var selectedInputType: IpRulesManager.IPRuleType = IpRulesManager.IPRuleType.IPV4

        val aa: ArrayAdapter<*> = ArrayAdapter<Any?>(activity, android.R.layout.simple_spinner_item,
                                                     types)
        aa.setDropDownViewResource(R.layout.custom_ip_spinner_item)
        dBind.daciSpinner.adapter = aa

        val lp = WindowManager.LayoutParams()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT
        dialog.show()
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        dialog.window?.attributes = lp


        dBind.daciIpTitle.text = activity.getString(R.string.cd_dialog_title)

        dBind.daciSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                // no-op
            }

            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int,
                                        id: Long) {
                if (position == 2 || position == 3) {
                    Utilities.showToastUiCentered(activity, activity.resources.getString(
                        R.string.ipv6_coming_soon_toast), Toast.LENGTH_SHORT)
                    dBind.daciSpinner.setSelection(0)
                    return
                }

                selectedInputType = IpRulesManager.IPRuleType.getType(position)
                dBind.daciIpEditText.hint = activity.resources.getString(
                    R.string.cd_dialog_edittext_hint, types[position])
                dBind.daciTextInputLayout.hint = activity.resources.getString(
                    R.string.cd_dialog_edittext_hint, types[position])

            }
        }

        dBind.daciAddBtn.setOnClickListener {
            val input = dBind.daciIpEditText.text.toString()

            when (selectedInputType) {
                IpRulesManager.IPRuleType.IPV4 -> {
                    val ipv4 = Utilities.removeLeadingAndTrailingDots(input)
                    if (!isValidIpv4(ipv4)) {
                        dBind.daciFailureTextView.text = activity.resources.getString(
                            R.string.ci_dialog_error_invalid_ip)
                        dBind.daciFailureTextView.visibility = View.VISIBLE
                        return@setOnClickListener
                    }

                    insertCustomIpv4(ipv4, false)
                    dialog.dismiss()
                }
                IpRulesManager.IPRuleType.IPV4_WILDCARD -> {
                    if (!isValidIpv4WildCard(input)) {
                        dBind.daciFailureTextView.text = activity.resources.getString(
                            R.string.ci_dialog_error_invalid_ip_wildcard)
                        dBind.daciFailureTextView.visibility = View.VISIBLE
                        return@setOnClickListener
                    }

                    insertCustomIpv4(input, true)
                    dialog.dismiss()
                }
                IpRulesManager.IPRuleType.IPV6 -> {
                    Utilities.showToastUiCentered(activity, activity.resources.getString(
                        R.string.ipv6_coming_soon_toast), Toast.LENGTH_SHORT)
                    dBind.daciSpinner.setSelection(0)
                }
                IpRulesManager.IPRuleType.IPV6_WILDCARD -> {
                    Utilities.showToastUiCentered(activity, activity.resources.getString(
                        R.string.ipv6_coming_soon_toast), Toast.LENGTH_SHORT)
                    dBind.daciSpinner.setSelection(0)
                }
            }
        }

        dBind.daciCancelBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun isValidIpv4(ipv4: String): Boolean {
        // ref: https://mkyong.com/regular-expressions/how-to-validate-ip-address-with-regular-expression/
        val ipv4Regex = "^(([0-9]|[1-9][0-9]|1[0-9][0-9]|2[0-4][0-9]|25[0-5])(\\.(?!$)|$)){4}$".toRegex()
        return ipv4.matches(ipv4Regex)
    }

    private fun isValidIpv4WildCard(ipv4WildCard: String): Boolean {
        // ref: https://stackoverflow.com/questions/11301670/regex-to-validate-ip-address-with-wildcard
        val ipv4WildcardRegEx = "^((((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){3}(25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?))|(((25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\\.){1,3}\\*))\$".toRegex()
        return ipv4WildCard.matches(ipv4WildcardRegEx)
    }

    private fun insertCustomIpv4(ipv4: String, isWildcard: Boolean) {
        IpRulesManager.addFirewallRules(IpRulesManager.UID_EVERYBODY, ipv4, isWildcard)
        Utilities.showToastUiCentered(activity,
                                      activity.getString(R.string.ci_dialog_added_success),
                                      Toast.LENGTH_SHORT)
    }

    private fun showIpRulesDeleteDialog() {
        val builder = AlertDialog.Builder(activity)
        builder.setTitle(R.string.univ_delete_firewall_dialog_title)
        builder.setMessage(R.string.univ_delete_firewall_dialog_message)
        builder.setCancelable(true)
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
