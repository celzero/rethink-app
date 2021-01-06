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
import android.os.Bundle
import android.os.Handler
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.CompoundButton
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.UniversalAppListAdapter
import com.celzero.bravedns.adapter.UniversalBlockedRulesAdapter
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.databinding.UniversalFragementContainerBinding
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.AppListViewModel
import com.celzero.bravedns.viewmodel.BlockedConnectionsViewModel
import org.koin.android.ext.android.get
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel


/**
 * UniversalFirewallFragment - Universal Firewall.
 * TODO: Search feature is removed for firewall header testing
 */

class UniversalFirewallFragment : Fragment(R.layout.universal_fragement_container), SearchView.OnQueryTextListener {
    private val b by viewBinding(UniversalFragementContainerBinding::bind)

    private var recyclerAdapter: UniversalAppListAdapter? = null
    private var recyclerRulesAdapter: UniversalBlockedRulesAdapter? = null
    private var layoutManager: RecyclerView.LayoutManager? = null
    private val viewModel: BlockedConnectionsViewModel by viewModel()
    private val appInfoViewModel: AppListViewModel by viewModel()

    private var universalState: Boolean = false
    private var ipListState: Boolean = false

    private val appInfoRepository by inject<AppInfoRepository>()
    private val blockedConnectionsRepository by inject<BlockedConnectionsRepository>()
    private val persistentState by inject<PersistentState>()

