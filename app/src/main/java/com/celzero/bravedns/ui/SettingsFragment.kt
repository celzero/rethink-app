package com.celzero.bravedns.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.widget.AppCompatButton
import androidx.appcompat.widget.AppCompatTextView
import androidx.fragment.app.Fragment
import com.celzero.bravedns.R
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.VpnState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.braveMode
import com.celzero.bravedns.ui.HomeScreenFragment.Companion.DNS_FIREWALL_MODE
import com.celzero.bravedns.ui.HomeScreenFragment.Companion.FIREWALL_MODE


class SettingsFragment : Fragment() {

    private lateinit var  appManagerBtn : Button
    private lateinit var  viewQueriesBtn : Button
    private lateinit var settingsBtn : Button
    private lateinit var firewallConfigBtn : AppCompatButton
    private lateinit var faqTxt : AppCompatTextView
    private lateinit var connTrackerBtn : AppCompatButton

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view: View = inflater.inflate(R.layout.fragment_settings,container,false)
        initView(view)
        return view
    }

    private fun initView(view : View){

        firewallConfigBtn = view.findViewById(R.id.configure_firewall_btn)
        appManagerBtn = view.findViewById(R.id.configure_app_mgr_btn)
        viewQueriesBtn = view.findViewById(R.id.view_queries_btn)
        settingsBtn = view.findViewById(R.id.configure_settings_btn)
        faqTxt = view.findViewById(R.id.settings_app_faq_icon)
        connTrackerBtn = view.findViewById(R.id.configure_conn_tracker_btn)

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

        faqTxt.setOnClickListener{
            startWebViewIntent()
        }

        connTrackerBtn.setOnClickListener{
            startConnectionTrackerActivity()
        }

    }

    private fun startConnectionTrackerActivity() {
        val status: VpnState? = VpnController.getInstance()!!.getState(context!!)
        if((status!!.activationRequested) ) {
            if(braveMode == DNS_FIREWALL_MODE || braveMode == FIREWALL_MODE) {
                val intent = Intent(requireContext(), ConnectionTrackerActivity::class.java)
                startActivity(intent)
            }else{
                Toast.makeText(context!!, resources.getText(R.string.brave_dns_connect_mode_change_connection).toString().capitalize(),Toast.LENGTH_SHORT ).show()
            }
        }else{
            Toast.makeText(context!!, resources.getText(R.string.brave_dns_connect_prompt_firewall).toString().capitalize(),Toast.LENGTH_SHORT ).show()
        }
    }

    private fun startWebViewIntent(){
        val intent = Intent(requireContext(), FaqWebViewActivity::class.java)
        startActivity(intent)
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