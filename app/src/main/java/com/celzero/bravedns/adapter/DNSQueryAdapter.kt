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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.database.DNSLogs
import com.celzero.bravedns.databinding.TransactionRowBinding
import com.celzero.bravedns.ui.DNSBlockListBottomSheetFragment
import com.celzero.bravedns.util.Constants
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
        holder.update(transaction, position)
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


    inner class TransactionViewHolder(private val b: TransactionRowBinding) : RecyclerView.ViewHolder(b.root) {

        fun update(transaction: DNSLogs, position: Int) {
            // This function can be run up to a dozen times while blocking rendering, so it needs to be
            // as brief as possible.
            if (transaction != null) {
                //this.transaction = transaction
                b.responseTime.text = convertLongToTime(transaction.time)
                b.flag.text = transaction.flag
                //fqdnView!!.text = Utilities.getETldPlus1(transaction.fqdn!!)
                b.fqdn.text = transaction.queryStr
                b.latencyVal.text = transaction.latency.toString() + "ms"

                if (transaction.isBlocked) {
                    b.queryLogIndicator.visibility = View.VISIBLE
                } else {
                    b.queryLogIndicator.visibility = View.INVISIBLE
                }
                b.root.setOnClickListener {
                    //if (!transaction.blockList.isNullOrEmpty()) {
                    b.root.isEnabled = false
                    openBottomSheet(transaction)
                    b.root.isEnabled = true
                    //}

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
            val format = SimpleDateFormat(Constants.DATE_FORMAT_PATTERN)
            return format.format(date)
        }
    }
}