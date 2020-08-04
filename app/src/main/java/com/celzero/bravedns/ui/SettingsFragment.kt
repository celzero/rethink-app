package com.celzero.bravedns.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatSpinner
import androidx.fragment.app.Fragment
import com.celzero.bravedns.R
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.VpnState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveMode
import com.celzero.bravedns.ui.HomeScreenFragment.Companion.DNS_FIREWALL_MODE
import com.celzero.bravedns.ui.HomeScreenFragment.Companion.FIREWALL_MODE


class SettingsFragment : Fragment() {

    //private lateinit var recentQuery1TV : TextView
    private lateinit var  appManagerBtn : Button
    private lateinit var  viewQueriesBtn : Button
    private lateinit var settingsBtn : Button
    private lateinit var firewallConfigBtn : AppCompatButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {

        val view: View = inflater.inflate(R.layout.fragment_settings,container,false)
        initView(view)
        return view
    }

    private fun initView(view : View){

        //val urlSpinner = view.findViewById<AppCompatSpinner>(R.id.setting_url_spinner)
        //val appModeRadioGroup = view.findViewById<RadioGroup>(R.id.setting_app_mode_radio_group)

        firewallConfigBtn = view.findViewById(R.id.configure_firewall_btn)
        appManagerBtn = view.findViewById(R.id.configure_app_mgr_btn)
        viewQueriesBtn = view.findViewById(R.id.view_queries_btn)
        settingsBtn = view.findViewById(R.id.configure_settings_btn)

        //val btnApply = view.findViewById<AppCompatButton>(R.id.setting_apply)



        /*if(PersistentState.getDnsMode(context!!) == 0)
            appModeRadioGroup.check(R.id.radio_button_1)
        else if(PersistentState.getDnsMode(context!!) == 1)
            appModeRadioGroup.check(R.id.radio_button_2)
        else
            appModeRadioGroup.check(R.id.radio_button_3)*/

        /*appModeRadioGroup.setOnCheckedChangeListener { group, checkedId ->
            val radio: RadioButton = view.findViewById(checkedId)
            Toast.makeText(context," On checked change : ${radio.text}",
                Toast.LENGTH_SHORT).show()
        }*/

        // Get radio group selected status and text using button click event
       /* btnApply.setOnClickListener{
            // Get the checked radio button id from radio group
            val id: Int = appModeRadioGroup.checkedRadioButtonId
            if (id!=-1){ // If any radio button checked from radio group
                // Get the instance of radio button using id
                val radio:RadioButton = view.findViewById(id)
                when (radio.text) {
                    getString(R.string.mode_dns) -> {
                        PersistentState.setDnsMode(context!!,1)
                        PersistentState.setFirewallMode(context!!, 0)
                    }
                    getString(R.string.mode_dns_firewall) -> {
                        PersistentState.setDnsMode(context!!,1)
                        PersistentState.setFirewallMode(context!!, 1)
                    }
                    else -> {
                        PersistentState.setDnsMode(context!!,1)
                        PersistentState.setFirewallMode(context!!, 2)
                    }
                }

            }else{
                // If no radio button checked in this radio group
                Toast.makeText(context,"Please select a mode",
                    Toast.LENGTH_SHORT).show()
            }
        }*/

        appManagerBtn.setOnClickListener {
            startAppManagerActivity()
        }

        settingsBtn.setOnClickListener {
            startPermissionManagerActivity()
        }

        viewQueriesBtn.setOnClickListener {
            startQueryListener()
        }

        firewallConfigBtn.setOnClickListener{
            startFirewallActivity()
        }


    }

    private fun startQueryListener() {
        val status: VpnState? = VpnController.getInstance()!!.getState(context!!)
        if((status!!.activationRequested)) {
            if(braveMode == DNS_FIREWALL_MODE || braveMode == HomeScreenFragment.DNS_MODE) {
                val intent = Intent(requireContext(), QueryDetailActivity::class.java)
                startActivity(intent)
            }else{
                Toast.makeText(context!!, resources.getText(R.string.brave_dns_connect_mode_change_dns).toString().capitalize(),Toast.LENGTH_SHORT ).show()
            }
        }else{
            Toast.makeText(context!!, resources.getText(R.string.brave_dns_connect_prompt_query).toString().capitalize(),Toast.LENGTH_SHORT ).show()
        }
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
    }
    private fun startAppManagerActivity() {
        val intent = Intent(requireContext(), ApplicationManagerActivity::class.java)
        startActivity(intent)
    }


    private fun startPermissionManagerActivity() {
        val intent = Intent(requireContext(), PermissionManagerActivity::class.java)
        startActivity(intent)
    }




}