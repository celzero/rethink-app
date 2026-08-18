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

import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.Event
import com.celzero.bravedns.database.Severity
import com.celzero.bravedns.databinding.ItemEventBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventsAdapter(private val context: Context) :
    PagingDataAdapter<Event, EventsAdapter.EventViewHolder>(EventDiffCallback()) {

    companion object {
        private const val ANIMATION_DURATION = 300L
        private const val ROTATION_EXPANDED = 180f
        private const val ROTATION_COLLAPSED = 0f
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val binding = ItemEventBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return EventViewHolder(binding)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        val event = getItem(position)
        if (event != null) {
            holder.bind(event)
        }
    }

    inner class EventViewHolder(private val binding: ItemEventBinding) :
        RecyclerView.ViewHolder(binding.root) {

        private var isExpanded = false

        fun bind(event: Event) {
            // Set tag for scroll header
            binding.root.tag = event.timestamp

            // Reset expansion state for recycled views
            isExpanded = false
            binding.detailsContainer.visibility = View.GONE
            binding.expandIcon.rotation = ROTATION_COLLAPSED

            // Set severity indicator color and icon
            setSeverityIndicator(event.severity)

            // Set event type
            binding.eventTypeChip.text = event.eventType.name.replace("_", " ")

            // Set severity badge
            binding.severityBadge.text = event.severity.name
            setSeverityBadgeColor(event.severity)

            // Set timestamp
            binding.timestampText.text = formatTimestamp(event.timestamp)

            // Set source
            binding.sourceText.text = event.source.name

            // Show user action indicator if applicable
            binding.userActionIcon.visibility = if (event.userAction) View.VISIBLE else View.GONE

            // Set message
            binding.messageText.text = event.message

            // Handle details
            if (!event.details.isNullOrBlank()) {
                binding.detailsText.text = event.details
                // Make card clickable to expand
                binding.root.setOnClickListener {
                    toggleExpansion()
                }
            } else {
                binding.root.setOnClickListener(null)
                binding.expandIcon.visibility = View.GONE
            }

            // Long press to copy message
            binding.root.setOnLongClickListener {
                copyToClipboard(event.message)
                true
            }
        }

        private fun setSeverityIndicator(severity: Severity) {
            val color = when (severity) {
                Severity.LOW -> 0xFF4CAF50.toInt() // Green
                Severity.MEDIUM -> 0xFFFFC107.toInt() // Amber/Yellow
                Severity.HIGH -> 0xFFFF9800.toInt() // Orange
                Severity.CRITICAL -> 0xFFF44336.toInt() // Red
            }
            binding.severityIndicator.setBackgroundColor(color)

            val iconRes = when (severity) {
                Severity.LOW -> R.drawable.ic_tick_normal
                Severity.MEDIUM -> R.drawable.ic_app_info_accent
                Severity.HIGH -> R.drawable.ic_block_accent
                Severity.CRITICAL -> R.drawable.ic_block
            }
            binding.severityIcon.setImageResource(iconRes)
            binding.severityIcon.setColorFilter(color)
        }

        private fun setSeverityBadgeColor(severity: Severity) {
            val color = when (severity) {
                Severity.LOW -> 0xFF4CAF50.toInt() // Green
                Severity.MEDIUM -> 0xFFFFC107.toInt() // Amber/Yellow
                Severity.HIGH -> 0xFFFF9800.toInt() // Orange
                Severity.CRITICAL -> 0xFFF44336.toInt() // Red
            }
            binding.severityBadge.setBackgroundColor(color)
        }

        private fun formatTimestamp(timestamp: Long): String {
            val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }

        private fun toggleExpansion() {
            isExpanded = !isExpanded

            // Animate expand icon rotation
            val rotation = if (isExpanded) ROTATION_EXPANDED else ROTATION_COLLAPSED
            ObjectAnimator.ofFloat(binding.expandIcon, "rotation", rotation).apply {
                duration = ANIMATION_DURATION
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }

            // Animate details container visibility
            if (isExpanded) {
                binding.detailsContainer.visibility = View.VISIBLE
                binding.detailsContainer.alpha = 0f
                binding.detailsContainer.animate()
                    .alpha(1f)
                    .setDuration(ANIMATION_DURATION)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            } else {
                binding.detailsContainer.animate()
                    .alpha(0f)
                    .setDuration(ANIMATION_DURATION)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        binding.detailsContainer.visibility = View.GONE
                    }
                    .start()
            }
        }

        private fun copyToClipboard(text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Event Message", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }
    }

    class EventDiffCallback : DiffUtil.ItemCallback<Event>() {
        override fun areItemsTheSame(oldItem: Event, newItem: Event): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Event, newItem: Event): Boolean {
            return oldItem == newItem
        }
    }
}

