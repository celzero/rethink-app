package com.celzero.bravedns.ui


import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.icu.text.CompactDecimalFormat
import android.icu.text.DecimalFormat
import android.icu.text.NumberFormat
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Handler
import android.text.format.DateUtils
import android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE
import android.text.format.DateUtils.MINUTE_IN_MILLIS
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.*
import androidx.appcompat.widget.AppCompatSpinner
import androidx.appcompat.widget.AppCompatTextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProviders
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.SpinnerArrayAdapter
import com.celzero.bravedns.animation.RippleBackground
import com.celzero.bravedns.data.BraveMode
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.*
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appStartTime
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.dnsMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.firewallMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.medianP90
import com.celzero.bravedns.viewmodel.FirewallViewModel
import com.google.android.material.chip.Chip
import kotlinx.coroutines.InternalCoroutinesApi
import settings.Settings
import java.net.NetworkInterface
import java.net.SocketException
import java.util.*


class HomeScreenFragment : Fragment() {


    lateinit var appManagerLL: LinearLayout

    private lateinit var firewallLL: LinearLayout

    private lateinit var tileShowFirewallLL: LinearLayout
    private lateinit var tileShowDnsLL: LinearLayout
    private lateinit var tileShowDnsFirewallLL: LinearLayout

    private lateinit var noOfAppsTV: TextView

    private lateinit var braveModeSpinner: AppCompatSpinner
    private lateinit var braveModeInfoIcon: ImageView

    private lateinit var dnsOnOffBtn: AppCompatTextView
    private lateinit var rippleRRLayout: RippleBackground

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

    private lateinit var chipConfigureFirewall: Chip
    private lateinit var chipViewLogs: Chip

    private lateinit var protectionDescTxt: TextView
    private lateinit var protectionLevelTxt: TextView

    private val MAIN_CHANNEL_ID = "vpn"

    //Removed code for VPN
    private var isServiceRunning: Boolean = false

    private var REQUEST_CODE_PREPARE_VPN: Int = 100

    companion object {
        //private
        val DNS_MODE = 0
        val FIREWALL_MODE = 1
        val DNS_FIREWALL_MODE = 2
    }

    override fun onCreateView(inflater: LayoutInflater,container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view: View = inflater.inflate(R.layout.fragment_home_screen, container, false)
        initView(view)
        syncDnsStatus()
        initializeValues()
        initializeClickListeners()

        //Show Tile
        showTileForMode()
        updateTime()
        registerForBroadCastReceivers()

        return view
    }

    /*
        Registering the broadcast receiver for the DNS State and the DNS results returned
     */
    private fun registerForBroadCastReceivers() {
        // Register broadcast receiver
        val intentFilter = IntentFilter(InternalNames.RESULT.name)
        intentFilter.addAction(InternalNames.DNS_STATUS.name)
        LocalBroadcastManager.getInstance(context!!).registerReceiver(messageReceiver, intentFilter)
    }

    /*
        Assign initial values to the view and variables.
     */
    private fun initializeValues() {
        val braveModeList = getAllModes()
        val spinnerAdapter = SpinnerArrayAdapter(context!!, braveModeList)

        // Set layout to use when the list of choices appear
        // spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        // Set Adapter to Spinner
        braveModeSpinner.adapter = spinnerAdapter

        braveMode = PersistentState.getBraveMode(context!!)

        if (braveMode == -1) {
            braveModeSpinner.setSelection(DNS_FIREWALL_MODE)
        }
        else {
            braveModeSpinner.setSelection(braveMode)
        }



        HomeScreenActivity.GlobalVariable.lifeTimeQueries = PersistentState.getNumOfReq(context!!)

    }


