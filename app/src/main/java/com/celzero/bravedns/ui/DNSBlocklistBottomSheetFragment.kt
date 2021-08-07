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

import android.app.Dialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Spanned
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.Toast
import androidx.core.text.HtmlCompat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.request.transition.Transition
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DNSLogs
import com.celzero.bravedns.databinding.BottomSheetDnsLogBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS_LOG
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getETldPlus1
import com.celzero.bravedns.util.Utilities.Companion.showToastUiCentered
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import org.koin.android.ext.android.inject
import java.util.*
import kotlin.collections.HashMap


class DNSBlocklistBottomSheetFragment(private var contextVal: Context,
                                      private var transaction: DNSLogs) :
        BottomSheetDialogFragment() {
    private var _binding: BottomSheetDnsLogBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b get() = _binding!!

    //private lateinit var recyclerAdapter: DNSBottomSheetBlockAdapter
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
        b.dnsBlockIpLatency.text = getString(R.string.dns_btm_latency_ms,
                                             transaction.latency.toString())

        b.dnsBlockRuleHeaderLl.setOnClickListener {
            showToastUiCentered(requireContext(), getString(R.string.coming_soon_toast),
                                Toast.LENGTH_SHORT)
        }

        displayFavIcon()
        displayDnsTransactionDetails()
    }

    private fun displayDnsTransactionDetails() {
        displayDescription()

        if (!transaction.hasBlocklists()) {
            b.dnsBlockBlocklistChip.visibility = View.GONE
            return
        }

        handleChip()
    }

    private fun handleChip() {
        b.dnsBlockBlocklistChip.visibility = View.VISIBLE
        val blocklists = transaction.getBlocklists()
        val groupNames: MutableMap<String, String> = HashMap()

        blocklists.forEach {
            val items = it.split(":")
            if (groupNames.containsKey(items[0])) {
                groupNames[items[0]] = groupNames.getValue(items[0]) + "," + items[1]
            } else {
                groupNames[items[0]] = items[1]
            }
        }

        val groupCount = groupNames.keys.count()
        if (groupCount > 1) {
            b.dnsBlockBlocklistChip.text = groupNames.keys.elementAt(0) + " +${groupCount - 1}"
        } else {
            b.dnsBlockBlocklistChip.text = groupNames.keys.elementAt(0)
        }

        b.dnsBlockBlocklistChip.setOnClickListener {
            showBlocklistDialog(groupNames)
        }
    }

    private fun showBlocklistDialog(groupNames: Map<String, String>) {
        val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(dialogBinding.root)
        dialogBinding.infoRulesDialogRulesDesc.text = formatText(groupNames)
        dialogBinding.infoRulesDialogRulesTitle.visibility = View.GONE

        dialogBinding.infoRulesDialogCancelImg.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun formatText(groupNames: Map<String, String>): Spanned {
        var text = ""
        groupNames.forEach {
            val heading = it.key.capitalize(Locale.getDefault())
            val size = it.value.split(",").size
            text += getString(R.string.dns_btm_sheet_dialog_message, heading, size.toString(),
                              it.value)
        }
        text = text.replace(",", ", ")
        return HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun displayDescription() {
        val uptime = DateUtils.getRelativeTimeSpanString(transaction.time,
                                                         System.currentTimeMillis(),
                                                         DateUtils.MINUTE_IN_MILLIS,
                                                         DateUtils.FORMAT_ABBREV_RELATIVE).toString()
        if (transaction.isBlocked) {
            showBlockedState(uptime)
        } else {
            showResolvedState(uptime)
        }
    }

    private fun showResolvedState(uptime: String) {
        if (transaction.isAnonymized()) { // anonymized queries answered by dnscrypt
            val text = getString(R.string.dns_btm_resolved_crypt, uptime, transaction.serverIP)
            b.dnsBlockBlockedDesc.text = Utilities.updateHtmlEncodedText(text)
        } else if (transaction.isLocallyAnswered()) { // usually happens when there is a network failure
            b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_resolved_doh_no_server, uptime)
        } else {
            b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_resolved_doh, uptime,
                                                   transaction.serverIP)
        }
    }

    private fun showBlockedState(uptime: String) {
        if (transaction.isLocallyAnswered()) { // usually true when query blocked by on-device blocklists
            b.dnsBlockBlockedDesc.text = getString(R.string.bsct_conn_block_desc_device, uptime)
        } else {
            b.dnsBlockBlockedDesc.text = getString(R.string.bsct_conn_block_desc, uptime,
                                                   transaction.serverIP)
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

    private fun updateImage(url: String, subDomainUrl: String) {
        try {
            if (DEBUG) Log.d(LOG_TAG_DNS_LOG,
                             "Glide - TransactionViewHolder updateImage() -$url, $subDomainUrl")
            val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
            GlideApp.with(contextVal.applicationContext).load(url).onlyRetrieveFromCache(
                true).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).override(Target.SIZE_ORIGINAL,
                                                                              Target.SIZE_ORIGINAL).error(
                GlideApp.with(contextVal.applicationContext).load(
                    subDomainUrl).onlyRetrieveFromCache(true)).transition(
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
