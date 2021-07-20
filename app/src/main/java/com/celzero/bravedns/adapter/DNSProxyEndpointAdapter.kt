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
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.database.DNSProxyEndpoint
import com.celzero.bravedns.database.DNSProxyEndpointRepository
import com.celzero.bravedns.databinding.DnsProxyListItemBinding
import com.celzero.bravedns.service.QueryTracker
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appList
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_PROXY
import com.celzero.bravedns.util.UIUpdateInterface
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import settings.Settings

class DNSProxyEndpointAdapter(private val context: Context,
                              private val dnsProxyEndpointRepository: DNSProxyEndpointRepository,
                              private val appMode: AppMode, private val queryTracker: QueryTracker,
                              private val listener: UIUpdateInterface) :
        PagedListAdapter<DNSProxyEndpoint, DNSProxyEndpointAdapter.DNSProxyEndpointViewHolder>(
            DIFF_CALLBACK) {


    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DNSProxyEndpoint>() {
            override fun areItemsTheSame(oldConnection: DNSProxyEndpoint,
                                         newConnection: DNSProxyEndpoint) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: DNSProxyEndpoint,
                                            newConnection: DNSProxyEndpoint) = oldConnection == newConnection
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
                b.dnsProxyListCheckImage.isChecked = true
            }
            b.dnsProxyListActionImage.setOnClickListener {
                showExplanationOnImageClick(endpoint)
            }

            b.dnsProxyListCheckImage.setOnClickListener {
                updateDNSProxyDetails(endpoint)
                b.dnsProxyListCheckImage.isChecked = true
                b.dnsProxyListActionImage.setImageDrawable(
                    AppCompatResources.getDrawable(context, R.drawable.ic_fab_appinfo))
            }

            b.root.setOnClickListener {
                updateDNSProxyDetails(endpoint)
                b.dnsProxyListCheckImage.isChecked = true
            }

            b.dnsProxyListActionImage.setOnClickListener {
                showExplanationOnImageClick(endpoint)
            }

            b.dnsProxyListCheckImage.setOnClickListener {
                updateDNSProxyDetails(endpoint)
                b.dnsProxyListCheckImage.isChecked = true
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


    private fun showExplanationOnImageClick(endpoint: DNSProxyEndpoint) {
        if (endpoint.isDeletable()) showDialogForDelete(endpoint)
        else {
            showDialogExplanation(endpoint.proxyName, endpoint.getPackageName(), endpoint.proxyIP,
                                  endpoint.proxyPort.toString())
        }
    }

    private fun showDialogExplanation(title: String, packageName: String?, ip: String?,
                                      port: String) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle(title)
        val app = appList[packageName]?.appName

        if (app != null && !app.isNullOrEmpty()) {
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
                // No op: Copy functionality is for the Ip of the endpoint, no operation needed
                // when the ip is not available for endpoint.
            }
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    private fun showDialogForDelete(dnsProxyEndpoint: DNSProxyEndpoint) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle(R.string.dns_proxy_remove_dialog_title)
        builder.setMessage(R.string.dns_proxy_remove_dialog_message)

        builder.setCancelable(true)
        builder.setPositiveButton(context.getString(R.string.dns_delete_positive)) { _, _ ->
            GlobalScope.launch(Dispatchers.IO) {
                dnsProxyEndpointRepository.deleteDNSProxyEndpoint(dnsProxyEndpoint.id)
            }
            Toast.makeText(context, R.string.dns_proxy_remove_success, Toast.LENGTH_SHORT).show()
        }

        builder.setNegativeButton(context.getString(R.string.dns_delete_negative)) { _, _ ->
        }
        val alertDialog: AlertDialog = builder.create()
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    private fun updateDNSProxyDetails(dnsProxyEndpoint: DNSProxyEndpoint) {
        dnsProxyEndpoint.isSelected = true
        dnsProxyEndpointRepository.removeConnectionStatus()
        Utilities.delay(500) {
            notifyDataSetChanged()
            appMode.onNewDnsConnected(PREF_DNS_MODE_PROXY, Settings.DNSModeProxyIP)
            queryTracker.reinitializeQuantileEstimator()
        }

        listener.updateUIFromAdapter(PREF_DNS_MODE_PROXY)
        dnsProxyEndpointRepository.updateAsync(dnsProxyEndpoint)
    }

}