    private fun initView(view: View) {
        //Complete UI component initialization for the view
        dnsOnOffBtn = view.findViewById(R.id.fhs_dns_on_off_btn)

        rippleRRLayout = view.findViewById(R.id.content)

        appManagerLL = view.findViewById(R.id.fhs_ll_app_mgr)

        firewallLL = view.findViewById(R.id.fhs_ll_firewall)
        noOfAppsTV = view.findViewById(R.id.tv_app_installed)

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
            }
        }

        // Brave Mode Information Icon which shows the dialog - explanation
        braveModeInfoIcon.setOnClickListener {
            showDialogForBraveModeInfo()
        }

        //Chips for the Configure Firewall
        chipConfigureFirewall.setOnClickListener {
            startFirewallActivity()
        }

        //Chips for the View Logs
        chipViewLogs.setOnClickListener {
            startQueryListener()
        }

        // Connect/Diconnect button ==> TODO : Change the label to Start and Stop
        dnsOnOffBtn.setOnClickListener(View.OnClickListener {
            //TODO : check for the service already running
            if (VpnController.getInstance()!!.getState(context!!)!!.activationRequested) {
                appStartTime = System.currentTimeMillis()
                dnsOnOffBtn.setText("start")
                rippleRRLayout.startRippleAnimation()
                protectionDescTxt.setText(getString(R.string.dns_explanation_disconnected))
                stopDnsVpnService()
            } else {
                appStartTime = System.currentTimeMillis()
                prepareAndStartDnsVpn()
            }
        })

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

    private fun updateBuilder() {
        val mainActivityIntent = PendingIntent.getActivity(
            context,0,Intent(context, HomeScreenActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT)

        val name: CharSequence = context!!.resources.getString(R.string.app_name_brave)
        val description = context!!.resources.getString(R.string.notification_content)
        val importance = NotificationManager.IMPORTANCE_LOW
        val notificationManager = context!!.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(MAIN_CHANNEL_ID, name, importance)
        channel.description = description
        notificationManager.createNotificationChannel(channel)
        val builder: Notification.Builder = Notification.Builder(context, MAIN_CHANNEL_ID)

        var contentTitle: String = if (braveMode == 0)
            context!!.resources.getString(R.string.dns_mode_notification_title)
        else if (braveMode == 1)
            context!!.resources.getString(R.string.firewall_mode_notification_title)
        else if (braveMode == 2)
            context!!.resources.getString(R.string.hybrid_mode_notification_title)
        else
            context!!.resources.getString(R.string.notification_title)

        //TODO - Update the values with observer in the Notification using the below setContentText method
        builder.setSmallIcon(R.drawable.dns_icon)
            .setContentTitle(contentTitle)
            //.setContentText(resources.getText(R.string.notification_content))
            .setContentIntent(mainActivityIntent)

        // Secret notifications are not shown on the lock screen.  No need for this app to show there.
        // Only available in API >= 21
        builder.setVisibility(Notification.VISIBILITY_SECRET)
        notificationManager!!.notify(BraveVPNService.SERVICE_ID, builder.build())
    }

    /*
        Start the querylistener activity for the DNS logs.
     */

    @OptIn(InternalCoroutinesApi::class)
    private fun startQueryListener() {
        val status: VpnState? = VpnController.getInstance()!!.getState(context!!)
        if (status!!.activationRequested) {
            if (braveMode == DNS_FIREWALL_MODE || braveMode == DNS_MODE) {
                val intent = Intent(requireContext(), QueryDetailActivity::class.java)
                startActivity(intent)
            } else {
                Toast.makeText(context!!,resources.getText(R.string.brave_dns_connect_mode_change_dns).toString().capitalize(), Toast.LENGTH_SHORT).show()
            }
        }else {
            Toast.makeText(context!!, resources.getText(R.string.brave_dns_connect_prompt_query).toString().capitalize(), Toast.LENGTH_SHORT).show()
        }
    }


    override fun onResume() {
        super.onResume()
        syncDnsStatus()
    }


    /*
        Start the Firewall activity from the chip
     */
    private fun startFirewallActivity() {
        val status: VpnState? = VpnController.getInstance()!!.getState(context!!)
        if ( status!!.activationRequested) {
            if (braveMode == DNS_FIREWALL_MODE || braveMode == FIREWALL_MODE) {
                val intent = Intent(requireContext(), FirewallActivity::class.java)
                startActivity(intent)
            } else {
                Toast.makeText(context!!, resources.getText(R.string.brave_dns_connect_mode_change_firewall).toString().capitalize(), Toast.LENGTH_SHORT).show()
            }
        }else {
            Toast.makeText(context!!, resources.getText(R.string.brave_dns_connect_prompt_firewall).toString().capitalize(), Toast.LENGTH_SHORT).show()
        }
    }

    //TODO -- Modify the logic for the below.
    private fun modifyBraveMode(position: Int) {
        if (position == 0) {
            dnsMode = Settings.DNSModePort.toInt()
            firewallMode = Settings.BlockModeNone.toInt()
            braveMode = DNS_MODE
        } else if (position == 1) {
            dnsMode = Settings.DNSModePort.toInt()
            firewallMode = if (VERSION.SDK_INT >= VERSION_CODES.O && VERSION.SDK_INT < VERSION_CODES.Q)
                Settings.BlockModeFilterProc.toInt()
            else
                Settings.BlockModeFilter.toInt()
            braveMode = FIREWALL_MODE
        } else if (position == 2) {
            firewallMode = if(VERSION.SDK_INT >= VERSION_CODES.O && VERSION.SDK_INT < VERSION_CODES.Q)
                Settings.BlockModeFilterProc.toInt()
            else
                Settings.BlockModeFilter.toInt()
            dnsMode = Settings.DNSModePort.toInt()
            braveMode = DNS_FIREWALL_MODE
        }

        PersistentState.setDnsMode(context!!, dnsMode)
        PersistentState.setFirewallMode(context!!, firewallMode)
        PersistentState.setBraveMode(context!!, braveMode)

        if (VpnController.getInstance()!!.getState(context!!)!!.activationRequested) {
            updateBuilder()
            if (braveMode == 0)
                protectionDescTxt.setText("connected to bravedns")
            else if (braveMode == 1)
                protectionDescTxt.setText("connected to firewall")
            else
                protectionDescTxt.setText(getString(R.string.dns_explanation_connected))
        }

        braveModeSpinner.isEnabled = true
    }

    /*
        Show the Info dialog for various modes in Brave
     */
    private fun showDialogForBraveModeInfo() {

        val dialog = Dialog(context!!)
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

    /*
        TODO : The data has been hardcoded need to modify
     */
    @SuppressLint("ResourceType")
    private fun getAllModes(): ArrayList<BraveMode> {
        val braveNames = resources.getStringArray(R.array.brave_dns_mode_names)
        val icons = resources.obtainTypedArray(R.array.brave_dns_mode_icons)
        var braveList = ArrayList<BraveMode>(3)
        var braveModes = BraveMode(icons.getResourceId(0, -1), 0, braveNames[0])
        braveList.add(braveModes)
        braveModes = BraveMode(icons.getResourceId(1, -1), 1, braveNames[1])
        braveList.add(braveModes)
        braveModes = BraveMode(icons.getResourceId(2, -1), 2, braveNames[2])
        braveList.add(braveModes)
        icons.recycle()
        return braveList
    }

    //TODO - Remove the below code and modify it to LiveData
    var updater: Runnable? = null
    val timerHandler = Handler()

    fun updateTime() {
        updater = Runnable {
            val numReq = PersistentState.getNumOfReq(context!!).toDouble()
            val blockedReq = PersistentState.getBlockedReq(context!!).toDouble()

            HomeScreenActivity.GlobalVariable.lifeTimeQueries =
                PersistentState.getNumOfReq(context!!)
            medianP90 = PersistentState.getMedianLatency(context!!)

            tileDLifetimeQueriesTxt.setText((HomeScreenActivity.GlobalVariable.lifeTimeQueries).toString())
            tileDmedianTxt.setText(medianP90.toString() + "ms")

            val blocked = CompactDecimalFormat.getInstance(Locale.US, CompactDecimalFormat.CompactStyle.SHORT).format(blockedReq)

            tileDFTrackersBlockedtxt.setText(blocked)
            tileDtrackersBlockedTxt.setText(blocked)

            tileFAppsBlockedTxt.setText(PersistentState.getExcludedPackagesWifi(context!!)!!.size.toString())

            tileFCategoryBlockedTxt.setText(PersistentState.getCategoriesBlocked(context!!)!!.size.toString())
            var numUniversalBlock = 0
            if (PersistentState.getBackgroundEnabled(context!!)) {
                numUniversalBlock += 1
            }
            if (PersistentState.getFirewallModeForScreenState(context!!)) {
                numUniversalBlock += 1
            }
            tileFUniversalBlockedTxt.setText(numUniversalBlock.toString())

            tileDFAppsBlockedTxt.setText(PersistentState.getExcludedPackagesWifi(context!!)!!.size.toString())
            tileDFMedianTxt.setText(medianP90.toString() + "ms")

            updateUptime()
            timerHandler.postDelayed(updater, 1500)

        }
        timerHandler.post(updater!!)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timerHandler.removeCallbacks(updater!!);
    }


    private val messageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (InternalNames.RESULT.name.equals(intent.action)) {
                updateStatsDisplay(
                    PersistentState.getNumOfReq(context).toLong(),
                    intent.getSerializableExtra(InternalNames.TRANSACTION.name) as Transaction
                )

            } else if (InternalNames.DNS_STATUS.name.equals(intent.action)) {
                syncDnsStatus()
            }
        }
    }

    private fun updateStatsDisplay(numRequests: Long, transaction: Transaction) {
        QueryDetailActivity.updateStatsDisplay(numRequests, transaction)
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(context!!).unregisterReceiver(messageReceiver)
        super.onDestroy()
    }

    private fun updateUptime() {
        val upTime = DateUtils.getRelativeTimeSpanString(appStartTime, System.currentTimeMillis(), MINUTE_IN_MILLIS, FORMAT_ABBREV_RELATIVE)
        appUpTimeTxt.setText("(" + upTime + ")")
    }


    private fun prepareAndStartDnsVpn() {
        if (hasVpnService()) {
            if (prepareVpnService()) {
                startDnsVpnService()
            }
        } else {
            Log.e("BraveVPN", "Device does not support system-wide VPN mode.")
        }
    }

    private fun stopDnsVpnService() {
        VpnController.getInstance()!!.stop(context)
    }

    private fun startDnsVpnService() {
        dnsOnOffBtn.setText("stop")
        rippleRRLayout.stopRippleAnimation()
        VpnController.getInstance()?.start(context!!)
        if (braveMode == 0)
            protectionDescTxt.setText("connected to bravedns")
        else if (braveMode == 1)
            protectionDescTxt.setText("connected to firewall")
        else
            protectionDescTxt.setText(getString(R.string.dns_explanation_connected))
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

        var status = VpnController.getInstance()!!.getState(context!!)

        if (status!!.activationRequested || status.connectionState === BraveVPNService.State.WORKING) {
            rippleRRLayout.stopRippleAnimation()
            dnsOnOffBtn.text = "stop"
            if (braveMode == 0)
                protectionDescTxt.setText("connected to bravedns")
            else if (braveMode == 1)
                protectionDescTxt.setText("connected to firewall")
            else
                protectionDescTxt.setText(getString(R.string.dns_explanation_connected))
        } else {
            rippleRRLayout.startRippleAnimation()
            dnsOnOffBtn.text = "start"
            protectionDescTxt.setText(getString(R.string.dns_explanation_disconnected))
        }

        // Change status and explanation text
        var statusId: Int = R.string.status_exposed
        val explanationId: Int
        var privateDnsMode: PrivateDnsMode = PrivateDnsMode.NONE
        if (status!!.activationRequested) {
            if (status.connectionState == null) {
                if (firewallMode == 2) {
                    statusId = R.string.status_protected
                    explanationId = R.string.explanation_protected
                } else {
                    //prepareAndStartDnsVpn()
                    statusId = R.string.status_waiting
                    explanationId = R.string.explanation_offline
                }
            } else if (status.connectionState === BraveVPNService.State.NEW) {
                statusId = R.string.status_starting
                explanationId = R.string.explanation_starting
            } else if (status.connectionState === BraveVPNService.State.WORKING) {
                statusId = R.string.status_protected
                explanationId = R.string.explanation_protected
            } else {
                // status.connectionState == ServerConnection.State.FAILING
                statusId = R.string.status_failing
                explanationId = R.string.explanation_failing
                //changeServerButton.visibility = View.VISIBLE
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
        if (braveMode == FIREWALL_MODE && VpnController.getInstance()!!.getState(context!!)!!.activationRequested)
            colorId = R.color.positive

        val color = ContextCompat.getColor(context!!, colorId)
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
            context!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
                context!!.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
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
            Log.e("BraveDNS", e.message,e)
        }
        return false
    }

    enum class PrivateDnsMode {
        NONE,  // The setting is "Off" or "Opportunistic", and the DNS connection is not using TLS.
        UPGRADED,  // The setting is "Opportunistic", and the DNS connection has upgraded to TLS.
        STRICT // The setting is "Strict".
    }


}