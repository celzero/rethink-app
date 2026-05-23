/*
 * Copyright 2026 RethinkDNS and its authors
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
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.databinding.ItemServerGroupBinding
import com.celzero.bravedns.databinding.ListItemCountryCardBinding
import com.celzero.bravedns.util.SnackbarHelper.capitalizeWords
import com.celzero.bravedns.util.UIUtils.fetchColor
import java.util.Locale

/**
 * Adapter showing servers grouped by country using list_item_country_card.
 * Each country row can be expanded to reveal its city servers.
 */
class CountryServerAdapter(
    private var countries: List<CountryItem>,
    private val listener: CitySelectionListener
) : RecyclerView.Adapter<CountryServerAdapter.CountryViewHolder>() {

    interface CitySelectionListener {
        fun onCitySelected(server: CountryConfig, isEnabled: Boolean)
        /**
         * Called when a server item is tapped while the proxy is stopped.
         * The host should open the settings sheet so the user can restart the proxy.
         */
        fun onProxyStoppedItemTapped()
        /**
         * Called when the user taps the star icon on a country card.
         * [countryCode] identifies the country;
         * [countryName] is provided for user feedback purposes;
         * [isFavourite] is the new desired state;
         */
        fun onFavouriteToggled(countryCode: String, countryName: String, isFavourite: Boolean)
    }

    /**
     * Represents a merged group of servers sharing the same key.
     * Multiple servers with same key are grouped and shown as one selectable item.
     */
    data class ServerGroup(
        val key: String, // Common server key
        val servers: List<CountryConfig>, // All servers with this key
        val city: String, // Primary city name
        val avgLoad: Int, // Average load across servers
        val avgLink: Int, // Average link speed across servers (in Mbps)
        val isEnabled: Boolean // Selection state
    ) {
        val serverCount: Int get() = servers.size
    }

    data class CountryItem(
        val countryCode: String,
        val countryName: String,
        val flagEmoji: String,
        val serverGroups: List<ServerGroup>,  // Changed from cities to serverGroups
        var isFavourite: Boolean = false
    )

    // track which countries are expanded by country code
    private val expandedCountries = mutableSetOf<String>()

    /**
     * True when the RPN proxy has been deliberately stopped.
     * Propagated to every [ServerGroupAdapter] instance so server items
     * block selection and redirect taps to the settings sheet.
     *
     * Using a plain private backing field (not `var isXxx: Boolean`) to avoid the
     * JVM declaration clash between the auto-generated `setProxyStopped` property
     * setter and the explicit [setProxyStopped] method.
     */
    private var proxyStopped = false

    /**
     * Switches all country rows (and their expanded server groups) to/from stopped mode.
     * Skips rebind if the flag did not change.
     */
    fun setProxyStopped(stopped: Boolean) {
        if (proxyStopped == stopped) return
        proxyStopped = stopped
        notifyItemRangeChanged(0, itemCount)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CountryViewHolder {
        val binding = ListItemCountryCardBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return CountryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CountryViewHolder, position: Int) {
        val country = countries[position]
        Logger.v(LOG_TAG_UI, "CountryServerAdapter.bind: ${country.countryName} with ${country.serverGroups.size} server groups")
        holder.bind(country, expandedCountries.contains(country.countryCode))
    }

    override fun getItemCount(): Int = countries.size

    fun updateCountries(newCountries: List<CountryItem>) {
        val old = countries
        val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
            override fun getOldListSize() = old.size
            override fun getNewListSize() = newCountries.size
            override fun areItemsTheSame(o: Int, n: Int) = old[o].countryCode == newCountries[n].countryCode
            override fun areContentsTheSame(o: Int, n: Int) = old[o] == newCountries[n]
        })
        countries = newCountries
        val newCodes = newCountries.map { it.countryCode }.toSet()
        expandedCountries.retainAll(newCodes)
        diff.dispatchUpdatesTo(this)
    }

    inner class CountryViewHolder(
        private val binding: ListItemCountryCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var serverGroupAdapter: ServerGroupAdapter? = null

        fun bind(item: CountryItem, isExpanded: Boolean) {
            binding.apply {
                tvCountryFlag.text = item.flagEmoji
                tvCountryName.text = item.countryName

                // Count total servers across all groups
                val totalServers = item.serverGroups.sumOf { it.serverCount }
                tvServerCount.text =
                    itemView.context.resources.getQuantityString(
                        R.plurals.server_count,
                        totalServers,
                        totalServers
                    )

                if (serverGroupAdapter == null) {
                    serverGroupAdapter = ServerGroupAdapter(listener)
                    rvServers.layoutManager = LinearLayoutManager(itemView.context)
                    rvServers.adapter = serverGroupAdapter
                    rvServers.isNestedScrollingEnabled = false
                }
                // Always keep the inner adapter in sync with the outer stopped state
                // before submitting new data so all bound items reflect the correct mode.
                serverGroupAdapter?.setProxyStopped(this@CountryServerAdapter.proxyStopped)
                serverGroupAdapter?.submitList(item.serverGroups)

                // expand / collapse state
                rvServers.visibility = if (isExpanded) View.VISIBLE else View.GONE
                ivExpandArrow.rotation = if (isExpanded) 180f else 0f

                bindFavouriteStar(item)

                ivFavourite.setOnClickListener {
                    val newFavourite = !item.isFavourite
                    // Animate the star: quick scale bounce for tactile feedback
                    ivFavourite.animate()
                        .scaleX(1.35f).scaleY(1.35f)
                        .setDuration(120)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .withEndAction {
                            ivFavourite.animate()
                                .scaleX(1f).scaleY(1f)
                                .setDuration(100)
                                .setInterpolator(AccelerateDecelerateInterpolator())
                                .start()
                        }
                        .start()
                    bindFavouriteStar(item.copy(isFavourite = newFavourite))
                    item.isFavourite = newFavourite
                    val position = bindingAdapterPosition
                    if (position != RecyclerView.NO_POSITION) {
                        notifyItemChanged(position)
                    }
                    listener.onFavouriteToggled(item.countryCode, item.countryName,newFavourite)
                }

                layoutCountryHeader.setOnClickListener {
                    val code = item.countryCode
                    val nowExpanded: Boolean
                    if (expandedCountries.contains(code)) {
                        expandedCountries.remove(code)
                        nowExpanded = false
                    } else {
                        expandedCountries.add(code)
                        nowExpanded = true
                    }
                    rvServers.visibility = if (nowExpanded) View.VISIBLE else View.GONE
                    ivExpandArrow.animate().rotation(if (nowExpanded) 180f else 0f).start()
                }
            }
        }

        private fun bindFavouriteStar(item: CountryItem) {
            binding.apply {
                if (item.isFavourite) {
                    ivFavourite.setImageResource(R.drawable.ic_star_filled)
                    // The filled star vector uses ?attr/colorGolden as its fillColor,
                    // so no explicit tint override is needed — clear any residual tint.
                    ivFavourite.imageTintList = null
                } else {
                    ivFavourite.setImageResource(R.drawable.ic_star_outline)
                    // Outline star is tinted in the vector via ?attr/primaryLightColorText,
                    // but set tint explicitly here for robustness at runtime.
                    ivFavourite.imageTintList = ContextCompat.getColorStateList(
                        itemView.context, android.R.color.transparent
                    )
                    ivFavourite.imageTintList = null
                }
            }
        }
    }

    /**
     * Adapter for server groups (merged by key) under each country card.
     */
    private class ServerGroupAdapter(
        private val listener: CitySelectionListener
    ) : RecyclerView.Adapter<ServerGroupAdapter.ServerGroupViewHolder>() {

        private val items = mutableListOf<ServerGroup>()

        /**
         * True when the RPN proxy is stopped, taps are redirected to
         * [CitySelectionListener.onProxyStoppedItemTapped] instead of selecting.
         *
         * Plain private field (no `is` prefix) to avoid the JVM setter name clash.
         */
        private var proxyStopped: Boolean = false

        fun setProxyStopped(stopped: Boolean) {
            if (proxyStopped == stopped) return
            proxyStopped = stopped
            notifyItemRangeChanged(0, items.size)
        }

        fun submitList(newItems: List<ServerGroup>) {
            val old = items.toList()
            val diff = DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = newItems.size
                override fun areItemsTheSame(o: Int, n: Int) = old[o].key == newItems[n].key
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == newItems[n]
            })
            items.clear()
            items.addAll(newItems)
            diff.dispatchUpdatesTo(this)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerGroupViewHolder {
            val binding = ItemServerGroupBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return ServerGroupViewHolder(binding)
        }

        override fun onBindViewHolder(holder: ServerGroupViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        inner class ServerGroupViewHolder(
            private val binding: ItemServerGroupBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(group: ServerGroup) {
                binding.apply {
                    tvCityName.text = group.city.capitalizeWords()

                    if (group.serverCount > 1) {
                        tvServerCount.visibility = View.VISIBLE
                        tvServerCount.text = itemView.context.resources.getQuantityString(
                            R.plurals.server_count,
                            group.serverCount,
                            group.serverCount
                        )
                    } else {
                        tvServerCount.visibility = View.GONE
                    }

                    if (group.avgLink > 0) {
                        chipLinkSpeed.visibility = View.VISIBLE
                        val (speedStr, speedAttr) = speedInfo(group.avgLink)
                        chipLinkSpeed.text = speedStr
                        chipLinkSpeed.chipBackgroundColor =
                            ColorStateList.valueOf(fetchColor(itemView.context, speedAttr))
                    } else {
                        chipLinkSpeed.visibility = View.GONE
                    }

                    if (group.avgLoad > 0) {
                        tvLoad.visibility = View.VISIBLE
                        val (loadStr, loadAttr) = loadInfo(group.avgLoad)
                        tvLoad.text = loadStr
                        tvLoad.setTextColor(fetchColor(itemView.context, loadAttr))
                    } else {
                        tvLoad.visibility = View.GONE
                    }

                    checkboxServer.isChecked = group.isEnabled

                    if (proxyStopped) {
                        // Visually indicate the server cannot be selected right now.
                        checkboxServer.isEnabled = false
                        checkboxServer.alpha = 0.4f
                        // Any tap opens the settings sheet so the user can restart the proxy.
                        root.setOnClickListener { listener.onProxyStoppedItemTapped() }
                    } else {
                        checkboxServer.isEnabled = true
                        checkboxServer.alpha = 1f
                        root.setOnClickListener {
                            listener.onCitySelected(group.servers.first(), !group.isEnabled)
                        }
                    }
                }
            }

            /**
             * Returns (formattedSpeed, tierLabel, chipBackgroundAttr) for [linkMbps].
             *
             * Tier thresholds (matching common datacenter NIC grades):
             *   ≥ 10 000 Mbps (10 Gbps)
             *   ≥ 1 000 Mbps (1 Gbps)
             *   ≥ 100 Mbps
             *   ≥ 10 Mbps
             *   > 0 Mbps
             */
            private fun speedInfo(linkMbps: Int): Pair<String, Int> {
                val formatted: String
                val attr: Int

                when {
                    linkMbps >= 10_000 -> {
                        val gbps = linkMbps / 1_000.0
                        formatted = String.format(Locale.US, "%.0f Gbps", gbps)
                        attr = R.attr.chipTextPositive
                    }
                    linkMbps >= 1_000 -> {
                        val gbps = linkMbps / 1_000.0
                        formatted = if (gbps == gbps.toLong().toDouble())
                            String.format(Locale.US, "%.0f Gbps", gbps)
                        else
                            String.format(Locale.US, "%.1f Gbps", gbps)
                        attr = R.attr.accentGood
                    }
                    linkMbps >= 100 -> {
                        formatted = "$linkMbps Mbps"
                        attr = R.attr.chipTextNeutral
                    }
                    linkMbps >= 10 -> {
                        formatted = "$linkMbps Mbps"
                        attr = R.attr.chipTextNeutral
                    }
                    else -> {
                        formatted = "$linkMbps Mbps"
                        attr = R.attr.chipTextNegative
                    }
                }
                return Pair(formatted, attr)
            }

            /**
             * Returns (displayText, textColorAttr) for [loadPercent].
             *
             * Tier thresholds:
             *   ≤ 20 → Light
             *   ≤ 40 → Normal
             *   ≤ 60 → Busy
             *   ≤ 80 → Very Busy
             *   > 80 → Overloaded
             */
            private fun loadInfo(loadPercent: Int): Pair<String, Int> {
                val ctx = itemView.context
                val label: String
                val attr: Int

                when {
                    loadPercent <= 20 -> {
                        label = "$loadPercent% · ${ctx.getString(R.string.server_load_light)}"
                        attr  = R.attr.chipTextPositive
                    }
                    loadPercent <= 40 -> {
                        label = "$loadPercent% · ${ctx.getString(R.string.server_load_normal)}"
                        attr  = R.attr.accentGood
                    }
                    loadPercent <= 60 -> {
                        label = "$loadPercent% · ${ctx.getString(R.string.server_load_busy)}"
                        attr  = R.attr.chipTextNeutral
                    }
                    loadPercent <= 80 -> {
                        label = "$loadPercent% · ${ctx.getString(R.string.server_load_very_busy)}"
                        attr  = R.attr.chipTextNegative
                    }
                    else -> {
                        label = "$loadPercent% · ${ctx.getString(R.string.server_load_overloaded)}"
                        attr  = R.attr.chipTextNegative
                    }
                }
                return Pair(label, attr)
            }
        }
    }
}
