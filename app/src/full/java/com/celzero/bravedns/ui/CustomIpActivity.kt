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
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.isVisible
import androidx.core.widget.addTextChangedListener
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.CustomIpAdapter
import com.celzero.bravedns.databinding.ActivityCustomIpBinding
import com.celzero.bravedns.databinding.DialogAddCustomIpBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Constants.Companion.UID_EVERYBODY
import com.celzero.bravedns.util.CustomLinearLayoutManager
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.CustomIpViewModel
import inet.ipaddr.HostName
import inet.ipaddr.HostNameException
import inet.ipaddr.IPAddress
import inet.ipaddr.IPAddressString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel

class CustomIpActivity :
    AppCompatActivity(R.layout.activity_custom_ip), SearchView.OnQueryTextListener {

    private var layoutManager: RecyclerView.LayoutManager? = null
    private val b by viewBinding(ActivityCustomIpBinding::bind)
    private val viewModel: CustomIpViewModel by viewModel()
    private val persistentState by inject<PersistentState>()
    private var uid = UID_EVERYBODY

    companion object {
        const val INTENT_UID = "UID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(Themes.getCurrentTheme(isDarkThemeOn(), persistentState.theme))
        super.onCreate(savedInstanceState)

        uid = intent.getIntExtra(INTENT_UID, UID_EVERYBODY)

        b.customDialogHeading.text = getString(R.string.ci_header, getAppName())

        b.cipSearchView.setOnQueryTextListener(this)
        observeCustomRules()
        updateHintUi()
        setupRecyclerView()
        setupClickListeners()

        b.cipRecycler.requestFocus()
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

    private fun Context.isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    // now both the universal and app rules are shown in same ui, update the universal and
    // app specific hints. bypass universal/app rules is one such example
    private fun updateHintUi() {
        if (uid == UID_EVERYBODY) {
            b.cipBypassHint.text = getString(R.string.ci_bypass_universal_desc)
            b.cipBypassHintInitial.text = getString(R.string.ci_bypass_universal_initial)
        } else {
            b.cipBypassHint.text = getString(R.string.ci_bypass_app_desc)
            b.cipBypassHintInitial.text = getString(R.string.ci_bypass_app_initial)
        }
    }

    private fun observeCustomRules() {
        viewModel.customIpSize(uid).observe(this) {
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
        layoutManager = CustomLinearLayoutManager(this)
        b.cipRecycler.setHasFixedSize(true)
        b.cipRecycler.layoutManager = layoutManager
        val adapter = CustomIpAdapter(this)

        viewModel.setUid(uid)
        viewModel.customIpDetails.observe(this) { adapter.submitData(this.lifecycle, it) }
        b.cipRecycler.adapter = adapter
    }

    private fun setupClickListeners() {
        b.customDialogAddFab.setOnClickListener { showAddIpDialog() }

        b.cipSearchDeleteIcon.setOnClickListener { showIpRulesDeleteDialog() }
    }

    /**
     * Shows dialog to add custom IP. Provides user option to user to add ips. validates the entered
     * input, if valid then will add it to the custom ip database table.
     */
    private fun showAddIpDialog() {
        val dialog = Dialog(this)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(getString(R.string.ci_dialog_title))
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

        dBind.daciIpTitle.text = getString(R.string.ci_dialog_title)

        dBind.daciIpEditText.addTextChangedListener {
            if (dBind.daciFailureTextView.isVisible) {
                dBind.daciFailureTextView.visibility = View.GONE
            }
        }

        dBind.daciAddBtn.setOnClickListener {
            ui {
                val input = dBind.daciIpEditText.text.toString()
                val ipString = Utilities.removeLeadingAndTrailingDots(input)
                var hostName: HostName? = null
                var ip: IPAddress? = null

                // chances of creating NetworkOnMainThread exception, handling with io operation
                ioCtx {
                    hostName = getHostName(ipString)
                    ip = hostName?.address
                }

                if (ip == null || ipString.isEmpty()) {
                    dBind.daciFailureTextView.text = getString(R.string.ci_dialog_error_invalid_ip)
                    dBind.daciFailureTextView.visibility = View.VISIBLE
                    return@ui
                }

                dBind.daciIpEditText.text.clear()
                insertCustomIp(hostName)
            }
        }

        dBind.daciCancelBtn.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private suspend fun getHostName(ip: String): HostName? {
        return try {
            val host = HostName(ip)
            host.validate()
            host
        } catch (e: HostNameException) {
            val ipAddress = IPAddressString(ip).address ?: return null
            HostName(ipAddress)
        }
    }

    private fun insertCustomIp(ip: HostName?) {
        if (ip == null) return

        IpRulesManager.addIpRule(uid, ip, IpRulesManager.IpRuleStatus.BLOCK)
        Utilities.showToastUiCentered(
            this,
            getString(R.string.ci_dialog_added_success),
            Toast.LENGTH_SHORT
        )
    }

    private fun showIpRulesDeleteDialog() {
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.univ_delete_firewall_dialog_title)
        builder.setMessage(R.string.univ_delete_firewall_dialog_message)
        builder.setPositiveButton(getString(R.string.univ_ip_delete_dialog_positive)) { _, _ ->
            IpRulesManager.deleteIpRulesByUid(uid)
            Utilities.showToastUiCentered(
                this,
                getString(R.string.univ_ip_delete_toast_success),
                Toast.LENGTH_SHORT
            )
        }

        builder.setNegativeButton(getString(R.string.univ_ip_delete_dialog_negative)) { _, _ ->
            // no-op
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    private suspend fun ioCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        lifecycleScope.launch { withContext(Dispatchers.Main) { f() } }
    }
}
