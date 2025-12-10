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
package com.celzero.bravedns.ui.bottomsheet

import Logger
import Logger.LOG_TAG_DNS
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Spanned
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.AdapterView
import android.widget.ImageView
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.request.transition.Transition
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.FirewallStatusSpinnerAdapter
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.database.EventSource
import com.celzero.bravedns.database.EventType
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.BottomSheetDnsLogBinding
import com.celzero.bravedns.databinding.DialogInfoRulesLayoutBinding
import com.celzero.bravedns.databinding.DialogIpDetailsLayoutBinding
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.service.DomainRulesManager
import com.celzero.bravedns.service.EventLogger
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.activity.DomainConnectionsActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.ResourceRecordTypes
import com.celzero.bravedns.util.Themes
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.UIUtils.htmlToSpannedText
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.getIcon
import com.celzero.bravedns.util.Utilities.isAtleastQ
import com.celzero.bravedns.util.useTransparentNoDimBackground
import com.celzero.bravedns.viewmodel.DomainConnectionsViewModel
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.collect.HashMultimap
import com.google.common.collect.Multimap
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.ext.android.inject
import java.util.Locale

class DnsBlocklistBottomSheet : BottomSheetDialogFragment() {
    private var _binding: BottomSheetDnsLogBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val b
        get() = _binding!!

    private var log: DnsLog? = null

    private val persistentState by inject<PersistentState>()
    private val eventLogger by inject<EventLogger>()

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

    override fun onStart() {
        super.onStart()
        dialog?.useTransparentNoDimBackground()
    }

    // enum to represent the status of the domain in the chip (ui)
    enum class BlockType(val id: Int) {
        ALLOWED(0),
        BLOCKED(1),
        MAYBE_BLOCKED(2),
        NONE(3)
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
        dialog?.window?.let { window ->
            if (isAtleastQ()) {
                val controller = WindowInsetsControllerCompat(window, window.decorView)
                controller.isAppearanceLightNavigationBars = false
                window.isNavigationBarContrastEnforced = false
            }
        }

        val data = arguments?.getString(INSTANCE_STATE_DNSLOGS)
        log = Gson().fromJson(data, DnsLog::class.java)

        if (log == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, dismiss the dialog")
            this.dismiss()
            return
        }

        b.bsdlDomainRuleDesc.text = htmlToSpannedText(getString(R.string.bsdl_block_desc))
        b.dnsBlockUrl.text = "${log?.queryStr.orEmpty()}      ❯"
        b.dnsBlockIpAddress.text = getResponseIp()
        b.dnsBlockConnectionFlag.text = log?.flag.orEmpty()
        b.dnsBlockIpLatency.text = getString(R.string.dns_btm_latency_ms, log?.ttl?.toString().orEmpty())
        if (Logger.LoggerLevel.fromId(persistentState.goLoggerLevel.toInt())
                .isLessThan(Logger.LoggerLevel.DEBUG)
        ) {
            b.dnsMessage.text = "${log?.msg}; ${log?.proxyId}; ${log?.relayIP}"
        } else {
            b.dnsMessage.text = log?.msg.orEmpty()
        }

        displayFavIcon()
        displayDnsTransactionDetails()
        displayRecordTypeChip()
        setupClickListeners()
        updateAppDetails(log)
        updateRulesUi(log?.queryStr.orEmpty())

        val region = log?.region.orEmpty()
        if (region.isNotEmpty()) {
            b.dnsRegion.visibility = View.VISIBLE
            b.dnsRegion.text = region
        } else {
            b.dnsRegion.visibility = View.GONE
        }
    }

    private fun updateAppDetails(log: DnsLog?) {
        if (log == null) {
            b.dnsAppNameHeader.visibility = View.GONE
            return
        }

        if (log.appName.isNotEmpty() && log.packageName.isNotEmpty()) {
            b.dnsAppNameHeader.visibility = View.VISIBLE
            b.dnsAppName.text = log.appName
            b.dnsAppIcon.setImageDrawable(getIcon(requireContext(), log.packageName, log.appName))
            return
        }

        io {
            val appNames = FirewallManager.getAppNamesByUid(log.uid)
            if (appNames.isEmpty()) {
                uiCtx {
                    b.dnsAppNameHeader.visibility = View.GONE
                }
                return@io
            }
            val pkgName = FirewallManager.getPackageNameByAppName(appNames[0])

            val appCount = appNames.count()
            uiCtx {
                if (appCount >= 1) {
                    b.dnsAppName.text =
                        if (appCount >= 2) {
                            getString(
                                R.string.ctbs_app_other_apps,
                                appNames[0],
                                appCount.minus(1).toString()
                            )
                        } else {
                            appNames[0]
                        }
                    if (pkgName == null) return@uiCtx
                    b.dnsAppIcon.setImageDrawable(
                        getIcon(requireContext(), pkgName, log.appName)
                    )
                } else {
                    // apps which are not available in cache are treated as non app.
                    // TODO: check packageManager#getApplicationInfo() for appInfo
                    b.dnsAppNameHeader.visibility = View.GONE
                }
            }
        }
    }

