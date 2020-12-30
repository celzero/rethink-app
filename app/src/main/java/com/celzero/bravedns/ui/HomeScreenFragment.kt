/*
 * Copyright 2020 RethinkDNS and its authors
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

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.*
import android.icu.text.CompactDecimalFormat
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.CountDownTimer
import android.os.Handler
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatSpinner
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SpinnerArrayAdapter
import com.celzero.bravedns.data.BraveMode
import com.celzero.bravedns.database.AppInfoRepository
import com.celzero.bravedns.database.CategoryInfoRepository
import com.celzero.bravedns.service.*
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appStartTime
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.blockedCount
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveModeToggler
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.lifeTimeQ
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.lifeTimeQueries
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.median50
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.numUniversalBlock
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Utilities
import com.facebook.shimmer.Shimmer
import com.facebook.shimmer.ShimmerFrameLayout
import com.google.android.material.chip.Chip
import org.koin.android.ext.android.inject
import settings.Settings
import java.net.NetworkInterface
import java.net.SocketException
import java.util.*


class HomeScreenFragment : Fragment() {

    private lateinit var layoutHeaderView : TextView
    private lateinit var layoutHeaderViewDesc : TextView

    private lateinit var appManagerLL: LinearLayout

    private lateinit var firewallLL: LinearLayout

    private lateinit var cardViewTileTop : CardView

    private lateinit var tileShowFirewallLL: LinearLayout
    private lateinit var tileShowDnsLL: LinearLayout
    private lateinit var tileShowDnsFirewallLL: LinearLayout

    private lateinit var braveModeSpinner: AppCompatSpinner
    private lateinit var braveModeInfoIcon: ImageView

    private lateinit var dnsOnOffBtn: AppCompatButton

    private lateinit var tileDLifetimeQueriesTxt: TextView
    private lateinit var tileDmedianTxt: TextView
    private lateinit var tileDtrackersBlockedTxt: TextView

    private lateinit var tileFAppsBlockedTxt: TextView
    private lateinit var tileFCategoryBlockedTxt: TextView
    private lateinit var tileFUniversalBlockedTxt: TextView

    private lateinit var tileDFAppsBlockedTxt: TextView
    private lateinit var tileDFMedianTxt: TextView
    private lateinit var tileDFTrackersBlockedtxt: TextView

    private lateinit var appUpTimeTxt: TextView

    private lateinit var chipConfigureFirewall: AppCompatButton
    private lateinit var chipViewLogs: AppCompatButton
    private lateinit var btmSheetIcon : Button
    private lateinit var chipNetworkMonitor : Chip
    private lateinit var chipSettings : Chip

    private lateinit var protectionDescTxt: TextView
    private lateinit var protectionLevelTxt: TextView

    private lateinit var shimmerContainer: ShimmerFrameLayout
    private lateinit var shimmerHeaderContainer : ShimmerFrameLayout

    private val MAIN_CHANNEL_ID = "vpn"

    private var REQUEST_CODE_PREPARE_VPN: Int = 100

    private val categoryInfoRepository by inject<CategoryInfoRepository>()
    private val appInfoRepository by inject<AppInfoRepository>()
    private val persistentState by inject<PersistentState>()

    companion object {
        //private
        const val DNS_MODE = 0
        const val FIREWALL_MODE = 1
        const val DNS_FIREWALL_MODE = 2
        private const val GREETING_CHANNEL_ID = 2021
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view: View = inflater.inflate(R.layout.fragment_home_screen, container, false)
        initView(view)
        syncDnsStatus()
        initializeValues()
        initializeClickListeners()

        //Show Tile
        showTileForMode()
        //updateTime()
        updateUptime()
        registerForBroadCastReceivers()

        checkForHeaderUpdate()

        return view
    }

    /**
    *   Function to show the new year wishes to the user,
    *   As of now the function will check for new year time and
    *   will turn the text heading and description.
     */
    private fun checkForHeaderUpdate() {
        val currentTime = Calendar.getInstance(Locale.ROOT)
        val timeToMatch = Calendar.getInstance(Locale.ROOT)
        val timeToOverride = Calendar.getInstance(Locale.ROOT)

        timeToMatch[Calendar.HOUR_OF_DAY] = 23
        timeToMatch[Calendar.MINUTE] = 59
        timeToMatch[Calendar.SECOND] = 59
        timeToMatch[Calendar.DAY_OF_MONTH] = 31
        timeToMatch[Calendar.MONTH] = 11
        timeToMatch[Calendar.YEAR] = 2020

        timeToOverride[Calendar.HOUR_OF_DAY] = 23
        timeToOverride[Calendar.MINUTE] = 59
        timeToOverride[Calendar.SECOND] = 59
        timeToOverride[Calendar.DAY_OF_MONTH] = 1
        timeToOverride[Calendar.MONTH] = 0
        timeToOverride[Calendar.YEAR] = 2021
        if(DEBUG) Log.d(LOG_TAG, "NewYearAlarm : ${currentTime.time}, ${timeToMatch.time}, ${timeToOverride.time} ")
        if(currentTime > timeToMatch && currentTime < timeToOverride ){
            layoutHeaderView.text = getString(R.string.new_year)
            layoutHeaderViewDesc.text = getString(R.string.new_year_desc)
        }else{
            layoutHeaderView.text = getString(R.string.app_name).toLowerCase(Locale.ROOT)
            layoutHeaderViewDesc.text = getString(R.string.backed_by_mozilla)
        }
    }

    /*
        Registering the broadcast receiver for the DNS State and the DNS results returned
     */
    private fun registerForBroadCastReceivers() {
        // Register broadcast receiver
        val intentFilter = IntentFilter(InternalNames.DNS_STATUS.name)
        //intentFilter.addAction(InternalNames.DNS_STATUS.name)
        //intentFilter.addAction(InternalNames.TRACKER.name)
        LocalBroadcastManager.getInstance(requireContext()).registerReceiver(messageReceiver, intentFilter)
    }

    /*
        Assign initial values to the view and variables.
     */
    private fun initializeValues() {
        val braveModeList = getAllModes()
        val spinnerAdapter = SpinnerArrayAdapter(requireContext(), braveModeList)

        // Set layout to use when the list of choices appear
        // spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        // Set Adapter to Spinner
        braveModeSpinner.adapter = spinnerAdapter

        braveMode = persistentState.getBraveMode()

        if (braveMode == -1) {
            braveModeSpinner.setSelection(DNS_FIREWALL_MODE)
        } else {
            braveModeSpinner.setSelection(braveMode)
        }

        modifyBraveMode(braveMode)

        lifeTimeQueries = persistentState.getNumOfReq()
        //var lifeTimeQ : MutableLiveData<Int> = MutableLiveData()
        //val aList = PersistentState.getExcludedPackagesWifi(requireContext())
        //appsBlocked.postValue(aList!!.size)
        blockedCount.postValue(persistentState.numberOfBlockedRequests)
    }


    private fun initView(view: View) {

        layoutHeaderView = view.findViewById(R.id.fhs_title_rethink)
        layoutHeaderViewDesc = view.findViewById(R.id.fhs_title_rethink_desc)

        //Complete UI component initialization for the view
        dnsOnOffBtn = view.findViewById(R.id.fhs_dns_on_off_btn)

        /*val detectSwipe = DetectSwipe()
        dnsOnOffBtn.setOnTouchListener(detectSwipe)*/

        //Shimmer Animation for the heading and start btn
        shimmerContainer = view.findViewById(R.id.shimmer_view_container1)

       /* shimmerHeaderContainer = view.findViewById(R.id.shimmer_view_heading)
        //shimmerHeaderContainer.baseAlpha = F
        shimmerHeaderContainer

        shimmerHeaderContainer.startShimmer()*/

        cardViewTileTop = view.findViewById(R.id.fhs_card_home_top_tile)

        appManagerLL = view.findViewById(R.id.fhs_ll_app_mgr)

        firewallLL = view.findViewById(R.id.fhs_ll_firewall)

        tileShowFirewallLL = view.findViewById(R.id.fhs_tile_show_firewall)
        tileShowDnsLL = view.findViewById(R.id.fhs_tile_show_dns)
        tileShowDnsFirewallLL = view.findViewById(R.id.fhs_tile_show_dns_firewall)

        tileDLifetimeQueriesTxt = view.findViewById(R.id.fhs_tile_dns_lifetime_txt)
        tileDmedianTxt = view.findViewById(R.id.fhs_tile_dns_median_latency_txt)
        tileDtrackersBlockedTxt = view.findViewById(R.id.fhs_tile_dns_trackers_blocked_txt)

        tileFAppsBlockedTxt = view.findViewById(R.id.fhs_tile_firewall_apps_txt)
        tileFCategoryBlockedTxt = view.findViewById(R.id.fhs_tile_firewall_category_txt)
        tileFUniversalBlockedTxt = view.findViewById(R.id.fhs_tile_firewall_universal_txt)

        tileDFAppsBlockedTxt = view.findViewById(R.id.fhs_tile_dns_firewall_apps_blocked_txt)
        tileDFMedianTxt = view.findViewById(R.id.fhs_tile_dns_firewall_median_latency_txt)
        tileDFTrackersBlockedtxt = view.findViewById(R.id.fhs_tile_dns_firewall_trackers_blocked_txt)

        appUpTimeTxt = view.findViewById(R.id.fhs_app_uptime)

        protectionLevelTxt = view.findViewById(R.id.fhs_protection_level_txt)
        protectionDescTxt = view.findViewById(R.id.fhs_app_connected_desc)

        //Spinner Adapter code
        braveModeSpinner = view.findViewById(R.id.fhs_brave_mode_spinner)
        braveModeInfoIcon = view.findViewById(R.id.fhs_dns_mode_info)
        //For Chip
        chipConfigureFirewall = view.findViewById(R.id.chip_configure_firewall)
        chipViewLogs = view.findViewById(R.id.chip_view_logs)
        chipSettings = view.findViewById(R.id.chip_settings)
        chipNetworkMonitor = view.findViewById(R.id.chip_network_monitor)

        btmSheetIcon = view.findViewById(R.id.home_fragment_bottom_sheet_icon)
    }

    private fun initializeClickListeners() {
    // BraveMode Spinner OnClick
        braveModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {
                braveMode = DNS_FIREWALL_MODE
            }
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                braveModeSpinner.isEnabled = false
                modifyBraveMode(position)
                showTileForMode()
                object : CountDownTimer(1000, 500) {
                    override fun onTick(millisUntilFinished: Long) {
                        braveModeSpinner.isEnabled = false
                    }
                    override fun onFinish() {
                        braveModeSpinner.isEnabled = true
                    }
                }.start()
            }
        }

        // Brave Mode Information Icon which shows the dialog - explanation
        braveModeInfoIcon.setOnClickListener {
            showDialogForBraveModeInfo()
        }

        //Chips for the Configure Firewall
        chipConfigureFirewall.setOnClickListener {
            if(DEBUG) Log.d(LOG_TAG, "Status : Configure firewall clicked")
            startFirewallActivity()
        }

        //Chips for the DNS Screen
        chipViewLogs.setOnClickListener {
            //startQueryListener()
            startConnectionTrackerActivity()
        }

        chipNetworkMonitor.setOnClickListener{
            //startBottomSheetForSettings()
        }

        chipSettings.setOnClickListener{
            /*val threadPoolExecutor = ThreadPoolExecutor(
                2, 2, 0,
                TimeUnit.MILLISECONDS, LinkedBlockingQueue<Runnable>()
            )
            val mainExecutor: Executor = threadPoolExecutor
            val cancellationSignal: CancellationSignal = CancellationSignal()
            val callBack: DnsResolver.Callback<in MutableList<InetAddress>>? = TestCallBack()
            if (callBack != null) {
                DnsResolver.getInstance().query(null, "www.segment.io", 0, mainExecutor, cancellationSignal, callBack)
            }*/
        }

        btmSheetIcon.setOnClickListener{
            btmSheetIcon.isEnabled = false
            openBottomSheet()
            Handler().postDelayed({ btmSheetIcon.isEnabled = true }, 500)
            //startBottomSheetForSettings()
        }

        // Connect/Disconnect button ==> TODO : Change the label to Start and Stop
        dnsOnOffBtn.setOnClickListener {
            handleStartBtnClickEvent()
        }

        categoryInfoRepository.getAppCategoryForLiveData().observe(viewLifecycleOwner, {
            val list = it.filter { a -> a.isInternetBlocked }
            tileFCategoryBlockedTxt.text = list.size.toString()
        })

        median50.observe(viewLifecycleOwner, {
            tileDmedianTxt.text = median50.value.toString() + "ms"
            tileDFMedianTxt.text = median50.value.toString() + "ms"
        })

        lifeTimeQ.observe(viewLifecycleOwner, {
            val lifeTimeConversion = if (VERSION.SDK_INT >= VERSION_CODES.N) {
                CompactDecimalFormat.getInstance(Locale.US, CompactDecimalFormat.CompactStyle.SHORT).format(lifeTimeQ.value)
            } else {
                // FIXME: 19-11-2020 - Format the number similar to CompctDecimalFormat
                lifeTimeQ.value.toString()
            }
            tileDLifetimeQueriesTxt.text = lifeTimeConversion
        })

         blockedCount.observe(viewLifecycleOwner, {
             val blocked = if (VERSION.SDK_INT >= VERSION_CODES.N) {
                 CompactDecimalFormat.getInstance(Locale.US, CompactDecimalFormat.CompactStyle.SHORT).format(blockedCount.value)
             } else {
                 // FIXME: 19-11-2020 - Format the number similar to CompctDecimalFormat
                 blockedCount.value.toString()
             }
             tileDFTrackersBlockedtxt.text = blocked
             tileDtrackersBlockedTxt.text = blocked
         })

        appInfoRepository.getBlockedAppCount().observe(viewLifecycleOwner, {
            tileFAppsBlockedTxt.text = it.toString()
            tileDFAppsBlockedTxt.text = it.toString()
        })

        numUniversalBlock.observe(viewLifecycleOwner, {
            tileFUniversalBlockedTxt.text = numUniversalBlock.value.toString()
        })

        braveModeToggler.observe(viewLifecycleOwner, {
            if (DEBUG) Log.d(LOG_TAG, "HomeScreen -> braveModeToggler -> observer")
            if (persistentState.vpnEnabled) {
                enableBraveModeIcons()
                showTileForMode()
            }
        })
    }

    private fun handleStartBtnClickEvent() {
        dnsOnOffBtn.isEnabled = false
        //TODO : check for the service already running
        //val status = VpnController.getInstance()!!.getState(requireContext())
        val status = persistentState.vpnEnabled
        if (!checkForPrivateDNSandAlwaysON()) {
            //if (status!!.activationRequested) {
            if (status) {
                appStartTime = System.currentTimeMillis()
                dnsOnOffBtn.setText("start")
                updateUIForStop()
                //rippleRRLayout.startRippleAnimation()
                protectionDescTxt.setText(getString(R.string.dns_explanation_disconnected))
                stopDnsVpnService()
            } else {
                appStartTime = System.currentTimeMillis()
                if (DEBUG) Log.d(LOG_TAG, "VPN service start initiated with time $appStartTime")
                if (VpnController.getInstance()?.getBraveVpnService() != null) {
                    VpnController.getInstance()?.stop(requireContext())
                    if (DEBUG) Log.d(LOG_TAG, "VPN service start initiated but the brave service is already running - so stop called")
                } else {
                    if (DEBUG) Log.d(LOG_TAG, "VPN service start initiated")
                }
                prepareAndStartDnsVpn()
            }
        } else if (status) {
            Utilities.showToastInMidLayout(requireContext(), getString(R.string.always_on_rethink_enabled), Toast.LENGTH_SHORT)
        }
        Handler().postDelayed({ dnsOnOffBtn.isEnabled = true }, 500)
    }

    private fun openBottomSheet() {
        val textConnectionDetails = protectionDescTxt.text.toString()
        val bottomSheetFragment = HomeScreenSettingBottomSheet(textConnectionDetails)
        val frag = context as FragmentActivity
        bottomSheetFragment.show(frag.supportFragmentManager, bottomSheetFragment.tag)
    }


    private fun startBottomSheetForSettings() {
        val bottomSheetFragment = SettingsBottomSheetFragment()
        val frag = activity as FragmentActivity
        bottomSheetFragment.show(frag.supportFragmentManager, bottomSheetFragment.tag)
    }

    // FIXME: 19-11-2020 - Check the below code for all the edge cases.
    private fun checkForPrivateDNSandAlwaysON() : Boolean {
        val stats = persistentState.vpnEnabled
        val alwaysOn = android.provider.Settings.Secure.getString(context?.contentResolver, "always_on_vpn_app")
        if (!TextUtils.isEmpty(alwaysOn)) {
            if (context?.packageName == alwaysOn) {
                if (DEBUG) Log.i(LOG_TAG, "Status: $stats , alwaysOn: $alwaysOn - lockdown")
            } else if (!stats) {
                if (DEBUG) Log.i(LOG_TAG, "Status: $stats , alwaysOn: $alwaysOn - stats value")
                showDialogForAlwaysOn()
            }
            return true
        }
        if (DEBUG) Log.i(LOG_TAG, "Status: $stats , alwaysOn: $alwaysOn")
        if (getPrivateDnsMode() == PrivateDnsMode.STRICT) {
            Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.private_dns_toast).toString(), Toast.LENGTH_SHORT)
        }
        return false
    }

    private fun startConnectionTrackerActivity() {
            val status: VpnState? = VpnController.getInstance()!!.getState(context)
            if((status?.on!!) ) {
                if(braveMode == DNS_FIREWALL_MODE || braveMode == DNS_MODE && (getPrivateDnsMode() != PrivateDnsMode.STRICT)) {
                    val intent = Intent(requireContext(), DNSDetailActivity::class.java)
                    startActivity(intent)
                }else{
                    if(getPrivateDnsMode() == PrivateDnsMode.STRICT){
                        Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.private_dns_toast).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
                    }
                    Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.brave_dns_connect_mode_change_dns).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
                }
            }else{
                Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.brave_dns_connect_mode_change_dns).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
            }
        }

    private fun showDialogForAlwaysOn() {
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(R.string.always_on_dialog_heading)
        //set message for alert dialog
        builder.setMessage(R.string.always_on_dialog)
        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton(R.string.always_on_dialog_positive_btn) { dialogInterface, which ->
            val intent = Intent("android.net.vpn.SETTINGS")
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }

        builder.setNegativeButton(R.string.always_on_dialog_negative_btn) { dialogInterface, which ->
        }

        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(false)
        alertDialog.show()

    }


    private fun showTileForMode() {
        if (braveMode == DNS_MODE) {
            tileShowDnsLL.visibility = View.VISIBLE
            tileShowFirewallLL.visibility = View.GONE
            tileShowDnsFirewallLL.visibility = View.GONE
        } else if (braveMode == FIREWALL_MODE) {
            tileShowDnsLL.visibility = View.GONE
            tileShowFirewallLL.visibility = View.VISIBLE
            tileShowDnsFirewallLL.visibility = View.GONE
        } else if (braveMode == DNS_FIREWALL_MODE) {
            tileShowDnsLL.visibility = View.GONE
            tileShowFirewallLL.visibility = View.GONE
            tileShowDnsFirewallLL.visibility = View.VISIBLE
        }
        braveModeSpinner.isEnabled = true
    }

    //https://stackoverflow.com/questions/2614545/animate-change-of-view-background-color-on-android/14467625#14467625
    private fun enableBraveModeIcons() {
        val fadeOut: Animation = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out)
        val fadeIn: Animation = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in)

        if (braveMode == DNS_MODE) {
            chipViewLogs.startAnimation(fadeOut)
            chipViewLogs.setBackgroundResource(R.drawable.rounded_corners_button_primary)
            chipViewLogs.startAnimation(fadeIn)
            chipConfigureFirewall.startAnimation(fadeOut)
            chipConfigureFirewall.setBackgroundResource(R.drawable.rounded_corners_button_accent)
            chipConfigureFirewall.startAnimation(fadeIn)
        } else if (braveMode == FIREWALL_MODE) {
            chipViewLogs.startAnimation(fadeOut)
            chipViewLogs.setBackgroundResource(R.drawable.rounded_corners_button_accent)
            chipViewLogs.startAnimation(fadeIn)
            chipConfigureFirewall.startAnimation(fadeOut)
            chipConfigureFirewall.setBackgroundResource(R.drawable.rounded_corners_button_primary)
            chipConfigureFirewall.startAnimation(fadeIn)
        } else if (braveMode == DNS_FIREWALL_MODE) {
            chipViewLogs.startAnimation(fadeOut)
            chipViewLogs.setBackgroundResource(R.drawable.rounded_corners_button_primary)
            chipViewLogs.startAnimation(fadeIn)

            chipConfigureFirewall.startAnimation(fadeOut)
            chipConfigureFirewall.setBackgroundResource(R.drawable.rounded_corners_button_primary)
            chipConfigureFirewall.startAnimation(fadeIn)
        }
    }

    private fun disableBraveModeIcon() {
        val fadeOut: Animation = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_out)
        val fadeIn: Animation = AnimationUtils.loadAnimation(requireContext(), R.anim.fade_in)
        chipViewLogs.startAnimation(fadeOut)
        chipViewLogs.setBackgroundResource(R.drawable.rounded_corners_button_accent)
        chipViewLogs.startAnimation(fadeIn)

        chipConfigureFirewall.startAnimation(fadeOut)
        chipConfigureFirewall.setBackgroundResource(R.drawable.rounded_corners_button_accent)
        chipConfigureFirewall.startAnimation(fadeIn)
    }


    override fun onResume() {
        super.onResume()
        syncDnsStatus()
        updateUptime()
        if (persistentState.vpnEnabled) {
            enableBraveModeIcons()
            shimmerForStart()
        }else{
            shimmerForStop()
        }
    }




    /**
     * Start the Firewall activity from the chip
     */
    private fun startFirewallActivity() {
        val status: VpnState? = VpnController.getInstance()!!.getState(requireContext())
        if(DEBUG) Log.d(LOG_TAG, "Status : ${status?.on!!} , BraveMode: $braveMode")
         if(status?.on!!){
            if (braveMode == DNS_FIREWALL_MODE || braveMode == FIREWALL_MODE) {
                val intent = Intent(requireContext(), FirewallActivity::class.java)
                startActivity(intent)
            } else {
                Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.brave_dns_connect_mode_change_firewall).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
            }
        }else {
             Utilities.showToastInMidLayout(requireContext(), resources.getText(R.string.brave_dns_connect_mode_change_firewall).toString().capitalize(Locale.ROOT), Toast.LENGTH_SHORT)
        }
    }

    //TODO -- Modify the logic for the below.
    private fun modifyBraveMode(position: Int) {
        var firewallMode = -1L
        if (position == 0) {
            firewallMode = Settings.BlockModeNone
            braveMode = DNS_MODE
        } else if (position == 1) {
            firewallMode = if (VERSION.SDK_INT >= VERSION_CODES.M && VERSION.SDK_INT < VERSION_CODES.Q)
                Settings.BlockModeFilterProc
            else
                Settings.BlockModeFilter
            braveMode = FIREWALL_MODE
        } else if (position == 2) {
            firewallMode = if(VERSION.SDK_INT >= VERSION_CODES.M && VERSION.SDK_INT < VERSION_CODES.Q)
                Settings.BlockModeFilterProc
            else
                Settings.BlockModeFilter
            braveMode = DNS_FIREWALL_MODE
        }
        if(DEBUG) Log.d(LOG_TAG, "Firewall mode : $firewallMode, braveMode: $braveMode, SDK_Version: ${VERSION_CODES.M} == ${VERSION.SDK_INT}")
        appMode?.setFirewallMode(firewallMode)

        if(braveMode == FIREWALL_MODE){
            appMode?.setDNSMode(Settings.DNSModeNone)
        }else{
            appMode?.setDNSMode(-1)
        }

        //PersistentState.setFirewallMode(requireContext(), firewallMode)
        persistentState.setBraveMode(braveMode)

        if (VpnController.getInstance()!!.getState(requireContext())!!.activationRequested) {
            if (braveMode == 0){
                protectionDescTxt.setText(getString(R.string.dns_explanation_dns_connected))
            }else if (braveMode == 1) {
                protectionDescTxt.setText(getString(R.string.dns_explanation_firewall_connected))
            } else {
                protectionDescTxt.setText(getString(R.string.dns_explanation_connected))
            }
        }

        braveModeSpinner.isEnabled = true
    }

    /**
     * Show the Info dialog for various modes in Brave
     */
    private fun showDialogForBraveModeInfo() {
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle("BraveDNS Modes")
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(R.layout.dialog_info_custom_layout)
        val okBtn = dialog.findViewById(R.id.info_dialog_cancel_img) as ImageView
        okBtn.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    // TODO : The data has been hardcoded need to modify
    @SuppressLint("ResourceType")
    private fun getAllModes(): ArrayList<BraveMode> {
        val braveNames = resources.getStringArray(R.array.brave_dns_mode_names)
        val icons = resources.obtainTypedArray(R.array.brave_dns_mode_icons)
        val braveList = ArrayList<BraveMode>(3)
        var braveModes = BraveMode(icons.getResourceId(0, -1), 0, braveNames[0])
        braveList.add(braveModes)
        braveModes = BraveMode(icons.getResourceId(1, -1), 1, braveNames[1])
        braveList.add(braveModes)
        braveModes = BraveMode(icons.getResourceId(2, -1), 2, braveNames[2])
        braveList.add(braveModes)
        icons.recycle()
        return braveList
    }

    override fun onDestroyView() {
        super.onDestroyView()
    }


    private val messageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
          if (InternalNames.DNS_STATUS.name == intent.action) {
                syncDnsStatus()
            }
        }
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(requireContext()).unregisterReceiver(messageReceiver)
        super.onDestroy()
    }

    private fun updateUptime() {
        val upTime = DateUtils.getRelativeTimeSpanString(appStartTime, System.currentTimeMillis(), MINUTE_IN_MILLIS, FORMAT_ABBREV_RELATIVE)
        appUpTimeTxt.setText("($upTime)")
    }

    private fun prepareAndStartDnsVpn() {
        if (hasVpnService()) {
            if (prepareVpnService()) {
                dnsOnOffBtn.setText("stop")
                dnsOnOffBtn.setBackgroundResource(R.drawable.rounded_corners_button_accent)
                updateUIForStart()
                startDnsVpnService()
                if(DEBUG) Log.d(LOG_TAG, "VPN service start initiated - startDnsVpnService()")
            }
        } else {
            Log.e("BraveVPN", "Device does not support system-wide VPN mode.")
        }
    }

    private fun updateUIForStart() {
        enableBraveModeIcons()
        shimmerForStart()
    }

    private fun shimmerForStart(){
        val builder = Shimmer.AlphaHighlightBuilder()
        builder.setDuration(10000)
        builder.setBaseAlpha(0.85f)
        builder.setDropoff(1f)
        builder.setHighlightAlpha(0.35f)
        shimmerContainer.setShimmer(builder.build())
    }

    private fun shimmerForStop(){
        val builder = Shimmer.AlphaHighlightBuilder()
        builder.setDuration(2000)
        builder.setBaseAlpha(0.85f)
        builder.setDropoff(1f)
        builder.setHighlightAlpha(0.35f)
        shimmerContainer.setShimmer(builder.build())
    }

    private fun updateUIForStop(){
        disableBraveModeIcon()
        shimmerForStop()
    }

    private fun stopDnsVpnService() {
        VpnController.getInstance()!!.stop(context)
    }

    private fun startDnsVpnService() {
        VpnController.getInstance()?.start(requireContext())
        if (braveMode == 0) {
            protectionDescTxt.setText(getString(R.string.dns_explanation_dns_connected))
        } else if (braveMode == 1) {
            protectionDescTxt.setText(getString(R.string.dns_explanation_firewall_connected))
        } else {
            protectionDescTxt.setText(getString(R.string.dns_explanation_connected))
        }
    }

    // Returns whether the device supports the tunnel VPN service.
    private fun hasVpnService(): Boolean {
        return VERSION.SDK_INT >= VERSION_CODES.ICE_CREAM_SANDWICH
    }

    @Throws(ActivityNotFoundException::class)
    private fun prepareVpnService(): Boolean {
        var prepareVpnIntent: Intent? = null
        prepareVpnIntent = try {
            VpnService.prepare(context)
        } catch (e: NullPointerException) {
            // This exception is not mentioned in the documentation, but it has been encountered by Intra
            // users and also by other developers, e.g. https://stackoverflow.com/questions/45470113.
            Log.e("BraveVPN", "Device does not support system-wide VPN mode.")
            return false
        }
        if (prepareVpnIntent != null) {
            startActivityForResult(prepareVpnIntent, REQUEST_CODE_PREPARE_VPN)
            //TODO - Check the below code
            syncDnsStatus() // Set DNS status to off in case the user does not grant VPN permissions
            return false
        }
        return true
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PREPARE_VPN && resultCode == Activity.RESULT_OK) {
            startDnsVpnService()
        } else {
            stopDnsVpnService()
        }
    }

    // Sets the UI DNS status on/off.
    private fun syncDnsStatus() {

        val status = VpnController.getInstance()!!.getState(requireContext())

        if (status!!.activationRequested || status.connectionState == BraveVPNService.State.WORKING) {
            dnsOnOffBtn.setBackgroundResource(R.drawable.rounded_corners_button_accent)
            dnsOnOffBtn.text = "stop"
            if (braveMode == 0)
                protectionDescTxt.text = getString(R.string.dns_explanation_dns_connected)
            else if (braveMode == 1)
                protectionDescTxt.text = getString(R.string.dns_explanation_firewall_connected)
            else
                protectionDescTxt.text = getString(R.string.dns_explanation_connected)
        } else {
            dnsOnOffBtn.setBackgroundResource(R.drawable.rounded_corners_button_primary)
            updateUIForStop()
            dnsOnOffBtn.text = "start"
            protectionDescTxt.text = getString(R.string.dns_explanation_disconnected)
        }

        // Change status and explanation text
        var statusId: Int = R.string.status_exposed
        val explanationId: Int
        var privateDnsMode: PrivateDnsMode = PrivateDnsMode.NONE
        if (status.activationRequested) {
            if (status.connectionState == null) {
                statusId = R.string.status_waiting
                explanationId = R.string.explanation_offline
            } else if (status.connectionState === BraveVPNService.State.NEW) {
                statusId = R.string.status_starting
                explanationId = R.string.explanation_starting
            } else if (status.connectionState === BraveVPNService.State.WORKING) {
                statusId = R.string.status_protected
                explanationId = R.string.explanation_protected
            } else {
                statusId = R.string.status_failing
                explanationId = R.string.explanation_failing
            }
        } else if (isAnotherVpnActive()) {
            statusId = R.string.status_exposed
            explanationId = R.string.explanation_vpn
        } else {
            privateDnsMode = getPrivateDnsMode()
            if (privateDnsMode == PrivateDnsMode.STRICT) {
                statusId = R.string.status_strict
                explanationId = R.string.explanation_strict
            } else if (privateDnsMode == PrivateDnsMode.UPGRADED) {
                statusId = R.string.status_exposed
                explanationId = R.string.explanation_upgraded
            } else {
                statusId = R.string.status_exposed
                explanationId = R.string.explanation_exposed
            }
        }
        if(DEBUG) Log.d(LOG_TAG, "Status - ${status.activationRequested}, ${status.connectionState}")

        if(status.connectionState == BraveVPNService.State.WORKING){
            statusId = R.string.status_protected
        }

        if(braveMode == 1 && status.activationRequested){
            statusId = R.string.status_protected
        }

        if(appMode?.getDNSType() == 3 && status.activationRequested){
            statusId = R.string.status_protected
        }

        var colorId: Int
        colorId = if (status.on) {
            if (status.connectionState != BraveVPNService.State.FAILING) R.color.positive else R.color.accent_bad
        } else if (privateDnsMode == PrivateDnsMode.STRICT) {
            // If the VPN is off but we're in strict mode, show the status in white.  This isn't a bad
            // state, but Intra isn't helping.
            R.color.indicator
        } else {
            R.color.accent_bad
        }
        if (braveMode == FIREWALL_MODE && status.activationRequested)
            colorId = R.color.positive

        val color = ContextCompat.getColor(requireContext(), colorId)
        protectionLevelTxt.setTextColor(color)
        protectionLevelTxt.setText(statusId)

    }

    private fun getPrivateDnsMode(): PrivateDnsMode {
        if (VERSION.SDK_INT < VERSION_CODES.P) {
            // Private DNS was introduced in P.
            return PrivateDnsMode.NONE
        }
        val linkProperties: LinkProperties = getLinkProperties()
            ?: return PrivateDnsMode.NONE
        if (linkProperties.privateDnsServerName != null) {
            return PrivateDnsMode.STRICT
        }
        return if (linkProperties.isPrivateDnsActive) {
            PrivateDnsMode.UPGRADED
        } else PrivateDnsMode.NONE
    }

    private fun getLinkProperties(): LinkProperties? {
        val connectivityManager =
            requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        if (VERSION.SDK_INT < VERSION_CODES.M) {
            // getActiveNetwork() requires M or later.
            return null
        }
        val activeNetwork = connectivityManager.activeNetwork ?: return null
        return connectivityManager.getLinkProperties(activeNetwork)
    }

    private fun isAnotherVpnActive(): Boolean {
        if (VERSION.SDK_INT >= VERSION_CODES.M) {
            val connectivityManager =
                requireContext().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = connectivityManager.activeNetwork ?: return false
            val capabilities =
                connectivityManager.getNetworkCapabilities(activeNetwork)
                    ?: // It's not clear when this can happen, but it has occurred for at least one user.
                    return false
            return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        }
        // For pre-M versions, return true if there's any network whose name looks like a VPN.
        try {
            val networkInterfaces =
                NetworkInterface.getNetworkInterfaces()
            while (networkInterfaces.hasMoreElements()) {
                val networkInterface = networkInterfaces.nextElement()
                val name = networkInterface.name

                if (networkInterface.isUp && name != null &&
                    (name.startsWith("tun") || name.startsWith("pptp") || name.startsWith("l2tp"))
                ) {
                    return true
                }
            }
        } catch (e: SocketException) {
            Log.e(LOG_TAG, e.message, e)
        }
        return false
    }

    enum class PrivateDnsMode {
        NONE,  // The setting is "Off" or "Opportunistic", and the DNS connection is not using TLS.
        UPGRADED,  // The setting is "Opportunistic", and the DNS connection has upgraded to TLS.
        STRICT // The setting is "Strict".
    }

}
