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

package com.celzero.bravedns.adapter

import Logger
import Logger.LOG_TAG_DNS
import Logger.LOG_TAG_UI
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.request.transition.Transition
import com.celzero.bravedns.R
import com.celzero.bravedns.adapter.DnsLogAdapter.DnsLogViewHolder
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.databinding.ListItemDnsLogBinding
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.ui.bottomsheet.DnsBlocklistBottomSheet
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Constants.Companion.MAX_ENDPOINT
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities.getDefaultIcon
import com.celzero.bravedns.util.Utilities.getIcon
import com.celzero.firestack.backend.Backend
import com.google.gson.Gson

class DnsLogAdapter(val context: Context, val loadFavIcon: Boolean, val isRethinkDns: Boolean) :
    PagingDataAdapter<DnsLog, DnsLogViewHolder>(DIFF_CALLBACK) {

    companion object {
        private const val TAG = "DnsLogAdapter"
        private const val RTT_SHORT_THRESHOLD_MS = 10 // milliseconds

        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<DnsLog>() {

                override fun areItemsTheSame(prev: DnsLog, curr: DnsLog) =
                    prev.id == curr.id

                override fun areContentsTheSame(prev: DnsLog, curr: DnsLog): Boolean {
                    return prev == curr
                }
            }
    }

    override fun onBindViewHolder(holder: DnsLogViewHolder, position: Int) {
        val log: DnsLog = getItem(position) ?: return

        holder.clear()
        holder.update(log)
        holder.setTag(log)
    }

    override fun getItemViewType(position: Int): Int {
        return R.layout.list_item_dns_log
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DnsLogViewHolder {
        val binding = ListItemDnsLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DnsLogViewHolder(binding)
    }

    inner class DnsLogViewHolder(private val b: ListItemDnsLogBinding): RecyclerView.ViewHolder(b.root) {
        fun clear() {
            b.dnsWallTime.text = ""
            b.dnsFlag.text = ""
            b.dnsQuery.text = ""
            b.dnsAppName.text = ""
            b.dnsIps.text = ""
            b.dnsAppIcon.setImageDrawable(null)
            b.dnsTypeName.text = ""
            b.dnsQueryType.text = ""
            b.dnsUnicodeHint.text = ""
            b.dnsStatusIndicator.visibility = View.INVISIBLE
            b.dnsSummaryLl.visibility = View.GONE
        }

        fun setTag(log: DnsLog?) {
            if (log == null) return

            b.dnsWallTime.tag = log.time
            b.root.tag = log.time
        }

        fun update(log: DnsLog) {
            displayTransactionDetails(log)
            displayAppDetails(log)
            displayLogEntryHint(log)
            displayIcon(log)
            displayUnicodeIfNeeded(log)
            displayDnsType(log)
            b.dnsParentLayout.setOnClickListener { openBottomSheet(log) }
        }

        private fun openBottomSheet(log: DnsLog) {
            if (context !is FragmentActivity) {
                Logger.w(LOG_TAG_UI, "$TAG err opening dns log btm sheet, no ctx to activity")
                return
            }

            val bottomSheetFragment = DnsBlocklistBottomSheet()
            val bundle = Bundle()
            bundle.putString(DnsBlocklistBottomSheet.INSTANCE_STATE_DNSLOGS, Gson().toJson(log))
            bottomSheetFragment.arguments = bundle
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }


        private fun displayLogEntryHint(log: DnsLog) {
            if (log.isBlocked) {
                b.dnsStatusIndicator.visibility = View.VISIBLE
                b.dnsStatusIndicator.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.colorRed_A400)
                )
            } else if (determineMaybeBlocked(log)) {
                b.dnsStatusIndicator.visibility = View.VISIBLE
                val color = fetchColor(context, R.attr.chipTextNeutral)
                b.dnsStatusIndicator.setBackgroundColor(color)
            } else {
                b.dnsStatusIndicator.visibility = View.INVISIBLE
            }
        }

        private fun determineMaybeBlocked(log: DnsLog): Boolean {
            return log.upstreamBlock || log.blockLists.isNotEmpty()
        }

        private fun displayTransactionDetails(log: DnsLog) {
            b.dnsWallTime.text = log.wallTime()

            b.dnsQuery.text = log.queryStr
            b.dnsIps.text = log.responseIps.split(",").firstOrNull() ?: ""
            b.dnsIps.visibility = View.VISIBLE
            // marquee is not working for the textview, hence the workaround.
            b.dnsIps.isSelected = true

            b.dnsLatency.text = context.getString(R.string.dns_query_latency, log.latency.toString())
            b.dnsQueryType.text = log.typeName
        }

        private fun displayUnicodeIfNeeded(log: DnsLog) {
            // rtt -> show rocket if less than 20ms, treat it as rtt
            if (isRoundTripShorter(log.latency, log.isBlocked)) {
                b.dnsUnicodeHint.text =
                    context.getString(
                        R.string.ci_desc,
                        b.dnsUnicodeHint.text,
                        context.getString(R.string.symbol_rocket)
                    )
            }
            // bunny in case rpid as present, key in case of proxy
            // bunny and key indicate conn is proxied, so its enough to show one of them
            if (containsRelayProxy(log.relayIP)) {
                b.dnsUnicodeHint.text =
                    context.getString(
                        R.string.ci_desc,
                        b.dnsUnicodeHint.text,
                        context.getString(R.string.symbol_bunny)
                    )
            } else if (isConnectionProxied(log.proxyId)) {
                b.dnsUnicodeHint.text =
                    context.getString(
                        R.string.ci_desc,
                        b.dnsUnicodeHint.text,
                        context.getString(R.string.symbol_key)
                    )
            }

            // show star if RethinkDNS or RPN is used
            if (isRethinkUsed(log)) {
                b.dnsUnicodeHint.text =
                    context.getString(
                        R.string.ci_desc,
                        b.dnsUnicodeHint.text,
                        getRethinkUnicode(log)
                    )
            } else if (isGoosOrSystemUsed(log)) {
                // show duck icon in case of system or goos transport
                b.dnsUnicodeHint.text =
                    context.getString(
                        R.string.ci_desc,
                        b.dnsUnicodeHint.text,
                        context.getString(R.string.symbol_duck)
                    )
            } else if (isDefaultResolverUsed(log)) {
                // show globe icon in case of default or bootstrap resolver
                b.dnsUnicodeHint.text =
                    context.getString(
                        R.string.ci_desc,
                        b.dnsUnicodeHint.text,
                        context.getString(R.string.symbol_diamond)
                    )
            } else if (containsMultipleIPs(log)) {
                b.dnsUnicodeHint.text =
                    context.getString(
                        R.string.ci_desc,
                        b.dnsUnicodeHint.text,
                        context.getString(R.string.symbol_heavy)
                    )
            }

            if (dnssecIndicatorRequired(log)) {
                if (dnssecOk(log)) {
                    b.dnsUnicodeHint.text =
                        context.getString(
                            R.string.ci_desc,
                            b.dnsUnicodeHint.text,
                            context.getString(R.string.symbol_lock)
                        )
                } else {
                    b.dnsUnicodeHint.text =
                        context.getString(
                            R.string.ci_desc,
                            b.dnsUnicodeHint.text,
                            context.getString(R.string.symbol_unlock)
                        )
                }
            }

            if (b.dnsUnicodeHint.text.isEmpty() && b.dnsQueryType.text.isEmpty()) {
                b.dnsSummaryLl.visibility = View.GONE
            } else {
                b.dnsSummaryLl.visibility = View.VISIBLE
            }
        }

        private fun dnssecIndicatorRequired(log: DnsLog): Boolean {
            // dnssec indicator is shown only for complete transactions
            if (log.status != Transaction.Status.COMPLETE.name) {
                return false
            }

            return log.dnssecOk || log.dnssecValid
        }

        private fun dnssecOk(log: DnsLog): Boolean {
            // dnssec ok is true only when both dnssecOk and dnssecValid are true
            return log.dnssecOk && log.dnssecValid
        }

        private fun isRoundTripShorter(rtt: Long, blocked: Boolean): Boolean {
            return rtt in 1..RTT_SHORT_THRESHOLD_MS && !blocked
        }

        private fun containsRelayProxy(rpid: String): Boolean {
            return rpid.isNotEmpty()
        }

        private fun isConnectionProxied(proxy: String?): Boolean {
            if (proxy.isNullOrEmpty()) return false

            return ProxyManager.isNotLocalAndRpnProxy(proxy)
        }

        private fun containsMultipleIPs(log: DnsLog): Boolean {
            return log.responseIps.split(",").size > 1
        }

        private fun isRethinkUsed(log: DnsLog): Boolean {
            if (log.status != Transaction.Status.COMPLETE.name) {
                return false
            }

            // now the rethink dns is added as preferred in the backend, instead of separate
            // id, so match it with Preferred and BlockFree
            return if (isRethinkDns) {
                (log.resolverId.contains(Backend.Preferred) ||
                        log.resolverId.contains(Backend.BlockFree))
            } else {
                false
            }
        }

        private fun isGoosOrSystemUsed(log: DnsLog): Boolean {
            if (log.status != Transaction.Status.COMPLETE.name) {
                return false
            }

            return log.resolverId.contains(Backend.Goos) || log.resolverId.contains(Backend.System)
        }

        private fun isDefaultResolverUsed(log: DnsLog): Boolean {
            if (log.status != Transaction.Status.COMPLETE.name) {
                return false
            }

            // ideally bootstrap will not be sent from go-tun, just in case check for it
            return log.resolverId.contains(Backend.Default) || log.resolverId.contains(Backend.Bootstrap)
        }

        private fun getRethinkUnicode(log: DnsLog): String {
            // resolver check for rethink dns is done before calling this method
            if (log.relayIP.endsWith(Backend.RPN) || log.relayIP == Backend.Auto) return context.getString(
                R.string.symbol_sparkle
            )

            return if (log.serverIP.contains(MAX_ENDPOINT)) {
                context.getString(R.string.symbol_max)
            } else {
                context.getString(R.string.symbol_sky)
            }
        }

        private fun displayAppDetails(log: DnsLog) {
            if (log.appName.isEmpty()) {
                b.dnsAppName.text = context.getString(R.string.network_log_app_name_unknown).uppercase()
            } else {
                b.dnsAppName.text = log.appName
            }
            if (log.packageName.isEmpty() || log.packageName == Constants.EMPTY_PACKAGE_NAME) {
                loadAppIcon(getDefaultIcon(context))
            } else {
                loadAppIcon(getIcon(context, log.packageName))
            }
            return
        }

        private fun loadAppIcon(drawable: Drawable?) {
            Glide.with(context)
                .load(drawable)
                .error(getDefaultIcon(context))
                .into(b.dnsAppIcon)
        }

        private fun displayIcon(log: DnsLog) {
            b.dnsFlag.text = log.flag
            b.dnsFlag.visibility = View.VISIBLE
            b.dnsFavIcon.visibility = View.GONE
            if (!loadFavIcon || log.groundedQuery()) {
                clearFavIcon()
                return
            }

            // no need to check in glide cache if the value is available in failed cache
            if (
                FavIconDownloader.isUrlAvailableInFailedCache(log.queryStr.dropLast(1)) != null
            ) {
                hideFavIcon()
                showFlag()
            } else {
                // Glide will cache the icons against the urls. To extract the fav icon from the
                // cache, first verify that the cache is available with the next dns url.
                // If it is not available then glide will throw an error, do the duckduckgo
                // url check in that case.
                displayNextDnsFavIcon(log)
            }
        }

        private fun displayDnsType(log: DnsLog) {
            val type = Transaction.TransportType.fromOrdinal(log.dnsType)
            when (type) {
                Transaction.TransportType.DOH -> {
                    if (isRethinkDns && isRethinkUsed(log)) {
                        b.dnsTypeName.text = context.getString(R.string.lbl_rdns)
                    } else {
                        b.dnsTypeName.text = context.getString(R.string.other_dns_list_tab1)
                    }
                }
                Transaction.TransportType.DNS_CRYPT -> {
                    b.dnsTypeName.text = context.getString(R.string.lbl_dc_abbr)
                }
                Transaction.TransportType.DNS_PROXY -> {
                    b.dnsTypeName.text = context.getString(R.string.lbl_dp)
                }
                Transaction.TransportType.DOT -> {
                    b.dnsTypeName.text = context.getString(R.string.lbl_dot)
                }
                Transaction.TransportType.ODOH -> {
                    b.dnsTypeName.text = context.getString(R.string.lbl_odoh)
                }
            }
        }

        private fun clearFavIcon() {
            Glide.with(context.applicationContext).clear(b.dnsFavIcon)
        }

        private fun displayNextDnsFavIcon(log: DnsLog) {
            val trim = log.queryStr.dropLastWhile { it == '.' }
            // url to check if the icon is cached from nextdns
            val nextDnsUrl = FavIconDownloader.constructFavIcoUrlNextDns(trim)
            // url to check if the icon is cached from duckduckgo
            val duckduckGoUrl = FavIconDownloader.constructFavUrlDuckDuckGo(trim)
            // subdomain to check if the icon is cached from duckduckgo
            val duckduckgoDomainURL = FavIconDownloader.getDomainUrlFromFdqnDuckduckgo(trim)
            try {
                val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
                Glide.with(context.applicationContext)
                    .load(nextDnsUrl)
                    .onlyRetrieveFromCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .error(
                        // on error, check if the icon is stored in the name of duckduckgo url
                        displayDuckduckgoFavIcon(duckduckGoUrl, duckduckgoDomainURL)
                    )
                    .transition(withCrossFade(factory))
                    .into(
                        object : CustomViewTarget<ImageView, Drawable>(b.dnsFavIcon) {
                            override fun onLoadFailed(errorDrawable: Drawable?) {
                                showFlag()
                                hideFavIcon()
                            }

                            override fun onResourceReady(
                                resource: Drawable,
                                transition: Transition<in Drawable>?
                            ) {
                                hideFlag()
                                showFavIcon(resource)
                            }

                            override fun onResourceCleared(placeholder: Drawable?) {
                                hideFavIcon()
                                showFlag()
                            }
                        }
                    )
            } catch (_: Exception) {
                Logger.d(LOG_TAG_DNS, "err loading icon, load flag instead")
                displayDuckduckgoFavIcon(duckduckGoUrl, duckduckgoDomainURL)
            }
        }

        /**
        * Loads the fav icons from the cache, the icons are cached by favIconDownloader. On
        * failure, will check if there is a icon for top level domain is available in cache. Else,
        * will show the Flag.
        *
        * This method will be executed only when show fav icon setting is turned on.
        */
        private fun displayDuckduckgoFavIcon(url: String, subDomainURL: String) {
            try {
                val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
                Glide.with(context.applicationContext)
                    .load(url)
                    .onlyRetrieveFromCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .error(
                        Glide.with(context.applicationContext)
                            .load(subDomainURL)
                            .onlyRetrieveFromCache(true)
                    )
                    .transition(withCrossFade(factory))
                    .into(
                        object : CustomViewTarget<ImageView, Drawable>(b.dnsFavIcon) {
                            override fun onLoadFailed(errorDrawable: Drawable?) {
                                showFlag()
                                hideFavIcon()
                            }

                            override fun onResourceReady(
                                resource: Drawable,
                                transition: Transition<in Drawable>?
                            ) {
                                hideFlag()
                                showFavIcon(resource)
                            }

                            override fun onResourceCleared(placeholder: Drawable?) {
                                hideFavIcon()
                                showFlag()
                            }
                        }
                    )
            } catch (_: Exception) {
                Logger.d(LOG_TAG_DNS, "$TAG err loading icon, load flag instead")
                showFlag()
                hideFavIcon()
            }
        }

        private fun showFavIcon(drawable: Drawable) {
            b.dnsFavIcon.visibility = View.VISIBLE
            b.dnsFavIcon.setImageDrawable(drawable)
        }

        private fun hideFavIcon() {
            b.dnsFavIcon.visibility = View.GONE
            b.dnsFavIcon.setImageDrawable(null)
        }

        private fun showFlag() {
            b.dnsFlag.visibility = View.VISIBLE
        }

        private fun hideFlag() {
            b.dnsFlag.visibility = View.GONE
        }
    }
}
