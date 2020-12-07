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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.SwitchCompat
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.UniversalAppListAdapter
import com.celzero.bravedns.adapter.UniversalBlockedRulesAdapter
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.BackgroundAccessibilityService
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.AppListViewModel
import com.celzero.bravedns.viewmodel.BlockedConnectionsViewModel


/**
 * UniversalFirewallFragment - Universal Firewall.
 * TODO: Search feature is removed for firewall header testing
 */

class UniversalFirewallFragment : Fragment() , SearchView.OnQueryTextListener {

    private lateinit var firewallAllAppsToggle : SwitchCompat
    private lateinit var firewallAllAppsTxt : TextView

    private lateinit var universalFirewallTxt : TextView
    private lateinit var screenLockLL : LinearLayout
    private lateinit var allAppBgLL : LinearLayout

    private lateinit var whiteListHeader : RelativeLayout
    private lateinit var whiteListExpTxt : TextView
    private lateinit var whitelistCountTxt : TextView

    private lateinit var ipRulesHeader : TextView
    private lateinit var ipSearchView: SearchView
    private lateinit var ipSearchViewContainer : LinearLayout
    private lateinit var ipRulesExpTxt : TextView
    private lateinit var ipRulesNoRulesSetTxt : TextView
    private lateinit var ipRulesDeleteBtn : ImageView
    //private lateinit var ipRulesAddBtn : ImageView
    private lateinit var ipSearchCardContainer : CardView

    private lateinit var  firewallNotEnabledLL : LinearLayout

    private lateinit var backboardModeToggleText : TextView
    private lateinit var backgroundModeToggle : SwitchCompat

    private lateinit var unknownToggleText : TextView
    private lateinit var unknownToggle : SwitchCompat

    private lateinit var udpBlockToggleText : TextView
    private lateinit var udpBlockToggle : SwitchCompat

    private lateinit var recyclerView: RecyclerView
    private var recyclerAdapter : UniversalAppListAdapter ?= null
    private var recyclerRulesAdapter : UniversalBlockedRulesAdapter?= null
    private var layoutManager: RecyclerView.LayoutManager? = null
    private val viewModel: BlockedConnectionsViewModel by viewModels()
    private val appInfoViewModel : AppListViewModel by viewModels()

    private var universalState : Boolean = false
    private var ipListState : Boolean = false

    private lateinit var scrollView : NestedScrollView

