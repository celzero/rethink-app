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
import com.celzero.bravedns.adapter.UniversalBlockedRulesAdapter
import com.celzero.bravedns.adapter.WhitelistedAppsAdapter
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.databinding.FragmentFirewallBinding
import com.celzero.bravedns.databinding.UniversalFragementContainerBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Constants.Companion.INIT_TIME_MS
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_FIREWALL
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.delay
import com.celzero.bravedns.util.Utilities.Companion.getCurrentTheme
import com.celzero.bravedns.viewmodel.AppListViewModel
import com.celzero.bravedns.viewmodel.BlockedConnectionsViewModel
import com.google.android.material.switchmaterial.SwitchMaterial
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import kotlin.reflect.KMutableProperty0


class UniversalFirewallFragment : Fragment(R.layout.universal_fragement_container),
                                  SearchView.OnQueryTextListener {
    private val b by viewBinding(UniversalFragementContainerBinding::bind)

    private lateinit var recyclerAdapter: WhitelistedAppsAdapter
    private lateinit var recyclerRulesAdapter: UniversalBlockedRulesAdapter
    private var layoutManager: RecyclerView.LayoutManager? = null
    private val viewModel: BlockedConnectionsViewModel by viewModel()
    private val appInfoViewModel: AppListViewModel by viewModel()

    private var ipListState: Boolean = false

    private val blockedConnectionsRepository by inject<BlockedConnectionsRepository>()
    private val persistentState by inject<PersistentState>()

    private var blockedRulesCount: Int = 0

    companion object {
        fun newInstance() = UniversalFirewallFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
        val includeView = b.appScrollingInclFirewall

        val isServiceRunning = VpnController.state().on

        if (!isServiceRunning) {
            includeView.firewallScrollConnectCheck.visibility = View.GONE
            return
        }

        includeView.firewallScrollConnectCheck.visibility = View.VISIBLE

        toggleIpRulesState(state = false)

        includeView.firewallUniversalRecycler.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(requireContext())
        includeView.firewallUniversalRecycler.layoutManager = layoutManager
        recyclerRulesAdapter = UniversalBlockedRulesAdapter(requireContext(),
                                                            blockedConnectionsRepository)
        recyclerAdapter = WhitelistedAppsAdapter(requireContext())
        includeView.firewallUniversalRecycler.adapter = recyclerRulesAdapter

        includeView.firewallAllAppsCheck.isChecked = persistentState.blockWhenDeviceLocked
        includeView.firewallBackgroundModeCheck.isChecked = persistentState.blockAppWhenBackground
        includeView.firewallUdpConnectionModeCheck.isChecked = persistentState.udpBlockedSettings
        includeView.firewallUnknownConnectionModeCheck.isChecked = persistentState.blockUnknownConnections
        includeView.firewallDisallowDnsBypassModeCheck.isChecked = persistentState.disallowDnsBypass

        setupClickListeners(includeView)

        FirewallManager.getApplistObserver().observe(viewLifecycleOwner, {
            val whiteListApps = it.filter { a -> a.whiteListUniv1 }.size
            includeView.firewallUnivWhitelistCount.text = getString(
                R.string.whitelist_dialog_apps_in_use, whiteListApps.toString())
        })

        blockedConnectionsRepository.getBlockedConnectionCountLiveData().observe(viewLifecycleOwner,
                                                                                 {
                                                                                     blockedRulesCount = it
                                                                                     includeView.firewallUnivIpCount.text = getString(
                                                                                         R.string.univ_blocked_ip_count,
                                                                                         it.toString())
                                                                                 })

        viewModel.blockedUnivRulesList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            recyclerRulesAdapter::submitList))
        appInfoViewModel.appDetailsList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(
            recyclerAdapter::submitList))

    }

    private fun setupClickListeners(includeView: FragmentFirewallBinding) {
        includeView.firewallAllAppsCheck.setOnCheckedChangeListener { _, b ->
            persistentState.blockWhenDeviceLocked = b
        }

        includeView.firewallAllAppsTxt.setOnClickListener {
            toggle(includeView.firewallAllAppsCheck, persistentState::blockWhenDeviceLocked)
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
            // In this case, the isChecked property of the switch would have already flipped.
            recheckFirewallBackgroundMode(includeView.firewallBackgroundModeCheck.isChecked)
        }

        includeView.firewallAppsShowTxt.setOnClickListener {
            includeView.firewallAppsShowTxt.isEnabled = false
            val themeID = getCurrentTheme(isDarkThemeOn(), persistentState.theme)

            val customDialog = WhitelistAppDialog(requireActivity() as FirewallActivity,
                                                  recyclerAdapter, appInfoViewModel, themeID)
            customDialog.setCanceledOnTouchOutside(false)
            customDialog.show()

            delay(500) { if (isAdded) includeView.firewallAppsShowTxt.isEnabled = true }
        }

        includeView.firewallDisallowDnsBypassModeCheck.setOnCheckedChangeListener { _, b ->
            persistentState.disallowDnsBypass = b
        }

        includeView.firewallDisallowDnsBypassModeTxt.setOnClickListener {
            toggle(includeView.firewallDisallowDnsBypassModeCheck,
                   persistentState::disallowDnsBypass)
        }

        includeView.firewallUnivIpHeader.setOnClickListener {
            toggleIpRulesState(ipListState)
        }

        includeView.firewallUnivIpImg.setOnClickListener {
            toggleIpRulesState(ipListState)
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

    private fun toggleIpRulesState(state: Boolean) {
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
            persistentState.blockAppWhenBackground = false
            return
        }

        val isAccessibilityServiceRunning = Utilities.isAccessibilityServiceEnabled(
            requireContext(), BackgroundAccessibilityService::class.java)
        val isAccessibilityServiceEnabled = Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
            requireContext(), BackgroundAccessibilityService::class.java)
        val isAccessibilityServiceFunctional = isAccessibilityServiceRunning && isAccessibilityServiceEnabled

        if (isAccessibilityServiceFunctional) {
            persistentState.blockAppWhenBackground = true
            includeView.firewallBackgroundModeCheck.isChecked = true
            // Reset the heart beat time for the accessibility check.
            // On accessibility failure the value will be stored for next 5 mins.
            // If user, re-enable the settings reset the timestamp so that vpn service
            // will check for the accessibility service availability.
            VpnController.getBraveVpnService()?.accessibilityHearbeatTimestamp = INIT_TIME_MS
            return
        }

        showPermissionAlert()
        includeView.firewallBackgroundModeCheck.isChecked = false
        persistentState.blockAppWhenBackground = false
    }

    private fun showIpRulesDeleteDialog() {
        if (blockedRulesCount <= 0) {
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
        updateUniversalFirewallPreferences()
    }

    private fun updateUniversalFirewallPreferences() {
        b.appScrollingInclFirewall.firewallAllAppsCheck.isChecked = persistentState.blockWhenDeviceLocked
        b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked = persistentState.blockAppWhenBackground
        b.appScrollingInclFirewall.firewallUdpConnectionModeCheck.isChecked = persistentState.udpBlockedSettings
        b.appScrollingInclFirewall.firewallUnknownConnectionModeCheck.isChecked = persistentState.blockUnknownConnections
        checkAppNotInUseRule()
    }


    private fun checkAppNotInUseRule() {
        if (!persistentState.blockAppWhenBackground) return

        val isAccessibilityServiceRunning = Utilities.isAccessibilityServiceEnabled(
            requireContext(), BackgroundAccessibilityService::class.java)
        val isAccessibilityServiceEnabled = Utilities.isAccessibilityServiceEnabledViaSettingsSecure(
            requireContext(), BackgroundAccessibilityService::class.java)

        if (DEBUG) Log.d(LOG_TAG_FIREWALL,
                         "backgroundEnabled? ${persistentState.blockAppWhenBackground}, isServiceEnabled? $isAccessibilityServiceEnabled, isServiceRunning? $isAccessibilityServiceRunning")
        val isAccessibilityServiceFunctional = isAccessibilityServiceRunning && isAccessibilityServiceEnabled

        if (!isAccessibilityServiceFunctional) {
            persistentState.blockAppWhenBackground = false
            b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked = false
            Utilities.showToastUiCentered(requireContext(),
                                          getString(R.string.accessibility_failure_toast),
                                          Toast.LENGTH_SHORT)
            return
        }

        if (isAccessibilityServiceRunning) {
            b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked = persistentState.blockAppWhenBackground
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
