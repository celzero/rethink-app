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
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
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
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.databinding.BottomSheetDnsLogBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.databinding.DialogIpDetailsLayoutBinding
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.BuildConfig.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS_LOG
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.fetchColor
import com.celzero.bravedns.util.Utilities.Companion.updateHtmlEncodedText
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import com.google.gson.Gson
import java.util.*
import org.koin.android.ext.android.inject

class DnsBlocklistBottomSheetFragment : BottomSheetDialogFragment() {
    private var _binding: BottomSheetDnsLogBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b
        get() = _binding!!

    private var transaction: DnsLog? = null

    private val persistentState by inject<PersistentState>()

    override fun getTheme(): Int =
        Themes.getBottomsheetCurrentTheme(isDarkThemeOn(), persistentState.theme)

    companion object {
        const val INSTANCE_STATE_DNSLOGS = "DNSLOGS"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetDnsLogBinding.inflate(inflater, container, false)
        return b.root
    }

    private fun isDarkThemeOn(): Boolean {
        return resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val data = arguments?.getString(INSTANCE_STATE_DNSLOGS)
        transaction = Gson().fromJson(data, DnsLog::class.java)

        if (transaction == null) {
            Log.w(LOG_TAG_DNS_LOG, "Transaction detail missing, dismiss the dialog")
            this.dismiss()
            return
        }

        b.dnsBlockUrl.text = transaction!!.queryStr
        b.dnsBlockIpAddress.text = getResponseIp()
        b.dnsBlockConnectionFlag.text = transaction!!.flag
        b.dnsBlockIpLatency.text =
            getString(R.string.dns_btm_latency_ms, transaction!!.latency.toString())

        handleCustomDomainUi()
        displayFavIcon()
        displayDnsTransactionDetails()
        displayRecordTypeChip()
        setupClickListeners()
    }

    private fun getResponseIp(): String {
        val ips = transaction!!.response.split(",")
        return ips[0]
    }

    private fun displayRecordTypeChip() {
        if (transaction == null) {
            Log.w(LOG_TAG_DNS_LOG, "Transaction detail missing, no need to update chips")
            return
        }

        if (transaction!!.typeName.isEmpty()) {
            b.dnsRecordTypeChip.visibility = View.GONE
            return
        }

        b.dnsRecordTypeChip.visibility = View.VISIBLE
        b.dnsRecordTypeChip.text = getString(R.string.dns_btm_record_type, transaction!!.typeName)
    }

    private fun handleCustomDomainUi() {
        if (transaction == null) {
            Log.w(LOG_TAG_DNS_LOG, "Transaction detail missing, no need to update ui")
            return
        }

        when (DomainRulesManager.matchesDomain(transaction!!.queryStr)) {
            DomainRulesManager.DomainStatus.BLOCK -> {
                b.dnsBlockToggleGroup.check(b.dnsBlockTgDeny.id)
                enableToggleButton(b.dnsBlockTgDeny)
            }
            DomainRulesManager.DomainStatus.WHITELIST -> {
                b.dnsBlockToggleGroup.check(b.dnsBlockTgAllow.id)
                enableToggleButton(b.dnsBlockTgAllow)
            }
            DomainRulesManager.DomainStatus.NONE -> {
                b.dnsBlockToggleGroup.check(b.dnsBlockTgNoRule.id)
                enableToggleButton(b.dnsBlockTgNoRule)
            }
        }
    }

    private fun setupClickListeners() {
        b.dnsBlockToggleGroup.addOnButtonCheckedListener { group, checkedId, isChecked ->
            val btn: MaterialButton = b.dnsBlockToggleGroup.findViewById(checkedId)
            if (!isChecked) {
                disableToggleButton(btn)
                return@addOnButtonCheckedListener
            }

            group.check(checkedId)
            enableToggleButton(btn)
            applyDnsRule(findSelectedDnsRule(getTag(btn.tag)))
        }
    }

    private fun applyDnsRule(status: DomainRulesManager.DomainStatus) {
        if (transaction == null) {
            Log.w(LOG_TAG_DNS_LOG, "Transaction detail missing, no need to apply dns rules")
            return
        }

        DomainRulesManager.applyStatus(
            transaction!!.queryStr,
            transaction!!.responseIps,
            DomainRulesManager.DomainType.DOMAIN,
            status
        )
    }

