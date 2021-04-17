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
import android.text.Html
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.text.HtmlCompat
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
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Utilities.Companion.getETldPlus1
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject


class DNSBlockListBottomSheetFragment(private var contextVal: Context, private var transaction: DNSLogs) : BottomSheetDialogFragment() {
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
        if (persistentState.fetchFavIcon) {
            if (transaction.status == Transaction.Status.COMPLETE.toString()
                && transaction.response != Constants.NXDOMAIN && !transaction.isBlocked) {
                val trim = transaction.queryStr.dropLast(1)
                val url = "${Constants.FAV_ICON_URL}$trim.ico"
                val domainURL = getETldPlus1(trim)
                val glideURL = "${Constants.FAV_ICON_URL}$domainURL.ico"
                updateImage(url, glideURL)
            }
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

    private fun updateImage(url: String, cacheKey: String) {
        try {
            if(DEBUG) Log.d(LOG_TAG, "Glide - TransactionViewHolder updateImage() -$url, $cacheKey")
            val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
            GlideApp.with(contextVal.applicationContext)
                    .load(url).onlyRetrieveFromCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                    .error(GlideApp.with(contextVal.applicationContext).load(cacheKey).onlyRetrieveFromCache(true))
                    .transition(DrawableTransitionOptions.withCrossFade(factory))
                    .into(object : CustomViewTarget<ImageView, Drawable>(b.dnsBlockFavIcon) {
                        override fun onLoadFailed(errorDrawable: Drawable?) {
                            if(isAdded) {
                                b.dnsBlockFavIcon.visibility = View.GONE
                            }
                        }

                        override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                            if(DEBUG) Log.d(LOG_TAG, "Glide - CustomViewTarget onResourceReady() -$url")
                            if(isAdded) {
                                b.dnsBlockFavIcon.visibility = View.VISIBLE
                                b.dnsBlockFavIcon.setImageDrawable(resource)
                            }
                        }

                        override fun onResourceCleared(placeholder: Drawable?) {
                            if(isAdded) {
                                b.dnsBlockFavIcon.visibility = View.GONE
                            }
                        }
                    })
        } catch (e: Exception) {
            if(DEBUG) Log.d(LOG_TAG, "Glide - TransactionViewHolder Exception() -${e.message}")
            if(isAdded) {
                b.dnsBlockFavIcon.visibility = View.GONE
            }
        }
    }

}