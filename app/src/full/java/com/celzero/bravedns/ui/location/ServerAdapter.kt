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
import android.animation.ObjectAnimator
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.databinding.ItemServerBinding

class ServerAdapter(
    private val servers: List<ServerLocation>,
    private val listener: OnServerSelectionChangeListener
) : RecyclerView.Adapter<ServerAdapter.ServerViewHolder>() {

    interface OnServerSelectionChangeListener {
        fun onServerSelectionChanged(server: ServerLocation, isSelected: Boolean)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val binding = ItemServerBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ServerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        val server = servers[position]
        holder.bind(server)
    }

    override fun getItemCount(): Int = servers.size

    inner class ServerViewHolder(private val binding: ItemServerBinding) :
        RecyclerView.ViewHolder(binding.root) {

        init {
            binding.root.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    val server = servers[position]
                    val newState = !server.isSelected
                    listener.onServerSelectionChanged(server, newState)
                }
            }
        }

        fun bind(server: ServerLocation) {
            binding.tvServerLocation.text = server.name
            binding.tvServerLatency.text = server.latency

            // Animate checkbox change
            if (binding.cbServerSelected.isChecked != server.isSelected) {
                val scaleX = ObjectAnimator.ofFloat(binding.cbServerSelected, "scaleX", 0.8f, 1.0f)
                val scaleY = ObjectAnimator.ofFloat(binding.cbServerSelected, "scaleY", 0.8f, 1.0f)
                scaleX.duration = 150
                scaleY.duration = 150
                scaleX.start()
                scaleY.start()
            }

            binding.cbServerSelected.isChecked = server.isSelected
        }
    }
}
*/