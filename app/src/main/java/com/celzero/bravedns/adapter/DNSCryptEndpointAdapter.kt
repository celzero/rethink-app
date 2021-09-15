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
import androidx.lifecycle.lifecycleScope
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppMode
import com.celzero.bravedns.database.DNSCryptEndpoint
import com.celzero.bravedns.databinding.DnsCryptEndpointListItemBinding
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.*

class DNSCryptEndpointAdapter(private val context: Context, val lifecycleOwner: LifecycleOwner,
                              private val appMode: AppMode) :
        PagedListAdapter<DNSCryptEndpoint, DNSCryptEndpointAdapter.DNSCryptEndpointViewHolder>(
            DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DNSCryptEndpoint>() {

            override fun areItemsTheSame(oldConnection: DNSCryptEndpoint,
                                         newConnection: DNSCryptEndpoint): Boolean {
                return (oldConnection.id == newConnection.id && oldConnection.isSelected == newConnection.isSelected)
            }

            override fun areContentsTheSame(oldConnection: DNSCryptEndpoint,
                                            newConnection: DNSCryptEndpoint): Boolean {
                return (oldConnection.id == newConnection.id && oldConnection.isSelected != newConnection.isSelected)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DNSCryptEndpointViewHolder {
        val itemBinding = DnsCryptEndpointListItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false)
        return DNSCryptEndpointViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: DNSCryptEndpointViewHolder, position: Int) {
        val dnsCryptEndpoint: DNSCryptEndpoint = getItem(position) ?: return
        holder.update(dnsCryptEndpoint)
    }

    inner class DNSCryptEndpointViewHolder(private val b: DnsCryptEndpointListItemBinding) :
            RecyclerView.ViewHolder(b.root) {


        fun update(endpoint: DNSCryptEndpoint) {
            displayDetails(endpoint)
            setupClickListeners(endpoint)
        }

        private fun setupClickListeners(endpoint: DNSCryptEndpoint) {
            b.root.setOnClickListener {
                b.dnsCryptEndpointListActionImage.isChecked = !b.dnsCryptEndpointListActionImage.isChecked
                updateDNSCryptDetails(endpoint, b.dnsCryptEndpointListActionImage.isChecked)
            }

            b.dnsCryptEndpointListActionImage.setOnClickListener {
                updateDNSCryptDetails(endpoint, b.dnsCryptEndpointListActionImage.isChecked)
            }

            b.dnsCryptEndpointListInfoImage.setOnClickListener {
                showExplanationOnImageClick(endpoint)
            }
        }

        private fun displayDetails(endpoint: DNSCryptEndpoint) {
            b.dnsCryptEndpointListUrlName.text = endpoint.dnsCryptName
            b.dnsCryptEndpointListActionImage.isChecked = endpoint.isSelected

            b.dnsCryptEndpointListUrlExplanation.text = if (endpoint.isSelected) {
                context.getString(R.string.dns_connected)
            } else {
                ""
            }

            if (endpoint.isDeletable()) {
                b.dnsCryptEndpointListInfoImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_fab_uninstall))
            } else {
                b.dnsCryptEndpointListInfoImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_fab_appinfo))
            }
        }

        private fun showExplanationOnImageClick(dnsCryptEndpoint: DNSCryptEndpoint) {
            if (dnsCryptEndpoint.isDeletable()) showDeleteDialog(dnsCryptEndpoint.id)
            else {
                showDialogExplanation(dnsCryptEndpoint.dnsCryptName, dnsCryptEndpoint.dnsCryptURL,
                                      dnsCryptEndpoint.dnsCryptExplanation)
            }
        }

        private fun showDeleteDialog(id: Int) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.dns_crypt_custom_url_remove_dialog_title)
            builder.setMessage(R.string.dns_crypt_url_remove_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(context.getString(R.string.dns_delete_positive)) { _, _ ->
                deleteEndpoint(id)
            }

            builder.setNegativeButton(context.getString(R.string.dns_delete_negative)) { _, _ ->
            }
            val alertDialog: AlertDialog = builder.create()
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun showDialogExplanation(title: String, url: String, message: String?) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(title)
            if (message == null) builder.setMessage(url)
            else builder.setMessage(url + "\n\n" + message)
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

        private fun updateDNSCryptDetails(endpoint: DNSCryptEndpoint, isSelected: Boolean) {
            io {
                if (!isSelected && !appMode.canRemoveDnscrypt(endpoint)) {
                    // Do not unselect the only user-selected dnscrypt endpoint, that is
                    // when the getConnectedDnsCrypt returns a list of size 1
                    uiCtx {
                        Toast.makeText(context, context.getString(R.string.dns_select_toast),
                                       Toast.LENGTH_SHORT).show()
                        b.dnsCryptEndpointListActionImage.isChecked = true
                    }
                    return@io
                }

                endpoint.isSelected = isSelected
                appMode.handleDnscryptChanges(endpoint)
            }
        }

        private fun deleteEndpoint(id: Int) {
            io {
                appMode.deleteDnscryptEndpoint(id)
                uiCtx {
                    Toast.makeText(context, R.string.dns_crypt_url_remove_success,
                                   Toast.LENGTH_SHORT).show()
                }
            }
        }

        private suspend fun uiCtx(f: () -> Unit) {
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
