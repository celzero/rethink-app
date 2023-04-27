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
import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.RethinkDnsApplication.Companion.DEBUG
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.databinding.DohEndpointListItemBinding
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS
import com.celzero.bravedns.util.UIUtils.clipboardCopy
import com.celzero.bravedns.util.UIUtils.getDnsStatus
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DohEndpointAdapter(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val appConfig: AppConfig
) : PagingDataAdapter<DoHEndpoint, DohEndpointAdapter.DoHEndpointViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<DoHEndpoint>() {
                override fun areItemsTheSame(
                    oldConnection: DoHEndpoint,
                    newConnection: DoHEndpoint
                ): Boolean {
                    return (oldConnection.id == newConnection.id &&
                        oldConnection.isSelected == newConnection.isSelected)
                }

                override fun areContentsTheSame(
                    oldConnection: DoHEndpoint,
                    newConnection: DoHEndpoint
                ): Boolean {
                    return (oldConnection.id == newConnection.id &&
                        oldConnection.isSelected != newConnection.isSelected)
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DoHEndpointViewHolder {
        val itemBinding =
            DohEndpointListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DoHEndpointViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: DoHEndpointViewHolder, position: Int) {
        val doHEndpoint: DoHEndpoint = getItem(position) ?: return
        holder.update(doHEndpoint)
    }

    inner class DoHEndpointViewHolder(private val b: DohEndpointListItemBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(endpoint: DoHEndpoint) {
            displayDetails(endpoint)
            setupClickListeners(endpoint)
        }

        private fun setupClickListeners(endpoint: DoHEndpoint) {
            b.root.setOnClickListener { updateConnection(endpoint) }
            b.dohEndpointListActionImage.setOnClickListener {
                showExplanationOnImageClick(endpoint)
            }
            b.dohEndpointListCheckImage.setOnClickListener { updateConnection(endpoint) }
        }

        private fun displayDetails(endpoint: DoHEndpoint) {
            b.dohEndpointListUrlName.text = endpoint.dohName
            b.dohEndpointListUrlExplanation.text = ""
            b.dohEndpointListCheckImage.isChecked = endpoint.isSelected
            Log.i(
                LOG_TAG_DNS,
                "connected to doh: ${endpoint.dohName} isSelected? ${endpoint.isSelected}"
            )
            if (endpoint.isSelected) {
                b.dohEndpointListUrlExplanation.text =
                    context.getString(getDnsStatus()).replaceFirstChar(Char::titlecase)
            }

            // Shows either the info/delete icon for the DoH entries.
            showIcon(endpoint)
        }

        private fun showIcon(endpoint: DoHEndpoint) {
            if (endpoint.isDeletable()) {
                b.dohEndpointListActionImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_fab_uninstall)
                )
            } else {
                b.dohEndpointListActionImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_info)
                )
            }
        }

        private fun updateConnection(endpoint: DoHEndpoint) {
            if (DEBUG)
                Log.d(
                    LOG_TAG_DNS,
                    "on doh change - ${endpoint.dohName}, ${endpoint.dohURL}, ${endpoint.isSelected}"
                )
            io {
                endpoint.isSelected = true
                appConfig.handleDoHChanges(endpoint)
            }
        }

        private fun deleteEndpoint(id: Int) {
            io {
                appConfig.deleteDohEndpoint(id)
                uiCtx {
                    Toast.makeText(
                            context,
                            R.string.doh_custom_url_remove_success,
                            Toast.LENGTH_SHORT
                        )
                        .show()
                }
            }
        }

        private fun showExplanationOnImageClick(endpoint: DoHEndpoint) {
            if (endpoint.isDeletable()) showDeleteDnsDialog(endpoint.id)
            else showDohMetadataDialog(endpoint.dohName, endpoint.dohURL, endpoint.dohExplanation)
        }

        private fun showDohMetadataDialog(title: String, url: String, message: String?) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(title)
            builder.setMessage(url + "\n\n" + message)
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
            builder.create().show()
        }

        private fun showDeleteDnsDialog(id: Int) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.doh_custom_url_remove_dialog_title)
            builder.setMessage(R.string.doh_custom_url_remove_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(context.getString(R.string.dns_delete_positive)) { _, _ ->
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

        private fun io(f: suspend () -> Unit) {
            lifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
        }
    }
}
