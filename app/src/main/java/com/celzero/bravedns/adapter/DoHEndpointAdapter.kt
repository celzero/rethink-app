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
import android.content.*
import android.os.CountDownTimer
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.paging.PagedListAdapter
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.celzero.bravedns.R
import com.celzero.bravedns.database.DoHEndpoint
import com.celzero.bravedns.database.DoHEndpointRepository
import com.celzero.bravedns.service.PersistentState
import com.celzero.bravedns.service.QueryTracker
import com.celzero.bravedns.ui.DNSConfigureWebViewActivity
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.DEBUG
import com.celzero.bravedns.ui.HomeScreenActivity.GlobalVariable.appMode
import com.celzero.bravedns.util.Constants.Companion.LOG_TAG
import com.celzero.bravedns.util.Constants.Companion.RETHINK_DNS_PLUS
import com.celzero.bravedns.util.UIUpdateInterface
import com.celzero.bravedns.util.Utilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import settings.Settings
import xdns.Xdns.getBlocklistStampFromURL


class DoHEndpointAdapter(private val context: Context,
                         private val doHEndpointRepository: DoHEndpointRepository,
                         private val persistentState:PersistentState,
                         private val queryTracker: QueryTracker,
                         val listener: UIUpdateInterface) : PagedListAdapter<DoHEndpoint, DoHEndpointAdapter.DoHEndpointViewHolder>(DIFF_CALLBACK) {

    companion object {
        private val DIFF_CALLBACK = object :
            DiffUtil.ItemCallback<DoHEndpoint>() {
            // Concert details may have changed if reloaded from the database,
            // but ID is fixed.
            override fun areItemsTheSame(oldConnection: DoHEndpoint, newConnection: DoHEndpoint) = oldConnection.id == newConnection.id
            override fun areContentsTheSame(oldConnection: DoHEndpoint, newConnection: DoHEndpoint) = oldConnection == newConnection
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): DoHEndpointViewHolder {
        val v: View = LayoutInflater.from(parent.context).inflate(R.layout.doh_endpoint_list_item, parent, false)
        //v.setBackgroundColor(context.getColor(R.color.colorPrimary))
        return DoHEndpointViewHolder(v)
    }

    override fun onBindViewHolder(holder: DoHEndpointViewHolder, position: Int) {
        val doHEndpoint: DoHEndpoint? = getItem(position)
        holder.update(doHEndpoint)
    }


    inner class DoHEndpointViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        // Overall view
        private var rowView: View? = null

        // Contents of the condensed view
        private var urlNameTxt: TextView
        private var urlExplanationTxt: TextView
        private var imageAction: ImageView
        private var checkBox: AppCompatCheckBox
        private var configureBtn: Button


        init {
            rowView = itemView
            urlNameTxt = itemView.findViewById(R.id.doh_endpoint_list_url_name)
            urlExplanationTxt = itemView.findViewById(R.id.doh_endpoint_list_url_explanation)
            imageAction = itemView.findViewById(R.id.doh_endpoint_list_action_image)
            checkBox = itemView.findViewById(R.id.doh_endpoint_list_check_image)
            configureBtn = itemView.findViewById(R.id.doh_endpoint_list_configure)
        }

        fun update(doHEndpoint: DoHEndpoint?) {
            if (doHEndpoint != null) {
                //if(DEBUG) Log.d(LOG_TAG, "Update - dohName ==> ${doHEndpoint.dohURL}")
                urlNameTxt.text = doHEndpoint.dohName
                if (doHEndpoint.isSelected) {
                    urlExplanationTxt.text = "Connected."
                    Log.d(LOG_TAG, "DOH Endpoint connected - ${doHEndpoint.dohName}")
                    if(doHEndpoint.dohName == RETHINK_DNS_PLUS){
                        val count = persistentState.numberOfRemoteBlocklists
                        Log.d(LOG_TAG, "DOH Endpoint connected - ${doHEndpoint.dohName}, count- $count")
                        if (count != 0) {
                            urlExplanationTxt.text = "Connected. $count blocklists in-use."
                        }
                    }
                } else {
                    urlExplanationTxt.text = ""
                }
                checkBox.isChecked = doHEndpoint.isSelected
                if (doHEndpoint.isCustom && !doHEndpoint.isSelected) {
                    imageAction.setImageDrawable(context.getDrawable(R.drawable.ic_fab_uninstall))
                } else {
                    imageAction.setImageDrawable(context.getDrawable(R.drawable.ic_fab_appinfo))
                }
                if (doHEndpoint.dohName == RETHINK_DNS_PLUS) {

                    configureBtn.visibility = View.VISIBLE
                } else {
                    configureBtn.visibility = View.GONE
                }
                rowView?.setOnClickListener {
                    //TODO - Move the string in a common place and remove the literal.
                    //Maybe to strings.xml or to a Constant file in Util class
                    updateConnection(doHEndpoint)
                }
                imageAction.setOnClickListener {
                    showExplanationOnImageClick(doHEndpoint)
                }
                checkBox.setOnClickListener {
                    updateConnection(doHEndpoint)
                }
                configureBtn.setOnClickListener {
                    var stamp = ""
                    try {
                        stamp = getBlocklistStampFromURL(doHEndpoint.dohURL)
                        if(DEBUG) Log.d(LOG_TAG, "Configure btn click: ${doHEndpoint.dohURL}, $stamp")
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Exception while fetching stamp from Go ${e.message}", e)
                    }
                    if(DEBUG) Log.d(LOG_TAG, "startActivityForResult - DohEndpointadapter")
                    val intent = Intent(context, DNSConfigureWebViewActivity::class.java)
                    intent.putExtra("location", DNSConfigureWebViewActivity.REMOTE)
                    intent.putExtra("stamp", stamp)
                    (context as Activity).startActivityForResult(intent, Activity.RESULT_OK)
                }
            }
        }


        private fun updateConnection(doHEndpoint: DoHEndpoint) {
            if(DEBUG) Log.d(LOG_TAG, "updateConnection - ${doHEndpoint.dohName}, ${doHEndpoint.dohURL}")
            doHEndpoint.dohURL = doHEndpointRepository.getConnectionURL(doHEndpoint.id)
            if (doHEndpoint.dohName == RETHINK_DNS_PLUS) {
                var stamp = ""
                try {
                    stamp = getBlocklistStampFromURL(doHEndpoint.dohURL)
                } catch (e: Exception) {
                    Log.e(LOG_TAG, "Exception while fetching stamp from Go ${e.message}", e)
                }
                if(DEBUG) Log.d(LOG_TAG, "updateConnection - $stamp")
                if (stamp.isEmpty()) {
                    showDialogToConfigure()
                    checkBox.isChecked = false
                }else{
                    updateDoHDetails(doHEndpoint)
                    checkBox.isChecked = true
                }
            } else {
                updateDoHDetails(doHEndpoint)
                checkBox.isChecked = true
            }
            //mDb.close()
        }

        private fun showExplanationOnImageClick(doHEndpoint: DoHEndpoint) {
            if (doHEndpoint.isCustom && !doHEndpoint.isSelected)
                showDialogToDelete(doHEndpoint)
            else {
                if (doHEndpoint.dohExplanation.isNullOrEmpty()) {
                    showDialogExplanation(doHEndpoint.dohName, doHEndpoint.dohURL, "")
                    //Toast.makeText(context, doHEndpoint.dohURL, Toast.LENGTH_SHORT).show()
                } else {
                    showDialogExplanation(doHEndpoint.dohName, doHEndpoint.dohURL, doHEndpoint.dohExplanation!!)
                    //Toast.makeText(context, doHEndpoint.dohExplanation, Toast.LENGTH_SHORT).show()
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
            builder.setNeutralButton("Copy"){ dialogInterface: DialogInterface, i: Int ->
                val clipboard: ClipboardManager? = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager?
                val clip = ClipData.newPlainText("URL", url)
                clipboard?.setPrimaryClip(clip)
                Utilities.showToastInMidLayout(context,context.getString(R.string.info_dialog_copy_toast_msg),Toast.LENGTH_SHORT)
            }
            // Create the AlertDialog
            val alertDialog: AlertDialog = builder.create()
            // Set other dialog properties
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun showDialogToDelete(doHEndpoint: DoHEndpoint) {
            val builder = AlertDialog.Builder(context)
            //set title for alert dialog
            builder.setTitle(R.string.doh_custom_url_remove_dialog_title)
            //set message for alert dialog
            builder.setMessage(R.string.doh_custom_url_remove_dialog_message)
            builder.setIcon(android.R.drawable.ic_dialog_alert)
            builder.setCancelable(true)
            //performing positive action
            builder.setPositiveButton("Delete") { dialogInterface, which ->
                GlobalScope.launch(Dispatchers.IO) {
                    doHEndpointRepository.deleteDoHEndpoint(doHEndpoint.dohURL)
                }
                Toast.makeText(context, R.string.doh_custom_url_remove_success, Toast.LENGTH_SHORT).show()
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

        private fun showDialogToConfigure() {
            val builder = AlertDialog.Builder(context)
            //set title for alert dialog
            builder.setTitle(R.string.doh_brave_pro_configure)
            //set message for alert dialog
            builder.setMessage(R.string.doh_brave_pro_configure_desc)
            builder.setCancelable(true)
            //performing positive action
            builder.setPositiveButton("configure") { dialogInterface, which ->
                val intent = Intent(context, DNSConfigureWebViewActivity::class.java)
                intent.putExtra("location", DNSConfigureWebViewActivity.REMOTE)
                intent.putExtra("stamp", "")
                (context as Activity).startActivityForResult(intent, Activity.RESULT_OK)

            }

            //performing negative action
            builder.setNegativeButton("Cancel") { dialogInterface, which ->
                checkBox.isChecked = false
            }
            // Create the AlertDialog
            val alertDialog: AlertDialog = builder.create()
            // Set other dialog properties
            alertDialog.setCancelable(true)
            alertDialog.show()
        }

        private fun updateDoHDetails(doHEndpoint: DoHEndpoint) {
            doHEndpoint.isSelected = true
            doHEndpointRepository.removeConnectionStatus()
            doHEndpointRepository.updateAsync(doHEndpoint)
            object : CountDownTimer(1000, 500) {
                override fun onTick(millisUntilFinished: Long) {
                }

                override fun onFinish() {
                    notifyDataSetChanged()
                    persistentState.dnsType = 1
                    persistentState.connectionModeChange = doHEndpoint.dohURL
                    queryTracker.reinitializeQuantileEstimator()
                }
            }.start()
            appMode?.setDNSMode(Settings.DNSModePort)
            listener.updateUIFromAdapter(1)
            //mDb.close()
        }
    }


}