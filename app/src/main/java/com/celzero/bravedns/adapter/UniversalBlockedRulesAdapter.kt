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
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.database.AppDatabase
import com.celzero.bravedns.database.BlockedConnections
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.ui.ConnTrackerBottomSheetFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class UniversalBlockedRulesAdapter(
    private val context: Context,
    private val blockedConnectionsRepository: BlockedConnectionsRepository
) : PagedListAdapter<BlockedConnections, UniversalBlockedRulesAdapter.UniversalBlockedConnViewHolder>(
    DIFF_CALLBACK
) {

    companion object {
        private val DIFF_CALLBACK = object :
            DiffUtil.ItemCallback<BlockedConnections>() {
            // Concert details may have changed if reloaded from the database,
            // but ID is fixed.
            override fun areItemsTheSame(oldConnection: BlockedConnections, newConnection: BlockedConnections) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: BlockedConnections, newConnection: BlockedConnections) = oldConnection == newConnection
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UniversalBlockedConnViewHolder {
        val v: View = LayoutInflater.from(parent.context).inflate(
            R.layout.univ_whitelist_rules_item,
            parent, false
        )
        v.setBackgroundColor(context.getColor(R.color.colorPrimary))
        return UniversalBlockedConnViewHolder(v)
    }

    override fun onBindViewHolder(holder: UniversalBlockedConnViewHolder, position: Int) {
        val blockedConns: BlockedConnections? = getItem(position)
        holder.update(blockedConns)
    }


    inner class UniversalBlockedConnViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // Overall view
        private var rowView: View? = null

        // Contents of the condensed view
        private var ipAddressTxt: TextView
        private var deleteBtn: Button


        init {
            rowView = itemView
            ipAddressTxt = itemView.findViewById(R.id.univ_whitelist_rules_apk_label_tv)
            deleteBtn = itemView.findViewById(R.id.univ_whitelist_rules_delete_btn)
        }

        fun update(blockedConns: BlockedConnections?) {
            if (blockedConns != null) {
                ipAddressTxt.text = blockedConns.ipAddress
            }
            deleteBtn.setOnClickListener {
                if (blockedConns != null) {
                    showDialogForDelete(blockedConns)
                }
            }
        }

        private fun showDialogForDelete(blockedConns: BlockedConnections) {
            val builder = AlertDialog.Builder(context)
            //set title for alert dialog
            builder.setTitle(R.string.univ_firewall_dialog_title)
            //set message for alert dialog
            builder.setMessage(R.string.univ_firewall_dialog_message)
            builder.setIcon(android.R.drawable.ic_dialog_alert)
            builder.setCancelable(true)
            //performing positive action
            builder.setPositiveButton("Delete") { dialogInterface, which ->
                GlobalScope.launch(Dispatchers.IO) {
                    if (blockedConns != null) {
                        val firewallRules = FirewallRules.getInstance()
                        firewallRules.removeFirewallRules(ConnTrackerBottomSheetFragment.UNIVERSAL_RULES_UID, blockedConns.ipAddress!!, BraveVPNService.BlockedRuleNames.RULE2.ruleName, blockedConnectionsRepository)
                    }
                }
                Toast.makeText(context, "${blockedConns.ipAddress} unblocked.", Toast.LENGTH_SHORT).show()
            }

            //performing negative action
            builder.setNegativeButton("Cancel") { dialogInterface, which ->
            }
            // Create the AlertDialog
            val alertDialog: AlertDialog = builder.create()
            // Set other dialog properties
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

    }

}