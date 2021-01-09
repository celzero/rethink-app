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
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallRules
import com.celzero.bravedns.database.BlockedConnections
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.databinding.UnivWhitelistRulesItemBinding
import com.celzero.bravedns.service.BraveVPNService
import com.celzero.bravedns.ui.ConnTrackerBottomSheetFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class UniversalBlockedRulesAdapter(private val context: Context, private val blockedConnectionsRepository: BlockedConnectionsRepository) : PagedListAdapter<BlockedConnections, UniversalBlockedRulesAdapter.UniversalBlockedConnViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<BlockedConnections>() {
            // Concert details may have changed if reloaded from the database,
            // but ID is fixed.
            override fun areItemsTheSame(oldConnection: BlockedConnections, newConnection: BlockedConnections) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: BlockedConnections, newConnection: BlockedConnections) = oldConnection == newConnection
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UniversalBlockedConnViewHolder {
        val itemBinding = UnivWhitelistRulesItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        itemBinding.root.setBackgroundColor(context.getColor(R.color.colorPrimary))
        return UniversalBlockedConnViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: UniversalBlockedConnViewHolder, position: Int) {
        val blockedConns: BlockedConnections = getItem(position) ?: return
        holder.update(blockedConns)
    }


    inner class UniversalBlockedConnViewHolder(private val b: UnivWhitelistRulesItemBinding) : RecyclerView.ViewHolder(b.root) {

        fun update(blockedConns: BlockedConnections) {
            b.univWhitelistRulesApkLabelTv.text = blockedConns.ipAddress
            b.univWhitelistRulesDeleteBtn.setOnClickListener {
                showDialogForDelete(blockedConns)
            }
        }

        private fun showDialogForDelete(blockedConns: BlockedConnections) {
            val builder = AlertDialog.Builder(context)
                //set title for alert dialog
                .setTitle(R.string.univ_firewall_dialog_title)
                //set message for alert dialog
                .setMessage(R.string.univ_firewall_dialog_message).setIcon(android.R.drawable.ic_dialog_alert).setCancelable(true)
                //performing positive action
                .setPositiveButton("Delete") { _, _ ->
                    GlobalScope.launch(Dispatchers.IO) {
                        val firewallRules = FirewallRules.getInstance()
                        firewallRules.removeFirewallRules(ConnTrackerBottomSheetFragment.UNIVERSAL_RULES_UID, blockedConns.ipAddress!!, BraveVPNService.BlockedRuleNames.RULE2.ruleName, blockedConnectionsRepository)
                    }
                    Toast.makeText(context, "${blockedConns.ipAddress} unblocked.", Toast.LENGTH_SHORT).show()
                }
                //performing negative action
                .setNegativeButton("Cancel") { _, _ -> }
            // Create the AlertDialog
            val alertDialog: AlertDialog = builder.create()
            // Set other dialog properties
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

    }

}