    companion object {
        fun newInstance() = UniversalFirewallFragment()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreate(savedInstanceState)
        val view: View = inflater.inflate(R.layout.universal_fragement_container, container, false)
        if(DEBUG) Log.d(LOG_TAG, "UniversalFirewallFragment - onCreateView")
        initView(view)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        viewModel.blockedUnivRulesList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(recyclerRulesAdapter!!::submitList))
        appInfoViewModel.appDetailsList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(recyclerAdapter!!::submitList))
        super.onViewCreated(view, savedInstanceState)
    }

    private fun initView(view: View) {
        val includeView = view.findViewById<View>(R.id.app_scrolling_incl_firewall)
        scrollView = includeView as NestedScrollView
        //scrollView = view.findViewById(R.id.firewall_scroll_view)
        firewallNotEnabledLL = includeView.findViewById(R.id.firewall_scroll_connect_check)
        universalFirewallTxt = includeView.findViewById(R.id.firewall_universal_top_text)
        screenLockLL = includeView.findViewById(R.id.firewall_screen_ll)
        allAppBgLL = includeView.findViewById(R.id.firewall_background_ll)
        recyclerView = includeView.findViewById<View>(R.id.firewall_universal_recycler) as RecyclerView


        ipSearchView = includeView.findViewById(R.id.firewall_search_view)
        ipSearchViewContainer = includeView.findViewById(R.id.firewall_search_view_top)
        ipRulesExpTxt = includeView.findViewById(R.id.firewall_univ_whitelist_rules_exp_txt)
        ipRulesNoRulesSetTxt = includeView.findViewById(R.id.firewall_no_rules_set_txt)
        //ipRulesAddBtn = includeView.findViewById(R.id.firewall_search_add_icon)
        ipRulesDeleteBtn = includeView.findViewById(R.id.firewall_search_delete_icon)
        ipSearchCardContainer = includeView.findViewById(R.id.firewall_search_container)

        whiteListHeader = includeView.findViewById(R.id.firewall_apps_show_txt)
        whiteListExpTxt = includeView.findViewById(R.id.firewall_univ_whitelist_exp_txt)
        whitelistCountTxt = includeView.findViewById(R.id.firewall_univ_whitelist_count)

        ipRulesHeader = includeView.findViewById(R.id.firewall_rules_show_txt)
        val isServiceRunning = Utilities.isServiceRunning(requireContext(), BraveVPNService::class.java)

        if(!isServiceRunning){
            firewallNotEnabledLL.visibility = View.GONE
            return
        }else{
            firewallNotEnabledLL.visibility = View.VISIBLE
        }

        setIPRulesVisible()

        recyclerView.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(requireContext())
        recyclerView.layoutManager = layoutManager
        AppListViewModel.setContext(requireContext())
        BlockedConnectionsViewModel.setContext(requireContext())
        recyclerRulesAdapter = UniversalBlockedRulesAdapter(requireContext())
        recyclerAdapter = UniversalAppListAdapter(requireContext())
        recyclerView.adapter = recyclerRulesAdapter
        ipSearchView.requestFocus()

        //recyclerView.isNestedScrollingEnabled = false
        //ViewCompat.setNestedScrollingEnabled(recyclerView, false)

        if(DEBUG) Log.d(LOG_TAG, "UniversalFirewallFragment - post observer")

        //Firewall Toggle
        firewallAllAppsToggle = includeView.findViewById(R.id.firewall_all_apps_check)
        firewallAllAppsTxt = includeView.findViewById(R.id.firewall_all_apps_txt)
        backgroundModeToggle = includeView.findViewById(R.id.firewall_background_mode_check)
        backboardModeToggleText = includeView.findViewById(R.id.firewall_background_mode_txt)
        unknownToggleText = includeView.findViewById(R.id.firewall_unknown_connection_mode_txt)
        unknownToggle = includeView.findViewById(R.id.firewall_unknown_connection_mode_check)
        udpBlockToggle = includeView.findViewById(R.id.firewall_udp_connection_mode_check)
        udpBlockToggleText = includeView.findViewById(R.id.firewall_udp_connection_mode_txt)

        firewallAllAppsToggle.isChecked = PersistentState.getFirewallModeForScreenState(requireContext())

        if(Utilities.isAccessibilityServiceEnabledEnhanced(requireContext(), BackgroundAccessibilityService::class.java)){
            if(DEBUG) Log.d(LOG_TAG,"Background - onLoad accessibility is true")
            backgroundModeToggle.isChecked = PersistentState.getBackgroundEnabled(requireContext())
        }else{
            if(DEBUG) Log.d(LOG_TAG,"Background - onLoad accessibility is true, changed pref")
            PersistentState.setBackgroundEnabled(requireContext(), false)
            backgroundModeToggle.isChecked = false
        }

        udpBlockToggle.isChecked = PersistentState.getUDPBlockedSettings(requireContext())
        unknownToggle.isChecked =  PersistentState.getBlockUnknownConnections(requireContext())

        firewallAllAppsToggle.setOnCheckedChangeListener { _, b ->
            PersistentState.setFirewallModeForScreenState(requireContext(), b)
        }

        firewallAllAppsTxt.setOnClickListener {
            if(PersistentState.getFirewallModeForScreenState(requireContext())){
                firewallAllAppsToggle.isChecked = false
                PersistentState.setFirewallModeForScreenState(requireContext(), false)
            }else{
                firewallAllAppsToggle.isChecked = true
                PersistentState.setFirewallModeForScreenState(requireContext(), true)
            }
        }

        unknownToggle.setOnCheckedChangeListener{ compoundButton: CompoundButton, b: Boolean ->
            PersistentState.setBlockUnknownConnections(requireContext(), b)
        }

        unknownToggleText.setOnClickListener {
            PersistentState.setBlockUnknownConnections(requireContext(), !unknownToggle.isChecked)
            unknownToggle.isChecked = !unknownToggle.isChecked
        }

        udpBlockToggle.setOnCheckedChangeListener{ compoundButton: CompoundButton, b: Boolean ->
            PersistentState.setUDPBlockedSettings(requireContext(), b)
        }

        udpBlockToggleText.setOnClickListener{
            PersistentState.setUDPBlockedSettings(requireContext(), !udpBlockToggle.isChecked)
            udpBlockToggle.isChecked = !udpBlockToggle.isChecked
        }

        /*ipRulesAddBtn.setOnClickListener{
            Utilities.showToastInMidLayout(requireContext(),"Yet to implement",Toast.LENGTH_SHORT)
        }*/

        //Background mode toggle
        backboardModeToggleText.setOnClickListener {
            val checkedVal = backgroundModeToggle.isChecked
            if(!checkedVal) {
                if (Utilities.isAccessibilityServiceEnabledEnhanced(requireContext(), BackgroundAccessibilityService::class.java)) {
                    GlobalVariable.isBackgroundEnabled = !checkedVal
                    PersistentState.setBackgroundEnabled(requireContext(), !checkedVal)
                    backgroundModeToggle.isChecked = !checkedVal
                } else {
                    if (!showAlertForPermission()) {
                        backgroundModeToggle.isChecked = false
                        PersistentState.setBackgroundEnabled(requireContext(), false)
                    }
                }
            }else{
                backgroundModeToggle.isChecked = false
                PersistentState.setBackgroundEnabled(requireContext(), false)
            }
        }

        backgroundModeToggle.setOnCheckedChangeListener(null)

        backgroundModeToggle.setOnClickListener{
            val checkedVal = !backgroundModeToggle.isChecked
            if (!checkedVal) {
                if (Utilities.isAccessibilityServiceEnabledEnhanced(requireContext(), BackgroundAccessibilityService::class.java)) {
                    GlobalVariable.isBackgroundEnabled = !checkedVal
                    PersistentState.setBackgroundEnabled(requireContext(), !checkedVal)
                    backgroundModeToggle.isChecked = !checkedVal
                } else {
                    if (!showAlertForPermission()) {
                        backgroundModeToggle.isChecked = false
                        PersistentState.setBackgroundEnabled(requireContext(), false)
                    }
                }
            } else {
                backgroundModeToggle.isChecked = false
                PersistentState.setBackgroundEnabled(requireContext(), false)
            }
        }

        val mDb = AppDatabase.invoke(requireContext().applicationContext)
        val appInfoRepository = mDb.appInfoRepository()
        val appCount = GlobalVariable.appList.size
        val act: FirewallActivity = requireContext() as FirewallActivity
        appInfoRepository.getWhitelistCountLiveData().observe(act, Observer {
            whitelistCountTxt.text = "$it/$appCount apps whitelisted."
        })

        whiteListHeader.setOnClickListener{
            whiteListHeader.isEnabled = false
            val customDialog = WhitelistAppDialog(requireContext(), recyclerAdapter!!,appInfoViewModel)
            //if we know that the particular variable not null any time ,we can assign !!
            // (not null operator ), then  it won't check for null, if it becomes null,
            // it will throw exception
            customDialog.show()
            customDialog.setCanceledOnTouchOutside(false)
            Handler().postDelayed({ whiteListHeader.isEnabled = true }, 100)
        }

        ipRulesHeader.setOnClickListener {
            if (ipListState) {
                ipListState = false
                ipSearchViewContainer.visibility = View.VISIBLE
                recyclerView.visibility = View.VISIBLE
                ipRulesExpTxt.visibility = View.VISIBLE
                ipSearchCardContainer.visibility = View.VISIBLE
                ipRulesNoRulesSetTxt.visibility = View.VISIBLE
                ipRulesHeader.setCompoundDrawablesWithIntrinsicBounds(null, null, ContextCompat.getDrawable(requireContext(), R.drawable.ic_keyboard_arrow_down_gray_24dp), null)
            } else {
                ipListState = true
                ipSearchViewContainer.visibility = View.GONE
                recyclerView.visibility = View.GONE
                ipRulesExpTxt.visibility = View.VISIBLE
                ipRulesNoRulesSetTxt.visibility = View.GONE
                ipSearchCardContainer.visibility = View.GONE
                ipRulesHeader.setCompoundDrawablesWithIntrinsicBounds(null, null, ContextCompat.getDrawable(requireContext(), R.drawable.ic_keyboard_arrow_up_gray_24dp), null)
            }
        }

        ipRulesDeleteBtn.setOnClickListener{
            showDialogForDelete()
        }

        ipSearchView.setOnQueryTextListener(this)
        ipSearchView.setOnClickListener {
            ipSearchView.requestFocus()
            ipSearchView.onActionViewExpanded()
        }

    }

    private fun setIPRulesVisible(){
        ipListState = false
        ipSearchViewContainer.visibility = View.VISIBLE
        recyclerView.visibility = View.VISIBLE
        ipRulesExpTxt.visibility = View.VISIBLE
        ipSearchCardContainer.visibility = View.VISIBLE
        ipRulesNoRulesSetTxt.visibility = View.VISIBLE
        ipRulesHeader.setCompoundDrawablesWithIntrinsicBounds(null, null, ContextCompat.getDrawable(requireContext(), R.drawable.ic_keyboard_arrow_down_gray_24dp), null)
    }

    private fun showDialogForDelete() {
        val mDb = AppDatabase.invoke(requireContext().applicationContext)
        val blockedConnectionsRepository = mDb.blockedConnectionRepository()
        val count = blockedConnectionsRepository.getBlockedConnectionsCount()
        if(count > 0) {
            val builder = AlertDialog.Builder(requireContext())
            //set title for alert dialog
            builder.setTitle(R.string.univ_delete_firewall_dialog_title)
            //set message for alert dialog
            builder.setMessage(R.string.univ_delete_firewall_dialog_message)
            builder.setIcon(android.R.drawable.ic_dialog_alert)
            builder.setCancelable(true)
            //performing positive action
            builder.setPositiveButton("Delete all") { dialogInterface, which ->

                blockedConnectionsRepository.deleteAllIPRulesUniversal()
                GlobalVariable.firewallRules.clear()
                Utilities.showToastInMidLayout(requireContext(), "Deleted all IP rules.", Toast.LENGTH_SHORT)
            }

            //performing negative action
            builder.setNegativeButton("Cancel") { dialogInterface, which ->
            }
            // Create the AlertDialog
            val alertDialog: AlertDialog = builder.create()
            // Set other dialog properties
            alertDialog.setCancelable(true)
            alertDialog.show()
        }else{
            Utilities.showToastInMidLayout(requireContext(),"No IP rules set",Toast.LENGTH_SHORT)
        }
    }

    override fun onResume() {
        super.onResume()
        unknownToggle.isChecked =  PersistentState.getBlockUnknownConnections(requireContext())
    }

    private fun showAlertForPermission() : Boolean {
        var isAllowed  = false
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(R.string.alert_permission_accessibility)
        //set message for alert dialog
        builder.setMessage(R.string.alert_firewall_accessibility_explanation)
        builder.setIcon(android.R.drawable.ic_dialog_alert)
        //performing positive action
        builder.setPositiveButton("Grant"){ _, _ ->
            isAllowed = true
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivityForResult(intent, 0)
        }
        //performing negative action
        builder.setNegativeButton("Deny"){ _, _ ->
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
