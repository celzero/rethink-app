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
import androidx.paging.PagingDataAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.data.AppConfig
import com.celzero.bravedns.database.DnsCryptEndpoint
import com.celzero.bravedns.databinding.DnsCryptEndpointListItemBinding
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DnsCryptEndpointAdapter(
    private val context: Context, val lifecycleOwner: LifecycleOwner,
    private val appConfig: AppConfig
) :
    PagingDataAdapter<DnsCryptEndpoint, DnsCryptEndpointAdapter.DnsCryptEndpointViewHolder>(
        DIFF_CALLBACK
    ) {

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DnsCryptEndpoint>() {

            override fun areItemsTheSame(
                oldConnection: DnsCryptEndpoint,
                newConnection: DnsCryptEndpoint
            ): Boolean {
                return (oldConnection.id == newConnection.id && oldConnection.isSelected == newConnection.isSelected)
            }

            override fun areContentsTheSame(
                oldConnection: DnsCryptEndpoint,
                newConnection: DnsCryptEndpoint
            ): Boolean {
                return (oldConnection.id == newConnection.id && oldConnection.isSelected != newConnection.isSelected)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DnsCryptEndpointViewHolder {
        val itemBinding = DnsCryptEndpointListItemBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return DnsCryptEndpointViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: DnsCryptEndpointViewHolder, position: Int) {
        val dnsCryptEndpoint: DnsCryptEndpoint = getItem(position) ?: return
        holder.update(dnsCryptEndpoint)
    }

    inner class DnsCryptEndpointViewHolder(private val b: DnsCryptEndpointListItemBinding) :
        RecyclerView.ViewHolder(b.root) {


        fun update(endpoint: DnsCryptEndpoint) {
            displayDetails(endpoint)
            setupClickListeners(endpoint)
        }

        private fun setupClickListeners(endpoint: DnsCryptEndpoint) {
            b.root.setOnClickListener {
                b.dnsCryptEndpointListActionImage.isChecked =
                    !b.dnsCryptEndpointListActionImage.isChecked
                updateDnsCryptDetails(endpoint, b.dnsCryptEndpointListActionImage.isChecked)
            }

            b.dnsCryptEndpointListActionImage.setOnClickListener {
                updateDnsCryptDetails(endpoint, b.dnsCryptEndpointListActionImage.isChecked)
            }

            b.dnsCryptEndpointListInfoImage.setOnClickListener {
                showExplanationOnImageClick(endpoint)
            }
        }

        private fun displayDetails(endpoint: DnsCryptEndpoint) {
            b.dnsCryptEndpointListUrlName.text = endpoint.dnsCryptName
            b.dnsCryptEndpointListActionImage.isChecked = endpoint.isSelected

            b.dnsCryptEndpointListUrlExplanation.text = if (endpoint.isSelected) {
                context.getString(R.string.dns_connected)
            } else {
                ""
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

        private fun showExplanationOnImageClick(dnsCryptEndpoint: DnsCryptEndpoint) {
            if (dnsCryptEndpoint.isDeletable()) showDeleteDialog(dnsCryptEndpoint.id)
            else {
                showDialogExplanation(
                    dnsCryptEndpoint.dnsCryptName, dnsCryptEndpoint.dnsCryptURL,
                    dnsCryptEndpoint.dnsCryptExplanation
                )
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
                context.getString(R.string.dns_info_positive)
            ) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }

            builder.setNeutralButton(
                context.getString(R.string.dns_info_neutral)
            ) { _: DialogInterface, _: Int ->
                Utilities.clipboardCopy(
                    context, url,
                    context.getString(R.string.copy_clipboard_label)
                )
                Utilities.showToastUiCentered(
                    context, context.getString(
                        R.string.info_dialog_url_copy_toast_msg
                    ), Toast.LENGTH_SHORT
                )
            }
            val alertDialog: AlertDialog = builder.create()
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun updateDnsCryptDetails(endpoint: DnsCryptEndpoint, isSelected: Boolean) {
            io {
                if (!isSelected && !appConfig.canRemoveDnscrypt(endpoint)) {
                    // Do not unselect the only user-selected dnscrypt endpoint, that is
                    // when the getConnectedDnsCrypt returns a list of size 1
                    uiCtx {
                        Toast.makeText(
                            context, context.getString(R.string.dns_select_toast),
                            Toast.LENGTH_SHORT
                        ).show()
                        b.dnsCryptEndpointListActionImage.isChecked = true
                    }
                    return@io
                }

                endpoint.isSelected = isSelected
                appConfig.handleDnscryptChanges(endpoint)
            }
        }

        private fun deleteEndpoint(id: Int) {
            io {
                appConfig.deleteDnscryptEndpoint(id)
                uiCtx {
                    Toast.makeText(
                        context, R.string.dns_crypt_url_remove_success,
                        Toast.LENGTH_SHORT
                    ).show()
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
}
