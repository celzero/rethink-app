package com.celzero.bravedns.ui


import android.app.Activity
import android.content.*
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.Build.VERSION
import android.os.Build.VERSION_CODES
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.celzero.bravedns.R
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.*
import com.celzero.bravedns.util.ApkUtilities
import com.celzero.bravedns.util.ApkUtilities.Companion.isServiceRunning
import java.io.File
import java.net.NetworkInterface
import java.net.SocketException


class HomeScreenFragment : Fragment(){


    //lateinit var vpnOnOffTxt : TextView
    lateinit var dnsOnOffImg : ImageView
    lateinit var dnsOnOffTxt : TextView
    lateinit var appManagerLL : LinearLayout
    private lateinit var permManagerLL : LinearLayout
    private lateinit var queryViewerLL : LinearLayout
    private lateinit var firewallLL : LinearLayout
    private lateinit var noOfAppsTV : TextView
    private lateinit var  memorySpaceTV : TextView

    private lateinit var recentQuery1TV : TextView
    private lateinit var recentQuery2TV : TextView
    private lateinit var recentQuery3TV : TextView
    private lateinit var recentQuery4TV : TextView
    //private lateinit var statusTextTV : TextView
    //private lateinit var explainationTextTV  : TextView
    private lateinit var protectionLevelTxt : TextView

    //Removed code for VPN
    private var isServiceRunning : Boolean = false

    private var REQUEST_CODE_PREPARE_VPN : Int = 100

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {

        val view: View = inflater.inflate(R.layout.fragment_home_screen,container,false)
        //contextVal = context!!
        dnsOnOffImg = view.findViewById(R.id.fhs_dns_on_off_img)
        dnsOnOffTxt = view.findViewById(R.id.fhs_dns_on_off_txt)

        //vpnOnOffTxt = view.findViewById(R.id.fhs_vpn_on_off_txt)
        appManagerLL = view.findViewById(R.id.fhs_ll_app_mgr)
        permManagerLL = view.findViewById(R.id.fhs_ll_perm_mgr)
        queryViewerLL = view.findViewById(R.id.fhs_ll_query)
        firewallLL = view.findViewById(R.id.fhs_ll_firewall)
        noOfAppsTV = view.findViewById(R.id.tv_app_installed)
        memorySpaceTV = view.findViewById(R.id.memory_space_tv)

        recentQuery1TV = view.findViewById(R.id.recent_queries_1_tv)
        recentQuery2TV = view.findViewById(R.id.recent_queries_2_tv)
        recentQuery3TV = view.findViewById(R.id.recent_queries_3_tv)
        recentQuery4TV = view.findViewById(R.id.recent_queries_4_tv)

        //statusTextTV = view.findViewById(R.id.brave_status_text_tv)
        //explainationTextTV = view.findViewById(R.id.brave_status_exp_text_tv)

        protectionLevelTxt = view.findViewById(R.id.fhs_protection_level_txt)

        isServiceRunning = isServiceRunning(requireContext(),BraveVPNService::class.java)
        //Removed code for VPN
        if(isServiceRunning){
            dnsOnOffTxt.setText("On")
            dnsOnOffImg.setImageDrawable(resources.getDrawable(R.drawable.ic_dns_on))
            dnsOnOffTxt.setTextColor(resources.getColor(R.color.colorGreen_900))
          /*  vpnOnOffTxt.setText("ON")
            vpnOnOffTxt.setTextColor( resources.getColor(R.color.colorGreen_900))*/
        }else{
            dnsOnOffTxt.setText("Off")
            dnsOnOffImg.setImageDrawable(resources.getDrawable(R.drawable.ic_dns_off))
            dnsOnOffTxt.setTextColor(resources.getColor(R.color.colorRed_900))

            /*vpnOnOffTxt.setText("OFF")
            vpnOnOffTxt.setTextColor( resources.getColor(R.color.colorRed_900))*/
        }

        //TODO: Organize the onClick listeners
        //TODO : Look into options for text and text color change.
        dnsOnOffImg.setOnClickListener(View.OnClickListener {
            //TODO : check for the service already running

            //Removed code for VPN
            //startVpn()

            //isServiceRunning = isServiceRunning(requireContext(),BraveVPNService::class.java)
            if(VpnController.getInstance()!!.getState(context!!)!!.activationRequested){
                dnsOnOffTxt.setText("Off")
                dnsOnOffTxt.setTextColor( resources.getColor(R.color.colorRed_900))
                dnsOnOffImg.setImageDrawable(resources.getDrawable(R.drawable.ic_dns_off))
                stopDnsVpnService()
            }
            else {

                prepareAndStartDnsVpn()
            }
        })



        // Register broadcast receiver
        val intentFilter = IntentFilter(InternalNames.RESULT.name)
        intentFilter.addAction(InternalNames.DNS_STATUS.name)
        LocalBroadcastManager.getInstance(context!!).registerReceiver(messageReceiver, intentFilter)
        Log.i("BraveDNS","LocalBroadcastManager Registered")

        appManagerLL.setOnClickListener(View.OnClickListener {
            startAppManagerActivity()
        })

        permManagerLL.setOnClickListener(View.OnClickListener {
            startPermissionManagerActivity()
        })

        queryViewerLL.setOnClickListener(View.OnClickListener {
            startQueryListener()
        })

        firewallLL.setOnClickListener(View.OnClickListener{
            startFirewallActivity()
        })


        noOfAppsTV.setText(context!!.packageManager.getInstalledPackages(PackageManager.GET_META_DATA).size.toString()+" Apps\nInstalled")
        memorySpaceTV.setText("Available Storage \n"+getAvailableInternalMemorySize().toString()+ " GB")

        return view
    }


