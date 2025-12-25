/*
 * Copyright 2024 RethinkDNS and its authors
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
import Logger.LOG_TAG_UI
import android.content.Context
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.database.ConsoleLog
import com.celzero.bravedns.databinding.ListItemConsoleLogBinding
import com.celzero.bravedns.util.Constants.Companion.TIME_FORMAT_1
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.Utilities

class ConsoleLogAdapter(private val context: Context) :
    PagingDataAdapter<ConsoleLog, ConsoleLogAdapter.ConsoleLogViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<ConsoleLog>() {
                override fun areItemsTheSame(old: ConsoleLog, new: ConsoleLog): Boolean {
                    return old.id == new.id
                }

                override fun areContentsTheSame(old: ConsoleLog, new: ConsoleLog): Boolean {
                    return old == new
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ConsoleLogViewHolder {
        val itemBinding =
            ListItemConsoleLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ConsoleLogViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: ConsoleLogViewHolder, position: Int) {
        // Prevent IndexOutOfBoundsException
        if (position < 0 || position >= itemCount) {
            return
        }

        try {
            val logInfo = getItem(position) ?: return
            holder.update(logInfo)
        } catch (e: IndexOutOfBoundsException) {
            Logger.w(LOG_TAG_UI, "err invalid pos: $position, itemCount: $itemCount")
        }
    }

    inner class ConsoleLogViewHolder(private val b: ListItemConsoleLogBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(log: ConsoleLog) {
            try {
                // SAFETY CHECK: Verify log data is valid
                if (log.message.isEmpty()) return

                // update the textview color with the first letter of the log level
                val logLevel = log.message.firstOrNull() ?: 'V'
                when (logLevel) {
                    'V' ->
                        b.logDetail.setTextColor(
                            UIUtils.fetchColor(context, R.attr.primaryLightColorText)
                        )

                    'D' ->
                        b.logDetail.setTextColor(
                            UIUtils.fetchColor(context, R.attr.primaryLightColorText)
                        )

                    'I' ->
                        b.logDetail.setTextColor(
                            UIUtils.fetchColor(context, R.attr.defaultToggleBtnTxt)
                        )

                    'W' ->
                        b.logDetail.setTextColor(
                            UIUtils.fetchColor(context, R.attr.firewallWhiteListToggleBtnTxt)
                        )

                    'E' ->
                        b.logDetail.setTextColor(
                            UIUtils.fetchColor(context, R.attr.firewallBlockToggleBtnTxt)
                        )

                    else ->
                        b.logDetail.setTextColor(
                            UIUtils.fetchColor(context, R.attr.primaryLightColorText)
                        )
                }
                b.logDetail.text = log.message
                if (DEBUG) {
                    b.logTimestamp.text = "${log.id}\n${Utilities.convertLongToTime(log.timestamp, TIME_FORMAT_1)}"
                } else {
                    b.logTimestamp.text = Utilities.convertLongToTime(log.timestamp, TIME_FORMAT_1)
                }
            } catch (e: Exception) {
                Logger.w("ConsoleLogAdapter", "Error updating view holder: ${e.message}")
            }
        }
    }
}
