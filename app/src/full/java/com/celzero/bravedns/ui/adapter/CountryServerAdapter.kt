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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.database.CountryConfig
import com.celzero.bravedns.databinding.ListItemCountryCardBinding

/**
 * Adapter showing servers grouped by country using list_item_country_card.
 * Each country row can be expanded to reveal its city servers.
 */
class CountryServerAdapter(
    private var countries: List<CountryItem>,
    private val listener: CitySelectionListener
) : RecyclerView.Adapter<CountryServerAdapter.CountryViewHolder>() {

    interface CitySelectionListener {
        fun onCitySelected(server: CountryConfig, isSelected: Boolean)
    }

    data class CityItem(val server: CountryConfig)

    data class CountryItem(
        val countryCode: String,
        val countryName: String,
        val flagEmoji: String,
        val cities: List<CityItem>
    )

    // track which countries are expanded by country code
    private val expandedCountries = mutableSetOf<String>()

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
        Logger.v(LOG_TAG_UI, "CountryServerAdapter.bind: ${country.countryName} with ${country.cities.size} cities")
        holder.bind(country, expandedCountries.contains(country.countryCode))
    }

    override fun getItemCount(): Int = countries.size

    fun updateCountries(newCountries: List<CountryItem>) {
        countries = newCountries
        // retain expansion where possible
        val newCodes = newCountries.map { it.countryCode }.toSet()
        expandedCountries.retainAll(newCodes)
        notifyDataSetChanged()
    }

    inner class CountryViewHolder(
        private val binding: ListItemCountryCardBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        private var cityAdapter: CityServerAdapter? = null

        fun bind(item: CountryItem, isExpanded: Boolean) {
            binding.apply {
                tvCountryFlag.text = item.flagEmoji
                tvCountryName.text = item.countryName
                tvServerCount.text =
                    itemView.context.resources.getQuantityString(
                        com.celzero.bravedns.R.plurals.server_count,
                        item.cities.size,
                        item.cities.size
                    )

                if (cityAdapter == null) {
                    cityAdapter = CityServerAdapter(listener)
                    rvServers.layoutManager = LinearLayoutManager(itemView.context)
                    rvServers.adapter = cityAdapter
                    rvServers.isNestedScrollingEnabled = false
                }
                cityAdapter?.submitList(item.cities)

                // expand / collapse state
                rvServers.visibility = if (isExpanded) View.VISIBLE else View.GONE
                ivExpandArrow.rotation = if (isExpanded) 180f else 0f

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
    }

    /**
     * Child adapter for city rows under each country card.
     */
    private class CityServerAdapter(
        private val listener: CitySelectionListener
    ) : RecyclerView.Adapter<CityServerAdapter.CityViewHolder>() {

        private val items = mutableListOf<CityItem>()

        fun submitList(newItems: List<CityItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CityViewHolder {
            val binding = com.celzero.bravedns.databinding.ItemServerBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return CityViewHolder(binding)
        }

        override fun onBindViewHolder(holder: CityViewHolder, position: Int) {
            holder.bind(items[position].server)
        }

        override fun getItemCount(): Int = items.size

        inner class CityViewHolder(
            private val binding: com.celzero.bravedns.databinding.ItemServerBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            fun bind(server: CountryConfig) {
                binding.apply {
                    // Display city name
                    tvCityName.text = server.serverLocation

                    // Display server metrics (latency and load)
                    tvServerMetric.text = buildString {
                        if (server.link > 0) {
                            append("${server.link}ms")
                        }
                        if (server.load > 0) {
                            if (isNotEmpty()) append(" • ")
                            append("${server.load}%")
                        }
                    }.ifEmpty { "—" }

                    // Selection state
                    checkboxServer.isChecked = server.isActive

                    // Click handling
                    root.setOnClickListener {
                        val newState = !server.isActive
                        listener.onCitySelected(server, newState)
                    }
                }
            }
        }
    }
}
