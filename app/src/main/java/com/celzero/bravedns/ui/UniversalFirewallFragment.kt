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
import android.os.Bundle
import android.provider.Settings
import android.util.Log
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
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.AppInfoViewRepository
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.databinding.FragmentFirewallBinding
import com.celzero.bravedns.databinding.UniversalFragementContainerBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.delay
import com.celzero.bravedns.util.Utilities.Companion.getCurrentTheme
import com.celzero.bravedns.viewmodel.AppListViewModel
import com.celzero.bravedns.viewmodel.BlockedConnectionsViewModel
import com.google.android.material.switchmaterial.SwitchMaterial
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.reflect.KMutableProperty0


/**
 * UniversalFirewallFragment - Universal Firewall.
 * TODO: Search feature is removed for firewall header testing
 */

class UniversalFirewallFragment : Fragment(R.layout.universal_fragement_container),
                                  SearchView.OnQueryTextListener {
    private val b by viewBinding(UniversalFragementContainerBinding::bind)

    private lateinit var recyclerAdapter: UniversalAppListAdapter
    private lateinit var recyclerRulesAdapter: UniversalBlockedRulesAdapter
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
            recyclerRulesAdapter::submitList))
        appInfoViewModel.appDetailsList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            recyclerAdapter::submitList))
    }

    private fun initView() {
        val includeView = b.appScrollingInclFirewall

        val isServiceRunning = VpnController.state().on

        if (!isServiceRunning) {
            includeView.firewallScrollConnectCheck.visibility = View.GONE
            return
        }

        includeView.firewallScrollConnectCheck.visibility = View.VISIBLE

        handleIpRulesState(state = false)

        includeView.firewallUniversalRecycler.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(requireContext())
        includeView.firewallUniversalRecycler.layoutManager = layoutManager
        recyclerRulesAdapter = UniversalBlockedRulesAdapter(requireContext(),
                                                            blockedConnectionsRepository)
        recyclerAdapter = UniversalAppListAdapter(requireContext(), appInfoRepository, get(),
                                                  persistentState)
        includeView.firewallUniversalRecycler.adapter = recyclerRulesAdapter

        includeView.firewallAllAppsCheck.isChecked = persistentState.screenState
        includeView.firewallBackgroundModeCheck.isChecked = persistentState.backgroundEnabled
        includeView.firewallUdpConnectionModeCheck.isChecked = persistentState.udpBlockedSettings
        includeView.firewallUnknownConnectionModeCheck.isChecked = persistentState.blockUnknownConnections

        setupClickListeners(includeView)

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

    }

    private fun setupClickListeners(includeView: FragmentFirewallBinding) {
        includeView.firewallAllAppsCheck.setOnCheckedChangeListener { _, b ->
            persistentState.screenState = b
        }

        includeView.firewallAllAppsTxt.setOnClickListener {
            toggle(includeView.firewallAllAppsCheck, persistentState::screenState)
        }

        includeView.firewallUnknownConnectionModeCheck.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.blockUnknownConnections = b
        }

        includeView.firewallUnknownConnectionModeTxt.setOnClickListener {
            toggle(includeView.firewallUnknownConnectionModeCheck,
                   persistentState::blockUnknownConnections)
        }

        includeView.firewallUdpConnectionModeCheck.setOnCheckedChangeListener { _: CompoundButton, b: Boolean ->
            persistentState.udpBlockedSettings = b
        }

        includeView.firewallUdpConnectionModeTxt.setOnClickListener {
            toggle(includeView.firewallUdpConnectionModeCheck, persistentState::udpBlockedSettings)
        }

        //Background mode toggle
        includeView.firewallBackgroundModeTxt.setOnClickListener {
            recheckFirewallBackgroundMode(!includeView.firewallBackgroundModeCheck.isChecked)
        }

        includeView.firewallBackgroundModeCheck.setOnCheckedChangeListener(null)
        includeView.firewallBackgroundModeCheck.setOnClickListener {
            // In this case, the isChecked value of the swtich would have already flipped.
            recheckFirewallBackgroundMode(includeView.firewallBackgroundModeCheck.isChecked)
        }

        includeView.firewallAppsShowTxt.setOnClickListener {
            includeView.firewallAppsShowTxt.isEnabled = false
            val themeID = getCurrentTheme(isDarkThemeOn(), persistentState.theme)
            val customDialog = WhitelistAppDialog(requireContext(), get(), get(), get(),
                                                  recyclerAdapter, appInfoViewModel, themeID)
            customDialog.setCanceledOnTouchOutside(false)
            //if we know that the particular variable not null any time ,we can assign !!
            // (not null operator ), then  it won't check for null, if it becomes null,
            // it will throw exception
            customDialog.show()

            delay(500) { if (isAdded) includeView.firewallAppsShowTxt.isEnabled = true }
        }

        includeView.firewallUnivIpHeader.setOnClickListener {
            handleIpRulesState(ipListState)
        }

        includeView.firewallUnivIpImg.setOnClickListener {
            handleIpRulesState(ipListState)
        }

        includeView.firewallSearchDeleteIcon.setOnClickListener {
            showIpRulesDeleteDialog()
        }

        includeView.firewallSearchView.setOnQueryTextListener(this)
        includeView.firewallSearchView.setOnClickListener {
            includeView.firewallSearchView.requestFocus()
            includeView.firewallSearchView.onActionViewExpanded()
        }
    }

    private fun handleIpRulesState(state: Boolean) {
        if (state) {
            b.appScrollingInclFirewall.firewallSearchViewTop.visibility = View.VISIBLE
            b.appScrollingInclFirewall.firewallUniversalRecycler.visibility = View.VISIBLE
            b.appScrollingInclFirewall.firewallSearchContainer.visibility = View.VISIBLE
            b.appScrollingInclFirewall.firewallNoRulesSetTxt.visibility = View.VISIBLE
            b.appScrollingInclFirewall.firewallUnivIpImg.setImageResource(
                R.drawable.ic_keyboard_arrow_up_gray_24dp)
        } else {
            b.appScrollingInclFirewall.firewallSearchViewTop.visibility = View.GONE
            b.appScrollingInclFirewall.firewallUniversalRecycler.visibility = View.GONE
            b.appScrollingInclFirewall.firewallNoRulesSetTxt.visibility = View.GONE
            b.appScrollingInclFirewall.firewallSearchContainer.visibility = View.GONE
            b.appScrollingInclFirewall.firewallUnivIpImg.setImageResource(
                R.drawable.ic_keyboard_arrow_down_gray_24dp)
        }
        ipListState = !state
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    private fun recheckFirewallBackgroundMode(isChecked: Boolean) {
        val includeView = b.appScrollingInclFirewall
        if (!isChecked) {
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

        showPermissionAlert()
        includeView.firewallBackgroundModeCheck.isChecked = false
        persistentState.backgroundEnabled = false
    }

    private fun showIpRulesDeleteDialog() {
        if (blockedConnectionsRepository.getBlockedConnectionsCount() <= 0) {
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
            FirewallRules.clearAllIpRules(blockedConnectionsRepository)
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.univ_ip_delete_toast_success),
                                          Toast.LENGTH_SHORT)
        }

        builder.setNegativeButton(getString(R.string.univ_ip_delete_dialog_negative)) { _, _ ->
        }

        builder.setCancelable(true)
        builder.create().show()
    }

    override fun onResume() {
        super.onResume()
        b.appScrollingInclFirewall.firewallUnknownConnectionModeCheck.isChecked = persistentState.blockUnknownConnections
        checkAppNotInUse()
    }

    private fun checkAppNotInUse() {
        if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                         "App not in use check - isCrashDetected? - ${persistentState.isAccessibilityCrashDetected}" + ", background enabled- ${persistentState.backgroundEnabled}")

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
    }

    private fun showPermissionAlert() {
        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(R.string.alert_permission_accessibility)
        builder.setMessage(R.string.alert_firewall_accessibility_explanation)
        builder.setPositiveButton(getString(R.string.univ_accessibility_dialog_positive)) { _, _ ->
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivityForResult(intent, 0)
        }
        builder.setNegativeButton(getString(R.string.univ_accessibility_dialog_negative)) { _, _ ->
        }
        builder.setCancelable(false)
        builder.create().show()
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        viewModel.setFilter(query)
        return true
    }

    override fun onQueryTextChange(query: String): Boolean {
        viewModel.setFilter(query)
        return true
    }

    private fun toggle(v: SwitchMaterial, pref: KMutableProperty0<Boolean>) {
        pref.set(!pref.get())
        v.isChecked = pref.get()
    }

}
