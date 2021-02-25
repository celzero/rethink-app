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

import android.icu.text.CompactDecimalFormat
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import by.kirich1409.viewbindingdelegate.viewBinding
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DNSQueryAdapter
import com.celzero.bravedns.database.DNSLogDAO
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.databinding.ActivityQueryDetailBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.lifeTimeQ
import com.celzero.bravedns.viewmodel.DNSLogViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.viewModel
import java.util.*

class DNSLogFragment : Fragment(R.layout.activity_query_detail), SearchView.OnQueryTextListener {
    private val b by viewBinding(ActivityQueryDetailBinding::bind)

    private var layoutManager: RecyclerView.LayoutManager? = null
    private var recyclerAdapter: DNSQueryAdapter? = null

    private val viewModel: DNSLogViewModel by viewModel()
    private var checkedItem = 1
    private var filterValue: String = ""

    var check = 2

    private val dnsLogDAO by inject<DNSLogDAO>()
    private val persistentState by inject<PersistentState>()

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initView()
    }

    private fun initView() {
        val includeView = b.queryListScrollList

        if (persistentState.logsEnabled) {
            includeView.queryListLogsDisabledTv.visibility = View.GONE
            includeView.queryListCardViewTop.visibility = View.VISIBLE
            includeView.recyclerQuery.setHasFixedSize(true)
            layoutManager = LinearLayoutManager(requireContext())
            includeView.recyclerQuery.layoutManager = layoutManager
            recyclerAdapter = DNSQueryAdapter(requireContext())
            viewModel.dnsLogsList.observe(viewLifecycleOwner, androidx.lifecycle.Observer(recyclerAdapter!!::submitList))
            includeView.recyclerQuery.adapter = recyclerAdapter
        } else {
            includeView.queryListLogsDisabledTv.visibility = View.VISIBLE
            includeView.queryListCardViewTop.visibility = View.GONE
        }

        val isServiceRunning = persistentState.vpnEnabled
        if (!isServiceRunning) {
            includeView.queryListRl.visibility = View.GONE
        } else {
            includeView.queryListRl.visibility = View.VISIBLE
        }

        includeView.queryListSearch.setOnQueryTextListener(this)
        includeView.queryListSearch.setOnClickListener {
            includeView.queryListSearch.requestFocus()
            includeView.queryListSearch.onActionViewExpanded()
        }

        includeView.queryListFilterIcon.setOnClickListener {
            showDialogForFilter()
        }

        includeView.queryListDeleteIcon.setOnClickListener {
            showDialogForDelete()
        }

        registerForObservers()
    }

    private fun registerForObservers(){

        lifeTimeQ.observe(viewLifecycleOwner, {
            val lifeTimeConversion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                CompactDecimalFormat.getInstance(Locale.US, CompactDecimalFormat.CompactStyle.SHORT).format(lifeTimeQ.value)
            } else {
                lifeTimeQ.value.toString()
            }
            b.totalQueriesTxt.text  = getString(R.string.dns_logs_lifetime_queries, lifeTimeConversion)
        })

        HomeScreenActivity.GlobalVariable.blockedCount.observe(viewLifecycleOwner, {
            val blocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                CompactDecimalFormat.getInstance(Locale.US, CompactDecimalFormat.CompactStyle.SHORT).format(HomeScreenActivity.GlobalVariable.blockedCount.value)
            } else {
                HomeScreenActivity.GlobalVariable.blockedCount.value.toString()
            }

            b.latencyTxt.text = getString(R.string.dns_logs_blocked_queries, blocked)
        })


    }

    override fun onResume() {
        super.onResume()
        val dnsType = appMode?.getDNSType()

        if (dnsType == 1) {
            val dohDetail: DoHEndpoint?
            try {
                dohDetail = appMode?.getDOHDetails()
            } catch (e: Exception) {
                return
            }
            b.connectedStatusTitleUrl.text = resources.getString(R.string.configure_dns_connected_doh_status)
            b.connectedStatusTitle.text = resources.getString(R.string.configure_dns_connection_name, dohDetail?.dohName)
            b.queryListScrollList.recyclerQuery.visibility = View.VISIBLE
            b.queryListScrollList.dnsLogNoLogText.visibility = View.GONE
        } else if (dnsType == 2) {
            val cryptDetails = appMode?.getDNSCryptServerCount()
            val cryptName = resources.getString(R.string.configure_dns_crypt_name, cryptDetails.toString())
            b.connectedStatusTitle.text = resources.getString(R.string.configure_dns_connection_name, cryptName)
            b.connectedStatusTitleUrl.text = resources.getString(R.string.configure_dns_connected_dns_crypt_status)
            persistentState.setConnectedDNS("DNSCrypt: $cryptDetails resolvers")
            b.queryListScrollList.recyclerQuery.visibility = View.VISIBLE
            b.queryListScrollList.dnsLogNoLogText.visibility = View.GONE
        } else {
            val proxyDetails = appMode?.getDNSProxyServerDetails()
            b.connectedStatusTitleUrl.text = resources.getString(R.string.configure_dns_connected_dns_proxy_status)
            b.connectedStatusTitle.text = resources.getString(R.string.configure_dns_connection_name, proxyDetails?.proxyName)
            b.queryListScrollList.recyclerQuery.visibility = View.GONE
            if (persistentState.logsEnabled) {
                b.queryListScrollList.dnsLogNoLogText.visibility = View.VISIBLE
            }
        }
    }

    private fun showDialogForFilter() {
        val singleItems = arrayOf(getString(R.string.filter_dns_blocked_connections), getString(R.string.filter_dns_all_connections))

        val builder = AlertDialog.Builder(requireContext())
        builder.setTitle(getString(R.string.dns_log_dialog_title))

        // Single-choice items (initialized with checked item)
        builder.setSingleChoiceItems(singleItems, checkedItem) { dialog, which ->
            // Respond to item chosen
            filterValue = if (which == 0) ":isFilter"
            else ""
            checkedItem = which
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
        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton(getString(R.string.dns_log_dialog_positive)) { _, _ ->
            GlobalScope.launch(Dispatchers.IO) {
                dnsLogDAO.clearAllData()
            }
        }
        //performing negative action
        builder.setNegativeButton(getString(R.string.dns_log_dialog_negative)) { _, _ ->
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(true)
        alertDialog.show()
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
