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
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
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
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.service.ProxyManager
import com.celzero.bravedns.ui.bottomsheet.DnsBlocklistBottomSheet
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.celzero.bravedns.util.Utilities.getDefaultIcon
import com.celzero.bravedns.util.Utilities.getIcon
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsLogAdapter(val context: Context, val loadFavIcon: Boolean) :
    PagingDataAdapter<DnsLog, DnsLogViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<DnsLog>() {

                override fun areItemsTheSame(prev: DnsLog, curr: DnsLog) =
                    prev.id == curr.id

                override fun areContentsTheSame(prev: DnsLog, curr: DnsLog) =
                    prev == curr
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

            b.dnsParentLayout.setOnClickListener { openBottomSheet(log) }
        }

        private fun openBottomSheet(log: DnsLog) {
            if (context !is FragmentActivity) {
                Logger.w(LOG_TAG_UI, "err opening dns log btm sheet, no ctx to activity")
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
            b.dnsTypeName.text = log.typeName
        }

        private fun displayUnicodeIfNeeded(log: DnsLog) {
            if (isConnectionProxied(log.resolver)) {
                b.dnsUnicodeHint.text =
                    context.getString(
                        R.string.ci_desc,
                        b.dnsUnicodeHint.text,
                        context.getString(R.string.symbol_key)
                    )
            }
            // rtt -> show rocket if less than 20ms, treat it as rtt
            if (isRoundTripShorter(log.latency, log.isBlocked)) {
                b.dnsUnicodeHint.text =
                    context.getString(
                        R.string.ci_desc,
                        b.dnsUnicodeHint.text,
                        context.getString(R.string.symbol_rocket)
                    )
            }
            // bunny in case rpid as present
            if (containsRelayProxy(log.relayIP)) {
                b.dnsUnicodeHint.text =
                    context.getString(
                        R.string.ci_desc,
                        b.dnsUnicodeHint.text,
                        context.getString(R.string.symbol_bunny)
                    )
            }
            if (b.dnsUnicodeHint.text.isEmpty() && b.dnsTypeName.text.isEmpty()) {
                b.dnsSummaryLl.visibility = View.GONE
            } else {
                b.dnsSummaryLl.visibility = View.VISIBLE
            }
        }

        private fun isRoundTripShorter(rtt: Long, blocked: Boolean): Boolean {
            return rtt in 1..10 && !blocked
        }

        private fun containsRelayProxy(rpid: String): Boolean {
            return rpid.isNotEmpty()
        }

        private fun isConnectionProxied(proxy: String?): Boolean {
            if (proxy == null) return false

            return !ProxyManager.isIpnProxy(proxy)
        }

        private fun displayAppDetails(log: DnsLog) {
            io {
                uiCtx {
                    val apps = FirewallManager.getAppNamesByUid(log.uid)
                    val count = apps.count()

                    val appName = when {
                        count > 1 -> context.getString(
                            R.string.ctbs_app_other_apps,
                            apps.first(),
                            "${count - 1}"
                        )
                        count == 0 -> context.getString(R.string.network_log_app_name_unknown)
                        else -> apps.first()
                    }

                    b.dnsAppName.text = appName
                    if (apps.isEmpty()) {
                        loadAppIcon(getDefaultIcon(context))
                    } else {
                        val p = FirewallManager.getPackageNameByAppName(apps[0])
                        if (p == null) {
                            loadAppIcon(getDefaultIcon(context))
                        } else {
                            loadAppIcon(getIcon(context, p))
                        }
                    }
                }
            }
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
            } catch (ignored: Exception) {
                Logger.d(LOG_TAG_DNS, "Error loading icon, load flag instead")
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
            } catch (ignored: Exception) {
                Logger.d(LOG_TAG_DNS, "Error loading icon, load flag instead")
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

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