    private fun getResponseIp(): String {
        val ips = log?.response?.split(",") ?: return ""
        return ips.firstOrNull() ?: ""
    }

    private fun updateRulesUi(domain: String) {
        val d = domain.dropLastWhile { it == '.' }.lowercase()
        val status = DomainRulesManager.getDomainRule(d, Constants.UID_EVERYBODY)
        b.bsdlDomainRuleSpinner.setSelection(status.id)
    }

    private fun displayRecordTypeChip() {
        if (log == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update chips")
            return
        }

        if (log?.typeName.orEmpty().isEmpty()) {
            b.dnsRecordTypeChip.visibility = View.GONE
            return
        }

        b.dnsRecordTypeChip.visibility = View.VISIBLE
        b.dnsRecordTypeChip.text = getString(R.string.dns_btm_record_type, log?.typeName.orEmpty())
    }

    private fun setupClickListeners() {

        b.dnsBlockHeaderContainer.setOnClickListener {
            log?.queryStr?.let { query -> startDomainConnectionsActivity(query) }
        }

        b.dnsBlockUrl.setOnClickListener {
            log?.queryStr?.let { query -> startDomainConnectionsActivity(query) }
        }

        b.bsdlDomainRuleSpinner.adapter =
            FirewallStatusSpinnerAdapter(
                requireContext(),
                DomainRulesManager.Status.getLabel(requireContext())
            )
        b.bsdlDomainRuleSpinner.onItemSelectedListener =
            object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: AdapterView<*>?,
                    view: View?,
                    position: Int,
                    id: Long
                ) {
                    val iv = view?.findViewById<AppCompatImageView>(R.id.spinner_icon)
                    iv?.visibility = View.VISIBLE
                    val status = DomainRulesManager.Status.getStatus(position)

                    // no need to apply rule, if prev selection and current selection are same
                    val queryStr = log?.queryStr ?: return
                    if (
                        DomainRulesManager.getDomainRule(queryStr, Constants.UID_EVERYBODY) ==
                            status
                    ) {
                        return
                    }

                    applyDnsRule(status)
                }

