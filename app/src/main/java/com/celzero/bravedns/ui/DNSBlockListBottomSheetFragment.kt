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
import androidx.recyclerview.widget.LinearLayoutManager
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DNSBottomSheetBlockAdapter
import com.celzero.bravedns.database.DNSLogs
import com.celzero.bravedns.databinding.BottomSheetDnsLogBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment


class DNSBlockListBottomSheetFragment(private var contextVal: Context, private var transaction: DNSLogs) : BottomSheetDialogFragment() {
    private var _binding: BottomSheetDnsLogBinding? = null
    private val b get() = _binding!!

    private lateinit var recyclerAdapter: DNSBottomSheetBlockAdapter

    override fun getTheme(): Int = R.style.BottomSheetDialogTheme

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetDnsLogBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        b.dnsBlockUrl.text = transaction.queryStr
        b.dnsBlockIpAddress.text = transaction.response
        b.dnsBlockConnectionFlag.text = transaction.flag
        //latencyTxt.text = "Latency: "+transaction.latency
        b.dnsBlockLatency.visibility = View.GONE
        b.dnsBlockIpLatency.text = transaction.latency.toString() + "ms"
        if (transaction.serverIP.isNotEmpty()) {
            b.dnsBlockResolver.visibility = View.VISIBLE
            b.dnsBlockResolver.text = "Resolver (${transaction.serverIP})"
            b.dnsBlockResolver.visibility = View.GONE
        } else {
            b.dnsBlockResolver.visibility = View.GONE
        }
        val upTime = DateUtils.getRelativeTimeSpanString(transaction.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        if (transaction.isBlocked) {
            b.dnsBlockBlockedDesc.text = "blocked $upTime by ${transaction.serverIP}"
        } else {
            if (transaction.serverIP.isNotEmpty() && transaction.relayIP.isNotEmpty()) {
                var styledText = ""
                val text = "resolved <u>anonymously</u> $upTime by ${transaction.serverIP}"
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    styledText = Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
                } else {
                    styledText = Html.fromHtml(text).toString()
                }
                b.dnsBlockBlockedDesc.text = styledText
            } else if (transaction.serverIP.isNotEmpty()) {
                b.dnsBlockBlockedDesc.text = "resolved $upTime by ${transaction.serverIP}"
            } else {
                b.dnsBlockBlockedDesc.text = "resolved $upTime"
            }
        }
        if (transaction.blockLists.isEmpty()) {
            b.dnsBlockRecyclerContainer.visibility = View.GONE
            b.dnsBlockPlaceHolder.visibility = View.VISIBLE
        } else {
            if (transaction.serverIP.isEmpty()) {
                b.dnsBlockBlockedDesc.text = getString(R.string.bsct_conn_block_desc, upTime, " on device")
            } else {
                b.dnsBlockBlockedDesc.text = getString(R.string.bsct_conn_block_desc, upTime, " by ${transaction.serverIP}")
            }

            //blockedDescTxt.text = "blocked " + upTime
            val blockLists = transaction.blockLists.split(",")
            if (blockLists != null) {
                //placeHolderTxt.visibility = View.GONE
                b.dnsBlockRecyclerview.layoutManager = LinearLayoutManager(contextVal)
                recyclerAdapter = DNSBottomSheetBlockAdapter(contextVal, blockLists)
                b.dnsBlockRecyclerview.adapter = recyclerAdapter
                //dnsBlockRecyclerView.addItemDecoration(DividerItemDecoration(contextVal, DividerItemDecoration.VERTICAL))
                b.dnsBlockPlaceHolder.visibility = View.GONE
            } else {
                b.dnsBlockPlaceHolder.visibility = View.VISIBLE
            }
        }

        super.onViewCreated(view, savedInstanceState)
    }

}