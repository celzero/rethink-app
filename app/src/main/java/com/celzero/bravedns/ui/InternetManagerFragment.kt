package com.celzero.bravedns.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import android.widget.ToggleButton
import androidx.fragment.app.Fragment
import com.celzero.bravedns.util.MyAccessibilityService
import com.celzero.bravedns.R
import android.content.Context.ACCESSIBILITY_SERVICE
import android.os.Build
import android.util.Log
import android.view.accessibility.AccessibilityManager
import android.widget.Button
/*import com.celzero.bravedns.service.Actions
import com.celzero.bravedns.service.BackgroundService
import com.celzero.bravedns.service.ServiceState
import com.celzero.bravedns.service.getServiceState*/


class InternetManagerFragment : Fragment() {

    private lateinit var adToggle : ToggleButton
    private lateinit var contentToggle : ToggleButton
    private var contextVal : Context ?= null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view: View = inflater.inflate(R.layout.fragment_internet_manager,container,false)

        initView(view)
        return view
    }

    override fun onAttach(context: Context) {
        contextVal = context
        super.onAttach(context)
    }

    private fun initView(view:View) {
        adToggle = view.findViewById(R.id.toggle_ad_blocker)
        contentToggle = view.findViewById(R.id.toggle_content_blocker)

        //Code which needs to be removed
        //For testing purpose
        //Need to delete
        val testButton : Button = view.findViewById(R.id.test_val)

        testButton.setOnClickListener {
            Toast.makeText(contextVal,"Test1",Toast.LENGTH_SHORT).show()
            val intent = Intent(this.contextVal, FirewallActivity::class.java)
            startActivity(intent)
        }

        val testButton1 : Button = view.findViewById(R.id.test_val1)

        testButton1.setOnClickListener {
            Toast.makeText(contextVal,"Test1",Toast.LENGTH_SHORT).show()
            val intent = Intent(this.contextVal, PermissionManagerActivity::class.java)
            startActivity(intent)
        }

        val testButton2 : Button = view.findViewById(R.id.test_val2)

        /*testButton2.setOnClickListener {
            Toast.makeText(contextVal,"Test2",Toast.LENGTH_SHORT).show()
            val intent = Intent(this.contextVal, ApplicationManagerActivity::class.java)
            startActivity(intent)
        }*/

        /*testButton2.setOnClickListener {
            //Toast.makeText(contextVal,"Test2",Toast.LENGTH_SHORT).show()
            actionOnService(Actions.START)
        }*/


        //Above code for testing


        adToggle.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                val am = context?.getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager?
                val isAccessibilityEnabled = am!!.isEnabled
                if(isAccessibilityEnabled)
                    openNetworkDashboardActivity(true)
                else {
                    Toast.makeText(
                        context,
                        "Please enable Accessibility Service to proceed further",
                        Toast.LENGTH_LONG
                    ).show()
                    buttonView.isChecked = false
                }
            } else {
                Toast.makeText(context,"Turned Off",Toast.LENGTH_LONG).show()
                openNetworkDashboardActivity(false)
            }
        }


        contentToggle.setOnClickListener{
            Toast.makeText(context, "Coming Soon!",Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNetworkDashboardActivity(bool : Boolean) {
        val i = Intent("android.settings.WIRELESS_SETTINGS")
        i.setPackage("com.android.settings")
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

        //if(boo)

        if (bool) MyAccessibilityService.setPrivateDnsMode()
        else MyAccessibilityService.unsetPrivateDnsMode()

        startActivity(i)
    }

   /* private fun actionOnService(action: Actions) {
        if (getServiceState(contextVal!!) == ServiceState.STOPPED && action == Actions.STOP) return
        Intent(contextVal!!, BackgroundService::class.java).also {
            it.action = action.name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Log.w("Service","Starting the service in >=26 Mode")
                contextVal!!.startForegroundService(it)
                return
            }
            Log.w("Service","Starting the service in < 26 Mode")
            contextVal!!.startService(it)
        }
    }*/


}