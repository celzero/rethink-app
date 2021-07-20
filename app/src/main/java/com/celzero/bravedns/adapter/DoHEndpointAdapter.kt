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

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.databinding.DohEndpointListItemBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.QueryTracker
import com.celzero.bravedns.ui.DNSConfigureWebViewActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.util.Constants.Companion.LOCATION_INTENT_EXTRA
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DOH
import com.celzero.bravedns.util.Constants.Companion.STAMP_INTENT_EXTRA
import com.celzero.bravedns.util.LoggerConstants.Companion.LOG_TAG_DNS
import com.celzero.bravedns.util.UIUpdateInterface
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import settings.Settings
import xdns.Xdns.getBlocklistStampFromURL


class DoHEndpointAdapter(private val context: Context,
                         private val doHEndpointRepository: DoHEndpointRepository,
                         private val persistentState: PersistentState, private val appMode: AppMode,
                         private val queryTracker: QueryTracker, val listener: UIUpdateInterface) :
        PagedListAdapter<DoHEndpoint, DoHEndpointAdapter.DoHEndpointViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DoHEndpoint>() {
            override fun areItemsTheSame(oldConnection: DoHEndpoint,
                                         newConnection: DoHEndpoint) = (oldConnection.id == newConnection.id && oldConnection.isSelected == newConnection.isSelected)

            override fun areContentsTheSame(oldConnection: DoHEndpoint,
                                            newConnection: DoHEndpoint) = oldConnection == newConnection
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DoHEndpointViewHolder {
        val itemBinding = DohEndpointListItemBinding.inflate(LayoutInflater.from(parent.context),
                                                             parent, false)
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
            b.root.setOnClickListener {
                updateConnection(endpoint)
            }
            b.dohEndpointListActionImage.setOnClickListener {
                showExplanationOnImageClick(endpoint)
            }
            b.dohEndpointListCheckImage.setOnClickListener {
                updateConnection(endpoint)
            }
            b.dohEndpointListConfigure.setOnClickListener {
                configureRethinkEndpoint(endpoint)
            }
        }

        private fun displayDetails(endpoint: DoHEndpoint) {
            b.dohEndpointListUrlName.text = endpoint.dohName
            b.dohEndpointListUrlExplanation.text = ""
            b.dohEndpointListCheckImage.isChecked = endpoint.isSelected
            Log.i(LOG_TAG_DNS,
                  "connected to doh - ${endpoint.dohName} isSelected? - ${endpoint.isSelected}")
            if (endpoint.isSelected) {
                val count = persistentState.numberOfRemoteBlocklists
                b.dohEndpointListUrlExplanation.text = if (endpoint.isRethinkDns() && count > 0) {
                    context.getString(R.string.dns_connected_rethink_plus, count.toString())
                } else {
                    context.getString(R.string.dns_connected)
                }
            }

            // Shows either the info/delete icon for the DoH entries.
            showIcon(endpoint)

            if (endpoint.isRethinkDns()) {
                b.dohEndpointListConfigure.visibility = View.VISIBLE
            } else {
                b.dohEndpointListConfigure.visibility = View.GONE
            }
        }

        private fun configureRethinkEndpoint(endpoint: DoHEndpoint) {
            val stamp = getRemoteBlocklistStamp(endpoint.dohURL)
            if (DEBUG) Log.d(LOG_TAG_DNS,
                             "calling configure webview activity with doh url: ${endpoint.dohURL},and stamp: $stamp")
            startConfigureBlocklistActivity(stamp)
        }

        private fun startConfigureBlocklistActivity(stamp: String) {
            val intent = Intent(context, DNSConfigureWebViewActivity::class.java)
            intent.putExtra(LOCATION_INTENT_EXTRA, DNSConfigureWebViewActivity.REMOTE)
            intent.putExtra(STAMP_INTENT_EXTRA, stamp)
            (context as Activity).startActivityForResult(intent, Activity.RESULT_OK)
        }

        private fun getRemoteBlocklistStamp(url: String): String {
            // Interacts with GO lib to fetch the stamp (Xdnx#getBlocklistStampFromURL)
            return try {
                getBlocklistStampFromURL(url)
            } catch (e: Exception) {
                Log.w(LOG_TAG_DNS, "failure fetching stamp from Go ${e.message}", e)
                ""
            }
        }

        private fun showIcon(endpoint: DoHEndpoint) {
            if (endpoint.isDeletable()) {
                b.dohEndpointListActionImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_fab_uninstall))
            } else {
                b.dohEndpointListActionImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_fab_appinfo))
            }
        }

        private fun updateConnection(endpoint: DoHEndpoint) {
            if (DEBUG) Log.d(LOG_TAG_DNS,
                             "updateConnection - ${endpoint.dohName}, ${endpoint.dohURL}")
            endpoint.dohURL = doHEndpointRepository.getConnectionURL(endpoint.id)

            if (!endpoint.isRethinkDns()) {
                updateDoHDetails(endpoint)
                b.dohEndpointListCheckImage.isChecked = true
                return
            }

            val stamp = getRemoteBlocklistStamp(endpoint.dohURL)
            if (DEBUG) Log.d(LOG_TAG_DNS, "stamp for remote endpoint- $stamp")
            if (stamp.isNullOrEmpty()) {
                showDohConfigureDialog()
                b.dohEndpointListCheckImage.isChecked = false
            } else {
                updateDoHDetails(endpoint)
                b.dohEndpointListCheckImage.isChecked = true
            }
        }

        private fun showExplanationOnImageClick(endpoint: DoHEndpoint) {
            if (endpoint.isDeletable()) showDeleteDnsDialog(endpoint)
            else showDohMetadataDialog(endpoint.dohName, endpoint.dohURL, endpoint.dohExplanation)
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
            val alertDialog: AlertDialog = builder.create()
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun showDeleteDnsDialog(endpoint: DoHEndpoint) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.doh_custom_url_remove_dialog_title)
            builder.setMessage(R.string.doh_custom_url_remove_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(context.getString(R.string.dns_delete_positive)) { _, _ ->
                GlobalScope.launch(Dispatchers.IO) {
                    doHEndpointRepository.deleteDoHEndpoint(endpoint.dohURL)
                }
                Toast.makeText(context, R.string.doh_custom_url_remove_success,
                               Toast.LENGTH_SHORT).show()
            }

            builder.setNegativeButton(context.getString(R.string.dns_delete_negative)) { _, _ ->

            }
            val alertDialog: AlertDialog = builder.create()
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun showDohConfigureDialog() {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.doh_brave_pro_configure)
            builder.setMessage(R.string.doh_brave_pro_configure_desc)
            builder.setCancelable(true)
            builder.setPositiveButton(
                context.getString(R.string.dns_connected_rethink_configure)) { _, _ ->
                startConfigureBlocklistActivity(/*empty stamp*/"")
            }

            builder.setNegativeButton(context.getString(R.string.dns_delete_negative)) { _, _ ->
                b.dohEndpointListCheckImage.isChecked = false
            }
            val alertDialog: AlertDialog = builder.create()
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun updateDoHDetails(endpoint: DoHEndpoint) {
            endpoint.isSelected = true
            doHEndpointRepository.removeConnectionStatus()
            Utilities.delay(1000) {
                notifyDataSetChanged()
                appMode.onNewDnsConnected(PREF_DNS_MODE_DOH, Settings.DNSModePort)
                queryTracker.reinitializeQuantileEstimator()
            }

            listener.updateUIFromAdapter(PREF_DNS_MODE_DOH)
            doHEndpointRepository.updateAsync(endpoint)
        }

    }
}
