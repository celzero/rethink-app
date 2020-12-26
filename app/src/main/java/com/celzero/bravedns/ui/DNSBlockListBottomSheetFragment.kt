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
import android.os.Bundle
import android.text.Html
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RelativeLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DNSBottomSheetBlockAdapter
import com.celzero.bravedns.database.DNSLogs
import com.google.android.material.bottomsheet.BottomSheetDialogFragment


class DNSBlockListBottomSheetFragment(private var contextVal: Context, private var transaction: DNSLogs)  : BottomSheetDialogFragment() {

    private lateinit var fragmentView: View
    private lateinit var dnsBlockRecyclerView: RecyclerView
    private lateinit var recyclerAdapter : DNSBottomSheetBlockAdapter
    private lateinit var urlTxt : TextView
    private lateinit var flagTxt : TextView
    private lateinit var resolverTxt : TextView
    private lateinit var ipAddressTxt : TextView
    private lateinit var blockedDescTxt : TextView
    private lateinit var placeHolderTxt : TextView
    private lateinit var latencyTxt : TextView
    private lateinit var latencyChipTxt : TextView

    private lateinit var dnsBlockContainerRL : RelativeLayout

    private lateinit var txtView : TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
    }

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        fragmentView = inflater.inflate(R.layout.bottom_sheet_dns_log, container, false)
        initView(fragmentView)
        return fragmentView
    }

    private fun initView(fragmentView: View) {
        urlTxt = fragmentView.findViewById(R.id.dns_block_url)
        ipAddressTxt = fragmentView.findViewById(R.id.dns_block_ip_address)
        resolverTxt = fragmentView.findViewById(R.id.dns_block_resolver)
        flagTxt = fragmentView.findViewById(R.id.dns_block_connection_flag)
        dnsBlockContainerRL = fragmentView.findViewById(R.id.dns_block_recycler_container)
        dnsBlockRecyclerView = fragmentView.findViewById(R.id.dns_block_recyclerview)
        txtView = fragmentView.findViewById(R.id.dns_block_btm_sheet)
        blockedDescTxt = fragmentView.findViewById(R.id.dns_block_blocked_desc)
        placeHolderTxt = fragmentView.findViewById(R.id.dns_block_place_holder)
        latencyTxt = fragmentView.findViewById(R.id.dns_block_latency)
        latencyChipTxt = fragmentView.findViewById(R.id.dns_block_ip_latency)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        urlTxt.text = transaction.queryStr
        ipAddressTxt.text = transaction.response
        flagTxt.text = transaction.flag
        //latencyTxt.text = "Latency: "+transaction.latency
        latencyTxt.visibility = View.GONE
        latencyChipTxt.text = transaction.latency.toString() +"ms"
        if(transaction.serverIP.isNotEmpty()) {
            resolverTxt.visibility = View.VISIBLE
            resolverTxt.text = "Resolver (${transaction.serverIP})"
            resolverTxt.visibility = View.GONE
        }else{
            resolverTxt.visibility = View.GONE
        }
        val upTime = DateUtils.getRelativeTimeSpanString(transaction.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        if(transaction.isBlocked){
            blockedDescTxt.text = "blocked $upTime by ${transaction.serverIP}"
        }else{
            if(transaction.serverIP.isNotEmpty() && transaction.relayIP.isNotEmpty()) {
                var styledText= ""
                val text = "resolved <u>anonymously</u> $upTime by ${transaction.serverIP}"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    styledText = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
                } else {
                    styledText = Html.fromHtml(text).toString()
                }
                blockedDescTxt.text = styledText
            }else if(transaction.serverIP.isNotEmpty()){
                blockedDescTxt.text = "resolved $upTime by ${transaction.serverIP}"
            }
            else{
                blockedDescTxt.text = "resolved $upTime"
            }
        }
        if(transaction.blockLists.isEmpty()){
            dnsBlockContainerRL.visibility = View.GONE
            placeHolderTxt.visibility = View.VISIBLE
        }else{
            if(transaction.serverIP.isEmpty()){
                blockedDescTxt.text = getString(R.string.bsct_conn_block_desc, upTime, " on device")
            }else{
                blockedDescTxt.text = getString(R.string.bsct_conn_block_desc, upTime," by ${transaction.serverIP}")
            }

            //blockedDescTxt.text = "blocked " + upTime
            val blockLists = transaction.blockLists.split(",")
            if (blockLists != null) {
                //placeHolderTxt.visibility = View.GONE
                dnsBlockRecyclerView.layoutManager = LinearLayoutManager(contextVal)
                recyclerAdapter = DNSBottomSheetBlockAdapter(contextVal, blockLists)
                dnsBlockRecyclerView.adapter = recyclerAdapter
                //dnsBlockRecyclerView.addItemDecoration(DividerItemDecoration(contextVal, DividerItemDecoration.VERTICAL))
                placeHolderTxt.visibility = View.GONE
            }else{
                placeHolderTxt.visibility = View.VISIBLE
            }
        }

        super.onViewCreated(view, savedInstanceState)
    }



}