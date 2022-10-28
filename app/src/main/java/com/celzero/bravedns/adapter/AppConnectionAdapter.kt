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
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.IpRulesManager
import com.celzero.bravedns.data.AppConnections
import com.celzero.bravedns.databinding.ListItemAppConnDetailsBinding
import com.celzero.bravedns.ui.AppConnectionBottomSheet
import com.celzero.bravedns.util.LoggerConstants
import com.celzero.bravedns.util.Utilities.Companion.removeBeginningTrailingCommas

class AppConnectionAdapter(val context: Context, connLists: List<AppConnections>, val uid: Int) :
        RecyclerView.Adapter<AppConnectionAdapter.ConnectionDetailsViewHolder>(),
        AppConnectionBottomSheet.OnBottomSheetDialogFragmentDismiss {

    private lateinit var adapter: AppConnectionAdapter
    private var ips: List<AppConnections>

    init {
        ips = connLists
    }

    override fun onCreateViewHolder(parent: ViewGroup,
                                    viewType: Int): AppConnectionAdapter.ConnectionDetailsViewHolder {
        val itemBinding = ListItemAppConnDetailsBinding.inflate(LayoutInflater.from(parent.context),
                                                                parent, false)
        adapter = this
        return ConnectionDetailsViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: AppConnectionAdapter.ConnectionDetailsViewHolder,
                                  position: Int) {
        // updates the app-wise connections from network log to AppInfo screen
        holder.update(position)
    }

    override fun getItemCount(): Int {
        return ips.size
    }

    override fun notifyDataset(position: Int) {
        this.notifyItemChanged(position)
    }

    fun filter(filtered: List<AppConnections>?) {
        if (filtered == null) return

        ips = filtered
        notifyDataSetChanged()
    }

    inner class ConnectionDetailsViewHolder(private val b: ListItemAppConnDetailsBinding) :
            RecyclerView.ViewHolder(b.root) {
        fun update(position: Int) {
            displayTransactionDetails(position)
            setupClickListeners(ips[position], position)
        }

        private fun setupClickListeners(appConn: AppConnections, position: Int) {
            b.acdContainer.setOnClickListener {
                val status = IpRulesManager.hasRule(uid, appConn.ipAddress, appConn.port)
                // open bottom sheet for options
                openBottomSheet(appConn.ipAddress, appConn.port, status, position)
            }
        }

        private fun openBottomSheet(ipAddress: String, port: Int,
                                    ipRuleStatus: IpRulesManager.IpRuleStatus, position: Int) {
            if (context !is AppCompatActivity) {
                Log.wtf(LoggerConstants.LOG_TAG_UI, context.getString(R.string.ct_btm_sheet_error))
                return
            }

            val bottomSheetFragment = AppConnectionBottomSheet()
            // see AppIpRulesAdapter.kt#openBottomSheet()
            val bundle = Bundle()
            bundle.putInt(AppConnectionBottomSheet.UID, uid)
            bundle.putString(AppConnectionBottomSheet.IPADDRESS, ipAddress)
            bundle.putInt(AppConnectionBottomSheet.PORT, port)
            bundle.putInt(AppConnectionBottomSheet.IPRULESTATUS, ipRuleStatus.id)
            bottomSheetFragment.arguments = bundle
            bottomSheetFragment.dismissListener(adapter, position)
            bottomSheetFragment.show(context.supportFragmentManager, bottomSheetFragment.tag)
        }

        private fun displayTransactionDetails(position: Int) {
            val conn = ips[position]

            b.acdCount.text = conn.count.toString()
            b.acdFlag.text = conn.flag
            b.acdIpAddress.text = context.getString(R.string.ct_ip_port, conn.ipAddress,
                                                    conn.port.toString())
            if (!conn.dnsQuery.isNullOrEmpty()) {
                b.acdDomainName.visibility = View.VISIBLE
                b.acdDomainName.text = beautifyDomainString(conn.dnsQuery)
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
