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

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DNSQueryAdapter
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.service.VpnState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.median50
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Utilities
import settings.Settings
import java.net.MalformedURLException
import java.net.URL
import java.util.*


class DNSLogFragment  : Fragment() {

    private val SPINNER_VALUE_NO_FILTER = 0
    private val SPINNER_VALUE_FAMILY = 1
    private val SPINNER_VALUE_FREE_BRAVE_DNS = 2
    private val SPINNER_VALUE_CUSTOM_FILTER = 3
    private val SPINNER_VALUE_BRAVE_COMING_SOON = 4




    private var recyclerView: RecyclerView? = null
    //private lateinit var context: Context
    private var layoutManager: RecyclerView.LayoutManager? = null

    //private lateinit var loadingIllustration: FrameLayout
    private lateinit var latencyTxt: TextView
    private lateinit var queryCountTxt: TextView
    private lateinit var currentDNSStatus: TextView
    private lateinit var currentDNSURL : TextView

    private lateinit var recyclerHeadingLL : LinearLayout
    private lateinit var noLogsTxt : TextView
    private lateinit var topLayoutRL: RelativeLayout

    lateinit var urlName: Array<String>
    lateinit var urlValues: Array<String>
    var prevSpinnerSelection: Int = 2
    var check = 2

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreate(savedInstanceState)
        //setContentView(R.layout.activity_query_detail)
        val view: View = inflater.inflate(R.layout.activity_query_detail, container, false)
        urlName = resources.getStringArray(R.array.doh_endpoint_names)
        urlValues = resources.getStringArray(R.array.doh_endpoint_urls)
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView(view)
    }

    private fun initView(view : View) {
        if(DEBUG) Log.d(LOG_TAG,"InitView from DNSLogFragment")
        //context = this
        val includeView = view.findViewById<View>(R.id.query_list_scroll_list)
        // Set up the recycler
        recyclerView = includeView.findViewById<View>(R.id.recycler_query) as RecyclerView
       //loadingIllustration = includeView.findViewById(R.id.query_progressBarHolder)
        topLayoutRL = includeView.findViewById(R.id.query_list_rl)

        latencyTxt = includeView.findViewById(R.id.latency_txt)

        queryCountTxt = includeView.findViewById(R.id.total_queries_txt)
        //urlSpinner = includeView.findViewById(R.id.setting_url_spinner)

        currentDNSStatus = includeView.findViewById(R.id.connected_status_title)
        currentDNSURL = includeView.findViewById(R.id.connected_status_title_url)

        recyclerHeadingLL = includeView.findViewById(R.id.query_list_recycler_heading)
        noLogsTxt = includeView.findViewById(R.id.dns_log_no_log_text)
        //dnsSelectorInfoIcon = includeView.findViewById(R.id.query_dns_info_icon)

        recyclerView!!.setHasFixedSize(true)
        layoutManager = LinearLayoutManager(requireContext())
        recyclerView!!.layoutManager = layoutManager
        adapterDNS = DNSQueryAdapter(requireContext())
        adapterDNS!!.reset(getHistory())
        recyclerView!!.adapter = adapterDNS


        val isServiceRunning = Utilities.isServiceRunning(requireContext(), BraveVPNService::class.java)
        if (!isServiceRunning) {
            //loadingIllustration.visibility = View.VISIBLE
            topLayoutRL.visibility = View.GONE
        } else {
            //loadingIllustration.visibility = View.GONE
            topLayoutRL.visibility = View.VISIBLE
        }

        median50.observe(viewLifecycleOwner, androidx.lifecycle.Observer {
            latencyTxt.setText("Latency: "+median50.value.toString() + "ms")
        })

        //latencyTxt.setText("Latency: " + getMedianLatency(this) + "ms")
        queryCountTxt.setText("Lifetime Queries: " + PersistentState.getNumOfReq(requireContext()))

    }

    override fun onResume() {
        super.onResume()
        val dnsType = appMode?.getDNSType()

        if (dnsType == 1) {
            val dohDetail = appMode?.getDOHDetails()
            currentDNSURL.text = resources.getString(R.string.configure_dns_connected_doh_status)
            currentDNSStatus.text = resources.getString(R.string.configure_dns_connection_name) + " "+ dohDetail?.dohName
            recyclerHeadingLL.visibility = View.VISIBLE
            recyclerView?.visibility = View.VISIBLE
            noLogsTxt.visibility = View.GONE
        } else if (dnsType == 2) {
            val cryptDetails = appMode?.getDNSCryptServerCount()
            currentDNSStatus.text = resources.getString(R.string.configure_dns_connection_name) + " DNSCrypt resolvers: $cryptDetails"
            currentDNSURL.text = resources.getString(R.string.configure_dns_connected_dns_crypt_status)
            recyclerHeadingLL.visibility = View.VISIBLE
            recyclerView?.visibility = View.VISIBLE
            noLogsTxt.visibility = View.GONE
        } else {
            val proxyDetails = appMode?.getDNSProxyServerDetails()
            currentDNSURL.text = resources.getString(R.string.configure_dns_connected_dns_proxy_status)
            currentDNSStatus.text = resources.getString(R.string.configure_dns_connection_name) + " "+ proxyDetails?.proxyName
            recyclerHeadingLL.visibility = View.GONE
            recyclerView?.visibility = View.GONE
            noLogsTxt.visibility = View.VISIBLE
        }
    }

    // Check that the URL is a plausible DOH server: https with a domain, a path (at least "/"),
    // and no query parameters or fragment.
    private fun checkUrl(url: String): Boolean {
        return try {
            val parsed = URL(url)
            parsed.protocol == "https" && parsed.host.isNotEmpty() &&
                    parsed.path.isNotEmpty() && parsed.query == null && parsed.ref == null
        } catch (e: MalformedURLException) {
            false
        }
    }

    private fun checkConnection(): Boolean {
        var connectionStatus = false
        val status: VpnState? = VpnController.getInstance()!!.getState(requireContext())
        if (status!!.activationRequested) {
            if (status.connectionState == null) {
                if (appMode?.getFirewallMode() == Settings.BlockModeSink) {
                    connectionStatus = true
                }
            } else if (status.connectionState === BraveVPNService.State.WORKING) {
                connectionStatus = true
            }
        }
        return connectionStatus
    }


    companion object {
        private var adapterDNS: DNSQueryAdapter? = null

        fun newInstance() = DNSLogFragment()

        fun updateStatsDisplay(numRequests: Long, transaction: Transaction) {
            showTransaction(transaction)
        }

        private fun showTransaction(transaction: Transaction) {
            if(DEBUG) Log.d(LOG_TAG,"showTransaction from DNSLogFragment - ${transaction.name}")
            if(DEBUG) Log.d(LOG_TAG,"Show BlockList: ${transaction.blockList}")
            if(adapterDNS != null) {
                adapterDNS?.add(transaction)
                adapterDNS?.notifyDataSetChanged()
            }
        }
    }

    private fun getHistory(): Queue<Transaction?>? {
        val controller = VpnController.getInstance()
        return controller!!.getTracker(requireContext())!!.recentTransactions
    }

    private fun getIndex(url: String): Int {
        val urlValues = resources.getStringArray(R.array.doh_endpoint_urls)

        for(i in urlValues.indices){
            if(urlValues[i] == url)
                return i
        }
        return -1
    }

}
