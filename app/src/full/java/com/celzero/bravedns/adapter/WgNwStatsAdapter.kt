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
import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConnection
import com.celzero.bravedns.database.AppInfo
import com.celzero.bravedns.databinding.ListItemStatisticsSummaryBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.util.UIUtils.fetchToggleBtnColors
import com.celzero.bravedns.util.Utilities
import com.celzero.bravedns.util.Utilities.isAtleastN
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log2

class WgNwStatsAdapter(private val context: Context) :
    PagingDataAdapter<AppConnection, WgNwStatsAdapter.WgNwStatsAdapterViewHolder>(DIFF_CALLBACK) {

    private var maxValue: Int = 0
    companion object {
        private val TAG = WgNwStatsAdapter::class.simpleName
        private const val PERCENTAGE_MULTIPLIER = 100

        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<AppConnection>() {
                override fun areItemsTheSame(old: AppConnection, new: AppConnection): Boolean {
                    return (old == new)
                }

                override fun areContentsTheSame(old: AppConnection, new: AppConnection): Boolean {
                    return (old == new)
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WgNwStatsAdapterViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ListItemStatisticsSummaryBinding.inflate(inflater, parent, false)
        return WgNwStatsAdapterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WgNwStatsAdapterViewHolder, position: Int) {
        val conn = getItem(position) ?: return
        holder.bind(conn)
    }

    private fun calculatePercentage(c: Double): Int {
        val value = (log2(c) * PERCENTAGE_MULTIPLIER).toInt()
        // maxValue will be based on the count returned by the database query (order by count desc)
        if (value > maxValue) {
            maxValue = value
        }
        return if (maxValue == 0) {
            0
        } else {
            (value * PERCENTAGE_MULTIPLIER / maxValue)
        }
    }

    inner class WgNwStatsAdapterViewHolder(private val b: ListItemStatisticsSummaryBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(conn: AppConnection) {
            Logger.d(LOG_TAG_UI, "$TAG: Binding data for ${conn.uid}, ${conn.appOrDnsName}")
            setName(conn)
            setIcon(conn)
            showDataUsage(conn)
            setProgress(conn)
            setConnectionCount(conn)
            // remove right arrow indicator as there won't be any click action
            b.ssIndicator.visibility = View.INVISIBLE
        }

        private fun setConnectionCount(conn: AppConnection) {
            b.ssCount.text = conn.count.toString()
        }

        private fun showDataUsage(conn: AppConnection) {
            if (conn.downloadBytes == null || conn.uploadBytes == null) {
                b.ssName.visibility = View.GONE
                b.ssCount.text = conn.count.toString()
                return
            }

            b.ssName.visibility = View.VISIBLE
            val download =
                context.getString(
                    R.string.symbol_download,
                    Utilities.humanReadableByteCount(conn.downloadBytes, true)
                )
            val upload =
                context.getString(
                    R.string.symbol_upload,
                    Utilities.humanReadableByteCount(conn.uploadBytes, true)
                )
            val total = context.getString(R.string.two_argument, upload, download)
            b.ssDataUsage.text = total
            b.ssCount.text = conn.count.toString()
        }

        private fun setIcon(conn: AppConnection) {
            io {
                val appInfo = FirewallManager.getAppInfoByUid(conn.uid)
                uiCtx {
                    b.ssIcon.visibility = View.VISIBLE
                    b.ssFlag.visibility = View.GONE
                    loadAppIcon(
                        Utilities.getIcon(
                            context,
                            appInfo?.packageName ?: "",
                            appInfo?.appName ?: ""
                        )
                    )
                }
            }
        }

        private fun setName(conn: AppConnection) {
            io {
                val appInfo = FirewallManager.getAppInfoByUid(conn.uid)
                uiCtx {
                    val appName = getAppName(conn, appInfo)
                    b.ssName.visibility = View.VISIBLE
                    b.ssName.text = appName
                }
            }
        }

        private fun getAppName(conn: AppConnection, appInfo: AppInfo?): String? {
            return if (conn.appOrDnsName.isNullOrEmpty()) {
                if (appInfo?.appName.isNullOrEmpty()) {
                    context.getString(R.string.network_log_app_name_unnamed, "($conn.uid)")
                } else {
                    appInfo?.appName
                }
            } else {
                conn.appOrDnsName
            }
        }

        private fun setProgress(conn: AppConnection) {
            val d = conn.downloadBytes ?: 0L
            val u = conn.uploadBytes ?: 0L

            val c = (d + u).toDouble()
            val percentage = calculatePercentage(c)
            b.ssProgress.setIndicatorColor(
                fetchToggleBtnColors(context, R.color.accentGood)
            )
            if (isAtleastN()) {
                b.ssProgress.setProgress(percentage, true)
            } else {
                b.ssProgress.progress = percentage
            }
        }

        private fun loadAppIcon(drawable: Drawable?) {
            ui {
                Glide.with(context)
                    .load(drawable)
                    .error(Utilities.getDefaultIcon(context))
                    .into(b.ssIcon)
            }
        }
    }

    private fun io(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.IO) { f() }
    }

    private fun ui(f: suspend () -> Unit) {
        (context as LifecycleOwner).lifecycleScope.launch(Dispatchers.Main) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }
}
