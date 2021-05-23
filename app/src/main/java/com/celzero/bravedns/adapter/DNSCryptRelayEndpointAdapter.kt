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
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DNSCryptEndpointRepository
import com.celzero.bravedns.database.DNSCryptRelayEndpoint
import com.celzero.bravedns.database.DNSCryptRelayEndpointRepository
import com.celzero.bravedns.databinding.DnsCryptEndpointListItemBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.cryptRelayToRemove
import com.celzero.bravedns.util.Constants
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class DNSCryptRelayEndpointAdapter(
    private val context: Context,
    private val dnsCryptRelayEndpointRepository: DNSCryptRelayEndpointRepository,
    private val persistentState:PersistentState,
    private val dnsCryptEndpointRepository:DNSCryptEndpointRepository
) : PagedListAdapter<DNSCryptRelayEndpoint, DNSCryptRelayEndpointAdapter.DNSCryptRelayEndpointViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object :
            DiffUtil.ItemCallback<DNSCryptRelayEndpoint>() {
            override fun areItemsTheSame(oldConnection: DNSCryptRelayEndpoint, newConnection: DNSCryptRelayEndpoint) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: DNSCryptRelayEndpoint, newConnection: DNSCryptRelayEndpoint) = oldConnection == newConnection
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DNSCryptRelayEndpointViewHolder {
        val itemBinding = DnsCryptEndpointListItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return DNSCryptRelayEndpointViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: DNSCryptRelayEndpointViewHolder, position: Int) {
        val dnsCryptRelayEndpoint: DNSCryptRelayEndpoint = getItem(position) ?: return
        holder.update(dnsCryptRelayEndpoint)
    }


    inner class DNSCryptRelayEndpointViewHolder(private val b: DnsCryptEndpointListItemBinding) : RecyclerView.ViewHolder(b.root) {

        fun update(dnsCryptRelayEndpoint: DNSCryptRelayEndpoint) {
            b.dnsCryptEndpointListUrlName.text = dnsCryptRelayEndpoint.dnsCryptRelayName
            if (dnsCryptRelayEndpoint.isSelected) {
                b.dnsCryptEndpointListUrlExplanation.text = context.getString(R.string.dns_connected)
            } else {
                b.dnsCryptEndpointListUrlExplanation.text = ""
            }

            b.dnsCryptEndpointListActionImage.isChecked = dnsCryptRelayEndpoint.isSelected
            if (dnsCryptRelayEndpoint.isCustom && !dnsCryptRelayEndpoint.isSelected) {
                b.dnsCryptEndpointListInfoImage.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_fab_uninstall))
            } else {
                b.dnsCryptEndpointListInfoImage.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_fab_appinfo))
            }
            b.root.setOnClickListener {
                b.dnsCryptEndpointListActionImage.isChecked = !b.dnsCryptEndpointListActionImage.isChecked
                dnsCryptRelayEndpoint.isSelected = b.dnsCryptEndpointListActionImage.isChecked
                if (!dnsCryptRelayEndpoint.isSelected) {
                    cryptRelayToRemove = dnsCryptRelayEndpoint.dnsCryptRelayURL
                }
                val state = updateDNSCryptRelayDetails(dnsCryptRelayEndpoint)
                if (b.dnsCryptEndpointListActionImage.isChecked && !state) {
                    b.dnsCryptEndpointListActionImage.isChecked = state
                }

            }
            b.dnsCryptEndpointListActionImage.setOnClickListener {
                dnsCryptRelayEndpoint.isSelected = b.dnsCryptEndpointListActionImage.isChecked
                val state = updateDNSCryptRelayDetails(dnsCryptRelayEndpoint)
                if (b.dnsCryptEndpointListActionImage.isChecked && !state) {
                    b.dnsCryptEndpointListActionImage.isChecked = state
                }
                //showExplanationOnImageClick(dnsCryptRelayEndpoint)
            }
            b.dnsCryptEndpointListInfoImage.setOnClickListener {
                showExplanationOnImageClick(dnsCryptRelayEndpoint)
            }

        }

        private fun showExplanationOnImageClick(dnsCryptRelayEndpoint: DNSCryptRelayEndpoint) {
            if (dnsCryptRelayEndpoint.isCustom && !dnsCryptRelayEndpoint.isSelected) showDialogForDelete(dnsCryptRelayEndpoint)
            else {
                if (dnsCryptRelayEndpoint.dnsCryptRelayExplanation.isNullOrEmpty()) {
                    showDialogExplanation(dnsCryptRelayEndpoint.dnsCryptRelayName, dnsCryptRelayEndpoint.dnsCryptRelayURL, "")
                } else {
                    showDialogExplanation(dnsCryptRelayEndpoint.dnsCryptRelayName, dnsCryptRelayEndpoint.dnsCryptRelayURL, dnsCryptRelayEndpoint.dnsCryptRelayExplanation!!)
                }
            }
        }

        private fun showDialogExplanation(title: String, url: String, message: String) {
            val builder = AlertDialog.Builder(context)
            //set title for alert dialog
            builder.setTitle(title)
            //set message for alert dialog
            builder.setMessage(url + "\n\n" + message)
            builder.setCancelable(true)
            //performing positive action
            builder.setPositiveButton(context.getString(R.string.dns_info_positive)) { dialogInterface, _ ->
                dialogInterface.dismiss()
            }

            builder.setNeutralButton(context.getString(R.string.dns_info_neutral)) { _: DialogInterface, _: Int ->
                val clipboard: ClipboardManager? = context.getSystemService()
                val clip = ClipData.newPlainText("URL", url)
                clipboard?.setPrimaryClip(clip)
                Utilities.showToastInMidLayout(context, context.getString(R.string.info_dialog_copy_toast_msg), Toast.LENGTH_SHORT)
            }
            // Create the AlertDialog
            val alertDialog: AlertDialog = builder.create()
            // Set other dialog properties
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun showDialogForDelete(dnsCryptRelayEndpoint: DNSCryptRelayEndpoint?) {
            val builder = AlertDialog.Builder(context)
            //set title for alert dialog
            builder.setTitle(R.string.dns_crypt_relay_remove_dialog_title)
            //set message for alert dialog
            builder.setMessage(R.string.dns_crypt_relay_remove_dialog_message)
            builder.setCancelable(true)
            //performing positive action
            builder.setPositiveButton(context.getString(R.string.dns_delete_positive)) { _, _ ->
                GlobalScope.launch(Dispatchers.IO) {
                    if (dnsCryptRelayEndpoint != null) {
                        dnsCryptRelayEndpointRepository.deleteDNSCryptRelayEndpoint(dnsCryptRelayEndpoint.dnsCryptRelayURL)
                    }
                }
                Toast.makeText(context, R.string.dns_crypt_relay_remove_success, Toast.LENGTH_SHORT).show()
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

        private fun updateDNSCryptRelayDetails(dnsCryptRelayEndpoint: DNSCryptRelayEndpoint): Boolean {
            if (dnsCryptEndpointRepository.getConnectedCount() > 0) {
                dnsCryptRelayEndpointRepository.updateAsync(dnsCryptRelayEndpoint)
                object : CountDownTimer(500, 500) {
                    override fun onTick(millisUntilFinished: Long) {
                    }

                    override fun onFinish() {
                        notifyDataSetChanged()
                    }
                }.start()
                persistentState.dnsType = Constants.PREF_DNS_MODE_DNSCRYPT
                persistentState.connectionModeChange = dnsCryptRelayEndpoint.dnsCryptRelayURL
                return true
            } else {
                Toast.makeText(context, context.getString(R.string.dns_crypt_relay_error_toast), Toast.LENGTH_LONG).show()
                return false
            }

        }
    }


}