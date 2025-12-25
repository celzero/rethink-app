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

import Logger
import Logger.LOG_TAG_DNS
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
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
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.databinding.RethinkEndpointListItemBinding
import com.celzero.bravedns.service.RethinkBlocklistManager
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.ui.activity.ConfigureRethinkBasicActivity
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.clipboardCopy
import com.celzero.bravedns.util.Utilities
import com.celzero.firestack.backend.Backend
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RethinkEndpointAdapter(private val context: Context, private val appConfig: AppConfig) :
    PagingDataAdapter<RethinkDnsEndpoint, RethinkEndpointAdapter.RethinkEndpointViewHolder>(
        DIFF_CALLBACK
    ) {

    var lifecycleOwner: LifecycleOwner? = null

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
        return RethinkEndpointViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: RethinkEndpointViewHolder, position: Int) {
        val doHEndpoint: RethinkDnsEndpoint = getItem(position) ?: return
        holder.update(doHEndpoint)
    }

    inner class RethinkEndpointViewHolder(private val b: RethinkEndpointListItemBinding) :
        RecyclerView.ViewHolder(b.root) {
        private var statusCheckJob: Job? = null

        fun update(endpoint: RethinkDnsEndpoint) {
            displayDetails(endpoint)
            setupClickListeners(endpoint)
        }

        private fun setupClickListeners(endpoint: RethinkDnsEndpoint) {
            b.root.setOnClickListener { updateConnection(endpoint) }
            b.rethinkEndpointListActionImage.setOnClickListener { showDohMetadataDialog(endpoint) }
            b.rethinkEndpointListCheckImage.setOnClickListener { updateConnection(endpoint) }
        }

        private fun displayDetails(endpoint: RethinkDnsEndpoint) {
            b.rethinkEndpointListUrlName.text = endpoint.name
            b.rethinkEndpointListCheckImage.isChecked = endpoint.isActive

            // Shows either the info/delete icon for the DoH entries.
            showIcon(endpoint)

            if (endpoint.isActive && VpnController.hasTunnel() && !appConfig.isSmartDnsEnabled()) {
                keepSelectedStatusUpdated(endpoint)
            } else if (endpoint.isActive) {
                b.rethinkEndpointListUrlExplanation.text =
                    context.getString(R.string.rt_filter_parent_selected)
            } else {
                b.rethinkEndpointListUrlExplanation.text = ""
            }
        }

        private fun keepSelectedStatusUpdated(endpoint: RethinkDnsEndpoint) {
            statusCheckJob = io {
                while (true) {
                    updateBlocklistStatusText(endpoint)
                    delay(ONE_SEC)
                }
            }
        }

        private suspend fun updateBlocklistStatusText(endpoint: RethinkDnsEndpoint) {
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

            updateDnsStatus(endpoint)
        }

        private suspend fun updateDnsStatus(endpoint: RethinkDnsEndpoint) {
            val state = VpnController.getDnsStatus(Backend.Preferred)
            val status = UIUtils.getDnsStatusStringRes(state)
            uiCtx {
                // show the status as it is if it is not connected
                if (status != R.string.dns_connected) {
                    b.rethinkEndpointListUrlExplanation.text =
                        context.getString(status).replaceFirstChar(Char::titlecase)
                    return@uiCtx
                }

                if (endpoint.blocklistCount > 0) {
                    b.rethinkEndpointListUrlExplanation.text =
                        context.getString(
                            R.string.dns_connected_rethink_plus,
                            endpoint.blocklistCount.toString()
                        )
                } else {
                    b.rethinkEndpointListUrlExplanation.text = context.getString(status)
                }
            }

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

        private suspend fun uiCtx(f: suspend () -> Unit) {
            withContext(Dispatchers.Main) { f() }
        }

        private fun io(f: suspend () -> Unit): Job? {
            return lifecycleOwner?.lifecycleScope?.launch { withContext(Dispatchers.IO) { f() } }
        }
    }
}
