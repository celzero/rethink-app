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
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsProxyEndpoint
import com.celzero.bravedns.databinding.DnsProxyListItemBinding
import com.celzero.bravedns.service.FirewallManager
import com.celzero.bravedns.util.UIUtils.clipboardCopy
import com.celzero.bravedns.util.Utilities
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsProxyEndpointAdapter(
    private val context: Context,
    val lifecycleOwner: LifecycleOwner,
    private val appConfig: AppConfig
) :
    PagingDataAdapter<DnsProxyEndpoint, DnsProxyEndpointAdapter.DnsProxyEndpointViewHolder>(
        DIFF_CALLBACK
    ) {

    companion object {
        private val DIFF_CALLBACK =
            object : DiffUtil.ItemCallback<DnsProxyEndpoint>() {
                override fun areItemsTheSame(
                    oldConnection: DnsProxyEndpoint,
                    newConnection: DnsProxyEndpoint
                ): Boolean {
                    return (oldConnection.id == newConnection.id &&
                        oldConnection.isSelected == newConnection.isSelected)
                }

                override fun areContentsTheSame(
                    oldConnection: DnsProxyEndpoint,
                    newConnection: DnsProxyEndpoint
                ): Boolean {
                    return (oldConnection.id == newConnection.id &&
                        oldConnection.isSelected != newConnection.isSelected)
                }
            }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DnsProxyEndpointViewHolder {
        val itemBinding =
            DnsProxyListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DnsProxyEndpointViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: DnsProxyEndpointViewHolder, position: Int) {
        val dnsProxyEndpoint: DnsProxyEndpoint = getItem(position) ?: return
        holder.update(dnsProxyEndpoint)
    }

    inner class DnsProxyEndpointViewHolder(private val b: DnsProxyListItemBinding) :
        RecyclerView.ViewHolder(b.root) {

        fun update(endpoint: DnsProxyEndpoint) {
            displayDetails(endpoint)
            setupClickListeners(endpoint)
        }

        private fun setupClickListeners(endpoint: DnsProxyEndpoint) {
            b.root.setOnClickListener { updateDnsProxyDetails(endpoint) }

            b.dnsProxyListActionImage.setOnClickListener { promptUser(endpoint) }

            b.dnsProxyListCheckImage.setOnClickListener { updateDnsProxyDetails(endpoint) }

            b.root.setOnClickListener { updateDnsProxyDetails(endpoint) }

            b.dnsProxyListActionImage.setOnClickListener { promptUser(endpoint) }

            b.dnsProxyListCheckImage.setOnClickListener { updateDnsProxyDetails(endpoint) }
        }

        private fun displayDetails(endpoint: DnsProxyEndpoint) {
            b.dnsProxyListUrlName.text = endpoint.proxyName
            b.dnsProxyListCheckImage.isChecked = endpoint.isSelected

            io {
                val appInfo = FirewallManager.getAppInfoByPackage(endpoint.proxyAppName)
                uiCtx {
                    val appName =
                        if (
                            endpoint.proxyName !=
                                context.getString(R.string.cd_custom_dns_proxy_default_app)
                        ) {
                            appInfo?.appName
                                ?: context.getString(R.string.cd_custom_dns_proxy_default_app)
                        } else {
                            endpoint.proxyAppName
                                ?: context.getString(R.string.cd_custom_dns_proxy_default_app)
                        }

                    b.dnsProxyListUrlExplanation.text =
                        endpoint.getExplanationText(context, appName)
                }
            }

            if (endpoint.isDeletable()) {
                b.dnsProxyListActionImage.setImageDrawable(
                    AppCompatResources.getDrawable(context, R.drawable.ic_fab_uninstall)
                )
            } else {
                b.dnsProxyListActionImage.setImageDrawable(
                    AppCompatResources.getDrawable(context, R.drawable.ic_info)
                )
            }
        }
    }

    private fun promptUser(endpoint: DnsProxyEndpoint) {
        if (endpoint.isDeletable()) showDeleteDialog(endpoint)
        else {
            io {
                val app = FirewallManager.getAppInfoByPackage(endpoint.getPackageName())?.appName
                uiCtx {
                    showDetailsDialog(
                        endpoint.proxyName,
                        endpoint.proxyIP,
                        endpoint.proxyPort.toString(),
                        app
                    )
                }
            }
        }
    }

    private fun showDetailsDialog(title: String, ip: String?, port: String, app: String?) {
        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle(title)

        if (!app.isNullOrEmpty()) {
            builder.setMessage(context.getString(R.string.dns_proxy_dialog_message, app, ip, port))
        } else {
            builder.setMessage(
                context.getString(R.string.dns_proxy_dialog_message_no_app, ip, port)
            )
        }
        builder.setCancelable(true)
        builder.setPositiveButton(context.getString(R.string.dns_info_positive)) {
            dialogInterface,
            _ ->
            dialogInterface.dismiss()
        }
        builder.setNeutralButton(context.getString(R.string.dns_info_neutral)) {
            _: DialogInterface,
            _: Int ->
            if (ip != null) {
                clipboardCopy(context, ip, context.getString(R.string.copy_clipboard_label))
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.info_dialog_copy_toast_msg),
                    Toast.LENGTH_SHORT
                )
            } else {
                // no op: Copy functionality is for the Ip of the endpoint, no operation needed
                // when the ip is not available for endpoint.
            }
        }
        builder.create().show()
    }

    private fun showDeleteDialog(dnsProxyEndpoint: DnsProxyEndpoint) {
        val builder = MaterialAlertDialogBuilder(context)
        builder.setTitle(R.string.dns_proxy_remove_dialog_title)
        builder.setMessage(R.string.dns_proxy_remove_dialog_message)

        builder.setCancelable(true)
        builder.setPositiveButton(context.getString(R.string.lbl_delete)) { _, _ ->
            deleteProxyEndpoint(dnsProxyEndpoint.id)
        }

        builder.setNegativeButton(context.getString(R.string.lbl_cancel)) { _, _ -> }
        builder.create().show()
    }

    private fun updateDnsProxyDetails(endpoint: DnsProxyEndpoint) {
        io {
            endpoint.isSelected = true
            appConfig.handleDnsProxyChanges(endpoint)
        }
    }

    private fun deleteProxyEndpoint(id: Int) {
        io {
            appConfig.deleteDnsProxyEndpoint(id)
            uiCtx {
                Utilities.showToastUiCentered(
                    context,
                    context.getString(R.string.dns_proxy_remove_success),
                    Toast.LENGTH_SHORT
                )
            }
        }
    }

    private suspend fun uiCtx(f: suspend () -> Unit) {
        withContext(Dispatchers.Main) { f() }
    }

    private fun io(f: suspend () -> Unit) {
        lifecycleOwner.lifecycleScope.launch { withContext(Dispatchers.IO) { f() } }
    }
}
