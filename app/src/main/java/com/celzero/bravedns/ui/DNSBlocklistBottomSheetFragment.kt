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
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.Log
import android.view.*
import android.widget.ImageView
import androidx.core.text.HtmlCompat
import androidx.core.view.isVisible
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.target.Target
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.request.transition.Transition
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.CustomDomainManager
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.databinding.BottomSheetDnsLogBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.databinding.DialogIpDetailsLayoutBinding
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS_LOG
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.getETldPlus1
import com.celzero.bravedns.util.Utilities.Companion.updateHtmlEncodedText
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import org.koin.android.ext.android.inject
import java.util.*


class DNSBlocklistBottomSheetFragment(private var contextVal: Context,
                                      private var transaction: DnsLog) :
        BottomSheetDialogFragment() {
    private var _binding: BottomSheetDnsLogBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b get() = _binding!!

    private val persistentState by inject<PersistentState>()

    override fun getTheme(): Int = Themes.getBottomsheetCurrentTheme(isDarkThemeOn(),
                                                                     persistentState.theme)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?,
                              savedInstanceState: Bundle?): View {
        _binding = BottomSheetDnsLogBinding.inflate(inflater, container, false)
        return b.root
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        b.dnsBlockUrl.text = transaction.queryStr
        b.dnsBlockIpAddress.text = getResponseIp()
        b.dnsBlockConnectionFlag.text = transaction.flag
        b.dnsBlockIpLatency.text = getString(R.string.dns_btm_latency_ms,
                                             transaction.latency.toString())

        handleCustomDomainViews()
        displayFavIcon()
        displayDnsTransactionDetails()
    }

    private fun handleCustomDomainViews() {
        if (CustomDomainManager.isDomainWhitelisted(transaction.queryStr)) {
            b.dnsBlockCheck.isChecked = false
            b.dnsWhitelistCheck.isChecked = true
        } else if (CustomDomainManager.isDomainBlocked(transaction.queryStr)) {
            b.dnsBlockCheck.isChecked = true
            b.dnsWhitelistCheck.isChecked = false
        } else {
            b.dnsBlockCheck.isChecked = false
            b.dnsWhitelistCheck.isChecked = false
        }

        b.dnsBlockCheck.setOnCheckedChangeListener(null)
        b.dnsBlockCheck.setOnClickListener {
            handleBlockSwitch(b.dnsBlockCheck.isChecked)
        }

        b.dnsWhitelistCheck.setOnCheckedChangeListener(null)
        b.dnsWhitelistCheck.setOnClickListener {
            handleWhitelistSwitch(b.dnsWhitelistCheck.isChecked)
        }
    }

    private fun handleBlockSwitch(enabled: Boolean) {
        if (enabled) {
            // add to custom blocklist domains
            b.dnsWhitelistCheck.isChecked = false
            CustomDomainManager.blocklist(transaction.queryStr, transaction.responseIps)
        } else {
            // sets the status to none
            CustomDomainManager.removeStatus(transaction.queryStr, transaction.responseIps)
        }
    }

    private fun handleWhitelistSwitch(enabled: Boolean) {
        if (enabled) {
            // add to custom blocklist domains
            b.dnsBlockCheck.isChecked = false
            CustomDomainManager.whitelist(transaction.queryStr, transaction.responseIps)
        } else {
            // sets the status to none
            CustomDomainManager.removeStatus(transaction.queryStr, transaction.responseIps)
        }
    }

    private fun getResponseIp(): String {
        val ips = transaction.response.split(",")
        return ips[0]
    }

    private fun displayDnsTransactionDetails() {
        displayDescription()

        if (transaction.hasBlocklists()) {
            b.dnsBlockBlocklistChip.visibility = View.VISIBLE
            b.dnsBlockIpsChip.visibility = View.GONE
            handleBlocklistChip()
            return
        }

        b.dnsBlockBlocklistChip.visibility = View.GONE
        b.dnsBlockIpsChip.visibility = View.VISIBLE
        handleResponseIpsChip()
    }

    private fun handleResponseIpsChip() {
        if (transaction.response.isEmpty()) {
            b.dnsBlockIpsChip.visibility = View.GONE
            return
        }

        val ips = transaction.response.split(",")
        val ipCount = ips.count()
        if (ipCount > 1) b.dnsBlockIpsChip.text = getString(R.string.dns_btm_sheet_chip,
                                                            (ipCount - 1).toString())
        else b.dnsBlockIpsChip.visibility = View.GONE

        b.dnsBlockIpsChip.setOnClickListener {
            showIpsDialog()
        }
    }

    private fun handleBlocklistChip() {
        b.dnsBlockBlocklistChip.visibility = View.VISIBLE
        val group: Multimap<String, String> = HashMultimap.create()

        transaction.getBlocklists().forEach {
            val items = it.split(":")
            if (items.count() <= 1) return@forEach

            group.put(items[0], items[1])
        }

        val groupCount = group.keys().distinct().count()
        if (groupCount > 1) {
            b.dnsBlockBlocklistChip.text = "${group.keys().first()} +${groupCount - 1}"
        } else {
            b.dnsBlockBlocklistChip.text = group.keys().first()
        }

        b.dnsBlockBlocklistChip.setOnClickListener {
            showBlocklistDialog(group)
        }
    }

    private fun showBlocklistDialog(groupNames: Multimap<String, String>) {
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

    private fun showIpsDialog() {
        val dialogBinding = DialogIpDetailsLayoutBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(dialogBinding.root)
        val width = (resources.displayMetrics.widthPixels * 0.75).toInt()
        val height = (resources.displayMetrics.heightPixels * 0.5).toInt()
        dialog.window?.setLayout(width, height)

        if (b.dnsBlockFavIcon.isVisible) dialogBinding.ipDetailsFavIcon.setImageDrawable(
            b.dnsBlockFavIcon.drawable)
        else dialogBinding.ipDetailsFavIcon.visibility = View.GONE

        dialogBinding.ipDetailsFqdnTxt.text = "${transaction.queryStr}\n"
        dialogBinding.ipDetailsIpDetailsTxt.text = formatIps(transaction.response)

        dialogBinding.infoRulesDialogCancelImg.setOnClickListener {
            dialog.dismiss()
        }
        dialog.show()
    }

    private fun formatIps(ips: String): Spanned {
        val list = ips.split(",")
        var text = ""

        list.forEach {
            text += getString(R.string.dns_btm_sheet_dialog_ips, Utilities.getFlag(it.slice(0..2)),
                              it)
        }
        return updateHtmlEncodedText(text)
    }

    private fun formatText(groupNames: Multimap<String, String>): Spanned {
        var text = ""
        groupNames.keys().distinct().forEach {
            val heading = it.replaceFirstChar { a ->
                if (a.isLowerCase()) a.titlecase(Locale.getDefault()) else a.toString()
            }
            text += getString(R.string.dns_btm_sheet_dialog_message, heading,
                              groupNames.get(it).count().toString(),
                              TextUtils.join(", ", groupNames.get(it)))
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
        if (!persistentState.fetchFavIcon || transaction.groundedQuery()) return

        val trim = transaction.queryStr.dropLast(1)
        val url = "${FavIconDownloader.FAV_ICON_URL}$trim.ico"
        val domainURL = getETldPlus1(trim)
        val glideURL = "${FavIconDownloader.FAV_ICON_URL}$domainURL.ico"
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
