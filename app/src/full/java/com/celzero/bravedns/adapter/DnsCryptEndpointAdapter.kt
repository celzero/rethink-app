/*
Copyright 2020 RethinkDNS and its authors

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

import android.content.Context
import android.content.DialogInterface
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.findViewTreeLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import backend.Backend
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsCryptEndpoint
import com.celzero.bravedns.databinding.DnsCryptEndpointListItemBinding
import com.celzero.bravedns.service.VpnController
import com.celzero.bravedns.util.UIUtils
import com.celzero.bravedns.util.UIUtils.clipboardCopy
import com.celzero.bravedns.util.Utilities
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsCryptEndpointAdapter(private val context: Context, private val appConfig: AppConfig) :
    PagingDataAdapter<DnsCryptEndpoint, DnsCryptEndpointAdapter.DnsCryptEndpointViewHolder>(
        DIFF_CALLBACK
    ) {
    var lifecycleOwner: LifecycleOwner? = null

    companion object {
        private const val ONE_SEC = 1000L
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<DnsCryptEndpoint>() {

                override fun areItemsTheSame(
                    oldConnection: DnsCryptEndpoint,
                    newConnection: DnsCryptEndpoint
                ): Boolean {
                    return (oldConnection.id == newConnection.id &&
                        oldConnection.isSelected == newConnection.isSelected)
                }

                override fun areContentsTheSame(
                    oldConnection: DnsCryptEndpoint,
                    newConnection: DnsCryptEndpoint
                ): Boolean {
                    return (oldConnection.id == newConnection.id &&
                        oldConnection.isSelected != newConnection.isSelected)
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DnsCryptEndpointViewHolder {
        val itemBinding =
            DnsCryptEndpointListItemBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
        lifecycleOwner = parent.findViewTreeLifecycleOwner()
        return DnsCryptEndpointViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: DnsCryptEndpointViewHolder, position: Int) {
        val dnsCryptEndpoint: DnsCryptEndpoint = getItem(position) ?: return
        holder.update(dnsCryptEndpoint)
    }

    inner class DnsCryptEndpointViewHolder(private val b: DnsCryptEndpointListItemBinding) :
        RecyclerView.ViewHolder(b.root) {
        private var statusCheckJob: Job? = null

        fun update(endpoint: DnsCryptEndpoint) {
            displayDetails(endpoint)
            setupClickListeners(endpoint)
        }

        private fun setupClickListeners(endpoint: DnsCryptEndpoint) {
            b.root.setOnClickListener {
                b.dnsCryptEndpointListActionImage.isChecked =
                    !b.dnsCryptEndpointListActionImage.isChecked
                updateDnsCryptDetails(endpoint)
            }

            b.dnsCryptEndpointListActionImage.setOnClickListener { updateDnsCryptDetails(endpoint) }

            b.dnsCryptEndpointListInfoImage.setOnClickListener {
                showExplanationOnImageClick(endpoint)
            }
        }

        private fun displayDetails(endpoint: DnsCryptEndpoint) {
            b.dnsCryptEndpointListUrlName.text = endpoint.dnsCryptName
            b.dnsCryptEndpointListActionImage.isChecked = endpoint.isSelected

            if (endpoint.isSelected && VpnController.hasTunnel()) {
                keepSelectedStatusUpdated()
            } else if (endpoint.isSelected) {
                b.dnsCryptEndpointListUrlExplanation.text =
                    context.getString(R.string.rt_filter_parent_selected)
            } else {
                b.dnsCryptEndpointListUrlExplanation.text = ""
            }

            if (endpoint.isDeletable()) {
                b.dnsCryptEndpointListInfoImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_fab_uninstall)
                )
            } else {
                b.dnsCryptEndpointListInfoImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_info)
                )
            }
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

            // always use the id as Dnsx.Preffered as it is the primary dns id for now
            val state = VpnController.getDnsStatus(Backend.Preferred)
            val status = UIUtils.getDnsStatusStringRes(state)
            b.dnsCryptEndpointListUrlExplanation.text =
                context.getString(status).replaceFirstChar(Char::titlecase)
        }

        private fun showExplanationOnImageClick(dnsCryptEndpoint: DnsCryptEndpoint) {
            if (dnsCryptEndpoint.isDeletable()) showDeleteDialog(dnsCryptEndpoint.id)
            else {
                showDialogExplanation(
                    dnsCryptEndpoint.dnsCryptName,
                    dnsCryptEndpoint.dnsCryptURL,
                    dnsCryptEndpoint.dnsCryptExplanation
                )
            }
        }

        private fun showDeleteDialog(id: Int) {
            val builder = MaterialAlertDialogBuilder(context)
            builder.setTitle(R.string.dns_crypt_custom_url_remove_dialog_title)
            builder.setMessage(R.string.dns_crypt_url_remove_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(context.getString(R.string.lbl_delete)) { _, _ ->
                deleteEndpoint(id)
            }

            builder.setNegativeButton(context.getString(R.string.lbl_cancel)) { _, _ -> }
            val alertDialog: AlertDialog = builder.create()
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun showDialogExplanation(title: String, url: String, message: String?) {
            val builder = MaterialAlertDialogBuilder(context)
            builder.setTitle(title)
            if (message == null) builder.setMessage(url)
            else builder.setMessage(url + "\n\n" + cryptDesc(message))
            builder.setCancelable(true)
            builder.setPositiveButton(context.getString(R.string.dns_info_positive)) {
                dialogInterface,
                _ ->
                dialogInterface.dismiss()
            }

            builder.setNeutralButton(context.getString(R.string.dns_info_neutral)) {
                _: DialogInterface,
                _: Int ->
                clipboardCopy(context, url, context.getString(R.string.copy_clipboard_label))
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.info_dialog_url_copy_toast_msg),
                    Toast.LENGTH_SHORT
                )
            }
            val alertDialog: AlertDialog = builder.create()
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun cryptDesc(message: String?): String {
            if (message.isNullOrEmpty()) return ""

            return try {
                // fixme: find a better way to handle this
                if (message.contains("R.string.")) {
                    val m = message.substringAfter("R.string.")
                    val resId: Int =
                        context.resources.getIdentifier(m, "string", context.packageName)
                    context.getString(resId)
                } else {
                    message
                }
            } catch (ignored: Exception) {
                ""
            }
        }

        private fun updateDnsCryptDetails(endpoint: DnsCryptEndpoint) {
            io {
                endpoint.isSelected = true
                appConfig.handleDnscryptChanges(endpoint)
            }
        }

        private fun deleteEndpoint(id: Int) {
            io {
                appConfig.deleteDnscryptEndpoint(id)
                uiCtx {
                    Utilities.showToastUiCentered(
                        context,
                        context.getString(R.string.dns_crypt_url_remove_success),
                        Toast.LENGTH_SHORT
                    )
                }
            }
        }

        private suspend fun uiCtx(f: suspend () -> Unit) {
            withContext(Dispatchers.Main) { f() }
        }

        private fun ui(f: suspend () -> Unit): Job? {
            return lifecycleOwner?.lifecycleScope?.launch(Dispatchers.Main) { f() }
        }

        private fun io(f: suspend () -> Unit) {
            lifecycleOwner?.lifecycleScope?.launch(Dispatchers.IO) { f() }
        }
    }
}
