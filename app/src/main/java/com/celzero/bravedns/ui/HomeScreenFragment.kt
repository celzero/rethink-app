package com.celzero.bravedns.ui


import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.*
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Environment
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
import com.google.android.material.chip.Chip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import settings.Settings
import java.io.File
import java.net.NetworkInterface
import java.net.SocketException
import java.util.*


class HomeScreenFragment : Fragment(){



    lateinit var appManagerLL : LinearLayout
    //private lateinit var permManagerLL : LinearLayout
    //private lateinit var queryViewerLL : LinearLayout
    private lateinit var firewallLL : LinearLayout

    private lateinit var tileShowFirewallLL : LinearLayout
    private lateinit var tileShowDnsLL : LinearLayout
    private lateinit var tileShowDnsFirewallLL : LinearLayout

    private lateinit var noOfAppsTV : TextView

    private lateinit var braveModeSpinner : AppCompatSpinner
    private lateinit var braveModeInfoIcon : ImageView

    //private lateinit var  memorySpaceTV : TextView
    private lateinit var dnsOnOffBtn : AppCompatTextView
    private lateinit var  rippleRRLayout : RippleBackground

    private lateinit var tileDLifetimeQueriesTxt : TextView
    private lateinit var tileDmedianTxt : TextView
    private lateinit var tileDtrackersBlockedTxt : TextView

    private lateinit var tileFAppsBlockedTxt : TextView
    private lateinit var tileFCategoryBlockedTxt : TextView
    private lateinit var tileFUniversalBlockedTxt : TextView

    private lateinit var tileDFAppsBlockedTxt : TextView
    private lateinit var tileDFMedianTxt : TextView
    private lateinit var tileDFTrackersBlockedtxt : TextView

    private lateinit var  appUpTimeTxt : TextView

    private lateinit var chipConfigureFirewall : Chip
    private lateinit var chipViewLogs : Chip

    //private var lifeTimeQueries  : Long = 0
    //private lateinit var statusTextTV : TextView
    private lateinit var protectionDescTxt  : TextView
    private lateinit var protectionLevelTxt : TextView

    //Removed code for VPN
    private var isServiceRunning : Boolean = false

    private var REQUEST_CODE_PREPARE_VPN : Int = 100

    companion object {
        //private
        val DNS_MODE = 0
        val FIREWALL_MODE = 1
        val DNS_FIREWALL_MODE = 2
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        //Log.w("BraveDNS","onCreateView ")
        val view: View = inflater.inflate(R.layout.fragment_home_screen,container,false)
        //contextVal = context!!
        initView(view)

        return view
    }

    private fun initView(view: View){
        dnsOnOffBtn = view.findViewById(R.id.fhs_dns_on_off_btn)

        rippleRRLayout = view.findViewById(R.id.content)

        //vpnOnOffTxt = view.findViewById(R.id.fhs_vpn_on_off_txt)
        appManagerLL = view.findViewById(R.id.fhs_ll_app_mgr)
        //permManagerLL = view.findViewById(R.id.fhs_ll_perm_mgr)
        //queryViewerLL = view.findViewById(R.id.fhs_ll_query)
        firewallLL = view.findViewById(R.id.fhs_ll_firewall)
        noOfAppsTV = view.findViewById(R.id.tv_app_installed)
        //memorySpaceTV = view.findViewById(R.id.memory_space_tv)


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
        protectionDescTxt  = view.findViewById(R.id.fhs_app_connected_desc)

        //Spinner Adapter code
        braveModeSpinner = view.findViewById(R.id.fhs_brave_mode_spinner)
        braveModeInfoIcon = view.findViewById(R.id.fhs_dns_mode_info)

        val braveModeList = getAllModes()
        val spinnerAdapter = SpinnerArrayAdapter(context!!, braveModeList)
        // Set layout to use when the list of choices appear
        // spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        // Set Adapter to Spinner
        braveModeSpinner.adapter = spinnerAdapter

        braveMode = PersistentState.getBraveMode(context!!)


        //Show Tile
       showTileForMode()

        braveModeSpinner.setSelection(braveMode)

        braveModeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener{
            override fun onNothingSelected(parent: AdapterView<*>?) {
                braveMode = DNS_MODE
            }
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                /*if(position == 1)
                    return*/
                braveModeSpinner.isEnabled = false
                if(position == 2)
                    braveMode = if(VERSION.SDK_INT < VERSION_CODES.Q){
                        Toast.makeText(context, R.string.brave_mode_not_supported,Toast.LENGTH_LONG).show()
                        parent!!.setSelection(0)
                        0
                    } else
                        position
                modifyBraveMode(position)
                showTileForMode()
            }
        }

