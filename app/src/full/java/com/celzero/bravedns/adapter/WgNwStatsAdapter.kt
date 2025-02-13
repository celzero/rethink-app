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

import Logger.LOG_TAG_UI
import android.content.Context
import android.content.Intent
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
import com.celzero.bravedns.ui.activity.AppInfoActivity
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

        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<AppConnection>() {
            override fun areItemsTheSame(oldItem: AppConnection, newItem: AppConnection): Boolean {
                return oldItem.uid == newItem.uid
            }

            override fun areContentsTheSame(
                oldItem: AppConnection,
                newItem: AppConnection
            ): Boolean {
                return oldItem == newItem
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): WgNwStatsAdapterViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        val binding = ListItemStatisticsSummaryBinding.inflate(inflater, parent, false)
        return WgNwStatsAdapterViewHolder(binding)
    }

    override fun onBindViewHolder(holder: WgNwStatsAdapterViewHolder, position: Int) {
        val item = getItem(position)
        if (item != null) {
            holder.bind(item)
        }
    }

    private fun calculatePercentage(c: Double): Int {
        val value = (log2(c) * 100).toInt()
        // maxValue will be based on the count returned by the database query (order by count desc)
        if (value > maxValue) {
            maxValue = value
        }
        return if (maxValue == 0) {
            0
        } else {
            (value * 100 / maxValue)
        }
    }

    inner class WgNwStatsAdapterViewHolder(private val binding: ListItemStatisticsSummaryBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(appConnection: AppConnection) {
            setName(appConnection)
            io { setIcon(appConnection) }
            showDataUsage(appConnection)
            setProgress(appConnection)
            setConnectionCount(appConnection)
            setupClickListeners(appConnection)
        }

        fun setupClickListeners(appConnection: AppConnection) {
            binding.ssContainer.setOnClickListener {
                startAppInfoActivity(appConnection)
            }
        }

        private fun startAppInfoActivity(appConnection: AppConnection) {
            val intent = Intent(context, AppInfoActivity::class.java)
            intent.putExtra(AppInfoActivity.INTENT_UID, appConnection.uid)
            context.startActivity(intent)
        }

        private fun setConnectionCount(appConnection: AppConnection) {
            binding.ssCount.text = appConnection.count.toString()
        }

        private fun showDataUsage(appConnection: AppConnection) {
            if (appConnection.downloadBytes == null || appConnection.uploadBytes == null) {
                binding.ssName.visibility = View.GONE
                binding.ssCount.text = appConnection.count.toString()
                return
            }

            binding.ssName.visibility = View.VISIBLE
            val download =
                context.getString(
                    R.string.symbol_download,
                    Utilities.humanReadableByteCount(appConnection.downloadBytes, true)
                )
            val upload =
                context.getString(
                    R.string.symbol_upload,
                    Utilities.humanReadableByteCount(appConnection.uploadBytes, true)
                )
            Logger.v(LOG_TAG_UI, "$TAG: Data usage - $download, $upload for ${appConnection.uid}, ${appConnection.appOrDnsName}")
            val total = context.getString(R.string.two_argument, upload, download)
            binding.ssDataUsage.text = total
            binding.ssCount.text = appConnection.count.toString()
        }

        private suspend fun setIcon(appConnection: AppConnection) {
            io {
                val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
                uiCtx {
                    binding.ssIcon.visibility = View.VISIBLE
                    binding.ssFlag.visibility = View.GONE
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

        private fun setName(appConnection: AppConnection) {
            io {
                val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
                uiCtx {
                    val appName = getAppName(appConnection, appInfo)
                    binding.ssName.visibility = View.VISIBLE
                    binding.ssName.text = appName
                }
            }
        }

        private fun getAppName(appConnection: AppConnection, appInfo: AppInfo?): String? {
            return if (appConnection.appOrDnsName.isNullOrEmpty()) {
                if (appInfo?.appName.isNullOrEmpty()) {
                    context.getString(R.string.network_log_app_name_unnamed, "($appConnection.uid)")
                } else {
                    appInfo?.appName
                }
            } else {
                appConnection.appOrDnsName
            }
        }

        private fun setProgress(appConnection: AppConnection) {
            val d = appConnection.downloadBytes ?: 0L
            val u = appConnection.uploadBytes ?: 0L

            val c = (d + u).toDouble()
            val percentage = calculatePercentage(c)
            binding.ssProgress.setIndicatorColor(
                fetchToggleBtnColors(context, R.color.accentGood)
            )
            if (isAtleastN()) {
                binding.ssProgress.setProgress(percentage, true)
            } else {
                binding.ssProgress.progress = percentage
            }
        }

        private fun loadAppIcon(drawable: Drawable?) {
            ui {
                Glide.with(context)
                    .load(drawable)
                    .error(Utilities.getDefaultIcon(context))
                    .into(binding.ssIcon)
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