                override fun onNothingSelected(parent: AdapterView<*>?) {}
            }
    }

    private fun applyDnsRule(status: DomainRulesManager.Status) {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to apply dns rules")
            return
        }
        io {
            DomainRulesManager.changeStatus(
                currentLog.queryStr,
                Constants.UID_EVERYBODY,
                currentLog.responseIps,
                DomainRulesManager.DomainType.DOMAIN,
                status
            )
            logEvent("DNS domain rule change", "${currentLog.queryStr} to ${status.name}")
        }
    }

    private fun displayDnsTransactionDetails() {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update ui")
            return
        }

        displayDescription()

        if (currentLog.groundedQuery() || currentLog.hasBlocklists() || currentLog.upstreamBlock) {
            handleBlocklistChip()
            b.dnsBlockIpsChip.visibility = View.GONE
            return
        }

        handleResponseIpsChip()
    }

    private fun handleResponseIpsChip() {
        val currentLog = log ?: return
        b.dnsBlockIpsChip.visibility = View.VISIBLE
        lightenUpChip(b.dnsBlockIpsChip, BlockType.ALLOWED)
        if (ResourceRecordTypes.mayContainIp(currentLog.typeName) && currentLog.responseIps == "") {
            b.dnsBlockIpsChip.text = getString(R.string.dns_btm_sheet_chip_no_answer)
            return
        }

        try {
            val ips = currentLog.responseIps.split(",")
            val ipCount = ips.count()

            if (ipCount == 1) {
                b.dnsBlockIpsChip.text = getString(R.string.lbl_allowed)
                return
            }

            b.dnsBlockIpsChip.text =
                getString(R.string.dns_btm_sheet_chip, (ipCount - 1).toString())

            b.dnsBlockIpsChip.setOnClickListener { showIpsDialog() }
        } catch (e: Exception) {
            b.dnsBlockIpsChip.text = getString(R.string.dns_btm_sheet_chip_no_answer)
            return
        }
    }

    private fun handleBlocklistChip() {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update chips")
            return
        }

        b.dnsBlockBlocklistChip.visibility = View.VISIBLE

        if (currentLog.isBlocked) {
            lightenUpChip(b.dnsBlockBlocklistChip, BlockType.BLOCKED)
        } else if (determineMaybeBlocked()) {
            lightenUpChip(b.dnsBlockBlocklistChip, BlockType.MAYBE_BLOCKED)
        } else {
            lightenUpChip(b.dnsBlockBlocklistChip, BlockType.NONE)
        }

        // show blocklist chip
        if (currentLog.hasBlocklists()) {
            showBlocklistChip()
            return
        }

        // show no-answer chip
        if (currentLog.unansweredQuery()) {
            b.dnsBlockBlocklistChip.text = getString(R.string.dns_btm_sheet_chip_no_answer)
            return
        }

        // show chip as blocked
        b.dnsBlockBlocklistChip.text = getString(R.string.lbl_blocked)
        return
    }

    private fun determineMaybeBlocked(): Boolean {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update chips")
            return false
        }

        return currentLog.upstreamBlock || currentLog.blockLists.isNotEmpty()
    }

    private fun showBlocklistChip() {
        val group: Multimap<String, String> = HashMultimap.create()

        log?.getBlocklists()?.forEach {
            val items = it.split(":")
            if (items.count() <= 1) return@forEach

            group.put(items[0], items[1])
        }

        val groupCount = group.keys().distinct().count()
        if (groupCount > 1) {
            b.dnsBlockBlocklistChip.text = "${group.keys().firstOrNull()} +${groupCount - 1}"
        } else {
            b.dnsBlockBlocklistChip.text = group.keys().firstOrNull()
        }

        b.dnsBlockBlocklistChip.setOnClickListener { showBlocklistDialog(group) }
    }

    // ref chip transparency:
    // https://github.com/material-components/material-components-android/issues/367
    // Chips also have a chipSurfaceColor attribute that you can set to change that surface color.
    private fun lightenUpChip(chip: Chip, type: BlockType) {
        when (type) {
            BlockType.BLOCKED -> {
                chip.setTextColor(fetchColor(requireContext(), R.attr.chipTextNegative))
                chip.chipBackgroundColor =
                    ColorStateList.valueOf(fetchColor(requireContext(), R.attr.chipBgColorNegative))
            }
            BlockType.ALLOWED -> {
                chip.setTextColor(fetchColor(requireContext(), R.attr.chipTextPositive))
                chip.chipBackgroundColor =
                    ColorStateList.valueOf(fetchColor(requireContext(), R.attr.chipBgColorPositive))
            }
            BlockType.MAYBE_BLOCKED -> {
                chip.setTextColor(fetchColor(requireContext(), R.attr.chipTextNeutral))
                chip.chipBackgroundColor =
                    ColorStateList.valueOf(fetchColor(requireContext(), R.attr.chipBgColorNeutral))
            }
            BlockType.NONE -> {
                chip.setTextColor(fetchColor(requireContext(), R.attr.chipTextNegative))
                chip.chipBackgroundColor =
                    ColorStateList.valueOf(fetchColor(requireContext(), R.attr.chipBgColorNegative))
            }
        }
    }

    private fun startDomainConnectionsActivity(domain: String) {
        val intent = Intent(requireContext(), DomainConnectionsActivity::class.java)
        intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_TYPE, DomainConnectionsActivity.InputType.DOMAIN.type)
        intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_DOMAIN, domain)
        intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_TIME_CATEGORY, DomainConnectionsViewModel.TimeCategory.SEVEN_DAYS.value)
        intent.putExtra(DomainConnectionsActivity.INTENT_EXTRA_IS_BLOCKED, log?.isBlocked ?: false)
        requireContext().startActivity(intent)
        this.dismiss()
    }

    private fun showBlocklistDialog(groupNames: Multimap<String, String>) {
        val dialogBinding = DialogInfoRulesLayoutBinding.inflate(layoutInflater)
        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim).setView(dialogBinding.root)
        val dialog = builder.create()
        dialog.setCancelable(true)
        dialogBinding.infoRulesDialogRulesDesc.text = formatText(groupNames)
        dialogBinding.infoRulesDialogRulesTitle.visibility = View.GONE

        dialogBinding.infoRulesDialogCancelImg.setOnClickListener { dialog.dismiss() }
        dialog.show()
    }

    private fun showIpsDialog() {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, not showing dialog")
            return
        }

        val dialogBinding = DialogIpDetailsLayoutBinding.inflate(layoutInflater)

        val builder = MaterialAlertDialogBuilder(requireContext(), R.style.App_Dialog_NoDim).setView(dialogBinding.root)
        val lp = WindowManager.LayoutParams()
        val dialog = builder.create()
        dialog.show()
        lp.copyFrom(dialog.window?.attributes)
        lp.width = WindowManager.LayoutParams.MATCH_PARENT
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT

        dialog.setCancelable(true)
        dialog.window?.attributes = lp

        if (b.dnsBlockFavIcon.isVisible)
            dialogBinding.ipDetailsFavIcon.setImageDrawable(b.dnsBlockFavIcon.drawable)
        else dialogBinding.ipDetailsFavIcon.visibility = View.GONE

        dialogBinding.ipDetailsFqdnTxt.text = currentLog.queryStr
        dialogBinding.ipDetailsIpDetailsTxt.text = formatIps(currentLog.responseIps)

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
        return htmlToSpannedText(text)
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
        return htmlToSpannedText(text)
    }

    private fun displayDescription() {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update ui")
            return
        }

        val uptime =
            DateUtils.getRelativeTimeSpanString(
                    currentLog.time,
                    System.currentTimeMillis(),
                    DateUtils.MINUTE_IN_MILLIS,
                    DateUtils.FORMAT_ABBREV_RELATIVE
                )
                .toString()
        if (currentLog.isBlocked) {
            showBlockedState(uptime)
        } else {
            showResolvedState(uptime)
        }
    }

    private fun showResolvedState(uptime: String) {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update ui")
            return
        }

        if (currentLog.isCached) {
            val txt = if (currentLog.serverIP.isEmpty()) {
                getString(R.string.dns_btm_resolved_doh, uptime, getString(R.string.lbl_cache))
            } else {
                val concat = "${getString(R.string.lbl_cache)} (${currentLog.resolverId}:${currentLog.serverIP})"
                getString(R.string.dns_btm_resolved_doh, uptime, concat)
            }
            b.dnsBlockBlockedDesc.text = txt
            return
        }

        if (currentLog.isAnonymized()) { // anonymized queries answered by dns-crypt / proxies
            val p = currentLog.relayIP.ifEmpty {
                currentLog.proxyId
            }
            val text = getString(R.string.dns_btm_resolved_crypt, uptime, currentLog.serverIP, p)
            b.dnsBlockBlockedDesc.text = htmlToSpannedText(text)
        } else if (currentLog.isLocallyAnswered()) { // usually happens when there is a network failure
            b.dnsBlockBlockedDesc.text = getString(R.string.dns_btm_resolved_doh_no_server, uptime)
        } else {
            b.dnsBlockBlockedDesc.text =
                getString(R.string.dns_btm_resolved_doh, uptime, currentLog.serverIP)
        }
    }

    private fun showBlockedState(uptime: String) {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update ui")
            return
        }

        if (currentLog.isLocallyAnswered()) { // usually true when query blocked by on-device blocklists
            b.dnsBlockBlockedDesc.text = getString(R.string.bsct_conn_block_desc_device, uptime)
        } else {
            b.dnsBlockBlockedDesc.text =
                getString(R.string.bsct_conn_block_desc, uptime, currentLog.serverIP)
        }
    }

    private fun displayFavIcon() {
        val currentLog = log
        if (currentLog == null) {
            Logger.w(LOG_TAG_DNS, "Transaction detail missing, no need to update ui")
            return
        }

        if (!persistentState.fetchFavIcon || currentLog.groundedQuery()) return

        val trim = currentLog.queryStr.dropLastWhile { it == '.' }

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
            Logger.d(LOG_TAG_DNS, "Glide, TransactionViewHolder lookupForImageNextDns :$url")
            val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
            Glide.with(requireContext().applicationContext)
                .load(url)
                .onlyRetrieveFromCache(true)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
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
                            Logger.d(
                                LOG_TAG_DNS,
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
            Logger.d(LOG_TAG_DNS, "Glide - TransactionViewHolder Exception() -${e.message}")
            lookupForImageDuckduckgo(duckduckgoUrl, duckduckgoDomainURL)
        }
    }

    private fun lookupForImageDuckduckgo(url: String, domainUrl: String) {
        try {
            Logger.d(
                LOG_TAG_DNS,
                "Glide - TransactionViewHolder lookupForImageDuckduckgo: $url, $domainUrl"
            )
            val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
            Glide.with(requireContext().applicationContext)
                .load(url)
                .onlyRetrieveFromCache(true)
                .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                .error(
                    Glide.with(requireContext().applicationContext)
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
                            Logger.d(
                                LOG_TAG_DNS,
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
            Logger.d(LOG_TAG_DNS, "Glide - TransactionViewHolder Exception() -${e.message}")
            if (!isAdded) return

            b.dnsBlockFavIcon.visibility = View.GONE
        }
    }

    private fun logEvent(msg: String, details: String) {
        eventLogger.log(EventType.FW_RULE_MODIFIED, Severity.LOW, msg, EventSource.UI, false, details)
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
