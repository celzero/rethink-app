/*
 * Copyright 2021 RethinkDNS and its authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.celzero.bravedns.adapter

import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.LifecycleOwner
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.databinding.ListItemAppIpDetailsBinding
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.ui.bottomsheet.AppIpRulesBottomSheet
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.removeBeginningTrailingCommas
import kotlin.math.log2

class AppWiseIpsAdapter(val context: Context, val lifecycleOwner: LifecycleOwner, val uid: Int) :
    PagingDataAdapter<AppConnection, AppWiseIpsAdapter.ConnectionDetailsViewHolder>(DIFF_CALLBACK),
    AppIpRulesBottomSheet.OnBottomSheetDialogFragmentDismiss {

    private var maxValue: Int = 0
    private var minPercentage: Int = 100

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<AppConnection>() {
                override fun areItemsTheSame(old: AppConnection, new: AppConnection) = old == new

                override fun areContentsTheSame(old: AppConnection, new: AppConnection) = old == new
            }
    }

    private lateinit var adapter: AppWiseIpsAdapter

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): AppWiseIpsAdapter.ConnectionDetailsViewHolder {
        val itemBinding =
            ListItemAppIpDetailsBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        adapter = this
        return ConnectionDetailsViewHolder(itemBinding)
    }

    override fun onBindViewHolder(
        holder: AppWiseIpsAdapter.ConnectionDetailsViewHolder,
        position: Int
    ) {
        val appConnection: AppConnection = getItem(position) ?: return
        // updates the app-wise connections from network log to AppInfo screen
        holder.update(appConnection)
    }

    private fun calculatePercentage(c: Double): Int {
        val value = (log2(c) * 100).toInt()
        // maxValue will be based on the count returned by db query (order by count desc)
        if (value > maxValue) {
            maxValue = value
        }
        return if (maxValue == 0) {
            0
        } else {
            val percentage = (value * 100 / maxValue)
            // minPercentage is used to show the progress bar when the percentage is 0
            if (percentage < minPercentage && percentage != 0) {
                minPercentage = percentage
            }
            percentage
        }
    }

    inner class ConnectionDetailsViewHolder(private val b: ListItemAppIpDetailsBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun update(conn: AppConnection) {
            displayTransactionDetails(conn)
            setupClickListeners(conn)
        }

        private fun setupClickListeners(conn: AppConnection) {
            b.acdContainer.setOnClickListener {
                // open bottom sheet to apply domain/ip rules
                openBottomSheet(conn)
            }
        }

        private fun openBottomSheet(conn: AppConnection) {
            if (context !is AppCompatActivity) {
                Logger.w(LOG_TAG_UI, "err opening the app conn bottom sheet")
                return
            }

            val bottomSheetFragment = AppIpRulesBottomSheet()
            // Fix: free-form window crash
            // all BottomSheetDialogFragment classes created must have a public, no-arg constructor.
            // the best practice is to simply never define any constructors at all.
            // so sending the data using Bundles
            val bundle = Bundle()
            bundle.putInt(AppIpRulesBottomSheet.UID, uid)
            bundle.putString(AppIpRulesBottomSheet.IP_ADDRESS, conn.ipAddress)
            bundle.putString(
                AppIpRulesBottomSheet.DOMAINS,
                beautifyDomainString(conn.appOrDnsName ?: "")
            )
            bottomSheetFragment.arguments = bundle
            bottomSheetFragment.dismissListener(adapter, absoluteAdapterPosition)
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun displayTransactionDetails(conn: AppConnection) {
            b.acdCount.text = conn.count.toString()
            b.acdIpAddress.text = conn.ipAddress
            b.acdFlag.text = conn.flag
            if (!conn.appOrDnsName.isNullOrEmpty()) {
                b.acdDomainName.visibility = View.VISIBLE
                b.acdDomainName.text = beautifyDomainString(conn.appOrDnsName)
            } else {
                b.acdDomainName.visibility = View.GONE
            }
            updateStatusUi(conn)
        }

        private fun beautifyDomainString(d: String): String {
            // replace two commas in the string to one
            // add space after all the commas
            return removeBeginningTrailingCommas(d).replace(",,", ",").replace(",", ", ")
        }

        private fun updateStatusUi(conn: AppConnection) {
            val status = IpRulesManager.getMostSpecificRuleMatch(conn.uid, conn.ipAddress)
            when (status) {
                IpRulesManager.IpRuleStatus.NONE -> {
                    b.progress.setIndicatorColor(
                        UIUtils.fetchToggleBtnColors(context, R.color.chipTextNeutral)
                    )
                }
                IpRulesManager.IpRuleStatus.BLOCK -> {
                    b.progress.setIndicatorColor(
                        UIUtils.fetchToggleBtnColors(context, R.color.accentBad)
                    )
                }
                IpRulesManager.IpRuleStatus.BYPASS_UNIVERSAL -> {
                    b.progress.setIndicatorColor(
                        UIUtils.fetchToggleBtnColors(context, R.color.accentGood)
                    )
                }
                IpRulesManager.IpRuleStatus.TRUST -> {
                    b.progress.setIndicatorColor(
                        UIUtils.fetchToggleBtnColors(context, R.color.accentGood)
                    )
                }
            }

            var p = calculatePercentage(conn.count.toDouble())
            if (p == 0) {
                p = minPercentage / 2
            }

            if (Utilities.isAtleastN()) {
                b.progress.setProgress(p, true)
            } else {
                b.progress.progress = p
            }
        }
    }

    override fun notifyDataset(position: Int) {
        this.notifyItemChanged(position)
    }
}