    fun updateView(){
        noOfAppsTV.setText(context!!.packageManager.getInstalledPackages(PackageManager.GET_META_DATA).size.toString()+" Apps\n" + HomeScreenActivity.GlobalVariable.installedAppCount + " Installed")
    }

    private val messageReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.i("BraveDNS","Message Received : Broadcast")
            if (InternalNames.RESULT.name.equals(intent.action)) {
                updateStatsDisplay(getNumRequests(),intent.getSerializableExtra(InternalNames.TRANSACTION.name) as Transaction)

            } else if (InternalNames.DNS_STATUS.name.equals(intent.action)) {
                syncDnsStatus()
            }
        }
    }

    private fun updateStatsDisplay(numRequests: Long,transaction: Transaction) {
        QueryDetailActivity.updateStatsDisplay(numRequests,transaction)
        showTransaction(transaction)
    }

    private fun showTransaction(transaction: Transaction) {
        recentQuery4TV.text = recentQuery3TV.text
        recentQuery3TV.text = recentQuery2TV.text
        recentQuery2TV.text = recentQuery1TV.text
        recentQuery1TV.text = ApkUtilities.getETldPlus1(transaction.name)
        //recentQuery1TV.setCompoundDrawablesWithIntrinsicBounds(0,0,0,0)
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
        Log.i("BraveDNS","Memory Space : "+ path.freeSpace)
        /*val stat = StatFs(path.getPath())
        val blockSize = stat.blockSize.toLong()
        val availableBlocks = stat.availableBlocks.toLong()*/
        return (path.freeSpace)/(SIZE_GB)
    }


    private fun startQueryListener() {
        val intent = Intent(requireContext(), QueryDetailActivity::class.java)
        startActivity(intent)
    }

    private fun startFirewallActivity(){
        val intent = Intent(requireContext(), FirewallActivity::class.java)
        startActivity(intent)
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
        Log.e("BraveDNS", "stopDnsVpnService")
        VpnController.getInstance()!!.stop(context)
    }

    private fun startDnsVpnService() {
        //isServiceRunning = true
       /* vpnOnOffTxt.setText("ON")
        vpnOnOffTxt.setTextColor( resources.getColor(R.color.colorGreen_900))*/
        dnsOnOffTxt.setText("On")
        dnsOnOffTxt.setTextColor( resources.getColor(R.color.colorGreen_900))
        dnsOnOffImg.setImageDrawable(resources.getDrawable(R.drawable.ic_dns_on))
        Log.e("BraveDNS", "startDnsVpnService")
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

            Log.i("BraveVPN", "Prepare VPN with activity")
            startActivityForResult( prepareVpnIntent, REQUEST_CODE_PREPARE_VPN)
            //TODO - Check the below code
            syncDnsStatus() // Set DNS status to off in case the user does not grant VPN permissions
            return false
        }
        return true
    }


    private fun startAppManagerActivity() {
        val intent = Intent(requireContext(), ApplicationManagerActivity::class.java)
        startActivity(intent)
    }


    private fun startPermissionManagerActivity() {
        val intent = Intent(requireContext(), PermissionManagerActivity::class.java)
        startActivity(intent)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.e("BraveDNS","OnActivityResult - RequestCode: "+requestCode + " - ResultCode :"+resultCode)
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
            dnsOnOffTxt.setText("On")
            dnsOnOffTxt.setTextColor(resources.getColor(R.color.colorGreen_900))
            dnsOnOffImg.setImageDrawable(resources.getDrawable(R.drawable.ic_dns_on))
        }else {
            dnsOnOffTxt.setText("Off")
            dnsOnOffTxt.setTextColor(resources.getColor(R.color.colorRed_900))
            dnsOnOffImg.setImageDrawable(resources.getDrawable(R.drawable.ic_dns_off))
        }
        // Change status and explanation text
        val statusId: Int
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
        val colorId: Int
        colorId = if (status.on) {
            if (status.connectionState !== BraveVPNService.State.FAILING) R.color.positive else R.color.accent_bad
        } else if (privateDnsMode == PrivateDnsMode.STRICT) {
            // If the VPN is off but we're in strict mode, show the status in white.  This isn't a bad
            // state, but Intra isn't helping.
            R.color.indicator
        } else {
            R.color.accent_bad
        }

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