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
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DNSLogs
import com.celzero.bravedns.databinding.TransactionRowBinding
import com.celzero.bravedns.receiver.GlideImageRequestListener
import com.celzero.bravedns.ui.DNSBlockListBottomSheetFragment
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities.Companion.getETldPlus1
import java.text.SimpleDateFormat
import java.util.*


class DNSQueryAdapter(val context: Context) : PagedListAdapter<DNSLogs, DNSQueryAdapter.TransactionViewHolder>(DIFF_CALLBACK) {

    companion object {
        const val TYPE_TRANSACTION: Int = 1
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DNSLogs>() {

            override fun areItemsTheSame(oldConnection: DNSLogs, newConnection: DNSLogs) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: DNSLogs, newConnection: DNSLogs) = oldConnection == newConnection
        }
    }

    override fun onBindViewHolder(holder: TransactionViewHolder, position: Int) {
        val transaction: DNSLogs = getItem(position) ?: return
        holder.update(transaction)
    }

    override fun getItemViewType(position: Int): Int {
        return TYPE_TRANSACTION
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TransactionViewHolder {

        return if (viewType == TYPE_TRANSACTION) {
            val itemBinding = TransactionRowBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            TransactionViewHolder(itemBinding)
        } else {
            throw AssertionError(String.format(Locale.ROOT, "Unknown viewType %d", viewType))
        }
    }


    inner class TransactionViewHolder(private val b: TransactionRowBinding) : RecyclerView.ViewHolder(b.root), GlideImageRequestListener.Callback {
        fun update(transaction: DNSLogs?) {
            // This function can be run up to a dozen times while blocking rendering, so it needs to be
            // as brief as possible.
            if (transaction != null) {
                b.responseTime.text = convertLongToTime(transaction.time)
                b.fqdn.text = transaction.queryStr
                b.latencyVal.text = context.getString(R.string.dns_query_latency, transaction.latency.toString())
                b.flag.text = transaction.flag

                if (transaction.isBlocked) {
                    b.flag.visibility = View.VISIBLE
                    b.favIcon.visibility = View.GONE
                    b.queryLogIndicator.visibility = View.VISIBLE
                } else {
                    b.flag.visibility = View.GONE
                    b.favIcon.visibility = View.VISIBLE
                    b.queryLogIndicator.visibility = View.INVISIBLE
                }
                if (transaction.response != "NXDOMAIN" && !transaction.isBlocked) {
                    setFavIcon(transaction.queryStr)
                }else{
                    b.flag.visibility = View.VISIBLE
                    b.favIcon.visibility = View.GONE
                }
                b.root.setOnClickListener {
                    b.root.isEnabled = false
                    openBottomSheet(transaction)
                    b.root.isEnabled = true
                }

            }
        }

        private fun setFavIcon(query: String) {
            val trim = query.dropLast(1)
            val domainURL = getETldPlus1(trim)
            Log.d(Constants.LOG_TAG, "URI HOST value: $trim, $domainURL")
            val url = "https://icons.duckduckgo.com/ip2/$domainURL.ico"
            Glide.with(context)
                .load(url)
                .listener(GlideImageRequestListener(this))
                .into(b.favIcon)
        }

        override fun onFailure(message: String?) {
            b.flag.visibility = View.VISIBLE
            b.favIcon.visibility = View.GONE
        }

        override fun onSuccess(dataSource: String) {
            b.flag.visibility = View.GONE
            b.favIcon.visibility = View.VISIBLE
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
    }


}