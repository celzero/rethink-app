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
import com.celzero.bravedns.databinding.ListItemStatisticsSummaryBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.ui.activity.AppInfoActivity
import com.celzero.bravedns.ui.activity.DomainConnectionsActivity
import com.celzero.bravedns.ui.activity.NetworkLogsActivity
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DomainConnectionsAdapter(private val context: Context, private val type: DomainConnectionsActivity.InputType) :
    PagingDataAdapter<AppConnection, DomainConnectionsAdapter.DomainConnectionsViewHolder>(
        DIFF_CALLBACK
    ) {

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
    ): DomainConnectionsViewHolder {
        val itemBinding =
            ListItemStatisticsSummaryBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        return DomainConnectionsViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: DomainConnectionsViewHolder, position: Int) {
        val appNetworkActivity = getItem(position) ?: return
        holder.bind(appNetworkActivity)
    }

    inner class DomainConnectionsViewHolder(private val b: ListItemStatisticsSummaryBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun bind(dc: AppConnection) {
            io {
                val appInfo = FirewallManager.getAppInfoByUid(dc.uid)
                uiCtx {
                    if (dc.appOrDnsName.isNullOrEmpty()) {
                        b.ssDataUsage.text = appInfo?.appName ?: context.getString(
                            R.string.network_log_app_name_unnamed,
                            "(${dc.uid})"
                        )
                    } else {
                        b.ssDataUsage.text = dc.appOrDnsName
                    }
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
            if (dc.downloadBytes == null || dc.uploadBytes == null) {
                return
            }

            val download =
                context.getString(
                    R.string.symbol_download,
                    Utilities.humanReadableByteCount(dc.downloadBytes, true)
                )
            val upload =
                context.getString(
                    R.string.symbol_upload,
                    Utilities.humanReadableByteCount(dc.uploadBytes, true)
                )
            val total = context.getString(R.string.two_argument, upload, download)
            b.ssName.text = total
            b.ssCount.text = dc.count.toString()

            b.ssProgress.visibility = View.GONE

            b.ssContainer.setOnClickListener {
                io {
                    if (isUnknownApp(dc)) {
                        uiCtx {
                            val intent = Intent(context, NetworkLogsActivity::class.java)
                            intent.putExtra(Constants.VIEW_PAGER_SCREEN_TO_LOAD, NetworkLogsActivity.Tabs.NETWORK_LOGS.screen)
                            intent.putExtra(Constants.SEARCH_QUERY, dc.appOrDnsName)
                            context.startActivity(intent)
                        }
                    } else {
                        uiCtx {
                            val intent = Intent(context, AppInfoActivity::class.java)
                            intent.putExtra(AppInfoActivity.INTENT_UID, dc.uid)
                            context.startActivity(intent)
                        }
                    }
                }
            }
        }

        private suspend fun isUnknownApp(appConnection: AppConnection): Boolean {
            val appInfo = FirewallManager.getAppInfoByUid(appConnection.uid)
            return appInfo == null
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