    private fun getTag(tag: Any): Int {
        return tag.toString().toIntOrNull() ?: 0
    }

    private fun enableToggleButton(button: MaterialButton) {
        button.setTextColor(fetchColor(requireContext(), R.attr.secondaryTextColor))
    }

    private fun disableToggleButton(button: MaterialButton) {
        button.setTextColor(fetchColor(requireContext(), R.attr.primaryTextColor))
    }

    private fun findSelectedDnsRule(ruleId: Int): DomainRulesManager.DomainStatus {
        return when (ruleId) {
            DomainRulesManager.DomainStatus.WHITELIST.id -> {
                DomainRulesManager.DomainStatus.WHITELIST
            }
            DomainRulesManager.DomainStatus.BLOCK.id -> {
                DomainRulesManager.DomainStatus.BLOCK
            }
            else -> {
                DomainRulesManager.DomainStatus.NONE
            }
        }
    }

    private fun displayDnsTransactionDetails() {
        if (transaction == null) {
            Log.w(LOG_TAG_DNS_LOG, "Transaction detail missing, no need to update ui")
            return
        }

        displayDescription()

        if (transaction!!.groundedQuery()) {
            handleBlocklistChip()
            b.dnsBlockIpsChip.visibility = View.GONE
            return
        }

        handleResponseIpsChip()
    }

    private fun handleResponseIpsChip() {
        b.dnsBlockIpsChip.visibility = View.VISIBLE
        lightenUpChip(b.dnsBlockIpsChip, true)

        if (transaction!!.responseIps.isEmpty()) {
            b.dnsBlockIpsChip.text = getString(R.string.dns_btm_sheet_chip_allowed)
            return
        }

        val ips = transaction!!.responseIps.split(",")
        val ipCount = ips.count()

        if (ipCount == 1) {
            b.dnsBlockIpsChip.text = getString(R.string.dns_btm_sheet_chip_allowed)
            return
        }

        b.dnsBlockIpsChip.text = getString(R.string.dns_btm_sheet_chip, (ipCount - 1).toString())

        b.dnsBlockIpsChip.setOnClickListener { showIpsDialog() }
    }

