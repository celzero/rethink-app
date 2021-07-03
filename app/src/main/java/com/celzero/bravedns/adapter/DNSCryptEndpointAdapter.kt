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
import com.celzero.bravedns.database.DNSCryptEndpoint
import com.celzero.bravedns.database.DNSCryptEndpointRepository
import com.celzero.bravedns.databinding.DnsCryptEndpointListItemBinding
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.QueryTracker
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.util.Constants.Companion.PREF_DNS_MODE_DNSCRYPT
import com.celzero.bravedns.util.UIUpdateInterface
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import settings.Settings

class DNSCryptEndpointAdapter(private val context: Context,
                              private val dnsCryptEndpointRepository: DNSCryptEndpointRepository,
                              private val persistentState: PersistentState,
                              private val queryTracker: QueryTracker,
                              var listener: UIUpdateInterface) :
        PagedListAdapter<DNSCryptEndpoint, DNSCryptEndpointAdapter.DNSCryptEndpointViewHolder>(
            DIFF_CALLBACK) {


    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<DNSCryptEndpoint>() {

            override fun areItemsTheSame(oldConnection: DNSCryptEndpoint,
                                         newConnection: DNSCryptEndpoint) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: DNSCryptEndpoint,
                                            newConnection: DNSCryptEndpoint): Boolean {
                if (oldConnection.isSelected != newConnection.isSelected) {
                    return false
                }
                return oldConnection == newConnection
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


        fun update(dnsCryptEndpoint: DNSCryptEndpoint) {
            b.dnsCryptEndpointListUrlName.text = dnsCryptEndpoint.dnsCryptName
            if (dnsCryptEndpoint.isSelected) {
                b.dnsCryptEndpointListUrlExplanation.text = context.getString(
                    R.string.dns_connected)
            } else {
                b.dnsCryptEndpointListUrlExplanation.text = ""
            }

            if (dnsCryptEndpoint.isCustom && !dnsCryptEndpoint.isSelected) {
                b.dnsCryptEndpointListInfoImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_fab_uninstall))
            } else {
                b.dnsCryptEndpointListInfoImage.setImageDrawable(
                    ContextCompat.getDrawable(context, R.drawable.ic_fab_appinfo))
            }

            b.dnsCryptEndpointListActionImage.isChecked = dnsCryptEndpoint.isSelected
            b.root.setOnClickListener {
                b.dnsCryptEndpointListActionImage.isChecked = !b.dnsCryptEndpointListActionImage.isChecked
                dnsCryptEndpoint.isSelected = b.dnsCryptEndpointListActionImage.isChecked

                val state = updateDNSCryptDetails(dnsCryptEndpoint)
                if (!state) {
                    b.dnsCryptEndpointListActionImage.isChecked = !state
                }
            }
            b.dnsCryptEndpointListActionImage.setOnClickListener {
                dnsCryptEndpoint.isSelected = b.dnsCryptEndpointListActionImage.isChecked
                val state = updateDNSCryptDetails(dnsCryptEndpoint)
                if (!state) {
                    b.dnsCryptEndpointListActionImage.isChecked = !state
                }
            }
            b.dnsCryptEndpointListInfoImage.setOnClickListener {
                showExplanationOnImageClick(dnsCryptEndpoint)
            }
        }

        private fun showExplanationOnImageClick(dnsCryptEndpoint: DNSCryptEndpoint) {
            if (dnsCryptEndpoint.isCustom && !dnsCryptEndpoint.isSelected) showDialogForDelete(
                dnsCryptEndpoint)
            else {
                if (dnsCryptEndpoint.dnsCryptExplanation.isNullOrEmpty()) {
                    showDialogExplanation(dnsCryptEndpoint.dnsCryptName,
                                          dnsCryptEndpoint.dnsCryptURL, "")
                } else {
                    showDialogExplanation(dnsCryptEndpoint.dnsCryptName,
                                          dnsCryptEndpoint.dnsCryptURL,
                                          dnsCryptEndpoint.dnsCryptExplanation!!)
                }
            }
        }

        private fun showDialogForDelete(dnsCryptEndpoint: DNSCryptEndpoint?) {
            val builder = AlertDialog.Builder(context)
            builder.setTitle(R.string.dns_crypt_custom_url_remove_dialog_title)
            builder.setMessage(R.string.dns_crypt_url_remove_dialog_message)
            builder.setCancelable(true)
            builder.setPositiveButton(context.getString(R.string.dns_delete_positive)) { _, _ ->
                GlobalScope.launch(Dispatchers.IO) {
                    if (dnsCryptEndpoint != null) {
                        dnsCryptEndpointRepository.deleteDNSCryptEndpoint(
                            dnsCryptEndpoint.dnsCryptURL)
                    }
                }
                Toast.makeText(context, R.string.dns_crypt_url_remove_success,
                               Toast.LENGTH_SHORT).show()
            }

            builder.setNegativeButton(context.getString(R.string.dns_delete_negative)) { _, _ ->
            }
            val alertDialog: AlertDialog = builder.create()
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun showDialogExplanation(title: String, url: String, message: String) {
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
                val clipboard: ClipboardManager? = context.getSystemService()
                val clip = ClipData.newPlainText("URL", url)
                clipboard?.setPrimaryClip(clip)
                Utilities.showToastUiCentered(context, context.getString(
                    R.string.info_dialog_copy_toast_msg), Toast.LENGTH_SHORT)
            }
            val alertDialog: AlertDialog = builder.create()
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun updateDNSCryptDetails(dnsCryptEndpoint: DNSCryptEndpoint): Boolean {
            val list = dnsCryptEndpointRepository.getConnectedDNSCrypt()
            // Below check is to inform user not to unselect the only selected DNSCrypt.
            // This is true when the connected dns crypt list returned from the database is 1.
            if (list.size == 1 && !dnsCryptEndpoint.isSelected && list[0].dnsCryptURL == dnsCryptEndpoint.dnsCryptURL) {
                Toast.makeText(context, context.getString(R.string.dns_select_toast),
                               Toast.LENGTH_SHORT).show()
                return false
            }
            dnsCryptEndpointRepository.updateAsync(dnsCryptEndpoint)

            object : CountDownTimer(500, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    notifyDataSetChanged()
                    persistentState.dnsType = PREF_DNS_MODE_DNSCRYPT
                    val connectedDNS = dnsCryptEndpointRepository.getConnectedCount()
                    val text = context.getString(R.string.configure_dns_crypt,
                                                 connectedDNS.toString())
                    persistentState.setConnectedDNS(text)
                    queryTracker.reinitializeQuantileEstimator()
                }
            }.start()

            persistentState.connectionModeChange = dnsCryptEndpoint.dnsCryptURL
            listener.updateUIFromAdapter(PREF_DNS_MODE_DNSCRYPT)
            appMode?.setDNSMode(Settings.DNSModeCryptPort)

            return true
        }
    }
}
