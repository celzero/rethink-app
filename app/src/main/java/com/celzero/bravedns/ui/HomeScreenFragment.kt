package com.celzero.bravedns.ui

//Removed code for VPN
//import com.celzero.bravedns.service.DnsService
//import com.celzero.bravedns.service.DnsService.Companion.startVpn
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.celzero.bravedns.R
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.ApkUtilities.Companion.isServiceRunning

class HomeScreenFragment : Fragment(){


    lateinit var vpnOnOffTxt : TextView
    lateinit var appManagerLL : LinearLayout
    private lateinit var permManagerLL : LinearLayout
    private lateinit var queryViewerLL : LinearLayout
    //Removed code for VPN
    private var isServiceRunning : Boolean = false

    private var REQUEST_CODE : Int = 100

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {

        val view: View = inflater.inflate(R.layout.fragment_home_screen,container,false)
        //contextVal = context!!
        vpnOnOffTxt = view.findViewById(R.id.fhs_vpn_on_off_txt)
        appManagerLL = view.findViewById(R.id.fhs_ll_app_mgr)
        permManagerLL = view.findViewById(R.id.fhs_ll_perm_mgr)
        queryViewerLL = view.findViewById(R.id.fhs_ll_query)

        isServiceRunning = isServiceRunning(requireContext(),BraveVPNService::class.java)
        //Removed code for VPN
        if(isServiceRunning){
            vpnOnOffTxt.setText("ON")
            vpnOnOffTxt.setTextColor( resources.getColor(R.color.colorGreen_900))
        }else{
            vpnOnOffTxt.setText("OFF")
            vpnOnOffTxt.setTextColor( resources.getColor(R.color.colorRed_900))
        }

        //TODO: Organize the onClick listeners
        //TODO : Look into options for text and text color change.
        vpnOnOffTxt.setOnClickListener(View.OnClickListener {
            //TODO : check for the service already running

            //Removed code for VPN
            //startVpn()
            isServiceRunning = isServiceRunning(requireContext(),BraveVPNService::class.java)
            if(isServiceRunning){
                vpnOnOffTxt.setText("OFF")
                vpnOnOffTxt.setTextColor( resources.getColor(R.color.colorRed_900))
                stopDnsVpnService()
            }
            else {

                prepareAndStartDnsVpn()
            }
        })

        appManagerLL.setOnClickListener(View.OnClickListener {
            startAppManagerActivity()
        })

        permManagerLL.setOnClickListener(View.OnClickListener {
            startPermissionManagerActivity()
        })

        queryViewerLL.setOnClickListener(View.OnClickListener {
            startQueryListener()
        })

        return view
    }

    private fun startQueryListener() {
        val intent = Intent(requireContext(), QueryDetailActivity::class.java)
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
        vpnOnOffTxt.setText("ON")
        vpnOnOffTxt.setTextColor( resources.getColor(R.color.colorGreen_900))
        Log.e("BraveDNS", "startDnsVpnService")
        //var vpnController=VpnController()
        VpnController.getInstance()?.start(context!!)
    }

    // Returns whether the device supports the tunnel VPN service.
    private fun hasVpnService(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH
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
            startActivityForResult(
                prepareVpnIntent,
                REQUEST_CODE
            )
            //TODO
            //syncDnsStatus() // Set DNS status to off in case the user does not grant VPN permissions
            return false
        }
        return true
    }


    /*override fun onActivityResult(request: Int, result: Int, data: Intent?) {
        super.onActivityResult(request, result, data)
        if (request == REQUEST_CODE_PREPARE_VPN) {
            if (result == RESULT_OK) {
                startDnsVpnService()
            } else {
                stopDnsVpnService()
            }
        }
    }*/

    private fun startAppManagerActivity() {
        val intent = Intent(requireContext(), ApplicationManagerActivity::class.java)
        startActivity(intent)
    }


    private fun startPermissionManagerActivity() {
        val intent = Intent(requireContext(), PermissionManagerActivity::class.java)
        startActivity(intent)
    }

    //Removed code for VPN
/*
    private fun startVpn() {

        vpnOnOffTxt.setText("ON")
        vpnOnOffTxt.setTextColor( resources.getColor(R.color.colorGreen_900))

        val prepare = VpnService.prepare(requireContext()).apply {
            this?.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        }
        *//*
        val prepare = VpnService.prepare(contextVal).apply {
            this?.addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
        }*//*

        if (prepare == null) {
            requireContext().startService(Intent(requireContext(), DnsService::class.java))
            //getPreferences().vpnInformationShown = true
        } else {
            if (true) {
                startActivityForResult(prepare, 1)
            }
        }
    }*/

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.e("BraveDNS","OnActivityResult - RequestCode: "+requestCode + " - ResultCode :"+resultCode)
        if (requestCode == REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            startDnsVpnService()
        }
    }



}