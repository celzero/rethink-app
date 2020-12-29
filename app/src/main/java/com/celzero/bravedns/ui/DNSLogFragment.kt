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

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DNSQueryAdapter
import com.celzero.bravedns.database.DNSLogDAO
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.service.*
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.median50
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.viewmodel.DNSLogViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import settings.Settings
import java.net.MalformedURLException
import java.net.URL
import org.koin.androidx.viewmodel.ext.android.viewModel

class DNSLogFragment  : Fragment(), SearchView.OnQueryTextListener {

    private var recyclerView: RecyclerView? = null
    //private lateinit var context: Context
    private var layoutManager: RecyclerView.LayoutManager? = null
    private var recyclerAdapter: DNSQueryAdapter? = null

    //private lateinit var loadingIllustration: FrameLayout
    private lateinit var latencyTxt: TextView
    private lateinit var queryCountTxt: TextView
    private lateinit var currentDNSStatus: TextView
    private lateinit var currentDNSURL : TextView

    private var editSearchView: SearchView? = null
    private lateinit var filterIcon: ImageView
    private lateinit var deleteIcon: ImageView

   // private lateinit var recyclerHeadingLL : LinearLayout
    private lateinit var noLogsTxt : TextView
    private lateinit var topLayoutRL: RelativeLayout
    private lateinit var searchLayoutLL : LinearLayout

    private lateinit var logsDisabledTxt : TextView

    private val viewModel: DNSLogViewModel by viewModel()
    private var checkedItem = 1
    private var filterValue: String = ""

    lateinit var urlName: Array<String>
    lateinit var urlValues: Array<String>
    var prevSpinnerSelection: Int = 2
    var check = 2

    private val dnsLogDAO by inject<DNSLogDAO>()
    private val persistentState by inject<PersistentState>()


    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        super.onCreate(savedInstanceState)
        //setContentView(R.layout.activity_query_detail)
        //urlName = resources.getStringArray(R.array.doh_endpoint_names)
        //urlValues = resources.getStringArray(R.array.doh_endpoint_urls)
        return inflater.inflate(R.layout.activity_query_detail, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView(view)
    }

    private fun initView(view : View) {
        if(DEBUG) Log.d(LOG_TAG,"InitView from DNSLogFragment")
        //context = this
        latencyTxt = view.findViewById(R.id.latency_txt)
        queryCountTxt = view.findViewById(R.id.total_queries_txt)
        currentDNSStatus = view.findViewById(R.id.connected_status_title)
        currentDNSURL = view.findViewById(R.id.connected_status_title_url)

        //components from the included view.
        val includeView = view.findViewById<View>(R.id.query_list_scroll_list)
        // Set up the recycler
        recyclerView = includeView.findViewById<View>(R.id.recycler_query) as RecyclerView
        topLayoutRL = includeView.findViewById(R.id.query_list_rl)
        editSearchView = includeView.findViewById(R.id.query_list_search)
        filterIcon = includeView.findViewById(R.id.query_list_filter_icon)
        deleteIcon = includeView.findViewById(R.id.query_list_delete_icon)

        searchLayoutLL = includeView.findViewById(R.id.query_list_card_view_top)

        logsDisabledTxt = includeView.findViewById(R.id.query_list__logs_disabled_tv)

        //recyclerHeadingLL = includeView.findViewById(R.id.query_list_recycler_heading)
        noLogsTxt = includeView.findViewById(R.id.dns_log_no_log_text)

        if(persistentState.logsEnabled) {
            logsDisabledTxt.visibility = View.GONE
            searchLayoutLL.visibility = View.VISIBLE
            recyclerView!!.setHasFixedSize(true)
            layoutManager = LinearLayoutManager(requireContext())
            recyclerView!!.layoutManager = layoutManager
            recyclerAdapter = DNSQueryAdapter(requireContext())
            viewModel.dnsLogsList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(recyclerAdapter!!::submitList))
            recyclerView!!.adapter = recyclerAdapter
        }else{
            logsDisabledTxt.visibility = View.VISIBLE
            searchLayoutLL.visibility = View.GONE
        }

