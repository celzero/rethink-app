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
import com.celzero.bravedns.database.DNSLogs
import com.celzero.bravedns.databinding.TransactionRowBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.net.doh.Transaction
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.DNSBlockListBottomSheetFragment
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS_LOG
import com.celzero.bravedns.util.Utilities
import java.text.SimpleDateFormat
import java.util.*


class DNSQueryAdapter(val context: Context, private val persistentState: PersistentState) : PagedListAdapter<DNSLogs, DNSQueryAdapter.TransactionViewHolder>(DIFF_CALLBACK) {

    companion object {
        const val TYPE_TRANSACTION: Int = 1
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DNSLogs>() {

            override fun areItemsTheSame(oldConnection: DNSLogs, newConnection: DNSLogs) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: DNSLogs, newConnection: DNSLogs) = oldConnection == newConnection
        }
    }

    private val favIcon = persistentState.fetchFavIcon

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val transaction: DNSLogs = getItem(position) ?: return
        holder.update(transaction)
    }

    override fun getItemViewType(position: Int): Int {
        return TYPE_TRANSACTION
    }

    override fun getItemId(position: Int): Long {
        return super.getItemId(position)
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {
        val itemBinding = TransactionRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TransactionViewHolder(itemBinding, favIcon)
    }


    inner class TransactionViewHolder(private val b: TransactionRowBinding, private val favIcon: Boolean) : RecyclerView.ViewHolder(b.root) {
        fun update(transaction: DNSLogs?) {
            // This function can be run up to a dozen times while blocking rendering, so it needs to be
            // as brief as possible.
            if (transaction != null) {
                b.responseTime.text = convertLongToTime(transaction.time)
                b.fqdn.text = transaction.queryStr
                b.latencyVal.text = context.getString(R.string.dns_query_latency, transaction.latency.toString())
                b.flag.text = transaction.flag
                b.flag.visibility = View.VISIBLE
                b.favIcon.visibility = View.GONE
                if (favIcon) {
                    if (transaction.status == Transaction.Status.COMPLETE.toString() && transaction.response != Constants.NXDOMAIN && !transaction.isBlocked) {
                        val url = "${Constants.FAV_ICON_URL}${transaction.queryStr}ico"
                        val subDomainURL = Utilities.getETldPlus1(transaction.queryStr).toString()
                        val cacheKey = "${Constants.FAV_ICON_URL}${subDomainURL}.ico"
                        updateImage(url, cacheKey)
                    } else {
                        GlideApp.with(context.applicationContext).clear(b.favIcon)
                    }
                } else {
                    GlideApp.with(context.applicationContext).clear(b.favIcon)
                }
                if (transaction.isBlocked) {
                    b.queryLogIndicator.visibility = View.VISIBLE
                } else {
                    b.queryLogIndicator.visibility = View.INVISIBLE
                }
                b.root.setOnClickListener {
                    b.root.isEnabled = false
                    openBottomSheet(transaction)
                    b.root.isEnabled = true
                }

            }
        }

        private fun openBottomSheet(transaction: DNSLogs) {
            val bottomSheetFragment = DNSBlockListBottomSheetFragment(context, transaction)
            val frag = context as FragmentActivity
            bottomSheetFragment.show(frag.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun convertLongToTime(time: Long): String {
            val date = Date(time)
            val format = SimpleDateFormat(Constants.DATE_FORMAT_PATTERN, Locale.ROOT)
            return format.format(date)
        }


        /**
         * Loads the fav icons from the cache, the icons are cached by favIconDownloader.
         * On failure, will check if there is a icon for top level domain is available in cache.
         * Else, will show the Flag.
         *
         * This method will be executed only when show fav icon setting is turned on.
         */
        private fun updateImage(url: String, cacheKey: String) {
            try {
                val factory = DrawableCrossFadeFactory.Builder().setCrossFadeEnabled(true).build()
                GlideApp.with(context.applicationContext)
                    .load(url)
                    .onlyRetrieveFromCache(true)
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .override(SIZE_ORIGINAL, SIZE_ORIGINAL)
                    .error(GlideApp.with(context.applicationContext).load(cacheKey).onlyRetrieveFromCache(true))
                    .transition(withCrossFade(factory))
                    .into(object : CustomViewTarget<ImageView, Drawable>(b.favIcon) {
                        override fun onLoadFailed(errorDrawable: Drawable?) {
                            b.flag.visibility = View.VISIBLE
                            b.favIcon.visibility = View.GONE
                            b.favIcon.setImageDrawable(null)
                        }

                        override fun onResourceReady(resource: Drawable, transition: Transition<in Drawable>?) {
                            b.flag.visibility = View.GONE
                            b.favIcon.visibility = View.VISIBLE
                            b.favIcon.setImageDrawable(resource)
                        }

                        override fun onResourceCleared(placeholder: Drawable?) {
                            b.favIcon.visibility = View.GONE
                            b.flag.visibility = View.VISIBLE
                        }
                    })
            } catch (e: Exception) {
                if (DEBUG) Log.d(LOG_TAG_DNS_LOG, "Glide - TransactionViewHolder Exception() -${e.message}")
                b.flag.visibility = View.VISIBLE
                b.favIcon.visibility = View.GONE
            }
        }
    }
}
