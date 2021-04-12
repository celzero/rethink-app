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
import android.content.res.Configuration
import android.os.Bundle
import android.text.Html
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.text.HtmlCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DNSBottomSheetBlockAdapter
import com.celzero.bravedns.database.DNSLogs
import com.celzero.bravedns.databinding.BottomSheetDnsLogBinding
import com.celzero.bravedns.receiver.GlideImageRequestListener
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.util.Utilities
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject


class DNSBlockListBottomSheetFragment(private var contextVal: Context, private var transaction: DNSLogs)
            : BottomSheetDialogFragment(), GlideImageRequestListener.Callback {
    private var _binding: BottomSheetDnsLogBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b get() = _binding!!

    private lateinit var recyclerAdapter: DNSBottomSheetBlockAdapter
    private val persistentState by inject<PersistentState>()

    override fun getTheme(): Int = if (persistentState.theme == 0) {
        if (isDarkThemeOn()) {
            R.style.BottomSheetDialogThemeTrueBlack
        } else {
            R.style.BottomSheetDialogThemeWhite
        }
    } else if (persistentState.theme == 1) {
        R.style.BottomSheetDialogThemeWhite
    } else if (persistentState.theme == 2) {
        R.style.BottomSheetDialogTheme
    } else {
        R.style.BottomSheetDialogThemeTrueBlack
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = BottomSheetDnsLogBinding.inflate(inflater, container, false)
        return b.root
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.dnsBlockUrl.text = transaction.queryStr
        b.dnsBlockIpAddress.text = transaction.response
        b.dnsBlockConnectionFlag.text = transaction.flag
        b.dnsBlockLatency.visibility = View.GONE
        b.dnsBlockIpLatency.text = getString(R.string.dns_btm_latency_ms, transaction.latency.toString())
        if (transaction.serverIP.isNotEmpty()) {
            b.dnsBlockResolver.visibility = View.VISIBLE
            b.dnsBlockResolver.text = getString(R.string.dns_btm_resolver, transaction.serverIP)
            b.dnsBlockResolver.visibility = View.GONE
        } else {
            b.dnsBlockResolver.visibility = View.GONE
        }
        if(transaction.response != "NXDOMAIN" && !transaction.isBlocked){
            setFavIcon(transaction.queryStr)
        }
        val upTime = DateUtils.getRelativeTimeSpanString(transaction.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE)
        if (transaction.isBlocked) {
            b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_blocked_by, upTime, transaction.serverIP)
        } else {
            if (transaction.serverIP.isNotEmpty() && transaction.relayIP.isNotEmpty()) {
                val text = getString(R.string.dns_btm_resolved_crypt, upTime, transaction.serverIP)
                val styledText = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                    Html.fromHtml(text, Html.FROM_HTML_MODE_LEGACY).toString()
                } else {
                    HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
                }
                b.dnsBlockBlockedDesc.text = styledText
            } else if (transaction.serverIP.isNotEmpty()) {
                b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_resolved_doh, upTime, transaction.serverIP)
            } else {
                b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_resolved_doh_no_server, upTime)
            }
        }
        if (transaction.blockLists.isEmpty()) {
            b.dnsBlockRecyclerContainer.visibility = View.GONE
            b.dnsBlockPlaceHolder.visibility = View.VISIBLE
        } else {
            if (transaction.serverIP.isEmpty()) {
                b.dnsBlockBlockedDesc.text = getString(R.string.bsct_conn_block_desc_device, upTime)
            } else {
                b.dnsBlockBlockedDesc.text = getString(R.string.bsct_conn_block_desc, upTime, transaction.serverIP)
            }

            val blockLists = transaction.blockLists.split(",")
            if (blockLists.isNotEmpty()) {
                b.dnsBlockRecyclerview.layoutManager = LinearLayoutManager(contextVal)
                recyclerAdapter = DNSBottomSheetBlockAdapter(contextVal, blockLists)
                b.dnsBlockRecyclerview.adapter = recyclerAdapter
                b.dnsBlockPlaceHolder.visibility = View.GONE
            } else {
                b.dnsBlockPlaceHolder.visibility = View.VISIBLE
            }
        }
    }

    private fun setFavIcon(query: String) {
        val trim = query.dropLast(1)
        val domainURL = Utilities.getETldPlus1(trim)
        val url = "https://icons.duckduckgo.com/ip2/$domainURL.ico"
        Glide.with(contextVal)
            .load(url)
            .listener(GlideImageRequestListener(this))
            .into(b.dnsBlockFavIcon)
    }

    override fun onFailure(message: String?) {
        b.dnsBlockFavIcon.visibility = View.GONE
    }

    override fun onSuccess(dataSource: String) {
        b.dnsBlockFavIcon.visibility = View.VISIBLE
    }

}