        val isServiceRunning = Utilities.isServiceRunning(requireContext(), BraveVPNService::class.java)
        if (!isServiceRunning) {
            topLayoutRL.visibility = View.GONE
        } else {
            topLayoutRL.visibility = View.VISIBLE
        }

        median50.observe(viewLifecycleOwner, {
            latencyTxt.text = "Latency: "+median50.value.toString() + "ms"
        })

        queryCountTxt.text = "Lifetime Queries: " + persistentState.getNumOfReq()

        editSearchView!!.setOnQueryTextListener(this)
        editSearchView!!.setOnClickListener {
            editSearchView!!.requestFocus()
            editSearchView!!.onActionViewExpanded()
        }

        filterIcon.setOnClickListener {
            showDialogForFilter()
        }

        deleteIcon.setOnClickListener {
            showDialogForDelete()
        }
    }

    override fun onResume() {
        super.onResume()
        val dnsType = appMode?.getDNSType()

        if (dnsType == 1) {
            var dohDetail : DoHEndpoint ?= null
            try {
                dohDetail  = appMode?.getDOHDetails()
            }catch (e : Exception){
                return
            }
            currentDNSURL.text = resources.getString(R.string.configure_dns_connected_doh_status)
            currentDNSStatus.text = resources.getString(R.string.configure_dns_connection_name) + " "+ dohDetail?.dohName
            recyclerView?.visibility = View.VISIBLE
            noLogsTxt.visibility = View.GONE
        } else if (dnsType == 2) {
            val cryptDetails = appMode?.getDNSCryptServerCount()
            currentDNSStatus.text = resources.getString(R.string.configure_dns_connection_name) + " DNSCrypt resolvers: $cryptDetails"
            currentDNSURL.text = resources.getString(R.string.configure_dns_connected_dns_crypt_status)
            recyclerView?.visibility = View.VISIBLE
            noLogsTxt.visibility = View.GONE
        } else {
            val proxyDetails = appMode?.getDNSProxyServerDetails()
            currentDNSURL.text = resources.getString(R.string.configure_dns_connected_dns_proxy_status)
            currentDNSStatus.text = resources.getString(R.string.configure_dns_connection_name) + " "+ proxyDetails?.proxyName
            //recyclerHeadingLL.visibility = View.GONE
            recyclerView?.visibility = View.GONE
            if(persistentState.logsEnabled) {
                noLogsTxt.visibility = View.VISIBLE
            }
        }
    }

    private fun showDialogForFilter() {
        val singleItems = arrayOf(getString(R.string.filter_dns_blocked_connections), getString(R.string.filter_dns_all_connections))

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle("Select filter")

        // Single-choice items (initialized with checked item)
        builder.setSingleChoiceItems(singleItems, checkedItem) { dialog, which ->
            // Respond to item chosen
            filterValue = if (which == 0) ":isFilter"
            else ""
            checkedItem = which
            if (DEBUG) Log.d(LOG_TAG, "Filter Option selected: $filterValue")
            viewModel.setFilterBlocked(filterValue)
            dialog.dismiss()
        }
        builder.show()
    }


    private fun showDialogForDelete() {
        val builder = AlertDialog.Builder(requireContext())
        //set title for alert dialog
        builder.setTitle(R.string.dns_query_clear_logs_title)
        //set message for alert dialog
        builder.setMessage(R.string.dns_query_clear_logs_message)
        builder.setIcon(android.R.drawable.ic_dialog_alert)
        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton("Delete logs") { _, _ ->
            GlobalScope.launch(Dispatchers.IO) {
                dnsLogDAO.clearAllData()
            }
        }
        //performing negative action
        builder.setNegativeButton("Cancel") { _, _ ->
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(true)
        alertDialog.show()
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
        fun newInstance() = DNSLogFragment()
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        viewModel.setFilter(query!!, filterValue)
        return true
    }

    override fun onQueryTextChange(query: String?): Boolean {
        viewModel.setFilter(query!!, filterValue)
        return true
    }

}
