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

import android.content.Context
import android.content.Context.ACCESSIBILITY_SERVICE
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.FragmentInternetManagerBinding
import com.celzero.bravedns.util.MyAccessibilityService

/*import com.celzero.bravedns.service.Actions
import com.celzero.bravedns.service.BackgroundService
import com.celzero.bravedns.service.ServiceState
import com.celzero.bravedns.service.getServiceState*/


class InternetManagerFragment : Fragment(R.layout.fragment_internet_manager) {
    private val b by viewBinding(FragmentInternetManagerBinding::bind)
    private var contextVal: Context? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    override fun onAttach(context: Context) {
        contextVal = context
        super.onAttach(context)
    }

    private fun initView() {

        //Code which needs to be removed
        //For testing purpose
        //Need to delete

        b.testVal.setOnClickListener {
            Toast.makeText(contextVal, "Test1", Toast.LENGTH_SHORT).show()
            val intent = Intent(this.contextVal, UniversalFirewallFragment::class.java)
            startActivity(intent)
        }

        b.testVal1.setOnClickListener {
            Toast.makeText(contextVal, "Test1", Toast.LENGTH_SHORT).show()
            val intent = Intent(this.contextVal, PermissionManagerActivity::class.java)
            startActivity(intent)
        }


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


        b.toggleAdBlocker.setOnCheckedChangeListener { buttonView, isChecked ->
            if (isChecked) {
                val am = context?.getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager?
                val isAccessibilityEnabled = am!!.isEnabled
                if (isAccessibilityEnabled) openNetworkDashboardActivity(true)
                else {
                    Toast.makeText(context, "Please enable Accessibility Service to proceed further", Toast.LENGTH_LONG).show()
                    buttonView.isChecked = false
                }
            } else {
                Toast.makeText(context, "Turned Off", Toast.LENGTH_LONG).show()
                openNetworkDashboardActivity(false)
            }
        }


        b.toggleContentBlocker.setOnClickListener {
            Toast.makeText(context, "Coming Soon!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNetworkDashboardActivity(bool: Boolean) {
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