        braveModeInfoIcon.setOnClickListener{
            showDialogForBraveModeInfo()
        }

       /* //TODO : Check this below isServiceRunning method and do the changes.
        isServiceRunning = isServiceRunning(requireContext(),BraveVPNService::class.java)
        //Removed code for VPN
        if(!isServiceRunning){
            dnsOnOffBtn.text = "Connect"
            rippleRRLayout.startRippleAnimation()
            protectionDescTxt.setText(getString(R.string.dns_explanation_disconnected))

        }else{
            dnsOnOffBtn.text = "Disconnect"
            rippleRRLayout.stopRippleAnimation()
            protectionDescTxt.setText(getString(R.string.dns_explanation_connected))

        }*/

        syncDnsStatus()

        //TODO: Organize the onClick listeners
        //TODO : Look into options for text and text color change.
        dnsOnOffBtn.setOnClickListener(View.OnClickListener {
            //TODO : check for the service already running
            if(VpnController.getInstance()!!.getState(context!!)!!.activationRequested){
                dnsOnOffBtn.setText("Connect")
                rippleRRLayout.startRippleAnimation()
                protectionDescTxt.setText(getString(R.string.dns_explanation_disconnected))
                stopDnsVpnService()
            }
            else {
                appStartTime = System.currentTimeMillis()
                prepareAndStartDnsVpn()
            }
        })

        // Register broadcast receiver
        val intentFilter = IntentFilter(InternalNames.RESULT.name)
        intentFilter.addAction(InternalNames.DNS_STATUS.name)
        LocalBroadcastManager.getInstance(context!!).registerReceiver(messageReceiver, intentFilter)
        //Log.i("BraveDNS","LocalBroadcastManager Registered")

        //TODO : Modify the shared pref and migrate it to PersistentState class
        // Restore number of requests from storage, or 0 if it isn't defined yet.
        /*val settings = context!!.getSharedPreferences(
            QueryTracker::class.java.simpleName,
            Context.MODE_PRIVATE
        )*/
        HomeScreenActivity.GlobalVariable.lifeTimeQueries = PersistentState.getNumOfReq(context!!)

        if(HomeScreenActivity.GlobalVariable.installedAppCount == 0)
            noOfAppsTV.setText(context!!.packageManager.getInstalledPackages(PackageManager.GET_META_DATA).size.toString()+" Apps\n" + HomeScreenActivity.GlobalVariable.installedAppCount + " Installed")
        else
            noOfAppsTV.setText(context!!.packageManager.getInstalledPackages(PackageManager.GET_META_DATA).size.toString()+" Apps\nInstalled")
        //memorySpaceTV.setText("Available Storage \n"+getAvailableInternalMemorySize().toString()+ " GB")

        //For Chip
        chipConfigureFirewall = view.findViewById(R.id.chip_configure_firewall)
        chipViewLogs = view.findViewById(R.id.chip_view_logs)

        chipConfigureFirewall.setOnClickListener{
            startFirewallActivity()
        }
        chipViewLogs.setOnClickListener{
            startQueryListener()
        }

