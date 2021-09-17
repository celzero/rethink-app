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
import androidx.appcompat.content.res.AppCompatResources
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.automaton.FirewallManager
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.database.DNSProxyEndpoint
import com.celzero.bravedns.databinding.DnsProxyListItemBinding
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DNSProxyEndpointAdapter(private val context: Context, val lifecycleOwner: LifecycleOwner,
                              private val appMode: AppMode) :
        PagedListAdapter<DNSProxyEndpoint, DNSProxyEndpointAdapter.DNSProxyEndpointViewHolder>(
            DIFF_CALLBACK) {


    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DNSProxyEndpoint>() {
            override fun areItemsTheSame(oldConnection: DNSProxyEndpoint,
                                         newConnection: DNSProxyEndpoint): Boolean {
                return (oldConnection.id == newConnection.id && oldConnection.isSelected == newConnection.isSelected)
            }

            override fun areContentsTheSame(oldConnection: DNSProxyEndpoint,
                                            newConnection: DNSProxyEndpoint): Boolean {
                return (oldConnection.id == newConnection.id && oldConnection.isSelected != newConnection.isSelected)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DNSProxyEndpointViewHolder {
        val itemBinding = DnsProxyListItemBinding.inflate(LayoutInflater.from(parent.context),
                                                          parent, false)
        return DNSProxyEndpointViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: DNSProxyEndpointViewHolder, position: Int) {
        val dnsProxyEndpoint: DNSProxyEndpoint = getItem(position) ?: return
        holder.update(dnsProxyEndpoint)
    }


    inner class DNSProxyEndpointViewHolder(private val b: DnsProxyListItemBinding) :
            RecyclerView.ViewHolder(b.root) {

        fun update(endpoint: DNSProxyEndpoint) {
            displayDetails(endpoint)
            setupClickListeners(endpoint)
        }

        private fun setupClickListeners(endpoint: DNSProxyEndpoint) {
            b.root.setOnClickListener {
                updateDNSProxyDetails(endpoint)
            }
            b.dnsProxyListActionImage.setOnClickListener {
                promptUser(endpoint)
            }

            b.dnsProxyListCheckImage.setOnClickListener {
                updateDNSProxyDetails(endpoint)
            }

            b.root.setOnClickListener {
                updateDNSProxyDetails(endpoint)
            }

            b.dnsProxyListActionImage.setOnClickListener {
                promptUser(endpoint)
            }

            b.dnsProxyListCheckImage.setOnClickListener {
                updateDNSProxyDetails(endpoint)
            }
        }

        private fun displayDetails(endpoint: DNSProxyEndpoint) {
            b.dnsProxyListUrlName.text = endpoint.proxyName
            b.dnsProxyListCheckImage.isChecked = endpoint.isSelected

            b.dnsProxyListUrlExplanation.text = endpoint.getExplanationText(context)

            if (endpoint.isDeletable()) {
                b.dnsProxyListActionImage.setImageDrawable(
                    AppCompatResources.getDrawable(context, R.drawable.ic_fab_uninstall))
            } else {
                b.dnsProxyListActionImage.setImageDrawable(
                    AppCompatResources.getDrawable(context, R.drawable.ic_fab_appinfo))
            }
        }
    }

    private fun promptUser(endpoint: DNSProxyEndpoint) {
        if (endpoint.isDeletable()) showDeleteDialog(endpoint)
        else {
            showDetailsDialog(endpoint.proxyName, endpoint.getPackageName(), endpoint.proxyIP,
                              endpoint.proxyPort.toString())
        }
    }

    private fun showDetailsDialog(title: String, packageName: String?, ip: String?, port: String) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle(title)

        val app = FirewallManager.getAppInfoByPackage(packageName)?.appName

        if (!app.isNullOrEmpty()) {
            builder.setMessage(context.getString(R.string.dns_proxy_dialog_message, app, ip, port))
        } else {
            builder.setMessage(context.getString(R.string.dns_proxy_dialog_message,
                                                 context.getString(
                                                     R.string.cd_custom_dns_proxy_default_app), ip,
                                                 port))
        }
        builder.setCancelable(true)
        builder.setPositiveButton(
            context.getString(R.string.dns_info_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        builder.setNeutralButton(
            context.getString(R.string.dns_info_neutral)) { _: DialogInterface, _: Int ->
            if (ip != null) {
                Utilities.clipboardCopy(context, ip,
                                        context.getString(R.string.copy_clipboard_label))
                Utilities.showToastUiCentered(context, context.getString(
                    R.string.info_dialog_copy_toast_msg), Toast.LENGTH_SHORT)
            } else {
                // no op: Copy functionality is for the Ip of the endpoint, no operation needed
                // when the ip is not available for endpoint.
            }
        }
        builder.create().show()
    }

    private fun showDeleteDialog(dnsProxyEndpoint: DNSProxyEndpoint) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle(R.string.dns_proxy_remove_dialog_title)
        builder.setMessage(R.string.dns_proxy_remove_dialog_message)

        builder.setCancelable(true)
        builder.setPositiveButton(context.getString(R.string.dns_delete_positive)) { _, _ ->
            deleteProxyEndpoint(dnsProxyEndpoint.id)
        }

        builder.setNegativeButton(context.getString(R.string.dns_delete_negative)) { _, _ ->
        }
        builder.create().show()
    }

    private fun updateDNSProxyDetails(endpoint: DNSProxyEndpoint) {
        io {
            endpoint.isSelected = true
            appMode.handleDnsProxyChanges(endpoint)
        }
    }

    private fun deleteProxyEndpoint(id: Int) {
        io {
            appMode.deleteDnsProxyEndpoint(id)
            uiCtx {
                Toast.makeText(context, R.string.dns_proxy_remove_success,
                               Toast.LENGTH_SHORT).show()
            }
        }
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
