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

import Logger
import Logger.LOG_TAG_DNS
import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.ODoHEndpoint
import com.celzero.bravedns.databinding.ListItemEndpointBinding
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.UIUtils.clipboardCopy
import com.celzero.bravedns.util.UIUtils.getDnsStatusStringRes
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ODoHEndpointAdapter(private val context: Context, private val appConfig: AppConfig) :
    PagingDataAdapter<ODoHEndpoint, ODoHEndpointAdapter.ODoHEndpointViewHolder>(DIFF_CALLBACK) {

    var lifecycleOwner: LifecycleOwner? = null

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
        val endpoint: ODoHEndpoint = getItem(position) ?: return
        holder.update(endpoint)
    }

    inner class ODoHEndpointViewHolder(private val b: ListItemEndpointBinding) :
        RecyclerView.ViewHolder(b.root) {
        private var statusCheckJob: Job? = null

        fun update(endpoint: ODoHEndpoint) {
            displayDetails(endpoint)
            setupClickListeners(endpoint)
        }

        private fun setupClickListeners(endpoint: ODoHEndpoint) {
            b.root.setOnClickListener { updateConnection(endpoint) }
            b.endpointInfoImg.setOnClickListener { showExplanationOnImageClick(endpoint) }
            b.endpointCheck.setOnClickListener { updateConnection(endpoint) }
        }

        private fun displayDetails(endpoint: ODoHEndpoint) {
            b.endpointName.text = endpoint.name
            b.endpointCheck.isChecked = endpoint.isSelected

            if (endpoint.isSelected && VpnController.hasTunnel() && !appConfig.isSmartDnsEnabled()) {
                keepSelectedStatusUpdated()
            } else if (endpoint.isSelected) {
                b.endpointDesc.text = context.getString(R.string.rt_filter_parent_selected)
            } else {
                b.endpointDesc.text = ""
            }

            // Shows either the info/delete icon for the DoH entries.
            showIcon(endpoint)
        }

        private fun keepSelectedStatusUpdated() {
            statusCheckJob = ui {
                while (true) {
                    updateSelectedStatus()
                    delay(ONE_SEC)
                }
            }
        }

        private fun updateSelectedStatus() {
            // if the view is not active then cancel the job
            if (
                lifecycleOwner
                    ?.lifecycle
                    ?.currentState
                    ?.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) == false ||
                    bindingAdapterPosition == RecyclerView.NO_POSITION
            ) {
                statusCheckJob?.cancel()
                return
            }

            updateDnsStatus()

        }

        private fun updateDnsStatus() {
            io {
                // always use the id as Dnsx.Preffered as it is the primary dns id for now
                val state = VpnController.getDnsStatus(Backend.Preferred)
                val status = getDnsStatusStringRes(state)
                uiCtx {
                    b.endpointDesc.text = context.getString(status).replaceFirstChar(Char::titlecase)
                }
            }
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

        private suspend fun uiCtx(f: suspend () -> Unit) {
            withContext(Dispatchers.Main) { f() }
        }

        private fun ui(f: suspend () -> Unit): Job? {
            return lifecycleOwner?.lifecycleScope?.launch { withContext(Dispatchers.Main) { f() } }
        }

        private fun io(f: suspend () -> Unit) {
            lifecycleOwner?.lifecycleScope?.launch { withContext(Dispatchers.IO) { f() } }
        }
    }
}
