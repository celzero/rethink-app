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

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_DNS
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.util.SelectionIndicator
import com.celzero.bravedns.util.StatusTicker
import com.celzero.bravedns.customdownloader.IpInfoDownloader
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.databinding.RethinkEndpointListItemBinding
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity
import com.celzero.bravedns.util.Logger.LOG_TAG_UI
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.clipboardCopy
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class RethinkEndpointAdapter(private val context: Context, private val appConfig: AppConfig) :
    PagingDataAdapter<RethinkDnsEndpoint, RethinkEndpointAdapter.RethinkEndpointViewHolder>(
        DIFF_CALLBACK
    ) {

    var lifecycleOwner: LifecycleOwner? = null

    // RecyclerView callbacks run on the main thread, so no synchronization is needed.
    private val activeHolders = mutableSetOf<RethinkEndpointViewHolder>()

    private val statusHolders = mutableListOf<RethinkEndpointViewHolder>()
    private val statusTicker = StatusTicker()

    companion object {
        private const val ONE_SEC = 1000L
        private const val TAG = "RethinkEndpointAdapter"
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<RethinkDnsEndpoint>() {
                override fun areItemsTheSame(
                    oldConnection: RethinkDnsEndpoint,
                    newConnection: RethinkDnsEndpoint
                ): Boolean {
                    return (oldConnection.url == newConnection.url &&
                            oldConnection.isActive == newConnection.isActive)
                }

                override fun areContentsTheSame(
                    oldConnection: RethinkDnsEndpoint,
                    newConnection: RethinkDnsEndpoint
                ): Boolean {
                    return (oldConnection.url == newConnection.url &&
                            oldConnection.isActive != newConnection.isActive)
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RethinkEndpointViewHolder {
        val itemBinding =
            RethinkEndpointListItemBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        lifecycleOwner = parent.findViewTreeLifecycleOwner()
        return RethinkEndpointViewHolder(itemBinding).also { activeHolders.add(it) }
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        // cancel the shared ticker before dropping the lifecycle owner, otherwise the
        // ticker's own inactivity guard cannot fire (it reads lifecycleOwner)
        statusTicker.cancel()
        statusHolders.clear()
        activeHolders.clear()
        lifecycleOwner = null
    }

    private fun registerForStatusUpdates(holder: RethinkEndpointViewHolder) {
        if (statusHolders.contains(holder)) return
        statusHolders.add(holder)
        if (!ensureStatusTicker()) refreshSelectedStatuses()
    }

    private fun ensureStatusTicker(): Boolean {
        val owner = lifecycleOwner ?: return false
        return statusTicker.start(owner.lifecycleScope, ONE_SEC) { refreshSelectedStatuses() }
    }

    private fun refreshSelectedStatuses(): Boolean {
        val iterator = statusHolders.iterator()
        val active = ArrayList<RethinkEndpointViewHolder>()
        while (iterator.hasNext()) {
            val holder = iterator.next()
            val lifecycleActive =
                lifecycleOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.STARTED) == true
            if (!lifecycleActive || holder.bindingAdapterPosition == RecyclerView.NO_POSITION) {
                iterator.remove()
                continue
            }
            active.add(holder)
        }
        if (active.isEmpty()) {
            statusTicker.cancel()
            return false
        }
        io {
            val state = VpnController.getDnsStatus(Backend.Preferred)
            val status = UIUtils.getDnsStatusStringRes(state)
            uiCtx {
                active.forEach { it.showStatus(status) }
            }
        }
        return true
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleOwner?.lifecycleScope?.launch(Dispatchers.IO) { f() }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        val owner = lifecycleOwner ?: return

        withContext(Dispatchers.Main.immediate) {
            if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                return@withContext
            }

            f()
        }
    }

    override fun onBindViewHolder(holder: RethinkEndpointViewHolder, position: Int) {
        if (lifecycleOwner == null) {
            lifecycleOwner = holder.itemView.findViewTreeLifecycleOwner()
        }
        val doHEndpoint: RethinkDnsEndpoint = getItem(position) ?: return
        holder.update(doHEndpoint)
    }

    inner class RethinkEndpointViewHolder(private val b: RethinkEndpointListItemBinding) :
        RecyclerView.ViewHolder(b.root) {
        private val selectionIndicator =
            SelectionIndicator(
                b.rethinkEndpointListSelectionOrbital,
                b.rethinkEndpointListSelectionPill
            )

        private var boundEndpoint: RethinkDnsEndpoint? = null

        fun update(endpoint: RethinkDnsEndpoint) {
            boundEndpoint = endpoint
            displayDetails(endpoint)
            setupClickListeners(endpoint)
        }

        fun showStatus(statusRes: Int) {
            val endpoint = boundEndpoint ?: return
            if (statusRes != R.string.dns_connected) {
                b.rethinkEndpointListUrlExplanation.text =
                    context.getString(statusRes).replaceFirstChar(Char::titlecase)
                b.rethinkEndpointListUrlExplanation.visibility = View.VISIBLE
                return
            }

            if (endpoint.blocklistCount > 0) {
                b.rethinkEndpointListUrlExplanation.text =
                    context.getString(
                        R.string.dns_connected_rethink_plus,
                        endpoint.blocklistCount.toString()
                    )
            } else {
                b.rethinkEndpointListUrlExplanation.text = context.getString(statusRes)
            }
            b.rethinkEndpointListUrlExplanation.visibility = View.VISIBLE
        }

        private fun setupClickListeners(endpoint: RethinkDnsEndpoint) {
            b.root.setOnClickListener { updateConnection(endpoint) }
            b.rethinkEndpointListActionImage.setOnClickListener { showDohMetadataDialog(endpoint) }
        }

        private fun displayDetails(endpoint: RethinkDnsEndpoint) {
            b.rethinkEndpointListUrlName.text = endpoint.name
            b.root.contentDescription =
                context.getString(
                    if (endpoint.isActive) R.string.dns_list_item_selected_cd
                    else R.string.dns_list_item_select_cd,
                    endpoint.name
                )
            selectionIndicator.update(endpoint.isActive)


            // Shows either the info/delete icon for the DoH entries.
            showIcon(endpoint)

            if (endpoint.isActive && VpnController.hasTunnel() && !appConfig.isSmartDnsEnabled()) {
                registerForStatusUpdates(this)
            } else if (endpoint.isActive) {
                statusHolders.remove(this)
                b.rethinkEndpointListUrlExplanation.text =
                    context.getString(R.string.rt_filter_parent_selected)
                b.rethinkEndpointListUrlExplanation.visibility = View.VISIBLE
            } else {
                statusHolders.remove(this)
                b.rethinkEndpointListUrlExplanation.text = ""
                b.rethinkEndpointListUrlExplanation.visibility = View.GONE
            }

            io { updateFlag(endpoint) }
        }

        private fun showIcon(endpoint: RethinkDnsEndpoint) {
            if (endpoint.isEditable(context)) {
                b.rethinkEndpointListActionImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_edit_icon)
                )
            } else {
                b.rethinkEndpointListActionImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_info)
                )
            }
        }

        private fun updateConnection(endpoint: RethinkDnsEndpoint) {
            Logger.d(
                LOG_TAG_DNS,
                "$TAG rdns update; ${endpoint.name}, ${endpoint.url}, ${endpoint.isActive}"
            )

            io {
                endpoint.isActive = true
                appConfig.handleRethinkChanges(endpoint)
            }
        }

        private fun showDohMetadataDialog(endpoint: RethinkDnsEndpoint) {
            val builder = MaterialAlertDialogBuilder(context, R.style.App_Dialog_NoDim)
            builder.setTitle(endpoint.name)
            builder.setMessage(endpoint.url + "\n\n" + endpoint.desc)
            builder.setCancelable(true)
            if (endpoint.isEditable(context)) {
                builder.setPositiveButton(context.getString(R.string.rt_edit_dialog_positive)) { _, _ ->
                    openEditConfiguration(endpoint)
                }
            } else {
                builder.setPositiveButton(context.getString(R.string.dns_info_positive)) { dialogInterface, _ ->
                    dialogInterface.dismiss()
                }
            }
            builder.setNeutralButton(context.getString(R.string.dns_info_neutral)) { _: DialogInterface, _: Int ->
                clipboardCopy(
                    context,
                    endpoint.url,
                    context.getString(R.string.copy_clipboard_label)
                )
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.info_dialog_url_copy_toast_msg),
                    Toast.LENGTH_SHORT
                )
            }
            builder.create().show()
        }

        private fun openEditConfiguration(endpoint: RethinkDnsEndpoint) {

            if (!VpnController.hasTunnel()) {
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.ssv_toast_start_rethink),
                    Toast.LENGTH_SHORT
                )
                return
            }

            val intent = Intent(context, ConfigureRethinkBasicActivity::class.java)
            intent.putExtra(
                ConfigureRethinkBasicActivity.RETHINK_BLOCKLIST_TYPE,
                RethinkBlocklistManager.RethinkBlocklistType.REMOTE
            )
            intent.putExtra(ConfigureRethinkBasicActivity.RETHINK_BLOCKLIST_NAME, endpoint.name)
            intent.putExtra(ConfigureRethinkBasicActivity.RETHINK_BLOCKLIST_URL, endpoint.url)
            context.startActivity(intent)
        }

        private suspend fun updateFlag(endpoint: RethinkDnsEndpoint) {
            var ip: String? = null

            if (endpoint.isActive) {
                val ips = VpnController.getDnsIps(Backend.Preferred)
                ip = ips?.split(",")?.firstOrNull()?.trim()?.let { stripPort(it) }
            }

            if (ip.isNullOrBlank()) {
                val url = getBaseUrl(endpoint.url)
                if (url.isNullOrBlank()) {
                    uiCtx { b.rethinkEndpointListUrlFlagText.visibility = View.GONE }
                    return
                }
                ip = Utilities.getIpForUrl(context, url)
            }

            if (ip.isNullOrBlank()) {
                uiCtx { b.rethinkEndpointListUrlFlagText.visibility = View.GONE }
                return
            }

            val ipInfo = IpInfoDownloader.getIpInfo(ip)
            uiCtx {
                if (ipInfo != null && ipInfo.countryCode.isNotEmpty()) {
                    b.rethinkEndpointListUrlFlagText.text = Utilities.getFlag(ipInfo.countryCode)
                    b.rethinkEndpointListUrlFlagText.visibility = View.VISIBLE
                } else {
                    b.rethinkEndpointListUrlFlagText.visibility = View.GONE
                }
            }
        }

        fun getBaseUrl(url: String): String? {
            val uri = android.net.Uri.parse(url)
            val scheme = uri.scheme ?: return null
            val host = uri.host ?: return null
            val port = if (uri.port != -1) ":${uri.port}" else ""

            return "$scheme://$host$port/"
        }

        private fun stripPort(addr: String): String {
            return IpRulesManager.splitHostPort(addr).first
        }

        private suspend fun uiCtx(f: suspend () -> Unit) {
            val owner = lifecycleOwner ?: return

            withContext(Dispatchers.Main.immediate) {
                if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    return@withContext
                }

                f()
            }
        }

        private fun io(f: suspend () -> Unit): Job? {
            return lifecycleOwner?.lifecycleScope?.launch { withContext(Dispatchers.IO) { f() } }
        }
    }
}
