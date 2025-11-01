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
package com.celzero.bravedns.ui.location
/*
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.databinding.ItemCountryCardBinding

class CountryAdapter(
    private val countries: List<Country>,
    private val listener: OnServerSelectionChangeListener
) : RecyclerView.Adapter<CountryAdapter.CountryViewHolder>() {

    interface OnServerSelectionChangeListener {
        fun onServerSelectionChanged(server: ServerLocation, isSelected: Boolean)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CountryViewHolder {
        val binding = ItemCountryCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return CountryViewHolder(binding)
    }

    override fun onBindViewHolder(holder: CountryViewHolder, position: Int) {
        val country = countries[position]
        holder.bind(country)
    }

    override fun getItemCount(): Int = countries.size

    inner class CountryViewHolder(private val binding: ItemCountryCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.rvServers.layoutManager = LinearLayoutManager(binding.root.context)

            binding.layoutCountryHeader.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val country = countries[position]
                    toggleExpansion(country)
                }
            }
        }

        fun bind(country: Country) {
            binding.tvCountryName.text = country.name
            binding.tvServerCount.text = "${country.serverCount} servers available"

            // Set flag placeholder
            binding.ivCountryFlag.setImageResource(android.R.drawable.ic_menu_mapmode)

            // Setup server adapter
            val serverAdapter = ServerAdapter(country.servers, object : ServerAdapter.OnServerSelectionChangeListener {
                override fun onServerSelectionChanged(server: ServerLocation, isSelected: Boolean) {
                    listener.onServerSelectionChanged(server, isSelected)
                }
            })
            binding.rvServers.adapter = serverAdapter

            // Set expansion state
            binding.rvServers.visibility = if (country.isExpanded) View.VISIBLE else View.GONE
            binding.ivExpandArrow.rotation = if (country.isExpanded) 180f else 0f
        }

        private fun toggleExpansion(country: Country) {
            country.isExpanded = !country.isExpanded

            // Animate arrow rotation
            val arrowRotation = ObjectAnimator.ofFloat(
                binding.ivExpandArrow,
                "rotation",
                if (country.isExpanded) 0f else 180f,
                if (country.isExpanded) 180f else 0f
            ).apply {
                duration = 300
                interpolator = AccelerateDecelerateInterpolator()
            }
            arrowRotation.start()

            // Animate server list visibility
            if (country.isExpanded) {
                binding.rvServers.visibility = View.VISIBLE
                binding.rvServers.alpha = 0f
                ObjectAnimator.ofFloat(binding.rvServers, "alpha", 0f, 1f).apply {
                    duration = 250
                    startDelay = 50
                }.start()
            } else {
                ObjectAnimator.ofFloat(binding.rvServers, "alpha", 1f, 0f).apply {
                    duration = 200
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            binding.rvServers.visibility = View.GONE
                        }
                    })
                }.start()
            }
        }
    }
}
*/