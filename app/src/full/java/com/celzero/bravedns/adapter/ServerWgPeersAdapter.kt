/*
 * Copyright 2025 RethinkDNS and its authors
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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.databinding.ListItemServerWgPeersBinding
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities.tos
import com.celzero.bravedns.wireguard.Peer

/**
 * Adapter for displaying server WireGuard peers with expandable details
 * - No delete functionality
 * - Expandable/collapsible peer items
 * - Persistent keepalive shown and can be edited inline
 */
class ServerWgPeersAdapter(
    val context: Context,
    private var peers: MutableList<Peer>,
    private val onPeerExpanded: (Int, Boolean) -> Unit
) : RecyclerView.Adapter<ServerWgPeersAdapter.ServerWgPeersViewHolder>() {

    private val expandedPositions = mutableSetOf<Int>()

    override fun onBindViewHolder(holder: ServerWgPeersViewHolder, position: Int) {
        val peer: Peer = peers[position]
        holder.update(peer, position)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerWgPeersViewHolder {
        val itemBinding =
            ListItemServerWgPeersBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ServerWgPeersViewHolder(itemBinding)
    }

    override fun getItemCount(): Int {
        return peers.size
    }

    fun updatePeers(newPeers: MutableList<Peer>) {
        peers = newPeers
        notifyDataSetChanged()
    }

    inner class ServerWgPeersViewHolder(private val b: ListItemServerWgPeersBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(wgPeer: Peer, position: Int) {
            val isExpanded = expandedPositions.contains(position)

            // Always show public key
            b.publicKeyText.text = wgPeer.getPublicKey().base64().tos()

            // Toggle expanded/collapsed state
            if (isExpanded) {
                b.expandedDetailsLayout.visibility = View.VISIBLE
                b.expandIcon.rotation = 180f

                // Show endpoint
                if (wgPeer.getEndpoint().isPresent) {
                    b.endpointText.text = wgPeer.getEndpoint().get().toString()
                    b.endpointLabel.visibility = View.VISIBLE
                    b.endpointText.visibility = View.VISIBLE
                } else {
                    b.endpointLabel.visibility = View.GONE
                    b.endpointText.visibility = View.GONE
                }

                // Show allowed IPs
                if (wgPeer.getAllowedIps().isNotEmpty()) {
                    b.allowedIpsText.text = wgPeer.getAllowedIps().joinToString { it.toString() }
                    b.allowedIpsLabel.visibility = View.VISIBLE
                    b.allowedIpsText.visibility = View.VISIBLE
                } else {
                    b.allowedIpsLabel.visibility = View.GONE
                    b.allowedIpsText.visibility = View.GONE
                }

                // Show persistent keepalive
                if (wgPeer.persistentKeepalive.isPresent) {
                    b.persistentKeepaliveText.text =
                        UIUtils.getDurationInHumanReadableFormat(
                            context,
                            wgPeer.persistentKeepalive.get()
                        )
                    b.persistentKeepaliveLabel.visibility = View.VISIBLE
                    b.persistentKeepaliveText.visibility = View.VISIBLE
                } else {
                    b.persistentKeepaliveLabel.visibility = View.VISIBLE
                    b.persistentKeepaliveText.visibility = View.VISIBLE
                    b.persistentKeepaliveText.text = context.getString(R.string.lbl_not_set)
                }
            } else {
                b.expandedDetailsLayout.visibility = View.GONE
                b.expandIcon.rotation = 0f
            }

            // Click to expand/collapse
            b.root.setOnClickListener {
                toggleExpanded(position)
                onPeerExpanded(position, !isExpanded)
            }
        }

        private fun toggleExpanded(position: Int) {
            if (expandedPositions.contains(position)) {
                expandedPositions.remove(position)
            } else {
                expandedPositions.add(position)
            }
            notifyItemChanged(position)
        }
    }
}