        updateTime()
    }

    private fun showTileForMode() {
        if(braveMode == DNS_MODE){
            tileShowDnsLL.visibility = View.VISIBLE
            tileShowFirewallLL.visibility = View.GONE
            tileShowDnsFirewallLL.visibility = View.GONE
        }else if(braveMode == FIREWALL_MODE){
            tileShowDnsLL.visibility = View.GONE
            tileShowFirewallLL.visibility = View.VISIBLE
            tileShowDnsFirewallLL.visibility = View.GONE
        }else if(braveMode == DNS_FIREWALL_MODE){
            tileShowDnsLL.visibility = View.GONE
            tileShowFirewallLL.visibility = View.GONE
            tileShowDnsFirewallLL.visibility = View.VISIBLE
        }

        braveModeSpinner.isEnabled = true
    }

    private fun startQueryListener() {
        val status: VpnState? = VpnController.getInstance()!!.getState(context!!)
        if((status!!.activationRequested)) {
            if(braveMode == DNS_FIREWALL_MODE || braveMode == DNS_MODE) {
                val intent = Intent(requireContext(), QueryDetailActivity::class.java)
                startActivity(intent)
            }else{
                Toast.makeText(context!!, resources.getText(R.string.brave_dns_connect_mode_change_dns).toString().capitalize(),Toast.LENGTH_SHORT ).show()
            }
        }else{
            Toast.makeText(context!!, resources.getText(R.string.brave_dns_connect_prompt_query).toString().capitalize(),Toast.LENGTH_SHORT ).show()
        }
        /*val intent = Intent(requireContext(), QueryDetailActivity::class.java)
        startActivity(intent)*/
    }

    private fun startFirewallActivity(){
        val status: VpnState? = VpnController.getInstance()!!.getState(context!!)
        if((status!!.activationRequested) ) {
            if(braveMode == DNS_FIREWALL_MODE || braveMode == FIREWALL_MODE) {
                val intent = Intent(requireContext(), FirewallActivity::class.java)
                startActivity(intent)
            }else{
                Toast.makeText(context!!, resources.getText(R.string.brave_dns_connect_mode_change_firewall).toString().capitalize(),Toast.LENGTH_SHORT ).show()
            }
        }else{
            Toast.makeText(context!!, resources.getText(R.string.brave_dns_connect_prompt_firewall).toString().capitalize(),Toast.LENGTH_SHORT ).show()
        }
        /*val intent = Intent(requireContext(), FirewallActivity::class.java)
        startActivity(intent)*/
    }


    private fun modifyBraveMode(position : Int) {
        //Log.d("BraveVPN","modifyBraveMode - Position: "+position)
        if(position == 0){
            dnsMode = Settings.DNSModePort.toInt()
            firewallMode = Settings.BlockModeNone.toInt()
            braveMode = DNS_MODE
        }else if(position == 1){
            dnsMode = Settings.BlockModeFilter.toInt()
            firewallMode = Settings.BlockModeSink.toInt()
            braveMode = FIREWALL_MODE
        }else if(position == 2){
            dnsMode = Settings.DNSModePort.toInt()
            firewallMode = Settings.BlockModeFilter.toInt()
            braveMode = DNS_FIREWALL_MODE
        }


            PersistentState.setDnsMode(context!!, dnsMode)
            PersistentState.setFirewallMode(context!!, firewallMode)
            PersistentState.setBraveMode(context!!, braveMode)



        if(VpnController.getInstance()!!.getState(context!!)!!.activationRequested) {
            if (braveMode == 0)
                protectionDescTxt.setText("connected to bravedns")
            else if (braveMode == 1)
                protectionDescTxt.setText("connected to firewall")
            else
                protectionDescTxt.setText(getString(R.string.dns_explanation_connected))
        }

        braveModeSpinner.isEnabled = true
    }

    private fun showDialogForBraveModeInfo() {
      /*  val builderSingle: AlertDialog.Builder = AlertDialog.Builder(FirewallHeader.context)
        builderSingle.setIcon(R.drawable.ic_launcher)
        builderSingle.setTitle("Brave DNS Modes")
        builderSingle.show()
*/
        val dialog = Dialog(context!!)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle("Brave DNS Modes")
        dialog.setCanceledOnTouchOutside(true)
        //dialog.setCancelable(false)
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
        var braveList  = ArrayList<BraveMode>(3)
        var braveMode = BraveMode(icons.getResourceId(0,-1), 0, braveNames[0])
        braveList.add(braveMode)
        braveMode = BraveMode(icons.getResourceId(1,-1), 1, braveNames[1])
        braveList.add(braveMode)
        braveMode = BraveMode(icons.getResourceId(2,-1), 2, braveNames[2])
        braveList.add(braveMode)
        icons.recycle()
        return  braveList
    }

    var updater: Runnable? = null
    val timerHandler = Handler()
    fun updateTime() {
        updater = Runnable {
            val numReq = PersistentState.getNumOfReq(context!!).toDouble()
            val blockedReq = PersistentState.getBlockedReq(context!!).toDouble()
            var percentage : Double = 0.0
            if(numReq > blockedReq) {
                //Log.d("BraveDNS","Blocked : "+blockedReq + " - num : "+numReq)
                percentage = (blockedReq / numReq) * 100
            }
            HomeScreenActivity.GlobalVariable.lifeTimeQueries = PersistentState.getNumOfReq(context!!)
            medianP90 = PersistentState.getMedianLatency(context!!)

            tileDLifetimeQueriesTxt.setText((HomeScreenActivity.GlobalVariable.lifeTimeQueries).toString())
            tileDmedianTxt.setText(medianP90.toString() + "ms")
            tileDtrackersBlockedTxt.setText(percentage.toInt().toString() +"%")

            tileFAppsBlockedTxt.setText(PersistentState.getExcludedPackagesWifi(context!!)!!.size.toString())
            tileFCategoryBlockedTxt.setText(PersistentState.getCategoriesBlocked(context!!)!!.size.toString())
            var numUniversalBlock : Int = 0
            if(PersistentState.getBackgroundEnabled(context!!))
                numUniversalBlock =+1
            if(PersistentState.getFirewallModeForScreenState(context!!))
                numUniversalBlock =+1
            tileFUniversalBlockedTxt.setText(numUniversalBlock.toString())

            tileDFAppsBlockedTxt.setText(PersistentState.getExcludedPackagesWifi(context!!)!!.size.toString())
            tileDFMedianTxt.setText(medianP90.toString()+ "ms")
            tileDFTrackersBlockedtxt.setText(percentage.toInt().toString() +"%")

            updateUptime()
            timerHandler.postDelayed(updater, 1000)

        }
        timerHandler.post(updater)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        timerHandler.removeCallbacks(updater);
    }

    /* fun updateView(){
        noOfAppsTV.setText(context!!.packageManager.getInstalledPackages(PackageManager.GET_META_DATA).size.toString()+" Apps\n" + HomeScreenActivity.GlobalVariable.installedAppCount + " Installed")
    }*/

    private val messageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            //Log.i("BraveDNS","Message Received : Broadcast")
            if (InternalNames.RESULT.name.equals(intent.action)) {
                updateStatsDisplay(getNumRequests(),intent.getSerializableExtra(InternalNames.TRANSACTION.name) as Transaction)

            } else if (InternalNames.DNS_STATUS.name.equals(intent.action)) {
                syncDnsStatus()
            }
        }
    }

    private fun updateStatsDisplay(numRequests: Long,transaction: Transaction) {
        HomeScreenActivity.GlobalVariable.lifeTimeQueries = numRequests.toInt()
        QueryDetailActivity.updateStatsDisplay(numRequests,transaction)
        //showTransaction(transaction)
    }

    private fun getNumRequests(): Long {
        val controller: VpnController ?= VpnController.getInstance()
        return controller!!.getTracker(context)!!.getNumRequests()
    }

    override fun onDestroy() {
        LocalBroadcastManager.getInstance(context!!).unregisterReceiver(messageReceiver)
        super.onDestroy()
    }

    fun getAvailableInternalMemorySize(): Long {
        val SIZE_KB = 1000L
        val SIZE_GB = SIZE_KB * SIZE_KB * SIZE_KB
        val path: File = Environment.getDataDirectory()
        return (path.freeSpace)/(SIZE_GB)
    }


    private fun updateUptime() {

        // Get the whole uptime
        //val uptimeMillis: Long =  SystemClock.currentThreadTimeMillis() - appStartTime
        val upTime = DateUtils.getRelativeTimeSpanString( appStartTime, System.currentTimeMillis(),MINUTE_IN_MILLIS,FORMAT_ABBREV_RELATIVE)
        /*val wholeUptime: String = String.format(
            Locale.getDefault(),
            "%02d:%02d:%02d",
            TimeUnit.MILLISECONDS.toHours(uptimeMillis),
            TimeUnit.MILLISECONDS.toMinutes(uptimeMillis)
                    - TimeUnit.HOURS.toMinutes(
                TimeUnit.MILLISECONDS
                    .toHours(uptimeMillis)
            ),
            TimeUnit.MILLISECONDS.toSeconds(uptimeMillis)
                    - TimeUnit.MINUTES.toSeconds(
                TimeUnit.MILLISECONDS
                    .toMinutes(uptimeMillis)
            )
        )*/
        appUpTimeTxt.setText("("+upTime+")")
    }



    private fun prepareAndStartDnsVpn() {

        if (hasVpnService()) {
            if (prepareVpnService()) {
                startDnsVpnService()
            }
        } else {
            Log.e("BraveVPN","Device does not support system-wide VPN mode.")
        }
    }

    private fun stopDnsVpnService() {
        VpnController.getInstance()!!.stop(context)
    }

    private fun startDnsVpnService() {
        //isServiceRunning = true
       /* vpnOnOffTxt.setText("ON")
        vpnOnOffTxt.setTextColor( resources.getColor(R.color.colorGreen_900))*/
        dnsOnOffBtn.setText("Disconnect")
        rippleRRLayout.stopRippleAnimation()
        if(braveMode == 0)
            protectionDescTxt.setText("connected to bravedns")
        else if(braveMode == 1)
            protectionDescTxt.setText("connected to firewall")
        else
            protectionDescTxt.setText(getString(R.string.dns_explanation_connected))
        //var vpnController=VpnController()
        VpnController.getInstance()?.start(context!!)
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
            Log.e("BraveVPN","Device does not support system-wide VPN mode.")
            return false
        }
        if (prepareVpnIntent != null) {
            startActivityForResult( prepareVpnIntent, REQUEST_CODE_PREPARE_VPN)
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
        }else{
            stopDnsVpnService()
        }
    }


    // Sets the UI DNS status on/off.
    private fun syncDnsStatus() {

        val status: VpnState? = VpnController.getInstance()!!.getState(context!!)

        // Change indicator text
        //vpnOnOffTxt.setText(if (status!!.activationRequested) "On" else "Off")

        if(status!!.activationRequested) {
            rippleRRLayout.stopRippleAnimation()
            dnsOnOffBtn.text = "Disconnect"
            if(braveMode == 0)
                protectionDescTxt.setText("connected to bravedns")
            else if(braveMode == 1)
                protectionDescTxt.setText("connected to firewall")
            else
                protectionDescTxt.setText(getString(R.string.dns_explanation_connected))

        }else {
            rippleRRLayout.startRippleAnimation()
            dnsOnOffBtn.text = "Connect"
            protectionDescTxt.setText(getString(R.string.dns_explanation_disconnected))
        }
        // Change status and explanation text
        val statusId: Int
        val explanationId: Int
        var privateDnsMode: PrivateDnsMode = PrivateDnsMode.NONE
        if (status.activationRequested) {
            if (status.connectionState == null) {
                if(firewallMode == 2){
                    statusId = R.string.status_protected
                    explanationId = R.string.explanation_protected
                }else {
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
                statusId = R.string.status_upgraded
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
        if(braveMode == FIREWALL_MODE && VpnController.getInstance()!!.getState(context!!)!!.activationRequested)
            colorId = R.color.positive
        //val statusText: TextView = controlView.findViewById(R.id.status)
        //val explanationText: TextView = controlView.findViewById(R.id.explanation)
        val color = ContextCompat.getColor(context!!, colorId)
        protectionLevelTxt.setTextColor(color)
        protectionLevelTxt.setText(statusId)
        //explainationTextTV.setText(explanationId)

    }

    fun getPrivateDnsMode(): PrivateDnsMode {
        if (VERSION.SDK_INT < VERSION_CODES.P) {
            // Private DNS was introduced in P.
            return PrivateDnsMode.NONE
        }
        val linkProperties: LinkProperties = getLinkProperties()
            ?: return  PrivateDnsMode.NONE
        if (linkProperties.privateDnsServerName != null) {
            return  PrivateDnsMode.STRICT
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
            Log.e("BraveDNS",e.message)
        }
        return false
    }
    enum class PrivateDnsMode {
        NONE,  // The setting is "Off" or "Opportunistic", and the DNS connection is not using TLS.
        UPGRADED,  // The setting is "Opportunistic", and the DNS connection has upgraded to TLS.
        STRICT // The setting is "Strict".
    }



}