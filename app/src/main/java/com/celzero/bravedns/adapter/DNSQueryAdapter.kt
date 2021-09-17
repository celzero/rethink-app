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

import android.content.Context
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.FragmentActivity
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions.withCrossFade
import com.bumptech.glide.request.target.CustomViewTarget
import com.bumptech.glide.request.target.Target.SIZE_ORIGINAL
import com.bumptech.glide.request.transition.DrawableCrossFadeFactory
import com.bumptech.glide.request.transition.Transition
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DnsLog
import com.celzero.bravedns.databinding.TransactionRowBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.ui.DNSBlocklistBottomSheetFragment
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS_LOG


class DNSQueryAdapter(val context: Context, val loadFavIcon: Boolean) :
        PagedListAdapter<DnsLog, DNSQueryAdapter.TransactionViewHolder>(DIFF_CALLBACK) {

    companion object {
        const val TYPE_TRANSACTION: Int = 1
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DnsLog>() {

            override fun areItemsTheSame(oldConnection: DnsLog,
                                         newConnection: DnsLog) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: DnsLog,
                                            newConnection: DnsLog) = oldConnection == newConnection
        }
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val dnsLog: DnsLog = getItem(position) ?: return
        holder.update(dnsLog)
    }

    override fun getItemViewType(position: Int): Int {
        return TYPE_TRANSACTION
    }

    override fun getItemId(position: Int): Long {
        return super.getItemId(position)
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val itemBinding = TransactionRowBinding.inflate(LayoutInflater.from(parent.context), parent,
                                                        false)
        return TransactionViewHolder(itemBinding)
    }


    inner class TransactionViewHolder(private val b: TransactionRowBinding) :
            RecyclerView.ViewHolder(b.root) {
        fun update(dnsLog: DnsLog?) {
            if (dnsLog == null) return

            displayDetails(dnsLog)
            displayLogEntryHint(dnsLog)
            displayIcon(dnsLog)

            b.root.setOnClickListener {
                openBottomSheet(dnsLog)
            }
        }

        private fun displayLogEntryHint(dnsLog: DnsLog) {
            if (dnsLog.isBlocked) {
                b.queryLogIndicator.visibility = View.VISIBLE
            } else {
                b.queryLogIndicator.visibility = View.INVISIBLE
            }
        }

        private fun displayIcon(dnsLog: DnsLog) {
            b.flag.text = dnsLog.flag
            b.flag.visibility = View.VISIBLE
            b.favIcon.visibility = View.GONE
            if (!loadFavIcon || dnsLog.groundedQuery()) {
                clearFavIcon()
                return
            }

            val url = dnsLog.favIcoUrl()
            val subDomainURL = dnsLog.subdomain()
            displayFavIcon(url, subDomainURL)
        }

        private fun clearFavIcon() {
            GlideApp.with(context.applicationContext).clear(b.favIcon)
        }

        private fun displayDetails(dnsLog: DnsLog) {
            b.responseTime.text = dnsLog.wallTime()
            b.fqdn.text = dnsLog.queryStr
            b.latencyVal.text = context.getString(R.string.dns_query_latency,
                                                  dnsLog.latency.toString())
        }

        private fun openBottomSheet(dnsLog: DnsLog) {
            if (context !is FragmentActivity) {
                Log.wtf(LoggerConstants.LOG_TAG_UI,
                        "Can not open bottom sheet. Context is not attached to activity")
                return
            }
            val bottomSheetFragment = DNSBlocklistBottomSheetFragment(context, dnsLog)
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        /**
         * Loads the fav icons from the cache, the icons are cached by favIconDownloader.
         * On failure, will check if there is a icon for top level domain is available in cache.
         * Else, will show the Flag.
         *
         * This method will be executed only when show fav icon setting is turned on.
         */
        private fun displayFavIcon(url: String, subDomainURL: String) {
            try {
                val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
                GlideApp.with(context.applicationContext).load(url).onlyRetrieveFromCache(
                    true).diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).override(SIZE_ORIGINAL,
                                                                                  SIZE_ORIGINAL).error(
                    GlideApp.with(context.applicationContext).load(
                        subDomainURL).onlyRetrieveFromCache(true)).transition(
                    withCrossFade(factory)).into(object : CustomViewTarget<ImageView, Drawable>(
                    b.favIcon) {
                    override fun onLoadFailed(errorDrawable: Drawable?) {
                        showFlag()
                        hideFavIcon()
                    }

                    override fun onResourceReady(resource: Drawable,
                                                 transition: Transition<in Drawable>?) {
                        hideFlag()
                        showFavIcon(resource)
                    }

                    override fun onResourceCleared(placeholder: Drawable?) {
                        hideFavIcon()
                        showFlag()
                    }
                })
            } catch (e: Exception) {
                if (DEBUG) Log.d(LOG_TAG_DNS_LOG, "Error loading icon, load flag instead")
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
