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

import Logger
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.BlockedAppInfo
import com.celzero.bravedns.databinding.ItemBlockedAppBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class BubbleBlockedAppsAdapter(
    private val onAllowClick: (BlockedAppInfo) -> Unit
) : PagingDataAdapter<BlockedAppInfo, BubbleBlockedAppsAdapter.BlockedAppViewHolder>(DIFF_CALLBACK) {

    companion object {
        private const val TAG = "BubbleBlockedAppsAdapter"

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<BlockedAppInfo>() {
            override fun areItemsTheSame(oldItem: BlockedAppInfo, newItem: BlockedAppInfo): Boolean {
                return oldItem.uid == newItem.uid
            }

            override fun areContentsTheSame(oldItem: BlockedAppInfo, newItem: BlockedAppInfo): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BlockedAppViewHolder {
        val binding = ItemBlockedAppBinding.inflate(
            LayoutInflater.from(parent.context),
            parent,
            false
        )
        return BlockedAppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BlockedAppViewHolder, position: Int) {
        val app = getItem(position)
        if (app != null) {
            holder.bind(app)
        }
    }

    inner class BlockedAppViewHolder(
        private val binding: ItemBlockedAppBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(app: BlockedAppInfo) {
            // Enable marquee reliably (focus is usually held by other views like buttons).
            binding.blockedAppName.isSelected = true

            binding.blockedAppName.text = app.appName
            binding.blockedAppPackage.text = app.packageName
            binding.blockedAppCount.text = itemView.context.getString(
                R.string.bubble_blocked_count,
                app.count
            )
            binding.blockedAppTime.text = getTimeAgo(app.lastBlocked)

            // Load app icon
            binding.blockedAppIcon.setImageDrawable(getAppIcon(app.packageName))

            // Set allow button click listener
            binding.blockedAppAllowBtn.setOnClickListener {
                onAllowClick(app)
            }
        }

        private fun getAppIcon(packageName: String): Drawable {
            return try {
                if (packageName != "Unknown") {
                    itemView.context.packageManager.getApplicationIcon(packageName)
                } else {
                    ContextCompat.getDrawable(itemView.context, R.drawable.default_app_icon)!!
                }
            } catch (_: PackageManager.NameNotFoundException) {
                Logger.e(TAG, "App icon not found for $packageName")
                ContextCompat.getDrawable(itemView.context, R.drawable.default_app_icon)!!
            }
        }

        private fun getTimeAgo(timestamp: Long): String {
            val now = System.currentTimeMillis()
            val diff = now - timestamp

            return when {
                diff < TimeUnit.MINUTES.toMillis(1) -> {
                    itemView.context.getString(R.string.bubble_time_just_now)
                }
                diff < TimeUnit.HOURS.toMillis(1) -> {
                    val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
                    itemView.context.getString(R.string.bubble_time_minutes_ago, minutes)
                }
                diff < TimeUnit.DAYS.toMillis(1) -> {
                    val hours = TimeUnit.MILLISECONDS.toHours(diff)
                    itemView.context.getString(R.string.bubble_time_hours_ago, hours)
                }
                else -> {
                    val dateFormat = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
                    dateFormat.format(Date(timestamp))
                }
            }
        }
    }
}
