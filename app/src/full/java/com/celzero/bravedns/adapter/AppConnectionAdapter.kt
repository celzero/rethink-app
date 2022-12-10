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

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.databinding.ListItemAppConnDetailsBinding
import com.celzero.bravedns.ui.AppConnectionBottomSheet
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities.Companion.removeBeginningTrailingCommas

class AppConnectionAdapter(val context: Context, val uid: Int) :
    PagingDataAdapter<AppConnection, AppConnectionAdapter.ConnectionDetailsViewHolder>(
        DIFF_CALLBACK
    ) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<AppConnection>() {

                override fun areItemsTheSame(
                    oldConnection: AppConnection,
                    newConnection: AppConnection
                ) = oldConnection.ipAddress == newConnection.ipAddress

                override fun areContentsTheSame(
                    oldConnection: AppConnection,
                    newConnection: AppConnection
                ) = oldConnection == newConnection
            }
    }

    private lateinit var adapter: AppConnectionAdapter

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): AppConnectionAdapter.ConnectionDetailsViewHolder {
        val itemBinding =
            ListItemAppConnDetailsBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        adapter = this
        return ConnectionDetailsViewHolder(itemBinding)
    }

    override fun onBindViewHolder(
        holder: AppConnectionAdapter.ConnectionDetailsViewHolder,
        position: Int
    ) {
        val appConnection: AppConnection = getItem(position) ?: return
        // updates the app-wise connections from network log to AppInfo screen
        holder.update(appConnection)
    }

    inner class ConnectionDetailsViewHolder(private val b: ListItemAppConnDetailsBinding) :
        RecyclerView.ViewHolder(b.root) {
        fun update(conn: AppConnection) {
            displayTransactionDetails(conn)
            setupClickListeners(conn)
        }

        private fun setupClickListeners(appConn: AppConnection) {
            b.acdContainer.setOnClickListener {
                val status = IpRulesManager.hasRule(uid, appConn.ipAddress, Constants.UNSPECIFIED_PORT)
                // open bottom sheet for options
                openBottomSheet(appConn.ipAddress, Constants.UNSPECIFIED_PORT, status)
            }
        }

        private fun openBottomSheet(
            ipAddress: String,
            port: Int,
            ipRuleStatus: IpRulesManager.IpRuleStatus
        ) {
            if (context !is AppCompatActivity) {
                Log.wtf(LoggerConstants.LOG_TAG_UI, "Error opening the app conn bottomsheet")
                return
            }

            val bottomSheetFragment = AppConnectionBottomSheet()
            // Fix: free-form window crash
            // all BottomSheetDialogFragment classes created must have a public, no-arg constructor.
            // the best practice is to simply never define any constructors at all.
            // so sending the data using Bundles
            val bundle = Bundle()
            bundle.putInt(AppConnectionBottomSheet.UID, uid)
            bundle.putString(AppConnectionBottomSheet.IPADDRESS, ipAddress)
            bundle.putInt(AppConnectionBottomSheet.PORT, port)
            bundle.putInt(AppConnectionBottomSheet.IPRULESTATUS, ipRuleStatus.id)
            bottomSheetFragment.arguments = bundle
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun displayTransactionDetails(appConnection: AppConnection) {
            b.acdCount.text = appConnection.count.toString()
            b.acdFlag.text = appConnection.flag
            b.acdIpAddress.text =
                context.getString(
                    R.string.ct_ip_port,
                    appConnection.ipAddress,
                    appConnection.port.toString()
                )
            if (!appConnection.dnsQuery.isNullOrEmpty()) {
                b.acdDomainName.visibility = View.VISIBLE
                b.acdDomainName.text = beautifyDomainString(appConnection.dnsQuery)
            } else {
                b.acdDomainName.visibility = View.GONE
            }
        }

        private fun beautifyDomainString(d: String): String {
            // replace two commas in the string to one
            // add space after all the commas
            return removeBeginningTrailingCommas(d).replace(",,", ",").replace(",", ", ")
        }
    }
}
