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

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.DialogInterface
import android.os.CountDownTimer
import android.view.*
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatButton
import androidx.core.content.getSystemService
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DNSCryptEndpointRepository
import com.celzero.bravedns.database.DNSCryptRelayEndpoint
import com.celzero.bravedns.database.DNSCryptRelayEndpointRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.cryptRelayToRemove
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
            // Concert details may have changed if reloaded from the database,
            // but ID is fixed.
            override fun areItemsTheSame(oldConnection: DNSCryptRelayEndpoint, newConnection: DNSCryptRelayEndpoint) = oldConnection.id == newConnection.id

            override fun areContentsTheSame(oldConnection: DNSCryptRelayEndpoint, newConnection: DNSCryptRelayEndpoint) = oldConnection == newConnection
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DNSCryptRelayEndpointViewHolder {
        val v: View = LayoutInflater.from(parent.context).inflate(
            R.layout.dns_crypt_endpoint_list_item,
            parent, false
        )
        //v.setBackgroundColor(context.getColor(R.color.colorPrimary))
        return DNSCryptRelayEndpointViewHolder(v)
    }

    override fun onBindViewHolder(holder: DNSCryptRelayEndpointViewHolder, position: Int) {
        val dnsCryptRelayEndpoint: DNSCryptRelayEndpoint? = getItem(position)
        holder.update(dnsCryptRelayEndpoint)
    }


    inner class DNSCryptRelayEndpointViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // Overall view
        private var rowView: View? = null

        // Contents of the condensed view
        private var urlNameTxt : TextView
        private var urlExplanationTxt : TextView
        private var imageAction: CheckBox
        private var infoImage : ImageView


        init {
            rowView = itemView
            urlNameTxt = itemView.findViewById(R.id.dns_crypt_endpoint_list_url_name)
            urlExplanationTxt = itemView.findViewById(R.id.dns_crypt_endpoint_list_url_explanation)
            imageAction = itemView.findViewById(R.id.dns_crypt_endpoint_list_action_image)
            infoImage = itemView.findViewById(R.id.dns_crypt_endpoint_list_info_image)
        }

        fun update(dnsCryptRelayEndpoint: DNSCryptRelayEndpoint?) {
            if (dnsCryptRelayEndpoint != null) {
                //if(DEBUG) Log.d(LOG_TAG,"Update - dohName ==> ${dnsCryptRelayEndpoint.dnsCryptRelayURL}")
                urlNameTxt.text = dnsCryptRelayEndpoint.dnsCryptRelayName
                /*if(dnsCryptRelayEndpoint.isSelected && HomeScreenActivity.GlobalVariable.cryptModeInProgress == 2){
                    urlExplanationTxt.text = "Connected"
                } else if (dnsCryptRelayEndpoint.isSelected && HomeScreenActivity.GlobalVariable.cryptModeInProgress == 1) {
                    urlExplanationTxt.text = "Connecting.."
                } else */
                if (dnsCryptRelayEndpoint.isSelected) {
                    urlExplanationTxt.text = "Connected"
                } else {
                    urlExplanationTxt.text = ""
                }

                imageAction.isChecked = dnsCryptRelayEndpoint.isSelected
                if (dnsCryptRelayEndpoint.isCustom && !dnsCryptRelayEndpoint.isSelected) {
                    infoImage.setImageDrawable(context.getDrawable(R.drawable.ic_fab_uninstall))
                } else {
                    infoImage.setImageDrawable(context.getDrawable(R.drawable.ic_fab_appinfo))
                }
                rowView?.setOnClickListener {
                    //updateDNSCryptRelayDetails(dnsCryptRelayEndpoint)
                    imageAction.isChecked = !imageAction.isChecked
                    dnsCryptRelayEndpoint.isSelected = imageAction.isChecked
                    if(!dnsCryptRelayEndpoint.isSelected) {
                        cryptRelayToRemove = dnsCryptRelayEndpoint.dnsCryptRelayURL
                    }
                    val state  = updateDNSCryptRelayDetails(dnsCryptRelayEndpoint)
                    if(imageAction.isChecked && !state){
                        imageAction.isChecked = state
                    }
                }
                imageAction.setOnClickListener {
                    dnsCryptRelayEndpoint.isSelected = imageAction.isChecked
                    val state = updateDNSCryptRelayDetails(dnsCryptRelayEndpoint)
                    if (imageAction.isChecked && !state) {
                        imageAction.isChecked = state
                    }
                    //showExplanationOnImageClick(dnsCryptRelayEndpoint)
                }
                infoImage.setOnClickListener {
                    showExplanationOnImageClick(dnsCryptRelayEndpoint)
                }
            }

        }

        private fun showExplanationOnImageClick(dnsCryptRelayEndpoint: DNSCryptRelayEndpoint) {
            if (dnsCryptRelayEndpoint.isCustom && !dnsCryptRelayEndpoint.isSelected)
                showDialogForDelete(dnsCryptRelayEndpoint)
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
            builder.setPositiveButton("Ok") { dialogInterface, which ->
                dialogInterface.dismiss()
            }

            builder.setNeutralButton("Copy") { dialogInterface: DialogInterface, i: Int ->
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

        private fun showDialogForDelete(dnsCryptRelayEndpoint: DNSCryptRelayEndpoint) {
            val builder = AlertDialog.Builder(context)
            //set title for alert dialog
            builder.setTitle(R.string.dns_crypt_relay_remove_dialog_title)
            //set message for alert dialog
            builder.setMessage(R.string.dns_crypt_relay_remove_dialog_message)
            builder.setIcon(android.R.drawable.ic_dialog_alert)
            builder.setCancelable(true)
            //performing positive action
            builder.setPositiveButton("Delete") { dialogInterface, which ->
                GlobalScope.launch(Dispatchers.IO) {
                    if (dnsCryptRelayEndpoint != null) {
                        dnsCryptRelayEndpointRepository.deleteDNSCryptRelayEndpoint(dnsCryptRelayEndpoint.dnsCryptRelayURL)
                    }
                }
                Toast.makeText(context, R.string.dns_crypt_relay_remove_success, Toast.LENGTH_SHORT).show()
            }

            //performing negative action
            builder.setNegativeButton("Cancel") { dialogInterface, which ->
            }
            // Create the AlertDialog
            val alertDialog: AlertDialog = builder.create()
            // Set other dialog properties
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun showApplyDialog(dnsCryptRelayEndpoint : DNSCryptRelayEndpoint) {
            val dialog = Dialog(context)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setContentView(R.layout.dialog_bottom_apply_changes)
            val window: Window = dialog.window!!
            val wlp: WindowManager.LayoutParams = window.attributes
            wlp.width = WindowManager.LayoutParams.WRAP_CONTENT
            wlp.gravity = Gravity.BOTTOM
            wlp.flags = wlp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
            window.attributes = wlp

            val applyURLBtn = dialog.findViewById(R.id.dialog_bottom_apply_changes_ok_btn) as AppCompatButton
            val cancelURLBtn = dialog.findViewById(R.id.dialog_bottom_apply_changes_cancel_btn) as AppCompatButton

            applyURLBtn.setOnClickListener {
                updateDNSCryptRelayDetails(dnsCryptRelayEndpoint)
                dialog.dismiss()
            }

            cancelURLBtn.setOnClickListener {
                dialog.dismiss()
            }
            // Set other dialog properties
            dialog.show()

        }

        private fun updateDNSCryptRelayDetails(dnsCryptRelayEndpoint : DNSCryptRelayEndpoint) : Boolean{
            if (dnsCryptEndpointRepository.getConnectedCount() > 0) {
                dnsCryptRelayEndpointRepository.updateAsync(dnsCryptRelayEndpoint)
                object : CountDownTimer(500, 500) {
                    override fun onTick(millisUntilFinished: Long) {
                    }

                    override fun onFinish() {
                        notifyDataSetChanged()
                    }
                }.start()
                persistentState.dnsType = 2
                persistentState.connectionModeChange = dnsCryptRelayEndpoint.dnsCryptRelayURL
                //mDb.close()
                return true
            } else {
                Toast.makeText(context, "No resolver selected", Toast.LENGTH_LONG).show()
                return false
            }

        }
    }



}