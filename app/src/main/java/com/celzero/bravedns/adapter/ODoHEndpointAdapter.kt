/*
Copyright 2023 RethinkDNS and its authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

https://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.celzero.bravedns.adapter

import com.celzero.bravedns.util.Logger
import com.celzero.bravedns.util.Logger.LOG_TAG_DNS
import android.content.Context
import android.content.DialogInterface
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
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.databinding.ListItemEndpointBinding
import com.celzero.bravedns.service.IpRulesManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.UIUtils.clipboardCopy
import com.celzero.bravedns.util.UIUtils.getDnsStatusStringRes
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

class ODoHEndpointAdapter(private val context: Context, private val appConfig: AppConfig) :
    PagingDataAdapter<ODoHEndpoint, ODoHEndpointAdapter.ODoHEndpointViewHolder>(DIFF_CALLBACK) {

    var lifecycleOwner: LifecycleOwner? = null

    private val statusHolders = mutableListOf<ODoHEndpointViewHolder>()
    private val statusTicker = StatusTicker()

    companion object {
        private const val ONE_SEC = 1000L
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<ODoHEndpoint>() {
                override fun areItemsTheSame(
                    oldConnection: ODoHEndpoint,
                    newConnection: ODoHEndpoint
                ): Boolean {
                    return (oldConnection.id == newConnection.id &&
                        oldConnection.isSelected == newConnection.isSelected)
                }

                override fun areContentsTheSame(
                    oldConnection: ODoHEndpoint,
                    newConnection: ODoHEndpoint
                ): Boolean {
                    return (oldConnection.id == newConnection.id &&
                        oldConnection.isSelected != newConnection.isSelected)
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ODoHEndpointViewHolder {
        val itemBinding =
            ListItemEndpointBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        lifecycleOwner = parent.findViewTreeLifecycleOwner()
        return ODoHEndpointViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: ODoHEndpointViewHolder, position: Int) {
        if (lifecycleOwner == null) {
            lifecycleOwner = holder.itemView.findViewTreeLifecycleOwner()
        }
        val endpoint: ODoHEndpoint = getItem(position) ?: return
        holder.update(endpoint)
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        super.onDetachedFromRecyclerView(recyclerView)
        statusTicker.cancel()
        statusHolders.clear()
        lifecycleOwner = null
    }

    private fun registerForStatusUpdates(holder: ODoHEndpointViewHolder) {
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
        val active = ArrayList<ODoHEndpointViewHolder>()
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
            // always use the id as Dnsx.Preffered as it is the primary dns id for now
            val state = VpnController.getDnsStatus(Backend.Preferred)
            val status = getDnsStatusStringRes(state)
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

    inner class ODoHEndpointViewHolder(private val b: ListItemEndpointBinding) :
        RecyclerView.ViewHolder(b.root) {
        private val selectionIndicator =
            SelectionIndicator(b.endpointSelectionOrbital, b.endpointSelectionPill)


        fun update(endpoint: ODoHEndpoint) {
            displayDetails(endpoint)
            setupClickListeners(endpoint)
        }

        fun showStatus(statusRes: Int) {
            b.endpointDesc.text = context.getString(statusRes).replaceFirstChar(Char::titlecase)
            b.endpointDesc.visibility = View.VISIBLE
        }

        private fun setupClickListeners(endpoint: ODoHEndpoint) {
            b.root.setOnClickListener { updateConnection(endpoint) }
            b.endpointInfoImg.setOnClickListener { showExplanationOnImageClick(endpoint) }
        }

        private fun displayDetails(endpoint: ODoHEndpoint) {
            b.endpointName.text = endpoint.name
            b.root.contentDescription =
                context.getString(
                    if (endpoint.isSelected) R.string.dns_list_item_selected_cd
                    else R.string.dns_list_item_select_cd,
                    endpoint.name
                )
            selectionIndicator.update(endpoint.isSelected)

            if (endpoint.isSelected && VpnController.hasTunnel() && !appConfig.isSmartDnsEnabled()) {
                registerForStatusUpdates(this)
            } else if (endpoint.isSelected) {
                statusHolders.remove(this)
                b.endpointDesc.text = context.getString(R.string.rt_filter_parent_selected)
                b.endpointDesc.visibility = View.VISIBLE
            } else {
                statusHolders.remove(this)
                b.endpointDesc.text = ""
                b.endpointDesc.visibility = View.GONE
            }

            // Shows either the info/delete icon for the DoH entries.
            showIcon(endpoint)

            io { updateFlag(endpoint) }
        }

        private fun showIcon(endpoint: ODoHEndpoint) {
            if (endpoint.isDeletable()) {
                b.endpointInfoImg.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_fab_uninstall)
                )
            } else {
                b.endpointInfoImg.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_info)
                )
            }
        }

        private fun updateConnection(endpoint: ODoHEndpoint) {
            Logger.d(
                LOG_TAG_DNS,
                "on-ODoH change ${endpoint.name}, ${endpoint.proxy}, ${endpoint.resolver}, ${endpoint.isSelected}"
            )
            io {
                endpoint.isSelected = true
                appConfig.handleODoHChanges(endpoint)
            }
        }

        private fun deleteEndpoint(id: Int) {
            io {
                appConfig.deleteODoHEndpoint(id)
                uiCtx {
                    Utilities.showToastUiCentered(
                        context,
                        context.getString(R.string.doh_custom_url_remove_success),
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        private fun showExplanationOnImageClick(endpoint: ODoHEndpoint) {
            if (endpoint.isDeletable()) showDeleteDialog(endpoint.id)
            else
                showDoTMetadataDialog(
                    endpoint.name,
                    endpoint.proxy,
                    endpoint.resolver,
                    endpoint.desc
                )
        }

        private fun showDoTMetadataDialog(
            title: String,
            proxy: String,
            resolver: String,
            message: String?
        ) {
            val builder = MaterialAlertDialogBuilder(context)
            builder.setTitle(title)
            builder.setMessage(proxy + "\n\n" + resolver + "\n\n" + getDnsDesc(message))
            builder.setCancelable(true)
            builder.setPositiveButton(context.getString(R.string.dns_info_positive)) {
                dialogInterface,
                _ ->
                dialogInterface.dismiss()
            }
            builder.setNeutralButton(context.getString(R.string.dns_info_neutral)) {
                _: DialogInterface,
                _: Int ->
                clipboardCopy(context, resolver, context.getString(R.string.copy_clipboard_label))
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.info_dialog_url_copy_toast_msg),
                    Toast.LENGTH_SHORT
                )
            }
            builder.create().show()
        }

        private fun getDnsDesc(message: String?): String {
            if (message.isNullOrEmpty()) return ""

            return try {
                if (message.contains("R.string.")) {
                    val m = message.substringAfter("R.string.")
                    val resId: Int =
                        context.resources.getIdentifier(m, "string", context.packageName)
                    context.getString(resId)
                } else {
                    message
                }
            } catch (_: Exception) {
                ""
            }
        }

        private fun showDeleteDialog(id: Int) {
            val builder = MaterialAlertDialogBuilder(context)
            builder.setTitle(R.string.dot_custom_url_remove_dialog_title)
            builder.setMessage(R.string.dot_custom_url_remove_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(context.getString(R.string.lbl_delete)) { _, _ ->
                deleteEndpoint(id)
            }

            builder.setNegativeButton(context.getString(R.string.lbl_cancel)) { _, _ ->
                // no-op
            }
            builder.create().show()
        }

        private suspend fun updateFlag(endpoint: ODoHEndpoint) {
            var ip: String? = null

            if (endpoint.isSelected) {
                val ips = VpnController.getDnsIps(Backend.Preferred)
                ip = ips?.split(",")?.firstOrNull()?.trim()?.let { stripPort(it) }
            }

            if (ip.isNullOrBlank()) {
                ip = Utilities.getIpForUrl(context, endpoint.resolver)
            }

            if (ip.isNullOrBlank()) {
                uiCtx { b.endpointFlagText.visibility = View.GONE }
                return
            }

            val ipInfo = IpInfoDownloader.getIpInfo(ip)
            uiCtx {
                if (ipInfo != null && ipInfo.countryCode.isNotEmpty()) {
                    b.endpointFlagText.text = Utilities.getFlag(ipInfo.countryCode)
                    b.endpointFlagText.visibility = View.VISIBLE
                } else {
                    b.endpointFlagText.visibility = View.GONE
                }
            }
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

        private fun io(f: suspend () -> Unit) {
            lifecycleOwner?.lifecycleScope?.launch { withContext(Dispatchers.IO) { f() } }
        }
    }
}
