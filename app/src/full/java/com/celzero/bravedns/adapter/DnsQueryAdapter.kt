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
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.databinding.TransactionRowBinding
import com.celzero.bravedns.glide.FavIconDownloader
import com.celzero.bravedns.ui.bottomsheet.DnsBlocklistBottomSheet
import com.celzero.bravedns.util.UIUtils.fetchColor
import com.google.gson.Gson

class DnsQueryAdapter(val context: Context, val loadFavIcon: Boolean) :
    PagingDataAdapter<DnsLog, DnsQueryAdapter.TransactionViewHolder>(DIFF_CALLBACK) {

    companion object {
        const val TYPE_TRANSACTION: Int = 1
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<DnsLog>() {

                override fun areItemsTheSame(oldConnection: DnsLog, newConnection: DnsLog) =
                    oldConnection.id == newConnection.id

                override fun areContentsTheSame(oldConnection: DnsLog, newConnection: DnsLog) =
                    oldConnection == newConnection
            }
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val dnsLog: DnsLog = getItem(position) ?: return

        holder.update(dnsLog)
        holder.setTag(dnsLog)
    }

    override fun getItemViewType(position: Int): Int {
        return TYPE_TRANSACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val itemBinding =
            TransactionRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TransactionViewHolder(itemBinding)
    }

    inner class TransactionViewHolder(private val b: TransactionRowBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun update(dnsLog: DnsLog?) {
            if (dnsLog == null) return

            displayDetails(dnsLog)
            displayLogEntryHint(dnsLog)
            displayIcon(dnsLog)

            b.root.setOnClickListener { openBottomSheet(dnsLog) }
        }

        fun setTag(dnsLog: DnsLog?) {
            if (dnsLog == null) return

            b.responseTime.tag = dnsLog.time
            b.root.tag = dnsLog.time
        }

        private fun displayLogEntryHint(dnsLog: DnsLog) {
            // TODO: make the entry as maybe blocked if there is a universal rule blocking the
            // domain / ip
            if (dnsLog.isBlocked) {
                b.queryLogIndicator.visibility = View.VISIBLE
                b.queryLogIndicator.setBackgroundColor(
                    ContextCompat.getColor(context, R.color.colorRed_A400)
                )
            } else if (determineMaybeBlocked(dnsLog)) {
                b.queryLogIndicator.visibility = View.VISIBLE
                val color = fetchColor(context, R.attr.chipTextNeutral)
                b.queryLogIndicator.setBackgroundColor(color)
            } else {
                b.queryLogIndicator.visibility = View.INVISIBLE
            }
        }

        private fun determineMaybeBlocked(dnsLog: DnsLog): Boolean {
            return dnsLog.upstreamBlock || dnsLog.blockLists.isNotEmpty()
        }

        private fun displayIcon(dnsLog: DnsLog) {
            b.flag.text = dnsLog.flag
            b.flag.visibility = View.VISIBLE
            b.favIcon.visibility = View.GONE
            if (!loadFavIcon || dnsLog.groundedQuery()) {
                clearFavIcon()
                return
            }

            // no need to check in glide cache if the value is available in failed cache
            if (
                FavIconDownloader.isUrlAvailableInFailedCache(dnsLog.queryStr.dropLast(1)) != null
            ) {
                hideFavIcon()
                showFlag()
            } else {
                // Glide will cache the icons against the urls. To extract the fav icon from the
                // cache, first verify that the cache is available with the next dns url.
                // If it is not available then glide will throw an error, do the duckduckgo
                // url check in that case.
                displayNextDnsFavIcon(dnsLog)
            }
        }

        private fun clearFavIcon() {
            Glide.with(context.applicationContext).clear(b.favIcon)
        }

        private fun displayDetails(dnsLog: DnsLog) {
            b.responseTime.text = dnsLog.wallTime()
            b.fqdn.text = dnsLog.queryStr

            b.latencyVal.text =
                context.getString(R.string.dns_query_latency, dnsLog.latency.toString())
        }

        private fun openBottomSheet(dnsLog: DnsLog) {
            if (context !is FragmentActivity) {
                Logger.w(
                    LOG_TAG_UI,
                    "Can not open bottom sheet. Context is not attached to activity"
                )
                return
            }

            val bottomSheetFragment = DnsBlocklistBottomSheet()
            val bundle = Bundle()
            bundle.putString(DnsBlocklistBottomSheet.INSTANCE_STATE_DNSLOGS, Gson().toJson(dnsLog))
            bottomSheetFragment.arguments = bundle
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun displayNextDnsFavIcon(dnsLog: DnsLog) {
            val trim = dnsLog.queryStr.dropLastWhile { it == '.' }
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
                        object : CustomViewTarget<ImageView, Drawable>(b.favIcon) {
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
            } catch (e: Exception) {
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
                        object : CustomViewTarget<ImageView, Drawable>(b.favIcon) {
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
            } catch (e: Exception) {
                Logger.d(LOG_TAG_DNS, "Error loading icon, load flag instead")
                showFlag()
                hideFavIcon()
            }
        }

        private fun showFavIcon(drawable: Drawable) {
            b.favIcon.visibility = View.VISIBLE
            b.favIcon.setImageDrawable(drawable)
        }

        private fun hideFavIcon() {
            b.favIcon.visibility = View.GONE
            b.favIcon.setImageDrawable(null)
        }

        private fun showFlag() {
            b.flag.visibility = View.VISIBLE
        }

        private fun hideFlag() {
            b.flag.visibility = View.GONE
        }
    }
}