    companion object {
        fun newInstance() = UniversalFirewallFragment()
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
        viewModel.blockedUnivRulesList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(recyclerRulesAdapter!!::submitList))
        appInfoViewModel.appDetailsList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(recyclerAdapter!!::submitList))
    }

    private fun initView() {
        val includeView = b.appScrollingInclFirewall

        val isServiceRunning = Utilities.isServiceRunning(requireContext(), BraveVPNService::class.java)

        if (!isServiceRunning) {
            includeView.firewallScrollConnectCheck.visibility = View.GONE
            return
        } else {
            includeView.firewallScrollConnectCheck.visibility = View.VISIBLE
        }

        setIPRulesVisible()

        includeView.firewallUniversalRecycler.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(requireContext())
        includeView.firewallUniversalRecycler.layoutManager = layoutManager
        recyclerRulesAdapter = UniversalBlockedRulesAdapter(requireContext(), blockedConnectionsRepository)
        recyclerAdapter = UniversalAppListAdapter(requireContext(), appInfoRepository, get(), persistentState)
        includeView.firewallUniversalRecycler.adapter = recyclerRulesAdapter
        includeView.firewallSearchView.requestFocus()

        //recyclerView.isNestedScrollingEnabled = false
        //ViewCompat.setNestedScrollingEnabled(recyclerView, false)

        if (DEBUG) Log.d(LOG_TAG, "UniversalFirewallFragment - post observer")

        includeView.firewallAllAppsCheck.isChecked = persistentState.getFirewallModeForScreenState()

        includeView.firewallUdpConnectionModeCheck.isChecked = persistentState.udpBlockedSettings
        includeView.firewallUnknownConnectionModeCheck.isChecked = persistentState.blockUnknownConnections

        includeView.firewallAllAppsCheck.setOnCheckedChangeListener { _, b ->
            persistentState.setFirewallModeForScreenState(b)
        }

        includeView.firewallAllAppsTxt.setOnClickListener {
            if (persistentState.getFirewallModeForScreenState()) {
                includeView.firewallAllAppsCheck.isChecked = false
                persistentState.setFirewallModeForScreenState(false)
            } else {
                includeView.firewallAllAppsCheck.isChecked = true
                persistentState.setFirewallModeForScreenState(true)
            }
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

        /*ipRulesAddBtn.setOnClickListener{
            Utilities.showToastInMidLayout(requireContext(),"Yet to implement",Toast.LENGTH_SHORT)
        }*/

        //Background mode toggle
        includeView.firewallBackgroundModeTxt.setOnClickListener {
            val checkedVal = includeView.firewallBackgroundModeCheck.isChecked
            if (!checkedVal) {
                if (Utilities.isAccessibilityServiceEnabledEnhanced(requireContext(), BackgroundAccessibilityService::class.java)) {
                    if (!Utilities.isAccessibilityServiceEnabled(requireContext(), BackgroundAccessibilityService::class.java)) {
                        if (!showAlertForPermission(true)) {
                            includeView.firewallBackgroundModeCheck.isChecked = false
                            persistentState.setIsBackgroundEnabled(false)
                        }
                    }
                    GlobalVariable.isBackgroundEnabled = !checkedVal
                    persistentState.setIsBackgroundEnabled(!checkedVal)
                    includeView.firewallBackgroundModeCheck.isChecked = !checkedVal
                } else {
                    if (!showAlertForPermission(false)) {
                        includeView.firewallBackgroundModeCheck.isChecked = false
                        persistentState.setIsBackgroundEnabled(false)
                    }
                }
            } else {
                includeView.firewallBackgroundModeCheck.isChecked = false
                persistentState.setIsBackgroundEnabled(false)
            }
        }

        includeView.firewallBackgroundModeCheck.setOnCheckedChangeListener(null)

        includeView.firewallBackgroundModeCheck.setOnClickListener {
            val checkedVal = !includeView.firewallBackgroundModeCheck.isChecked
            if (!checkedVal) {
                if (Utilities.isAccessibilityServiceEnabledEnhanced(requireContext(), BackgroundAccessibilityService::class.java)) {
                    if (!Utilities.isAccessibilityServiceEnabled(requireContext(), BackgroundAccessibilityService::class.java)) {
                        if (!showAlertForPermission(true)) {
                            includeView.firewallBackgroundModeCheck.isChecked = false
                            persistentState.setIsBackgroundEnabled(false)
                        }
                    }
                    GlobalVariable.isBackgroundEnabled = !checkedVal
                    persistentState.setIsBackgroundEnabled(!checkedVal)
                    includeView.firewallBackgroundModeCheck.isChecked = !checkedVal
                } else {
                    if (!showAlertForPermission(false)) {
                        includeView.firewallBackgroundModeCheck.isChecked = false
                        persistentState.setIsBackgroundEnabled(false)
                    }
                }
            } else {
                includeView.firewallBackgroundModeCheck.isChecked = false
                persistentState.setIsBackgroundEnabled(false)
            }
        }

        val appCount = GlobalVariable.appList.size
        val act: FirewallActivity = requireContext() as FirewallActivity
        appInfoRepository.getWhitelistCountLiveData().observe(act, {
            includeView.firewallUnivWhitelistCount.text = "$it/$appCount apps whitelisted."
        })

        includeView.firewallAppsShowTxt.setOnClickListener {
            includeView.firewallAppsShowTxt.isEnabled = false
            val customDialog = WhitelistAppDialog(requireContext(), appInfoRepository, get(), recyclerAdapter!!, appInfoViewModel)
            //if we know that the particular variable not null any time ,we can assign !!
            // (not null operator ), then  it won't check for null, if it becomes null,
            // it will throw exception
            customDialog.show()
            customDialog.setCanceledOnTouchOutside(false)
            Handler().postDelayed({ includeView.firewallAppsShowTxt.isEnabled = true }, 100)
        }

        includeView.firewallRulesShowTxt.setOnClickListener {
            if (ipListState) {
                ipListState = false
                includeView.firewallSearchViewTop.visibility = View.VISIBLE
                includeView.firewallUniversalRecycler.visibility = View.VISIBLE
                includeView.firewallUnivWhitelistRulesExpTxt.visibility = View.VISIBLE
                includeView.firewallSearchContainer.visibility = View.VISIBLE
                includeView.firewallNoRulesSetTxt.visibility = View.VISIBLE
                includeView.firewallRulesShowTxt.setCompoundDrawablesWithIntrinsicBounds(null, null, ContextCompat.getDrawable(requireContext(), R.drawable.ic_keyboard_arrow_down_gray_24dp), null)
            } else {
                ipListState = true
                includeView.firewallSearchViewTop.visibility = View.GONE
                includeView.firewallUniversalRecycler.visibility = View.GONE
                includeView.firewallUnivWhitelistRulesExpTxt.visibility = View.VISIBLE
                includeView.firewallNoRulesSetTxt.visibility = View.GONE
                includeView.firewallSearchContainer.visibility = View.GONE
                includeView.firewallRulesShowTxt.setCompoundDrawablesWithIntrinsicBounds(null, null, ContextCompat.getDrawable(requireContext(), R.drawable.ic_keyboard_arrow_up_gray_24dp), null)
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

    private fun setIPRulesVisible() {
        ipListState = false
        b.appScrollingInclFirewall.firewallSearchViewTop.visibility = View.VISIBLE
        b.appScrollingInclFirewall.firewallUniversalRecycler.visibility = View.VISIBLE
        b.appScrollingInclFirewall.firewallUnivWhitelistRulesExpTxt.visibility = View.VISIBLE
        b.appScrollingInclFirewall.firewallSearchContainer.visibility = View.VISIBLE
        b.appScrollingInclFirewall.firewallNoRulesSetTxt.visibility = View.VISIBLE
        b.appScrollingInclFirewall.firewallRulesShowTxt.setCompoundDrawablesWithIntrinsicBounds(null, null, ContextCompat.getDrawable(requireContext(), R.drawable.ic_keyboard_arrow_down_gray_24dp), null)
    }

    private fun showDialogForDelete() {
        val count = blockedConnectionsRepository.getBlockedConnectionsCount()
        if (count > 0) {
            val builder = AlertDialog.Builder(requireContext())
            //set title for alert dialog
            builder.setTitle(R.string.univ_delete_firewall_dialog_title)
            //set message for alert dialog
            builder.setMessage(R.string.univ_delete_firewall_dialog_message)
            builder.setIcon(android.R.drawable.ic_dialog_alert)
            builder.setCancelable(true)
            //performing positive action
            builder.setPositiveButton("Delete all") { _, _ ->

                blockedConnectionsRepository.deleteAllIPRulesUniversal()
                GlobalVariable.firewallRules.clear()
                Utilities.showToastInMidLayout(requireContext(), "Deleted all IP rules.", Toast.LENGTH_SHORT)
            }

            //performing negative action
            builder.setNegativeButton("Cancel") { _, _ ->
            }
            // Create the AlertDialog
            val alertDialog: AlertDialog = builder.create()
            // Set other dialog properties
            alertDialog.setCancelable(true)
            alertDialog.show()
        } else {
            Utilities.showToastInMidLayout(requireContext(), "No IP rules set", Toast.LENGTH_SHORT)
        }
    }

    override fun onResume() {
        super.onResume()
        b.appScrollingInclFirewall.firewallUnknownConnectionModeCheck.isChecked = persistentState.blockUnknownConnections
        if (Utilities.isAccessibilityServiceEnabledEnhanced(requireContext(), BackgroundAccessibilityService::class.java)) {
            if (DEBUG) Log.d(LOG_TAG, "Background - onLoad accessibility is true")
            b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked = persistentState.backgroundEnabled
        } else {
            if (DEBUG) Log.d(LOG_TAG, "Background - onLoad accessibility is true, changed pref")
            persistentState.setIsBackgroundEnabled(false)
            b.appScrollingInclFirewall.firewallBackgroundModeCheck.isChecked = false
        }
    }

    private fun showAlertForPermission(isRegrant: Boolean): Boolean {
        var isAllowed = false
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        if (isRegrant) {
            builder.setTitle(R.string.alert_permission_accessibility_regrant)
        } else {
            builder.setTitle(R.string.alert_permission_accessibility)
        }
        //set message for alert dialog
        if (isRegrant) {
            builder.setMessage(R.string.alert_firewall_accessibility_regrant_explanation)
        } else {
            builder.setMessage(R.string.alert_firewall_accessibility_explanation)
        }

        builder.setIcon(android.R.drawable.ic_dialog_alert)
        //performing positive action
        builder.setPositiveButton("Grant") { _, _ ->
            isAllowed = true
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivityForResult(intent, 0)
        }
        //performing negative action
        builder.setNegativeButton("Deny") { _, _ ->
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(false)
        alertDialog.show()
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
