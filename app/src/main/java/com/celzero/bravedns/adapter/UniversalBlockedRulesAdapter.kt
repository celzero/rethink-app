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
import com.celzero.bravedns.automaton.FirewallRules.UID_EVERYBODY
import com.celzero.bravedns.database.BlockedConnections
import com.celzero.bravedns.database.BlockedConnectionsRepository
import com.celzero.bravedns.databinding.UnivWhitelistRulesItemBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class UniversalBlockedRulesAdapter(private val context: Context,
                                   private val blockedConnectionsRepository: BlockedConnectionsRepository) :
        PagedListAdapter<BlockedConnections, UniversalBlockedRulesAdapter.UniversalBlockedConnViewHolder>(
            DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<BlockedConnections>() {

            override fun areItemsTheSame(oldConnection: BlockedConnections,
                                         newConnection: BlockedConnections) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: BlockedConnections,
                                            newConnection: BlockedConnections) = oldConnection == newConnection
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup,
                                    viewType: Int): UniversalBlockedConnViewHolder {
        val itemBinding = UnivWhitelistRulesItemBinding.inflate(LayoutInflater.from(parent.context),
                                                                parent, false)
        return UniversalBlockedConnViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: UniversalBlockedConnViewHolder, position: Int) {
        val blockedConnections: BlockedConnections = getItem(position) ?: return
        holder.update(blockedConnections)
    }


    inner class UniversalBlockedConnViewHolder(private val b: UnivWhitelistRulesItemBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(blockedConnections: BlockedConnections) {
            b.univWhitelistRulesApkLabelTv.text = blockedConnections.ipAddress
            b.univWhitelistRulesDeleteBtn.setOnClickListener {
                showDialogForDelete(blockedConnections)
            }
        }

        private fun showDialogForDelete(blockedConnections: BlockedConnections) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.univ_firewall_dialog_title)
            builder.setMessage(R.string.univ_firewall_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(
                context.getString(R.string.univ_ip_delete_individual_positive)) { _, _ ->

                CoroutineScope(Dispatchers.IO).launch {
                    FirewallRules.removeFirewallRules(UID_EVERYBODY, blockedConnections.ipAddress,
                                                      blockedConnectionsRepository)
                }
                Toast.makeText(context, context.getString(R.string.univ_ip_delete_individual_toast,
                                                          blockedConnections.ipAddress),
                               Toast.LENGTH_SHORT).show()
            }

            builder.setNegativeButton(
                context.getString(R.string.univ_ip_delete_individual_negative)) { _, _ -> }

            val alertDialog: AlertDialog = builder.create()
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

    }

}
