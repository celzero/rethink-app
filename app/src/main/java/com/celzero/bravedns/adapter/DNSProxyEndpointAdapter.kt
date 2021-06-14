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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.CountDownTimer
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DNSProxyEndpoint
import com.celzero.bravedns.database.DNSProxyEndpointRepository
import com.celzero.bravedns.databinding.DnsProxyListItemBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.QueryTracker
import com.celzero.bravedns.ui.HomeScreenActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appList
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.UIUpdateInterface
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import settings.Settings

class DNSProxyEndpointAdapter(private val context: Context,
                              private val dnsProxyEndpointRepository: DNSProxyEndpointRepository,
                              private val persistentState: PersistentState,
                              private val queryTracker: QueryTracker,
                              private val listener: UIUpdateInterface)
    : PagedListAdapter<DNSProxyEndpoint, DNSProxyEndpointAdapter.DNSProxyEndpointViewHolder>(DIFF_CALLBACK) {


    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DNSProxyEndpoint>() {
            override fun areItemsTheSame(oldConnection: DNSProxyEndpoint, newConnection: DNSProxyEndpoint) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: DNSProxyEndpoint, newConnection: DNSProxyEndpoint) = oldConnection == newConnection
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DNSProxyEndpointViewHolder {
        val itemBinding = DnsProxyListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DNSProxyEndpointViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: DNSProxyEndpointViewHolder, position: Int) {
        val dnsProxyEndpoint: DNSProxyEndpoint = getItem(position) ?: return
        holder.update(dnsProxyEndpoint)
    }


    inner class DNSProxyEndpointViewHolder(private val b: DnsProxyListItemBinding) : RecyclerView.ViewHolder(b.root) {

        fun update(dnsProxyEndpoint: DNSProxyEndpoint) {

            b.dnsProxyListUrlName.text = dnsProxyEndpoint.proxyName
            if (dnsProxyEndpoint.isSelected) {
                if (dnsProxyEndpoint.proxyAppName == context.getString(R.string.cd_custom_dns_proxy_default_app)) {
                    b.dnsProxyListUrlExplanation.text = context.getString(R.string.settings_socks_forwarding_desc, dnsProxyEndpoint.proxyIP, dnsProxyEndpoint.proxyPort.toString(), context.getString(R.string.cd_custom_dns_proxy_default_app))
                } else {
                    b.dnsProxyListUrlExplanation.text = context.getString(R.string.settings_socks_forwarding_desc, dnsProxyEndpoint.proxyIP, dnsProxyEndpoint.proxyPort.toString(), dnsProxyEndpoint.proxyAppName)

                }
            } else {
                if (dnsProxyEndpoint.proxyAppName == context.getString(R.string.cd_custom_dns_proxy_default_app)) {
                    b.dnsProxyListUrlExplanation.text = context.getString(R.string.dns_proxy_desc, dnsProxyEndpoint.proxyIP, dnsProxyEndpoint.proxyPort.toString(), context.getString(R.string.cd_custom_dns_proxy_default_app))
                } else {
                    b.dnsProxyListUrlExplanation.text = context.getString(R.string.dns_proxy_desc, dnsProxyEndpoint.proxyIP, dnsProxyEndpoint.proxyPort.toString(), dnsProxyEndpoint.proxyAppName)
                }
            }

            b.dnsProxyListCheckImage.isChecked = dnsProxyEndpoint.isSelected
            if (dnsProxyEndpoint.isCustom && !dnsProxyEndpoint.isSelected) {
                b.dnsProxyListActionImage.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_fab_uninstall))
            } else {
                b.dnsProxyListActionImage.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_fab_appinfo))
            }
            b.root.setOnClickListener {
                updateDNSProxyDetails(dnsProxyEndpoint)
                b.dnsProxyListCheckImage.isChecked = true
            }
            b.dnsProxyListActionImage.setOnClickListener {
                showExplanationOnImageClick(dnsProxyEndpoint)
            }

            b.dnsProxyListCheckImage.setOnClickListener {
                updateDNSProxyDetails(dnsProxyEndpoint)
                b.dnsProxyListCheckImage.isChecked = true
                b.dnsProxyListActionImage.setImageDrawable(AppCompatResources.getDrawable(context, R.drawable.ic_fab_appinfo))
            }
            b.root.setOnClickListener {
                updateDNSProxyDetails(dnsProxyEndpoint)
                b.dnsProxyListCheckImage.isChecked = true
            }
            b.dnsProxyListActionImage.setOnClickListener {
                showExplanationOnImageClick(dnsProxyEndpoint)
            }

            b.dnsProxyListCheckImage.setOnClickListener {
                updateDNSProxyDetails(dnsProxyEndpoint)
                b.dnsProxyListCheckImage.isChecked = true
            }
        }

        //checkBox.setOn

    }

    private fun showExplanationOnImageClick(dnsProxyEndpoint: DNSProxyEndpoint) {
        if (dnsProxyEndpoint.isCustom && !dnsProxyEndpoint.isSelected) showDialogForDelete(dnsProxyEndpoint)
        else {
            showDialogExplanation(dnsProxyEndpoint.proxyName, dnsProxyEndpoint.proxyAppName!!, dnsProxyEndpoint.proxyIP!!, dnsProxyEndpoint.proxyPort.toString())
        }
    }

    private fun showDialogExplanation(title: String, appName: String, url: String, message: String) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle(title)
        val app = appList[appName]?.appName

        if (app != null && !app.isNullOrEmpty()) {
            builder.setMessage(context.getString(R.string.dns_proxy_dialog_message, app, url, message))
        } else {
            builder.setMessage(context.getString(R.string.dns_proxy_dialog_message, context.getString(R.string.cd_custom_dns_proxy_default_app), url, message))
        }
        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton(context.getString(R.string.dns_info_positive)) { dialogInterface, _ ->
            dialogInterface.dismiss()
        }
        builder.setNeutralButton(context.getString(R.string.dns_info_neutral)) { _: DialogInterface, _: Int ->
            val clipboard: ClipboardManager? = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
            val clip = ClipData.newPlainText("URL", url)
            clipboard?.setPrimaryClip(clip)
            Utilities.showToastUiCentered(context, context.getString(R.string.info_dialog_copy_toast_msg), Toast.LENGTH_SHORT)
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    private fun showDialogForDelete(dnsProxyEndpoint: DNSProxyEndpoint?) {
        val builder = AlertDialog.Builder(context)
        builder.setTitle(R.string.dns_proxy_remove_dialog_title)
        builder.setMessage(R.string.dns_proxy_remove_dialog_message)

        builder.setCancelable(true)
        //performing positive action
        builder.setPositiveButton(context.getString(R.string.dns_delete_positive)) { _, _ ->
            GlobalScope.launch(Dispatchers.IO) {
                if (dnsProxyEndpoint != null) {
                    dnsProxyEndpointRepository.deleteDNSProxyEndpoint(dnsProxyEndpoint.id)
                }
            }
            Toast.makeText(context, R.string.dns_proxy_remove_success, Toast.LENGTH_SHORT).show()
        }

        //performing negative action
        builder.setNegativeButton(context.getString(R.string.dns_delete_negative)) { _, _ ->
        }
        // Create the AlertDialog
        val alertDialog: AlertDialog = builder.create()
        // Set other dialog properties
        alertDialog.setCancelable(true)
        alertDialog.show()
    }

    private fun updateDNSProxyDetails(dnsProxyEndpoint: DNSProxyEndpoint) {
        dnsProxyEndpoint.isSelected = true
        dnsProxyEndpointRepository.removeConnectionStatus()
        object : CountDownTimer(500, 500) {
            override fun onTick(millisUntilFinished: Long) {
            }

            override fun onFinish() {
                notifyDataSetChanged()
                queryTracker.reinitializeQuantileEstimator()
            }
        }.start()

        // Capture traffic for particular IP. Based on the setting. need to change the DNS
        // mode.
        if (dnsProxyEndpoint.proxyType == context.getString(R.string.cd_dns_proxy_mode_internal)) {
            HomeScreenActivity.GlobalVariable.appMode?.setDNSMode(Settings.DNSModeProxyIP)
        } else {
            HomeScreenActivity.GlobalVariable.appMode?.setDNSMode(Settings.DNSModeProxyIP)
        }
        listener.updateUIFromAdapter(3)
        persistentState.dnsType = Constants.PREF_DNS_MODE_PROXY
        persistentState.setConnectedDNS(dnsProxyEndpoint.proxyName)
        persistentState.connectionModeChange = dnsProxyEndpoint.proxyIP!!
        dnsProxyEndpointRepository.updateAsync(dnsProxyEndpoint)
    }
}
