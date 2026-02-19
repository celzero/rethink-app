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
package com.celzero.bravedns.ui.adapter

import Logger
import Logger.LOG_TAG_UI
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.databinding.ListItemVpnServerBinding
import com.celzero.bravedns.ui.activity.ServerWgConfigDetailActivity
import com.celzero.bravedns.util.UIUtils.fetchColor
import kotlin.collections.toList

/**
 * RecyclerView adapter for displaying VPN server list
 * with Material Design cards and expandable/collapsible functionality
 */
class VpnServerAdapter(
    private var servers: List<CountryConfig>,
    private val listener: ServerSelectionListener
) : RecyclerView.Adapter<VpnServerAdapter.ServerViewHolder>() {

    interface ServerSelectionListener {
        fun onServerSelected(server: CountryConfig, isSelected: Boolean)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val binding = ListItemVpnServerBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ServerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        Logger.v(LOG_TAG_UI, "VpnServerAdapter.onBindViewHolder: position=$position, server=${servers[position].countryName}")
        holder.bind(servers[position])
        // Add subtle entrance animation
        holder.itemView.alpha = 0f
        holder.itemView.translationY = 50f
        holder.itemView.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setStartDelay((position * 50).toLong())
            .start()
    }

    override fun getItemCount(): Int {
        val count = servers.size
        Logger.v(LOG_TAG_UI, "VpnServerAdapter.getItemCount: returning $count")
        return count
    }

    fun updateServers(newServers: List<CountryConfig>) {
        val oldSize = servers.size
        servers = newServers.toList() // Create a new copy to ensure list is not shared
        Logger.v(LOG_TAG_UI, "VpnServerAdapter.updateServers: oldSize=$oldSize, newSize=${servers.size}, servers=${servers.map { it.countryName }}")
        notifyDataSetChanged()
    }

    inner class ServerViewHolder(
        private val binding: ListItemVpnServerBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private val context: Context = binding.root.context

        fun bind(server: CountryConfig) {
            binding.apply {
                Logger.v(LOG_TAG_UI, "VpnServerAdapter.bind: server=${server.countryName}, selected=${server.isActive}, position=$bindingAdapterPosition")
                Logger.v(LOG_TAG_UI, "Binding server: ${server.name}, location: ${server.serverLocation}, latency: ${server.link}ms, load: ${server.load}%, selected: ${server.isActive}, cc: ${server.cc}, addr: ${server.address}, key: ${server.key}, active: ${server.isActive}")

                // Check if this is the AUTO server
                val isAutoServer = server.id == "AUTO"

                if (isAutoServer) {
                    // Special styling for AUTO server
                    tvFlag.text = "🌐"
                    tvCountryName.text = "AUTO"
                    tvServerLocation.text = "Automatic server selection"
                    latencyBadge.text = "Auto"
                    latencyBadge.setTextColor(fetchColor(context, R.attr.accentGood))

                    // AUTO is always selected and cannot be manually toggled
                    checkboxSelect.isChecked = true
                    checkboxSelect.isEnabled = false
                    checkboxSelect.alpha = 0.6f

                    // Disable info icon for AUTO
                    infoIcon.isEnabled = false
                    infoIcon.alpha = 0.3f
                } else {
                    // Normal server display
                    tvFlag.text = server.flagEmoji
                    tvCountryName.text = server.countryName
                    tvServerLocation.text = root.context.getString(R.string.server_location_format, server.serverLocation, server.cc)
                    checkboxSelect.isChecked = server.isActive
                    latencyBadge.text = server.getBadgeText()
                    setLatencyColor(server)

                    // Re-enable controls for normal servers
                    checkboxSelect.isEnabled = true
                    checkboxSelect.alpha = 1f
                    infoIcon.isEnabled = true
                    infoIcon.alpha = 1f
                }

                premiumBadgeMini.visibility = View.GONE // premium badge not used for RPN

                checkboxSelect.setOnClickListener {
                    if (!isAutoServer) {
                        val newState = !server.isActive
                        listener.onServerSelected(server, newState)
                    }
                }

                infoIcon.setOnClickListener {
                    if (!isAutoServer) {
                        openServerDetail(server)
                    }
                }

                serverCard.setOnClickListener {
                    if (!isAutoServer) {
                        checkboxSelect.performClick()
                    }
                }
            }
        }

        private fun setLatencyColor(server: CountryConfig) {
            val colorAttr = when (server.getQualityLevel()) {
                CountryConfig.ServerQuality.EXCELLENT -> R.attr.chipTextPositive
                CountryConfig.ServerQuality.GOOD -> R.attr.accentGood
                CountryConfig.ServerQuality.FAIR -> R.attr.chipTextNeutral
                CountryConfig.ServerQuality.POOR -> R.attr.chipTextNegative
             }
            binding.latencyBadge.setTextColor(fetchColor(context, colorAttr))
        }

        private fun openServerDetail(server: CountryConfig) {
            val intent = android.content.Intent(context, ServerWgConfigDetailActivity::class.java)
            intent.putExtra(ServerWgConfigDetailActivity.INTENT_EXTRA_SERVER_ID, server.id.hashCode())
            intent.putExtra(ServerWgConfigDetailActivity.INTENT_EXTRA_FROM_SERVER_SELECTION, true)
            intent.putExtra(ServerWgConfigDetailActivity.INTENT_EXTRA_COUNTRY_CODE, server.key)
            context.startActivity(intent)
        }
    }
}
