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

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.data.AllowedAppInfo
import com.celzero.bravedns.databinding.ItemAllowedAppBinding

class BubbleAllowedAppsAdapter(
    private val onRemoveClick: (AllowedAppInfo) -> Unit
) : PagingDataAdapter<AllowedAppInfo, BubbleAllowedAppsAdapter.ViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AllowedAppInfo>() {
            override fun areItemsTheSame(oldItem: AllowedAppInfo, newItem: AllowedAppInfo): Boolean {
                return oldItem.uid == newItem.uid
            }

            override fun areContentsTheSame(oldItem: AllowedAppInfo, newItem: AllowedAppInfo): Boolean {
                return oldItem == newItem
            }
        }
    }

    inner class ViewHolder(private val binding: ItemAllowedAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(app: AllowedAppInfo) {
            binding.allowedAppName.text = app.appName

            // Calculate time remaining
            val now = System.currentTimeMillis()
            val expiresAt = app.allowedAt + (15 * 60 * 1000) // 15 minutes
            val remaining = (expiresAt - now) / 1000 / 60 // minutes

            binding.allowedTimeRemaining.text = if (remaining > 0) {
                "$remaining min${if (remaining != 1L) "s" else ""} remaining"
            } else {
                "Expired"
            }

            // Load app icon
            try {
                val packageManager = binding.root.context.packageManager
                val icon = packageManager.getApplicationIcon(app.packageName)
                binding.allowedAppIcon.setImageDrawable(icon)
            } catch (_: Exception) {
                binding.allowedAppIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            binding.allowedRemoveBtn.setOnClickListener {
                onRemoveClick(app)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemAllowedAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val app = getItem(position)
        if (app != null) {
            holder.bind(app)
        }
    }
}