    private fun handleBlocklistChip() {
        if (transaction == null) {
            Log.w(LOG_TAG_DNS_LOG, "Transaction detail missing, no need to update chips")
            return
        }

        b.dnsBlockBlocklistChip.visibility = View.VISIBLE
        lightenUpChip(b.dnsBlockBlocklistChip, false)

        // show no-answer chip
        if (transaction!!.unansweredQuery()) {
            b.dnsBlockBlocklistChip.text = getString(R.string.dns_btm_sheet_chip_no_answer)
            return
        }

        if (!transaction!!.hasBlocklists()) {
            b.dnsBlockBlocklistChip.text = getString(R.string.dns_btm_sheet_chip_blocked)
            return
        }

        val group: Multimap<String, String> = HashMultimap.create()

        transaction!!.getBlocklists().forEach {
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

        b.dnsBlockBlocklistChip.setOnClickListener { showBlocklistDialog(group) }
    }

    // ref chip transparency:
    // https://github.com/material-components/material-components-android/issues/367
    // Chips also have a chipSurfaceColor attribute that you can set to change that surface color.
    private fun lightenUpChip(chip: Chip, isPositive: Boolean) {
        if (isPositive) {
            chip.setTextColor(fetchColor(requireContext(), R.attr.chipTextPositive))
            val colorFilter =
                PorterDuffColorFilter(
                    fetchColor(requireContext(), R.attr.chipTextPositive),
                    PorterDuff.Mode.SRC_IN
                )
            chip.chipBackgroundColor =
                ColorStateList.valueOf(fetchColor(requireContext(), R.attr.chipBgColorPositive))
            chip.chipIcon?.colorFilter = colorFilter
        } else {
            chip.setTextColor(fetchColor(requireContext(), R.attr.chipTextNegative))
            val colorFilter =
                PorterDuffColorFilter(
                    fetchColor(requireContext(), R.attr.chipTextNegative),
                    PorterDuff.Mode.SRC_IN
                )
            chip.chipBackgroundColor =
                ColorStateList.valueOf(fetchColor(requireContext(), R.attr.chipBgColorNegative))
            chip.chipIcon?.colorFilter = colorFilter
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

        dialogBinding.infoRulesDialogCancelImg.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showIpsDialog() {
        if (transaction == null) {
            Log.w(LOG_TAG_DNS_LOG, "Transaction detail missing, not showing dialog")
            return
        }

        val dialogBinding = DialogIpDetailsLayoutBinding.inflate(layoutInflater)
        val dialog = Dialog(requireContext())
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setContentView(dialogBinding.root)
        val width = (resources.displayMetrics.widthPixels * 0.75).toInt()
        val height = (resources.displayMetrics.heightPixels * 0.5).toInt()
        dialog.window?.setLayout(width, height)

        if (b.dnsBlockFavIcon.isVisible)
            dialogBinding.ipDetailsFavIcon.setImageDrawable(b.dnsBlockFavIcon.drawable)
        else dialogBinding.ipDetailsFavIcon.visibility = View.GONE

        dialogBinding.ipDetailsFqdnTxt.text = transaction!!.queryStr
        dialogBinding.ipDetailsIpDetailsTxt.text = formatIps(transaction!!.responseIps)

        dialogBinding.infoRulesDialogCancelImg.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun formatIps(ips: String): Spanned {
        val list = ips.split(",")
        var text = ""

        list.forEach {
            text +=
                getString(R.string.dns_btm_sheet_dialog_ips, Utilities.getFlag(it.slice(0..2)), it)
        }
        return updateHtmlEncodedText(text)
    }

    private fun formatText(groupNames: Multimap<String, String>): Spanned {
        var text = ""
        groupNames.keys().distinct().forEach {
            val heading =
                it.replaceFirstChar { a ->
                    if (a.isLowerCase()) a.titlecase(Locale.getDefault()) else a.toString()
                }
            text +=
                getString(
                    R.string.dns_btm_sheet_dialog_message,
                    heading,
                    groupNames.get(it).count().toString(),
                    TextUtils.join(", ", groupNames.get(it))
                )
        }
        text = text.replace(",", ", ")
        return HtmlCompat.fromHtml(text, HtmlCompat.FROM_HTML_MODE_LEGACY)
    }

    private fun displayDescription() {
        if (transaction == null) {
            Log.w(LOG_TAG_DNS_LOG, "Transaction detail missing, no need to update ui")
            return
        }

        val uptime =
            DateUtils.getRelativeTimeSpanString(
                    transaction!!.time,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
                .toString()
        if (transaction!!.isBlocked) {
            showBlockedState(uptime)
        } else {
            showResolvedState(uptime)
        }
    }

    private fun showResolvedState(uptime: String) {
        if (transaction == null) {
            Log.w(LOG_TAG_DNS_LOG, "Transaction detail missing, no need to update ui")
            return
        }

        if (transaction!!.isAnonymized()) { // anonymized queries answered by dns-crypt
            val text = getString(R.string.dns_btm_resolved_crypt, uptime, transaction!!.serverIP)
            b.dnsBlockBlockedDesc.text = updateHtmlEncodedText(text)
        } else if (
            transaction!!.isLocallyAnswered()
        ) { // usually happens when there is a network failure
            b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_resolved_doh_no_server, uptime)
        } else {
            b.dnsBlockBlockedDesc.text =
                getString(R.string.dns_btm_resolved_doh, uptime, transaction!!.serverIP)
        }
    }

    private fun showBlockedState(uptime: String) {
        if (transaction == null) {
            Log.w(LOG_TAG_DNS_LOG, "Transaction detail missing, no need to update ui")
            return
        }

        if (
            transaction!!.isLocallyAnswered()
        ) { // usually true when query blocked by on-device blocklists
            b.dnsBlockBlockedDesc.text = getString(R.string.bsct_conn_block_desc_device, uptime)
        } else {
            b.dnsBlockBlockedDesc.text =
                getString(R.string.bsct_conn_block_desc, uptime, transaction!!.serverIP)
        }
    }

    private fun displayFavIcon() {
        if (transaction == null) {
            Log.w(LOG_TAG_DNS_LOG, "Transaction detail missing, no need to update ui")
            return
        }

        if (!persistentState.fetchFavIcon || transaction!!.groundedQuery()) return

        val trim = transaction!!.queryStr.dropLast(1)

        // no need to check in glide cache if the value is available in failed cache
        if (FavIconDownloader.isUrlAvailableInFailedCache(trim) != null) {
            b.dnsBlockFavIcon.visibility = View.GONE
        } else {
            // Glide will cache the icons against the urls. To extract the fav icon from the
            // cache, first verify that the cache is available with the next dns url.
            // If it is not available then glide will throw an error, do the duckduckgo
            // url check in that case.
            lookupForImageNextDns(trim)
        }
    }

    // FIXME: the glide app code to fetch the image from the cache is repeated in
    // both lookupForImageNextDns() and lookupForImageDuckduckgo().
    // come up with common method to handle this
    private fun lookupForImageNextDns(query: String) {
        val url = FavIconDownloader.constructFavIcoUrlNextDns(query)
        val duckduckgoUrl = FavIconDownloader.constructFavUrlDuckDuckGo(query)
        val duckduckgoDomainURL = FavIconDownloader.getDomainUrlFromFdqnDuckduckgo(query)
        try {
            if (DEBUG)
                Log.d(LOG_TAG_DNS_LOG, "Glide, TransactionViewHolder lookupForImageNextDns :$url")
            val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
            GlideApp.with(requireContext().applicationContext)
                .load(url)
                .onlyRetrieveFromCache(true)
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                .error(lookupForImageDuckduckgo(duckduckgoUrl, duckduckgoDomainURL))
                .transition(DrawableTransitionOptions.withCrossFade(factory))
                .into(
                    object : CustomViewTarget<ImageView, Drawable>(b.dnsBlockFavIcon) {
                        override fun onLoadFailed(errorDrawable: Drawable?) {
                            if (!isAdded) return

                            b.dnsBlockFavIcon.visibility = View.GONE
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            transition: Transition<in Drawable>?
                        ) {
                            if (DEBUG)
                                Log.d(
                                    LOG_TAG_DNS_LOG,
                                    "Glide - CustomViewTarget onResourceReady() nextdns: $url"
                                )
                            if (!isAdded) return

                            b.dnsBlockFavIcon.visibility = View.VISIBLE
                            b.dnsBlockFavIcon.setImageDrawable(resource)
                        }

                        override fun onResourceCleared(placeholder: Drawable?) {
                            if (!isAdded) return

                            b.dnsBlockFavIcon.visibility = View.GONE
                        }
                    }
                )
        } catch (e: Exception) {
            if (DEBUG)
                Log.d(LOG_TAG_DNS_LOG, "Glide - TransactionViewHolder Exception() -${e.message}")
            lookupForImageDuckduckgo(duckduckgoUrl, duckduckgoDomainURL)
        }
    }

    private fun lookupForImageDuckduckgo(url: String, domainUrl: String) {
        try {
            if (DEBUG)
                Log.d(
                    LOG_TAG_DNS_LOG,
                    "Glide - TransactionViewHolder lookupForImageDuckduckgo: $url, $domainUrl"
                )
            val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
            GlideApp.with(requireContext().applicationContext)
                .load(url)
                .onlyRetrieveFromCache(true)
                .diskCacheStrategy(DiskCacheStrategy.DATA)
                .override(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL)
                .error(
                    GlideApp.with(requireContext().applicationContext)
                        .load(domainUrl)
                        .onlyRetrieveFromCache(true)
                )
                .transition(DrawableTransitionOptions.withCrossFade(factory))
                .into(
                    object : CustomViewTarget<ImageView, Drawable>(b.dnsBlockFavIcon) {
                        override fun onLoadFailed(errorDrawable: Drawable?) {
                            if (!isAdded) return

                            b.dnsBlockFavIcon.visibility = View.GONE
                        }

                        override fun onResourceReady(
                            resource: Drawable,
                            transition: Transition<in Drawable>?
                        ) {
                            if (DEBUG)
                                Log.d(
                                    LOG_TAG_DNS_LOG,
                                    "Glide - CustomViewTarget onResourceReady() -$url"
                                )
                            if (!isAdded) return

                            b.dnsBlockFavIcon.visibility = View.VISIBLE
                            b.dnsBlockFavIcon.setImageDrawable(resource)
                        }

                        override fun onResourceCleared(placeholder: Drawable?) {
                            if (!isAdded) return

                            b.dnsBlockFavIcon.visibility = View.GONE
                        }
                    }
                )
        } catch (e: Exception) {
            if (DEBUG)
                Log.d(LOG_TAG_DNS_LOG, "Glide - TransactionViewHolder Exception() -${e.message}")
            if (!isAdded) return

            b.dnsBlockFavIcon.visibility = View.GONE
        }
    }
}
