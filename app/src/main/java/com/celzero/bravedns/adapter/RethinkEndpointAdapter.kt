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
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.RethinkDnsEndpoint
import com.celzero.bravedns.databinding.RethinkEndpointListItemBinding
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class RethinkEndpointAdapter(private val context: Context,
                             private val lifecycleOwner: LifecycleOwner,
                             private val appConfig: AppConfig) :
        PagedListAdapter<RethinkDnsEndpoint, RethinkEndpointAdapter.RethinkEndpointViewHolder>(
            DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<RethinkDnsEndpoint>() {
            override fun areItemsTheSame(oldConnection: RethinkDnsEndpoint,
                                         newConnection: RethinkDnsEndpoint): Boolean {
                return (oldConnection.url == newConnection.url && oldConnection.isActive == newConnection.isActive)
            }

            override fun areContentsTheSame(oldConnection: RethinkDnsEndpoint,
                                            newConnection: RethinkDnsEndpoint): Boolean {
                return (oldConnection.url == newConnection.url && oldConnection.isActive != newConnection.isActive)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RethinkEndpointViewHolder {
        val itemBinding = RethinkEndpointListItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return RethinkEndpointViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: RethinkEndpointViewHolder, position: Int) {
        val doHEndpoint: RethinkDnsEndpoint = getItem(position) ?: return
        holder.update(doHEndpoint)
    }

    inner class RethinkEndpointViewHolder(private val b: RethinkEndpointListItemBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(endpoint: RethinkDnsEndpoint) {
            displayDetails(endpoint)
            setupClickListeners(endpoint)
        }

        private fun setupClickListeners(endpoint: RethinkDnsEndpoint) {
            b.root.setOnClickListener {
                updateConnection(endpoint)
            }
            b.rethinkEndpointListActionImage.setOnClickListener {
                showExplanationOnImageClick(endpoint)
            }
            b.rethinkEndpointListCheckImage.setOnClickListener {
                updateConnection(endpoint)
            }
        }

        private fun displayDetails(endpoint: RethinkDnsEndpoint) {
            b.rethinkEndpointListUrlName.text = endpoint.name
            b.rethinkEndpointListUrlExplanation.text = ""
            b.rethinkEndpointListCheckImage.isChecked = endpoint.isActive
            Log.i(LOG_TAG_DNS,
                  "connected to doh: ${endpoint.name} isSelected? ${endpoint.isActive}")
            if (endpoint.isActive) {
                val count = appConfig.getRemoteBlocklistCount()
                b.rethinkEndpointListUrlExplanation.text = context.getString(
                    R.string.dns_connected_rethink_plus, count.toString())
            }

            // Shows either the info/delete icon for the DoH entries.
            showIcon(endpoint)

        }

        private fun showIcon(endpoint: RethinkDnsEndpoint) {
            if (endpoint.isDeletable()) {
                b.rethinkEndpointListActionImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_fab_uninstall))
            } else {
                b.rethinkEndpointListActionImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_info))
            }
        }

        private fun updateConnection(endpoint: RethinkDnsEndpoint) {
            if (DEBUG) Log.d(LOG_TAG_DNS,
                             "on doh change - ${endpoint.name}, ${endpoint.url}, ${endpoint.isActive}")

            io {
                endpoint.isActive = true
                appConfig.handleRethinkChanges(endpoint)
            }
        }

        private fun deleteEndpoint(name: String, url: String, uid: Int) {
            io {
                appConfig.deleteRethinkEndpoint(name, url, uid)
                uiCtx {
                    Toast.makeText(context, R.string.doh_custom_url_remove_success,
                                   Toast.LENGTH_SHORT).show()
                }
            }
        }

        private fun showExplanationOnImageClick(endpoint: RethinkDnsEndpoint) {
            if (endpoint.isDeletable()) showDeleteDnsDialog(endpoint)
            else showDohMetadataDialog(endpoint.name, endpoint.url, endpoint.desc)
        }

        private fun showDohMetadataDialog(title: String, url: String, message: String?) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(title)
            builder.setMessage(url + "\n\n" + message)
            builder.setCancelable(true)
            builder.setPositiveButton(
                context.getString(R.string.dns_info_positive)) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }
            builder.setNeutralButton(
                context.getString(R.string.dns_info_neutral)) { _: DialogInterface, _: Int ->

                Utilities.clipboardCopy(context, url,
                                        context.getString(R.string.copy_clipboard_label))
                Utilities.showToastUiCentered(context, context.getString(
                    R.string.info_dialog_url_copy_toast_msg), Toast.LENGTH_SHORT)
            }
            builder.create().show()
        }

        private fun showDeleteDnsDialog(endpoint: RethinkDnsEndpoint) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.doh_custom_url_remove_dialog_title)
            builder.setMessage(R.string.doh_custom_url_remove_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(context.getString(R.string.dns_delete_positive)) { _, _ ->
                deleteEndpoint(endpoint.name, endpoint.url, endpoint.uid)
            }

            builder.setNegativeButton(context.getString(R.string.dns_delete_negative)) { _, _ ->
                // no-op
            }
            builder.create().show()
        }

        private suspend fun uiCtx(f: suspend () -> Unit) {
            withContext(Dispatchers.Main) {
                f()
            }
        }

        private fun io(f: suspend () -> Unit) {
            lifecycleOwner.lifecycleScope.launch {
                withContext(Dispatchers.IO) {
                    f()
                }
            }
        }

    }
}
