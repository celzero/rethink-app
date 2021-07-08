/*
Copyright 2020 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/
package com.celzero.bravedns.ui

import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.Settings
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.UniversalAppListAdapter
import com.celzero.bravedns.adapter.UniversalBlockedRulesAdapter
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.AppInfoViewRepository
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.databinding.UniversalFragementContainerBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getCurrentTheme
import com.celzero.bravedns.viewmodel.AppListViewModel
import com.celzero.bravedns.viewmodel.BlockedConnectionsViewModel
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel


/**
 * UniversalFirewallFragment - Universal Firewall.
 * TODO: Search feature is removed for firewall header testing
 */

class UniversalFirewallFragment : Fragment(R.layout.universal_fragement_container),
                                  SearchView.OnQueryTextListener {
    private val b by viewBinding(UniversalFragementContainerBinding::bind)

    private var recyclerAdapter: UniversalAppListAdapter? = null
    private var recyclerRulesAdapter: UniversalBlockedRulesAdapter? = null
    private var layoutManager: RecyclerView.LayoutManager? = null
    private val viewModel: BlockedConnectionsViewModel by viewModel()
    private val appInfoViewModel: AppListViewModel by viewModel()

    private var ipListState: Boolean = false

    private val appInfoRepository by inject<AppInfoRepository>()
    private val appInfoViewRepository by inject<AppInfoViewRepository>()
    private val blockedConnectionsRepository by inject<BlockedConnectionsRepository>()
    private val persistentState by inject<PersistentState>()

    companion object {
        fun newInstance() = UniversalFirewallFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        viewModel.blockedUnivRulesList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            recyclerRulesAdapter!!::submitList))
        appInfoViewModel.appDetailsList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            recyclerAdapter!!::submitList))
    }

    private fun initView() {
        val includeView = b.appScrollingInclFirewall

        val isServiceRunning = persistentState.vpnEnabled

        if (!isServiceRunning) {
            includeView.firewallScrollConnectCheck.visibility = View.GONE
            return
        } else {
            includeView.firewallScrollConnectCheck.visibility = View.VISIBLE
        }

        setIPRulesInvisible()

        includeView.firewallUniversalRecycler.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(requireContext())
        includeView.firewallUniversalRecycler.layoutManager = layoutManager
        recyclerRulesAdapter = UniversalBlockedRulesAdapter(requireContext(),
                                                            blockedConnectionsRepository)
        recyclerAdapter = UniversalAppListAdapter(requireContext(), appInfoRepository, get(),
                                                  persistentState)
        includeView.firewallUniversalRecycler.adapter = recyclerRulesAdapter

        includeView.firewallAllAppsCheck.isChecked = persistentState.screenState

        includeView.firewallUdpConnectionModeCheck.isChecked = persistentState.udpBlockedSettings
        includeView.firewallUnknownConnectionModeCheck.isChecked = persistentState.blockUnknownConnections

        includeView.firewallAllAppsCheck.setOnCheckedChangeListener { _, b ->
            persistentState.screenState = b
        }

        includeView.firewallAllAppsTxt.setOnClickListener {
            includeView.firewallAllAppsCheck.isChecked = !persistentState.screenState
            persistentState.screenState = !persistentState.screenState
        }

        includeView.firewallUnknownConnectionModeCheck.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.blockUnknownConnections = b
        }

        includeView.firewallUnknownConnectionModeTxt.setOnClickListener {
            persistentState.blockUnknownConnections = !includeView.firewallUnknownConnectionModeCheck.isChecked
            includeView.firewallUnknownConnectionModeCheck.isChecked = !includeView.firewallUnknownConnectionModeCheck.isChecked
        }

        includeView.firewallUdpConnectionModeCheck.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.udpBlockedSettings = b
        }

        includeView.firewallUdpConnectionModeTxt.setOnClickListener {
            persistentState.udpBlockedSettings = !includeView.firewallUdpConnectionModeCheck.isChecked
            includeView.firewallUdpConnectionModeCheck.isChecked = !includeView.firewallUdpConnectionModeCheck.isChecked
        }

        //Background mode toggle
        includeView.firewallBackgroundModeTxt.setOnClickListener {
            val checkedVal = includeView.firewallBackgroundModeCheck.isChecked
            isAccessibilityAvailable(checkedVal)
        }

        includeView.firewallBackgroundModeCheck.setOnCheckedChangeListener(null)

        includeView.firewallBackgroundModeCheck.setOnClickListener {
            val checkedVal = !includeView.firewallBackgroundModeCheck.isChecked
            isAccessibilityAvailable(checkedVal)
        }

        val appCount = GlobalVariable.appList.size
        appInfoViewRepository.getWhitelistCountLiveData().observe(viewLifecycleOwner, {
            includeView.firewallUnivWhitelistCount.text = getString(
                R.string.whitelist_dialog_apps_in_use, it.toString(), appCount.toString())
        })

        blockedConnectionsRepository.getBlockedConnectionCountLiveData().observe(viewLifecycleOwner,
                                                                                 {
                                                                                     includeView.firewallUnivIpCount.text = getString(
                                                                                         R.string.univ_blocked_ip_count,
                                                                                         it.toString())
                                                                                 })

        includeView.firewallAppsShowTxt.setOnClickListener {
            includeView.firewallAppsShowTxt.isEnabled = false
            val themeID = getCurrentTheme(isDarkThemeOn())
            val customDialog = WhitelistAppDialog(requireContext(), get(), get(), get(),
                                                  recyclerAdapter!!, appInfoViewModel, themeID)
            //if we know that the particular variable not null any time ,we can assign !!
            // (not null operator ), then  it won't check for null, if it becomes null,
            // it will throw exception
            customDialog.show()
            customDialog.setCanceledOnTouchOutside(false)
            object : CountDownTimer(500, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    includeView.firewallAppsShowTxt.isEnabled = true
                }
            }.start()
        }

        includeView.firewallUnivIpHeader.setOnClickListener {
            if (ipListState) {
                setIPRulesVisible()
            } else {
                setIPRulesInvisible()
            }
        }

        includeView.firewallUnivIpImg.setOnClickListener {
            if (ipListState) {
                setIPRulesVisible()
            } else {
                setIPRulesInvisible()
            }
        }

        includeView.firewallSearchDeleteIcon.setOnClickListener {
            showDialogForDelete()
        }

        includeView.firewallSearchView.setOnQueryTextListener(this)
        includeView.firewallSearchView.setOnClickListener {
            includeView.firewallSearchView.requestFocus()
            includeView.firewallSearchView.onActionViewExpanded()
        }

    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun isAccessibilityAvailable(isChecked: Boolean) {
        val includeView = b.appScrollingInclFirewall
        if (isChecked) {
            includeView.firewallBackgroundModeCheck.isChecked = false
            persistentState.backgroundEnabled = false
            return
        }

        val isAccessibilityServiceRunning = Utilities.isAccessibilityServiceEnabled(
            requireContext(), BackgroundAccessibilityService::class.java)
        val isAccessibilityServiceEnabled = Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
            requireContext(), BackgroundAccessibilityService::class.java)
        val isAccessibilityServiceFunctional = isAccessibilityServiceRunning && isAccessibilityServiceEnabled

        if (isAccessibilityServiceFunctional) {
            persistentState.backgroundEnabled = true
            includeView.firewallBackgroundModeCheck.isChecked = true
            return
        }

        showAlertForPermission(isAccessibilityServiceEnabled)
        includeView.firewallBackgroundModeCheck.isChecked = false
        persistentState.backgroundEnabled = false
    }

    private fun setIPRulesVisible() {
        ipListState = false
        b.appScrollingInclFirewall.firewallSearchViewTop.visibility = View.VISIBLE
        b.appScrollingInclFirewall.firewallUniversalRecycler.visibility = View.VISIBLE
        b.appScrollingInclFirewall.firewallUnivWhitelistRulesExpTxt.visibility = View.VISIBLE
        b.appScrollingInclFirewall.firewallSearchContainer.visibility = View.VISIBLE
        b.appScrollingInclFirewall.firewallNoRulesSetTxt.visibility = View.VISIBLE
        b.appScrollingInclFirewall.firewallUnivIpImg.setImageResource(
            R.drawable.ic_keyboard_arrow_up_gray_24dp)
    }

    private fun setIPRulesInvisible() {
        ipListState = true
        b.appScrollingInclFirewall.firewallSearchViewTop.visibility = View.GONE
        b.appScrollingInclFirewall.firewallUniversalRecycler.visibility = View.GONE
        b.appScrollingInclFirewall.firewallUnivWhitelistRulesExpTxt.visibility = View.VISIBLE
        b.appScrollingInclFirewall.firewallNoRulesSetTxt.visibility = View.GONE
        b.appScrollingInclFirewall.firewallSearchContainer.visibility = View.GONE
        b.appScrollingInclFirewall.firewallUnivIpImg.setImageResource(
            R.drawable.ic_keyboard_arrow_down_gray_24dp)
    }

    private fun showDialogForDelete() {
        val count = blockedConnectionsRepository.getBlockedConnectionsCount()
        if (count <= 0) {
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.univ_ip_no_rules_set),
                                          Toast.LENGTH_SHORT)
            return
        }

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.univ_delete_firewall_dialog_title)
        builder.setMessage(R.string.univ_delete_firewall_dialog_message)
        builder.setCancelable(true)
        builder.setPositiveButton(getString(R.string.univ_ip_delete_dialog_positive)) { _, _ ->
            blockedConnectionsRepository.deleteAllIPRulesUniversal()
            GlobalVariable.firewallRules.clear()
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.univ_ip_delete_toast_success),
                                          Toast.LENGTH_SHORT)
        }

        builder.setNegativeButton(getString(R.string.univ_ip_delete_dialog_negative)) { _, _ ->
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    override fun onResume() {
        super.onResume()
        b.appScrollingInclFirewall.firewallUnknownConnectionModeCheck.isChecked = persistentState.blockUnknownConnections
        checkAppNotInUse()
    }

    private fun checkAppNotInUse() {

        if (!persistentState.isAccessibilityCrashDetected) {
            return
        }

        val isAccessibilityServiceRunning = Utilities.isAccessibilityServiceEnabled(
            requireContext(), BackgroundAccessibilityService::class.java)
        val isAccessibilityServiceEnabled = Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
            requireContext(), BackgroundAccessibilityService::class.java)

        if (!isAccessibilityServiceEnabled) {
            persistentState.backgroundEnabled = false
            persistentState.isAccessibilityCrashDetected = false
            b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked = false
            return
        }

        if (isAccessibilityServiceRunning) {
            b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked = persistentState.backgroundEnabled
            return
        }

        if (persistentState.isAccessibilityCrashDetected) {
            persistentState.backgroundEnabled = false
            b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked = false
            showAlertForPermission(true)
        }
    }

    private fun showAlertForPermission(isRegrant: Boolean): Boolean {
        var isAllowed = false
        val builder = AlertDialog.Builder(requireContext())
        if (isRegrant) {
            builder.setTitle(R.string.alert_permission_accessibility_regrant)
            builder.setMessage(R.string.alert_firewall_accessibility_regrant_explanation)
            builder.setPositiveButton(
                getString(R.string.univ_accessibility_crash_dialog_positive)) { _, _ ->
                persistentState.isAccessibilityCrashDetected = false
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                val packageName = requireContext().packageName
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
            builder.setNegativeButton(
                getString(R.string.univ_accessibility_crash_dialog_negative)) { _, _ ->
                persistentState.backgroundEnabled = false
                persistentState.isAccessibilityCrashDetected = false
            }
        } else {
            builder.setTitle(R.string.alert_permission_accessibility)
            builder.setMessage(R.string.alert_firewall_accessibility_explanation)
            builder.setPositiveButton(
                getString(R.string.univ_accessibility_dialog_positive)) { _, _ ->
                isAllowed = true
                val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                startActivityForResult(intent, 0)
            }
            builder.setNegativeButton(
                getString(R.string.univ_accessibility_dialog_negative)) { _, _ ->
                persistentState.backgroundEnabled = false
            }
        }

        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(false)
        alertDialog.show()
        alertDialog.setCancelable(false)
        return isAllowed
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        viewModel.setFilter(query!!)
        return true
    }

    override fun onQueryTextChange(query: String?): Boolean {
        viewModel.setFilter(query!!)
        return true
    }

}
