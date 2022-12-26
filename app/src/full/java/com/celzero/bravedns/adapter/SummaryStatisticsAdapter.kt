/*
 * Copyright 2022 RethinkDNS and its authors
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
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.databinding.ListItemStatisticsSummaryBinding
import com.celzero.bravedns.glide.GlideApp
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.SummaryStatisticsFragment
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.Companion.isAtleastN

class SummaryStatisticsAdapter(
    private val context: Context,
    private val type: SummaryStatisticsFragment.SummaryStatisticsType
) :
    PagingDataAdapter<AppConnection, SummaryStatisticsAdapter.AppNetworkActivityViewHolder>(
        DIFF_CALLBACK
    ) {

    private var maxValue: Int = 0

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<AppConnection>() {
                override fun areItemsTheSame(
                    oldConnection: AppConnection,
                    newConnection: AppConnection
                ): Boolean {
                    return (oldConnection == newConnection)
                }

                override fun areContentsTheSame(
                    oldConnection: AppConnection,
                    newConnection: AppConnection
                ): Boolean {
                    return (oldConnection == newConnection)
                }
            }
    }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): AppNetworkActivityViewHolder {
        val itemBinding =
            ListItemStatisticsSummaryBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        return AppNetworkActivityViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: AppNetworkActivityViewHolder, position: Int) {
        val appNetworkActivity = getItem(position) ?: return
        holder.bind(appNetworkActivity)
    }

    private fun calculatePercentage(value: Int): Int {
        if (value > maxValue) {
            maxValue = value
        }
        return if (maxValue == 0) {
            0
        } else {
            (value * 100 / maxValue)
        }
    }

    inner class AppNetworkActivityViewHolder(
        private val itemBinding: ListItemStatisticsSummaryBinding
    ) : RecyclerView.ViewHolder(itemBinding.root) {

        fun bind(appConnection: AppConnection) {
            setName(appConnection)
            setIcon(appConnection)
            setProgress(appConnection.count, appConnection.blocked)
            setConnectionCount(appConnection)
        }

        private fun setConnectionCount(appConnection: AppConnection) {
            itemBinding.ssCount.text = appConnection.count.toString()
        }

        private fun setIcon(appConnection: AppConnection) {
            when (type) {
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONNECTED_APPS -> {
                    val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
                    itemBinding.ssIcon.visibility = View.VISIBLE
                    itemBinding.ssFlag.visibility = View.GONE
                    loadAppIcon(
                        Utilities.getIcon(
                            context,
                            appInfo?.packageInfo ?: "",
                            appInfo?.appName ?: ""
                        )
                    )
                }
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_APPS -> {
                    val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
                    itemBinding.ssIcon.visibility = View.VISIBLE
                    itemBinding.ssFlag.visibility = View.GONE
                    loadAppIcon(
                        Utilities.getIcon(
                            context,
                            appInfo?.packageInfo ?: "",
                            appInfo?.appName ?: ""
                        )
                    )
                }
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> {
                    itemBinding.ssIcon.visibility = View.GONE
                    itemBinding.ssFlag.visibility = View.VISIBLE
                    itemBinding.ssFlag.text = appConnection.flag
                }
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> {
                    itemBinding.ssIcon.visibility = View.GONE
                    itemBinding.ssFlag.visibility = View.VISIBLE
                    itemBinding.ssFlag.text = appConnection.flag
                }
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_IPS -> {
                    itemBinding.ssIcon.visibility = View.GONE
                    itemBinding.ssFlag.visibility = View.VISIBLE
                    itemBinding.ssFlag.text = appConnection.flag
                }
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_IPS -> {
                    itemBinding.ssIcon.visibility = View.GONE
                    itemBinding.ssFlag.visibility = View.VISIBLE
                    itemBinding.ssFlag.text = appConnection.flag
                }
            }
        }

        private fun setName(appConnection: AppConnection) {
            when (type) {
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONNECTED_APPS -> {
                    val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
                    itemBinding.ssName.text = appInfo?.appName
                }
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_APPS -> {
                    val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
                    itemBinding.ssName.text = appInfo?.appName
                }
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_DOMAINS -> {
                    itemBinding.ssName.text = appConnection.dnsQuery
                }
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_DOMAINS -> {
                    itemBinding.ssName.text = appConnection.dnsQuery
                }
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_CONTACTED_IPS -> {
                    itemBinding.ssName.text = appConnection.ipAddress
                }
                SummaryStatisticsFragment.SummaryStatisticsType.MOST_BLOCKED_IPS -> {
                    itemBinding.ssName.text = appConnection.ipAddress
                }
            }
        }

        private fun setProgress(count: Int, isBlocked: Boolean = false) {
            val percentage = calculatePercentage(count)
            if (isBlocked) {
                itemBinding.ssProgress.setIndicatorColor(
                    Utilities.fetchToggleBtnColors(context, R.color.accentBad)
                )
            } else {
                itemBinding.ssProgress.setIndicatorColor(
                    Utilities.fetchToggleBtnColors(context, R.color.accentGood)
                )
            }
            if (isAtleastN()) {
                itemBinding.ssProgress.setProgress(percentage, true)
            } else {
                itemBinding.ssProgress.progress = percentage
            }
        }

        private fun loadAppIcon(drawable: Drawable?) {
            GlideApp.with(context)
                .load(drawable)
                .error(Utilities.getDefaultIcon(context))
                .into(itemBinding.ssIcon)
        }
    }
}
