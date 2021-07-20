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
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.request.transition.Transition
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DNSBottomSheetBlockAdapter
import com.celzero.bravedns.database.DNSLogs
import com.celzero.bravedns.databinding.BottomSheetDnsLogBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS_LOG
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getETldPlus1
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject


class DNSBlocklistBottomSheetFragment(private var contextVal: Context,
                                      private var transaction: DNSLogs) :
        BottomSheetDialogFragment() {
    private var _binding: BottomSheetDnsLogBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b get() = _binding!!

    private lateinit var recyclerAdapter: DNSBottomSheetBlockAdapter
    private val persistentState by inject<PersistentState>()

    override fun getTheme(): Int = Utilities.getBottomsheetCurrentTheme(isDarkThemeOn())

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
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
        b.dnsBlockIpLatency.text = getString(R.string.dns_btm_latency_ms,
                                             transaction.latency.toString())

        displayFavIcon()
        displayDnsTransactionDetails()
    }

    private fun displayDnsTransactionDetails() {

        val uptime = DateUtils.getRelativeTimeSpanString(transaction.time,
                                                         System.currentTimeMillis(),
                                                         DateUtils.MINUTE_IN_MILLIS,
                                                         DateUtils.FORMAT_ABBREV_RELATIVE)

        displayDescription(uptime.toString())

        if (transaction.blockLists.isEmpty()) {
            b.dnsBlockRecyclerContainer.visibility = View.GONE
            b.dnsBlockPlaceHolder.visibility = View.VISIBLE
        } else {
            if (transaction.serverIP.isEmpty()) {
                b.dnsBlockBlockedDesc.text = getString(R.string.bsct_conn_block_desc_device, uptime)
            } else {
                b.dnsBlockBlockedDesc.text = getString(R.string.bsct_conn_block_desc, uptime,
                                                       transaction.serverIP)
            }

            val blocklists = transaction.blockLists.split(",")
            if (blocklists.isNotEmpty()) {
                b.dnsBlockRecyclerview.layoutManager = LinearLayoutManager(contextVal)
                recyclerAdapter = DNSBottomSheetBlockAdapter(contextVal, blocklists)
                b.dnsBlockRecyclerview.adapter = recyclerAdapter
                b.dnsBlockPlaceHolder.visibility = View.GONE
            } else {
                b.dnsBlockPlaceHolder.visibility = View.VISIBLE
            }
        }
    }

    private fun displayDescription(uptime: String) {

        if (transaction.isBlocked) {
            b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_blocked_by, uptime,
                                                   transaction.serverIP)
        } else {
            if (transaction.serverIP.isNotEmpty() && transaction.relayIP.isNotEmpty()) {
                val text = getString(R.string.dns_btm_resolved_crypt, uptime, transaction.serverIP)
                b.dnsBlockBlockedDesc.text = Utilities.updateHtmlEncodedText(text)
            } else if (transaction.serverIP.isNotEmpty()) {
                b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_resolved_doh, uptime,
                                                       transaction.serverIP)
            } else {
                b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_resolved_doh_no_server,
                                                       uptime)
            }
        }
    }

    private fun displayFavIcon() {
        if (!persistentState.fetchFavIcon || transaction.failure()) return

        val trim = transaction.queryStr.dropLast(1)
        val url = "${Constants.FAV_ICON_URL}$trim.ico"
        val domainURL = getETldPlus1(trim)
        val glideURL = "${Constants.FAV_ICON_URL}$domainURL.ico"
        updateImage(url, glideURL)
    }

    private fun updateImage(url: String, cacheKey: String) {
        try {
            if (DEBUG) Log.d(LOG_TAG_DNS_LOG,
                             "Glide - TransactionViewHolder updateImage() -$url, $cacheKey")
            val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
            GlideApp.with(contextVal.applicationContext).load(url).onlyRetrieveFromCache(
                true).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).override(Target.SIZE_ORIGINAL,
                                                                              Target.SIZE_ORIGINAL).error(
                GlideApp.with(contextVal.applicationContext).load(cacheKey).onlyRetrieveFromCache(
                    true)).transition(
                DrawableTransitionOptions.withCrossFade(factory)).into(object :
                                                                               CustomViewTarget<ImageView, Drawable>(
                                                                                   b.dnsBlockFavIcon) {
                override fun onLoadFailed(errorDrawable: Drawable?) {
                    if (!isAdded) return

                    b.dnsBlockFavIcon.visibility = View.GONE
                }

                override fun onResourceReady(resource: Drawable,
                                             transition: Transition<in Drawable>?) {
                    if (DEBUG) Log.d(LOG_TAG_DNS_LOG,
                                     "Glide - CustomViewTarget onResourceReady() -$url")
                    if (!isAdded) return

                    b.dnsBlockFavIcon.visibility = View.VISIBLE
                    b.dnsBlockFavIcon.setImageDrawable(resource)
                }

                override fun onResourceCleared(placeholder: Drawable?) {
                    if (!isAdded) return

                    b.dnsBlockFavIcon.visibility = View.GONE
                }
            })
        } catch (e: Exception) {
            if (DEBUG) Log.d(LOG_TAG_DNS_LOG,
                             "Glide - TransactionViewHolder Exception() -${e.message}")
            if (!isAdded) return

            b.dnsBlockFavIcon.visibility = View.GONE
        }
    }

}
